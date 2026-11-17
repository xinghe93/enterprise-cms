package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleAdminService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理写服务实现。对齐 OpenAPI {@code POST /api/admin/roles}：
 * 默认 status=1、不分配权限；roleCode 唯一，重复编码 409。
 *
 * <p>唯一性由数据库 {@code uk_role_code} 约束保证：直接插入并捕获
 * {@link DuplicateKeyException} 转 {@code BusinessException(CONFLICT)}
 * （协作约定：各写 Service 将已知唯一键冲突转为安全 409，不落入 500 兜底）。
 * 并发创建同一编码时同样由唯一键兜底，无需先查后插的竞态窗口。</p>
 */
@Service
public class RoleAdminServiceImpl implements RoleAdminService {

    /** 角色编码上限（OpenAPI RoleCreate.roleCode.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_ROLE_CODE_LENGTH = 64;

    /** 角色名称上限（OpenAPI RoleCreate.roleName.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_ROLE_NAME_LENGTH = 100;

    private final SysRoleMapper sysRoleMapper;

    public RoleAdminServiceImpl(SysRoleMapper sysRoleMapper) {
        this.sysRoleMapper = sysRoleMapper;
    }

    @Override
    @Transactional
    public RoleView create(RoleCreateRequest request) {
        // OpenAPI maxLength 语义为字符数：按 Unicode 码点统计，
        // 避免 String.length() 把增补字符（emoji）按两个代码单元重复计数
        if (request.roleCode().codePointCount(0, request.roleCode().length()) > MAX_ROLE_CODE_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色编码最长 64 个字符");
        }
        if (request.roleName().codePointCount(0, request.roleName().length()) > MAX_ROLE_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称最长 100 个字符");
        }

        SysRole role = new SysRole();
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName());
        role.setStatus(1);
        try {
            sysRoleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            // 并发下同一 roleCode 的重复插入也由 uk_role_code 兜底，不泄露约束细节
            throw new BusinessException(ResultCode.CONFLICT, "角色编码已存在");
        }

        // createdAt/updatedAt 由数据库默认值维护（实体侧 NEVER 策略），
        // 插入后重新查询取回真实时间，不向客户端返回伪造时间
        SysRole saved = sysRoleMapper.selectById(role.getId());
        if (saved == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "角色创建结果读取失败");
        }
        return RoleView.from(saved);
    }
}
