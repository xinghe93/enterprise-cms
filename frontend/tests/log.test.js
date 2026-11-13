import test from 'node:test'
import assert from 'node:assert/strict'
import { isValidLogId, normalizeTotal } from '../src/api/log.js'
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

test('isValidLogId 只接受 1-18 位正整数，拒绝 undefined/0/负数/0x 前缀', () => {
  assert.equal(isValidLogId(990001), true)
  assert.equal(isValidLogId('990001'), true)
  assert.equal(isValidLogId(1), true)
  assert.equal(isValidLogId(undefined), false)
  assert.equal(isValidLogId(null), false)
  assert.equal(isValidLogId(''), false)
  assert.equal(isValidLogId(0), false)
  assert.equal(isValidLogId(-1), false)
  assert.equal(isValidLogId('0x1'), false)
  assert.equal(isValidLogId('abc'), false)
})

test('normalizeTotal 把非法或负值收敛为 0，其余取整', () => {
  assert.equal(normalizeTotal(3), 3)
  assert.equal(normalizeTotal('12'), 12)
  assert.equal(normalizeTotal('12.9'), 12)
  assert.equal(normalizeTotal(undefined), 0)
  assert.equal(normalizeTotal(null), 0)
  assert.equal(normalizeTotal(NaN), 0)
  assert.equal(normalizeTotal(-5), 0)
  assert.equal(normalizeTotal(Infinity), 0)
})
