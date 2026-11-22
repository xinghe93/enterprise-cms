package com.xinghe.cms.review.dto;

import com.xinghe.cms.common.json.StrictIntegerDeserializer;
import com.xinghe.cms.common.json.StrictStringDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 标签绑定请求（OpenAPI {@code ArticleTagsUpdate}）。
 *
 * <p>{@code version} 必填：成功绑定后版本自增一次，客户端凭旧版本重放会得到 409；
 * {@code tagIds} 为十进制字符串 ID 数组（契约 Id 定义），最多 100 个、不可重复，
 * 具体格式与重名校验由服务层完成并统一返回 400。</p>
 *
 * <p>JSON 类型按契约 8.1 严格校验：{@code version} 必须是范围内的整数字面量
 * （{@code -0.9}、{@code "0"} 均为 400），{@code tagIds} 的每个元素必须是
 * 字符串字面量（数字 {@code 1} 为 400）。Jackson 默认的宽松强转会把这类输入
 * 静默转成 {@code 0}/{"1"} 并进入写入口，因此在 DTO 级用严格反序列化器拒绝，
 * 不采用会改变其他模块行为的全局 CoercionConfig。</p>
 */
public record ArticleTagsUpdateRequest(
        @NotNull(message = "文章版本不能为空")
        @Min(value = 0, message = "文章版本不能为负数")
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer version,
        @NotNull(message = "标签 ID 列表不能为空")
        @Size(max = 100, message = "标签数量不能超过 100")
        @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
        List<String> tagIds) {
}
