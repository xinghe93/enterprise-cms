package com.xinghe.cms.user.vo;

import java.util.List;

/** 员工分页响应（OpenAPI: UserPage）。 */
public record UserPageVO(List<UserVO> records, long total, long current, long size) {
}
