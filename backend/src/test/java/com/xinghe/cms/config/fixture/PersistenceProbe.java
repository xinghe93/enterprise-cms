package com.xinghe.cms.config.fixture;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

/** 仅用于测试公共映射约定，不关联任何业务表。 */
@Getter
@Setter
@TableName("enterprise_cms_config_probe")
public class PersistenceProbe {

    @TableId
    private Long id;

    private String displayName;

    @Version
    private Integer version = 0;

    @TableLogic
    private Integer isDeleted = 0;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
