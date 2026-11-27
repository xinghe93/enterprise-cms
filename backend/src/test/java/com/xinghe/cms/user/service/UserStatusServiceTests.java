package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.CurrentUserProvider;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import com.xinghe.cms.user.mapper.row.UserStatusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 启停接口的事务/会话顺序回归。
 *
 * <p>用真实 {@code TransactionTemplate} + 打桩的 {@link PlatformTransactionManager}，
 * 确定性验证两个方向的撤销时序（PR #39 第三轮审查 P1/P2）：</p>
 * <ul>
 * <li>停用：数据库提交<strong>之后</strong>才按 status_version 撤销；回滚不触碰 Redis；
 *     撤销失败向上抛 500，重试可按版本补偿；</li>
 * <li>启用：撤销在事务内、提交<strong>之前</strong>完成——前序撤销失败时在此补偿，
 *     撤销失败整体回滚，绝不出现「已启用但旧会话仍有效」的复活窗口；</li>
 * <li>同状态请求：不写库，仅按当前 status_version 幂等补偿；
 *     从未变更过状态的账号版本为 0，不会误踢正常会话。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserStatusServiceTests {

    private static final long USER_ID = 7L;
    private static final long V0 = 0L;
    private static final long V1 = 1L;
    private static final long V2 = 2L;

    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private UserAdminMapper userMapper;
    @Mock
    private AuthSessionService sessions;
    @Mock
    private CurrentUserProvider currentUserProvider;

    private UserStatusService service;
    private SimpleTransactionStatus txStatus;

    @BeforeEach
    void setUp() {
        txStatus = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(txStatus);
        service = new UserStatusService(transactionManager, userMapper, sessions,
                new UserAuditedActions(), new UserWriteLocks(userMapper, currentUserProvider));
    }

    /** 停用：提交前绝不触碰 Redis，提交后才按新版本撤销。 */
    @Test
    void disableCommitsDatabaseBeforeRevokingSessionsByVersion() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(new CurrentUser(9L, "admin", null));
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, V1));
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
        // 撤销版本唯一来源：updateStatus 后同事务回读的 status_version（+1 后的 V2），
        // 不是入参 status、也不是 updated_at。
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(0, V2));
        // 提交这一刻会话还没被撤销，确定性排除「先刷 Redis 再提交」。
        doAnswer(invocation -> {
            verifyNoInteractions(sessions);
            return null;
        }).when(transactionManager).commit(txStatus);

        service.changeStatus(USER_ID, 0);

        InOrder order = inOrder(transactionManager, userMapper, sessions);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(userMapper).findStatusForUpdate(USER_ID);
        order.verify(userMapper).findUserForShare(9L);
        order.verify(userMapper).updateStatus(USER_ID, 0);
        order.verify(userMapper).findStatusVersion(USER_ID);
        order.verify(transactionManager).commit(txStatus);
        order.verify(sessions).revokeAllForUserUpToVersion(USER_ID, V2);
    }

    /**
     * 启用：撤销必须在提交<strong>之前</strong>完成——前序停用撤销失败时在此补偿，
     * 保证重新启用提交时旧会话已全部失效，不产生复活窗口。
     */
    @Test
    void enableRevokesOldSessionsBeforeCommitting() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(0, V1));
        when(userMapper.updateStatus(USER_ID, 1)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(1, V2));

        service.changeStatus(USER_ID, 1);

        InOrder order = inOrder(sessions, transactionManager);
        order.verify(sessions).revokeAllForUserUpToVersion(USER_ID, V2);
        order.verify(transactionManager).commit(txStatus);
    }

    /** 启用方向的撤销失败：事务必须回滚（数据库仍停用），异常向上抛成 500。 */
    @Test
    void enableRevokeFailureRollsBackInsteadOfFakingSuccess() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(0, V1));
        when(userMapper.updateStatus(USER_ID, 1)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(1, V2));
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(sessions).revokeAllForUserUpToVersion(USER_ID, V2);

        assertThrows(IllegalStateException.class, () -> service.changeStatus(USER_ID, 1));

        verify(transactionManager).rollback(txStatus);
        verify(transactionManager, never()).commit(txStatus);
    }

    /**
     * 同状态请求仍调用带版本撤销（由 Lua 按版本幂等跳过），不写库。
     * 版本取自 status_version——它不随资料更新抬升，因此正常会话不会被误踢；
     * 只有此前撤销失败（记录未抬高）时重试才真正补偿。
     */
    @Test
    void sameStatusRetriesRevokeByCurrentVersionWithoutWriting() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(0, V1));

        service.changeStatus(USER_ID, 0);

        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
        verify(userMapper, never()).findStatusVersion(anyLong());
        verify(sessions).revokeAllForUserUpToVersion(USER_ID, V1);
        verify(transactionManager).commit(txStatus);
    }

    /**
     * 从未变更过状态的账号版本为 0：同状态请求按 0 幂等跳过。
     * 「Redis 记录缺省时按 0 比较」是 Lua 脚本语义，由 AuthSessionServiceTests 与
     * RedisIT 验证；本测试只锁定 Service 把 status_version 原样交给带版本撤销。
     */
    @Test
    void neverDisabledAccountSkipsRevokeByZeroVersion() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, V0));

        service.changeStatus(USER_ID, 1);

        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
        verify(sessions).revokeAllForUserUpToVersion(USER_ID, V0);
    }

    @Test
    void missingUserReturns404RollsBackAndDoesNotRevoke() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeStatus(USER_ID, 0));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verify(transactionManager).rollback(txStatus);
        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
        verifyNoInteractions(sessions);
    }

    /** 条件更新影响 0 行不能静默按成功返回（契约 280）。 */
    @Test
    void zeroAffectedRowsReturns409AndDoesNotRevoke() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, V1));
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeStatus(USER_ID, 0));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(transactionManager).rollback(txStatus);
        verifyNoInteractions(sessions);
    }

    /** 事务内任一步失败都必须整体回滚，且绝不刷新 Redis。 */
    @Test
    void rollbackLeavesRedisUntouched() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenThrow(new IllegalStateException("数据库不可用"));

        assertThrows(IllegalStateException.class, () -> service.changeStatus(USER_ID, 0));

        verify(transactionManager).rollback(txStatus);
        verifyNoInteractions(sessions);
    }

    /**
     * 停用的撤销失败时事务已提交、异常必须向上抛成 500，且版本记录未被抬高（由带版本方法保证），
     * 因此重试能补偿，不会被契约的「相同状态返回 200」短路。
     */
    @Test
    void disableRevokeFailurePropagatesInsteadOfFakingSuccess() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, V1));
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(0, V2));
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(sessions).revokeAllForUserUpToVersion(USER_ID, V2);

        assertThrows(IllegalStateException.class, () -> service.changeStatus(USER_ID, 0));

        verify(transactionManager).commit(txStatus);
        verify(transactionManager, never()).rollback(txStatus);
    }

    /**
     * 提交后撤销失败后的同状态重试必须能补偿：首次停用把版本从 V1 抬到 V2 并提交，
     * 撤销失败；重试时状态已是 0（同状态短路不写库），但版本仍是 V2——正是那次
     * 未完成撤销的版本，Lua 比较后发现记录未抬高而真正轮换纪元，完成补偿
     * （DeepSeek 审查「响应与状态不一致」）。
     */
    @Test
    void disableRevokeFailureCanBeCompensatedBySameStatusRetry() {
        when(userMapper.findStatusForUpdate(USER_ID))
                .thenReturn(new UserStatusSnapshot(1, V1))
                .thenReturn(new UserStatusSnapshot(0, V2));
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(0, V2));
        when(sessions.revokeAllForUserUpToVersion(USER_ID, V2))
                .thenThrow(new IllegalStateException("Redis 不可用"))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.changeStatus(USER_ID, 0));
        service.changeStatus(USER_ID, 0);

        verify(userMapper, times(1)).updateStatus(USER_ID, 0);
        verify(sessions, times(2)).revokeAllForUserUpToVersion(USER_ID, V2);
    }
}
