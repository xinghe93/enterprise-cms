package com.xinghe.cms.log.service;

import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.vo.OperationLogVO;

/**
 * 操作日志查询服务。
 */
public interface OperationLogService {

    /**
     * 按条件分页查询日志；条件全部为 AND，默认按 createdAt、id 倒序。
     */
    PageResult<OperationLogVO> page(OperationLogQuery query);

    /**
     * 查询单条日志详情；不存在时抛出 NOT_FOUND。
     */
    OperationLogVO detail(Long id);
}
