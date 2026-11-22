/**
 * B 模块「角色与权限」管理接口封装（OpenAPI /api/admin/roles、/api/admin/permissions、/api/auth/permissions）。
 *
 * 接受公共 api 实例：client.js 已完成统一响应解包（返回 data）与错误归一化，
 * 这里只负责 URL、查询参数与请求体，不重复拼 /api、不重复解包。
 * 抽成工厂是为了在 Node 原生测试中注入 axios adapter，不依赖浏览器或 Element Plus。
 *
 * 与 src/api/role.js 的关系：role.js 是 A 为「分配角色」弹窗封装的只读候选接口
 * （注释明示 B 可复用），本文件是 B 的管理接口全集（含写操作）。角色分页两处调
 * 同一契约端点，但筛选参数与用途不同，互不依赖、避免改 A 的文件。
 *
 * 请求体构造（trim、类型条件校验）在 utils/rolePermission.js 的纯函数中完成，
 * 视图先经纯函数得到合法 payload 再传入本层；本层对 ID 只做路径合法性校验。
 */
import { compactParams, isValidId } from '../utils/organization.js'

function pathId(id) {
  if (!isValidId(id)) throw new TypeError(`业务 ID 必须是十进制字符串：${String(id)}`)
  return encodeURIComponent(id)
}

export function createPermissionApi(client) {
  return {
    // ── 角色：契约 8.2 —— keyword 字面包含、status 省略为全部、current/size 常规分页 ──
    listRoles: (params = {}) => client.get('/admin/roles', { params: compactParams(params) }),
    createRole: (payload) => client.post('/admin/roles', payload),
    // 完整 PUT：仅 roleName 可改，roleCode 创建后不可改（契约 3.1）
    updateRole: (id, payload) => client.put(`/admin/roles/${pathId(id)}`, payload),
    setRoleStatus: (id, status) => client.put(`/admin/roles/${pathId(id)}/status`, { status }),
    // 已分配权限回显：返回全部已分配权限（含停用项），按关联 ID 升序。
    // 评审 P2：只读账号（有 role:read-permission 但无 permission:list）也能独立
    // 查看它——目录请求 403 不得阻断这个结果的展示，故视图需单独请求、单独保存。
    getRolePermissions: (id) => client.get(`/admin/roles/${pathId(id)}/permissions`),
    // 全量替换：permissionIds 已由视图经 normalizePermissionIds 规范化（去重、≤100、合法 ID）；
    // 契约：不存在/停用权限分别 404/409，既有停用权限不能保留在本次请求中
    setRolePermissions: (id, permissionIds) =>
      client.put(`/admin/roles/${pathId(id)}/permissions`, { permissionIds }),

    // ── 权限项：permissionType 精确筛选（MENU/BUTTON/API），其余同上 ──
    listPermissions: (params = {}) => client.get('/admin/permissions', { params: compactParams(params) }),
    createPermission: (payload) => client.post('/admin/permissions', payload),
    // 完整 PUT：permissionName/path/method 三个键都必填、permissionCode 与
    // permissionType 不可改。MENU/BUTTON 的 path 是菜单路由，必须按已有值保留
    // （不能提交 null 清空，见 rolePermission.js 的 buildPermissionUpdatePayload），
    // method 必须 null；API 的 path/method 需符合契约前缀与枚举。
    updatePermission: (id, payload) => client.put(`/admin/permissions/${pathId(id)}`, payload),
    setPermissionStatus: (id, status) => client.put(`/admin/permissions/${pathId(id)}/status`, { status }),

    /**
     * 分配权限弹窗用：分页拉全部权限项目录（含停用），只保留 records 数组。
     * 与 role.js 的 listAssignableRoles 同一防御理由：全量替换的候选必须「完整且可信」，
     * 不完整或重复的候选会让保存时误删角色实际持有的权限：
     *  - 以服务端 total 为推进依据，不能用 rows.length < size 提前退出；
     *  - total 缺失 / 非法 / 翻页间变化、空页未取满、超过 MAX_PAGES 安全上限，
     *    都视为目录不可信而抛错，由上层禁用保存，绝不返回不完整候选；
     *  - 跨页重复权限 ID 同样不可信而抛错；
     *  - 真「零权限项」（total=0 且首页空）是合法结果，正常返回空数组。
     */
    listAllPermissions: async () => {
      const MAX_PAGES = 100
      const PAGE_SIZE = 100
      const records = []
      const seenIds = new Set()
      let declaredTotal = null
      for (let current = 1; current <= MAX_PAGES; current += 1) {
        const page = await client.get('/admin/permissions', { params: { current, size: PAGE_SIZE } })
        const rows = page?.records
        const rawTotal = page?.total
        if (!Array.isArray(rows) || !Number.isSafeInteger(rawTotal) || rawTotal < 0) {
          throw new TypeError('权限目录分页数据异常，已中止加载以避免误删现有权限')
        }
        if (declaredTotal === null) {
          declaredTotal = rawTotal
        } else if (rawTotal !== declaredTotal) {
          throw new TypeError('权限目录分页总数不一致，已中止加载以避免误删现有权限')
        }
        if (rows.length === 0) {
          if (records.length !== declaredTotal) {
            throw new RangeError('权限目录提前返回空页，已中止加载以避免误删现有权限')
          }
          break
        }
        for (const row of rows) {
          const permissionId = String(row?.id ?? '')
          if (!permissionId) {
            throw new TypeError('权限目录存在缺失 ID 的记录，已中止加载以避免误删现有权限')
          }
          if (seenIds.has(permissionId)) {
            throw new TypeError('权限目录出现重复权限 ID，已中止加载以避免误删现有权限')
          }
          seenIds.add(permissionId)
          records.push(row)
        }
        if (records.length >= declaredTotal) break
      }
      if (records.length !== declaredTotal) {
        if (records.length > declaredTotal) {
          throw new RangeError('权限目录记录数超过声明 total，已中止加载以避免误删现有权限')
        }
        throw new RangeError('权限目录未完整加载，已中止以避免误删现有权限')
      }
      return { records, total: records.length }
    },
  }
}

// 当前账号有效权限编码（OpenAPI GET /api/auth/permissions，x-owner B；返回 string[]）。
// 与 review.js 的 fetchMyPermissions 同一模式：api 经动态 import 引入，纯函数可在
// node --test 下单独测试；silent 关闭全局错误提示，由页面按 unavailable 状态显式提示；
// signal 供页面在卸载/请求被替代时取消旧请求。
async function apiClient() {
  const { api } = await import('./index.js')
  return api
}

/** 当前账号有效权限编码（OpenAPI GET /api/auth/permissions，x-owner B；返回 string[]）。 */
export async function fetchMyPermissions({ signal } = {}) {
  const api = await apiClient()
  return api.get('/auth/permissions', { silent: true, ...(signal ? { signal } : {}) })
}
