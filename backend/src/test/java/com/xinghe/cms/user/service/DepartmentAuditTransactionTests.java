package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinghe.cms.log.aspect.AuditLogAspect;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.security.CurrentUserProvider;
import com.xinghe.cms.user.dto.DepartmentCreateRequest;
import com.xinghe.cms.user.mapper.DepartmentAdminMapper;
import com.xinghe.cms.user.mapper.row.DepartmentRow;
import com.xinghe.cms.user.vo.DepartmentVO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 部门写操作审计的真实 Spring 事务代理回归（PR #40 审查 P2）。
 *
 * <p>不使用 Mockito 触发切面：经过真实的 {@code AuditLogAspect} + 事务管理器同步器，
 * 验证 {@code @AuditLog} 标在事务内经代理调用的 {@link DepartmentAuditedActions} 上时：
 * 成功日志随业务事务提交；审计写入失败时提交阶段抛错、业务事务回滚、部门变更不生效。
 * 与 D 交付的 {@code AuditLogTransactionProxyTests} 是同一套接线约定。</p>
 */
@SpringJUnitConfig(DepartmentAuditTransactionTests.Config.class)
class DepartmentAuditTransactionTests {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 2, 16, 0, 0);

    @Autowired
    private DepartmentAdminService service;
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
        DepartmentVO created = service.create(
                new DepartmentCreateRequest(null, "DEV", "研发部门", 0));

        assertNotNull(created);
        assertTrue(transactionManager.committed);
        assertFalse(transactionManager.rolledBack);
        assertEquals(List.of(List.of("DEV", "研发部门", 0)), businessEffects.inserts());
        assertEquals(1, auditLogWriter.written.size());
        assertEquals("SUCCESS", auditLogWriter.written.get(0).getResult());
        assertEquals("department.create", auditLogWriter.written.get(0).getOperation());
        assertTrue(auditLogWriter.writtenInNewTransaction.isEmpty());
    }

    /** 非根部门创建走「锁树 → 父链校验 → 插入」的完整路径，审计同样随业务事务提交。 */
    @Test
    void successfulAuditWithNonRootParentCreation() {
        DepartmentVO created = service.create(
                new DepartmentCreateRequest(2L, "DEV2", "研发二部", 0));

        assertNotNull(created);
        assertTrue(transactionManager.committed);
        assertFalse(transactionManager.rolledBack);
        assertEquals(List.of(List.of("DEV2", "研发二部", 0)), businessEffects.inserts());
        assertEquals(1, auditLogWriter.written.size());
        assertEquals("department.create", auditLogWriter.written.get(0).getOperation());
    }

    @Test
    void auditWriteFailureRollsBackDepartmentChange() {
        auditLogWriter.failOnWrite = true;

        assertThrows(IllegalStateException.class,
                () -> service.create(new DepartmentCreateRequest(null, "DEV", "研发部门", 0)));

        // beforeCommit 写审计失败 → 事务管理器回滚，部门插入的副作用一并撤销；
        // 不会出现「部门已创建但接口报错/无审计记录」的不一致状态。
        assertFalse(transactionManager.committed);
        assertTrue(transactionManager.rolledBack);
        assertTrue(businessEffects.inserts().isEmpty());
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {

        @Bean
        RecordingTransactionManager transactionManager(BusinessEffects effects) {
            return new RecordingTransactionManager(effects);
        }

        @Bean
        CurrentUserProvider currentUserProvider() {
            return new CurrentUserProvider();
        }

        @Bean
        AuditLogWriter auditLogWriter() {
            return new RecordingAuditLogWriter();
        }

        @Bean
        AuditLogAspect auditLogAspect(AuditLogWriter writer, CurrentUserProvider provider) {
            return new AuditLogAspect(writer, provider);
        }

        @Bean
        BusinessEffects businessEffects() {
            return new BusinessEffects();
        }

        @Bean
        DepartmentAdminMapper departmentAdminMapper(BusinessEffects effects) {
            DepartmentAdminMapper mapper = mock(DepartmentAdminMapper.class);
            when(mapper.insert(any(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
                // 完整记录插入参数（编码/名称/排序），不只记编码（DeepSeek 审查）。
                effects.addInsert(invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3));
                return 1;
            });
            when(mapper.findByDeptCode(anyString())).thenAnswer(invocation ->
                    new DepartmentRow(5L, invocation.getArgument(0).equals("DEV2") ? 2L : null,
                            invocation.getArgument(0), "研发部门",
                            0, 1, TIME, TIME));
            // 非根创建场景的父链校验依赖锁树快照：启用父节点 id=2。
            when(mapper.findAllForUpdate()).thenReturn(List.of(
                    new DepartmentRow(2L, null, "ROOT", "父部门", 0, 1, TIME, TIME)));
            return mapper;
        }

        @Bean
        DepartmentAuditedActions auditedActions() {
            return new DepartmentAuditedActions();
        }

        @Bean
        DepartmentAdminService departmentService(PlatformTransactionManager manager,
                DepartmentAdminMapper mapper, DepartmentAuditedActions actions) {
            return new DepartmentAdminService(manager, mapper, actions);
        }
    }

    /** 模拟数据库写入副作用：记录完整插入参数，事务回滚时由事务管理器清空。 */
    static class BusinessEffects {
        private final List<List<Object>> inserts = new ArrayList<>();

        void addInsert(Object deptCode, Object deptName, Object sortOrder) {
            inserts.add(List.of(deptCode, deptName, sortOrder));
        }

        List<List<Object>> inserts() {
            return List.copyOf(inserts);
        }

        void clear() {
            inserts.clear();
        }
    }

    /** 记录调用的 Writer；failOnWrite 模拟审计写入失败。 */
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

    /** 沿用 AbstractPlatformTransactionManager 的同步器回调，不连接数据库。 */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final BusinessEffects effects;
        private boolean committed;
        private boolean rolledBack;

        RecordingTransactionManager(BusinessEffects effects) {
            this.effects = effects;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 无资源绑定；同步器由基类维护。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
            effects.clear();
        }

        void reset() {
            committed = false;
            rolledBack = false;
        }
    }
}
