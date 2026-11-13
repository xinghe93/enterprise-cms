package com.xinghe.cms.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.vo.OperationLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 操作审计日志 Mapper：单表写入使用 BaseMapper，分页查询与详情使用 XML 中的明确 SQL。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /**
     * 按条件分页查询日志，并 LEFT JOIN sys_user 取得操作人当前显示名称。
     * 计数由 MyBatis-Plus 分页插件生成，不手写 count。
     */
    Page<OperationLogVO> selectLogPage(Page<OperationLogVO> page, @Param("query") OperationLogQuery query);

    /**
     * 查询单条日志详情，条件与分页查询使用同一 SELECT 结构。
     */
    OperationLogVO selectLogDetail(@Param("id") Long id);
}
