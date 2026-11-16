package com.xinghe.cms.common.request;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import java.util.Map;
import java.util.Set;

/**
 * 查询参数的契约级校验（契约 8.x：未知查询参数、重复传参、空值/非法枚举参数一律 400，
 * 不得因 MVC 绑定忽略未声明参数而放行）。
 *
 * <p>用法：Controller 方法追加 {@link jakarta.servlet.http.HttpServletRequest} 参数，
 * 把 {@code request.getParameterMap()} 传入本工具（Servlet 规范保证值为 {@code String[]}）。
 * 不要用 {@code @RequestParam Map<String, String[]>}——泛型擦除后运行期可能得到单值 String，
 * 取数组时抛 {@code ClassCastException}。
 * 校验顺序：{@link #requireAllowedKeys} 过滤未知键 → {@link #requireSingleValueForEachPresentKey}
 * 拒绝重复传参 → {@link #requireStrictEnumValue} 校验枚举原始取值。
 * JSON 请求体的未知字段由 Jackson 严格模式全局拦截（A 的配置），查询参数无全局钩子，
 * 由各 Controller 显式调用本工具。</p>
 */
public final class QueryParams {

    private QueryParams() {
    }

    /**
     * 校验实际到达的查询参数键都在允许清单内，任何未知键返回 400。
     *
     * <p>统一使用数组形态（{@code Map<String, String[]>}）：单值 {@code Map<String, String>}
     * 会与它擦除后签名相同，Java 不允许这样的重载；且 MVC 对单值 Map 同样会静默取首值，
     * 无法表达重复传参。详情接口不关心重复传参时，先调
     * {@link #requireSingleValueForEachPresentKey} 等价地拒绝。</p>
     *
     * @param allParams  本次请求的原始查询参数全集
     * @param allowedKeys 允许出现的参数名清单
     */
    public static void requireAllowedKeys(Map<String, String[]> allParams, Set<String> allowedKeys) {
        for (String key : allParams.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "未知查询参数: " + key);
            }
        }
    }

    /**
     * 要求每个出现的参数都只传了一个值。{@code status=1&status=2} 这类重复传参
     * 会被 MVC 标量绑定静默取首值，属于契约未定义的输入，一律 400。
     *
     * @param allParams 本次请求的原始查询参数全集（数组形态）
     */
    public static void requireSingleValueForEachPresentKey(Map<String, String[]> allParams) {
        for (Map.Entry<String, String[]> entry : allParams.entrySet()) {
            String[] values = entry.getValue();
            if (values == null || values.length != 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "查询参数重复传值: " + entry.getKey());
            }
        }
    }

    /**
     * 枚举参数取值必须严格匹配白名单（如 {@code 0x1} 会被 Spring 的
     * {@code Integer.decode} 解析为十六进制、{@code 01}、带空白的变体一律 400）。
     * 参数未出现不校验；空字符串不在白名单内，自然被拒绝。
     *
     * @param allParams     本次请求的原始查询参数全集（数组形态）
     * @param key           枚举参数名
     * @param allowedValues 允许的原始取值
     */
    public static void requireStrictEnumValue(Map<String, String[]> allParams, String key,
            Set<String> allowedValues) {
        String[] values = allParams.get(key);
        if (values == null) {
            return;
        }
        if (values.length != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询参数重复传值: " + key);
        }
        String value = values[0];
        if (!allowedValues.contains(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询参数取值非法: " + key);
        }
    }
}
