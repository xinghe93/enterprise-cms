import test from 'node:test'
import assert from 'node:assert/strict'
import { moduleEntries, navigation } from '../src/router/navigation.js'
import { moduleViews } from '../src/router/viewComponents.js'

const aEntries = moduleEntries.filter((entry) => entry.owner === 'A')

test('导航中 A 的两个入口保持原路径与名称，只替换渲染组件', () => {
  assert.deepEqual(aEntries.map((entry) => [entry.path, entry.name]), [
    ['/admin/users', 'users'],
    ['/admin/departments', 'departments'],
  ])
  assert.equal(navigation.find((group) => group.title === '组织管理').items.length, 4)
})

test('只有 A 的两个入口映射到真实页面，其余模块仍走占位页', () => {
  assert.deepEqual(Object.keys(moduleViews).sort(), ['departments', 'users'])
  for (const view of Object.values(moduleViews)) assert.equal(typeof view, 'function')
  for (const entry of moduleEntries) {
    assert.equal(Boolean(moduleViews[entry.name]), entry.owner === 'A')
  }
})

test('占位页入口仍会收到 entry props（由 router/index.js 的条件展开保证）', () => {
  // 该断言与 user-api.test.js 的静态检查共同防止有人把路由改回全量硬编码。
  assert.ok(moduleEntries.some((entry) => entry.owner === 'C'))
  assert.ok(moduleEntries.some((entry) => entry.owner === 'D'))
})
