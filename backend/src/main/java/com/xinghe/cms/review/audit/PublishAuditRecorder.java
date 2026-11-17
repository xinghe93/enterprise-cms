package com.xinghe.cms.review.audit;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.log.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 发布/下架命令的事务内审计执行体（单条与批量）。
 *
 * <p>与 {@link ReviewAuditRecorder} 同理：由 {@code PublishCommandServiceImpl}（已开启事务）
 * 通过注入的代理调用，保证 {@link AuditLog} 切面运行在业务事务内部，成功日志随事务提交、
 * 流转失败也能记下一行 FAILURE。</p>
 *
 * <p>批量：调用方已完成全部参数校验；这里只按文章 ID <b>升序</b>逐个调用 transition
 * （固定加锁顺序降低死锁概率），结果按<b>请求顺序</b>回填。任何一项失败即抛异常，
 * 由外层 {@code @Transactional} 整体回滚。</p>
 */
@Component
@RequiredArgsConstructor
public class PublishAuditRecorder {

    private final ArticleWorkflowService articleWorkflowService;

    @AuditLog(operation = "article.publish")
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleState publish(long articleId, int expectedVersion) {
        return articleWorkflowService.transition(articleId, expectedVersion, ArticleAction.PUBLISH);
    }

    @AuditLog(operation = "article.offline")
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleState offline(long articleId, int expectedVersion) {
        return articleWorkflowService.transition(articleId, expectedVersion, ArticleAction.OFFLINE);
    }

    @AuditLog(operation = "article.publish-batch")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ArticleState> publishBatch(List<Long> articleIds, List<Integer> versions) {
        return transitionBatch(articleIds, versions, ArticleAction.BATCH_PUBLISH);
    }

    @AuditLog(operation = "article.offline-batch")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ArticleState> offlineBatch(List<Long> articleIds, List<Integer> versions) {
        return transitionBatch(articleIds, versions, ArticleAction.BATCH_OFFLINE);
    }

    private List<ArticleState> transitionBatch(List<Long> articleIds, List<Integer> versions,
            ArticleAction action) {
        Integer[] order = new Integer[articleIds.size()];
        for (int index = 0; index < order.length; index++) {
            order[index] = index;
        }
        Arrays.sort(order, Comparator.comparing(articleIds::get));

        ArticleState[] results = new ArticleState[articleIds.size()];
        for (int requestIndex : order) {
            results[requestIndex] = articleWorkflowService.transition(
                    articleIds.get(requestIndex), versions.get(requestIndex), action);
        }
        return List.of(results);
    }
}
