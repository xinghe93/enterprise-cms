import test from 'node:test'
import assert from 'node:assert/strict'
import { articlePayload, isContentId, isEditableArticle } from '../src/views/content/contentModel.js'

const rootId = '9223372036854775807'
const childId = '9223372036854775806'
const activeId = '9223372036854775805'

test('完整保存保留正文和字符串 ID，并显式表达清空摘要与标签', () => {
  const form = {
    title: ' 更新 ', content: '  保留缩进\n', summary: '', categoryId: activeId,
    tagIds: [], authorId: '1', status: 'PUBLISHED', visibility: 'ALL',
  }
  assert.deepEqual(articlePayload(form, 0), {
    title: '更新', summary: null, content: '  保留缩进\n', categoryId: activeId, tagIds: [], version: 0,
  })
  assert.deepEqual(articlePayload({ ...form, summary: ' 保留摘要空格 ', tagIds: [childId] }), {
    title: '更新', summary: ' 保留摘要空格 ', content: '  保留缩进\n', categoryId: activeId, tagIds: [childId],
  })
})

test('ID 校验不转换数字，文章操作仅针对允许的状态', () => {
  assert.equal(isContentId(rootId), true)
  assert.equal(isContentId('9223372036854775808'), false)
  assert.equal(isContentId('01'), false)
  assert.equal(isContentId(1), false)
  for (const status of ['DRAFT', 'REJECTED', 'OFFLINE']) assert.equal(isEditableArticle(status), true)
  for (const status of ['PENDING_REVIEW', 'APPROVED', 'PUBLISHED']) assert.equal(isEditableArticle(status), false)
})
