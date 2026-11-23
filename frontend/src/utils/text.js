/**
 * 文本长度与 Unicode 代码点的通用工具。
 *
 * 契约口径：字段长度按 Unicode 代码点（字符）计数，与后端 AdminJson.text() /
 * DepartmentJson.text() 的 codePointCount 一致。原生 input 的 maxlength 属性按
 * UTF-16 代码单元截断，含增补平面字符（如「𠮷」或 emoji，每字占 2 个 char）时
 * 会把合法输入截半——受契约限制的输入框必须改用本工具逐代码点截断。
 */

/** 按 Unicode 代码点计数（与后端 codePointCount 同口径）。 */
export function countCodePoints(value) {
  return Array.from(String(value ?? '')).length
}

/** 按 Unicode 代码点截断到 max 个字符，超出部分丢弃；null/undefined 视为空串。 */
export function clampCodePoints(value, max) {
  const chars = Array.from(String(value ?? ''))
  if (chars.length <= max) return String(value ?? '')
  return chars.slice(0, max).join('')
}
