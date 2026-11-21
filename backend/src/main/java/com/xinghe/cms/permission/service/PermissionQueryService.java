package com.xinghe.cms.permission.service;

import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.response.PermissionView;

/**
 * 权限项只读查询服务（OpenAPI {@code GET /api/admin/permissions}，权限项分页）。
 */
public interface PermissionQueryService {

    /**
     * 权限项分页：keyword 去首尾空白后对 permissionCode/permissionName 做字面
     * 包含匹配（LIKE 通配符按字面转义），status/permissionType 精确筛选，
     * 省略条件不筛选；排序 updated_at DESC, id DESC。
     *
     * @param query          分页参数（current/size，范围由 {@link PageQuery#toPage} 兜底）
     * @param keyword        原始关键词，null 表示未传
     * @param status         状态筛选 0/1，null 表示全部
     * @param permissionType 类型筛选 MENU/BUTTON/API，null 表示全部
     * @return 分页结果（含停用项及真实 status）
     */
    PageResult<PermissionView> page(PageQuery query, String keyword, Integer status,
            String permissionType);
}
