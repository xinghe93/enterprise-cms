package com.xinghe.cms.user.dto;

/**
 * 员工分页查询条件（由 Controller 完成 HTTP 参数解析后传入 Service）。
 *
 * <p>keyword 已去首尾空白，空串表示不筛选；departmentId 为 null 表示不按部门筛选；
 * status 为 null 表示全部状态。所有条件按 AND 组合。</p>
 */
public record UserListQuery(
        long current, long size, String keyword, Long departmentId, Integer status) {
}
