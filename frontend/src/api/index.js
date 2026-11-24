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
    if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login' })
  },
})
