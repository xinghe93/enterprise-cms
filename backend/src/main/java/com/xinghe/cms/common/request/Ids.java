package com.xinghe.cms.common.request;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import java.util.regex.Pattern;

/**
 * 路径与查询参数中的资源 ID 解析。
 *
 * <p>契约：JSON 中业务 ID 使用十进制字符串，路径/查询参数采用相同规则
 * （OpenAPI {@code Id} schema：{@code ^[1-9][0-9]{0,18}$}，即不带前导零、
 * 不含 0、长度 1 到 19 位）。解析失败一律返回 400。</p>
 */
public final class Ids {

    private static final Pattern PATTERN = Pattern.compile("[1-9][0-9]{0,18}");

    private Ids() {
    }

    /**
     * 解析路径/查询参数中的 ID，不合法时抛出 400。
     *
     * @param raw 原始字符串，如 {@code "37"}
     * @return 对应的 Long 值
     */
    public static long parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "资源 ID 格式不正确");
        }
        return Long.parseLong(raw);
    }
}
