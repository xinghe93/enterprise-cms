package com.xinghe.cms.content.service;

import com.xinghe.cms.content.vo.CategoryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CategoryWriteService {

    private final CategoryAuditOperations operations;

    public CategoryWriteService(CategoryAuditOperations operations) {
        this.operations = operations;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO create(Long parentId, String name, Integer sortOrder) {
        return operations.create(parentId, name, sortOrder);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO update(long id, Long parentId, String name, int sortOrder) {
        return operations.update(id, parentId, name, sortOrder);
    }

    public void setStatus(long id, int status) {
        operations.setStatus(id, status);
    }

    public void delete(long id) {
        operations.delete(id);
    }
}
