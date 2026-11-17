import { createRouter, createWebHistory } from 'vue-router'
import { moduleEntries } from './navigation.js'

const articleList = () => import('../views/content/ArticleListView.vue')
const articleDetail = () => import('../views/content/ArticleDetailView.vue')
const articleEditor = () => import('../views/content/ArticleEditorView.vue')
const contentRoutes = {
  categories: { component: () => import('../views/content/TaxonomyView.vue'), props: false },
  articles: { component: articleList, props: false },
  portal: { component: articleList, props: { portal: true } },
}

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
          // 已实现的模块挂真实页面，其余仍由占位页接管。
          ...contentRoutes[entry.name],
          component: entry.name === 'reviews'
            ? () => import('../views/ReviewListView.vue')
            : () => import('../views/ModuleView.vue'),
          props: { entry }, meta: { title: entry.title },
        })),
        { path: 'portal/articles/:id', name: 'portal-article', component: articleDetail, props: { portal: true }, meta: { title: '知识文章' } },
        { path: 'admin/articles/new', name: 'article-new', component: articleEditor, meta: { title: '新建草稿' } },
        { path: 'admin/articles/:id', name: 'article-detail', component: articleDetail, meta: { title: '文章详情' } },
        { path: 'admin/articles/:id/edit', name: 'article-edit', component: articleEditor, meta: { title: '编辑文章' } },
        { path: 'login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '账号登录' } },
        { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面未找到' } },
      ],
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => { document.title = `${to.meta.title || '知识工作空间'} · EnterpriseCMS` })
