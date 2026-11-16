import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiClient, ApiError } from '../src/api/client.js'
import { createAuthApi } from '../src/api/auth.js'

const envelope = (code, data = null, message = '操作成功') =>
  ({ code, data, message, timestamp: '2026-11-05T08:00:00Z' })

function recordingClient(body, status = 200) {
  const calls = []
  const client = createApiClient({ adapter: async (config) => {
    calls.push({
      url: config.url,
      method: config.method,
      data: config.data ? JSON.parse(config.data) : undefined,
      authorization: config.headers.get('Authorization'),
    })
    return { data: body, status, statusText: '', headers: {}, config }
  } })
  return { client, calls }
}

const user = {
  id: '9223372036854775807', username: 'editor_demo', realName: '演示编辑',
  departmentId: null, departmentName: null, status: 1, roles: [],
  createdAt: '2026-11-02T16:00:00.000', updatedAt: '2026-11-02T16:00:00.000',
}

test('登录提交 username/password，解包后保留字符串 ID 与固定 7200 秒', async () => {
  const { client, calls } = recordingClient(envelope(200, {
    accessToken: 'opaque-token', tokenType: 'Bearer', expiresIn: 7200, user,
  }))
  const result = await createAuthApi(client).login('editor_demo', 'example-password')
  assert.deepEqual(calls[0], {
    url: '/auth/login', method: 'post',
    data: { username: 'editor_demo', password: 'example-password' }, authorization: undefined,
  })
  assert.equal(result.tokenType, 'Bearer')
  assert.equal(result.expiresIn, 7200)
  assert.equal(result.user.id, '9223372036854775807')
})

test('退出不带请求体且解包为 null；当前用户返回 User 契约', async () => {
  const out = recordingClient(envelope(200))
  assert.equal(await createAuthApi(out.client).logout(), null)
  assert.equal(out.calls[0].url, '/auth/logout')
  assert.equal(out.calls[0].data, undefined)

  const me = recordingClient(envelope(200, user))
  assert.deepEqual(await createAuthApi(me.client).getCurrentUser(), user)
  assert.equal(me.calls[0].url, '/auth/me')
})

test('已登录请求自动携带 Bearer Token', async () => {
  const seen = []
  const authClient = createApiClient({
    getToken: () => 'opaque-token',
    adapter: async (config) => {
      seen.push({ url: config.url, authorization: config.headers.get('Authorization') })
      return { data: envelope(200, user), status: 200, statusText: '', headers: {}, config }
    },
  })
  await createAuthApi(authClient).getCurrentUser()
  assert.deepEqual(seen, [{ url: '/auth/me', authorization: 'Bearer opaque-token' }])
})

test('401 归一化为 ApiError，不产生可登录状态', async () => {
  const { client } = recordingClient(envelope(401, null, '用户名或密码错误'), 401)
  await assert.rejects(createAuthApi(client).login('u', 'p'),
    (error) => error instanceof ApiError && error.code === 401 && error.message === '用户名或密码错误')
})
