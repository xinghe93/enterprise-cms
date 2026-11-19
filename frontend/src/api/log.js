// 操作日志接口（D 模块）。
// api 通过动态 import 引入：这样本文件的纯函数可以在 node --test 下单独测试，
// 不会因为 index.js 依赖 router/pinia/element-plus 而无法加载。
async function apiClient() {
  const { api } = await import('./index.js')
  return api
}

const DATE_TIME_PATTERN = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/
// 后端业务 ID 为 Long：1–19 位十进制、无前导零、最大值 9223372036854775807。
const BUSINESS_ID_PATTERN = /^[1-9][0-9]{0,18}$/
const LONG_MAX = 9223372036854775807n

function pad2(value) {
  return String(value).padStart(2, '0')
}

/** 分页数字（current/size）用：只接受正整数。 */
function normalizePositiveInt(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') return Number.isInteger(value) && value > 0 ? value : null
  if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
    const number = Number(value.trim())
    return Number.isSafeInteger(number) && number > 0 ? number : null
  }
  return null
}

/**
 * 业务 ID（userId、日志详情 ID）用：保持字符串，避免 Number 精度丢失。
 * 只做格式与 Long 范围校验，合法时返回规范化字符串，否则返回 null。
 */
export function normalizeBusinessId(value) {
  if (value === null || value === undefined) return null
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return null
  const text = String(value).trim()
  if (!BUSINESS_ID_PATTERN.test(text)) return null
  return BigInt(text) <= LONG_MAX ? text : null
}

/** 把 Date 或已有时分秒字符串整理成后端只接受的本地时间 yyyy-MM-dd'T'HH:mm:ss（不带 Z）。 */
export function formatLocalDateTime(value) {
  if (value instanceof Date) {
    if (Number.isNaN(value.getTime())) return null
    const date = `${value.getFullYear()}-${pad2(value.getMonth() + 1)}-${pad2(value.getDate())}`
    return `${date}T${pad2(value.getHours())}:${pad2(value.getMinutes())}:${pad2(value.getSeconds())}`
  }
  if (typeof value === 'string') {
    const match = value.trim().match(DATE_TIME_PATTERN)
    return match ? `${match[1]}T${match[2]}` : null
  }
  return null
}

/** 构造查询参数：只放非空值，时间转成本地格式，默认 current=1、size=10。 */
export function buildLogQuery({ userId, operation, result, timeRange, current, size } = {}) {
  const query = {}

  const user = normalizeBusinessId(userId)
  if (user !== null) query.userId = user

  const keyword = typeof operation === 'string' ? operation.trim() : ''
  if (keyword) query.operation = keyword

  if (result === 'SUCCESS' || result === 'FAILURE') query.result = result

  if (Array.isArray(timeRange) && timeRange.length === 2) {
    const createdFrom = formatLocalDateTime(timeRange[0])
    const createdTo = formatLocalDateTime(timeRange[1])
    if (createdFrom) query.createdFrom = createdFrom
    if (createdTo) query.createdTo = createdTo
  }

  query.current = normalizePositiveInt(current) ?? 1
  query.size = normalizePositiveInt(size) ?? 10
  return query
}

/** 日志 ID 按后端 Long 契约校验：1–19 位十进制、无前导零、不超过 Long.MAX_VALUE。 */
export function isValidLogId(value) {
  return normalizeBusinessId(value) !== null
}

/** 后端返回的 total 可能是 undefined/null/字符串/NaN，统一收敛为非负整数。 */
export function normalizeTotal(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed < 0) return 0
  return Math.trunc(parsed)
}

/** 把后端返回的 2026-11-08T10:00:00.000 显示为 2026-11-08 10:00:00；空值显示 —。 */
export function formatLogTime(value) {
  if (typeof value !== 'string' || !value.trim()) return '—'
  const match = value.trim().match(DATE_TIME_PATTERN)
  return match ? `${match[1]} ${match[2]}` : value
}

/** 分页查询日志；参数非法时前端先剔除，避免触发后端 400。 */
export async function listLogs(query) {
  const api = await apiClient()
  return api.get('/admin/logs', { params: buildLogQuery(query) })
}

/** 查询单条日志详情。详情接口不接受任何查询参数；ID 非法时直接拒绝，避免请求 /admin/logs/undefined。 */
export async function getLog(id) {
  const logId = normalizeBusinessId(id)
  if (logId === null) {
    throw new TypeError('无效的日志 ID')
  }
  const api = await apiClient()
  return api.get(`/admin/logs/${logId}`)
}
