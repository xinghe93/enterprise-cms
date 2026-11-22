package com.xinghe.cms.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 严格整数反序列化器：JSON 值必须是整数字面量，拒绝 Jackson 默认的宽松强转
 * （字符串 {@code "1"}、小数 {@code 1.0} 会被默认转成 {@code 1}）。OpenAPI 对
 * 字段声明了 {@code type: integer}，类型不符按契约属 400 请求参数错误，而非静默转换。
 *
 * <p>用于 DTO 字段级标注：{@code @JsonDeserialize(using = StrictIntDeserializer.class)}。
 * {@code null} 与缺省字段不经过本反序列化器，由 Bean Validation 的必填校验兜底。
 * 取值范围（如 status 仅 0/1）不在本类判断——它只负责"是整数"这一件事，
 * 业务取值由服务层按契约校验。</p>
 */
public class StrictIntDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class,
                    "字段必须是 JSON 整数，实际为 %s", parser.currentToken());
        }
        return parser.getIntValue();
    }
}
