package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（OpenAPI: LoginRequest）。
 *
 * <p>用户名在 Service 中去首尾空白后查询；密码原样送哈希校验，不 trim、不截断。
 * 按《接口与协作约定》8.1，未知 JSON 字段必须返回 400，故此处显式关闭
 * Spring Boot 默认的忽略未知字段行为。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LoginRequest(
        @NotBlank(message = "账号不能为空") @Size(max = 64, message = "账号长度不能超过 64")
        String username,

        @NotNull(message = "密码不能为空") @Size(min = 1, max = 128, message = "密码长度必须在 1 到 128 之间")
        String password) {
}
