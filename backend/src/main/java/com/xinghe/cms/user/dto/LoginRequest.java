package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 登录请求（OpenAPI: LoginRequest）。
 *
 * <p>用户名在 Service 中去首尾空白后查询；密码原样送哈希校验，不 trim、不截断。
 * 按《接口与协作约定》8.1，未知 JSON 字段必须返回 400。</p>
 *
 * <p>字段类型与未知字段的严格校验由 {@link LoginRequestDeserializer} 在本 DTO 上完成：
 * username / password 必须是 JSON 字符串，数字、布尔、null、数组、对象以及额外字段
 * 都在绑定阶段变成 400，不进入登录服务。这样不必改动全局 Jackson 配置，
 * 也不会改变其他模块 DTO 的反序列化行为。长度与非空仍由下面的 Bean Validation 校验。</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} 只表达 DTO 意图；
 * Jackson 3 中它不能反向开启严格检查，且自定义反序列化器不经过 BeanDeserializer，
 * 因此真正的生效点是上面的 {@code @JsonDeserialize}。保留注解便于读者看出契约要求。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = LoginRequestDeserializer.class)
public record LoginRequest(
        @NotBlank(message = "账号不能为空") @Size(max = 64, message = "账号长度不能超过 64")
        String username,

        @NotNull(message = "密码不能为空") @Size(min = 1, max = 128, message = "密码长度必须在 1 到 128 之间")
        String password) {
}
