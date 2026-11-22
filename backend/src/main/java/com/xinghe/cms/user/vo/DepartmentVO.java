package com.xinghe.cms.user.vo;

import java.util.List;

/**
 * 部门树节点（OpenAPI: Department）。
 *
 * <p>业务 ID 输出为十进制字符串，parentId 可为 null；children 恒为数组，
 * 叶子为 []（空 children 不表示「未加载」）；status 保留真实值，
 * 按 status 筛选时祖先节点不会被伪装成启用。</p>
 */
public record DepartmentVO(
        String id,
        String parentId,
        String deptCode,
        String deptName,
        Integer sortOrder,
        Integer status,
        List<DepartmentVO> children,
        String createdAt,
        String updatedAt) {
}
