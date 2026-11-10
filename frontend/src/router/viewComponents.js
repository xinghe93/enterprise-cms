/**
 * 已落地真实页面的模块入口映射。
 *
 * router/index.js 只在这里查表：命中的入口渲染真实页面，其余继续用 ModuleView 占位页。
 * 后续 B/C/D 的页面合入时只需在本文件追加一项，不必再改 router/index.js 的路由构造逻辑，
 * 也能避免多条功能分支同时修改同一个公共路由文件而产生冲突。
 */
export const moduleViews = {
  departments: () => import('../views/user/DepartmentView.vue'),
  users: () => import('../views/user/AssignmentView.vue'),
}
