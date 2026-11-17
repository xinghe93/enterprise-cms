package com.xinghe.cms.log.aspect;

import java.util.ArrayList;
import java.util.List;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计事务语义的"真实 Spring 事务代理"回归测试（不使用 Mockito、不需要数据库）。
 *
 * <p>与 {@link AuditLogAspectTests} 手动触发同步回调不同，这里经过真实的 Spring
 * {@code @Transactional} 代理与 {@link AbstractPlatformTransactionManager} 的提交/回滚流程，
 * 验证文档要求的接入方式：{@code @AuditLog} 标在**事务内被调用的方法**上（Controller →
 * 事务 Service → 审计方法），因此进入切面时事务已开启。</p>
 *
 * <ul>
 *   <li>审计写入成功：日志通过业务事务写入（不另开新事务），业务正常提交；</li>
 *   <li>审计写入失败：提交阶段的 {@code beforeCommit} 抛出，事务管理器回滚业务，
 *       业务副作用一起撤销，异常向调用方传播；</li>
 *   <li>被审计方法内部的状态流转失败：异常在切面内部抛出，业务回滚，
 *       同时以独立事务记下 {@code FAILURE}（而不是漏记）。</li>
 * </ul>
 */
@SpringJUnitConfig(AuditLogTransactionProxyTests.Config.class)
class AuditLogTransactionProxyTests {

    @Autowired
    private DemoTransactorService demoService;

    @Autowired
    private DemoReviewService demoReviewService;

    @Autowired
    private RecordingTransactionManager transactionManager;

    @Autowired
    private RecordingAuditLogWriter auditLogWriter;

    @Autowired
    private BusinessEffects businessEffects;

    @BeforeEach
    void setUp() {
        transactionManager.reset();
        auditLogWriter.reset();
        businessEffects.clear();
    }

    @Test
    void successfulAuditParticipatesInBusinessTransaction() {
        demoService.perform("row-1");

        assertThat(transactionManager.committed).isTrue();
        assertThat(transactionManager.rolledBack).isFalse();
        assertThat(businessEffects.values()).containsExactly("row-1");
        assertThat(auditLogWriter.written).hasSize(1);
        assertThat(auditLogWriter.written.get(0).getResult()).isEqualTo("SUCCESS");
        assertThat(auditLogWriter.writtenInNewTransaction).isEmpty();
    }

