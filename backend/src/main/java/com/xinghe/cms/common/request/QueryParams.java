package com.xinghe.cms.common.request;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import java.util.Map;
import java.util.Set;

/**
 * 查询参数的契约级校验（契约 8.x：未知查询参数、空值枚举参数一律 400，
 * 不得因 MVC 绑定忽略未声明参数而放行）。
 *
 * <p>用法：Controller 方法追加 {@code @RequestParam Map<String, String> allParams}
 * 接收原始参数全集，先经 {@link #requireAllowedKeys} 过滤未知键，
 * 再对"出现即必须有值"的可选枚举参数调用 {@link #requireNonBlankValueIfPresent}。
 * JSON 请求体的未知字段由 Jackson 严格模式全局拦截（A 的配置），查询参数无全局钩子，
 * 由各 Controller 显式调用本工具。</p>
 */
public final class QueryParams {

    private QueryParams() {
    }

    /**
     * 校验实际到达的查询参数键都在允许清单内，任何未知键返回 400。
     *
     * @param allParams  本次请求的原始查询参数全集
     * @param allowedKeys 允许出现的参数名清单
     */
    public static void requireAllowedKeys(Map<String, String> allParams, Set<String> allowedKeys) {
        for (String key : allParams.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "未知查询参数: " + key);
            }
        }
    }

    /**
     * 要求可选参数一旦出现就必须有非空白取值（如 {@code status=}），
     * 未出现（键不存在）不校验。取值本身是否属于合法枚举由后续绑定/服务层校验。
     *
     * @param allParams 本次请求的原始查询参数全集
     * @param key       要求非空白的参数名
     */
    public static void requireNonBlankValueIfPresent(Map<String, String> allParams, String key) {
        String value = allParams.get(key);
        if (value != null && value.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询参数取值不能为空: " + key);
        }
    }
}
