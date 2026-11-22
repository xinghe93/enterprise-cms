package com.xinghe.cms.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 严格整数反序列化器：JSON 值必须是整数范围内的整数字面量，拒绝 Jackson 默认的
 * 宽松强转（字符串 {@code "0"} 会被默认转成 {@code 0}，浮点数 {@code -0.9}
 * 在开启 {@code ACCEPT_FLOAT_AS_INT} 时会被截断成 {@code 0}）。
 *
 * <p>契约《接口与协作约定》8.1 要求字段 JSON 类型与 OpenAPI 声明一致，类型不符
 * 属 400 请求参数错误，而不是静默转换后继续执行业务写操作。用于 DTO 字段级标注：
 * {@code @JsonDeserialize(using = StrictIntegerDeserializer.class)}。
 * {@code null} 与缺省字段不经过本反序列化器，由 Bean Validation 的必填校验兜底。</p>
 *
 * <p>与 A 模块 {@code LoginRequestDeserializer}、{@link StrictStringDeserializer}
 * 同类：校验放在 DTO 级而非全局 {@code JsonMapper}/CoercionConfig，避免改变
 * 其他模块 DTO 的反序列化行为。</p>
 */
public class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class,
                    "字段必须是 JSON 整数，实际为 %s", parser.currentToken());
        }
        long value = parser.getLongValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return context.reportInputMismatch(Integer.class,
                    "字段必须是 32 位 JSON 整数，实际为 %s", value);
        }
        return (int) value;
    }
}
