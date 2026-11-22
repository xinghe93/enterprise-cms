/**
 * 「角色与权限」页的纯逻辑（B 模块）。
 *
 * 契约依据：《接口与协作约定 V1.0》3.1 / 8.1 ——
 *  - 角色编码、权限编码创建后不可修改；
 *  - 名称/编码「先按模型校验类型与长度，再去首尾空白并验证非空」
 *    （OpenAPI pattern \S：必须含非空白字符）；
 *  - 权限项 API 类型必须提供有效 path（^/api/(auth|admin|portal)(/[^\s?#]*)?$）
 *    与大写 method；MENU/BUTTON 的 method 必须为 null；
 *  - 权限完整 PUT：permissionName/path/method 都必填（MENU/BUTTON 的 path/method
 *    提交 null），permissionCode 与 permissionType 不可改；
 *  - 权限全量替换 permissionIds：唯一、最多 100 项、空数组表示清空。
 *
 * 不依赖 Vue / DOM，便于 Node 原生测试直接覆盖这些规则。
 */

const MAX_LONG = 9223372036854775807n
const ID_PATTERN = /^[1-9][0-9]{0,18}$/

/** 权限项可选的 HTTP 方法（契约 PermissionCreate/Update 的 method 枚举）。 */
export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

/** 权限项类型（契约 PermissionCreate 的 permissionType 枚举）。 */
export const PERMISSION_TYPES = ['MENU', 'BUTTON', 'API']

