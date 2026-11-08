package com.xinghe.cms.log.service;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.xinghe.cms.log.entity.OperationLog;
import com.xinghe.cms.log.mapper.OperationLogMapper;
import com.xinghe.cms.log.service.impl.AuditLogWriterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计写入组件测试：用 JDK 动态代理代替 Mapper（本机 JDK 25 下 Mockito 不可用），不连接 MySQL。
 */
class AuditLogWriterTests {

    private final List<OperationLog> inserted = new ArrayList<>();
    private final AtomicInteger insertCalls = new AtomicInteger();

    private AuditLogWriter writer;

    @BeforeEach
    void setUp() {
        OperationLogMapper mapper = (OperationLogMapper) Proxy.newProxyInstance(
                OperationLogMapper.class.getClassLoader(),
                new Class<?>[]{OperationLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        insertCalls.incrementAndGet();
                        inserted.add((OperationLog) args[0]);
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        writer = new AuditLogWriterImpl(mapper);
    }

    @Test
    void writeInsertsEntityThroughMapper() {
        OperationLog entry = sampleEntry();

        writer.write(entry);

        assertThat(insertCalls).hasValue(1);
        assertThat(inserted).containsExactly(entry);
    }

    @Test
    void writeInNewTransactionAlsoInsertsEntity() {
        OperationLog entry = sampleEntry();

        writer.writeInNewTransaction(entry);

        assertThat(insertCalls).hasValue(1);
        assertThat(inserted).containsExactly(entry);
        assertThat(entry.getCreatedAt()).isNull();
    }

    private OperationLog sampleEntry() {
        OperationLog entry = new OperationLog();
        entry.setOperation("article.approve");
        entry.setRequestUri("/api/admin/articles/123/approve");
        entry.setHttpMethod("POST");
        entry.setResult("SUCCESS");
        entry.setDurationMs(12L);
        return entry;
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
