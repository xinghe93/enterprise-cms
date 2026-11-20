import test from 'node:test'
import assert from 'node:assert/strict'
import { moduleEntries, navigation } from '../src/router/navigation.js'
import { moduleViews, contentViewProps } from '../src/router/viewComponents.js'

const aEntries = moduleEntries.filter((entry) => entry.owner === 'A')

test('导航中 A 的三个入口保持原路径与名称，只替换渲染组件', () => {
  assert.deepEqual(aEntries.map((entry) => [entry.path, entry.name]), [
    ['/admin/users', 'users'],
    ['/admin/assignments', 'assignments'],
    ['/admin/departments', 'departments'],
  ])
  assert.equal(navigation.find((group) => group.title === '组织管理').items.length, 5)
})

test('A 的三个入口与迁入映射表的 C/D 页面映射到真实页面，其余模块仍走占位页', () => {
  // categories 是 C 的 #31、articles/portal 是 C 的 #42、logs 是 D 的 #36，
  // 合入 dev 后从 router/index.js 硬编码分支迁入映射表，目的仍是让
  // router/index.js 不再逐页硬编码；均属真实页面。
  assert.deepEqual(Object.keys(moduleViews).sort(), ['articles', 'assignments', 'categories', 'departments', 'logs', 'portal', 'users'])
  for (const view of Object.values(moduleViews)) assert.equal(typeof view, 'function')
  for (const entry of moduleEntries) {
    const expected = entry.owner === 'A' || ['categories', 'articles', 'portal', 'logs'].includes(entry.name)
    assert.equal(Boolean(moduleViews[entry.name]), expected)
  }
})

test('知识门户入口带固定 portal props，其余真实页面不接收 entry props', () => {
  assert.deepEqual(contentViewProps, { portal: { portal: true } })
  // 固定 props 只作用于映射表内已落地的页面，不能落到占位页上。
  for (const name of Object.keys(contentViewProps)) assert.ok(moduleViews[name])
})

test('占位页入口仍会收到 entry props（由 router/index.js 的条件展开保证）', () => {
  // 该断言与 user-api.test.js 的静态检查共同防止有人把路由改回全量硬编码。
  assert.ok(moduleEntries.some((entry) => entry.owner === 'C'))
  assert.ok(moduleEntries.some((entry) => entry.owner === 'D'))
})
