import { ElMessage } from 'element-plus'
import { createApiClient } from './client.js'
import { pinia } from '../stores/index.js'
import { useSessionStore } from '../stores/session.js'
import { router } from '../router/index.js'

export const api = createApiClient({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  getToken: () => useSessionStore(pinia).accessToken,
  onError: (error) => ElMessage.error({ message: error.message, grouping: true }),
  onUnauthorized: () => {
    useSessionStore(pinia).clear()
    const current = router.currentRoute.value
    // 初始导航尚未解析完成时（current.name 为空），由 router.beforeEach 统一按真实目标路径
    // 重定向到登录页，避免这里用初始 '/' 覆盖掉目标深链的 redirect 参数。
    if (current.name && current.name !== 'login') {
      void router.replace({
        name: 'login',
        ...(current.fullPath && current.fullPath !== '/login' ? { query: { redirect: current.fullPath } } : {}),
      })
    }
  },
})
