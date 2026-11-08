package com.xinghe.cms.log.aspect;

import java.util.concurrent.TimeUnit;

import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面：环绕 {@link AuditLog} 标注的方法，记录操作编码、请求路径、HTTP 方法、结果与耗时。
 * 成功日志在事务提交后写入；业务回滚时用新事务记录 FAILURE，避免留下假的 SUCCESS。
 * 切面不采集请求体、查询参数、密码、Token 或认证头，也不吞原业务异常。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";
    private static final String UNKNOWN = "";

    private final AuditLogWriter auditLogWriter;

    @Around("@annotation(auditLog)")
    public Object record(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            submit(buildEntry(auditLog.operation(), SUCCESS, elapsedMillis(startNanos)), true);
            return result;
        } catch (Throwable throwable) {
            submit(buildEntry(auditLog.operation(), FAILURE, elapsedMillis(startNanos)), false);
            // 审计不能吞业务异常：原样抛出，由全局异常处理生成响应。
            throw throwable;
        }
    }

    private OperationLog buildEntry(String operation, String result, long durationMs) {
        HttpServletRequest request = currentRequest();
        OperationLog entry = new OperationLog();
        entry.setUserId(resolveUserId());
        entry.setOperation(operation);
        entry.setRequestUri(request == null ? UNKNOWN : request.getRequestURI());
        entry.setHttpMethod(request == null ? UNKNOWN : request.getMethod());
        entry.setResult(result);
        entry.setDurationMs(durationMs);
        return entry;
    }

    private void submit(OperationLog entry, boolean success) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeWrite(entry);
            return;
        }
        if (success) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeWrite(entry);
                }
            });
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    safeWrite(entry);
                } else {
                    safeWriteInNewTransaction(entry);
                }
            }
        });
    }

    private void safeWrite(OperationLog entry) {
        try {
            auditLogWriter.write(entry);
        } catch (Exception exception) {
            log.error("写入操作审计日志失败：operation={}", entry.getOperation(), exception);
        }
    }

    private void safeWriteInNewTransaction(OperationLog entry) {
        try {
            auditLogWriter.writeInNewTransaction(entry);
        } catch (Exception exception) {
            log.error("业务回滚后写入操作审计日志失败：operation={}", entry.getOperation(), exception);
        }
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletRequest(servletAttributes);
        }
        return null;
    }

    private HttpServletRequest servletRequest(ServletRequestAttributes attributes) {
        try {
            return attributes.getRequest();
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * 操作人 ID。
     * TODO 待 A 的 CurrentUserProvider 合入 dev 后改为 requireCurrentUser().userId()；
     * 当前该契约尚未合入，按"未登录操作为 null"处理，不自建身份类型或使用反射注入。
     */
    private Long resolveUserId() {
        return null;
    }
}
