package com.xinghe.cms.review.service.impl;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.review.dto.ArticleVersionRequest;
import com.xinghe.cms.review.dto.BatchArticleItemRequest;
import com.xinghe.cms.review.dto.BatchArticlesRequest;
import com.xinghe.cms.review.service.PublishCommandService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布/下架命令实现（D 模块）。
 *
 * <p>本类只负责参数校验、批量编排与结果映射；状态是否允许、权限、自审、
 * 版本冲突、行锁和 publishedAt 全部由 C 的 {@code ArticleWorkflowService.transition}
 * 在同一个事务内完成，因此这里不重复判断状态、也不直接写 SQL。</p>
 *
 * <p>批量语义：先完成全部参数校验（ID 格式、重复、版本），再按文章 ID
 * <b>升序</b>逐个调用 transition（固定加锁顺序，降低死锁概率）；任一项失败则
 * 抛出异常，由 {@code @Transactional} 整体回滚；结果按<b>请求顺序</b>返回。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PublishCommandServiceImpl implements PublishCommandService {

    /** OpenAPI Id 定义：无前导零的正十进制，最长 19 位；溢出由 Long.parseLong 兜底。 */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int MAX_BATCH_SIZE = 100;

    private final ArticleWorkflowService articleWorkflowService;

    @Override
    public ArticleStateVO publish(long articleId, ArticleVersionRequest request) {
        return decideSingle(articleId, request, ArticleAction.PUBLISH);
    }

    @Override
    public ArticleStateVO offline(long articleId, ArticleVersionRequest request) {
        return decideSingle(articleId, request, ArticleAction.OFFLINE);
    }

    @Override
    public List<ArticleStateVO> publishBatch(BatchArticlesRequest request) {
        return decideBatch(request, ArticleAction.BATCH_PUBLISH);
    }

    @Override
    public List<ArticleStateVO> offlineBatch(BatchArticlesRequest request) {
        return decideBatch(request, ArticleAction.BATCH_OFFLINE);
    }

    private ArticleStateVO decideSingle(long articleId, ArticleVersionRequest request, ArticleAction action) {
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request == null ? null : request.version());
        return toVO(articleWorkflowService.transition(articleId, expectedVersion, action));
    }

    private List<ArticleStateVO> decideBatch(BatchArticlesRequest request, ArticleAction action) {
        List<BatchArticleItemRequest> items = requireItems(request);

        List<Long> articleIds = new ArrayList<>(items.size());
        List<Integer> versions = new ArrayList<>(items.size());
        Set<Long> uniqueIds = new HashSet<>();
        for (BatchArticleItemRequest item : items) {
            long articleId = parseItemId(item.id());
            if (!uniqueIds.add(articleId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 不能重复");
            }
            articleIds.add(articleId);
            versions.add(requireVersion(item.version()));
        }

        // 先校验全部参数，再按文章 ID 升序流转：固定加锁顺序，结果仍按请求顺序回填。
        Integer[] order = new Integer[articleIds.size()];
        for (int index = 0; index < order.length; index++) {
            order[index] = index;
        }
        Arrays.sort(order, Comparator.comparing(articleIds::get));

        ArticleStateVO[] results = new ArticleStateVO[articleIds.size()];
        for (int requestIndex : order) {
            ArticleState state = articleWorkflowService.transition(
                    articleIds.get(requestIndex), versions.get(requestIndex), action);
            results[requestIndex] = toVO(state);
        }
        return List.of(results);
    }

    private static List<BatchArticleItemRequest> requireItems(BatchArticlesRequest request) {
        List<BatchArticleItemRequest> items = request == null ? null : request.items();
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章列表不能为空");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "批量数量不能超过 100");
        }
        return items;
    }

    private static long parseItemId(String rawId) {
        if (rawId == null || !ARTICLE_ID_PATTERN.matcher(rawId).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数错误");
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数错误");
        }
    }

    private static void requireArticleId(long articleId) {
        if (articleId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 必须为正整数");
        }
    }

    private static int requireVersion(Integer version) {
        if (version == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为空");
        }
        if (version < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文章版本不能为负数");
        }
        return version;
    }

    private static ArticleStateVO toVO(ArticleState state) {
        return new ArticleStateVO(state.id(), state.status(), state.version(), state.publishedAt());
    }
}
