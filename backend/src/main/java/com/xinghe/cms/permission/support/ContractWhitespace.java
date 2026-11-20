package com.xinghe.cms.permission.support;

/**
 * 契约空白规则（OpenAPI {@code pattern: \S} 的 ECMAScript {@code \s} 语义）
 * 在本模块的<b>唯一真源</b>。角色名称/编码的「非空判定」与「去首尾空白」
 * 在 HTTP DTO 必填校验（{@code @ContractNotBlank}）和服务层规范化
 * （{@link com.xinghe.cms.permission.service.impl.RoleAdminServiceImpl}）
 * 都委托本类，避免两套定义打架——Java {@code String.isBlank()} /
 * {@code Character.isWhitespace} 与契约 {@code \s} 的差集见
 * {@link #isWhitespace(int)}。
 *
 * <p>不借用 {@link Character#isWhitespace(int)}：它两头都不对——
 * <ul>
 *   <li>多认 U+001C–U+001F（信息分隔控制符）：按契约它们是非空白字符，
 *       纯 U+001C 的名称合法、首尾出现也原样保留，不能裁剪或误拒；</li>
 *   <li>少认 NBSP 家族与 BOM：U+00A0、U+202F 等 Zs 字符与 U+FEFF 在
 *       Java 判定中不是空白，仅含这些的输入会「看起来为空」却通过校验。</li>
 * </ul>
 * 因此按 ECMAScript 规范精确枚举 {@code \s} = WhiteSpace + LineTerminator：
 * Zs 空格分隔符 + U+2028/U+2029 + 控制空白 U+0009/U+000A/U+000B/U+000C/
 * U+000D + U+FEFF。</p>
 *
 * <p>本类只提供与契约一致的两个原语：{@link #isWhitespace(int)} 判定单个
 * 码点、{@link #isBlank(String)} 判定整串是否全为契约空白、
 * {@link #strip(String)} 去除首尾契约空白（内部空白原样保留）。
 * 判空与裁剪共用 {@link #isWhitespace(int)}，保证「判定同源」。</p>
 */
public final class ContractWhitespace {

    private ContractWhitespace() {
    }

    /**
     * 判断单个 Unicode 码点是否为契约（ECMAScript {@code \s}）空白。
     * 见类说明：不复用 {@link Character#isWhitespace(int)}，按规范精确枚举。
     */
    public static boolean isWhitespace(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.SPACE_SEPARATOR            // Zs：\u0020 \u00A0 \u1680 \u2000-\u200A \u202F \u205F \u3000
                || type == Character.LINE_SEPARATOR          // \u2028
                || type == Character.PARAGRAPH_SEPARATOR     // \u2029
                || codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0B
                || codePoint == 0x0C || codePoint == 0x0D    // 控制空白（\t \n \v \f \r）
                || codePoint == 0xFEFF;                      // ZWNBSP/BOM（ECMAScript WhiteSpace 成员）
    }

    /**
     * 判断整串是否「全为契约空白」（含空串、null）。对应 OpenAPI
     * {@code pattern: \S}：串中<b>至少有一个</b>非 {@code \s} 码点才算非空白。
     * 与 {@link #strip(String)} 同源，供 DTO 必填校验与服务层判空共用。
     */
    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (!isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /**
     * 去除首尾的契约空白（见 {@link #isWhitespace(int)}），内部空白原样保留。
     * 与 {@link #isBlank(String)} 共用同一套判定，避免「裁剪用一套、判空用另一套」。
     */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }
}
