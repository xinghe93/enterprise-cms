import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createApiClient } from '../src/api/client.js'
import { createPermissionApi } from '../src/api/permission.js'
import {
  buildPermissionCreatePayload,
  buildPermissionUpdatePayload,
  buildRoleCreatePayload,
  buildRoleUpdatePayload,
  findStatusConflicts,
  findUnpreselectableAssigned,
  formatDateTime,
  normalizePermissionIds,
  permissionBaseline,
  validatePermissionCreateForm,
  validateRoleForm,
} from '../src/utils/rolePermission.js'

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

test('角色 6 个接口的 URL、方法与请求体符合契约', async () => {
  const ctx = recording({ data: { id: '12' } })
  const api = createPermissionApi(ctx.client)

  await api.listRoles({ keyword: '  编辑  ', status: '', current: 1, size: 10 })
  assert.equal(ctx.last().method, 'get')
  assert.equal(ctx.last().url, '/admin/roles')
  // compactParams 剔除空串，keyword 原样透传（后端负责去首尾空白做字面包含）
  assert.deepEqual(ctx.last().params, { keyword: '  编辑  ', current: 1, size: 10 })

  await api.createRole({ roleCode: 'editor', roleName: '内容编辑' })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['post', '/admin/roles'])
  assert.deepEqual(body(ctx.last()), { roleCode: 'editor', roleName: '内容编辑' })

  await api.updateRole('12', { roleName: '内容编辑组' })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['put', '/admin/roles/12'])
  assert.deepEqual(body(ctx.last()), { roleName: '内容编辑组' })

  await api.setRoleStatus('12', 0)
  assert.equal(ctx.last().url, '/admin/roles/12/status')
  assert.deepEqual(body(ctx.last()), { status: 0 })

  await api.getRolePermissions('12')
  assert.equal(ctx.last().url, '/admin/roles/12/permissions')

  await api.setRolePermissions('12', ['3', '9'])
  assert.deepEqual([ctx.last().method, ctx.last().url], ['put', '/admin/roles/12/permissions'])
  assert.deepEqual(body(ctx.last()), { permissionIds: ['3', '9'] })
})

test('权限项 4 个接口的 URL、方法与请求体符合契约', async () => {
  const ctx = recording({ data: { id: '7' } })
  const api = createPermissionApi(ctx.client)

  await api.listPermissions({ keyword: '', status: 0, permissionType: 'API', current: 2, size: 20 })
  assert.equal(ctx.last().url, '/admin/permissions')
  // status=0 必须保留（compactParams 只剔除空串/null/undefined）
  assert.deepEqual(ctx.last().params, { status: 0, permissionType: 'API', current: 2, size: 20 })

  await api.createPermission({
    permissionCode: 'article:publish', permissionName: '发布文章',
    permissionType: 'API', path: '/api/admin/articles/{id}/publish', method: 'POST',
  })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['post', '/admin/permissions'])
  assert.deepEqual(body(ctx.last()), {
    permissionCode: 'article:publish', permissionName: '发布文章',
    permissionType: 'API', path: '/api/admin/articles/{id}/publish', method: 'POST',
  })

  // 完整 PUT：MENU/BUTTON 的 path/method 显式提交 null，不被省略
  await api.updatePermission('7', { permissionName: '新名称', path: null, method: null })
  assert.deepEqual([ctx.last().method, ctx.last().url], ['put', '/admin/permissions/7'])
  assert.deepEqual(body(ctx.last()), { permissionName: '新名称', path: null, method: null })

  await api.setPermissionStatus('7', 1)
  assert.equal(ctx.last().url, '/admin/permissions/7/status')
  assert.deepEqual(body(ctx.last()), { status: 1 })
})

