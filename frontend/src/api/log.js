// 操作日志接口（D 模块）。
// api 通过动态 import 引入：这样本文件的纯函数可以在 node --test 下单独测试，
// 不会因为 index.js 依赖 router/pinia/element-plus 而无法加载。
async function apiClient() {
  const { api } = await import('./index.js')
  return api
}

const DATE_TIME_PATTERN = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/

function pad2(value) {
  return String(value).padStart(2, '0')
}

function normalizePositiveInt(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') return Number.isInteger(value) && value > 0 ? value : null
  if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
    const number = Number(value.trim())
    return Number.isSafeInteger(number) && number > 0 ? number : null
  }
  return null
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

  const user = normalizePositiveInt(userId)
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

/** 查询单条日志详情。详情接口不接受任何查询参数。 */
export async function getLog(id) {
  const api = await apiClient()
  return api.get(`/admin/logs/${encodeURIComponent(String(id))}`)
}
