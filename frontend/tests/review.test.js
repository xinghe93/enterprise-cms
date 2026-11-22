import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  MAX_COMMENT_LENGTH, buildPendingQuery, buildReviewQuery, formatLocalDateTime,
  formatReviewTime, getArticleDetail, isValidArticleId, normalizeComment, validatePendingFilters,
} from '../src/api/review.js'

test('isValidArticleId 接受合法 ID 并拒绝非法 ID', () => {
  assert.equal(isValidArticleId('1'), true)
  assert.equal(isValidArticleId('990201'), true)
  assert.equal(isValidArticleId('123456789012345678'), true)
  assert.equal(isValidArticleId('9223372036854775807'), true)

  assert.equal(isValidArticleId('9223372036854775808'), false)
  assert.equal(isValidArticleId('001'), false)
  assert.equal(isValidArticleId('0'), false)
  assert.equal(isValidArticleId('12345678901234567890'), false)
  assert.equal(isValidArticleId(''), false)
  assert.equal(isValidArticleId('abc'), false)
  assert.equal(isValidArticleId(null), false)
})

test('buildPendingQuery 使用默认分页并剔除空筛选', () => {
  assert.deepEqual(buildPendingQuery(), { current: 1, size: 10 })
  assert.deepEqual(buildPendingQuery({ keyword: '   ', categoryId: '', authorId: null }), { current: 1, size: 10 })
})

test('buildPendingQuery 保留合法筛选并 trim 关键词', () => {
  assert.deepEqual(
    buildPendingQuery({ keyword: '  知识  ', categoryId: '12', authorId: '990101' }),
    { keyword: '知识', categoryId: '12', authorId: '990101', current: 1, size: 10 },
  )
})

test('buildPendingQuery 剔除非法 ID 并兜底分页', () => {
  const query = buildPendingQuery({
    keyword: 'ok', categoryId: '001', authorId: '9223372036854775808', current: 0, size: 500,
  })
  assert.deepEqual(query, { keyword: 'ok', current: 1, size: 100 })
})

test('buildReviewQuery 只接受分页参数并兜底', () => {
  assert.deepEqual(buildReviewQuery(), { current: 1, size: 10 })
  assert.deepEqual(buildReviewQuery({ current: 2, size: 20 }), { current: 2, size: 20 })
  assert.deepEqual(buildReviewQuery({ current: -1, size: 0 }), { current: 1, size: 10 })
  assert.deepEqual(buildReviewQuery({ current: 3, size: 1000 }), { current: 3, size: 100 })
})

test('formatReviewTime 截断毫秒且空值显示占位符', () => {
  assert.equal(formatReviewTime('2026-11-15T10:00:00.120'), '2026-11-15 10:00:00')
  assert.equal(formatReviewTime('2026-11-15T10:00:00'), '2026-11-15 10:00:00')
  assert.equal(formatReviewTime(null), '—')
  assert.equal(formatReviewTime(''), '—')
  assert.equal(formatReviewTime('not-a-date'), '—')
})

test('formatLocalDateTime 输出本地时间且不生成本地时区以外的格式', () => {
  const value = formatLocalDateTime('2026-11-15T10:00:00.999')
  assert.equal(value, '2026-11-15 10:00:00')
  assert.equal(value.includes('Z'), false)
  assert.equal(formatLocalDateTime(new Date(2026, 10, 15, 10, 0, 0)), '2026-11-15 10:00:00')
})

test('normalizeComment trim 后按码点限长', () => {
  assert.equal(normalizeComment('  内容完整  '), '内容完整')
  assert.equal(normalizeComment('   '), '')
  assert.equal(normalizeComment(null), '')
  assert.equal([...normalizeComment('请'.repeat(MAX_COMMENT_LENGTH))].length, MAX_COMMENT_LENGTH)
  assert.throws(() => normalizeComment('请'.repeat(MAX_COMMENT_LENGTH + 1)), TypeError)
})

test('validatePendingFilters 只提示非空的非法 ID', () => {
  assert.deepEqual(validatePendingFilters(), [])
  assert.deepEqual(validatePendingFilters({ categoryId: '', authorId: '   ' }), [])
  assert.deepEqual(validatePendingFilters({ categoryId: '12', authorId: '990101' }), [])
  assert.deepEqual(validatePendingFilters({ categoryId: '0' }), [
    '分类 ID 无效：请输入 1–19 位正整数，且不超过 Long 上限',
  ])
  assert.deepEqual(validatePendingFilters({ authorId: '9223372036854775808' }), [
    '作者 ID 无效：请输入 1–19 位正整数，且不超过 Long 上限',
  ])
  const both = validatePendingFilters({ categoryId: '001', authorId: 'abc' })
  assert.equal(both.length, 2)
  assert.match(both[0], /分类 ID 无效/)
  assert.match(both[1], /作者 ID 无效/)
})

test('getArticleDetail 在发出请求前拒绝非法文章 ID', async () => {
  await assert.rejects(() => getArticleDetail('0'), /无效的文章 ID/)
  await assert.rejects(() => getArticleDetail('001'), /无效的文章 ID/)
  await assert.rejects(() => getArticleDetail('9223372036854775808'), /无效的文章 ID/)
  await assert.rejects(() => getArticleDetail(null), /无效的文章 ID/)
})
