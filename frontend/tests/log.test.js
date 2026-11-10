import test from 'node:test'
import assert from 'node:assert/strict'
import { buildLogQuery, formatLocalDateTime, formatLogTime } from '../src/api/log.js'

test('默认分页参数为第一页、每页 10 条，空筛选项不传给后端', () => {
  const query = buildLogQuery({})
  assert.deepEqual(query, { current: 1, size: 10 })
  assert.deepEqual(buildLogQuery({ userId: '', operation: '   ', result: '' }), { current: 1, size: 10 })
})

test('有效筛选项被保留，操作类型去掉首尾空白，非数字操作人 ID 被剔除', () => {
  const query = buildLogQuery({ userId: '990101', operation: '  article.approve  ', result: 'FAILURE', current: 2, size: 20 })
  assert.deepEqual(query, {
    userId: 990101,
    operation: 'article.approve',
    result: 'FAILURE',
    current: 2,
    size: 20,
  })
  assert.deepEqual(buildLogQuery({ userId: 'abc', result: 'OTHER' }), { current: 1, size: 10 })
})

test('时间范围转成本地格式且不带 Z，边界值可左闭右开', () => {
  const from = new Date(2026, 10, 8, 10, 0, 0)
  const to = new Date(2026, 10, 8, 11, 0, 0)
  const query = buildLogQuery({ timeRange: [from, to] })
  assert.equal(query.createdFrom, '2026-11-08T10:00:00')
  assert.equal(query.createdTo, '2026-11-08T11:00:00')
  assert.ok(!JSON.stringify(query).includes('Z'))
})

test('时间范围也接受日期选择器返回的字符串，毫秒被截断', () => {
  const query = buildLogQuery({ timeRange: ['2026-11-08T10:00:00.120', '2026-11-08T11:00:00'] })
  assert.equal(query.createdFrom, '2026-11-08T10:00:00')
  assert.equal(query.createdTo, '2026-11-08T11:00:00')
})

test('formatLocalDateTime 对无效输入返回 null', () => {
  assert.equal(formatLocalDateTime(null), null)
  assert.equal(formatLocalDateTime('not-a-date'), null)
  assert.equal(formatLocalDateTime(new Date('invalid')), null)
})

test('formatLogTime 显示到秒，空值显示破折号', () => {
  assert.equal(formatLogTime('2026-11-08T10:00:00.000'), '2026-11-08 10:00:00')
  assert.equal(formatLogTime('2026-11-08T10:00:00'), '2026-11-08 10:00:00')
  assert.equal(formatLogTime(null), '—')
  assert.equal(formatLogTime(''), '—')
  assert.equal(formatLogTime(undefined), '—')
})
