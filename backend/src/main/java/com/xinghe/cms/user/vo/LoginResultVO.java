package com.xinghe.cms.user.vo;

/** 登录成功数据（OpenAPI: LoginResult）；expiresIn 固定 7200 秒，无 refreshToken。 */
public record LoginResultVO(String accessToken, String tokenType, long expiresIn, UserVO user) {
}
