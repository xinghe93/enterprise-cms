import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  canSeeMenuItem,
  requiredCodes,
  resolveMenuAccess,
  shouldRefreshOnNavigation,
  visibleNavigation,
} from '../src/utils/menuPermission.js'
import { navigation, moduleEntries } from '../src/router/navigation.js'

test('单入口可见性：未声明恒显；声明后任一命中即可见（组合页面语义）', () => {
  // 工作空间等免权限入口：登录即可见
  assert.equal(canSeeMenuItem({ path: '/workspace' }, () => false), true)

  // 单码：命中才可见
  assert.equal(canSeeMenuItem({ requiredPermissions: ['role:list'] }, (c) => c === 'role:list'), true)
  assert.equal(canSeeMenuItem({ requiredPermissions: ['role:list'] }, () => false), false)

  // 组合页面：category:tree / tag:list 任一命中即可（评审 P2：只有标签权限也要能进「分类与标签」）
  const taxonomy = { requiredPermissions: ['category:tree', 'tag:list'] }
  assert.equal(canSeeMenuItem(taxonomy, (c) => c === 'tag:list'), true)
  assert.equal(canSeeMenuItem(taxonomy, (c) => c === 'category:tree'), true)
  assert.equal(canSeeMenuItem(taxonomy, (c) => c === 'article:list'), false)

  // 兼容旧的单值字段，避免历史数据静默失去可见性
  assert.equal(canSeeMenuItem({ requiredPermission: 'role:list' }, (c) => c === 'role:list'), true)

  // 判定函数缺失或非函数：一律不可见（fail-closed）
  assert.equal(canSeeMenuItem({ requiredPermissions: ['role:list'] }, undefined), false)
  assert.equal(canSeeMenuItem({ requiredPermissions: ['role:list'] }, null), false)

  // 脏数据防御
  assert.equal(canSeeMenuItem(null, () => true), false)
  assert.equal(canSeeMenuItem('role:list', () => true), false)
})

test('权限码归一化：数组/单值/去重/去空都稳定', () => {
  assert.deepEqual(requiredCodes({ requiredPermissions: ['a', 'a', '', null, 'b'] }), ['a', 'b'])
  assert.deepEqual(requiredCodes({ requiredPermission: 'role:list' }), ['role:list'])
  assert.deepEqual(requiredCodes({}), [])
  assert.deepEqual(requiredCodes(null), [])
})

test('菜单分组过滤：空分组整组隐藏、不改动入参、非数组返回空', () => {
  const groups = [
    { title: '工作空间', items: [{ path: '/workspace' }, { path: '/portal', requiredPermissions: ['portal:article:list'] }] },
    { title: '组织管理', items: [{ path: '/admin/roles', requiredPermissions: ['role:list', 'permission:list'] }] },
  ]
  const snapshot = JSON.stringify(groups)

  // 只有角色 Tab 权限：组合页仍可见
  const onlyRoleTab = visibleNavigation(groups, (code) => code === 'role:list' || code === 'portal:article:list')
  assert.deepEqual(onlyRoleTab.map((g) => g.title), ['工作空间', '组织管理'])
  assert.deepEqual(onlyRoleTab[1].items.map((i) => i.path), ['/admin/roles'])

  // 只有权限项 Tab 权限：同样可见（评审 P2 的权限项管理员场景）
  const onlyPermTab = visibleNavigation(groups, (code) => code === 'permission:list')
  assert.deepEqual(onlyPermTab.map((g) => g.title), ['工作空间', '组织管理'])
  assert.deepEqual(onlyPermTab[1].items.map((i) => i.path), ['/admin/roles'])

  // 无任何权限：只剩免权限入口，需权限的整组消失
  const none = visibleNavigation(groups, () => false)
  assert.deepEqual(none.map((g) => g.title), ['工作空间'])
  assert.deepEqual(none[0].items.map((i) => i.path), ['/workspace'])

  // 不改动入参（渲染层复用同一份导航定义）
  assert.equal(JSON.stringify(groups), snapshot)

  // 防御性输入
  assert.deepEqual(visibleNavigation(null, () => true), [])
  assert.deepEqual(visibleNavigation(undefined, () => true), [])
})

