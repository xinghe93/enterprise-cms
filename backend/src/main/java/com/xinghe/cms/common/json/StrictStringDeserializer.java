package com.xinghe.cms.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 严格字符串反序列化器：JSON 值必须是字符串字面量，拒绝 Jackson 默认的
 * 宽松强转（数字 {@code 123}、布尔 {@code true} 会被默认转成
 * {@code "123"}/{@code "true"}）。OpenAPI 对字段声明了 {@code type: string}，
 * 类型不符按契约属 400 请求参数错误，而非静默转换。
 *
 * <p>用于 DTO 字段级标注：{@code @JsonDeserialize(using = StrictStringDeserializer.class)}。
 * {@code null} 与缺省字段不经过本反序列化器，由 Bean Validation 的必填校验兜底。
 * 与 A 模块 {@code LoginRequestDeserializer} 同类：校验放在 DTO 级而非全局
 * {@code JsonMapper}/CoercionConfig，避免改变其他模块 DTO 的反序列化行为。</p>
 */
public class StrictStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(String.class,
                    "字段必须是 JSON 字符串，实际为 %s", parser.currentToken());
        }
        return parser.getText();
    }
}
