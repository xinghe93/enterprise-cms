/**
 * 「角色与权限」页的纯逻辑（B 模块）。
 *
 * 契约依据：《接口与协作约定 V1.0》3.1 / 8.1 ——
 *  - 角色编码、权限编码创建后不可修改；
 *  - 名称/编码「先按模型校验类型与长度，再去首尾空白并验证非空」
 *    （OpenAPI pattern \S：必须含非空白字符）；
 *  - 权限项 API 类型必须提供有效 path（^/api/(auth|admin|portal)(/[^\s?#]*)?$）
 *    与大写 method；MENU/BUTTON 的 method 必须为 null；
 *  - path 对 MENU/BUTTON 是「菜单路由」（后端契约不限格式、仅限长 255）；
 *    权限完整 PUT 的 set("path", ...) 会无条件写库，故修改 MENU/BUTTON 时
 *    必须按已有值保留 path，不能提交 null 清空（评审 P1 数据丢失）；
 *    permissionName/path/method 三个键都必填，permissionCode / permissionType 不可改；
 *  - 权限全量替换 permissionIds：唯一、最多 100 项、空数组表示清空；
 *  - 保存前校验「已分配且启用」的权限都能在目录中预选（评审 P1 防误删授权）。
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
 *  - MENU/BUTTON：method 必须为空（界面不提供输入）；创建时不采集菜单路由，
 *    path 提交 null 是合法终态（编辑时则必须按已有值保留，见 buildPermissionUpdatePayload）。
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
 * 菜单路由归一化（MENU/BUTTON 的 path）：后端契约不限格式、仅限长 255，
 * 且完整 PUT 会无条件 {@code set("path", path)} 写库。去首尾空白，空串归一
 * 为 null；超长抛错（与后端 validateOptionalPath 的码点上限一致）。
 */
function normalizeMenuRoute(value) {
  if (value === null || value === undefined) return null
  const trimmed = String(value).trim()
  if (trimmed.length > PATH_MAX) {
    throw new TypeError(`菜单路由不能超过 ${PATH_MAX} 个字符`)
  }
  return trimmed.length === 0 ? null : trimmed
}

/**
 * 权限项修改 payload（完整 PUT）：permissionName/path/method 三个键都必填，
 * 基于 row 中的数据库 permissionType 校验（编码与类型不可改）：
 *  - API：path 必须是三个 API 前缀下的有效路径、method 为大写方法；
 *  - MENU/BUTTON：method 必须 null；path 是「菜单路由」，必须按<b>已有值保留</b>，
 *    不能提交 null 清空——否则仅改名称也会清空原路由（评审 P1 数据丢失）。
 *    取 form.path（编辑表单以 row.path 回显）为准，未带则回退 row.path 兜底。
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
  // MENU/BUTTON：保留已有菜单路由（评审 P1）。form.path 由编辑表单以 row.path
  // 回显；调用方未带 form.path 或显式传 null（纯逻辑测试/未来复用）时回退
  // row.path 兜底，绝不以 null 覆盖数据库已有值。method 恒 null（契约 oneOf）。
  const rawPath = form.path !== undefined && form.path !== null ? form.path : row?.path
  const menuRoute = normalizeMenuRoute(rawPath)
  return { permissionName: form.permissionName.trim(), path: menuRoute, method: null }
}

/**
 * 分配权限弹窗「保存前一致性校验」（评审 P1，防误删授权）：返回「已分配且
 * 启用、但在权限目录中无法预选」的权限 ID 列表。
 *
 * <p>目录（{@code listAllPermissions}）与已分配（{@code getRolePermissions}）
 * 是两个独立请求，之间可能穿插其他管理员的启停操作：若目录把某个已分配且
 * 启用的权限读成停用/缺失，弹窗既不会预选它（目录 status≠1 不可勾选），
 * 也不会把它列入「停用·保存后移除」提示，全量替换就会静默解除该角色的这项
 * 授权。返回非空即表示状态漂移，调用方必须<b>阻止保存并重新加载</b>。</p>
 *
 * <p>只校验「已分配且启用」的项：已分配但停用的项本就应按契约在保存时移除
 * （弹窗有停用提示），不属于误删。</p>
 */
export function findUnpreselectableAssigned(assigned, catalog) {
  const selectable = new Set(
    (Array.isArray(catalog) ? catalog : [])
      .filter((item) => item && item.status === 1)
      .map((item) => String(item.id)),
  )
  return (Array.isArray(assigned) ? assigned : [])
    .filter((item) => item && item.status === 1 && !selectable.has(String(item.id)))
    .map((item) => String(item.id))
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
