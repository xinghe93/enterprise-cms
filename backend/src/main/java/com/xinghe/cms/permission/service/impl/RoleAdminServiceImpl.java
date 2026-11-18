package com.xinghe.cms.permission.service.impl;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleAdminService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理写服务实现。对齐 OpenAPI {@code POST /api/admin/roles}、
 * {@code PUT /api/admin/roles/{id}}、{@code PUT /api/admin/roles/{id}/status}。
 *
 * <p>唯一性由数据库 {@code uk_role_code} 约束保证：直接插入并捕获
 * {@link DuplicateKeyException} 转 {@code BusinessException(CONFLICT)}
 * （协作约定：各写 Service 将已知唯一键冲突转为安全 409，不落入 500 兜底）。
 * 并发创建同一编码时同样由唯一键兜底，无需先查后插的竞态窗口。</p>
 *
 * <p>字段规范化（协作约定 8.1，与查询接口一致）：先校验原始长度，再去除
 * 首尾空白并检查非空，写库使用规范化后的值——保证唯一性按去空白后的编码
 * 判断（库存量 {@code EDITOR} 时，提交 {@code " EDITOR "} 返回 409 而非
 * 创建出第二条记录）。</p>
 *
 * <p>修改与启停遵循契约：修改只改 roleName（编码创建后不可改），启停只改
 * status（不删除 user-role / role-permission 历史关联）；重复设置同一状态
 * 幂等返回 200。时间字段由数据库维护（{@code ON UPDATE}），写后重新查询取回。</p>
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
        // 协作约定 8.1：先校验原始长度，再去空白。OpenAPI maxLength 语义为字符数，
        // 按 Unicode 码点统计，避免 String.length() 把增补字符（emoji）按两个代码单元重复计数
        if (request.roleCode().codePointCount(0, request.roleCode().length()) > MAX_ROLE_CODE_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色编码最长 64 个字符");
        }
        if (request.roleName().codePointCount(0, request.roleName().length()) > MAX_ROLE_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称最长 100 个字符");
        }

        // 再去除首尾空白并检查非空：写库用规范化后的值，roleCode 唯一性
        // 按去空白后的编码判断（" EDITOR " 与已有 EDITOR 撞唯一索引 → 409）
        String roleCode = request.roleCode().strip();
        String roleName = request.roleName().strip();
        if (roleCode.isEmpty() || roleName.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色编码与名称不能为空白");
        }

        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
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

    @Override
    @Transactional
    public RoleView update(Long id, RoleUpdateRequest request) {
        // 协作约定 8.1：先校验原始长度，再去空白（码点计数与创建一致）
        if (request.roleName().codePointCount(0, request.roleName().length()) > MAX_ROLE_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称最长 100 个字符");
        }
        String roleName = request.roleName().strip();
        if (roleName.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称不能为空白");
        }

        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }

        // 只改 roleName：编码、状态与关联一律不动（契约明文）
        role.setRoleName(roleName);
        sysRoleMapper.updateById(role);

        // updatedAt 由数据库 ON UPDATE 维护，重新查询取回真实时间
        SysRole saved = sysRoleMapper.selectById(id);
        if (saved == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "角色修改结果读取失败");
        }
        return RoleView.from(saved);
    }

    @Override
    @Transactional
    public void setStatus(Long id, RoleStatusRequest request) {
        Integer status = request.status();
        // OpenAPI Status enum [0,1]；类型已由 StrictIntDeserializer 拦截，此处校验取值
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态取值仅支持 0（停用）或 1（启用）");
        }

        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }

        // 契约：重复设置同一状态返回 200，幂等不重复写库
        if (status.equals(role.getStatus())) {
            return;
        }

        // 只改 status：不删除 user-role / role-permission 历史关联（契约明文）
        role.setStatus(status);
        sysRoleMapper.updateById(role);
    }
}
