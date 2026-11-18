package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.log.aspect.AuditLogAspect;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.service.RoleAdminService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 评审 P1 回归：{@code @AuditLog} 标注在事务边界内的服务方法上后，审计切面
 * 在业务事务内进入——success 日志必须经 {@code beforeCommit} 随业务事务一起
 * 提交（与角色写入同事务），审计写入失败必须回滚业务事务，业务失败必须以
 * 独立事务补记 FAILURE。对应《跨模块服务接口》第 5 节与
 * {@code AuditLogAspect} 的接入位置要求。
 *
 * <p>不连真实数据库：用记录提交/回滚结果的桩事务管理器驱动真实的事务拦截器、
 * 事务同步器与审计切面（Spring 代理链与生产一致），Writer 用记录型替身，
 * 并在写入瞬间采样事务激活状态以证明写入发生在业务事务提交之前。</p>
 */
@SpringJUnitConfig(RoleAdminAuditTransactionTests.AuditTxConfig.class)
class RoleAdminAuditTransactionTests {

    @Autowired
    private RoleAdminService service;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private RecordingAuditLogWriter writer;

    @Autowired
    private RecordingTransactionManager txManager;

    /**
     * Spring 上下文在测试方法间缓存共享，替身与桩事务管理器是有状态的，
     * 每个用例前必须重置，避免前一个用例的写入失败开关/记录污染后续用例。
     */
    @BeforeEach
    void resetStatefulFakes() {
        Mockito.reset(sysRoleMapper);
        writer.failOnWrite = false;
        writer.written.clear();
        writer.writtenInNewTransaction.clear();
        writer.txActiveAtWrite.clear();
        txManager.outcomes.clear();
    }

    /** 成功路径：success 审计在业务事务激活期间（beforeCommit）写入，随业务一起提交。 */
    @Test
    void successAuditIsWrittenInsideBusinessTransactionAndCommitsWithIt() {
        when(sysRoleMapper.selectById(37L)).thenReturn(
                role(37L, "EDITOR", "旧名称"), role(37L, "EDITOR", "新名称"));

        service.update(37L, new RoleUpdateRequest("新名称"));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        assertThat(writer.written).hasSize(1);
        OperationLog entry = writer.written.get(0);
        assertThat(entry.getOperation()).isEqualTo("role.update");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        // 关键断言：写入瞬间事务仍激活 = beforeCommit 阶段 = 与角色写入同一事务；
        // 若切面在事务外（旧 Controller 标注方式），此值为 false 且发生在提交之后
        assertThat(writer.txActiveAtWrite).containsExactly(true);
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /** 审计写入失败：beforeCommit 抛出 → 业务事务整体回滚，异常向调用方传播（不可能 200）。 */
    @Test
    void auditWriteFailureRollsBackBusinessTransaction() {
        when(sysRoleMapper.selectById(37L)).thenReturn(role(37L, "EDITOR", "旧名称"));
        writer.failOnWrite = true;

        assertThatThrownBy(() -> service.update(37L, new RoleUpdateRequest("新名称")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("审计写入失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        // 回滚后成功日志已撤销（未写），由切面用独立事务补记 FAILURE
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        assertThat(writer.writtenInNewTransaction.get(0).getOperation()).isEqualTo("role.update");
    }

    /** 业务失败（404）：事务回滚，不留下任何 success 审计，只补记 FAILURE。 */
    @Test
    void businessFailureRollsBackAndRecordsFailureAuditOnly() {
        when(sysRoleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(999L, new RoleUpdateRequest("新名称")))
                .isInstanceOf(BusinessException.class);

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
    }

    /** 幂等跳写库（同状态 200）仍记 SUCCESS 审计：操作成功即成功，不取决于是否写库。 */
    @Test
    void idempotentStatusChangeStillRecordsSuccessAudit() {
        when(sysRoleMapper.selectById(37L)).thenReturn(role(37L, "EDITOR", "编辑"));

        service.setStatus(37L, new RoleStatusRequest(1));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        verify(sysRoleMapper, never()).updateById(any(SysRole.class));
        assertThat(writer.written).hasSize(1);
        assertThat(writer.written.get(0).getOperation()).isEqualTo("role.status");
        assertThat(writer.written.get(0).getResult()).isEqualTo("SUCCESS");
        assertThat(writer.txActiveAtWrite).containsExactly(true);
    }

    private static SysRole role(long id, String code, String name) {
        SysRole entity = new SysRole();
        entity.setId(id);
        entity.setRoleCode(code);
        entity.setRoleName(name);
        entity.setStatus(1);
        return entity;
    }

    /**
     * 桩事务管理器：只记录提交/回滚结果，事务同步器回调由父类
     * {@link AbstractPlatformTransactionManager} 照常触发——commit 时先
     * beforeCommit 再 doCommit，commit 阶段异常转为 doRollback。
     */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        final List<String> outcomes = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 桩实现：无资源绑定
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            outcomes.add("COMMIT");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            outcomes.add("ROLLBACK");
        }
    }

    /** 记录型 Writer 替身：采样写入瞬间的事务激活状态，可注入写入失败。 */
    static class RecordingAuditLogWriter implements AuditLogWriter {

        final List<OperationLog> written = new ArrayList<>();
        final List<OperationLog> writtenInNewTransaction = new ArrayList<>();
        final List<Boolean> txActiveAtWrite = new ArrayList<>();
        boolean failOnWrite;

        @Override
        public void write(OperationLog log) {
            txActiveAtWrite.add(TransactionSynchronizationManager.isSynchronizationActive());
            if (failOnWrite) {
                throw new IllegalStateException("审计写入失败");
            }
            written.add(log);
        }

        @Override
        public void writeInNewTransaction(OperationLog log) {
            writtenInNewTransaction.add(log);
        }
    }

    /**
     * 最小上下文：真实服务实现 + 真实审计切面（Spring 代理链与生产一致），
     * Mapper/Writer 用替身，不启动应用主类、不连接数据库。
     */
    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class AuditTxConfig {

        @Bean
        RecordingAuditLogWriter auditLogWriter() {
            return new RecordingAuditLogWriter();
        }

        @Bean
        CurrentUserProvider currentUserProvider() {
            return new CurrentUserProvider();
        }

        @Bean
        AuditLogAspect auditLogAspect(RecordingAuditLogWriter writer, CurrentUserProvider currentUserProvider) {
            return new AuditLogAspect(writer, currentUserProvider);
        }

        @Bean
        SysRoleMapper sysRoleMapper() {
            return mock(SysRoleMapper.class);
        }

        @Bean
        RoleAdminServiceImpl roleAdminService(SysRoleMapper sysRoleMapper) {
            return new RoleAdminServiceImpl(sysRoleMapper);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }
}
