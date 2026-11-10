import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createApiClient, ApiError } from '../src/api/client.js'
import { createUserApi } from '../src/api/user.js'
import { createRoleApi } from '../src/api/role.js'

const envelope = (code, data = null, message = '操作成功') => ({
  code, data, message, timestamp: '2026-11-10T00:00:00Z',
})

function recording({ data = null, status = 200, code = 200 } = {}) {
  const calls = []
  const client = createApiClient({
    adapter: async (config) => {
      calls.push({ method: config.method, url: config.url, params: config.params, data: config.data })
      return { data: envelope(code, data), status, statusText: '', headers: {}, config }
    },
  })
  return { client, calls, last: () => calls.at(-1) }
}

const body = (call) => JSON.parse(call.data)

test('员工分页：省略空条件、保留 status=0，统一解包后的分页结构原样返回', async () => {
  const page = { records: [{ id: '9223372036854775807' }], total: 1, current: 1, size: 10 }
  const ctx = recording({ data: page })
  const result = await createUserApi(ctx.client).listUsers({
    keyword: '', departmentId: '', status: 0, current: 1, size: 10, ignored: undefined,
  })
  assert.equal(ctx.last().method, 'get')
  assert.equal(ctx.last().url, '/admin/users')
  assert.deepEqual(ctx.last().params, { status: 0, current: 1, size: 10 })
  assert.deepEqual(result, page)
  assert.equal(result.records[0].id, '9223372036854775807')
})

test('员工 6 个接口的 URL、方法与请求体符合契约', async () => {
  const user = { id: '12', username: 'wang', realName: '小王', departmentId: null }
  const ctx = recording({ data: user })
  const userApi = createUserApi(ctx.client)

  await userApi.createUser({ username: 'wang', password: 'Passw0rd!', realName: '小王' })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['post', '/admin/users'])
  assert.deepEqual(body(ctx.last()), { username: 'wang', password: 'Passw0rd!', realName: '小王' })

  await userApi.getUser('9223372036854775807')
  assert.equal(ctx.last().url, '/admin/users/9223372036854775807')

  // 完整 PUT：departmentId=null 必须显式提交，不能被当作「省略」丢掉。
  await userApi.updateUser('12', { realName: '小王', departmentId: null })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['put', '/admin/users/12'])
  assert.deepEqual(body(ctx.last()), { realName: '小王', departmentId: null })

  await userApi.setUserStatus('12', 0)
  assert.equal(ctx.last().url, '/admin/users/12/status')
  assert.deepEqual(body(ctx.last()), { status: 0 })

  await userApi.setUserRoles('12', ['3', '3', '9'])
  assert.equal(ctx.last().url, '/admin/users/12/roles')
  assert.deepEqual(body(ctx.last()), { roleIds: ['3', '9'] })

  // 空数组表示清空，不是「不修改」。
  await userApi.setUserRoles('12', [])
  assert.deepEqual(body(ctx.last()), { roleIds: [] })
})

test('部门 5 个接口的 URL、方法与请求体符合契约', async () => {
  const department = { id: '5', children: [] }
  const ctx = recording({ data: department })
  const userApi = createUserApi(ctx.client)

  await userApi.getDepartmentTree({ status: '' })
  assert.deepEqual([ctx.last().method, ctx.last().url, ctx.last().params], ['get', '/admin/departments', {}])
  await userApi.getDepartmentTree({ status: 1 })
  assert.deepEqual(ctx.last().params, { status: 1 })

  await userApi.createDepartment({ parentId: null, deptCode: 'SALES', deptName: '销售部', sortOrder: 0 })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['post', '/admin/departments'])
  assert.deepEqual(body(ctx.last()), { parentId: null, deptCode: 'SALES', deptName: '销售部', sortOrder: 0 })

  // 更新不提交 deptCode（编码创建后不可改），三个必填字段都带上。
  await userApi.updateDepartment('5', { parentId: '2', deptName: '销售一部', sortOrder: 3 })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['put', '/admin/departments/5'])
  assert.deepEqual(body(ctx.last()), { parentId: '2', deptName: '销售一部', sortOrder: 3 })

  await userApi.deleteDepartment('5')
  assert.deepEqual([ctx.last().method, ctx.last().url], ['delete', '/admin/departments/5'])

  await userApi.setDepartmentStatus('5', 0)
  assert.equal(ctx.last().url, '/admin/departments/5/status')
  assert.deepEqual(body(ctx.last()), { status: 0 })
})

test('业务 ID 必须是十进制字符串，非法值不发请求', async () => {
  const ctx = recording()
  const userApi = createUserApi(ctx.client)
  for (const bad of [0, 12, '0', '01', '-1', 'abc', '9223372036854775808', null, undefined]) {
    assert.throws(() => userApi.getUser(bad), TypeError)
  }
  assert.equal(ctx.calls.length, 0)
  assert.throws(() => userApi.deleteDepartment('9223372036854775808'), TypeError)
  assert.equal(ctx.calls.length, 0)
})

test('角色全量替换拒绝非法 ID、重复与超过 100 项，不发请求', async () => {
  const ctx = recording()
  const userApi = createUserApi(ctx.client)
  assert.throws(() => userApi.setUserRoles('12', ['0']), TypeError)
  assert.throws(() => userApi.setUserRoles('12', ['1', 'x']), TypeError)
  assert.throws(() => userApi.setUserRoles('12', Array.from({ length: 101 }, (_, i) => String(i + 1))), RangeError)
  assert.equal(ctx.calls.length, 0)
})

test('409 业务冲突原样透传为 ApiError，不被伪装成成功', async () => {
  const ctx = recording({ code: 409, status: 409, data: null })
  await assert.rejects(
    createUserApi(ctx.client).deleteDepartment('5'),
    (error) => error instanceof ApiError && error.code === 409 && error.status === 409,
  )
})

test('角色候选固定只取启用项，且按 B 的分页契约传参', async () => {
  const ctx = recording({ data: { records: [{ id: '2', status: 1 }], total: 1, current: 1, size: 100 } })
  const roleApi = createRoleApi(ctx.client)
  await roleApi.listAssignableRoles()
  assert.equal(ctx.last().url, '/admin/roles')
  assert.deepEqual(ctx.last().params, { current: 1, size: 100, status: 1 })
  await roleApi.listRoles({ keyword: '编辑', status: 1 })
  assert.deepEqual(ctx.last().params, { keyword: '编辑', status: 1 })
})

test('公共入口把 A 的两个模块映射到真实页面，其余仍走占位页', () => {
  const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
  const viewsSource = readFileSync(new URL('../src/router/viewComponents.js', import.meta.url), 'utf8')
  const mainSource = readFileSync(new URL('../src/main.js', import.meta.url), 'utf8')
  assert.match(routerSource, /moduleViews\[entry\.name\] \?\?/)
  assert.match(routerSource, /props: \{ entry \}/)
  assert.match(viewsSource, /departments: \(\) => import\('\.\.\/views\/user\/DepartmentView\.vue'\)/)
  assert.match(viewsSource, /users: \(\) => import\('\.\.\/views\/user\/AssignmentView\.vue'\)/)
  assert.match(mainSource, /import '\.\/styles\/organization\.css'/)
})