test('路由访问决策：未就绪不误判、组合页任一满足、未登录跳登录', () => {
  const base = { requiredPermissions: ['role:list', 'permission:list'], hasPermission: (c) => c === 'permission:list' }

  // 未登录：需要权限的页面跳登录
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: false, permissionStatus: 'idle' }), 'login')
  // 已登录但权限还在加载 / 加载失败：放行，由页面自身门控兜底（不把「尚未知/不可用」当成「没有」）
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'idle' }), 'allow')
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'loading' }), 'allow')
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'unavailable' }), 'allow')
  // 已加载：任一命中放行
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'loaded' }), 'allow')
  // 已加载且全未命中：拒绝
  assert.equal(resolveMenuAccess({
    ...base, hasPermission: () => false, isAuthenticated: true, permissionStatus: 'loaded',
  }), 'forbidden')
  // 未声明权限的路由（工作空间/登录/404）：任何状态都放行
  assert.equal(resolveMenuAccess({ isAuthenticated: false, permissionStatus: 'idle' }), 'allow')
  assert.equal(resolveMenuAccess({ requiredPermissions: [], isAuthenticated: false, permissionStatus: 'idle' }), 'allow')
})

test('进入已放行的页面也要刷新权限（评审 P2 子功能新增即时生效）', () => {
  // 评审复现：登录时只有 category:tree、随后被授予 tag:list；旧快照已满足组合页
  // 入口条件，若只在 forbidden 前刷新，进入 /admin/categories 时 tag:list 永远读不到。
  // 规则：只要进入声明了权限的业务页面（含已放行的）就刷新，未登录/免权限页不刷新。
  assert.equal(shouldRefreshOnNavigation({
    requiredPermissions: ['category:tree', 'tag:list'], isAuthenticated: true,
  }), true)
  assert.equal(shouldRefreshOnNavigation({
    requiredPermissions: ['role:list', 'permission:list'], isAuthenticated: true,
  }), true)
  // 已加载（loaded）也要刷新——force 忽略快照新鲜度，这正是本轮修复点
  assert.equal(shouldRefreshOnNavigation({
    requiredPermissions: ['category:tree'], isAuthenticated: true,
  }), true)
  // 免权限页（工作空间/403/登录）不刷新
  assert.equal(shouldRefreshOnNavigation({ isAuthenticated: true }), false)
  assert.equal(shouldRefreshOnNavigation({ requiredPermissions: [], isAuthenticated: true }), false)
  // 未登录不刷新（无有效令牌，刷新也拿不到权限）
  assert.equal(shouldRefreshOnNavigation({
    requiredPermissions: ['role:list'], isAuthenticated: false,
  }), false)
})

test('导航声明的权限码必须存在于 OpenAPI x-permission-catalog（防止写错码导致菜单永久隐藏）', () => {
  const openapi = JSON.parse(readFileSync(new URL('../../docs/api/enterprise-cms.openapi.json', import.meta.url), 'utf8'))
  const catalog = new Set((openapi['x-permission-catalog'] || []).map((entry) => entry.code))
  assert.ok(catalog.size >= 53, `权限目录应至少 53 条，实际 ${catalog.size}`)

  const entries = navigation.flatMap((group) => group.items)
  for (const entry of entries) {
    const codes = requiredCodes(entry)
    if (entry.name === 'workspace') {
      assert.equal(codes.length, 0, '工作空间不应要求权限码')
      continue
    }
    assert.ok(codes.length > 0, `${entry.path} 必须声明 requiredPermissions`)
    for (const code of codes) {
      assert.ok(catalog.has(code), `${entry.path} 的权限码 ${code} 不在 OpenAPI 权限目录中`)
    }
  }
  assert.equal(moduleEntries.length, entries.length - 1, 'moduleEntries 应排除工作空间')
})

