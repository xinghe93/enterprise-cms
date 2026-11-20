package com.xinghe.cms.review.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 批量文章命令中的单一项，对应 OpenAPI 的 BatchArticleItem。
 *
 * <p>文章 ID 以 JSON 字符串传输：OpenAPI Id 定义为无前导零的正十进制、
 * 最长 19 位且不超过 {@link Long#MAX_VALUE}；超出 Long 的溢出由服务层兜底。</p>
 */
public record BatchArticleItemRequest(
        @NotBlank(message = "文章 ID 不能为空")
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "文章 ID 必须是十进制正整数")
        String id,

        @NotNull(message = "文章版本不能为空")
        @Min(value = 0, message = "文章版本不能为负数")
        Integer version) {
}