test('非法路径 ID 在客户端即拒绝，不发出请求', () => {
  const ctx = recording()
  const api = createPermissionApi(ctx.client)
  // pathId 是同步纯校验：非法 ID 在拼 URL 时同步抛 TypeError，绝不发出请求。
  // 因此用 assert.throws（assert.rejects 只接受 Promise 拒绝，会把同步抛出当成测试失败）。
  assert.throws(() => api.updateRole('0x12', { roleName: 'x' }), TypeError)
  assert.throws(() => api.setPermissionStatus('-3', 1), TypeError)
  assert.equal(ctx.calls.length, 0)
})

function permissionRow(id, status = 1) {
  return { id, permissionCode: `perm:${id}`, permissionName: `权限${id}`, permissionType: 'API', status }
}

function catalogServer(total, pages) {
  return createApiClient({
    adapter: async (config) => {
      const current = Number(config.params.current)
      const rows = pages[current - 1] ?? []
      return { data: envelope(200, { records: rows, total, current, size: 100 }), status: 200, statusText: '', headers: {}, config }
    },
  })
}

test('权限目录全量分页：跨页聚合完整且可信', async () => {
  const client = catalogServer(3, [
    [permissionRow('1'), permissionRow('2')],
    [permissionRow('3')],
  ])
  const result = await createPermissionApi(client).listAllPermissions()
  assert.deepEqual(result.records.map((row) => row.id), ['1', '2', '3'])
  assert.equal(result.total, 3)
})

test('权限目录全量分页：零权限项是合法结果', async () => {
  const client = catalogServer(0, [[]])
  const result = await createPermissionApi(client).listAllPermissions()
  assert.deepEqual(result.records, [])
  assert.equal(result.total, 0)
})

test('权限目录全量分页：total 翻页间变化即中止（防止误删现有权限）', async () => {
  const client = createApiClient({
    adapter: async (config) => {
      const current = Number(config.params.current)
      // 第 1 页声明 3 条却只给 2 条（逼出第 2 页，不能在首页就 break），
      // 第 2 页 total 偷变为 2 —— 实现必须在聚合 records 之前按"总数不一致"中止。
      const total = current === 1 ? 3 : 2
      return {
        data: envelope(200, {
          records: current === 1 ? [permissionRow('1'), permissionRow('2')] : [permissionRow('3')],
          total, current, size: 100,
        }),
        status: 200, statusText: '', headers: {}, config,
      }
    },
  })
  await assert.rejects(() => createPermissionApi(client).listAllPermissions(), /总数不一致/)
})

test('权限目录全量分页：重复 ID、提前空页、超出安全上限均中止', async () => {
  const duplicate = catalogServer(3, [
    [permissionRow('1'), permissionRow('2')],
    [permissionRow('2'), permissionRow('3')],
  ])
  await assert.rejects(() => createPermissionApi(duplicate).listAllPermissions(), /重复权限 ID/)

  const earlyEmpty = catalogServer(3, [[permissionRow('1')], []])
  await assert.rejects(() => createPermissionApi(earlyEmpty).listAllPermissions(), /提前返回空页/)

  // 每页都非空且 ID 唯一，total 远超 MAX_PAGES * PAGE_SIZE：
  // 达到 MAX_PAGES 安全上限仍未取满 → "未完整加载"（ID 必须逐页递增，
  // 否则第 2 页就先撞"重复权限 ID"，测不到安全上限分支）。
  const endlessPages = Array.from({ length: 200 }, (_, i) => [permissionRow(String(i + 1))])
  const endless = catalogServer(99999, endlessPages)
  await assert.rejects(() => createPermissionApi(endless).listAllPermissions(), /未完整加载/)
})

