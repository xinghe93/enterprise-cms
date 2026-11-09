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
 *
 * <p>结果以事务最终状态为准，避免留下假的 SUCCESS：</p>
 * <ul>
 *   <li>调用方存在事务时，只注册一个事务同步器，在 {@code afterCompletion} 按状态写日志：
 *       COMMITTED → SUCCESS，其余（回滚）→ FAILURE；两种结果都用独立新事务提交，
 *       不受业务事务回滚影响，也不会与业务事务共用连接而互相拖累；</li>
 *   <li>调用方没有事务时，方法返回后直接写 SUCCESS、抛出后直接写 FAILURE。</li>
 * </ul>
 *
 * <p>耗时只统计方法执行时间（事务提交等待不计入）。切面不采集请求体、查询参数、密码、
 * Token 或认证头，也不吞原业务异常。</p>
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
        OperationLog entry = buildEntry(auditLog.operation());

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            registerCompletion(entry);
            try {
                return joinPoint.proceed();
            } finally {
                // 只统计方法耗时；同步器回调发生在事务完成后，仍使用这个值。
                entry.setDurationMs(elapsedMillis(startNanos));
            }
        }

        try {
            Object result = joinPoint.proceed();
            entry.setDurationMs(elapsedMillis(startNanos));
            entry.setResult(SUCCESS);
            safeWrite(entry);
            return result;
        } catch (Throwable throwable) {
            entry.setDurationMs(elapsedMillis(startNanos));
            entry.setResult(FAILURE);
            safeWrite(entry);
            // 审计不能吞业务异常：原样抛出，由全局异常处理生成响应。
            throw throwable;
        }
    }

    /**
     * 事务内只注册一个同步器：结果由事务最终状态决定，写入走独立新事务。
     */
    private void registerCompletion(OperationLog entry) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                entry.setResult(status == TransactionSynchronization.STATUS_COMMITTED ? SUCCESS : FAILURE);
                safeWriteInNewTransaction(entry);
            }
        });
    }

    private OperationLog buildEntry(String operation) {
        HttpServletRequest request = currentRequest();
        OperationLog entry = new OperationLog();
        entry.setUserId(resolveUserId());
        entry.setOperation(operation);
        entry.setRequestUri(request == null ? UNKNOWN : request.getRequestURI());
        entry.setHttpMethod(request == null ? UNKNOWN : request.getMethod());
        return entry;
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
            log.error("事务完成后写入操作审计日志失败：operation={}", entry.getOperation(), exception);
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
