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
  // C 的 #31 分类与标签页：从 router/index.js 的硬编码分支迁入映射表，保持单一入口。
  categories: () => import('../views/content/TaxonomyView.vue'),
  // C 的 #42 文章管理与知识门户页：dev 上原先由 router/index.js 的 contentRoutes 硬编码，
  // 合并 dev 后迁入映射表；两者共用 ArticleListView，靠 portal props 切换数据范围。
  articles: () => import('../views/content/ArticleListView.vue'),
  portal: () => import('../views/content/ArticleListView.vue'),
  // D 的 #36 操作日志页：dev 上原先由 router/index.js 的硬编码分支兜底，
  // 合并 dev 后迁入映射表；页面自取会话与权限，不依赖 entry props。
  logs: () => import('../views/LogListView.vue'),
}

/**
 * 需要向真实页面传入固定 props 的入口（而不是占位页的 entry props）。
 * 沿用 dev 上 contentRoutes 的契约：目前只有知识门户需要 { portal: true }。
 */
export const contentViewProps = {
  portal: { portal: true },
}
