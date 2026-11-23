/**
 * 「角色与权限」页的纯逻辑（B 模块）。
 *
 * 契约依据：《接口与协作约定 V1.0》3.1 / 8.1 ——
 *  - 角色编码、权限编码创建后不可修改；
 *  - 名称/编码「先按模型校验类型与长度，再去首尾空白并验证非空」
 *    （OpenAPI pattern \S：必须含非空白字符）；
 *  - 权限项 API 类型必须提供有效 path（^/api/(auth|admin|portal)(/[^\s?#]*)?$）
 *    与大写 method；MENU/BUTTON 的 method 必须为 null；
 *  - path 对 MENU/BUTTON 是「菜单路由」（后端 validateOptionalPath 只按 Unicode
 *    码点限长 255，不限格式、不 trim）；权限完整 PUT 的 set("path", ...) 会
 *    无条件写库，故修改 MENU/BUTTON 时必须【原样回传 row.path】：不能提交
 *    null 清空（评审 P1 数据丢失），也不能 trim 丢尾部空格、或按 JS .length
 *    （UTF-16 码元）复检长度而误拒后端按码点接受的路径（评审 P2）；
 *    permissionName/path/method 三个键都必填，permissionCode / permissionType 不可改；
 *  - 权限全量替换 permissionIds：唯一、最多 100 项、空数组表示清空；
 *  - 分配弹窗用「原权限集合」基线（permissionBaselineIds / permissionBaseline）
 *    做并发校验：本地用签名快速拦截，提交时把基线 ID 数组作为契约字段
 *    expectedPermissionIds 交给后端在角色行锁内原子比对，返回 409 表示冲突
 *    （评审 P1 并发覆盖——前端重读只能缩小窗口，原子性必须由服务端保证）；
 *  - 保存前校验「已分配且启用」都能在目录中预选（评审 P1 防误删），且两份
 *    读取的 status 一致（评审 P2：避免「提示移除」与「预选保留」自相矛盾）。
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
 * 按 Unicode 码点计数（与后端 {@code String.codePointCount} 对齐）。JS 的
 * {@code .length} 按 UTF-16 码元计数，含增补平面字符（emoji、部分汉字）时会
 * 把 1 个码点算成 2，据此做的长度校验会误拒后端接受的合法输入（评审 P2）。
 * {@code for...of} 按码点迭代字符串，可正确处理代理对。
 */
function codePointLength(value) {
  let count = 0
  for (const _char of String(value)) count += 1
  return count
}

/**
 * 名称/编码统一处理（契约 8.1）：先验原始长度（「类型及原始长度先按模型校验」），
 * 再去首尾空白并验证非空（pattern \S）。长度一律按码点计数，与后端一致。
 * 非法时返回 null，由调用方给出行内提示。
 */
