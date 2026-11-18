package com.xinghe.cms.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.content.entity.Category;
import com.xinghe.cms.content.mapper.CategoryMapper;
import com.xinghe.cms.content.service.CategoryService;
import com.xinghe.cms.review.dto.PendingReviewQuery;
import com.xinghe.cms.review.mapper.PendingReviewMapper;
import com.xinghe.cms.review.mapper.PendingReviewRow;
import com.xinghe.cms.review.service.impl.PendingReviewServiceImpl;
import com.xinghe.cms.review.vo.ArticleSummaryVO;
import com.xinghe.cms.security.CurrentUserProvider;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 待审分页服务的条件解析、关键词转义、标签组装与分页元数据测试；
 * 不连接数据库（Mapper / ArticleTagService / CategoryMapper 用 JDK 动态代理替身）。
 */
class PendingReviewServiceTests {

    private static final long REVIEWER_ID = 990201L;
    private static final long ROOT_CATEGORY_ID = 991101L;
    private static final long CHILD_CATEGORY_ID = 991102L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);

    private final AtomicReference<Long> capturedReviewerId = new AtomicReference<>();
    private final AtomicReference<Collection<Long>> capturedCategoryIds = new AtomicReference<>();
    private final AtomicReference<Long> capturedAuthorId = new AtomicReference<>();
    private final AtomicReference<String> capturedKeyword = new AtomicReference<>();
    private final AtomicReference<Boolean> mapperCalled = new AtomicReference<>(false);
    private List<PendingReviewRow> rows = List.of();
    private long total = 0;
    private Map<Long, List<TagView>> tagViews = Map.of();

    private PendingReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        CategoryService categoryService = new CategoryService(categoryMapper());
        ArticleTagService articleTagService = articleTagService();
        service = new PendingReviewServiceImpl(realCurrentUserProvider(), categoryService,
                articleTagService, mapper());
        bindCurrentUser(REVIEWER_ID);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void passesReviewerIdAndExpandsCategorySubtree() {
        rows = List.of(row(990201L, "待审文章", ROOT_CATEGORY_ID, 991201L));
        total = 3;
        PendingReviewQuery query = query();
        query.setCategoryId(String.valueOf(ROOT_CATEGORY_ID));
        query.setAuthorId("991203");
        query.setKeyword("  100%  ");
        query.setCurrent(2);
        query.setSize(3);

        PageResult<ArticleSummaryVO> result = service.pending(query);

        assertThat(mapperCalled.get()).isTrue();
        assertThat(capturedReviewerId.get()).isEqualTo(REVIEWER_ID);
        assertThat(capturedCategoryIds.get()).containsExactlyInAnyOrder(ROOT_CATEGORY_ID, CHILD_CATEGORY_ID);
        assertThat(capturedAuthorId.get()).isEqualTo(991203L);
        assertThat(capturedKeyword.get()).isEqualTo("%100!%%");
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(3);
    }

    @Test
    void blankFiltersBecomeNull() {
        PendingReviewQuery query = query();
        query.setKeyword("   ");
        query.setCategoryId(" ");
        query.setAuthorId("");

        service.pending(query);

        assertThat(capturedCategoryIds.get()).isNull();
        assertThat(capturedAuthorId.get()).isNull();
        assertThat(capturedKeyword.get()).isNull();
    }

    @Test
    void returnsEmptyPageWithoutQueryingMapperWhenCategoryHasNoDescendants() {
        PendingReviewQuery query = query();
        query.setCategoryId("999999");
        query.setCurrent(3);
        query.setSize(5);

        PageResult<ArticleSummaryVO> result = service.pending(query);

        assertThat(mapperCalled.get()).isFalse();
        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.current()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(5);
    }

    @Test
    void escapesBangPercentAndUnderscoreLiterally() {
        PendingReviewQuery query = query();
        query.setKeyword("a!b_c%d");

        service.pending(query);

        assertThat(capturedKeyword.get()).isEqualTo("%a!!b!_c!%d%");
    }

    @Test
    void rejectsKeywordLongerThan255CodePoints() {
        PendingReviewQuery query = query();
        query.setKeyword("请".repeat(256));

        assertThatThrownBy(() -> service.pending(query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("关键词长度不能超过 255");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void rejectsKeywordLongerThan255BeforeStripping() {
        PendingReviewQuery query = query();
        // 原始值 258 个 code point，去掉首尾空白后只剩 254 个：
        // OpenAPI maxLength 作用于原始值，必须先按原始长度拒绝，不能 strip 后放行。
        query.setKeyword("  " + "请".repeat(254) + "  ");

        assertThatThrownBy(() -> service.pending(query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("关键词长度不能超过 255");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void acceptsKeywordWithExactly255CodePoints() {
        PendingReviewQuery query = query();
        query.setKeyword("请".repeat(255));

        service.pending(query);

        assertThat(capturedKeyword.get()).isEqualTo("%" + "请".repeat(255) + "%");
    }

    @Test
    void rejectsMalformedAuthorId() {
        PendingReviewQuery query = query();
        query.setAuthorId("0x1");

        assertThatThrownBy(() -> service.pending(query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("资源 ID 格式不正确");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void rejectsOverlongKeywordEvenWhenCategoryDoesNotExist() {
        PendingReviewQuery query = query();
        query.setCategoryId("999999");
        query.setKeyword("请".repeat(256));

        assertThatThrownBy(() -> service.pending(query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("关键词长度不能超过 255");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void rejectsMalformedAuthorIdEvenWhenCategoryDoesNotExist() {
        PendingReviewQuery query = query();
        query.setCategoryId("999999");
        query.setAuthorId("0x1");

        assertThatThrownBy(() -> service.pending(query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("资源 ID 格式不正确");
        assertThat(mapperCalled.get()).isFalse();
    }

    @Test
    void assemblesTagsInAscendingOrderIncludingDisabled() {
        rows = List.of(row(990201L, "待审文章", ROOT_CATEGORY_ID, 991201L));
        total = 1;
        tagViews = Map.of(990201L, List.of(
                new TagView(3L, "标签C", 0, NOW, NOW),
                new TagView(1L, "标签A", 1, NOW, NOW)));

        PageResult<ArticleSummaryVO> result = service.pending(query());

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
        assertThat(result.records().getFirst().getTags())
                .extracting(tag -> tag.getId())
                .containsExactly(1L, 3L);
        assertThat(result.records().getFirst().getTags().get(1).getStatus()).isZero();
        assertThat(result.records().getFirst().getTags()).hasSize(2);
    }

    @Test
    void returnsEmptyTagsWhenArticleHasNoBinding() {
        rows = List.of(row(990201L, "待审文章", ROOT_CATEGORY_ID, 991201L));
        total = 1;
        tagViews = Map.of();

        PageResult<ArticleSummaryVO> result = service.pending(query());

        assertThat(result.records().getFirst().getTags()).isEmpty();
    }

    private static PendingReviewQuery query() {
        PendingReviewQuery query = new PendingReviewQuery();
        query.setCurrent(1);
        query.setSize(10);
        return query;
    }

    private static PendingReviewRow row(long id, String title, long categoryId, long authorId) {
        return new PendingReviewRow(id, title, "摘要", categoryId, "IT分类", authorId, "IT作者",
                "PENDING_REVIEW", "ALL", 1, null,
                LocalDateTime.of(2026, 11, 14, 9, 0), LocalDateTime.of(2026, 11, 15, 9, 0));
    }

    private static void bindCurrentUser(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, new CurrentUser(userId, "reviewer", null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static CurrentUserProvider realCurrentUserProvider() {
        return new CurrentUserProvider();
    }

    private static CategoryMapper categoryMapper() {
        List<Category> categories = new ArrayList<>();
        categories.add(category(ROOT_CATEGORY_ID, null));
        categories.add(category(CHILD_CATEGORY_ID, ROOT_CATEGORY_ID));
        categories.add(category(991103L, null));
        return (CategoryMapper) Proxy.newProxyInstance(
                CategoryMapper.class.getClassLoader(),
                new Class<?>[]{CategoryMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> categories;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Category category(long id, Long parentId) {
        Category category = new Category();
        category.setId(id);
        category.setParentId(parentId);
        category.setCategoryName("分类" + id);
        category.setSortOrder(0);
        category.setStatus(1);
        return category;
    }

    private PendingReviewMapper mapper() {
        return (PendingReviewMapper) Proxy.newProxyInstance(
                PendingReviewMapper.class.getClassLoader(),
                new Class<?>[]{PendingReviewMapper.class},
                (proxy, method, args) -> {
                    if (!"selectPendingPage".equals(method.getName())) {
                        return defaultValue(method.getReturnType());
                    }
                    mapperCalled.set(true);
                    capturedReviewerId.set((Long) args[1]);
                    @SuppressWarnings("unchecked")
                    Collection<Long> ids = (Collection<Long>) args[2];
                    capturedCategoryIds.set(ids);
                    capturedAuthorId.set((Long) args[3]);
                    capturedKeyword.set((String) args[4]);
                    @SuppressWarnings("unchecked")
                    Page<PendingReviewRow> page = (Page<PendingReviewRow>) args[0];
                    page.setRecords(rows);
                    page.setTotal(total);
                    return page;
                });
    }

    private ArticleTagService articleTagService() {
        return (ArticleTagService) Proxy.newProxyInstance(
                ArticleTagService.class.getClassLoader(),
                new Class<?>[]{ArticleTagService.class},
                (proxy, method, args) -> {
                    if ("findByArticleIds".equals(method.getName())) {
                        return tagViews;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
