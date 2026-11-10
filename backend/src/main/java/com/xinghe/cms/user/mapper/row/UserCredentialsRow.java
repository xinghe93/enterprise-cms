package com.xinghe.cms.user.mapper.row;

/** 登录校验所需的账号凭据行；passwordHash 只留在服务端，不进入任何 VO。 */
public record UserCredentialsRow(Long id, String username, String passwordHash, Integer status) {
}
