package com.xinghe.cms.user.mapper.row;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 启停事务内加行锁读取的状态快照。
 *
 * <p>{@code version} 取自 {@code sys_user.updated_at}，随每次状态写单调更新，
 * 提交后传给会话服务做「按版本的可重试全量撤销」：版本更高才轮换纪元，
 * 使撤销失败可补偿、重复请求幂等、延迟的旧撤销不误伤新会话。</p>
 */
public record UserStatusSnapshot(int status, LocalDateTime updatedAt) {

    /** 状态变更版本号：updated_at 的 UTC 毫秒时间戳；时间缺失时取 0（永不触发撤销）。 */
    public long version() {
        return updatedAt == null ? 0L : updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
