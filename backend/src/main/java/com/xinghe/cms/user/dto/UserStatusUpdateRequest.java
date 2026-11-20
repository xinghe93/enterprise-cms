package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 员工启停请求（OpenAPI: StatusUpdate，{@code PUT /api/admin/users/{id}/status}）。
 *
 * <p>契约《接口与协作约定》8.1 要求字段类型严格按 schema：status 必须是 JSON 整数且只能为
 * 0（停用）或 1（启用），{@code additionalProperties=false}。Jackson 默认会把字符串
 * {@code "1"} 或布尔值强转成整数并送进业务层，这里用 DTO 级的自定义反序列化器在绑定阶段
 * 拒绝，避免改变全局 Jackson 配置而影响 B/C/D 的其他 DTO。</p>
 *
 * <p>与 {@link LoginRequest} 同一套做法：自定义反序列化器不经过 BeanDeserializer，
 * 未知字段也必须在这里自行拒绝，不能只依赖全局 {@code fail-on-unknown-properties}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = UserStatusUpdateRequestDeserializer.class)
public record UserStatusUpdateRequest(int status) {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;
}
