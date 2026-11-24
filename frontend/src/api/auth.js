/**
 * A 模块认证接口（OpenAPI /api/auth/*）。
 *
 * 接受公共 api 实例，不重复拼接 /api、不重复解包 data。
 * 抽成工厂是为了在 Node 原生测试中注入 axios adapter，不依赖浏览器或 Element Plus。
 */
export function createAuthApi(client) {
  return {
    login: (username, password) => client.post('/auth/login', { username, password }),
    logout: () => client.post('/auth/logout'),
    getCurrentUser: () => client.get('/auth/me'),
  }
}
