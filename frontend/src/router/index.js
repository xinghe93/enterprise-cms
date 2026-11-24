import { createRouter, createWebHistory } from 'vue-router'
import { moduleEntries } from './navigation.js'
import { contentViewProps, hasModuleView, resolveModuleView } from './viewComponents.js'
import { pinia } from '../stores/index.js'
import { useSessionStore } from '../stores/session.js'
import { resolveMenuAccess } from '../utils/menuPermission.js'

const articleDetail = () => import('../views/content/ArticleDetailView.vue')
const articleEditor = () => import('../views/content/ArticleEditorView.vue')

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/', component: () => import('../layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/workspace' },
        { path: 'workspace', name: 'workspace', component: () => import('../views/WorkspaceView.vue'), meta: { title: '工作空间' } },
        ...moduleEntries.map((entry) => ({
          path: entry.path.slice(1), name: entry.name,
          component: resolveModuleView(entry.name),
          // 占位页接收 entry；真实页面用自身固定 props（如 portal），否则不传，
          // 避免多余 props 落到组件根元素上。
          ...(hasModuleView(entry.name)
            ? (contentViewProps[entry.name] ? { props: contentViewProps[entry.name] } : {})
            : { props: { entry } }),
          meta: { title: entry.title, requiredPermissions: entry.requiredPermissions },
        })),
        // 门户详情用 read 而非 list：与后端 /api/portal/articles/{id} 的权限编码一致
        { path: 'portal/articles/:id', name: 'portal-article', component: articleDetail, props: { portal: true }, meta: { title: '知识文章', requiredPermissions: ['portal:article:read'] } },
        { path: 'admin/articles/new', name: 'article-new', component: articleEditor, meta: { title: '新建草稿', requiredPermissions: ['article:create'] } },
        { path: 'admin/articles/:id', name: 'article-detail', component: articleDetail, meta: { title: '文章详情', requiredPermissions: ['article:read'] } },
        { path: 'admin/articles/:id/edit', name: 'article-edit', component: articleEditor, meta: { title: '编辑文章', requiredPermissions: ['article:update'] } },
        { path: 'forbidden', name: 'forbidden', component: () => import('../views/ForbiddenView.vue'), meta: { title: '无访问权限' } },
        { path: 'login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '账号登录' } },
        { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面未找到' } },
      ],
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// 菜单的权限编码与路由 meta 同源（navigation.js 的 requiredPermissions），
// 保证「菜单可见」与「直达 URL 可访问」一致，不存在菜单藏了但 URL 能进的反向绕过。
//
// 三条异步真实约束（PR 评审 P2）：
// ① 未就绪（idle）先加载；**unavailable（上次请求失败）也重试**，否则一次超时会永久只剩工作空间；
// ② 已 loaded 但即将判 403 时，先强制刷新一次快照再裁决——本会话内刚被管理员授予权限的账号
//    不能因为首次加载的旧快照被永久拒绝；
// ③ 仍未就绪（刷新也失败）时放行，由页面自身门控兜底，不把「不可用」当成「没有」。
router.beforeEach(async (to) => {
  const session = useSessionStore(pinia)
  const required = to.meta.requiredPermissions
  const decide = () => resolveMenuAccess({
    requiredPermissions: required,
    isAuthenticated: session.isAuthenticated,
    permissionStatus: session.permissionStatus,
    hasPermission: session.hasPermission,
  })

  if (!session.isAuthenticated) return decide() === 'login' ? { name: 'login' } : true

  // 未就绪或上次失败：首次加载 / 自动重试
  if (session.permissionStatus !== 'loaded') await session.ensurePermissions()

  if (decide() === 'forbidden') {
    // 拒绝前复核：权限可能在本会话期间被他人授予，用新快照再判一次
    await session.refreshPermissions()
    if (decide() === 'forbidden') return { name: 'forbidden' }
  }
  return true
})

router.afterEach((to) => { document.title = `${to.meta.title || '知识工作空间'} · EnterpriseCMS` })
