import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  canSeeMenuItem,
  resolveMenuAccess,
  visibleNavigation,
} from '../src/utils/menuPermission.js'
import { navigation, moduleEntries } from '../src/router/navigation.js'

test('单个菜单入口可见性（MENU 权限映射）：无声明即可见，声明后必须命中权限码', () => {
  // 工作空间等免权限入口：登录即可见
  assert.equal(canSeeMenuItem({ path: '/workspace' }, () => false), true)
  // 声明了权限码：命中才可见
  assert.equal(canSeeMenuItem({ requiredPermission: 'role:list' }, (c) => c === 'role:list'), true)
  assert.equal(canSeeMenuItem({ requiredPermission: 'role:list' }, () => false), false)
  // 判定函数缺失或非函数：一律不可见（fail-closed，不误展示）
  assert.equal(canSeeMenuItem({ requiredPermission: 'role:list' }, undefined), false)
  assert.equal(canSeeMenuItem({ requiredPermission: 'role:list' }, null), false)
  // 脏数据防御
  assert.equal(canSeeMenuItem(null, () => true), false)
  assert.equal(canSeeMenuItem('role:list', () => true), false)
})

test('菜单分组过滤：空分组整组隐藏、不改动入参、非数组返回空', () => {
  const groups = [
    { title: '工作空间', items: [{ path: '/workspace' }, { path: '/portal', requiredPermission: 'portal:article:list' }] },
    { title: '组织管理', items: [{ path: '/admin/roles', requiredPermission: 'role:list' }] },
  ]
  const snapshot = JSON.stringify(groups)

  // 只有工作空间权限：门户被过滤，组织管理整组隐藏
  const onlyWorkspace = visibleNavigation(groups, (code) => code === 'portal:article:list')
  assert.deepEqual(onlyWorkspace.map((g) => g.title), ['工作空间'])
  assert.deepEqual(onlyWorkspace[0].items.map((i) => i.path), ['/workspace', '/portal'])

  // 无任何权限：只剩免权限入口，需权限的整组消失
  const none = visibleNavigation(groups, () => false)
  assert.deepEqual(none.map((g) => g.title), ['工作空间'])
  assert.deepEqual(none[0].items.map((i) => i.path), ['/workspace'])

  // 不该改动入参（渲染层复用同一份导航定义）
  assert.equal(JSON.stringify(groups), snapshot)

  // 防御性输入
  assert.deepEqual(visibleNavigation(null, () => true), [])
  assert.deepEqual(visibleNavigation(undefined, () => true), [])
})

test('路由访问决策：未就绪不误判、已就绪按编码裁决、未登录跳登录', () => {
  const has = (code) => code === 'role:list'
  const base = { requiredPermission: 'role:list', hasPermission: has }

  // 未登录：需要权限的页面跳登录（不能因为权限为空就判 403）
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: false, permissionStatus: 'idle' }), 'login')
  // 已登录但权限还在加载：放行，由页面自身门控兜底（避免误判）
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'idle' }), 'allow')
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'loading' }), 'allow')
  // 加载失败：同样放行，不把「不可用」当成「没有」
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'unavailable' }), 'allow')
  // 已加载：有权限放行、无权限 403
  assert.equal(resolveMenuAccess({ ...base, isAuthenticated: true, permissionStatus: 'loaded' }), 'allow')
  assert.equal(resolveMenuAccess({
    ...base, hasPermission: () => false, isAuthenticated: true, permissionStatus: 'loaded',
  }), 'forbidden')
  // 未声明权限的路由（工作空间/登录/404）：任何状态都放行
  assert.equal(resolveMenuAccess({ isAuthenticated: false, permissionStatus: 'idle' }), 'allow')
})

test('导航声明的权限码必须存在于 OpenAPI x-permission-catalog（防止写错码导致菜单永久隐藏）', () => {
  const openapi = JSON.parse(readFileSync(new URL('../../docs/api/enterprise-cms.openapi.json', import.meta.url), 'utf8'))
  const catalog = new Set((openapi['x-permission-catalog'] || []).map((entry) => entry.code))
  assert.ok(catalog.size >= 53, `权限目录应至少 53 条，实际 ${catalog.size}`)

  // 工作空间免权限；其余每个入口都必须声明且声明的码在目录内
  const entries = navigation.flatMap((group) => group.items)
  for (const entry of entries) {
    if (entry.name === 'workspace') {
      assert.equal(entry.requiredPermission, undefined, '工作空间不应要求权限码')
      continue
    }
    assert.ok(entry.requiredPermission, `${entry.path} 必须声明 requiredPermission`)
    assert.ok(catalog.has(entry.requiredPermission),
      `${entry.path} 的权限码 ${entry.requiredPermission} 不在 OpenAPI 权限目录中`)
  }
  assert.equal(moduleEntries.length, entries.length - 1, 'moduleEntries 应排除工作空间')
})

test('菜单/路由接线：同一份权限码驱动菜单过滤与路由守卫，保存期状态不误判', () => {
  const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
  const layoutSource = readFileSync(new URL('../src/layouts/AppLayout.vue', import.meta.url), 'utf8')
  const sessionSource = readFileSync(new URL('../src/stores/session.js', import.meta.url), 'utf8')

  // 路由：meta 带 requiredPermission、有登录/403 守卫、注册 forbidden 页
  assert.match(routerSource, /meta: \{ title: entry\.title, requiredPermission: entry\.requiredPermission \}/)
  assert.match(routerSource, /router\.beforeEach\(async \(to\) => \{/)
  assert.match(routerSource, /resolveMenuAccess\(\{/)
  assert.match(routerSource, /name: 'forbidden'/)
  assert.match(routerSource, /ForbiddenView\.vue/)
  // 路由 meta 里的每个 requiredPermission 也必须来自同一份权限目录
  const openapi = JSON.parse(readFileSync(new URL('../../docs/api/enterprise-cms.openapi.json', import.meta.url), 'utf8'))
  const catalog = new Set((openapi['x-permission-catalog'] || []).map((entry) => entry.code))
  const declared = [...routerSource.matchAll(/requiredPermission: '([^']+)'/g)].map((m) => m[1])
  assert.ok(declared.length >= 4, `路由应声明至少 4 个权限码，实际 ${declared.length}`)
  for (const code of declared) {
    assert.ok(catalog.has(code), `路由权限码 ${code} 不在 OpenAPI 权限目录中`)
  }

  // 布局：菜单来自 visibleNavigation，模板不再直接遍历 navigation
  assert.match(layoutSource, /const menuGroups = computed\(\(\) => visibleNavigation\(navigation, session\.hasPermission\)\)/)
  assert.match(layoutSource, /v-for="group in menuGroups"/)
  assert.doesNotMatch(layoutSource, /v-for="group in navigation"/)

  // 会话：权限状态四态 + 换令牌重置 + 并发复用请求 + 换账号丢弃旧结果
  assert.match(sessionSource, /permissionStatus: 'idle'/)
  assert.match(sessionSource, /this\.permissionStatus = 'idle'/)
  assert.match(sessionSource, /inflight && inflight\.token === this\.accessToken/)
  assert.match(sessionSource, /this\.accessToken !== tokenAtStart/)
})
