package com.xinghe.cms.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 标签绑定请求（OpenAPI {@code ArticleTagsUpdate}）。
 *
 * <p>{@code version} 必填：成功绑定后版本自增一次，客户端凭旧版本重放会得到 409；
 * {@code tagIds} 为十进制字符串 ID 数组（契约 Id 定义），最多 100 个、不可重复，
 * 具体格式与重名校验由服务层完成并统一返回 400。</p>
 */
public record ArticleTagsUpdateRequest(
        @NotNull(message = "文章版本不能为空")
        @Min(value = 0, message = "文章版本不能为负数")
        Integer version,
        @NotNull(message = "标签 ID 列表不能为空")
        @Size(max = 100, message = "标签数量不能超过 100")
        List<String> tagIds) {
}
