package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.content.dto.ArticleDraft;
import com.xinghe.cms.content.mapper.ArticleMapper;
import com.xinghe.cms.content.mapper.row.ArticleRow;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.review.service.ArticleTagService;
import java.util.List;
import org.springframework.stereotype.Component;

/** These operations enter the audit proxy inside the ArticleWriteService transaction. */
@Component
class ArticleAuditOperations {

    private final ArticleDraftService drafts;
    private final ArticleTagService tags;
    private final ArticleMapper mapper;
    private final ArticleReadService reads;

    ArticleAuditOperations(ArticleDraftService drafts, ArticleTagService tags, ArticleMapper mapper,
            ArticleReadService reads) {
        this.drafts = drafts;
        this.tags = tags;
        this.mapper = mapper;
        this.reads = reads;
    }

    @AuditLog(operation = "article.create")
    public ArticleDetailVO create(ArticleDraft draft, List<Long> tagIds) {
        long id = drafts.create(draft);
        tags.replaceBindings(id, tagIds);
        return writtenDetail(id);
    }

    @AuditLog(operation = "article.update")
    public ArticleDetailVO save(long id, int version, ArticleDraft draft, List<Long> tagIds) {
        drafts.save(id, version, draft);
        tags.replaceBindings(id, tagIds);
        return writtenDetail(id);
    }

    @AuditLog(operation = "article.delete")
    public void delete(long id, int version) {
        drafts.delete(id, version);
    }

    @AuditLog(operation = "article.submit")
    public ArticleState submit(long id, int version) {
        return drafts.submit(id, version);
    }

    private ArticleDetailVO writtenDetail(long id) {
        // The guarded draft service already authorized this write and acquired the article row lock.
        ArticleRow row = mapper.findDetail(id, false, null, false);
        if (row == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "无法读取已写入的文章");
        }
        return reads.detail(row);
    }
}
