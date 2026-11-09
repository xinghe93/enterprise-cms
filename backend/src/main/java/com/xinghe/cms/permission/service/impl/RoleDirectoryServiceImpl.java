package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.common.contract.RoleRef;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.service.RoleDirectoryService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RoleDirectoryService} 默认实现。
 *
 * <p>事务传播使用 {@code MANDATORY}：角色共享锁必须与 A 的关联写入处于同一事务，
 * 否则“校验通过 → 提交关联”之间会出现角色被停用的窗口。</p>
 */
@Service
public class RoleDirectoryServiceImpl implements RoleDirectoryService {

    /** sys_role.status 的启用值（chk_role_status 约束：0 停用，1 启用）。 */
    private static final int ROLE_STATUS_ENABLED = 1;

    private final SysRoleMapper sysRoleMapper;

    public RoleDirectoryServiceImpl(SysRoleMapper sysRoleMapper) {
        this.sysRoleMapper = sysRoleMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RoleRef> lockAssignableRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }

        // 去重 + 升序：固定加锁顺序，避免与反向顺序的调用互锁
        List<Long> distinctIdAsc = roleIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (distinctIdAsc.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色 ID 不能为空");
        }

        List<SysRole> lockedRoles = sysRoleMapper.selectByIdsForShare(distinctIdAsc);
        if (lockedRoles.size() != distinctIdAsc.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        boolean hasDisabledRole = lockedRoles.stream()
                .anyMatch(role -> role.getStatus() == null || role.getStatus() != ROLE_STATUS_ENABLED);
        if (hasDisabledRole) {
            throw new BusinessException(ResultCode.CONFLICT, "角色已停用，无法分配");
        }

        return lockedRoles.stream()
                .sorted(Comparator.comparing(SysRole::getId))
                .map(role -> new RoleRef(role.getId(), role.getRoleCode(), role.getRoleName()))
                .toList();
    }
}
