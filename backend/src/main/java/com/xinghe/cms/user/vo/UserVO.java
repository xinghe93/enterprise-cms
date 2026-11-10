package com.xinghe.cms.user.vo;

import java.util.List;

/**
 * 员工响应（OpenAPI: User）。
 *
 * <p>业务 ID 输出为十进制字符串，部门可为 null；roles 为全部已分配角色（含停用）且按
 * 角色 ID 升序；业务时间为毫秒精度的本地时间字符串。绝不包含 password/passwordHash。</p>
 */
public record UserVO(
        String id,
        String username,
        String realName,
        String departmentId,
        String departmentName,
        Integer status,
        List<RoleBriefVO> roles,
        String createdAt,
        String updatedAt) {
}
