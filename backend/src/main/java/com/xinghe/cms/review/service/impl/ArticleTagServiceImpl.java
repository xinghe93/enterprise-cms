package com.xinghe.cms.review.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.xinghe.cms.common.contract.TagView;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.mapper.ArticleTagMapper;
import com.xinghe.cms.review.mapper.ArticleTagRow;
import com.xinghe.cms.review.mapper.TagRow;
import com.xinghe.cms.review.service.ArticleTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文章标签关联服务实现：只维护 cms_article_tag，不改文章与标签本身的数据。
 */
@Service
@RequiredArgsConstructor
public class ArticleTagServiceImpl implements ArticleTagService {

    /** 契约上限：单篇文章最多绑定 100 个标签。 */
    static final int MAX_TAGS = 100;

    /** 批量查询上限：一次最多查询 500 篇文章的标签，避免超长 IN 子句。 */
    static final int MAX_ARTICLE_IDS = 500;

    /** cms_tag.status：1=启用，0=停用。 */
    static final int TAG_STATUS_ENABLED = 1;

    private final ArticleTagMapper articleTagMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceBindings(Long articleId, List<Long> tagIds) {
        requireArticleId(articleId);
        List<Long> requested = normalizeTagIds(tagIds);

        // 加锁当前读：外层已持文章行锁，这里要看到并发事务对关联表的最新修改。
        List<Long> existing = articleTagMapper.selectBoundTagIds(articleId);
        Set<Long> existingSet = existing == null ? Set.of() : new LinkedHashSet<>(existing);
        Set<Long> requestedSet = new LinkedHashSet<>(requested);

        List<Long> toInsert = new ArrayList<>();
        for (Long tagId : requested) {
            if (!existingSet.contains(tagId)) {
                toInsert.add(tagId);
            }
        }
        List<Long> toDelete = new ArrayList<>();
        for (Long tagId : existingSet) {
            if (!requestedSet.contains(tagId)) {
                toDelete.add(tagId);
            }
        }

        // 先完成全部校验（共享锁 + 存在性 + 状态），再执行删除/插入，避免半途失败留下不一致数据。
        if (!toInsert.isEmpty()) {
            List<TagRow> locked = articleTagMapper.lockTagsByIds(toInsert);
            requireAllTagsBindable(toInsert, locked);
        }

        // 只删除明确算出的差集，避免 NOT IN 误删并发新增的绑定。
        if (!toDelete.isEmpty()) {
            articleTagMapper.deleteBindingsByIds(articleId, toDelete);
        }
        if (!toInsert.isEmpty()) {
            articleTagMapper.insertBindings(articleId, toInsert);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<TagView>> findByArticleIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }
        if (articleIds.size() > MAX_ARTICLE_IDS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章数量不能超过 500");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long articleId : articleIds) {
            requireArticleId(articleId);
            distinctIds.add(articleId);
        }

        Map<Long, List<TagView>> result = new LinkedHashMap<>();
        for (Long articleId : distinctIds) {
            result.put(articleId, new ArrayList<>());
        }

        List<ArticleTagRow> rows = articleTagMapper.selectTagRowsByArticleIds(new ArrayList<>(distinctIds));
        if (rows == null) {
            return result;
        }
        for (ArticleTagRow row : rows) {
            result.get(row.articleId()).add(new TagView(
                    row.tagId(), row.tagName(), row.status(), row.createdAt(), row.updatedAt()));
        }
        // SQL 已按标签 ID 升序返回；这里再排一次，保证契约不依赖驱动/执行计划的行序。
        for (List<TagView> views : result.values()) {
            views.sort(Comparator.comparing(TagView::id));
        }
        return result;
    }

    private static void requireArticleId(Long articleId) {
        if (articleId == null || articleId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 必须为正整数");
        }
    }

    private static List<Long> normalizeTagIds(List<Long> tagIds) {
        if (tagIds == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 列表不能为空");
        }
        if (tagIds.size() > MAX_TAGS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签数量不能超过 100");
        }
        Set<Long> unique = new TreeSet<>();
        for (Long tagId : tagIds) {
            if (tagId == null || tagId <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 必须为正整数");
            }
            if (!unique.add(tagId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 不能重复");
            }
        }
        return new ArrayList<>(unique);
    }

    private static void requireAllTagsBindable(List<Long> toInsert, List<TagRow> locked) {
        Set<Long> lockedIds = new LinkedHashSet<>();
        if (locked != null) {
            for (TagRow row : locked) {
                lockedIds.add(row.id());
            }
        }
        for (Long tagId : toInsert) {
            if (!lockedIds.contains(tagId)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "标签不存在");
            }
        }
        if (locked != null) {
            for (TagRow row : locked) {
                if (row.status() != TAG_STATUS_ENABLED) {
                    throw new BusinessException(ResultCode.CONFLICT, "标签已停用");
                }
            }
        }
    }
}
