package com.xinghe.cms.log.aspect;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.mapper.OperationLogMapper;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
class AuditLogConnectionPoolTests {

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private OperationLogMapper logMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditLogAspect aspect;

    @Test
    void rollbackReturnsTheOnlyConnectionBeforeWritingFailureAudit() throws Throwable {
        var pool = stubPool(1);
        var transaction = new TransactionTemplate(transactionManager);
        var conflict = new BusinessException(ResultCode.CONFLICT);
        var auditedWork = failingAudit(conflict);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> runAudit(auditedWork)))
                .isSameAs(conflict);

        verifyFailureAudit();
        assertThat(pool.availablePermits()).isEqualTo(1);
    }

    @Test
    void nestedRollbackDefersAuditUntilTheOuterConnectionIsReturned() throws Throwable {
        var pool = stubPool(2);
        var outer = new TransactionTemplate(transactionManager);
        var inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var conflict = new BusinessException(ResultCode.CONFLICT);
        var auditedWork = failingAudit(conflict);

        outer.executeWithoutResult(ignored -> {
            assertThatThrownBy(() -> inner.executeWithoutResult(nested -> runAudit(auditedWork)))
                    .isSameAs(conflict);
            verifyNoInteractions(logMapper);
            assertThat(pool.availablePermits()).isEqualTo(1);
        });

        verifyFailureAudit();
        assertThat(pool.availablePermits()).isEqualTo(2);
    }

    @Test
    void errorWhileWritingFailureAuditDoesNotReplaceTheBusinessException() throws Throwable {
        var pool = stubPool(1);
        var transaction = new TransactionTemplate(transactionManager);
        var conflict = new BusinessException(ResultCode.CONFLICT);
        var auditedWork = failingAudit(conflict);
        when(logMapper.insert(any(OperationLog.class))).thenThrow(new AssertionError("Audit write failed"));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> runAudit(auditedWork)))
                .isSameAs(conflict);

        verifyFailureAudit();
        assertThat(pool.availablePermits()).isEqualTo(1);
    }

    private Semaphore stubPool(int size) throws Exception {
        var permits = new Semaphore(size);
        when(dataSource.getConnection()).thenAnswer(ignored -> {
            if (!permits.tryAcquire()) {
                throw new SQLTransientConnectionException("No spare connection");
            }
            var connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            doAnswer(call -> {
                permits.release();
                return null;
            }).when(connection).close();
            return connection;
        });
        return permits;
    }

    private ProceedingJoinPoint failingAudit(BusinessException failure) throws Throwable {
        var work = mock(ProceedingJoinPoint.class);
        when(work.proceed()).thenThrow(failure);
        return work;
    }

    private void runAudit(ProceedingJoinPoint work) {
        var annotation = mock(AuditLog.class);
        when(annotation.operation()).thenReturn("article.offline");
        try {
            aspect.record(work, annotation);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    private void verifyFailureAudit() {
        verify(logMapper).insert(argThat((OperationLog log) -> "FAILURE".equals(log.getResult())
                && "article.offline".equals(log.getOperation())));
    }
}
