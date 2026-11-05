import test from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createApiClient, ApiError } from '../src/api/client.js'

const envelope = (code, data = null, message = '请求失败') => ({ code, data, message, timestamp: '2026-11-05T00:00:00Z' })
const response = (data, status = 200) => async (config) => ({ data, status, statusText: '', headers: {}, config })

test('解包成功响应，保留长整型字符串 ID 和分页结构', async () => {
  const data = { records: [{ id: '9223372036854775807' }], current: 1, size: 10, total: 1 }
  const client = createApiClient({ adapter: response(envelope(200, data)) })
  assert.deepEqual(await client.get('/admin/users'), data)
  assert.equal(await createApiClient({ adapter: response(envelope(200)) }).post('/auth/logout'), null)
})

test('每次请求读取当前令牌，清理会话后不再携带 Bearer', async () => {
  let token = 'session-one'
  const headers = []
  const client = createApiClient({ getToken: () => token, adapter: async (config) => {
    headers.push(config.headers.get('Authorization'))
    return response(envelope(200))(config)
  } })
  await client.get('/auth/me')
  token = ''
  await client.get('/auth/me')
  assert.deepEqual(headers, ['Bearer session-one', undefined])
})

for (const code of [400, 401, 403, 404, 409, 500]) {
  test(`${code} 错误仅提示一次，401 即使静默也通知会话失效`, async () => {
    const errors = []
    let expired = 0
    const client = createApiClient({
      adapter: response(envelope(code, null, '后端错误提示'), code),
      onError: (error) => errors.push(error), onUnauthorized: () => expired++,
    })
    await assert.rejects(client.get('/admin/users'), (error) => error instanceof ApiError && error.code === code && error.message === '后端错误提示')
    assert.equal(errors.length, 1)
    assert.equal(expired, code === 401 ? 1 : 0)
    await assert.rejects(client.get('/admin/users', { silent: true }))
    assert.equal(errors.length, 1)
    assert.equal(expired, code === 401 ? 2 : 0)
  })
}

test('Axios 拒绝的非 2xx 响应仍使用后端统一错误', async () => {
  const client = createApiClient({ adapter: async (config) => {
    const result = await response(envelope(403, null, '无操作权限'), 403)(config)
    throw new axios.AxiosError('HTTP error', 'ERR_BAD_REQUEST', config, null, result)
  } })
  await assert.rejects(client.get('/admin/users'), { code: 403, message: '无操作权限', status: 403 })
})

test('非法响应及 HTTP 与业务码不一致时拒绝，不误报成功', async () => {
  for (const [body, status] of [['<html>proxy error</html>', 502], [{ records: [] }, 200], [envelope(200), 500], [envelope(409), 200]]) {
    const client = createApiClient({ adapter: response(body, status) })
    await assert.rejects(client.get('/admin/users'), ApiError)
  }
})

for (const [code, message] of [['ECONNABORTED', '请求超时，请稍后重试'], ['ERR_NETWORK', '网络连接失败，请检查网络或服务状态']]) {
  test(`归一化 ${code} 错误`, async () => {
    const client = createApiClient({ adapter: async () => { throw new axios.AxiosError('internal', code) } })
    await assert.rejects(client.get('/auth/me'), { message })
  })
}

test('主动取消不触发全局错误提示', async () => {
  let notified = false
  const client = createApiClient({ onError: () => { notified = true } })
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(client.get('/auth/me', { signal: controller.signal }), axios.isCancel)
  assert.equal(notified, false)
})
