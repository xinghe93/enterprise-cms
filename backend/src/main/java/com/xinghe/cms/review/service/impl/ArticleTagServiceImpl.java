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

        if (!requested.isEmpty()) {
            // 先完成全部校验：按标签 ID 升序加共享锁，确认标签都存在，并收集停用标签。
            List<TagRow> locked = articleTagMapper.lockTagsByIds(requested);
            requireAllTagsExist(requested, locked);
            rejectNewBindingsOfDisabledTags(articleId, locked);
        }

        // 幂等写入，最终绑定集合严格等于请求集合：
        // 先批量补齐请求的绑定（INSERT IGNORE，已存在的跳过），再删除不在请求集合内的绑定。
        // 顺序不能颠倒：空关联表下若先做加锁读/删除，两个事务可能先锁住同一个索引间隙，
        // 随后各自的插入互相等待，导致其中一次保存死锁回滚（PR #28 复审 P2）。
        if (!requested.isEmpty()) {
            articleTagMapper.insertBindingsIfAbsent(articleId, requested);
        }
        articleTagMapper.deleteBindingsNotRequested(articleId, requested);
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
            List<TagView> views = result.get(row.articleId());
            if (views == null) {
                continue;
            }
            views.add(new TagView(row.tagId(), row.tagName(), row.status(), row.createdAt(), row.updatedAt()));
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
        Set<Long> unique = new TreeSet<>();
        for (Long tagId : tagIds) {
            if (tagId == null || tagId <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 必须为正整数");
            }
            if (!unique.add(tagId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "标签 ID 不能重复");
            }
        }
        if (unique.size() > MAX_TAGS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标签数量不能超过 100");
        }
        return new ArrayList<>(unique);
    }

    private static void requireAllTagsExist(List<Long> requested, List<TagRow> locked) {
        Set<Long> lockedIds = new LinkedHashSet<>();
        if (locked != null) {
            for (TagRow row : locked) {
                lockedIds.add(row.id());
            }
        }
        for (Long tagId : requested) {
            if (!lockedIds.contains(tagId)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "标签不存在");
            }
        }
    }

    /**
     * 停用标签规则：只有原本已绑定的停用标签可以保留，新绑定停用标签返回 409。
     *
     * <p>这里不读取关联表：用幂等插入的受影响行数判断该停用标签是否为"新绑定"。
     * 已绑定返回 0（保留），确实新增返回 1（回滚并返回 409）。</p>
     */
    private void rejectNewBindingsOfDisabledTags(Long articleId, List<TagRow> locked) {
        if (locked == null) {
            return;
        }
        for (TagRow row : locked) {
            if (row.status() != TAG_STATUS_ENABLED
                    && articleTagMapper.insertBindingIfAbsent(articleId, row.id()) > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "标签已停用");
            }
        }
    }
}
