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

/** 筛选 ID 校验：空值跳过；非空但不合契约时返回具体字段提示（页面据此提示并保留当前结果）。 */
export function validatePendingFilters({ categoryId, authorId } = {}) {
  const errors = []
  const isBlank = (value) => value === undefined || value === null || String(value).trim() === ''
  if (!isBlank(categoryId) && !isValidArticleId(String(categoryId).trim())) {
    errors.push('分类 ID 无效：请输入 1–19 位正整数，且不超过 Long 上限')
  }
  if (!isBlank(authorId) && !isValidArticleId(String(authorId).trim())) {
    errors.push('作者 ID 无效：请输入 1–19 位正整数，且不超过 Long 上限')
  }
  return errors
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

/** 当前账号有效权限编码（OpenAPI GET /api/auth/permissions，x-owner B；返回 string[]）。 */
export async function fetchMyPermissions({ signal } = {}) {
  const api = await apiClient()
  // 该接口 x-owner 为 B，dev 尚未实现时返回 404；这属于"权限状态未知"而非页面错误，
  // 用 silent 关闭全局错误提示，由页面按 unavailable 状态显式提示。
  // signal 供页面在卸载/请求被替代时取消旧请求，避免旧响应覆盖当前会话权限。
  return api.get('/auth/permissions', { silent: true, ...(signal ? { signal } : {}) })
}

export async function listPendingReviews(query, config = {}) {
  const api = await apiClient()
  return api.get('/admin/reviews/pending', { ...config, params: query })
}

export async function listReviewHistory(articleId, query, config = {}) {
  const api = await apiClient()
  return api.get(`/admin/articles/${encodeURIComponent(articleId)}/reviews`, { ...config, params: query })
}

/** 审核弹窗内查看正文（GET /api/admin/articles/{id}，权限 article:read）。 */
export async function getArticleDetail(articleId, { signal } = {}) {
  if (!isValidArticleId(articleId)) throw new TypeError('无效的文章 ID')
  const api = await apiClient()
  return api.get(`/admin/articles/${encodeURIComponent(articleId)}`, { ...(signal ? { signal } : {}) })
}

export const MAX_COMMENT_LENGTH = 1000

// 审核意见：trim 后按 Unicode 码点限长，超长直接拒绝（不静默裁剪）。
export function normalizeComment(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  if ([...text].length > MAX_COMMENT_LENGTH) {
    throw new TypeError(`审核意见不能超过 ${MAX_COMMENT_LENGTH} 字`)
  }
  return text
}

export async function approveArticle(articleId, { version, comment } = {}, config = {}) {
  const api = await apiClient()
  const body = { version }
  const normalizedComment = normalizeComment(comment)
  if (normalizedComment) body.comment = normalizedComment
  return api.post(`/admin/articles/${encodeURIComponent(articleId)}/approve`, body, config)
}

export async function rejectArticle(articleId, { version, comment } = {}, config = {}) {
  const api = await apiClient()
  const normalizedComment = normalizeComment(comment)
  if (!normalizedComment) throw new TypeError('驳回意见不能为空')
  return api.post(`/admin/articles/${encodeURIComponent(articleId)}/reject`, {
    version, comment: normalizedComment,
  }, config)
}
