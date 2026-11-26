package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.content.dto.ArticleQuery;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import com.xinghe.cms.content.vo.ArticleSummaryVO;
import com.xinghe.cms.review.service.ArticleTagService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ArticleReadService {

    private final ArticleQueryService queries;
    private final ArticleTagService tags;
    private final PortalContentCache portalCache;

    public ArticleReadService(ArticleQueryService queries, ArticleTagService tags, PortalContentCache portalCache) {
        this.queries = queries;
        this.tags = tags;
        this.portalCache = portalCache;
    }

    public PageResult<ArticleSummaryVO> adminPage(ArticleQuery query) {
        return summarize(queries.adminPage(query));
    }

    public PageResult<ArticleSummaryVO> portalPage(ArticleQuery query) {
        return summarize(queries.portalPage(query));
    }

    public ArticleDetailVO adminDetail(long id) {
        return detail(queries.adminDetail(id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ArticleDetailVO portalDetail(long id) {
        queries.requirePortalDetailPermission();
        return portalCache.article(id, () -> detail(queries.findPortalDetail(id)));
    }

    private PageResult<ArticleSummaryVO> summarize(PageResult<ArticleRow> page) {
        List<ArticleRow> rows = page.records();
        if (rows.isEmpty()) {
            return new PageResult<>(List.of(), page.total(), page.current(), page.size());
        }
        Map<Long, List<TagView>> byArticle = tags.findByArticleIds(rows.stream().map(ArticleRow::id).toList());
        List<ArticleSummaryVO> summaries = rows.stream()
                .map(row -> ArticleSummaryVO.from(row, byArticle.getOrDefault(row.id(), List.of())))
                .toList();
        return new PageResult<>(summaries, page.total(), page.current(), page.size());
    }

    // Callers supply a row already authorized for the current read or write.
    ArticleDetailVO detail(ArticleRow row) {
        List<TagView> articleTags = tags.findByArticleIds(List.of(row.id())).getOrDefault(row.id(), List.of());
        return new ArticleDetailVO(ArticleSummaryVO.from(row, articleTags), row.content());
    }
}
