package com.xinghe.cms.review.audit;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.review.entity.ReviewRecord;
import com.xinghe.cms.review.mapper.ReviewRecordMapper;
import com.xinghe.cms.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核裁决的事务内审计执行体（approve / reject）。
 *
 * <p>为什么要单独抽一个 bean：{@link AuditLog} 切面必须运行在业务事务内部，
 * 而事务由 {@code ReviewDecisionServiceImpl} 的方法开启。把注解标在 Controller 上
 * 会走"无事务"分支（成功日志在业务提交后才写、写入失败无法回滚业务）；标在 Service
 * 的私有方法上又会被同类自调用绕过代理。因此由 Service 通过注入的代理调用本 bean，
 * 本 bean 的方法同时覆盖"状态流转 + 审核记录写入"，流转失败也会被切面记为 FAILURE。</p>
 *
 * <p>事务：{@code MANDATORY} 表示必须并入外层事务，绝不自行开启新事务；审核人 ID 只取 A 的
 * 安全入口，不接受请求参数。</p>
 */
@Component
@RequiredArgsConstructor
public class ReviewAuditRecorder {

    private final ArticleWorkflowService articleWorkflowService;
    private final ReviewRecordMapper reviewRecordMapper;
    private final CurrentUserProvider currentUserProvider;

    @AuditLog(operation = "article.approve")
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleState approve(long articleId, int expectedVersion, String comment) {
        return record(articleId, expectedVersion, comment, ArticleAction.APPROVE,
                ReviewRecord.RESULT_APPROVED);
    }

    @AuditLog(operation = "article.reject")
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleState reject(long articleId, int expectedVersion, String comment) {
        return record(articleId, expectedVersion, comment, ArticleAction.REJECT,
                ReviewRecord.RESULT_REJECTED);
    }

    private ArticleState record(long articleId, int expectedVersion, String comment,
            ArticleAction action, String result) {
        // 先解析审核人，再流转状态：未登录时在产生任何写入之前直接 401。
        CurrentUser currentUser = currentUserProvider.requireCurrentUser();
        ArticleState state = articleWorkflowService.transition(articleId, expectedVersion, action);

        ReviewRecord record = new ReviewRecord();
        record.setArticleId(articleId);
        record.setReviewerId(currentUser.userId());
        record.setResult(result);
        record.setComment(comment);
        // reviewedAt 保持 null，由数据库 CURRENT_TIMESTAMP(3) 生成，Java 侧永不写入。
        // 审核历史是审计证据：插入必须真正写入一行，否则回滚整个事务。
        if (reviewRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("审核记录写入失败");
        }
        return state;
    }
}
