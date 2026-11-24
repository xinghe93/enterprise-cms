import { defineStore } from 'pinia'

// 仅保存于内存；登录接入后由 A 写入令牌/用户，B 写入后端有效权限编码。
// permissionStatus 记录权限加载进度（idle/loading/loaded/unavailable），供菜单过滤
// 与路由守卫区分「尚未知」与「确实没有」——未就绪时放行、由页面自身门控兜底。
let inflight = null

export const useSessionStore = defineStore('session', {
  state: () => ({ accessToken: '', user: null, permissionCodes: [], permissionStatus: 'idle' }),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken),
    hasPermission: (state) => (code) => state.permissionCodes.includes(code),
  },
  actions: {
    setToken(token) {
      this.accessToken = token
      // 换令牌必须重算权限：否则切换账号后会沿用上一账号的菜单可见性（B 的菜单映射）
      this.permissionCodes = []
      this.permissionStatus = 'idle'
    },
    setUser(user) { this.user = user },
    setPermissions(codes) {
      this.permissionCodes = [...new Set(Array.isArray(codes) ? codes : [])]
      this.permissionStatus = 'loaded'
    },
    /**
     * 加载当前账号有效权限（GET /api/auth/permissions）。同一时刻只发一个请求，
     * 并发调用复用同一个 Promise；失败置 unavailable 且不抛错——由页面按
     * unavailable 状态显式提示，路由守卫降级放行。
     */
    async ensurePermissions() {
      if (this.permissionStatus === 'loaded' || !this.accessToken) return
      // 同一令牌的并发导航复用同一个请求；令牌已换（切换/重新登录）则重新发起
      if (inflight && inflight.token === this.accessToken) return inflight.promise
      const tokenAtStart = this.accessToken
      this.permissionStatus = 'loading'
      const promise = (async () => {
        try {
          const { fetchMyPermissions } = await import('../api/permission.js')
          const codes = await fetchMyPermissions()
          // 请求期间切换/退出账号：结果不属于当前会话，直接丢弃，避免串号
          if (this.accessToken !== tokenAtStart) return
          this.setPermissions(codes)
        } catch {
          if (this.accessToken === tokenAtStart) this.permissionStatus = 'unavailable'
        } finally {
          if (inflight && inflight.token === tokenAtStart) inflight = null
        }
      })()
      inflight = { token: tokenAtStart, promise }
      return promise
    },
    clear() { this.$reset() },
  },
})
