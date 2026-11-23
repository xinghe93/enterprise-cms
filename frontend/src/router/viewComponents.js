/**
 * 已落地真实页面的模块入口映射。
 *
 * router/index.js 只在这里查表：命中的入口渲染真实页面，其余继续用 ModuleView 占位页。
 * 后续 B/C/D 的页面合入时只需在本文件追加一项，不必再改 router/index.js 的路由构造逻辑，
 * 也能避免多条功能分支同时修改同一个公共路由文件而产生冲突。
 */
export const moduleViews = {
  departments: () => import('../views/user/DepartmentView.vue'),
  // 员工账号新建/编辑/启停（功能①）；原分配页保留在 assignments 入口。
  users: () => import('../views/user/AccountView.vue'),
  assignments: () => import('../views/user/AssignmentView.vue'),
  // C 的 #31 分类与标签页：从 router/index.js 的硬编码分支迁入映射表，保持单一入口。
  categories: () => import('../views/content/TaxonomyView.vue'),
  // C 的 #42 文章管理与知识门户页：dev 上原先由 router/index.js 的 contentRoutes 硬编码，
  // 合并 dev 后迁入映射表；两者共用 ArticleListView，靠 portal props 切换数据范围。
  articles: () => import('../views/content/ArticleListView.vue'),
  portal: () => import('../views/content/ArticleListView.vue'),
  // D 的 #36 操作日志页：dev 上原先由 router/index.js 的硬编码分支兜底，
  // 合并 dev 后迁入映射表；页面自取会话与权限，不依赖 entry props。
  logs: () => import('../views/LogListView.vue'),
  // D 的 #34 审核工作台：同样迁入映射表，避免与 logs 的硬编码分支冲突。
  reviews: () => import('../views/ReviewListView.vue'),
  // B 的角色与权限管理页：单页双 Tab（角色管理 + 权限项），自取权限编码写入会话。
  roles: () => import('../views/role/RolePermissionView.vue'),
}

/**
 * 需要向真实页面传入固定 props 的入口（而不是占位页的 entry props）。
 * 沿用 dev 上 contentRoutes 的契约：目前只有知识门户需要 { portal: true }。
 */
export const contentViewProps = {
  portal: { portal: true },
}

/**
 * 路由构造用的纯查询函数：路由表只依赖这两个函数，测试可直接断言行为，
 * 不必对 router/index.js 做源码正则匹配（DeepSeek 审查 #11）。
 */
export function hasModuleView(name) {
  return Object.hasOwn(moduleViews, name)
}

export function resolveModuleView(name) {
  return moduleViews[name] ?? (() => import('../views/ModuleView.vue'))
}
