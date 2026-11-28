import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '../src/stores/session.js'

test('会话默认无身份，清理后令牌、用户、有效权限全部移除', () => {
  setActivePinia(createPinia())
  const session = useSessionStore()
  assert.equal(session.isAuthenticated, false)
  session.setToken('opaque-token')
  session.setUser({ id: '9223372036854775807', realName: '测试用户' })
  session.setPermissions(['article:list'])
  assert.equal(session.isAuthenticated, true)
  assert.equal(session.hasPermission('article:list'), true)
  // 切换新令牌时清空上一账号资料与权限，避免残留串号
  session.setToken('next-opaque-token')
  assert.equal(session.user, null)
  assert.deepEqual(session.permissionCodes, [])
  assert.equal(session.permissionStatus, 'idle')
  session.clear()
  assert.equal(session.accessToken, '')
  assert.equal(session.user, null)
  assert.deepEqual(session.permissionCodes, [])
  assert.equal(session.isAuthenticated, false)
})

test('有效权限整批替换，用户角色不能自动授予权限', () => {
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.setUser({ roles: [{ roleCode: 'ADMIN', status: 1 }] })
  assert.equal(session.hasPermission('article:publish'), false)
  session.setPermissions(['article:list', 'article:publish'])
  session.setPermissions(['article:list'])
  assert.equal(session.hasPermission('article:publish'), false)
})

test('令牌持久化到 sessionStorage：刷新重载可恢复，清理会话时同步移除', () => {
  const store = new Map()
  const prev = globalThis.sessionStorage
  globalThis.sessionStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)) },
    removeItem: (k) => { store.delete(k) },
  }
  try {
    setActivePinia(createPinia())
    const first = useSessionStore()
    first.setToken('persisted-token')
    assert.equal(store.get('enterprise_cms_access_token'), 'persisted-token')

    // 模拟浏览器刷新（全新 Pinia 实例）：应自动恢复 accessToken
    setActivePinia(createPinia())
    const reloaded = useSessionStore()
    assert.equal(reloaded.accessToken, 'persisted-token')
    assert.equal(reloaded.isAuthenticated, true)

    // 退出或 401 清理时同步移除 sessionStorage，且重置后的 state 为空
    reloaded.clear()
    assert.equal(store.has('enterprise_cms_access_token'), false)
    assert.equal(reloaded.accessToken, '')
    assert.equal(reloaded.isAuthenticated, false)
  } finally {
    if (prev === undefined) delete globalThis.sessionStorage
    else globalThis.sessionStorage = prev
  }
})
