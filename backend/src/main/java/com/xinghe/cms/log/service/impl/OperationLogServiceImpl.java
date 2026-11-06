package com.xinghe.cms.log.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.mapper.OperationLogMapper;
import com.xinghe.cms.log.service.OperationLogService;
import com.xinghe.cms.log.vo.OperationLogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作日志查询实现：只读，不负责日志写入（写入由审计切面在后续提交提供）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;

    @Override
    public PageResult<OperationLogVO> page(OperationLogQuery query) {
        if (query == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询条件不能为空");
        }
        query.validateTimeRange();
        // 去首尾空白后为空表示不筛选，交给 XML 的 if 判断。
        query.setOperation(query.normalizedOperation());

        Page<OperationLogVO> page = query.toPage();
        operationLogMapper.selectLogPage(page, query);
        return PageResult.from(page);
    }

    @Override
    public OperationLogVO detail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "日志 ID 必须大于零");
        }
        OperationLogVO detail = operationLogMapper.selectLogDetail(id);
        if (detail == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "日志不存在");
        }
        return detail;
    }
}
