package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（OpenAPI: LoginRequest）。
 *
 * <p>用户名在 Service 中去首尾空白后查询；密码原样送哈希校验，不 trim、不截断。
 * 按《接口与协作约定》8.1，未知 JSON 字段必须返回 400。</p>
 *
 * <p>注意：Jackson 3 里 {@code ignoreUnknown = false} 只表示「不覆盖全局设置」，
 * 不能反向开启严格检查（实测带额外字段仍会返回 200）。真正的开关是
 * {@code application.yaml} 的
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true}，
 * HTTP 层回归见 {@code AuthControllerHttpTests}。此注解保留用于表达 DTO 意图，
 * 不是校验生效点。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LoginRequest(
        @NotBlank(message = "账号不能为空") @Size(max = 64, message = "账号长度不能超过 64")
        String username,

        @NotNull(message = "密码不能为空") @Size(min = 1, max = 128, message = "密码长度必须在 1 到 128 之间")
        String password) {
}
