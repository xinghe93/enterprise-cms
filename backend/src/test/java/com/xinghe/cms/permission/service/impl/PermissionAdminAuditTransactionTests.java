package com.xinghe.cms.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.log.aspect.AuditLogAspect;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.service.AuditLogWriter;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.mapper.OperatorRowLockMapper;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionStatusRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.service.PermissionAdminService;
import com.xinghe.cms.security.CurrentUserProvider;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 权限项写服务的审计事务回归（与 {@code RoleAdminAuditTransactionTests} /
 * {@code RolePermissionAuditTransactionTests} 同源）：
 * {@code permission.create} / {@code permission.update} 标注在服务方法上、
 * {@code permission.status} 挂在 {@link PermissionWriteAuditMarker#permissionStatusChanged(Runnable)}
 * 受审计代理上——切面在写库<b>之前</b>注册事务同步器。
 *
 * <p>验证：success 日志经 {@code beforeCommit} 随业务事务一起提交；审计写入
 * 失败回滚业务事务；业务失败（唯一键冲突等）回滚后补记 FAILURE；启停的幂等
 * （重复同状态）路径与 404 路径在受审计代理之前返回，不产生任何审计记录；
 * <b>状态更新（写库）抛异常时事务回滚、切面仍用独立新事务补记
 * {@code permission.status / FAILURE}</b>（评审 P2 结论：实际写入必须包进
 * 受审计代理，否则写库抛错时切面从未进入、漏记失败审计）。</p>
 *
 * <p>不连真实数据库：用记录提交/回滚结果的桩事务管理器驱动真实事务拦截器、
 * 事务同步器与审计切面（Spring 代理链与生产一致），Writer 用记录型替身，
 * 并在写入瞬间采样事务激活状态以证明写入发生在业务事务提交之前。</p>
 */
@SpringJUnitConfig(PermissionAdminAuditTransactionTests.AuditTxConfig.class)
class PermissionAdminAuditTransactionTests {

    @Autowired
    private PermissionAdminService service;

    @Autowired
    private SysPermissionMapper sysPermissionMapper;

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
        Mockito.reset(sysPermissionMapper);
        writer.failOnWrite = false;
        writer.written.clear();
        writer.writtenInNewTransaction.clear();
        writer.txActiveAtWrite.clear();
        txManager.outcomes.clear();
    }

    private static SysPermission permission(long id, String code, String type, int status) {
        SysPermission entity = new SysPermission();
        entity.setId(id);
        entity.setPermissionCode(code);
        entity.setPermissionName("旧名称");
        entity.setPermissionType(type);
        entity.setPath("API".equals(type) ? "/api/admin/roles/{id}" : null);
        entity.setMethod("API".equals(type) ? "GET" : null);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.of(2026, 11, 21, 9, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 11, 21, 9, 0));
        return entity;
    }

    // ---------- 创建（@AuditLog 在服务方法上） ----------

    /** 创建成功：success 审计在业务事务内写入并随事务一起提交。 */
    @Test
    void createSuccessAuditIsWrittenInsideBusinessTransactionAndCommitsWithIt() {
        // 模拟数据库自增：插入时回填主键，随后的 selectById 才能取回创建结果
        Mockito.doAnswer(invocation -> {
            ((SysPermission) invocation.getArgument(0)).setId(77L);
            return 1;
        }).when(sysPermissionMapper).insert(any(SysPermission.class));
        when(sysPermissionMapper.selectById(77L)).thenReturn(permission(77L, "a:b", "API", 1));

        service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/articles/{id}", "GET"));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        assertThat(writer.written).hasSize(1);
        OperationLog entry = writer.written.get(0);
        assertThat(entry.getOperation()).isEqualTo("permission.create");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(writer.txActiveAtWrite).containsExactly(true);
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /** 创建业务失败（唯一键冲突 409）：回滚 + 独立新事务补记 FAILURE。 */
    @Test
    void createDuplicateKeyRollsBackAndRecordsFailureAudit() {
        Mockito.doThrow(new DuplicateKeyException("uk_permission_code"))
                .when(sysPermissionMapper).insert(any(SysPermission.class));

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "article:read", "名称", "API", "/api/admin/articles/{id}", "GET")))
                .isInstanceOf(BusinessException.class);

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        OperationLog failure = writer.writtenInNewTransaction.get(0);
        assertThat(failure.getOperation()).isEqualTo("permission.create");
        assertThat(failure.getResult()).isEqualTo("FAILURE");
    }

    /** 审计写入失败：beforeCommit 抛出 → 创建事务整体回滚（权限项不会落库）。 */
    @Test
    void createAuditWriteFailureRollsBackBusinessTransaction() {
        Mockito.doAnswer(invocation -> {
            ((SysPermission) invocation.getArgument(0)).setId(77L);
            return 1;
        }).when(sysPermissionMapper).insert(any(SysPermission.class));
        when(sysPermissionMapper.selectById(77L)).thenReturn(permission(77L, "a:b", "API", 1));
        writer.failOnWrite = true;

        assertThatThrownBy(() -> service.create(new PermissionCreateRequest(
                "a:b", "名称", "API", "/api/admin/articles/{id}", "GET")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("审计写入失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        // 业务方法已完整执行（插入 + 回读），失败发生在 beforeCommit 阶段；
        // 回滚后权限项不生效，成功日志撤销，仅独立新事务补记 FAILURE
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        assertThat(writer.writtenInNewTransaction.get(0).getOperation()).isEqualTo("permission.create");
    }

    // ---------- 修改（@AuditLog 在服务方法上） ----------

    /** 修改成功：success 审计随业务事务一起提交。 */
    @Test
    void updateSuccessAuditIsWrittenInsideBusinessTransactionAndCommitsWithIt() {
        when(sysPermissionMapper.selectById(9L)).thenReturn(permission(9L, "a:b", "API", 1));

        service.update(9L, new PermissionUpdateRequest("新名称", "/api/admin/articles/{id}", "PUT"));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        assertThat(writer.written).hasSize(1);
        assertThat(writer.written.get(0).getOperation()).isEqualTo("permission.update");
        assertThat(writer.written.get(0).getResult()).isEqualTo("SUCCESS");
        assertThat(writer.txActiveAtWrite).containsExactly(true);
    }

    // ---------- 启停（审计挂在受审计代理，写入包进 Runnable） ----------

    /** 状态实际变更：更新在受审计代理内执行，success 审计随业务事务提交。 */
    @Test
    void statusChangeAuditIsWrittenInsideBusinessTransactionAndCommitsWithIt() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "a:b", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class))).thenReturn(1);

        service.setStatus(9L, new PermissionStatusRequest(0));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        verify(sysPermissionMapper).update(any(SysPermission.class), any(Wrapper.class));
        assertThat(writer.written).hasSize(1);
        OperationLog entry = writer.written.get(0);
        assertThat(entry.getOperation()).isEqualTo("permission.status");
        assertThat(entry.getResult()).isEqualTo("SUCCESS");
        assertThat(writer.txActiveAtWrite).containsExactly(true);
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /**
     * 评审 P2 核心回归：状态更新（写库）抛异常 → 切面已在写库前注册回调，
     * 事务回滚后仍用独立新事务补记 {@code permission.status / FAILURE}。
     * 若写入在空锚点之前（旧模式），Mapper 抛错时切面从未进入、漏记失败审计。
     */
    @Test
    void statusUpdateFailureRollsBackAndWritesFailureAudit() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "a:b", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class)))
                .thenThrow(new IllegalStateException("模拟更新失败"));

        assertThatThrownBy(() -> service.setStatus(9L, new PermissionStatusRequest(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟更新失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        OperationLog failure = writer.writtenInNewTransaction.get(0);
        assertThat(failure.getOperation()).isEqualTo("permission.status");
        assertThat(failure.getResult()).isEqualTo("FAILURE");
    }

    /** 审计写入失败（beforeCommit 阶段）：状态变更事务整体回滚。 */
    @Test
    void statusAuditWriteFailureRollsBackBusinessTransaction() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "a:b", "API", 1));
        when(sysPermissionMapper.update(any(SysPermission.class), any(Wrapper.class))).thenReturn(1);
        writer.failOnWrite = true;

        assertThatThrownBy(() -> service.setStatus(9L, new PermissionStatusRequest(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("审计写入失败");

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        verify(sysPermissionMapper).update(any(SysPermission.class), any(Wrapper.class));
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).hasSize(1);
        assertThat(writer.writtenInNewTransaction.get(0).getResult()).isEqualTo("FAILURE");
        assertThat(writer.writtenInNewTransaction.get(0).getOperation()).isEqualTo("permission.status");
    }

    /** 幂等（重复同状态）：不写库、不进审计切面、事务正常提交。 */
    @Test
    void idempotentStatusChangeSkipsAuditEntirely() {
        when(sysPermissionMapper.selectByIdForUpdate(9L))
                .thenReturn(permission(9L, "a:b", "API", 1));

        service.setStatus(9L, new PermissionStatusRequest(1));

        assertThat(txManager.outcomes).containsExactly("COMMIT");
        verify(sysPermissionMapper, never()).update(any(SysPermission.class), any(Wrapper.class));
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).isEmpty();
    }

    /** 404 在受审计代理之前：回滚，切面从未进入，无任何审计记录。 */
    @Test
    void statusMissingPermissionRollsWithoutAnyAudit() {
        when(sysPermissionMapper.selectByIdForUpdate(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.setStatus(999L, new PermissionStatusRequest(0)))
                .isInstanceOf(BusinessException.class);

        assertThat(txManager.outcomes).containsExactly("ROLLBACK");
        assertThat(writer.written).isEmpty();
        assertThat(writer.writtenInNewTransaction).isEmpty();
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
     * 最小上下文：真实服务实现 + 真实审计代理 marker + 真实审计切面（Spring
     * 代理链与生产一致），Mapper/Writer 用替身，不启动应用主类、不连接数据库。
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
        SysPermissionMapper sysPermissionMapper() {
            return mock(SysPermissionMapper.class);
        }

        @Bean
        PermissionWriteAuditMarker permissionWriteAuditMarker() {
            return new PermissionWriteAuditMarker();
        }

        @Bean
        OperatorRowLockMapper operatorRowLockMapper() {
            return mock(OperatorRowLockMapper.class);
        }

        @Bean
        PermissionAdminServiceImpl permissionAdminService(SysPermissionMapper sysPermissionMapper,
                OperatorRowLockMapper operatorRowLockMapper,
                PermissionWriteAuditMarker permissionWriteAuditMarker,
                CurrentUserProvider currentUserProvider) {
            // 真实 CurrentUserProvider 在无请求上下文时抛 UNAUTHORIZED，
            // 服务按"无认证上下文跳过操作人锁"处理（与生产一致）
            return new PermissionAdminServiceImpl(sysPermissionMapper, operatorRowLockMapper,
                    permissionWriteAuditMarker, currentUserProvider);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }
}
