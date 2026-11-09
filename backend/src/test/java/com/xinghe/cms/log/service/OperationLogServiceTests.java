package com.xinghe.cms.log.service;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 断言同时覆盖"是否查询了 Mapper"与"传给 Mapper 的条件对象"，不依赖调用顺序。
 */
class OperationLogServiceTests {

    private final AtomicReference<OperationLogQuery> capturedQuery = new AtomicReference<>();
    private final AtomicBoolean detailMapperCalled = new AtomicBoolean(false);
    private final AtomicInteger pageMapperCalls = new AtomicInteger();

    private OperationLogServiceImpl service;

    @BeforeEach
    void setUp() {
        OperationLogMapper mapper = (OperationLogMapper) Proxy.newProxyInstance(
                OperationLogMapper.class.getClassLoader(),
                new Class<?>[]{OperationLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectLogPage" -> {
                        pageMapperCalls.incrementAndGet();
                        Page<OperationLogVO> page = requirePage(args[0]);
                        capturedQuery.set(requireQuery(args[1]));
                        page.setRecords(List.of(sampleLog(1L)));
                        page.setTotal(1);
                        yield page;
                    }
                    case "selectLogDetail" -> {
                        detailMapperCalled.set(true);
                        Long id = requireLong(args[0]);
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
        assertThat(pageMapperCalls).hasValue(1);
        // 查询层使用复制后的条件对象，不复用调用方实例。
        assertThat(capturedQuery.get()).isNotSameAs(query);
    }

    @Test
    void pageDoesNotMutateCallerQuery() {
        OperationLogQuery query = new OperationLogQuery();
        query.setOperation("   article.approve   ");

        service.page(query);

        assertThat(query.getOperation()).isEqualTo("   article.approve   ");
        assertThat(capturedQuery.get()).isNotSameAs(query);
        assertThat(capturedQuery.get().getOperation()).isEqualTo("article.approve");
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
        assertThat(pageMapperCalls).hasValue(0);
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

    private static OperationLogQuery requireQuery(Object argument) {
        if (argument instanceof OperationLogQuery query) {
            return query;
        }
        throw new IllegalStateException("selectLogPage 的第二个参数应为 OperationLogQuery，实际为 "
                + (argument == null ? "null" : argument.getClass().getName()));
    }

    @SuppressWarnings("unchecked")
    private static Page<OperationLogVO> requirePage(Object argument) {
        if (argument instanceof Page<?> page) {
            return (Page<OperationLogVO>) page;
        }
        throw new IllegalStateException("selectLogPage 的第一个参数应为 Page，实际为 "
                + (argument == null ? "null" : argument.getClass().getName()));
    }

    private static Long requireLong(Object argument) {
        if (argument instanceof Long id) {
            return id;
        }
        throw new IllegalStateException("selectLogDetail 的参数应为 Long，实际为 "
                + (argument == null ? "null" : argument.getClass().getName()));
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
