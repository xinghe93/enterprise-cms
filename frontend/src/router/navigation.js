// 菜单骨架入口：requiredPermission 声明该入口所需的 API 权限编码（复用 x-permission-catalog，
// 不另建 MENU 权限项）。缺失表示登录即可见（工作空间）。实际可见性由
// utils/menuPermission.js 的 visibleNavigation 按会话有效权限过滤，路由侧由
// router/index.js 的 beforeEach 用同一份编码做守卫（直达 URL 不能绕过）。
export const navigation = [
  { title: '工作空间', items: [
    { path: '/workspace', name: 'workspace', title: '工作空间', icon: 'grid' },
    { path: '/portal', name: 'portal', title: '知识门户', icon: 'book', description: '检索与阅读已发布的知识文章。', owner: 'C', requiredPermission: 'portal:article:list' },
  ] },
  { title: '内容协作', items: [
    { path: '/admin/articles', name: 'articles', title: '文章管理', icon: 'document', description: '创建草稿、编辑内容并提交审核。', owner: 'C', requiredPermission: 'article:list' },
    { path: '/admin/categories', name: 'categories', title: '分类与标签', icon: 'layers', description: '维护知识分类树和内容标签。', owner: 'C', requiredPermission: 'category:tree' },
    { path: '/admin/reviews', name: 'reviews', title: '审核与发布', icon: 'check', description: '审核内容、发布文章并查看审核历史。', owner: 'D', requiredPermission: 'article:review-list' },
  ] },
  { title: '组织管理', items: [
    { path: '/admin/users', name: 'users', title: '员工管理', icon: 'users', description: '维护员工账号的新建、编辑与启停。', owner: 'A', requiredPermission: 'user:list' },
    { path: '/admin/assignments', name: 'assignments', title: '岗位分配', icon: 'users', description: '维护员工所属部门与角色分配。', owner: 'A', requiredPermission: 'user:assign-role' },
    { path: '/admin/departments', name: 'departments', title: '部门管理', icon: 'layers', description: '维护部门层级与组织结构。', owner: 'A', requiredPermission: 'department:tree' },
    { path: '/admin/roles', name: 'roles', title: '角色与权限', icon: 'shield', description: '维护角色、权限项与权限分配。', owner: 'B', requiredPermission: 'role:list' },
    { path: '/admin/logs', name: 'logs', title: '操作日志', icon: 'clock', description: '查询关键操作记录，追溯内容与权限变更。', owner: 'D', requiredPermission: 'log:list' },
  ] },
]

export const moduleEntries = navigation.flatMap((group) => group.items).filter((item) => item.name !== 'workspace')
