/**
 * 「分配角色」弹窗所需的角色候选项（OpenAPI GET /api/admin/roles，B 模块契约）。
 *
 * 这里只是按契约调用 B 的只读分页接口，供 A 的角色分配页选择启用角色；
 * 不实现 B 的角色管理功能，也不修改 B 的后端模块。B 后续做角色管理页可直接复用。
 * 契约要求：全量替换的每个角色都必须启用，故候选固定带 status=1。
 */
import { compactParams } from '../utils/organization.js'

export function createRoleApi(client) {
  return {
    listRoles: (params = {}) => client.get('/admin/roles', { params: compactParams(params) }),
    /** 分配弹窗用：只取启用角色，size 上限 100，避免把停用项重新提交造成 409。 */
    listAssignableRoles: () => client.get('/admin/roles', { params: { current: 1, size: 100, status: 1 } }),
  }
}
