package com.xinghe.cms.content.service;

import com.xinghe.cms.content.vo.TagVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TagWriteService {

    private final TagAuditOperations operations;

    public TagWriteService(TagAuditOperations operations) {
        this.operations = operations;
    }

    public TagVO create(String name) {
        return operations.create(name);
    }

    public TagVO update(long id, String name) {
        return operations.update(id, name);
    }

    public void setStatus(long id, int status) {
        operations.setStatus(id, status);
    }

    public void delete(long id) {
        operations.delete(id);
    }
}
