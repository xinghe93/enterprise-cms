import { createRouter, createWebHistory } from 'vue-router'
import { moduleEntries } from './navigation.js'

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
          component: entry.name === 'logs'
            ? () => import('../views/LogListView.vue')
            : () => import('../views/ModuleView.vue'),
          props: { entry }, meta: { title: entry.title },
        })),
        { path: 'login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '账号登录' } },
        { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面未找到' } },
      ],
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => { document.title = `${to.meta.title || '知识工作空间'} · EnterpriseCMS` })
