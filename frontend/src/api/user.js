/**
 * A 模块「员工管理 / 部门管理」接口封装（OpenAPI /api/admin/users、/api/admin/departments）。
 *
 * 接受公共 api 实例：client.js 已完成统一响应解包（返回 data）与错误归一化，
 * 这里只负责 URL、查询参数与请求体，不重复拼 /api、不重复解包。
 * 抽成工厂是为了在 Node 原生测试中注入 axios adapter，不依赖浏览器或 Element Plus。
 */
import { compactParams, isValidId, normalizeRoleIds } from '../utils/organization.js'

function pathId(id) {
  if (!isValidId(id)) throw new TypeError(`业务 ID 必须是十进制字符串：${String(id)}`)
  return encodeURIComponent(id)
}

export function createUserApi(client) {
  return {
    // 员工：契约 3.2 —— keyword 字面包含、departmentId 含后代、status 省略为全部
    listUsers: (params = {}) => client.get('/admin/users', { params: compactParams(params) }),
    createUser: (payload) => client.post('/admin/users', payload),
    getUser: (id) => client.get(`/admin/users/${pathId(id)}`),
    // 完整 PUT：realName 与 departmentId 都必填，null 表示清除部门
    updateUser: (id, payload) => client.put(`/admin/users/${pathId(id)}`, payload),
    setUserStatus: (id, status) => client.put(`/admin/users/${pathId(id)}/status`, { status }),
    // 全量替换：空数组清空，停用项不应再次提交
    setUserRoles: (id, roleIds) => client.put(`/admin/users/${pathId(id)}/roles`, {
      roleIds: normalizeRoleIds(roleIds),
    }),

    // 部门：status 筛选时由后端保留必要祖先的真实状态
    getDepartmentTree: (params = {}) => client.get('/admin/departments', { params: compactParams(params) }),
    createDepartment: (payload) => client.post('/admin/departments', payload),
    // 完整 PUT：parentId / deptName / sortOrder 都必填，deptCode 创建后不可改
    updateDepartment: (id, payload) => client.put(`/admin/departments/${pathId(id)}`, payload),
    deleteDepartment: (id) => client.delete(`/admin/departments/${pathId(id)}`),
    setDepartmentStatus: (id, status) => client.put(`/admin/departments/${pathId(id)}/status`, { status }),
  }
}
