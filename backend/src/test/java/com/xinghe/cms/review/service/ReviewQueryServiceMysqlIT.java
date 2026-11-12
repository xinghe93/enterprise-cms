package com.xinghe.cms.review.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
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
 * 类名以 IT 结尾，默认 surefire 不包含，避免每次 mvn test 都依赖真实库。
 */
@SpringBootTest
class ReviewQueryServiceMysqlIT {

    private static final long AUTHOR_ID = 991201L;
    private static final long OTHER_USER_ID = 991202L;
    private static final long CATEGORY_ID = 991101L;
    private static final long ARTICLE_ID = 990201L;
    private static final long EMPTY_ARTICLE_ID = 990202L;
    private static final long MISSING_ARTICLE_ID = 990299L;

    private static final long REVIEW_OLD_ID = 991301L;
    private static final long REVIEW_SAME_SECOND_ID = 991302L;
    private static final long REVIEW_NEWEST_ID = 991303L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewQueryService reviewQueryService;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.update("INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status) "
                + "VALUES (?, ?, ?, ?, NULL, 1)", AUTHOR_ID, "it_author", "not-a-real-hash", "IT审核人");
        jdbc.update("INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status) "
                + "VALUES (?, ?, ?, ?, NULL, 1)", OTHER_USER_ID, "it_other", "not-a-real-hash", "IT他人");
        jdbc.update("INSERT INTO cms_category (id, parent_id, category_name, sort_order, status) "
                + "VALUES (?, NULL, ?, 0, 1)", CATEGORY_ID, "IT分类");
        insertDraft(ARTICLE_ID, "IT审核文章");
        insertDraft(EMPTY_ARTICLE_ID, "IT无审核文章");

        insertReview(REVIEW_OLD_ID, "APPROVED", null, LocalDateTime.of(2026, 11, 10, 10, 0, 0));
        insertReview(REVIEW_SAME_SECOND_ID, "REJECTED", "驳回：内容不完整",
                LocalDateTime.of(2026, 11, 10, 10, 0, 0));
        insertReview(REVIEW_NEWEST_ID, "APPROVED", null, LocalDateTime.of(2026, 11, 11, 9, 0, 0));

        bindCurrentUser(AUTHOR_ID, "it_author");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        cleanup();
    }

    @Test
    void joinsReviewerNameAndSortsByReviewedAtThenIdDesc() {
        PageResult<ReviewRecordVO> result = reviewQueryService.page(ARTICLE_ID, new PageQuery());

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.records())
                .extracting(ReviewRecordVO::getId, ReviewRecordVO::getArticleId, ReviewRecordVO::getReviewerId,
                        ReviewRecordVO::getReviewerName, ReviewRecordVO::getResult, ReviewRecordVO::getComment)
                .containsExactly(
                        tuple(REVIEW_NEWEST_ID, ARTICLE_ID, AUTHOR_ID, "IT审核人", "APPROVED", null),
                        tuple(REVIEW_SAME_SECOND_ID, ARTICLE_ID, AUTHOR_ID, "IT审核人", "REJECTED", "驳回：内容不完整"),
                        tuple(REVIEW_OLD_ID, ARTICLE_ID, AUTHOR_ID, "IT审核人", "APPROVED", null));
        assertThat(result.records().get(0).getReviewedAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 11, 9, 0, 0));
    }

    @Test
    void paginatesByRequestedPage() {
        PageQuery query = new PageQuery();
        query.setCurrent(2);
        query.setSize(2);

        PageResult<ReviewRecordVO> result = reviewQueryService.page(ARTICLE_ID, query);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.records()).extracting(ReviewRecordVO::getId).containsExactly(REVIEW_OLD_ID);
    }

    @Test
    void returnsEmptyPageWhenArticleHasNoReviews() {
        PageResult<ReviewRecordVO> result = reviewQueryService.page(EMPTY_ARTICLE_ID, new PageQuery());

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
        bindCurrentUser(OTHER_USER_ID, "it_other");

        assertThatThrownBy(() -> reviewQueryService.page(ARTICLE_ID, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无访问权限");
    }

    private void insertDraft(long articleId, String title) {
        jdbc.update("INSERT INTO cms_article (id, title, summary, content, category_id, author_id, status, visibility, version) "
                + "VALUES (?, ?, NULL, ?, ?, ?, 'DRAFT', 'ALL', 0)",
                articleId, title, "正文内容", CATEGORY_ID, AUTHOR_ID);
    }

    private void insertReview(long reviewId, String result, String comment, LocalDateTime reviewedAt) {
        jdbc.update("INSERT INTO cms_review (id, article_id, reviewer_id, result, comment, reviewed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                reviewId, ARTICLE_ID, AUTHOR_ID, result, comment, Timestamp.valueOf(reviewedAt));
    }

    private void bindCurrentUser(long userId, String username) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(userId, username, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void cleanup() {
        jdbc.update("DELETE FROM cms_review WHERE article_id IN (?, ?)", ARTICLE_ID, EMPTY_ARTICLE_ID);
        jdbc.update("DELETE FROM cms_article WHERE id IN (?, ?)", ARTICLE_ID, EMPTY_ARTICLE_ID);
        jdbc.update("DELETE FROM cms_category WHERE id = ?", CATEGORY_ID);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?, ?)", AUTHOR_ID, OTHER_USER_ID);
    }
}
