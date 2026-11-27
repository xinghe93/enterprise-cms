package com.xinghe.cms.log.aspect;

import java.util.concurrent.TimeUnit;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.security.CurrentUserProvider;
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
 * <p><b>接入位置：</b>{@link AuditLog} 必须标在事务边界之内、经 Spring 代理调用的方法上
 * （Controller → {@code @Transactional} Service → 审计方法）。标在 Controller 上时事务尚未开启，
 * 会走"无事务"分支，成功日志在业务提交后才写、失败也不回滚业务，不满足
 * 《跨模块服务接口》第 5 节的"成功审计随外层事务提交"要求。</p>
 *
 * <p>结果以"方法是否正常返回 + 事务最终状态"共同判定，并且成功日志必须与业务一起提交：</p>
 * <ul>
 *   <li>调用方存在事务时只注册一个事务同步器：方法正常返回时，在
 *       {@code beforeCommit} 用业务事务写入 SUCCESS，跟随业务一起提交或回滚——
 *       若这条写入失败，业务事务同样回滚，绝不出现"业务生效但审计缺失"；
 *       失败结果（事务回滚或方法抛过异常）在 {@code afterCompletion} 登记，
 *       等 JDBC 连接归还后用独立事务记 FAILURE；</li>
 *   <li>调用方没有事务时，方法返回后直接写 SUCCESS、抛出后直接写 FAILURE。</li>
 * </ul>
 *
 * <p>即使方法抛出的异常被外层捕获、外层事务随后提交，本次操作也只记 FAILURE；
 * 只有"没有异常 + 事务提交"才算成功。</p>
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

    private final CurrentUserProvider currentUserProvider;

    @Around("@annotation(auditLog)")
    public Object record(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startNanos = System.nanoTime();
        OperationLog entry = buildEntry(auditLog.operation());
        // 记录本次被审计方法是否抛出异常：外层即使捕获并提交了事务，也只能记 FAILURE。
        boolean[] thrown = {false};

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            registerCompletion(entry, thrown);
            try {
                return joinPoint.proceed();
            } catch (Throwable throwable) {
                thrown[0] = true;
                throw throwable;
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
     * 事务内只注册一个同步器：
     *
     * <ul>
     *   <li>{@code beforeCommit}：方法正常返回时用业务事务写入 SUCCESS（参与提交/回滚）；</li>
     *   <li>{@code afterCompletion}：事务未提交或方法抛过异常时，登记归还连接后要写的 FAILURE。</li>
     * </ul>
     */
    private void registerCompletion(OperationLog entry, boolean[] thrown) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!thrown[0]) {
                    entry.setResult(SUCCESS);
                    // 与业务同一事务：提交失败/回滚时这条成功日志一起撤销，
                    // 审计记录不会在业务回滚后残留；写入失败则事务回滚，避免业务生效而审计缺失。
                    auditLogWriter.write(entry);
                }
            }

            @Override
            public void afterCompletion(int status) {
                boolean committed = status == TransactionSynchronization.STATUS_COMMITTED;
                if (!committed || thrown[0]) {
                    // Spring 此时尚未归还 JDBC 连接，不能在回调里再借一条连接。
                    entry.setResult(FAILURE);
                    AuditJdbcTransactionManager.afterConnectionReleased(() -> safeWriteInNewTransaction(entry));
                }
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
        } catch (Throwable failure) {
            // Keep Spring's afterCompletion failure isolation after moving the write past connection cleanup.
            log.error("事务完成后写入操作审计日志失败：operation={}", entry.getOperation(), failure);
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
     * 操作人 ID：只取 A 的安全入口 {@link CurrentUserProvider#requireCurrentUser()}，
     * 不接受请求参数中的 userId，也不自建身份或使用反射注入。
     *
     * <p>无认证上下文时（未登录、内部调用、单元测试）返回 {@code null} 由数据库记录为空，
     * 不伪造身份；认证失败异常不向业务抛出，避免审计影响主流程。</p>
     */
    private Long resolveUserId() {
        try {
            return currentUserProvider.requireCurrentUser().userId();
        } catch (BusinessException exception) {
            return null;
        }
    }
}
