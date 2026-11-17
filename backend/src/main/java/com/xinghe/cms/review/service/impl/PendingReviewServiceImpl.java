package com.xinghe.cms.review.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.enums.ArticleStatus;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.Ids;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.CategoryService;
import com.xinghe.cms.review.dto.PendingReviewQuery;
import com.xinghe.cms.review.mapper.PendingReviewMapper;
import com.xinghe.cms.review.mapper.PendingReviewRow;
import com.xinghe.cms.review.service.ArticleTagService;
import com.xinghe.cms.review.service.PendingReviewService;
import com.xinghe.cms.review.vo.ArticleSummaryVO;
import com.xinghe.cms.review.vo.ArticleTagVO;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待审文章分页查询实现。
 *
 * <p>读取范围：契约规定的待审列表本身就是"审核能力"数据，因此不额外扩大范围；
 * 审核人身份只取自 A 的安全入口 {@link CurrentUserProvider}，并用于排除自己的文章
 * （禁止自审）。分类筛选先经 C 的 {@link CategoryService#descendantIds(Long)}
 * 展开为"分类 + 全部后代"，引用不存在的分类时直接返回空分页。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingReviewServiceImpl implements PendingReviewService {

    private static final int MAX_KEYWORD_CODE_POINTS = 255;

    private final CurrentUserProvider currentUserProvider;
    private final CategoryService categoryService;
    private final ArticleTagService articleTagService;
    private final PendingReviewMapper pendingReviewMapper;

    @Override
    public PageResult<ArticleSummaryVO> pending(PendingReviewQuery query) {
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        Page<PendingReviewRow> page = query.toPage();

        Set<Long> categoryIds = resolveCategoryIds(query.getCategoryId());
        if (categoryIds != null && categoryIds.isEmpty()) {
            // 分类不存在或没有后代：契约要求空分页，不查库。
            return new PageResult<>(List.of(), 0, page.getCurrent(), page.getSize());
        }
        Long authorId = resolveOptionalId(query.getAuthorId());
        String keyword = normalizeKeyword(query.getKeyword());

        Page<PendingReviewRow> result = pendingReviewMapper.selectPendingPage(
                page, currentUser.userId(), categoryIds, authorId, keyword);

        List<Long> articleIds = result.getRecords().stream().map(PendingReviewRow::id).toList();
        Map<Long, List<TagView>> tagViews = articleTagService.findByArticleIds(articleIds);
        List<ArticleSummaryVO> records = result.getRecords().stream()
                .map(row -> toVO(row, tagViews.getOrDefault(row.id(), List.of())))
                .toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private Set<Long> resolveCategoryIds(String rawCategoryId) {
        if (rawCategoryId == null || rawCategoryId.isBlank()) {
            return null;
        }
        return categoryService.descendantIds(Ids.parse(rawCategoryId));
    }

    private static Long resolveOptionalId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return Ids.parse(rawId);
    }

    /**
     * 去首尾空白后为空表示不筛选；长度按 code point 计算，超过 255 返回 400；
     * 转义符为 '!'（{@code ! → !!}、{@code % → !%}、{@code _ → !_}），两侧加 % 做包含匹配。
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_KEYWORD_CODE_POINTS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "关键词长度不能超过 255");
        }
        String escaped = trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static ArticleSummaryVO toVO(PendingReviewRow row, List<TagView> tags) {
        ArticleSummaryVO view = new ArticleSummaryVO();
        view.setId(row.id());
        view.setTitle(row.title());
        view.setSummary(row.summary());
        view.setCategoryId(row.categoryId());
        view.setCategoryName(row.categoryName());
        view.setAuthorId(row.authorId());
        view.setAuthorName(row.authorName());
        view.setStatus(ArticleStatus.valueOf(row.status()));
        view.setVisibility(row.visibility());
        view.setVersion(row.version());
        view.setPublishedAt(row.publishedAt());
        view.setCreatedAt(row.createdAt());
        view.setUpdatedAt(row.updatedAt());
        view.setTags(tags.stream()
                .sorted(Comparator.comparing(TagView::id))
                .map(PendingReviewServiceImpl::toTagVO)
                .toList());
        return view;
    }

    private static ArticleTagVO toTagVO(TagView tag) {
        ArticleTagVO view = new ArticleTagVO();
        view.setId(tag.id());
        view.setTagName(tag.tagName());
        view.setStatus(tag.status());
        view.setCreatedAt(tag.createdAt());
        view.setUpdatedAt(tag.updatedAt());
        return view;
    }
}
