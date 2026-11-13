package com.xinghe.cms.log.vo;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作日志对外视图；JSON 中的业务 ID 使用十进制字符串，时间为毫秒精度本地时间。
 * userName 通过 sys_user 的 LEFT JOIN 得到，用户不存在时为空。
 */
@Getter
@Setter
public class OperationLogVO {

    /** 日志 ID，JSON 中输出为字符串。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    /** 操作人 ID，未登录操作为 null；JSON 中输出为字符串。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;

    /** 操作人当前显示名称，取自 sys_user.real_name，可空。 */
    private String userName;

    /** 操作编码，例如 article.approve。 */
    private String operation;

    /** 请求路径，不含查询参数。 */
    private String requestUri;

    /** HTTP 方法。 */
    private String httpMethod;

    /** 执行结果：SUCCESS 或 FAILURE。 */
    private String result;

    /** 执行耗时，单位毫秒。 */
    private Long durationMs;

    /** 记录时间，毫秒精度本地时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime createdAt;
}
