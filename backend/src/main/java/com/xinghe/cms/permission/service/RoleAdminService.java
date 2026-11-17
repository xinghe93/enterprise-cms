package com.xinghe.cms.permission.service;

import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.response.RoleView;

/**
 * 角色管理写服务（管理端）。当前批次先交付创建；
 * 修改、启停、权限全量替换在后续迭代补充。
 */
public interface RoleAdminService {

    /**
     * 创建角色。新角色默认启用（status=1）且未分配任何权限；
     * {@code roleCode} 全局唯一，重复时按契约返回 409。
     *
     * <p>时间字段由数据库默认值生成，插入后重新查询取回（契约：
     * "字段时间由数据库生成后重新查询，不能返回客户端伪造时间"）。</p>
     *
     * @param request 创建请求（已通过 MVC 层必填/类型校验）
     * @return 创建后的完整角色视图
     * @throws com.xinghe.cms.common.exception.BusinessException
     *         字段超长时 BAD_REQUEST；roleCode 重复时 CONFLICT（含并发唯一键冲突）
     */
    RoleView create(RoleCreateRequest request);
}
