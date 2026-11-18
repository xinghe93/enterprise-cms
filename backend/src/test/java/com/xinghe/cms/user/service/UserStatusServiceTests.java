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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import com.xinghe.cms.user.mapper.row.UserStatusSnapshot;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 确定性验证：数据库提交之后才按版本撤销会话；回滚不触碰 Redis；
 * 撤销失败向上抛出；重复状态与过期撤销由带版本方法幂等跳过，
 * 使失败后可重试且延迟撤销不误伤新会话。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserStatusServiceTests {

    private static final long USER_ID = 7L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 16, 10, 0, 0);
    private static final LocalDateTime T1 = T0.plusMinutes(1);
    private static final long V1 = T1.toInstant(ZoneOffset.UTC).toEpochMilli();

    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private UserAdminMapper userMapper;
    @Mock
    private AuthSessionService sessions;

    private UserStatusService service;
    private SimpleTransactionStatus txStatus;

    @BeforeEach
    void setUp() {
        txStatus = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(txStatus);
        service = new UserStatusService(transactionManager, userMapper, sessions);
    }

    @Test
    void disableCommitsDatabaseBeforeRevokingSessionsByVersion() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, T0));
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(0, T1));
        // 提交这一刻会话还没被撤销，确定性排除「先刷 Redis 再提交」。
        doAnswer(invocation -> {
            verifyNoInteractions(sessions);
            return null;
        }).when(transactionManager).commit(txStatus);

        service.changeStatus(USER_ID, 0);

        InOrder order = inOrder(transactionManager, userMapper, sessions);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(userMapper).findStatusForUpdate(USER_ID);
        order.verify(userMapper).updateStatus(USER_ID, 0);
        order.verify(userMapper).findStatusVersion(USER_ID);
        order.verify(transactionManager).commit(txStatus);
        order.verify(sessions).revokeAllForUserUpToVersion(USER_ID, V1);
    }

    @Test
    void enableAlsoRotatesEpochSoOldTokensNeverComeBack() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(0, T0));
        when(userMapper.updateStatus(USER_ID, 1)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(1, T1));

        service.changeStatus(USER_ID, 1);

        InOrder order = inOrder(transactionManager, sessions);
        order.verify(transactionManager).commit(txStatus);
        order.verify(sessions).revokeAllForUserUpToVersion(USER_ID, V1);
    }

    /**
     * 重复状态仍调用带版本撤销（由 Lua 按版本幂等跳过），不写库。
     * 这样若此前撤销失败、记录版本没抬高，重试可补偿撤销，不再被「状态相同」短路。
     */
    @Test
    void sameStatusRetriesRevokeByCurrentVersionWithoutWriting() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(0, T1));

        service.changeStatus(USER_ID, 0);

        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
        verify(userMapper, never()).findStatusVersion(anyLong());
        verify(sessions).revokeAllForUserUpToVersion(USER_ID, V1);
        verify(transactionManager).commit(txStatus);
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
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, T0));
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
     * 撤销失败时事务已提交、异常必须向上抛成 500，且版本记录未被抬高（由带版本方法保证），
     * 因此重试能补偿，不会被契约的「相同状态返回 200」短路。
     */
    @Test
    void revokeFailurePropagatesInsteadOfFakingSuccess() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(new UserStatusSnapshot(1, T0));
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
        when(userMapper.findStatusVersion(USER_ID)).thenReturn(new UserStatusSnapshot(0, T1));
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(sessions).revokeAllForUserUpToVersion(USER_ID, V1);

        assertThrows(IllegalStateException.class, () -> service.changeStatus(USER_ID, 0));

        verify(transactionManager).commit(txStatus);
        verify(transactionManager, never()).rollback(txStatus);
    }
}
