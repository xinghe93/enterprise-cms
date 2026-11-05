// 这里只定义骨架入口。登录鉴权接入后再按后端有效权限决定菜单可见性。
export const navigation = [
  { title: '工作空间', items: [
    { path: '/workspace', name: 'workspace', title: '工作空间', icon: 'grid' },
    { path: '/portal', name: 'portal', title: '知识门户', icon: 'book', description: '检索与阅读已发布的知识文章。', owner: 'C' },
  ] },
  { title: '内容协作', items: [
    { path: '/admin/articles', name: 'articles', title: '文章管理', icon: 'document', description: '创建草稿、编辑内容并提交审核。', owner: 'C' },
    { path: '/admin/categories', name: 'categories', title: '分类与标签', icon: 'layers', description: '维护知识分类树和内容标签。', owner: 'C' },
    { path: '/admin/reviews', name: 'reviews', title: '审核与发布', icon: 'check', description: '审核内容、发布文章并查看审核历史。', owner: 'D' },
  ] },
  { title: '组织管理', items: [
    { path: '/admin/users', name: 'users', title: '员工管理', icon: 'users', description: '维护员工账号、所属部门与角色分配。', owner: 'A' },
    { path: '/admin/departments', name: 'departments', title: '部门管理', icon: 'layers', description: '维护部门层级与组织结构。', owner: 'A' },
    { path: '/admin/roles', name: 'roles', title: '角色与权限', icon: 'shield', description: '维护角色、权限项与权限分配。', owner: 'B' },
    { path: '/admin/logs', name: 'logs', title: '操作日志', icon: 'clock', description: '查询关键操作记录，追溯内容与权限变更。', owner: 'D' },
  ] },
]

export const moduleEntries = navigation.flatMap((group) => group.items).filter((item) => item.name !== 'workspace')
