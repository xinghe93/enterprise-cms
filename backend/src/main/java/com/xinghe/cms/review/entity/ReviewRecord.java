package com.xinghe.cms.review.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审核记录（cms_review）：一条记录代表一次"通过/驳回"的审核动作。
 *
 * <p>reviewed_at 由数据库默认值 CURRENT_TIMESTAMP(3) 生成，Java 侧永不写入，
 * 避免应用服务器与数据库时钟不一致。</p>
 */
@Getter
@Setter
@TableName("cms_review")
public class ReviewRecord {

    /** 审核通过：写入 cms_review.result。 */
    public static final String RESULT_APPROVED = "APPROVED";

    /** 审核驳回：写入 cms_review.result。 */
    public static final String RESULT_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long articleId;

    private Long reviewerId;

    private String result;

    private String comment;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime reviewedAt;
}
