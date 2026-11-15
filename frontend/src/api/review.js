/**
 * 审核工作台接口封装（D 模块）。
 *
 * <p>本文件的纯函数（查询参数构造、ID 校验、时间格式化）不依赖浏览器环境，
 * 可直接在 node --test 下加载；真正的 api 客户端在调用时动态导入，避免测试
 * 环境连带加载 router / pinia / element-plus。</p>
 *
 * <p>契约要点：ID 为 JSON 字符串（无前导零的正十进制、≤ Long.MAX_VALUE）；
 * 时间由后端返回本地"毫秒串"，前端展示时截断毫秒并替换 T 为空格，不使用 toISOString。</p>
 */

const MAX_PAGE_SIZE = 100
const DEFAULT_PAGE_SIZE = 10
const ID_PATTERN = /^[1-9][0-9]{0,18}$/
const LONG_MAX = 9223372036854775807n
const LOCAL_TIMESTAMP_PATTERN = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/

function normalizePageNumber(value, fallback) {
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed <= 0) return fallback
  return parsed
}

function normalizePageSize(value) {
  const parsed = normalizePageNumber(value, DEFAULT_PAGE_SIZE)
  return Math.min(parsed, MAX_PAGE_SIZE)
}

function normalizeNumericId(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  const text = String(value).trim()
  if (!ID_PATTERN.test(text)) return null
  if (BigInt(text) > LONG_MAX) return null
  return text
}

function toLocalTimestamp(value) {
  if (value instanceof Date) {
    if (Number.isNaN(value.getTime())) return null
    const pad = (part, length = 2) => String(part).padStart(length, '0')
    const date = `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
    const time = `${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
    return `${date} ${time}`
  }
  if (typeof value !== 'string') return null
  const matched = value.trim().match(LOCAL_TIMESTAMP_PATTERN)
  return matched ? `${matched[1]} ${matched[2]}` : null
}

/** 文章 ID 合法：1..19 位无前导零十进制，且不超过 Long.MAX_VALUE。 */
export function isValidArticleId(value) {
  return normalizeNumericId(value) !== null
}

/** 单个本地时间展示（yyyy-MM-dd HH:mm:ss），非法或空返回 "—"。 */
export function formatLocalDateTime(value) {
  return toLocalTimestamp(value) ?? '—'
}

/** 审核时间展示：毫秒/无毫秒/空值 → "yyyy-MM-dd HH:mm:ss" / "—"。 */
export function formatReviewTime(value) {
  return toLocalTimestamp(value) ?? '—'
}

/** 待审核队列查询参数：空值剔除、keyword 去首尾空白、ID 校验、分页兜底。 */
export function buildPendingQuery({ keyword, categoryId, authorId, current, size } = {}) {
  const query = {}
  const normalizedKeyword = typeof keyword === 'string' ? keyword.trim() : ''
  if (normalizedKeyword) query.keyword = normalizedKeyword
  const normalizedCategoryId = normalizeNumericId(categoryId)
  if (normalizedCategoryId) query.categoryId = normalizedCategoryId
  const normalizedAuthorId = normalizeNumericId(authorId)
  if (normalizedAuthorId) query.authorId = normalizedAuthorId
  query.current = normalizePageNumber(current, 1)
  query.size = normalizePageSize(size)
  return query
}

/** 审核历史查询参数：分页兜底，不接受其它筛选参数。 */
export function buildReviewQuery({ current, size } = {}) {
  return {
    current: normalizePageNumber(current, 1),
    size: normalizePageSize(size),
  }
}

async function apiClient() {
  const { api } = await import('./index.js')
  return api
}

export async function listPendingReviews(query) {
  const api = await apiClient()
  return api.get('/admin/reviews/pending', { params: query })
}

export async function listReviewHistory(articleId, query) {
  const api = await apiClient()
  return api.get(`/admin/articles/${encodeURIComponent(articleId)}/reviews`, { params: query })
}

export async function approveArticle(articleId, { version, comment } = {}) {
  const api = await apiClient()
  const body = { version }
  const normalizedComment = typeof comment === 'string' ? comment.trim() : ''
  if (normalizedComment) body.comment = normalizedComment
  return api.post(`/admin/articles/${encodeURIComponent(articleId)}/approve`, body)
}

export async function rejectArticle(articleId, { version, comment } = {}) {
  const api = await apiClient()
  return api.post(`/admin/articles/${encodeURIComponent(articleId)}/reject`, {
    version,
    comment: typeof comment === 'string' ? comment.trim() : '',
  })
}
