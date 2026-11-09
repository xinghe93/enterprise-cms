package com.xinghe.cms.log.dto;

import java.time.LocalDateTime;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.request.PageQuery;
import com.xinghe.cms.common.response.ResultCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 操作日志分页查询条件；条件之间为 AND，省略表示不筛选。
 * 分页参数（current/size）继承自 {@link PageQuery}。
 */
@Getter
@Setter
public class OperationLogQuery extends PageQuery {

    /** 操作人 ID，精确匹配；合法但不存在时返回空分页。 */
    @Min(value = 1, message = "用户 ID 必须大于零")
    private Long userId;

    /** 操作编码，去首尾空白后精确匹配；空串不筛选。 */
    @Size(max = 100, message = "操作类型长度不能超过 100")
    private String operation;

    /** 执行结果，只接受 SUCCESS / FAILURE。 */
    @Pattern(regexp = "SUCCESS|FAILURE", message = "结果只能是 SUCCESS 或 FAILURE")
    private String result;

    /**
     * 记录时间起点，包含边界。
     * 只接受本地时间（yyyy-MM-dd'T'HH:mm:ss[.SSS]），带 Z 或时区偏移一律 400。
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]")
    private LocalDateTime createdFrom;

    /**
     * 记录时间终点，不包含边界；同时提供时起点必须早于终点。
     * 只接受本地时间（yyyy-MM-dd'T'HH:mm:ss[.SSS]），带 Z 或时区偏移一律 400。
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]")
    private LocalDateTime createdTo;

    /**
     * 去首尾空白后的操作编码；null 或空白都视为不筛选。
     */
    public String normalizedOperation() {
        if (operation == null) {
            return null;
        }
        String trimmed = operation.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 复制查询条件并把 operation 归一化为去首尾空白后的值，返回新对象。
     * 查询层不就地修改调用方传入的对象，避免同一条件被复用时行为漂移。
     */
    public OperationLogQuery copyWithNormalizedOperation() {
        OperationLogQuery copy = new OperationLogQuery();
        copy.setUserId(userId);
        copy.setOperation(normalizedOperation());
        copy.setResult(result);
        copy.setCreatedFrom(createdFrom);
        copy.setCreatedTo(createdTo);
        copy.setCurrent(getCurrent());
        copy.setSize(getSize());
        return copy;
    }

    /**
     * 校验左闭右开时间区间：创建时间起点必须早于终点。
     */
    public void validateTimeRange() {
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "起始时间必须早于结束时间");
        }
    }
}
