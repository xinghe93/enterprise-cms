package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.log.aspect.AuditLogAspect;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.OperatorRowLockMapper;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.mapper.SysRolePermissionMapper;
import com.xinghe.cms.permission.request.RolePermissionsUpdateRequest;
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
 * 角色权限全量替换的审计事务回归（与 {@code RoleAdminAuditTransactionTests} 同源）。
 *
 * <p>验证 {@code @AuditLog(operation="role.assign-permission")} 挂在
 * {@link RoleWriteAuditMarker} 的受审计代理 {@code rolePermissionsChanged(Runnable)}
 * 上：差集写入动作经 {@link Runnable} 传入、在代理内部执行（切面进入时先注册
 * 事务同步器，再执行写入）。因此：success 日志经 {@code beforeCommit} 随替换
 * 事务一起提交；<b>差集写入（删除成功、插入抛错）失败时事务回滚，切面仍通过
 * {@code afterCompletion} 用独立新事务补记 FAILURE</b>（本轮评审 P2）；审计写入
 * 失败同样回滚整个替换事务；幂等（相同集合）与业务校验失败路径完全跳过审计
 * （与 role.status 的评审 P2 结论一致：无状态变更路径不产生审计记录，审计写入
 * 失败也不拖垮本应 200 的幂等请求）。</p>
 *
 * <p>不连真实数据库：用记录提交/回滚结果的桩事务管理器驱动真实事务拦截器、
 * 事务同步器与审计切面（Spring 代理链与生产一致），Writer 用记录型替身，
 * 并在写入瞬间采样事务激活状态以证明写入发生在业务事务提交之前。</p>
 */
@SpringJUnitConfig(RolePermissionAuditTransactionTests.AuditTxConfig.class)
class RolePermissionAuditTransactionTests {

    @Autowired
    private RolePermissionServiceImpl service;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysPermissionMapper sysPermissionMapper;

    @Autowired
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Autowired
    private OperatorRowLockMapper operatorRowLockMapper;

    @Autowired
    private RecordingAuditLogWriter writer;

    @Autowired
    private RecordingTransactionManager txManager;

    @BeforeEach
    void resetStatefulFakes() {
        Mockito.reset(sysRoleMapper, sysPermissionMapper, sysRolePermissionMapper,
                operatorRowLockMapper);
        writer.failOnWrite = false;
        writer.written.clear();
        writer.writtenInNewTransaction.clear();
        writer.txActiveAtWrite.clear();
        txManager.outcomes.clear();
    }

