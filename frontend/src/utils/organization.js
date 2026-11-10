/**
 * 组织管理页的纯逻辑（A 模块）。
 *
 * 契约依据：《接口与协作约定 V1.0》3.2 / 5 / 7 节——
 *  - 业务 ID 是十进制字符串，不使用虚构的 "0" 表示根节点，null 表示根；
 *  - 部门树各层按 sortOrder ASC、id ASC 排列（ID 必须按数值比较，不能按字典序）；
 *  - 树节点不允许成为自身或后代的父节点；
 *  - 新建或切换父节点时，目标及其所有祖先必须启用；原引用不变时允许保留停用引用；
 *  - 角色全量替换最多 100 项、不得重复，空列表表示清空。
 *
 * 不依赖 Vue / DOM，便于 Node 原生测试直接覆盖这些规则。
 */

const MAX_LONG = 9223372036854775807n
const ID_PATTERN = /^[1-9][0-9]{0,18}$/

/** ID 必须是正整数十进制字符串，且不超过 Java Long.MAX_VALUE。 */
export function isValidId(value) {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) return false
  try {
    return BigInt(value) <= MAX_LONG
  } catch {
    return false
  }
}

/** 可选查询条件：省略 undefined / null / 空串，但必须保留 status=0 与 sortOrder=0。 */
export function compactParams(params = {}) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

function compareId(a, b) {
  try {
    const left = BigInt(a.id)
    const right = BigInt(b.id)
    return left < right ? -1 : left > right ? 1 : 0
  } catch {
    return String(a.id).localeCompare(String(b.id))
  }
}

/** 各层按 sortOrder ASC、id ASC 排序，返回新树，不改原数组。 */
export function sortDepartmentTree(nodes = []) {
  return [...(nodes ?? [])]
    .sort((a, b) => (Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0)) || compareId(a, b))
    .map((node) => ({ ...node, children: sortDepartmentTree(node.children) }))
}

/**
 * 扁平化树，供缩进表格渲染。
 * ancestors 是父链上的部门对象引用，可直接读取真实 status。
 */
export function flattenDepartments(nodes = [], depth = 0, ancestors = [], rows = []) {
  for (const node of nodes ?? []) {
    rows.push({ node, depth, ancestors })
    flattenDepartments(node.children, depth + 1, [...ancestors, node], rows)
  }
  return rows
}

export function findDepartment(nodes, id) {
  for (const node of nodes ?? []) {
    if (node.id === id) return node
    const hit = findDepartment(node.children, id)
    if (hit) return hit
  }
  return null
}

/** 目标节点自身 + 全部后代的 ID，用于前端防环。 */
export function collectSubtreeIds(nodes, targetId) {
  const target = findDepartment(nodes, targetId)
  const ids = []
  const walk = (list) => {
    for (const node of list ?? []) {
      ids.push(node.id)
      walk(node.children)
    }
  }
  if (target) walk([target])
  return ids
}

/**
 * 可选的父部门列表。
 *  - editingId：编辑时排除自身及全部后代，关闭成环入口；
 *  - keepParentId：原父引用（可能已停用）仍保留，符合「原引用不变可保留停用引用」；
 *  - 其余候选项要求自身及全部祖先均为启用状态。
 */
export function parentOptions(nodes, { editingId = null, keepParentId = null } = {}) {
  const rows = flattenDepartments(sortDepartmentTree(nodes))
  const excluded = new Set(editingId ? collectSubtreeIds(nodes, editingId) : [])
  return rows
    .filter(({ node }) => !excluded.has(node.id))
    .filter(({ node, ancestors }) => node.id === keepParentId
      || (node.status === 1 && ancestors.every((parent) => parent.status === 1)))
    .map(({ node, depth }) => ({
      id: node.id,
      depth,
      status: node.status,
      label: `${'　'.repeat(depth)}${node.deptName} · ${node.deptCode}`,
    }))
}

/** 角色全量替换：转字符串、去重、逐个校验、上限 100；空数组表示清空。 */
export function normalizeRoleIds(ids) {
  if (!Array.isArray(ids)) throw new TypeError('roleIds 必须是数组')
  const unique = [...new Set(ids.map(String))]
  if (unique.length > 100) throw new RangeError('角色最多 100 项')
  for (const id of unique) {
    if (!isValidId(id)) throw new TypeError(`非法角色 ID：${id}`)
  }
  return unique
}
