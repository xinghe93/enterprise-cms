package com.xinghe.cms.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghe.cms.log.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志 Mapper；单表写入使用 BaseMapper，多表查询在后续提交中以 XML 补充。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
