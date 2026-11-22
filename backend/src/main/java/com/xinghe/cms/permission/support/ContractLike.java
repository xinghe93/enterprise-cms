package com.xinghe.cms.permission.support;

/**
 * LIKE 字面转义（契约 8.2）：{@code %}、{@code _} 和转义符 {@code !} 作为
 * 普通字符处理，一律前置转义符 {@code !}，其余字符原样保留——不接受用户
 * 自带 SQL 通配查询。对应 Mapper SQL 中的 {@code LIKE ... ESCAPE '!'}。
 *
 * <p>权限分页与角色分页共用同一真源（原先在 {@code RoleQueryServiceImpl}
 * 内私有实现，权限分页引入后提取到本支持类，避免同一契约规则两处维护）。</p>
 */
public final class ContractLike {

    private ContractLike() {
    }

    /**
     * 对将绑定进 {@code LIKE CONCAT('%', ?, '%') ESCAPE '!'} 的关键词做转义：
     * {@code !} → {@code !!}、{@code %} → {@code !%}、{@code _} → {@code !_}。
     */
    public static String escape(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            if (ch == '!' || ch == '%' || ch == '_') {
                builder.append('!');
            }
            builder.append(ch);
        }
        return builder.toString();
    }
}
