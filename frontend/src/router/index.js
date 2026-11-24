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
          meta: { title: entry.title, requiredPermission: entry.requiredPermission },
        })),
        // 门户详情用 read 而非 list：与后端 /api/portal/articles/{id} 的权限编码一致
        { path: 'portal/articles/:id', name: 'portal-article', component: articleDetail, props: { portal: true }, meta: { title: '知识文章', requiredPermission: 'portal:article:read' } },
        { path: 'admin/articles/new', name: 'article-new', component: articleEditor, meta: { title: '新建草稿', requiredPermission: 'article:create' } },
        { path: 'admin/articles/:id', name: 'article-detail', component: articleDetail, meta: { title: '文章详情', requiredPermission: 'article:read' } },
        { path: 'admin/articles/:id/edit', name: 'article-edit', component: articleEditor, meta: { title: '编辑文章', requiredPermission: 'article:update' } },
        { path: 'forbidden', name: 'forbidden', component: () => import('../views/ForbiddenView.vue'), meta: { title: '无访问权限' } },
        { path: 'login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '账号登录' } },
        { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面未找到' } },
      ],
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// 菜单的权限编码与路由 meta 同源（navigation.js 的 requiredPermission），
// 保证「菜单可见」与「直达 URL 可访问」一致，不存在菜单藏了但 URL 能进的反向绕过。
// 权限未加载时先拉一次（同一令牌复用请求），仍拿不到则放行、由页面自身门控兜底。
router.beforeEach(async (to) => {
  const session = useSessionStore(pinia)
  if (session.isAuthenticated && session.permissionStatus === 'idle') {
    await session.ensurePermissions()
  }
  const decision = resolveMenuAccess({
    requiredPermission: to.meta.requiredPermission,
    isAuthenticated: session.isAuthenticated,
    permissionStatus: session.permissionStatus,
    hasPermission: session.hasPermission,
  })
  if (decision === 'login') return { name: 'login' }
  if (decision === 'forbidden') return { name: 'forbidden' }
  return true
})

router.afterEach((to) => { document.title = `${to.meta.title || '知识工作空间'} · EnterpriseCMS` })
