// 菜单骨架入口：requiredPermissions 声明该入口所需的 API 权限编码（复用 x-permission-catalog，
// 不另建 MENU 权限项）。缺失表示登录即可见（工作空间）。组合页面（多 Tab / 多子功能）
// 声明多个编码、按「任一满足」放行，页面内部再按子功能各自门控——避免只有标签权限
// 却被「分类与标签」整页拦掉。可见性由 utils/menuPermission.js 的 visibleNavigation
// 过滤，路由侧由 router/index.js 的 beforeEach 用同一份编码做守卫。
export const navigation = [
  { title: '工作空间', items: [
    { path: '/workspace', name: 'workspace', title: '工作空间', icon: 'grid' },
    { path: '/portal', name: 'portal', title: '知识门户', icon: 'book', description: '检索与阅读已发布的知识文章。', owner: 'C', requiredPermissions: ['portal:article:list'] },
  ] },
  { title: '内容协作', items: [
    { path: '/admin/articles', name: 'articles', title: '文章管理', icon: 'document', description: '创建草稿、编辑内容并提交审核。', owner: 'C', requiredPermissions: ['article:list'] },
    // TaxonomyView 单页双 Tab：分类（category:tree）与标签（tag:list）各自门控
    { path: '/admin/categories', name: 'categories', title: '分类与标签', icon: 'layers', description: '维护知识分类树和内容标签。', owner: 'C', requiredPermissions: ['category:tree', 'tag:list'] },
    // ReviewListView 含待审列表、审核历史与通过/驳回：任一子功能权限即可进入
    { path: '/admin/reviews', name: 'reviews', title: '审核与发布', icon: 'check', description: '审核内容、发布文章并查看审核历史。', owner: 'D', requiredPermissions: ['article:review-list', 'review:list', 'article:approve', 'article:reject'] },
  ] },
  { title: '组织管理', items: [
    { path: '/admin/users', name: 'users', title: '员工管理', icon: 'users', description: '维护员工账号的新建、编辑与启停。', owner: 'A', requiredPermissions: ['user:list'] },
    // AssignmentView 兼做部门分配与角色分配：只负责部门分配的账号（无 user:assign-role）也应能进入
    { path: '/admin/assignments', name: 'assignments', title: '岗位分配', icon: 'users', description: '维护员工所属部门与角色分配。', owner: 'A', requiredPermissions: ['user:list', 'user:read', 'user:update', 'department:tree', 'user:assign-role'] },
    { path: '/admin/departments', name: 'departments', title: '部门管理', icon: 'layers', description: '维护部门层级与组织结构。', owner: 'A', requiredPermissions: ['department:tree'] },
    // RolePermissionView 单页双 Tab：角色（role:list）与权限项（permission:list）
    { path: '/admin/roles', name: 'roles', title: '角色与权限', icon: 'shield', description: '维护角色、权限项与权限分配。', owner: 'B', requiredPermissions: ['role:list', 'permission:list'] },
    { path: '/admin/logs', name: 'logs', title: '操作日志', icon: 'clock', description: '查询关键操作记录，追溯内容与权限变更。', owner: 'D', requiredPermissions: ['log:list'] },
  ] },
]

export const moduleEntries = navigation.flatMap((group) => group.items).filter((item) => item.name !== 'workspace')