test('角色表单：trim 后非空、长度上限、编码仅创建时校验', () => {
  assert.deepEqual(validateRoleForm({ roleCode: '', roleName: '内容编辑' }), { roleCode: '请输入角色编码（去首尾空白后不能为空）' })
  assert.deepEqual(validateRoleForm({ roleCode: 'x'.repeat(65), roleName: '名称' }), { roleCode: '角色编码不能超过 64 个字符' })
  // 默认 withCode=true：只测名称空白时须给合法 roleCode，否则会连带报编码缺失
  assert.deepEqual(validateRoleForm({ roleCode: 'editor', roleName: '   ' }), { roleName: '请输入角色名称（去首尾空白后不能为空）' })
  // 编辑（withCode: false）：编码缺失不报错
  assert.deepEqual(validateRoleForm({ roleName: '名称' }, { withCode: false }), {})

  assert.deepEqual(buildRoleCreatePayload({ roleCode: '  editor  ', roleName: ' 内容编辑 ' }), { roleCode: 'editor', roleName: '内容编辑' })
  assert.deepEqual(buildRoleUpdatePayload({ roleName: ' 名称 ' }), { roleName: '名称' })
  assert.throws(() => buildRoleCreatePayload({ roleCode: 'editor', roleName: '' }), TypeError)
})

test('权限创建 payload：API 类型要求合法 path/method，MENU/BUTTON 提交 null', () => {
  const base = { permissionCode: 'article:publish', permissionName: '发布文章', permissionType: 'API' }

  // 合法 API
  assert.deepEqual(
    buildPermissionCreatePayload({ ...base, path: '/api/admin/articles/{id}/publish', method: 'POST' }),
    { ...base, path: '/api/admin/articles/{id}/publish', method: 'POST' },
  )
  // path 必须在 /api/auth|admin|portal 下
  assert.throws(() => buildPermissionCreatePayload({ ...base, path: '/other/api', method: 'POST' }), TypeError)
  assert.throws(() => buildPermissionCreatePayload({ ...base, path: '/api/admin/x?a=1', method: 'POST' }), TypeError)
  assert.throws(() => buildPermissionCreatePayload({ ...base, path: '/api/admin/x y', method: 'POST' }), TypeError)
  // method 必须在枚举内
  assert.throws(() => buildPermissionCreatePayload({ ...base, path: '/api/admin/x', method: 'FETCH' }), TypeError)
  assert.throws(() => buildPermissionCreatePayload({ ...base, path: '/api/admin/x', method: '' }), TypeError)

  // MENU/BUTTON：path/method 提交 null（契约 oneOf 要求 method 必须为 null）
  const menu = { permissionCode: 'menu:reviews', permissionName: '审核菜单', permissionType: 'MENU' }
  assert.deepEqual(buildPermissionCreatePayload(menu), { ...menu, path: null, method: null })
  const button = { permissionCode: 'btn:publish', permissionName: '发布按钮', permissionType: 'BUTTON' }
  assert.deepEqual(buildPermissionCreatePayload(button), { ...button, path: null, method: null })

  // 类型缺失 / 名称超长
  assert.ok(validatePermissionCreateForm({ ...base, permissionType: '' }).permissionType)
  assert.ok(validatePermissionCreateForm({ ...base, permissionName: 'x'.repeat(101), path: '/api/admin/x', method: 'GET' }).permissionName)
})

test('权限修改 payload：完整 PUT 且按数据库类型校验，编码/类型不可改', () => {
  const apiRow = { permissionType: 'API' }
  assert.deepEqual(
    buildPermissionUpdatePayload(apiRow, { permissionName: ' 新名称 ', path: '/api/admin/articles', method: 'PUT' }),
    { permissionName: '新名称', path: '/api/admin/articles', method: 'PUT' },
  )
  assert.throws(() => buildPermissionUpdatePayload(apiRow, { permissionName: 'x', path: '/api/other', method: 'GET' }), TypeError)
  assert.throws(() => buildPermissionUpdatePayload(apiRow, { permissionName: 'x', path: '/api/admin/x', method: '' }), TypeError)

  const menuRow = { permissionType: 'MENU' }
  // 创建时未采集菜单路由的行（path 为 null）：编辑仍提交 null，null 是合法终态
  assert.deepEqual(
    buildPermissionUpdatePayload(menuRow, { permissionName: '菜单' }),
    { permissionName: '菜单', path: null, method: null },
  )
  assert.throws(() => buildPermissionUpdatePayload(menuRow, { permissionName: '   ' }), TypeError)
})

