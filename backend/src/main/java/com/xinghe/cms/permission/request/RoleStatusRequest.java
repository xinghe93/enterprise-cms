package com.xinghe.cms.permission.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xinghe.cms.common.json.StrictIntDeserializer;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 启停角色请求体（OpenAPI {@code StatusUpdate} schema，
 * {@code PUT /api/admin/roles/{id}/status}）。
 *
 * <p>契约：只改角色状态，不删除 user-role 或 role-permission 历史关联；
 * 重复设置同一状态返回 200（幂等），不存在返回 404。</p>
 *
 * <p>字段必填（{@code required: [status]}，由 {@code @NotNull} 表达）；
 * 取值仅 0（停用）/1（启用），取值范围由服务层按契约校验为 400。
 * 字段类型错误（如字符串 {@code "1"}）经 {@link StrictIntDeserializer} 拒绝为 400。</p>
 *
 * @param status 目标状态：0 停用、1 启用
 */
public record RoleStatusRequest(
        @NotNull(message = "状态不能为空")
        @JsonDeserialize(using = StrictIntDeserializer.class)
        Integer status) {
}
