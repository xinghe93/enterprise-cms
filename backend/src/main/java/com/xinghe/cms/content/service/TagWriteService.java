package com.xinghe.cms.content.service;

import com.xinghe.cms.content.vo.TagVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TagWriteService {

    private final TagAuditOperations operations;
    private final PortalContentCache portalCache;

    public TagWriteService(TagAuditOperations operations, PortalContentCache portalCache) {
        this.operations = operations;
        this.portalCache = portalCache;
    }

    public TagVO create(String name) {
        return operations.create(name);
    }

    public TagVO update(long id, String name) {
        TagVO updated = operations.update(id, name);
        portalCache.invalidateArticleDetails();
        return updated;
    }

    public void setStatus(long id, int status) {
        operations.setStatus(id, status);
        portalCache.invalidateArticleDetails();
    }

    public void delete(long id) {
        operations.delete(id);
    }
}