test('权限修改 payload（评审 P2）：MENU/BUTTON 编辑原样提交数据库已有值 row.path', () => {
  // 场景一：仅改名称——row.path 原样保留（评审复现路径：旧实现提交 null 清空 /content/reviews）
  const menuRow = { permissionType: 'MENU', path: '/content/reviews' }
  assert.deepEqual(
    buildPermissionUpdatePayload(menuRow, { permissionName: '审核菜单改', path: '/content/reviews' }),
    { permissionName: '审核菜单改', path: '/content/reviews', method: null },
  )
  // 场景二（评审 P2 复现）：row.path 带尾部空格也必须原样提交——后端按码点计数、
  // 不 trim，原样写库；旧实现只改名称也会触发 trim 而丢掉尾部空格
  assert.deepEqual(
    buildPermissionUpdatePayload({ permissionType: 'MENU', path: '/content/reviews ' }, { permissionName: 'x', path: 'ignored' }),
    { permissionName: 'x', path: '/content/reviews ', method: null },
  )
  // 场景三：row.path 存在时 form.path 一律被忽略（form.path 只是回显载体）
  assert.deepEqual(
    buildPermissionUpdatePayload(menuRow, { permissionName: 'x', path: '/form/other' }),
    { permissionName: 'x', path: '/content/reviews', method: null },
  )
  // 场景四：row 未携带 path 字段（纯逻辑复用/未来调用方）才回退 form.path
  const rowWithoutPath = { permissionType: 'MENU' }
  assert.deepEqual(
    buildPermissionUpdatePayload(rowWithoutPath, { permissionName: 'x', path: '/fallback/route' }),
    { permissionName: 'x', path: '/fallback/route', method: null },
  )
  // 场景五：row.path 显式为 null → 提交 null（契约：path 键必填，null 是合法终态）
  assert.deepEqual(
    buildPermissionUpdatePayload({ permissionType: 'MENU', path: null }, { permissionName: 'x', path: 'ignored' }),
    { permissionName: 'x', path: null, method: null },
  )
  // 场景六：BUTTON 同规则
  assert.deepEqual(
    buildPermissionUpdatePayload({ permissionType: 'BUTTON', path: '/content/publish' }, { permissionName: 'b' }),
    { permissionName: 'b', path: '/content/publish', method: null },
  )
  // 场景七（评审 P2）：前端不按 JS .length 复检长度——130 个 emoji 占 130 个
  // 码点（后端按 codePointCount 计 139 码点 ≤ 255，接受）但占 260 个 UTF-16 码元
  // （旧实现按 .length = 269 > 255 误拒且无处提示）；超长裁决交给后端
  // validateOptionalPath（同一请求 400），这里原样透传
  const emojiRoute = '/content/' + '📋'.repeat(130)
  assert.ok(emojiRoute.length > 255, '前置条件：按 UTF-16 码元确实超 255')
  const emojiCodePoints = [...emojiRoute].length
  assert.ok(emojiCodePoints <= 255, '前置条件：按 Unicode 码点后端会接受')
  const emojiRow = { permissionType: 'MENU', path: emojiRoute }
  assert.deepEqual(
    buildPermissionUpdatePayload(emojiRow, { permissionName: 'x' }),
    { permissionName: 'x', path: emojiRoute, method: null },
  )
  // API 分支不受影响：仍按 API_PATH_PATTERN 校验
  assert.deepEqual(
    buildPermissionUpdatePayload({ permissionType: 'API' }, { permissionName: 'x', path: '/api/admin/a', method: 'GET' }),
    { permissionName: 'x', path: '/api/admin/a', method: 'GET' },
  )
})

