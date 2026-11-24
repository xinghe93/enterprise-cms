import { defineStore } from 'pinia'

// 仅保存于内存；登录接入后由 A 写入令牌/用户，B 写入后端有效权限编码。
// permissionStatus 记录权限加载进度（idle/loading/loaded/unavailable），供菜单过滤
// 与路由守卫区分「尚未知 / 加载失败」与「确实没有」——未就绪时放行、由页面自身门控兜底。
let inflight = null

/**
 * 拉取一次当前账号有效权限（GET /api/auth/permissions）。
 * - 同一令牌并发调用复用同一个请求；令牌已换（切换/重新登录）则重新发起。
 * - force=false（ensure）：already loaded 直接返回，避免每次导航都打接口。
 * - force=true （refresh）：忽略 loaded 强制刷新，用于「即将按旧快照拒绝访问」前复核。
 * - 请求返回时若令牌已变（退出/切号），丢弃结果，避免串号。
 */
async function loadPermissions(store, { force = false } = {}) {
  const token = store.accessToken
  if (!token) return
  if (inflight && inflight.token === token) return inflight.promise
  if (!force && store.permissionStatus === 'loaded') return
  store.permissionStatus = 'loading'
  const promise = (async () => {
    try {
      const { fetchMyPermissions } = await import('../api/permission.js')
      const codes = await fetchMyPermissions()
      if (store.accessToken !== token) return
      store.setPermissions(codes)
    } catch {
      if (store.accessToken === token) store.permissionStatus = 'unavailable'
    } finally {
      if (inflight && inflight.token === token) inflight = null
    }
  })()
  inflight = { token, promise }
  return promise
}

export const useSessionStore = defineStore('session', {
  state: () => ({ accessToken: '', user: null, permissionCodes: [], permissionStatus: 'idle' }),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken),
    hasPermission: (state) => (code) => state.permissionCodes.includes(code),
  },
  actions: {
    setToken(token) {
      this.accessToken = token
      // 换令牌必须重算权限：否则切换账号后会沿用上一账号的菜单可见性
      this.permissionCodes = []
      this.permissionStatus = 'idle'
    },
    setUser(user) { this.user = user },
    setPermissions(codes) {
      this.permissionCodes = [...new Set(Array.isArray(codes) ? codes : [])]
      this.permissionStatus = 'loaded'
    },
    /** 首次加载或失败后重试：已 loaded 则跳过（不重复请求）。 */
    async ensurePermissions() {
      return loadPermissions(this)
    },
    /**
     * 强制刷新权限快照（忽略 loaded）。用于两处：
     * ① 路由守卫在「按旧快照即将 403」前复核，避免本会话内刚被授予的权限被永久拒绝；
     * ② 菜单在 permissionStatus==='unavailable' 时的手工重试入口。
     */
    async refreshPermissions() {
      return loadPermissions(this, { force: true })
    },
    clear() { this.$reset() },
  },
})
