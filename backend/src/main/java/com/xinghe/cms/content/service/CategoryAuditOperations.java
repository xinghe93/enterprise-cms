package com.xinghe.cms.content.service;

import com.xinghe.cms.content.vo.CategoryVO;
import com.xinghe.cms.log.annotation.AuditLog;
import org.springframework.stereotype.Component;

/** Called through the audit proxy after CategoryWriteService has opened the business transaction. */
@Component
public class CategoryAuditOperations {

    private final CategoryService categories;

    public CategoryAuditOperations(CategoryService categories) {
        this.categories = categories;
    }

    @AuditLog(operation = "category.create")
    public CategoryVO create(Long parentId, String name, Integer sortOrder) {
        return categories.create(parentId, name, sortOrder);
    }

    @AuditLog(operation = "category.update")
    public CategoryVO update(long id, Long parentId, String name, int sortOrder) {
        return categories.update(id, parentId, name, sortOrder);
    }

    @AuditLog(operation = "category.status")
    public void setStatus(long id, int status) {
        categories.setStatus(id, status);
    }

    @AuditLog(operation = "category.delete")
    public void delete(long id) {
        categories.delete(id);
    }
}
