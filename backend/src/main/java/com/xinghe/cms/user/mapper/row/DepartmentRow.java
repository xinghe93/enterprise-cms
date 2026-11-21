package com.xinghe.cms.user.mapper.row;

import java.time.LocalDateTime;

/** sys_department 一行的完整映射；时间由数据库生成，不接受客户端传入。 */
public record DepartmentRow(
        Long id,
        Long parentId,
        String deptCode,
        String deptName,
        Integer sortOrder,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