    @Test
    void auditWriteFailureRollsBackBusinessTransaction() {
        auditLogWriter.failOnWrite = true;

        assertThatThrownBy(() -> demoService.perform("row-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("审计写入失败");

        // 事务管理器执行了回滚，业务副作用一起撤销；不会出现"业务生效但审计缺失"。
        assertThat(transactionManager.committed).isFalse();
        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(businessEffects.values()).isEmpty();
    }

    @Test
    void transitionFailureInsideAuditedMethodRollsBackAndRecordsFailure() {
        assertThatThrownBy(() -> demoReviewService.approve("row-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文章状态或版本已变化，请刷新后重试");

        // 状态流转在切面内部抛异常：业务回滚、副作用撤销。
        assertThat(transactionManager.committed).isFalse();
        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(businessEffects.values()).isEmpty();
        // 不会误记 SUCCESS；FAILURE 走独立事务补写，避免"审核失败但查不到记录"。
        assertThat(auditLogWriter.written).isEmpty();
        assertThat(auditLogWriter.writtenInNewTransaction).hasSize(1);
        assertThat(auditLogWriter.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        assertThat(auditLogWriter.writtenInNewTransaction.get(0).getOperation()).isEqualTo("article.approve");
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        CurrentUserProvider currentUserProvider() {
            return new CurrentUserProvider();
        }

        @Bean
        RecordingAuditLogWriter auditLogWriter() {
            return new RecordingAuditLogWriter();
        }

        @Bean
        AuditLogAspect auditLogAspect(RecordingAuditLogWriter writer, CurrentUserProvider provider) {
            return new AuditLogAspect(writer, provider);
        }

        @Bean
        BusinessEffects businessEffects() {
            return new BusinessEffects();
        }

        @Bean
        AuditedStep auditedStep(BusinessEffects effects) {
            return new AuditedStep(effects);
        }

        @Bean
        DemoTransactorService demoTransactorService(AuditedStep step) {
            return new DemoTransactorService(step);
        }

        @Bean
        TransitionGateway transitionGateway() {
            return new TransitionGateway();
        }

        @Bean
        AuditedReviewStep auditedReviewStep(BusinessEffects effects, TransitionGateway gateway) {
            return new AuditedReviewStep(effects, gateway);
        }

        @Bean
        DemoReviewService demoReviewService(AuditedReviewStep auditedStep) {
            return new DemoReviewService(auditedStep);
        }
    }

    /**
     * 业务事务入口：事务由该 Service 方法开启，审计方法在事务内被调用。
     */
    @Service
    static class DemoTransactorService {

        private final AuditedStep auditedStep;

        DemoTransactorService(AuditedStep auditedStep) {
            this.auditedStep = auditedStep;
        }

        @Transactional
        public void perform(String value) {
            auditedStep.write(value);
        }
    }

    /**
     * 被审计的实际业务动作：必须在事务内被调用，切面才能看到活动事务。
     */
    @Component
    static class AuditedStep {

        private final BusinessEffects effects;

        AuditedStep(BusinessEffects effects) {
            this.effects = effects;
        }

        @AuditLog(operation = "demo.write")
        public void write(String value) {
            effects.add(value);
        }
    }

    /**
     * 审核事务入口：事务由该 Service 方法开启，状态流转在审计方法内部执行。
     */
    @Service
    static class DemoReviewService {

        private final AuditedReviewStep auditedStep;

        DemoReviewService(AuditedReviewStep auditedStep) {
            this.auditedStep = auditedStep;
        }

        @Transactional
        public void approve(String value) {
            auditedStep.apply(value);
        }
    }

    /**
     * 被审计的审核动作：覆盖"状态流转 + 审核记录写入"，冲突异常发生在切面内部。
     */
    @Component
    static class AuditedReviewStep {

        private final BusinessEffects effects;
        private final TransitionGateway gateway;

        AuditedReviewStep(BusinessEffects effects, TransitionGateway gateway) {
            this.effects = effects;
            this.gateway = gateway;
        }

        @AuditLog(operation = "article.approve")
        public void apply(String value) {
            gateway.transition(value);
            effects.add(value);
        }
    }

    /**
     * 状态流转替身：模拟旧版本/非法状态导致的业务冲突。
     */
    static class TransitionGateway {

        void transition(String value) {
            throw new BusinessException(ResultCode.CONFLICT, "文章状态或版本已变化，请刷新后重试");
        }
    }

    /**
     * 业务副作用收集器：回滚时由事务管理器清空，模拟数据库写入被回滚。
     */
    static class BusinessEffects {
        private final List<String> values = new ArrayList<>();

        void add(String value) {
            values.add(value);
        }

        List<String> values() {
            return List.copyOf(values);
        }

        void clear() {
            values.clear();
        }
    }

    /**
     * 记录调用的 Writer；{@link #failOnWrite} 为真时模拟审计写入失败。
     */
    static class RecordingAuditLogWriter implements AuditLogWriter {

        private final List<OperationLog> written = new ArrayList<>();
        private final List<OperationLog> writtenInNewTransaction = new ArrayList<>();
        private boolean failOnWrite;

        @Override
        public void write(OperationLog log) {
            if (failOnWrite) {
                throw new IllegalStateException("审计写入失败");
            }
            written.add(log);
        }

        @Override
        public void writeInNewTransaction(OperationLog log) {
            writtenInNewTransaction.add(log);
        }

        void reset() {
            written.clear();
            writtenInNewTransaction.clear();
            failOnWrite = false;
        }
    }

    /**
     * 记录提交/回滚的最小事务管理器：沿用 {@link AbstractPlatformTransactionManager} 的
     * 同步器回调语义（beforeCommit / afterCompletion），不连接数据库。
     */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private boolean committed;
        private boolean rolledBack;

        @Autowired
        private BusinessEffects businessEffects;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 无资源需要绑定，同步器由 AbstractPlatformTransactionManager 维护
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
            businessEffects.clear();
        }

        void reset() {
            committed = false;
            rolledBack = false;
        }
    }
}
