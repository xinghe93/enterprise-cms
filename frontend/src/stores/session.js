import { defineStore } from 'pinia'

// 仅保存于内存；登录接入后由 A 写入令牌/用户，B 写入后端有效权限编码。
export const useSessionStore = defineStore('session', {
  state: () => ({ accessToken: '', user: null, permissionCodes: [] }),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken),
    hasPermission: (state) => (code) => state.permissionCodes.includes(code),
  },
  actions: {
    setToken(token) { this.accessToken = token },
    setUser(user) { this.user = user },
    setPermissions(codes) { this.permissionCodes = [...new Set(codes)] },
    clear() { this.$reset() },
  },
})