test('分配权限一致性校验（评审 P1）：已分配且启用但目录无法预选时必须报告，阻止保存防误删', () => {
  const assignedRow = (id, status = 1) => ({ id, status })
  const catalogRow = (id, status = 1) => ({ id, status })

  // 场景一：目录把已分配且启用的权限读成「缺失」→ 必须报告
  const missing = findUnpreselectableAssigned(
    [assignedRow('3'), assignedRow('9')],
    [catalogRow('9')],
  )
  assert.deepEqual(missing, ['3'])

  // 场景二：目录把已分配且启用的权限读成「停用」→ 同样必须报告（status≠1 不可勾选）
  const disabledInCatalog = findUnpreselectableAssigned(
    [assignedRow('3'), assignedRow('9')],
    [catalogRow('3'), catalogRow('9', 0)],
  )
  assert.deepEqual(disabledInCatalog, ['9'])

  // 场景三：两边一致 → 空数组，允许保存
  const consistent = findUnpreselectableAssigned(
    [assignedRow('3'), assignedRow('9')],
    [catalogRow('3'), catalogRow('9'), catalogRow('12')],
  )
  assert.deepEqual(consistent, [])

  // 场景四：已分配但停用的项不在目录中 → 不误报（契约本就允许保存时移除停用项）
  const disabledAssigned = findUnpreselectableAssigned(
    [assignedRow('3', 0), assignedRow('9')],
    [catalogRow('9')],
  )
  assert.deepEqual(disabledAssigned, [])

  // 防御性输入：catalog/assigned 非数组时按空处理，不抛错
  assert.deepEqual(findUnpreselectableAssigned([assignedRow('3')], null), ['3'])
  assert.deepEqual(findUnpreselectableAssigned(null, [catalogRow('3')]), [])
})

test('并发基线签名（评审 P1 并发覆盖）：顺序无关、去重、变化可检出', () => {
  // 相同集合不同顺序 → 同一签名（对比必须忽略读取顺序）
  assert.equal(
    permissionBaseline([{ id: '3' }, { id: '9' }]),
    permissionBaseline([{ id: '9' }, { id: '3' }]),
  )
  // 重复 ID 去重；数字 ID 与字符串 ID 归一为同一签名（后端统一返回字符串）
  assert.equal(permissionBaseline([{ id: '3' }, { id: 3 }, { id: '9' }]), '3,9')
  // 空集合 / 缺失 ID 的脏数据项被忽略
  assert.equal(permissionBaseline([]), '')
  assert.equal(permissionBaseline([{ id: '3' }, { status: 1 }, null]), '3')
  // 防御性输入：非数组按空处理
  assert.equal(permissionBaseline(null), '')
  // 变化检出：增/删都改变签名（评审回归场景：甲撤销 X 后乙的基线比对必须失败）
  const before = permissionBaseline([{ id: '3' }, { id: '9' }])
  assert.notEqual(before, permissionBaseline([{ id: '9' }]), '甲撤销权限 3 后基线必须不同')
  assert.notEqual(before, permissionBaseline([{ id: '3' }, { id: '9' }, { id: '12' }]), '乙新增权限 12 后基线必须不同')
})

test('状态漂移检测（评审 P2）：两份读取的 status 不一致必须报告，避免「提示移除+预选保留」矛盾', () => {
  const assignedRow = (id, status = 1) => ({ id, status })
  const catalogRow = (id, status = 1) => ({ id, status })

  // 场景一：目录比已分配新（期间被停用）→ 报告 3
  assert.deepEqual(
    findStatusConflicts([assignedRow('3', 1)], [catalogRow('3', 0)]),
    ['3'],
  )
  // 场景二：目录比已分配旧（评审复现：读取已分配后被启用）→ 报告 9
  assert.deepEqual(
    findStatusConflicts([assignedRow('9', 0)], [catalogRow('9', 1)]),
    ['9'],
  )
  // 场景三：两边一致（含都是停用）→ 空
  assert.deepEqual(
    findStatusConflicts([assignedRow('3', 1), assignedRow('5', 0)], [catalogRow('3', 1), catalogRow('5', 0), catalogRow('7', 1)]),
    [],
  )
  // 场景四：catalog 缺失的已分配项不算 status 冲突（那是 findUnpreselectableAssigned 的职责）
  assert.deepEqual(findStatusConflicts([assignedRow('3', 1)], [catalogRow('9', 1)]), [])
  // 防御性输入：非数组按空处理，不抛错
  assert.deepEqual(findStatusConflicts([assignedRow('3')], null), [])
  assert.deepEqual(findStatusConflicts(null, [catalogRow('3')]), [])
})

