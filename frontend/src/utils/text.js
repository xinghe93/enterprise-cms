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

/**
 * 原生 input 的 @input 处理器：截断后把结果同步写回 DOM。
 *
 * 仅更新 model 不够——超限时 clampCodePoints 返回与当前 model 相同的值，Vue
 * 不会重渲染，输入框仍显示刚敲进去的第 129 字，但提交的是原 128 字，形成
 * 「显示值与提交值不同」（老师复审 #2）。截断结果一律写回 DOM，保证输入框
 * 与 model 始终一致。
 *
 * 注意：这里绝不能顺手 trim——逐字输入「Mary Jane」时敲完「Mary 」空格会被
 * 立即吃掉，最终只能得到「MaryJane」（老师复审 P2）。首尾空白只在 @blur 或
 * 提交时处理，见 trimOnBlur / 各 View 的 save/search。
 */
export function syncClampedInput(event, max) {
  const result = clampCodePoints(event.target.value, max)
  if (event.target.value !== result) event.target.value = result
  return result
}

/**
 * 原生 input 的 @blur 处理器：失焦时才去掉首尾空白，并同步写回 DOM。
 *
 * 放在 @blur 而不是 @input：输入过程中的内部空格（如「Mary Jane」）和尚未输完
 * 的尾随空格必须原样保留，否则用户永远无法输入带空格的姓名/部门名/关键字。
 * 保留 U+3000 等全角空白的 trim 语义（与后端 String.trim() 一致）。
 */
export function trimOnBlur(event) {
  const result = String(event.target.value ?? '').trim()
  if (event.target.value !== result) event.target.value = result
  return result
}

/** 按 Unicode 代码点截断到 max 个字符，超出部分丢弃；null/undefined 视为空串。 */
export function clampCodePoints(value, max) {
  const chars = Array.from(String(value ?? ''))
  if (chars.length <= max) return String(value ?? '')
  return chars.slice(0, max).join('')
}
