package com.xinghe.cms.user.mapper.row;

import java.time.LocalDateTime;

/** 用户 — 部门 LEFT JOIN 的结果行，用于构造 User VO。 */
public record UserProfileRow(
        Long id, String username, String realName, Long departmentId, String departmentName,
        Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
