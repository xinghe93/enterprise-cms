package com.xinghe.cms.log.service;

import com.xinghe.cms.log.entity.OperationLog;

/**
 * 审计日志写入能力；只负责落库，不负责采集请求信息。
 */
public interface AuditLogWriter {

    /**
     * 同步写入日志，参与调用方事务（随业务事务提交或回滚）。
     */
    void write(OperationLog log);

    /**
     * 在独立新事务中写入日志，供业务回滚后记录 FAILURE 使用。
     */
    void writeInNewTransaction(OperationLog log);
}