/** API 类型权限的 path 约束：必须在 /api/auth、/api/admin、/api/portal 下，无空白/query/fragment，可含 {id}。 */
export const API_PATH_PATTERN = /^\/api\/(auth|admin|portal)(\/[^\s?#]*)?$/

export const ROLE_CODE_MAX = 64
export const TEXT_MAX = 100
export const PATH_MAX = 255
export const PERMISSION_IDS_MAX = 100

/** ID 必须是正整数十进制字符串，且不超过 Java Long.MAX_VALUE。 */
export function isValidPermissionId(value) {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) return false
  try {
    return BigInt(value) <= MAX_LONG
  } catch {
    return false
  }
}

/**
 * 名称/编码统一处理（契约 8.1）：先验原始长度（「类型及原始长度先按模型校验」），
 * 再去首尾空白并验证非空（pattern \S）。非法时返回 null，由调用方给出行内提示。
 */
export function normalizeText(value, max) {
  if (value === null || value === undefined) return null
  const raw = String(value)
  if (raw.length > max) return null
  const trimmed = raw.trim()
  if (trimmed.length === 0) return null
  if (trimmed.length > max) return null
  return trimmed
}

/**
 * 角色表单校验：返回 { roleCode?, roleName? } 错误信息对象（无错字段不出现）。
 * 仅创建时校验 roleCode；编辑只改 roleName。
 */
export function validateRoleForm(form, { withCode = true } = {}) {
  const errors = {}
  if (withCode) {
    const code = normalizeText(form.roleCode, ROLE_CODE_MAX)
    if (code === null) {
      errors.roleCode = form.roleCode && String(form.roleCode).length > ROLE_CODE_MAX
        ? `角色编码不能超过 ${ROLE_CODE_MAX} 个字符`
        : '请输入角色编码（去首尾空白后不能为空）'
    }
  }
  const name = normalizeText(form.roleName, TEXT_MAX)
  if (name === null) {
    errors.roleName = form.roleName && String(form.roleName).length > TEXT_MAX
      ? `角色名称不能超过 ${TEXT_MAX} 个字符`
      : '请输入角色名称（去首尾空白后不能为空）'
  }
  return errors
}

/** 角色创建 payload：{ roleCode, roleName }，均已去首尾空白。非法字段抛 TypeError。 */
export function buildRoleCreatePayload(form) {
  const errors = validateRoleForm(form, { withCode: true })
  if (Object.keys(errors).length > 0) throw new TypeError(Object.values(errors)[0])
  return { roleCode: form.roleCode.trim(), roleName: form.roleName.trim() }
}

/** 角色修改 payload：完整 PUT 但契约仅接受 roleName；roleCode 不可改。 */
export function buildRoleUpdatePayload(form) {
  const errors = validateRoleForm(form, { withCode: false })
  if (Object.keys(errors).length > 0) throw new TypeError(Object.values(errors)[0])
  return { roleName: form.roleName.trim() }
}

/**
 * 权限项创建表单校验：返回 { permissionCode?, permissionName?, permissionType?, path?, method? }。
 * 规则（契约 PermissionCreate 的 oneOf）：
 *  - API：path 必填且匹配 API_PATH_PATTERN、method 必须在枚举内；
 *  - MENU/BUTTON：method 必须为空（界面不提供输入），path 不接受（仅 API 元数据）。
 */
export function validatePermissionCreateForm(form) {
  const errors = {}
  const code = normalizeText(form.permissionCode, TEXT_MAX)
  if (code === null) {
    errors.permissionCode = form.permissionCode && String(form.permissionCode).length > TEXT_MAX
      ? `权限编码不能超过 ${TEXT_MAX} 个字符`
      : '请输入权限编码（去首尾空白后不能为空）'
  }
  const name = normalizeText(form.permissionName, TEXT_MAX)
  if (name === null) {
    errors.permissionName = form.permissionName && String(form.permissionName).length > TEXT_MAX
      ? `权限名称不能超过 ${TEXT_MAX} 个字符`
      : '请输入权限名称（去首尾空白后不能为空）'
  }
  if (!PERMISSION_TYPES.includes(form.permissionType)) {
    errors.permissionType = '请选择权限类型（MENU / BUTTON / API）'
  }
  if (form.permissionType === 'API' && !errors.permissionType) {
    const path = normalizeText(form.path, PATH_MAX)
    if (path === null || !API_PATH_PATTERN.test(path)) {
      errors.path = 'API 路径必须在 /api/auth、/api/admin、/api/portal 下，且不含空白、query 或 fragment'
    }
    if (!HTTP_METHODS.includes(form.method)) {
      errors.method = '请选择 HTTP 方法（GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS）'
    }
  }
  return errors
}

/**
 * 权限项创建 payload：
 *  - API：{ permissionCode, permissionName, permissionType: 'API', path, method }
 *  - MENU/BUTTON：{ permissionCode, permissionName, permissionType, path: null, method: null }
 */
export function buildPermissionCreatePayload(form) {
  const errors = validatePermissionCreateForm(form)
  if (Object.keys(errors).length > 0) throw new TypeError(Object.values(errors)[0])
  const base = {
    permissionCode: form.permissionCode.trim(),
    permissionName: form.permissionName.trim(),
    permissionType: form.permissionType,
  }
  if (form.permissionType === 'API') {
    return { ...base, path: form.path.trim(), method: form.method }
  }
  return { ...base, path: null, method: null }
}

/**
 * 权限项修改 payload（完整 PUT）：permissionName/path/method 都必填，
 * 基于数据库中的 permissionType 校验（编码与类型不可改）：
 *  - API：path/method 非空有效；
 *  - MENU/BUTTON：path/method 提交 null（表单不提供输入）。
 */
export function buildPermissionUpdatePayload(row, form) {
  // 名称校验与角色一致，复用同一 normalizeText 规则：
  const name = normalizeText(form.permissionName, TEXT_MAX)
  if (name === null) {
    throw new TypeError(form.permissionName && String(form.permissionName).length > TEXT_MAX
      ? `权限名称不能超过 ${TEXT_MAX} 个字符`
      : '请输入权限名称（去首尾空白后不能为空）')
  }
  if (row?.permissionType === 'API') {
    const path = normalizeText(form.path, PATH_MAX)
    if (path === null || !API_PATH_PATTERN.test(path)) {
      throw new TypeError('API 路径必须在 /api/auth、/api/admin、/api/portal 下，且不含空白、query 或 fragment')
    }
    if (!HTTP_METHODS.includes(form.method)) {
      throw new TypeError('请选择 HTTP 方法（GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS）')
    }
    return { permissionName: form.permissionName.trim(), path: form.path.trim(), method: form.method }
  }
  return { permissionName: form.permissionName.trim(), path: null, method: null }
}

/**
 * 权限全量替换的 permissionIds 规范化（契约 IdList：唯一、≤100、合法 ID）。
 * 先逐项拒绝 null/undefined（避免 String() 掩盖来源），再 trim 去重、逐项校验。
 * 空数组是合法输入（清空角色权限）。
 */
export function normalizePermissionIds(ids) {
  if (!Array.isArray(ids)) throw new TypeError('permissionIds 必须是数组')
  ids.forEach((id, index) => {
    if (id === null || id === undefined) {
      throw new TypeError(`权限 ID 第 ${index + 1} 项为空，请重新选择`)
    }
  })
  const unique = [...new Set(ids.map((id) => String(id).trim()))]
  if (unique.length > PERMISSION_IDS_MAX) {
    throw new RangeError(`权限项最多 ${PERMISSION_IDS_MAX} 项`)
  }
  for (const id of unique) {
    if (!isValidPermissionId(id)) throw new TypeError(`非法权限 ID：${id}`)
  }
  return unique
}

/** 后端 LocalDateTime（如 2026-11-02T16:00:00.000）展示为本地可读格式；异常输入原样返回。 */
export function formatDateTime(value) {
  if (typeof value !== 'string') return '—'
  const match = value.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/)
  return match ? `${match[1]} ${match[2]}` : value
}
