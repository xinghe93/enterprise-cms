package com.xinghe.cms.content.service;

import com.xinghe.cms.content.vo.TagVO;
import com.xinghe.cms.log.annotation.AuditLog;
import org.springframework.stereotype.Component;

/** The audit proxy is entered after TagWriteService has opened the business transaction. */
@Component
class TagAuditOperations {

    private final TagService tags;

    TagAuditOperations(TagService tags) {
        this.tags = tags;
    }

    @AuditLog(operation = "tag.create")
    public TagVO create(String name) {
        return tags.create(name);
    }

    @AuditLog(operation = "tag.update")
    public TagVO update(long id, String name) {
        return tags.update(id, name);
    }

    @AuditLog(operation = "tag.status")
    public void setStatus(long id, int status) {
        tags.setStatus(id, status);
    }

    @AuditLog(operation = "tag.delete")
    public void delete(long id) {
        tags.delete(id);
    }
}