test('角色与权限页并发/一致性接线（评审四条修复的静态源码断言）', () => {
  const source = readFileSync(new URL('../src/views/role/RolePermissionView.vue', import.meta.url), 'utf8')
  const utilsSource = readFileSync(new URL('../src/utils/rolePermission.js', import.meta.url), 'utf8')

  // P1 并发覆盖：打开弹窗记录基线、保存前重读并比对、冲突阻止保存并提示重新加载
  assert.match(source, /assignDialog\.baseline = permissionBaseline\(assigned\)/)
  assert.match(source, /await permissionApi\.getRolePermissions\(roleId\)/)
  assert.match(source, /permissionBaseline\(latestAssigned\) !== assignDialog\.baseline/)
  assert.match(source, /assignDialog\.concurrencyError/)
  assert.match(source, /!!assignDialog\.concurrencyError/)
  assert.match(utilsSource, /export function permissionBaseline/)
  // P2 路径原样：payload 以 row.path 为准，不再有 trim 路由的 normalizeMenuRoute
  assert.doesNotMatch(utilsSource, /normalizeMenuRoute/)
  assert.match(utilsSource, /hasOwnProperty\.call\(row, 'path'\)/)
  // P2 状态矛盾：双向漂移检测接线 + 「保存后移除」提示统一依据 catalog 状态
  assert.match(source, /findStatusConflicts\(assigned, catalog\)/)
  assert.match(source, /assignRemovedDisabled\.value = catalog/)
  // P2 写入后刷新：5 个写成功点统一走 afterWrite（重载会话权限 + 刷新列表）
  assert.match(source, /async function afterWrite\(\)/)
  const afterWriteCalls = source.match(/await afterWrite\(\)/g) ?? []
  assert.ok(afterWriteCalls.length >= 5, `5 个写成功点都应调用 afterWrite，实际 ${afterWriteCalls.length} 处`)
  // 合并 dev 后工作区文件为 CRLF（git core.autocrlf），断言需兼容两种换行符
  assert.match(source, /await loadPermissions\(\)\r?\n  if \(disposed\) return\r?\n  fetchRoles\(\)/)
})

test('permissionIds 规范化：trim 去重、上限 100、非法项拒绝、空数组合法', () => {
  assert.deepEqual(normalizePermissionIds([' 9 ', '9', '3']), ['9', '3'])
  assert.deepEqual(normalizePermissionIds([]), [])
  assert.throws(() => normalizePermissionIds('not-array'), TypeError)
  assert.throws(() => normalizePermissionIds([null]), /第 1 项为空/)
  assert.throws(() => normalizePermissionIds(['0']), TypeError)
  assert.throws(() => normalizePermissionIds(['x']), TypeError)
  assert.throws(() => normalizePermissionIds(Array.from({ length: 101 }, (_, i) => String(i + 1))), /最多 100 项/)
})

test('时间展示：LocalDateTime 转本地可读格式，异常输入原样返回', () => {
  assert.equal(formatDateTime('2026-11-02T16:00:00.000'), '2026-11-02 16:00:00')
  assert.equal(formatDateTime('2026-11-02 16:00:00'), '2026-11-02 16:00:00')
  assert.equal(formatDateTime('abc'), 'abc')
  assert.equal(formatDateTime(null), '—')
  assert.equal(formatDateTime(undefined), '—')
})
