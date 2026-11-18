/**
 * 「分配角色」弹窗所需的角色候选项（OpenAPI GET /api/admin/roles，B 模块契约）。
 *
 * 这里只是按契约调用 B 的只读分页接口，供 A 的角色分配页选择启用角色；
 * 不实现 B 的角色管理功能，也不修改 B 的后端模块。B 后续做角色管理页可直接复用。
 * 契约要求：全量替换的每个角色都必须启用，故候选固定带 status=1。
 */
import { compactParams } from '../utils/organization.js'

const PAGE_SIZE = 100

export function createRoleApi(client) {
  return {
    listRoles: (params = {}) => client.get('/admin/roles', { params: compactParams(params) }),
    /**
     * 分配弹窗用：分页拉全部启用角色，只保留 records 数组。
     * 不能只取第一页——员工若持有第二页的启用角色，用不完整候选做全量替换会误删该角色。
     * 按 total/current 推进并设页数上限，防止服务端分页信息异常时无限请求。
     */
    listAssignableRoles: async () => {
      const records = []
      const maxPages = 100
      for (let current = 1; current <= maxPages; current += 1) {
        const page = await client.get('/admin/roles', {
          params: { current, size: PAGE_SIZE, status: 1 },
        })
        const rows = page?.records ?? []
        records.push(...rows)
        const total = Number(page?.total ?? 0)
        if (rows.length < PAGE_SIZE || records.length >= total || total <= 0) break
      }
      return { records, total: records.length }
    },
  }
}
