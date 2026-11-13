package com.xinghe.cms.permission.service;

import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.permission.response.RoleView;

/**
 * 角色只读查询（管理端）。写操作（创建/修改/启停）在后续分支补充，
 * 届时与本接口合并为完整的角色服务。
 */
public interface RoleQueryService {

    /**
     * 角色分页。keyword 去首尾空白后对 roleCode/roleName 做字面包含匹配
     * （空串不筛选，通配符按字面转义）；status 仅允许 0/1。
     * 结果固定按 id 升序。
     *
     * @param query   分页参数（页码从 1 开始，每页 1–100）
     * @param keyword 原始关键词，可为 null
     * @param status  状态筛选，可为 null
     * @return 分页结果，records 可能为空数组
     */
    PageResult<RoleView> page(PageQuery query, String keyword, Integer status);

    /**
     * 按 ID 查询角色详情。
     *
     * @param id 角色 ID（已由 {@code Ids.parse} 校验为合法十进制）
     * @return 角色视图
     * @throws com.xinghe.cms.common.exception.BusinessException 角色不存在时抛 NOT_FOUND
     */
    RoleView getById(long id);
}