    /** 集合实际变化：success 审计在业务事务激活期间写入，随替换事务一起提交。 */
    @Test
    void permissionChangeAuditIsWrittenInsideBusinessTransactionAndCommitsWithIt() {
        stubChangedReplacement();

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L, 2L)));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        assertThat(writer.written).hasSize(1);
        OperationLog entry = writer.written.get(0);
        assertThat(entry.getOperation()).isEqualTo("role.assign-permission");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(writer.txActiveAtWrite).containsExactly(true);
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /** 审计写入失败：beforeCommit 抛出 → 替换事务整体回滚，补记 FAILURE。 */
    @Test
    void permissionChangeAuditWriteFailureRollsBackBusinessTransaction() {
        stubChangedReplacement();
        writer.failOnWrite = true;

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L, 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("审计写入失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        // 差集删除 + 插入已执行但随事务回滚；成功日志未写，仅独立事务补记 FAILURE
        verify(sysRolePermissionMapper).deletePermissions(37L, List.of(9L));
        verify(sysRolePermissionMapper).insertPermissions(org.mockito.ArgumentMatchers.eq(37L), anyList());
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        assertThat(writer.writtenInNewTransaction.get(0).getOperation())
                .isEqualTo("role.assign-permission");
    }

    /**
     * 本轮评审 P2 ②：删除成功、插入抛错——差集写入在受审计代理内部执行，切面
     * 已进入并注册 {@code afterCompletion} 回调，事务回滚后仍用独立新事务补记
     * FAILURE。修复前写入在空锚点调用之前（锚点在写入返回后才进入），Mapper
     * 抛错时切面从未进入、同步器未注册，回滚后漏记失败审计（《跨模块服务接口
     * V1.0》第 120、126 行）。
     */
    @Test
    void mapperInsertFailureRollsBackAndWritesFailureAudit() {
        stubChangedReplacement();
        when(sysRolePermissionMapper.insertPermissions(
                org.mockito.ArgumentMatchers.eq(37L), anyList()))
                .thenThrow(new IllegalStateException("模拟插入失败"));

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L, 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟插入失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        // 删除已执行但随事务回滚；SUCCESS 未写，仅独立新事务补记 FAILURE
        verify(sysRolePermissionMapper).deletePermissions(37L, List.of(9L));
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        OperationLog failure = writer.writtenInNewTransaction.get(0);
        assertThat(failure.getResult()).isEqualTo("FAILURE");
        assertThat(failure.getOperation()).isEqualTo("role.assign-permission");
    }

    /** 本轮评审 P2 ②：删除阶段就抛错，切面同样先于写入注册，回滚并补记 FAILURE。 */
    @Test
    void mapperDeleteFailureRollsBackAndWritesFailureAudit() {
        stubChangedReplacement();
        when(sysRolePermissionMapper.deletePermissions(
                org.mockito.ArgumentMatchers.eq(37L), anyList()))
                .thenThrow(new IllegalStateException("模拟删除失败"));

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L, 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟删除失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        verify(sysRolePermissionMapper, never()).insertPermissions(any(), anyList());
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        assertThat(writer.writtenInNewTransaction.get(0).getOperation())
                .isEqualTo("role.assign-permission");
    }

    /** 幂等（相同集合）：不写库、不进审计切面、事务正常提交。 */
    @Test
    void idempotentPermissionReplacementSkipsAuditEntirely() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role());
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(1L, 2L));

        service.replacePermissions(37L, new RolePermissionsUpdateRequest(List.of(1L, 2L)));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        verify(sysRolePermissionMapper, never()).insertPermissions(any(), anyList());
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /** 业务校验失败（角色 404）在受审计代理之前：回滚，且切面从未进入、无审计记录。 */
    @Test
    void businessFailureBeforeAnchorRollsBackWithoutAnyAudit() {
        when(sysRoleMapper.selectByIdForUpdate(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.replacePermissions(999L,
                new RolePermissionsUpdateRequest(List.of(1L))))
                .isInstanceOf(BusinessException.class);

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /** 校验失败（停用权限 409）同样在受审计代理之前回滚，不产生审计。 */
    @Test
    void disabledPermissionFailureBeforeAnchorRollsBackWithoutAnyAudit() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role());
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L)))
                .thenReturn(List.of(permission(1L, 0))); // 停用

        assertThatThrownBy(() -> service.replacePermissions(37L,
                new RolePermissionsUpdateRequest(List.of(1L))))
                .isInstanceOf(BusinessException.class);

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        verify(sysRolePermissionMapper, never()).deletePermissions(any(), anyList());
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    private void stubChangedReplacement() {
        when(sysRoleMapper.selectByIdForUpdate(37L)).thenReturn(role());
        when(sysPermissionMapper.selectByIdsForShare(List.of(1L, 2L)))
                .thenReturn(List.of(permission(1L, 1), permission(2L, 1)));
        when(sysRolePermissionMapper.selectPermissionIdsByRoleId(37L)).thenReturn(List.of(9L));
    }

    private static SysRole role() {
        SysRole entity = new SysRole();
        entity.setId(37L);
        entity.setRoleCode("EDITOR");
        entity.setRoleName("编辑");
        entity.setStatus(1);
        return entity;
    }

    private static SysPermission permission(long id, int status) {
        SysPermission entity = new SysPermission();
        entity.setId(id);
        entity.setPermissionCode("perm:" + id);
        entity.setPermissionName("权限" + id);
        entity.setPermissionType("API");
        entity.setStatus(status);
        return entity;
    }

    /** 桩事务管理器：只记录提交/回滚结果，事务同步器回调由父类照常触发。 */
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

    /** 最小上下文：真实服务实现 + 真实审计切面（Spring 代理链与生产一致），Mapper 用替身。 */
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
        SysPermissionMapper sysPermissionMapper() {
            return mock(SysPermissionMapper.class);
        }

        @Bean
        SysRolePermissionMapper sysRolePermissionMapper() {
            return mock(SysRolePermissionMapper.class);
        }

        @Bean
        OperatorRowLockMapper operatorRowLockMapper() {
            return mock(OperatorRowLockMapper.class);
        }

        @Bean
        RoleWriteAuditMarker roleWriteAuditMarker() {
            return new RoleWriteAuditMarker();
        }

        @Bean
        RolePermissionServiceImpl rolePermissionService(SysRoleMapper sysRoleMapper,
                SysPermissionMapper sysPermissionMapper,
                SysRolePermissionMapper sysRolePermissionMapper,
                OperatorRowLockMapper operatorRowLockMapper,
                CurrentUserProvider currentUserProvider,
                RoleWriteAuditMarker roleWriteAuditMarker) {
            return new RolePermissionServiceImpl(sysRoleMapper, sysPermissionMapper,
                    sysRolePermissionMapper, operatorRowLockMapper, currentUserProvider,
                    roleWriteAuditMarker);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }
}
