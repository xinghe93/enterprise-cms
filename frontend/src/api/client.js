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
    const token = getToken()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    else config.headers.delete('Authorization')
    return config
  })

  function reject(error, config) {
    if (axios.isCancel(error)) return Promise.reject(error)
    if (error.code === 401) onUnauthorized()
    if (!config?.silent) onError(error)
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
