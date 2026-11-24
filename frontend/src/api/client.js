import axios from 'axios'

export class ApiError extends Error {
  constructor(message, { code = 0, status = 0, cause } = {}) {
    super(message, { cause })
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

function isEnvelope(body) {
  return body && typeof body === 'object' && Number.isInteger(body.code)
    && typeof body.message === 'string' && typeof body.timestamp === 'string'
    && Object.hasOwn(body, 'data')
}

function responseError(response) {
  const body = response.data
  const valid = isEnvelope(body)
  const status = response.status
  // HTTP 状态优先，防止异常代理响应携带成功业务码。
  const code = status >= 400 ? status : valid ? body.code : 0
  return new ApiError(valid && body.code !== 200 && body.message
    ? body.message : status >= 500 ? '服务暂时不可用，请稍后重试' : '服务响应格式异常', { code, status })
}

/** 返回解包后的 data。silent 只关闭提示，不吞掉异常或 401 会话失效通知。 */
export function createApiClient({ baseURL = '/api', getToken = () => '', onError = () => {}, onUnauthorized = () => {}, adapter } = {}) {
  const client = axios.create({ baseURL, timeout: 15000, ...(adapter ? { adapter } : {}) })
  client.interceptors.request.use((config) => {
    // 记录发出请求时的会话令牌（允许为空字符串）：响应回来时若会话已切换，这条 401
    // 属于旧会话，不能再清掉刚登录的新会话（账号 A 请求迟到、账号 B 已登录的场景）。
    const token = getToken() ?? ''
    config.sessionToken = token
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    else config.headers.delete('Authorization')
    return config
  })

  function reject(error, config) {
    if (axios.isCancel(error)) return Promise.reject(error)
    // 只有"发出请求时确实记录了快照、且快照与当前令牌不同"才算旧会话；空字符串也是有效快照，
    // 覆盖"匿名请求发出后才登录"的情况。该判断只用于 401，其他状态码的全局提示保持原行为。
    const requestToken = config?.sessionToken
    const hasSnapshot = typeof requestToken === 'string'
    const staleSession = hasSnapshot && error.code === 401 && requestToken !== (getToken() ?? '')
    if (error.code === 401 && !staleSession) onUnauthorized()
    if (!config?.silent && !staleSession) onError(error)
    return Promise.reject(error)
  }

  client.interceptors.response.use((response) => {
    if (response.status === 200 && isEnvelope(response.data) && response.data.code === 200) {
      return response.data.data
    }
    return reject(responseError(response), response.config)
  }, (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const normalized = error.response ? responseError(error.response) : new ApiError(
      ['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)
        ? '请求超时，请稍后重试' : '网络连接失败，请检查网络或服务状态', { cause: error },
    )
    return reject(normalized, error.config)
  })
  return client
}
