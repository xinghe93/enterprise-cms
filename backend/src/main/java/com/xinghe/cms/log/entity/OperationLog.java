package com.xinghe.cms.log.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作审计日志，对应 sys_operation_log 表。
 * 日志只增不改；created_at 由数据库默认值生成，Java 侧只读。
 */
@Getter
@Setter
@TableName("sys_operation_log")
public class OperationLog {

    /** 日志 ID，数据库自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 ID；未登录操作为 null。 */
    private Long userId;

    /** 操作编码，取值见 OpenAPI 的 x-audit-operation，例如 article.approve。 */
    private String operation;

    /** 请求路径，只保存路径，不含查询参数。 */
    private String requestUri;

    /** HTTP 方法，例如 GET、POST。 */
    private String httpMethod;

    /** 执行结果：SUCCESS 或 FAILURE。 */
    private String result;

    /** 执行耗时，单位毫秒。 */
    private Long durationMs;

    /** 记录时间，由数据库 created_at 默认值生成，插入和更新时不由 MyBatis-Plus 赋值。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
