package com.xinghe.cms.review.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.review.vo.ReviewRecordVO;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 审核历史真实 MySQL 验证（显式通过 -Dtest=ReviewQueryServiceMysqlIT 运行）：
 * 1. cms_review 与 sys_user 的 JOIN、reviewerName 映射；
 * 2. reviewed_at DESC, id DESC 排序（同秒靠 id 决定次序）；
 * 3. 分页 total/current/size；
 * 4. 复用 C 的 ArticleAccessService#requireReadable 读取范围（本人可读、他人草稿 403、文章不存在 404）。
 *
 * <p>测试库隔离（PR #30 复审要求）：</p>
 * <ul>
 *   <li>使用独立、可丢弃的 {@value #IT_DATABASE} 库，启动前自动创建并按
 *       {@code database/001_init.sql} 建表；</li>
 *   <li>所有测试数据使用数据库生成的 ID，不按固定 ID 预清理，避免误删；
 *       测试方法整体在事务中执行，结束后自动回滚，不残留数据；</li>
 *   <li>不会触碰业务库 {@code enterprise_cms} 中的任何记录。</li>
 * </ul>
 *
 * <p>类名以 IT 结尾，默认 surefire 不包含，避免每次 mvn test 都依赖真实库。</p>
 */
@SpringBootTest
@Transactional
class ReviewQueryServiceMysqlIT {

    private static final String IT_DATABASE = "enterprise_cms_it";
    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/enterprise_cms?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    /** 未被插入的缺失文章 ID（仅用于 404 场景，测试事务回滚不会写入任何数据）。 */
    private static final long MISSING_ARTICLE_ID = 990299L;

    static {
        provisionItDatabase();
    }

    @DynamicPropertySource
    static void isolatedDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ReviewQueryServiceMysqlIT::itUrl);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewQueryService reviewQueryService;

    private long authorId;
    private long otherUserId;
    private long categoryId;
    private long articleId;
    private long emptyArticleId;
    private long oldReviewId;
    private long sameSecondReviewId;
    private long newestReviewId;

    @BeforeEach
    void seed() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("集成测试必须运行在独立测试库")
                .isEqualTo(IT_DATABASE);

        authorId = insertAndReturnId("INSERT INTO sys_user "
                        + "(username, password_hash, real_name, department_id, status) VALUES (?, ?, ?, NULL, 1)",
                "it_author", "not-a-real-hash", "IT审核人");
        otherUserId = insertAndReturnId("INSERT INTO sys_user "
                        + "(username, password_hash, real_name, department_id, status) VALUES (?, ?, ?, NULL, 1)",
                "it_other", "not-a-real-hash", "IT他人");
        categoryId = insertAndReturnId("INSERT INTO cms_category "
                        + "(parent_id, category_name, sort_order, status) VALUES (NULL, ?, 0, 1)",
                "IT分类");
        articleId = insertDraft("IT审核文章");
        emptyArticleId = insertDraft("IT无审核文章");

        oldReviewId = insertReview("APPROVED", null, LocalDateTime.of(2026, 11, 10, 10, 0, 0));
        sameSecondReviewId = insertReview("REJECTED", "驳回：内容不完整",
                LocalDateTime.of(2026, 11, 10, 10, 0, 0));
        newestReviewId = insertReview("APPROVED", null, LocalDateTime.of(2026, 11, 11, 9, 0, 0));

        bindCurrentUser(authorId, "it_author");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void joinsReviewerNameAndSortsByReviewedAtThenIdDesc() {
        PageResult<ReviewRecordVO> result = reviewQueryService.page(articleId, new PageQuery());

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records())
                .extracting(ReviewRecordVO::getId, ReviewRecordVO::getArticleId, ReviewRecordVO::getReviewerId,
                        ReviewRecordVO::getReviewerName, ReviewRecordVO::getResult, ReviewRecordVO::getComment)
                .containsExactly(
                        tuple(newestReviewId, articleId, authorId, "IT审核人", "APPROVED", null),
                        tuple(sameSecondReviewId, articleId, authorId, "IT审核人", "REJECTED", "驳回：内容不完整"),
                        tuple(oldReviewId, articleId, authorId, "IT审核人", "APPROVED", null));
        assertThat(result.records().get(0).getReviewedAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 11, 9, 0, 0));
    }

    @Test
    void paginatesByRequestedPage() {
        PageQuery query = new PageQuery();
        query.setCurrent(2);
        query.setSize(2);

        PageResult<ReviewRecordVO> result = reviewQueryService.page(articleId, query);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.records()).extracting(ReviewRecordVO::getId).containsExactly(oldReviewId);
    }

    @Test
    void returnsEmptyPageWhenArticleHasNoReviews() {
        PageResult<ReviewRecordVO> result = reviewQueryService.page(emptyArticleId, new PageQuery());

        assertThat(result.total()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void rejectsMissingArticleWithNotFound() {
        assertThatThrownBy(() -> reviewQueryService.page(MISSING_ARTICLE_ID, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章不存在");
    }

    @Test
    void forbidsReadingOthersDraft() {
        bindCurrentUser(otherUserId, "it_other");

        assertThatThrownBy(() -> reviewQueryService.page(articleId, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无访问权限");
    }

    // ---------------------------------------------------------------- 造数（数据库生成 ID）

    private long insertDraft(String title) {
        return insertAndReturnId("INSERT INTO cms_article "
                        + "(title, summary, content, category_id, author_id, status, visibility, version) "
                        + "VALUES (?, NULL, ?, ?, ?, 'DRAFT', 'ALL', 0)",
                title, "正文内容", categoryId, authorId);
    }

    private long insertReview(String result, String comment, LocalDateTime reviewedAt) {
        return insertAndReturnId("INSERT INTO cms_review "
                        + "(article_id, reviewer_id, result, comment, reviewed_at) VALUES (?, ?, ?, ?, ?)",
                articleId, authorId, result, comment, Timestamp.valueOf(reviewedAt));
    }

    private long insertAndReturnId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键：" + sql);
        }
        return key.longValue();
    }

    private void bindCurrentUser(long userId, String username) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(userId, username, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // ---------------------------------------------------------------- 独立测试库准备

    private static void provisionItDatabase() {
        String username = envOr("SPRING_DATASOURCE_USERNAME", "root");
        String password = envOr("SPRING_DATASOURCE_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(serverUrl(), username, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + IT_DATABASE
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            Path script = locateInitScript();
            String sql = Files.readString(script, StandardCharsets.UTF_8).replace("enterprise_cms", IT_DATABASE);
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("无法准备独立测试库 " + IT_DATABASE + "，请确认 MySQL 可连接", exception);
        }
    }

    private static Path locateInitScript() {
        Path fromBackend = Path.of("..", "database", "001_init.sql");
        if (Files.exists(fromBackend)) {
            return fromBackend;
        }
        Path fromRoot = Path.of("database", "001_init.sql");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("找不到 database/001_init.sql，无法初始化独立测试库");
    }

    private static String itUrl() {
        return baseUrl() + IT_DATABASE + queryPart();
    }

    private static String serverUrl() {
        return baseUrl();
    }

    private static String baseUrl() {
        String url = envOr("SPRING_DATASOURCE_URL", DEFAULT_URL);
        String withoutQuery = url;
        int query = url.indexOf('?');
        if (query >= 0) {
            withoutQuery = url.substring(0, query);
        }
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1);
    }

    private static String queryPart() {
        String url = envOr("SPRING_DATASOURCE_URL", DEFAULT_URL);
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(query) : "";
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
