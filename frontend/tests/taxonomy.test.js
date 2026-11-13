import test from 'node:test'
import assert from 'node:assert/strict'
import { categoryOptions } from '../src/views/content/contentModel.js'

const rootId = '9223372036854775807'
const childId = '9223372036854775806'
const activeId = '9223372036854775805'
const tree = [{
  id: rootId, categoryName: '历史分类', status: 0, children: [
    { id: childId, categoryName: '启用子分类', status: 1, children: [] },
  ],
}, { id: activeId, categoryName: '现行分类', status: 1, children: [] }]

test('分类选择排除停用分支，但允许保留现有历史分类', () => {
  assert.deepEqual(categoryOptions(tree, { activeOnly: true }).map(({ id }) => id), [activeId])
  assert.deepEqual(categoryOptions(tree, { activeOnly: true, currentId: childId }), [
    { id: childId, label: '历史分类 / 启用子分类（停用分支）', active: false },
    { id: activeId, label: '现行分类', active: true },
  ])
  assert.deepEqual(categoryOptions(tree, { excludeId: rootId }).map(({ id }) => id), [activeId])
})
