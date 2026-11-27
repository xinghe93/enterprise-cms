package com.xinghe.cms.user.service;

import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.CurrentUserProvider;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class UserWriteLocks {

    private final UserAdminMapper userMapper;
    private final CurrentUserProvider currentUserProvider;

    UserWriteLocks(UserAdminMapper userMapper, CurrentUserProvider currentUserProvider) {
        this.userMapper = userMapper;
        this.currentUserProvider = currentUserProvider;
    }

    /** 锁定部门行之前，先取得成功审计所需的操作人共享锁。 */
    void lockOperatorForShare() {
        Long operatorId = findOperatorId();
        if (operatorId != null) {
            userMapper.findUserForShare(operatorId);
        }
    }

    /**
     * 写事务内按 ID 升序锁目标员工和操作人。目标取排他锁，操作人提前取得
     * 成功审计外键所需的共享锁，两者为同一人时只取目标的排他锁。
     */
    <T> T lockTargetAndOperator(long targetId, Supplier<T> lockTargetForUpdate) {
        Long operatorId = findOperatorId();
        if (operatorId != null && operatorId < targetId) {
            userMapper.findUserForShare(operatorId);
        }
        T target = lockTargetForUpdate.get();
        if (target != null && operatorId != null && operatorId > targetId) {
            userMapper.findUserForShare(operatorId);
        }
        return target;
    }

    private Long findOperatorId() {
        try {
            CurrentUser operator = currentUserProvider.requireCurrentUser();
            return operator == null ? null : operator.userId();
        } catch (BusinessException exception) {
            if (exception.getResultCode() == ResultCode.UNAUTHORIZED) {
                return null;
            }
            throw exception;
        }
    }
}
