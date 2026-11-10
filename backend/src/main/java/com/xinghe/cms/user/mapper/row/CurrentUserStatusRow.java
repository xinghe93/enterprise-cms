package com.xinghe.cms.user.mapper.row;

/** 每个请求重新读取的账号状态；停用用户不得继续持有可用会话。 */
public record CurrentUserStatusRow(String username, Long departmentId, Integer status) {
}
