package com.xinghe.cms.review.service;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.contract.ArticleSnapshot;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleAccessService;
import com.xinghe.cms.review.mapper.ReviewRecordMapper;
import com.xinghe.cms.review.service.impl.ReviewQueryServiceImpl;
import com.xinghe.cms.review.vo.ReviewRecordVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReviewQueryServiceImpl 单元测试：用 JDK 动态代理代替 Mockito（本机 JDK 25 下 MockMaker 不可用）。
 */
class ReviewQueryServiceTests {

    private static final long ARTICLE_ID = 990201L;
    private static final long AUTHOR_ID = 991201L;
    private static final long CATEGORY_ID = 991101L;

    private StubArticleAccessService articleAccessService;
    private ReviewQueryService service;
    private AtomicReference<Page<ReviewRecordVO>> capturedPage;
    private AtomicReference<Long> capturedArticleId;
    private List<String> events;

    @BeforeEach
    void setUp() {
        articleAccessService = new StubArticleAccessService();
        capturedPage = new AtomicReference<>();
        capturedArticleId = new AtomicReference<>();
        events = new ArrayList<>();
        ReviewRecordMapper mapper = mapperStub();
        service = new ReviewQueryServiceImpl(mapper, articleAccessService);
    }

    @Test
    void rejectsNonPositiveArticleIdWithoutCallingDependencies() {
        assertThatThrownBy(() -> service.page(0L, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章 ID 必须为正整数");
        assertThatThrownBy(() -> service.page(-3L, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章 ID 必须为正整数");
        assertThat(articleAccessService.calls).isEmpty();
        assertThat(capturedPage.get()).isNull();
    }

    @Test
    void checksReadScopeBeforeQueryingReviews() {
        service.page(ARTICLE_ID, new PageQuery());

        assertThat(events).containsExactly("readable:" + ARTICLE_ID, "mapper:" + ARTICLE_ID);
    }

    @Test
    void returnsPagedResultWithPageMetadata() {
        PageQuery query = new PageQuery();
        query.setCurrent(2);
        query.setSize(5);

        PageResult<ReviewRecordVO> result = service.page(ARTICLE_ID, query);

        assertThat(capturedArticleId.get()).isEqualTo(ARTICLE_ID);
        assertThat(capturedPage.get().getCurrent()).isEqualTo(2);
        assertThat(capturedPage.get().getSize()).isEqualTo(5);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.current()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.records()).extracting(ReviewRecordVO::getId).containsExactly(101L, 102L);
    }

    @Test
    void usesDefaultPaginationWhenNoQueryValuesProvided() {
        service.page(ARTICLE_ID, new PageQuery());

        assertThat(capturedPage.get().getCurrent()).isEqualTo(1);
        assertThat(capturedPage.get().getSize()).isEqualTo(10);
    }

    @Test
    void propagatesAccessDeniedAndSkipsQuery() {
        articleAccessService.failure = new BusinessException(ResultCode.FORBIDDEN, "无访问权限");

        assertThatThrownBy(() -> service.page(ARTICLE_ID, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无访问权限");
        assertThat(capturedPage.get()).isNull();
    }

    private ReviewRecordMapper mapperStub() {
        return (ReviewRecordMapper) Proxy.newProxyInstance(
                ReviewRecordMapper.class.getClassLoader(),
                new Class<?>[]{ReviewRecordMapper.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "selectReviewPage" -> {
                            @SuppressWarnings("unchecked")
                            Page<ReviewRecordVO> page = (Page<ReviewRecordVO>) args[0];
                            capturedPage.set(page);
                            capturedArticleId.set((Long) args[1]);
                            events.add("mapper:" + args[1]);
                            page.setRecords(List.of(review(101L), review(102L)));
                            page.setTotal(3L);
                            return page;
                        }
                        case "toString" -> {
                            return "ReviewRecordMapperStub";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static ReviewRecordVO review(Long id) {
        ReviewRecordVO vo = new ReviewRecordVO();
        vo.setId(id);
        vo.setArticleId(ARTICLE_ID);
        vo.setReviewerId(AUTHOR_ID);
        vo.setReviewerName("IT审核人");
        vo.setResult("APPROVED");
        return vo;
    }

    private final class StubArticleAccessService implements ArticleAccessService {

        private final List<String> calls = new ArrayList<>();
        private BusinessException failure;

        @Override
        public ArticleSnapshot requireReadable(Long articleId) {
            calls.add("readable:" + articleId);
            events.add("readable:" + articleId);
            if (failure != null) {
                throw failure;
            }
            return new ArticleSnapshot(articleId, AUTHOR_ID, CATEGORY_ID, ArticleStatus.DRAFT, 0, null);
        }

        @Override
        public ArticleSnapshot lockEditable(Long articleId, int expectedVersion, ArticleAction action) {
            throw new UnsupportedOperationException();
        }
    }
}
