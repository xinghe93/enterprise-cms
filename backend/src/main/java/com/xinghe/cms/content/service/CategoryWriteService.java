package com.xinghe.cms.content.service;

import com.xinghe.cms.content.vo.CategoryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CategoryWriteService {

    private final CategoryAuditOperations operations;
    private final PortalContentCache portalCache;

    public CategoryWriteService(CategoryAuditOperations operations, PortalContentCache portalCache) {
        this.operations = operations;
        this.portalCache = portalCache;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO create(Long parentId, String name, Integer sortOrder) {
        CategoryVO created = operations.create(parentId, name, sortOrder);
        portalCache.invalidateCategoryMetadata();
        return created;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO update(long id, Long parentId, String name, int sortOrder) {
        CategoryVO updated = operations.update(id, parentId, name, sortOrder);
        portalCache.invalidateCategoryMetadata();
        return updated;
    }

    public void setStatus(long id, int status) {
        operations.setStatus(id, status);
        portalCache.invalidateCategoryMetadata();
    }

    public void delete(long id) {
        operations.delete(id);
        portalCache.invalidateCategoryMetadata();
    }
}
