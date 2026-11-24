package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.common.contract.AuthorizationSnapshot;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.mapper.AuthorizationQueryMapper;
import com.xinghe.cms.permission.service.AuthorizationQueryService;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuthorizationQueryService} 默认实现。
 *
 * <p>行为约定（《跨模块服务接口 V1.0》第 2 节）：</p>
 * <ul>
 *   <li>用户不存在或停用 → UNAUTHORIZED，不返回空快照；</li>
 *   <li>角色/权限有效性由 SQL 过滤（r.status = 1、p.status = 1）；</li>
 *   <li>同一请求可复用快照，但不做跨请求缓存；Redis/数据库故障按异常上抛返回 500，不降级放行。</li>
 * </ul>
 */
@Service
public class AuthorizationQueryServiceImpl implements AuthorizationQueryService {

    /** sys_user.status 的启用值（chk_user_status 约束：0 停用，1 启用）。 */
    private static final int USER_STATUS_ENABLED = 1;

    private final AuthorizationQueryMapper authorizationQueryMapper;

    public AuthorizationQueryServiceImpl(AuthorizationQueryMapper authorizationQueryMapper) {
        this.authorizationQueryMapper = authorizationQueryMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizationSnapshot getForUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户身份无效");
        }

        Integer userStatus = authorizationQueryMapper.selectUserStatus(userId);
        if (userStatus == null || userStatus != USER_STATUS_ENABLED) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已停用");
        }

        List<String> roleCodes = authorizationQueryMapper.selectEnabledRoleCodes(userId);
        List<String> permissionCodes = authorizationQueryMapper.selectPermissionCodes(userId);
        return new AuthorizationSnapshot(new LinkedHashSet<>(roleCodes), new LinkedHashSet<>(permissionCodes));
    }
}
