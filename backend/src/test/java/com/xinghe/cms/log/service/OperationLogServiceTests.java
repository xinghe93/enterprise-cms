package com.xinghe.cms.log.service;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.PageResult;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.dto.OperationLogQuery;
import com.xinghe.cms.log.mapper.OperationLogMapper;
import com.xinghe.cms.log.service.impl.OperationLogServiceImpl;
import com.xinghe.cms.log.vo.OperationLogVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日志查询 Service 的规则测试：不启动 Spring、不连接 MySQL。
 * 用 JDK 动态代理代替 Mapper（本机 JDK 25 下 Mockito 无法自附加）。
 */
class OperationLogServiceTests {

    private final AtomicReference<OperationLogQuery> capturedQuery = new AtomicReference<>();
    private final AtomicBoolean detailMapperCalled = new AtomicBoolean(false);

    private OperationLogServiceImpl service;

    @BeforeEach
    void setUp() {
        OperationLogMapper mapper = (OperationLogMapper) Proxy.newProxyInstance(
                OperationLogMapper.class.getClassLoader(),
                new Class<?>[]{OperationLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectLogPage" -> {
                        Page<OperationLogVO> page = (Page<OperationLogVO>) args[0];
                        capturedQuery.set((OperationLogQuery) args[1]);
                        page.setRecords(List.of(sampleLog(1L)));
                        page.setTotal(1);
                        yield page;
                    }
                    case "selectLogDetail" -> {
                        detailMapperCalled.set(true);
                        Long id = (Long) args[0];
                        yield id == 99L ? null : sampleLog(id);
                    }
                    default -> defaultValue(method.getReturnType());
                });
        service = new OperationLogServiceImpl(mapper);
    }

    @Test
    void pageReturnsRecordsAndPageMetadata() {
        OperationLogQuery query = new OperationLogQuery();

        PageResult<OperationLogVO> result = service.page(query);

        assertThat(result.records()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.current()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(capturedQuery.get()).isSameAs(query);
    }

    @Test
    void pageNormalizesBlankOperationBeforeQuerying() {
        OperationLogQuery query = new OperationLogQuery();
        query.setOperation("   article.approve   ");

        service.page(query);

        assertThat(capturedQuery.get().getOperation()).isEqualTo("article.approve");
    }

    @Test
    void pageTreatsBlankOperationAsNoFilter() {
        OperationLogQuery query = new OperationLogQuery();
        query.setOperation("   ");

        service.page(query);

        assertThat(capturedQuery.get().getOperation()).isNull();
    }

    @Test
    void pageRejectsInvalidTimeRangeBeforeQuerying() {
        OperationLogQuery query = new OperationLogQuery();
        LocalDateTime sameTime = LocalDateTime.of(2026, 11, 2, 0, 0);
        query.setCreatedFrom(sameTime);
        query.setCreatedTo(sameTime);

        assertThatThrownBy(() -> service.page(query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("起始时间必须早于结束时间")
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.BAD_REQUEST));
        assertThat(capturedQuery.get()).isNull();
    }

    @Test
    void detailReturnsLogWhenFound() {
        OperationLogVO detail = service.detail(7L);

        assertThat(detail.getId()).isEqualTo(7L);
        assertThat(detailMapperCalled).isTrue();
    }

    @Test
    void detailThrowsNotFoundWhenMissing() {
        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("日志不存在")
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void detailRejectsNonPositiveIdBeforeQuerying() {
        assertThatThrownBy(() -> service.detail(0L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("日志 ID 必须大于零")
                .satisfies(exception -> assertThat(((BusinessException) exception).getResultCode())
                        .isEqualTo(ResultCode.BAD_REQUEST));
        assertThat(detailMapperCalled).isFalse();
    }

    private OperationLogVO sampleLog(Long id) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(id);
        vo.setUserId(9L);
        vo.setUserName("审核员");
        vo.setOperation("article.approve");
        vo.setRequestUri("/api/admin/articles/123/approve");
        vo.setHttpMethod("POST");
        vo.setResult("SUCCESS");
        vo.setDurationMs(15L);
        vo.setCreatedAt(LocalDateTime.of(2026, 11, 2, 16, 0, 0, 120_000_000));
        return vo;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
