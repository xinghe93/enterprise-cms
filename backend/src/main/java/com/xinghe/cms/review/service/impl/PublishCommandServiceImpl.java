package com.xinghe.cms.review.service.impl;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.review.audit.PublishAuditRecorder;
import com.xinghe.cms.review.dto.ArticleVersionRequest;
import com.xinghe.cms.review.dto.BatchArticleItemRequest;
import com.xinghe.cms.review.dto.BatchArticlesRequest;
import com.xinghe.cms.review.service.PublishCommandService;
import com.xinghe.cms.review.vo.ArticleStateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布/下架命令实现（D 模块）。
 *
 * <p>本类只负责参数校验与结果映射；状态是否允许、权限、版本冲突、行锁和 publishedAt
 * 由 C 的 {@code ArticleWorkflowService.transition} 在同一个事务内完成。"实际的状态流转"
 * 移入 {@link PublishAuditRecorder}，由它承载 {@code @AuditLog} 审计切面：本方法先开启事务
 * 再调用审计 bean，成功日志随业务事务提交、失败记录独立写入。</p>
 *
 * <p>批量语义：先完成全部参数校验（ID 格式、重复、版本），再把 ID 与版本列表交给审计 bean
 * 按文章 ID <b>升序</b>逐个流转（固定加锁顺序）；任一项失败抛异常由 {@code @Transactional}
 * 整体回滚；结果按<b>请求顺序</b>返回。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PublishCommandServiceImpl implements PublishCommandService {

    /** OpenAPI Id 定义：无前导零的正十进制，最长 19 位；溢出由 Long.parseLong 兜底。 */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int MAX_BATCH_SIZE = 100;

    private final PublishAuditRecorder publishAuditRecorder;

    @Override
    public ArticleStateVO publish(long articleId, ArticleVersionRequest request) {
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request == null ? null : request.version());
        return toVO(publishAuditRecorder.publish(articleId, expectedVersion));
    }

    @Override
    public ArticleStateVO offline(long articleId, ArticleVersionRequest request) {
        requireArticleId(articleId);
        int expectedVersion = requireVersion(request == null ? null : request.version());
        return toVO(publishAuditRecorder.offline(articleId, expectedVersion));
    }

    @Override
    public List<ArticleStateVO> publishBatch(BatchArticlesRequest request) {
        return decideBatch(request, true);
    }

    @Override
    public List<ArticleStateVO> offlineBatch(BatchArticlesRequest request) {
        return decideBatch(request, false);
    }

    private List<ArticleStateVO> decideBatch(BatchArticlesRequest request, boolean publish) {
        List<BatchArticleItemRequest> items = requireItems(request);

        List<Long> articleIds = new ArrayList<>(items.size());
        List<Integer> versions = new ArrayList<>(items.size());
        Set<Long> uniqueIds = new HashSet<>();
        for (BatchArticleItemRequest item : items) {
            if (item == null) {
                // JSON 数组里允许出现 null 元素，@Valid 不会拦截元素本身，必须在服务层显式拒绝。
                throw new BusinessException(ResultCode.BAD_REQUEST, "文章项不能为空");
            }
            long articleId = parseItemId(item.id());
            if (!uniqueIds.add(articleId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文章 ID 不能重复");
            }
            articleIds.add(articleId);
            versions.add(requireVersion(item.version()));
        }

        List<ArticleState> states = publish
                ? publishAuditRecorder.publishBatch(articleIds, versions)
                : publishAuditRecorder.offlineBatch(articleIds, versions);

        List<ArticleStateVO> results = new ArrayList<>(states.size());
        for (ArticleState state : states) {
            results.add(toVO(state));
        }
        return List.copyOf(results);
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
        if (state == null) {
            // C 的 transition 约定返回非空；这里兜底，避免 NPE 变成难以定位的 500。
            throw new IllegalStateException("文章状态更新失败");
        }
        return new ArticleStateVO(state.id(), state.status(), state.version(), state.publishedAt());
    }
}
