package com.xinghe.cms.review.audit;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.enums.ArticleAction;
import com.xinghe.cms.content.service.ArticleWorkflowService;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.review.service.ArticleTagService;
import com.xinghe.cms.review.vo.ArticleTagsVO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标签绑定的审计接入点（{@code article.bind-tag}）。
 *
 * <p>按《跨模块服务接口》§4 的事务序：先由 C 的
 * {@link ArticleWorkflowService#transition} 校验权限/写范围/版本与状态并自增版本，
 * 再调用 {@link ArticleTagService#replaceBindings} 替换关联；两者处于同一业务事务，
 * REQUIRED/MANDATORY 均由外层 Service 开启。本方法标记 {@link AuditLog}，审计切面在
 * {@code beforeCommit} 随业务事务写 SUCCESS，失败时回滚并在新事务补记 FAILURE。</p>
 */
@Component
public class ArticleTagsAuditRecorder {

    private final ArticleWorkflowService articleWorkflowService;
    private final ArticleTagService articleTagService;

    public ArticleTagsAuditRecorder(ArticleWorkflowService articleWorkflowService,
                                    ArticleTagService articleTagService) {
        this.articleWorkflowService = articleWorkflowService;
        this.articleTagService = articleTagService;
    }

    @AuditLog(operation = "article.bind-tag")
    @Transactional(propagation = Propagation.MANDATORY)
    public ArticleTagsVO bind(long articleId, int expectedVersion, List<Long> tagIds) {
        ArticleState state = articleWorkflowService.transition(
                articleId, expectedVersion, ArticleAction.BIND_TAGS);
        articleTagService.replaceBindings(articleId, tagIds);
        List<String> sortedTagIds = tagIds.stream()
                .distinct()
                .sorted()
                .map(String::valueOf)
                .toList();
        return new ArticleTagsVO(state.id(), state.status(), state.version(), sortedTagIds);
    }
}
