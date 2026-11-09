package com.xinghe.cms.log.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计切面测试：不启动 Spring、不使用 Mockito，用替身 Writer/JoinPoint + MockHttpServletRequest 验证行为。
 * 事务分支用 {@link TransactionSynchronizationManager#initSynchronization()} 手工搭建同步环境，
 * 再手动触发 afterCompletion 模拟提交/回滚（不依赖真实数据库事务）。
 */
class AuditLogAspectTests {

    private final FakeAuditLogWriter writer = new FakeAuditLogWriter();

    private AuditLogAspect aspect;

    private AuditLog auditLog;

    @BeforeEach
    void setUp() throws Exception {
        aspect = new AuditLogAspect(writer);
        Method method = AnnotatedSample.class.getDeclaredMethod("approve");
        auditLog = method.getAnnotation(AuditLog.class);
        bindRequest("POST", "/api/admin/articles/123/approve", "token=secret&password=secret");
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordsSuccessEntryAndReturnsOriginalResult() throws Throwable {
        Object result = aspect.record(StubProceedingJoinPoint.returning("ok"), auditLog);

        assertThat(result).isEqualTo("ok");
        assertThat(writer.written).hasSize(1);
        OperationLog entry = writer.written.get(0);
        assertThat(entry.getOperation()).isEqualTo("article.approve");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(entry.getRequestUri()).isEqualTo("/api/admin/articles/123/approve");
        assertThat(entry.getRequestUri()).doesNotContain("token=secret").doesNotContain("password=secret");
        assertThat(entry.getHttpMethod()).isEqualTo("POST");
        assertThat(entry.getDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(entry.getUserId()).isNull();
        assertThat(entry.getCreatedAt()).isNull();
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    @Test
    void recordsFailureEntryAndRethrowsOriginalException() {
        IllegalStateException failure = new IllegalStateException("业务失败");

        assertThatThrownBy(() -> aspect.record(StubProceedingJoinPoint.throwing(failure), auditLog))
                .isSameAs(failure);
        assertThat(writer.written).hasSize(1);
        OperationLog entry = writer.written.get(0);
        assertThat(entry.getResult()).isEqualTo("FAILURE");
        assertThat(entry.getOperation()).isEqualTo("article.approve");
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    @Test
    void recordsEmptyRequestFieldsWhenNoRequestContext() throws Throwable {
        RequestContextHolder.resetRequestAttributes();

        aspect.record(StubProceedingJoinPoint.returning("ok"), auditLog);

        OperationLog entry = writer.written.get(0);
        assertThat(entry.getRequestUri()).isEmpty();
        assertThat(entry.getHttpMethod()).isEmpty();
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
    }

    @Test
    void committedTransactionWritesSuccessThroughNewTransaction() throws Throwable {
        beginTransactionSync();

        Object result = aspect.record(StubProceedingJoinPoint.returning("ok"), auditLog);

        assertThat(result).isEqualTo("ok");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).isEmpty();

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        OperationLog entry = writer.writtenInNewTransaction.get(0);
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(entry.getDurationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void rolledBackTransactionWritesFailureThroughNewTransaction() throws Throwable {
        beginTransactionSync();

        Object result = aspect.record(StubProceedingJoinPoint.returning("ok"), auditLog);

        assertThat(result).isEqualTo("ok");
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
    }

    @Test
    void throwingMethodInTransactionRethrowsAndWritesFailureAfterRollback() {
        beginTransactionSync();
        IllegalStateException failure = new IllegalStateException("业务失败");

        assertThatThrownBy(() -> aspect.record(StubProceedingJoinPoint.throwing(failure), auditLog))
                .isSameAs(failure);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
    }

    @Test
    void transactionPathRegistersOnlyOneSynchronization() throws Throwable {
        beginTransactionSync();

        aspect.record(StubProceedingJoinPoint.returning("ok"), auditLog);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void annotationIsRuntimeAndMethodScoped() {
        assertThat(auditLog.operation()).isEqualTo("article.approve");

        Retention retention = AuditLog.class.getAnnotation(Retention.class);
        Target target = AuditLog.class.getAnnotation(Target.class);

        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }

    private void beginTransactionSync() {
        TransactionSynchronizationManager.initSynchronization();
    }

    /**
     * 手动触发同步器回调，模拟事务提交/回滚后的行为，不启动真实事务。
     */
    private void completeTransaction(int status) {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }

    private void bindRequest(String method, String requestUri, String queryString) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setQueryString(queryString);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    /**
     * 记录写入调用的替身。
     */
    static class FakeAuditLogWriter implements AuditLogWriter {

        private final List<OperationLog> written = new ArrayList<>();
        private final List<OperationLog> writtenInNewTransaction = new ArrayList<>();

        @Override
        public void write(OperationLog log) {
            written.add(log);
        }

        @Override
        public void writeInNewTransaction(OperationLog log) {
            writtenInNewTransaction.add(log);
        }
    }

    /**
     * 只实现测试所需行为的 ProceedingJoinPoint 桩。
     */
    static class StubProceedingJoinPoint implements ProceedingJoinPoint {

        private final Object result;
        private final Throwable failure;

        private StubProceedingJoinPoint(Object result, Throwable failure) {
            this.result = result;
            this.failure = failure;
        }

        static StubProceedingJoinPoint returning(Object result) {
            return new StubProceedingJoinPoint(result, null);
        }

        static StubProceedingJoinPoint throwing(Throwable failure) {
            return new StubProceedingJoinPoint(null, failure);
        }

        @Override
        public Object proceed() throws Throwable {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            return proceed();
        }

        @Override
        public Object getThis() {
            return null;
        }

        @Override
        public Object getTarget() {
            return null;
        }

        @Override
        public Object[] getArgs() {
            return new Object[0];
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public void set$AroundClosure(AroundClosure aroundClosure) {
            // 测试桩不需要父闭包
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "stub";
        }

        @Override
        public String toLongString() {
            return "stub";
        }
    }

    static class AnnotatedSample {

        @AuditLog(operation = "article.approve")
        void approve() {
            // 仅用于反射读取注解
        }
    }
}
