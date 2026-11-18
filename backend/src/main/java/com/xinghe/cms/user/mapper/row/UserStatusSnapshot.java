package com.xinghe.cms.user.mapper.row;

/**
 * 启停事务内加行锁读取的状态快照。
 *
 * <p>{@code statusVersion} 取自 {@code sys_user.status_version}（见 004 迁移脚本）：
 * 仅在状态真正发生变化（0↔1）时由数据库原子 +1 的独立单调版本，与资料更新
 * 无关，也不依赖 updated_at 的毫秒精度（同一毫秒内的两次状态变更必然重复）。
 * 提交前后传给会话服务做「按版本的可重试全量撤销」：版本更高才轮换纪元，
 * 使撤销失败可补偿、重复请求幂等、延迟的旧撤销不误伤新会话。</p>
 *
 * <p>构造器参数刻意使用基本类型 {@code int}/{@code long}，Mapper 的
 * {@code <constructor>} 映射必须以 {@code _int}/{@code _long} 声明对应
 * {@code javaType}：MyBatis 按声明类型精确匹配构造器，声明成
 * {@code java.lang.Integer} 会找不到 {@code (int, long)} 构造器，
 * 查询时直接抛映射异常（PR #39 审查 P1）。</p>
 */
public record UserStatusSnapshot(int status, long statusVersion) {
}