export function normalizeText(value, max) {
  if (value === null || value === undefined) return null
  const raw = String(value)
  if (codePointLength(raw) > max) return null
  const trimmed = raw.trim()
  if (trimmed.length === 0) return null
  if (codePointLength(trimmed) > max) return null
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
 * 权限项修改 payload（完整 PUT）：permissionName/path/method 三个键都必填，
 * 基于 row 中的数据库 permissionType 校验（编码与类型不可改）：
 *  - API：path 必须是三个 API 前缀下的有效路径、method 为大写方法；
 *  - MENU/BUTTON：method 必须 null；path 是「菜单路由」，必须【原样回传
 *    数据库已有值 row.path】（评审 P1 数据丢失 + 评审 P2 原样保留）：
 *    完整 PUT 的 {@code set("path", path)} 无条件写库，提交 null 会清空原路由；
 *    不 trim——只改名称也会触发，会丢掉原路径的尾部空格；不按 JS {@code .length}
 *    复检长度——UTF-16 码元口径与后端 {@code codePointCount} 码点口径不一致，
 *    会误拒后端接受的路径，而本类表单没有对应字段可提示该错误；长度合法性
 *    由后端 {@code validateOptionalPath} 在同一请求中裁决。
 *    form.path 只是编辑表单的回显载体（openPermEdit 把 row.path 写入它），
 *    MENU/BUTTON 表单不提供 path 输入框，故一律以 row.path 为准；仅当 row
 *    未携带 path 字段（纯逻辑复用/未来调用方）时才原样回退 form.path。
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
  // MENU/BUTTON：path 原样回传数据库已有值，零加工。仅把 null/undefined 归一
  // 为 null 以保证契约「path 键必填」；其余（尾部空格、Unicode 增补字符、
  // 空串）一律原样提交，让后端的 validateOptionalPath 去裁决长度。
  const menuRoute = row != null && Object.prototype.hasOwnProperty.call(row, 'path')
    ? row.path
    : form.path
  return { permissionName: form.permissionName.trim(), path: menuRoute ?? null, method: null }
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
 * 权限分配弹窗的并发基线 ID 数组（评审 P1 并发覆盖）：把「打开弹窗时读到的已分配
 * 权限 ID 集合」归一化为去重、升序的 ID 字符串数组。它有两个用途：
 *  1. 本地快速拦截——与保存前重读结果的基线签名（permissionBaseline）比对；
 *  2. 提交给后端契约字段 expectedPermissionIds，由服务端在角色行锁内与数据库
 *     当前集合原子比对，不一致返回 409（前端重读到 PUT 之间仍有竞争窗口，
 *     真正的并发安全必须由服务端保证——这是评审复审后明确的要求）。
 */
export function permissionBaselineIds(assigned) {
  return [...new Set((Array.isArray(assigned) ? assigned : [])
    .filter((item) => item && item.id !== undefined && item.id !== null)
    .map((item) => String(item.id)))]
    .sort()
}

/**
 * 并发基线签名（本地快速比对用）：归一化 ID 数组后逗号连接。
 * 真正的并发裁决在服务端（见 permissionBaselineIds）。
 */
export function permissionBaseline(assigned) {
  return permissionBaselineIds(assigned).join(',')
}

/**
 * 弹窗会话有效性判断（评审 P2 会话竞态）：判断一个在途保存请求是否仍属于它
 * 开始时那个弹窗会话，防止「保存期间取消并重开同一角色」时旧请求混用旧勾选和
 * 新基线——旧请求继续 PUT 会恢复其他管理员刚撤销的权限。
 *
 * <p>必须同时满足：
 *  1. 会话序号未变（关闭 / 重开弹窗都会递增会话序号）；
 *  2. 弹窗仍可见（已被关闭则作废）；
 *  3. 仍是同一角色（切换到别的角色则作废）；
 *  4. 共享基线未被改写（保存开始时已固定局部副本，若共享基线与之不同说明
 *     弹窗已被重开并重新加载，作废旧请求）。</p>
 *
 * @param saved 保存开始时捕获的会话快照 { session, roleId, baselineIds }
 * @param current 当前弹窗状态 { session, roleId, baselineIds, visible }
 */
export function isAssignSessionCurrent(saved, current) {
  if (current?.visible !== true) return false
  if (!saved || saved.session === undefined) return false
  if (saved?.session !== current?.session) return false
  if (String(saved?.roleId ?? '') !== String(current?.roleId ?? '')) return false
  // 基线数组逐项比对（顺序无关）：共享基线被重开改写即判为不同会话。
  // 注意 session 里存的是纯 ID 字符串数组（permissionBaselineIds 的输出），
  // 不是 {id} 对象数组，故这里单独归一化，不能复用 permissionBaselineIds。
  const normalize = (ids) => Array.isArray(ids)
    ? [...new Set(ids.map((id) => String(id)))].sort().join(',')
    : ''
  return normalize(saved?.baselineIds) === normalize(current?.baselineIds)
}

/**
 * 两份读取的 status 漂移检测（评审 P2：避免「提示移除」与「预选保留」矛盾）：
 * 返回「在已分配与权限目录两份数据中 status 不一致」的权限 ID 列表。
 *
 * <p>目录与已分配是两个独立请求，之间可能穿插其他管理员的启停操作。若仅按
 * assigned 的 status 生成「保存后移除」提示、按 catalog 的 status 决定预选，
 * 同一权限可能一边被提示移除、一边被预选保留，实际保存仍保留授权（矛盾 UI）。
 * 返回非空即表示状态漂移，调用方必须<b>阻止保存并重新加载</b>。</p>
 *
 * <p>只对两份都存在的项比对：catalog 缺失的已分配项由 findUnpreselectableAssigned
 * 负责（那是「无法预选」问题，不是状态不一致问题）；防御性容忍非数组输入。</p>
 */
export function findStatusConflicts(assigned, catalog) {
  const catalogStatus = new Map(
    (Array.isArray(catalog) ? catalog : [])
      .filter((item) => item && item.id !== undefined && item.id !== null)
      .map((item) => [String(item.id), item.status]),
  )
  return (Array.isArray(assigned) ? assigned : [])
    .filter((item) => item && item.id !== undefined && item.id !== null
      && catalogStatus.has(String(item.id))
      && item.status !== catalogStatus.get(String(item.id)))
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
