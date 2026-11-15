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
 * 确定性验证契约 3.1 与 78 行：数据库提交之后才撤销会话；回滚不触碰 Redis；
 * 依赖故障必须向上抛出，不能伪装成成功。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserStatusServiceTests {

    private static final long USER_ID = 7L;

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
    void disableCommitsDatabaseBeforeRevokingSessions() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(1);
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
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
        order.verify(transactionManager).commit(txStatus);
        order.verify(sessions).revokeAllForUser(USER_ID);
    }

    @Test
    void enableAlsoRotatesEpochSoOldTokensNeverComeBack() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(0);
        when(userMapper.updateStatus(USER_ID, 1)).thenReturn(1);

        service.changeStatus(USER_ID, 1);

        InOrder order = inOrder(transactionManager, sessions);
        order.verify(transactionManager).commit(txStatus);
        order.verify(sessions).revokeAllForUser(USER_ID);
    }

    /** 契约 249：重复设置同一状态返回 200，不写库、不插关联，也不能踢掉管理员自己的会话。 */
    @Test
    void sameStatusIsNoOpButTransactionStillCommits() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(0);

        service.changeStatus(USER_ID, 0);

        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
        verifyNoInteractions(sessions);
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
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(1);
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
     * 撤销失败时事务已提交、异常必须向上抛成 500，不能被 afterCommit 静默吞掉。
     * 过滤器的条件撤销是此场景的兜底，但接口绝不能返回 200 伪装成功。
     */
    @Test
    void revokeFailurePropagatesInsteadOfFakingSuccess() {
        when(userMapper.findStatusForUpdate(USER_ID)).thenReturn(1);
        when(userMapper.updateStatus(USER_ID, 0)).thenReturn(1);
        doThrow(new IllegalStateException("Redis 不可用")).when(sessions).revokeAllForUser(USER_ID);

        assertThrows(IllegalStateException.class, () -> service.changeStatus(USER_ID, 0));

        verify(transactionManager).commit(txStatus);
        verify(transactionManager, never()).rollback(txStatus);
    }
}