test('组合页面按「任一」声明（评审 P2）：分类标签 / 角色权限 / 岗位分配', () => {
  const byPath = Object.fromEntries(navigation.flatMap((g) => g.items).map((i) => [i.path, i]))
  // 只有 tag:list 的账号也能进「分类与标签」（TaxonomyView 支持标签 Tab）
  assert.ok(requiredCodes(byPath['/admin/categories']).includes('tag:list'))
  // 只有 permission:list 的权限项管理员也能进「角色与权限」
  assert.ok(requiredCodes(byPath['/admin/roles']).includes('permission:list'))
  // 只负责部门分配的账号（无 user:assign-role）也能进「岗位分配」
  const assignment = requiredCodes(byPath['/admin/assignments'])
  for (const code of ['user:list', 'user:read', 'user:update', 'department:tree', 'user:assign-role']) {
    assert.ok(assignment.includes(code), `岗位分配应接受 ${code}`)
  }
  // 审核工作台：待审/历史/通过/驳回任一即可
  const reviews = requiredCodes(byPath['/admin/reviews'])
  for (const code of ['article:review-list', 'review:list', 'article:approve', 'article:reject']) {
    assert.ok(reviews.includes(code), `审核与发布应接受 ${code}`)
  }
})

test('接线：守卫支持失败重试与拒绝前复核；菜单区分「加载失败」与「确实无权」', () => {
  const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
  const layoutSource = readFileSync(new URL('../src/layouts/AppLayout.vue', import.meta.url), 'utf8')
  const sessionSource = readFileSync(new URL('../src/stores/session.js', import.meta.url), 'utf8')

  // 路由：meta 带数组权限码、有守卫、注册 forbidden 页
  assert.match(routerSource, /meta: \{ title: entry\.title, requiredPermissions: entry\.requiredPermissions \}/)
  assert.match(routerSource, /router\.beforeEach\(async \(to\) => \{/)
  assert.match(routerSource, /name: 'forbidden'/)
  assert.match(routerSource, /ForbiddenView\.vue/)
  // P2-复查：进入任一需权限页面（含已放行）都刷新——用 shouldRefreshOnNavigation 判定；
  // 不再只在 forbidden 前刷新，否则会话内新增的子功能权限（tag:list）永远读不到。
  assert.match(routerSource, /shouldRefreshOnNavigation/)
  assert.match(routerSource, /if \(shouldRefreshOnNavigation\(\{[\s\S]{0,120}?\}\)\) \{\s*await session\.refreshPermissions\(\)/)
  assert.doesNotMatch(routerSource, /if \(session\.permissionStatus !== 'loaded'\) await session\.ensurePermissions\(\)/)

  // 会话：四态 + 强制刷新入口 + 同令牌复用 + 换令牌/切号丢弃旧结果
  assert.match(sessionSource, /permissionStatus: 'idle'/)
  assert.match(sessionSource, /async refreshPermissions\(\)/)
  assert.match(sessionSource, /inflight && inflight\.token === token/)
  assert.match(sessionSource, /store\.accessToken !== token/)

  // 布局：loaded 严格过滤；unavailable 展示全部 + 重试按钮
  assert.match(layoutSource, /const permissionFailed = computed\(\(\) => session\.permissionStatus === 'unavailable'\)/)
  assert.match(layoutSource, /permissionFailed\.value[\s\S]{0,80}?\? navigation/)
  assert.match(layoutSource, /visibleNavigation\(navigation, session\.hasPermission\)/)
  assert.match(layoutSource, /v-for="group in menuGroups"/)
  assert.match(layoutSource, /点击重试/)
  assert.match(layoutSource, /session\.refreshPermissions\(\)/)
  assert.doesNotMatch(layoutSource, /v-for="group in navigation"/)

  // 路由 meta 里的每个权限码也必须来自同一份权限目录
  const openapi = JSON.parse(readFileSync(new URL('../../docs/api/enterprise-cms.openapi.json', import.meta.url), 'utf8'))
  const catalog = new Set((openapi['x-permission-catalog'] || []).map((entry) => entry.code))
  const declared = [...routerSource.matchAll(/requiredPermissions: \[([^\]]+)\]/g)]
    .flatMap((m) => [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]))
  assert.ok(declared.length >= 4, `路由应声明至少 4 个权限码，实际 ${declared.length}`)
  for (const code of declared) {
    assert.ok(catalog.has(code), `路由权限码 ${code} 不在 OpenAPI 权限目录中`)
  }
})
