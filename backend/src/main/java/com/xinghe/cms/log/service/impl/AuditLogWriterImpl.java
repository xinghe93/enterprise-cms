package com.xinghe.cms.log.service.impl;

import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.mapper.OperationLogMapper;
import com.xinghe.cms.log.service.AuditLogWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志写入实现：只插入表字段，created_at 由数据库默认值生成。
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriterImpl implements AuditLogWriter {

    private final OperationLogMapper operationLogMapper;

    @Override
    public void write(OperationLog log) {
        operationLogMapper.insert(log);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInNewTransaction(OperationLog log) {
        operationLogMapper.insert(log);
    }
}
