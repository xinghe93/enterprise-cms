package com.xinghe.cms.content.service;

import com.xinghe.cms.common.contract.ArticleState;
import com.xinghe.cms.content.dto.ArticleDraft;
import com.xinghe.cms.content.vo.ArticleDetailVO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ArticleWriteService {

    private final ArticleAuditOperations operations;

    ArticleWriteService(ArticleAuditOperations operations) {
        this.operations = operations;
    }

    public ArticleDetailVO create(ArticleDraft draft, List<Long> tagIds) {
        return operations.create(draft, tagIds);
    }

    public ArticleDetailVO save(long id, int version, ArticleDraft draft, List<Long> tagIds) {
        return operations.save(id, version, draft, tagIds);
    }

    public void delete(long id, int version) {
        operations.delete(id, version);
    }

    public ArticleState submit(long id, int version) {
        return operations.submit(id, version);
    }
}
