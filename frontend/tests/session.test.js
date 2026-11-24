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
