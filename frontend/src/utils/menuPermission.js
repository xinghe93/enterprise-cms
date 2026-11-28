/**
 * 菜单与路由的权限映射（MENU 权限接入，《接口与协作约定》3.4：
 * 「MENU/BUTTON 权限和前端路由由 B 与前端骨架阶段共同补齐」）。
 *
 * <p>职责边界：<b>前端隐藏菜单不构成鉴权</b>（设计计划书 3.2），接口安全由后端
 * {@code @RequirePermission} 保证；本模块只保证「前端展示与后端有效权限一致」——
 * 没有该页面权限的账号看不到入口、直达 URL 会被路由守卫拦到 403 页。</p>
 *
 * <p>组合页面（单入口多 Tab / 多子功能）用<b>任一满足</b>语义：例如分类与标签页
 * 只要 {@code category:tree} 或 {@code tag:list} 之一即可进入，页面内部再按子功能
 * 各自门控，避免「有标签权限却被整页拦掉」。</p>
 *
 * <p>全部为纯函数，不依赖 Vue/Pinia，可直接在 Node 原生测试中覆盖。</p>
 */

/**
 * 归一化入口声明的权限码：支持数组（任一满足）与单值，去重去空。
 *
 * @param {{ requiredPermissions?: string[], requiredPermission?: string } | null} item
 * @returns {string[]}
 */
export function requiredCodes(item) {
  if (!item || typeof item !== 'object') return []
  const raw = Array.isArray(item.requiredPermissions)
    ? item.requiredPermissions
    : (item.requiredPermission ? [item.requiredPermission] : [])
  return [...new Set(raw.filter((code) => typeof code === 'string' && code.length > 0))]
}

/**
 * 单个菜单入口是否对当前账号可见。
 *
 * <p>未声明权限码表示「登录即可见」（如工作空间）；一旦声明，命中其中<b>任一</b>
 * 编码即可见（组合页面的子功能并集）。</p>
 *
 * @param {{ requiredPermissions?: string[] } | null} item 菜单入口
 * @param {(code: string) => boolean} hasPermission 会话权限判定
 */
export function canSeeMenuItem(item, hasPermission) {
  if (!item || typeof item !== 'object') return false
  const codes = requiredCodes(item)
  if (codes.length === 0) return true
  if (typeof hasPermission !== 'function') return false
  return codes.some((code) => hasPermission(code) === true)
}

/**
 * 按当前账号有效权限过滤菜单分组，返回新数组、不改动入参。
 *
 * <p>组内入口全部不可见时整组隐藏（避免出现只有标题的空分组）。
 * 权限未加载完成时 {@code hasPermission} 通常恒为 false，结果只剩免权限入口，
 * 这与「尚未确认的入口不展示」一致；权限加载失败同样不会误展示。</p>
 *
 * @param {Array<{ title: string, items: Array }>} groups 导航分组
 * @param {(code: string) => boolean} hasPermission 会话权限判定
 */
export function visibleNavigation(groups, hasPermission) {
  if (!Array.isArray(groups)) return []
  return groups
    .map((group) => ({
      ...group,
      items: (Array.isArray(group?.items) ? group.items : [])
        .filter((item) => canSeeMenuItem(item, hasPermission)),
    }))
    .filter((group) => group.items.length > 0)
}

/**
 * 导航时是否需要刷新权限快照（评审 P2：已进入的组合页也要更新新增子功能权限）。
 *
 * <p>只在「即将判 403」时才刷新是不够的：若旧快照已满足入口的任一编码（如
 * `category:tree`），守卫不会刷新，而会话期间新增的子功能权限（`tag:list`）永远
 * 读不到——页面自己的 `hasPermission` 仍用旧快照，且页面不主动拉权限。因此只要
 * 进入<b>声明了权限的业务页面</b>就刷新一次（`force` 忽略 loaded），保证本会话内
 * 新增的子功能权限即时生效。权限接口只返回字符串数组、很轻量，且同一令牌的并发
 * 导航由 `inflight` 复用同一请求，不会放大流量。</p>
 *
 * @param {object} state
 * @param {string[]|string} [state.requiredPermissions] 目标路由声明的权限编码
 * @param {boolean} state.isAuthenticated 是否已登录
 */
export function shouldRefreshOnNavigation({ requiredPermissions, isAuthenticated }) {
  if (isAuthenticated !== true) return false
  return requiredCodes({ requiredPermissions }).length > 0
}

/**
 * 同页重复点击菜单是否应直接刷新权限（评审 P2）：
 * Vue Router 4.6.4 对与当前路径相同的 `router.push` 判为 `duplicated`、不触发
 * `beforeEach`，所以守卫里的刷新不会发生。用户停留在业务页时管理员新授予的子功能
 * 权限（如 `tag:list`）在「再点一次当前菜单」时不会生效，页面自身的 Tab 又是本地
 * ref、不会重新读权限。因此菜单点击必须自己识别同页并直接刷新。
 *
 * @param {string} targetPath 被点击菜单的目标路径
 * @param {string} currentPath 当前路由 fullPath
 */
export function shouldRefreshOnSamePageClick(targetPath, currentPath) {
  return typeof targetPath === 'string' && targetPath.length > 0 && targetPath === currentPath
}

/**
 * 路由访问决策：`allow` 放行 / `login` 跳登录 / `forbidden` 跳 403。
 *
 * <p>权限状态只有 {@code loaded} 时才按编码裁决；{@code idle}/{@code loading}/
 * {@code unavailable} 一律放行，由页面自身门控兜底——避免登录后首次直达深链时
 * 因权限尚未返回，把本来有权限的账号误判成 403（不要用「尚未知」当成「没有」）。</p>
 *
 * <p>声明的多个编码按<b>任一满足</b>判通过（组合页面）。</p>
 *
 * @param {object} state
 * @param {string[]|string} [state.requiredPermissions] 路由声明的权限编码
 * @param {boolean} state.isAuthenticated 是否已登录
 * @param {string} state.permissionStatus idle / loading / loaded / unavailable
 * @param {(code: string) => boolean} state.hasPermission 会话权限判定
 * @returns {'allow' | 'login' | 'forbidden'}
 */
export function resolveMenuAccess({
  requiredPermissions, requireAll = false, isAuthenticated, permissionStatus, hasPermission,
}) {
  const codes = requiredCodes({ requiredPermissions })
  if (codes.length === 0) return 'allow'
  if (isAuthenticated !== true) return 'login'
  if (permissionStatus !== 'loaded') return 'allow'
  if (typeof hasPermission !== 'function') return 'forbidden'
  const allowed = requireAll
    ? codes.every((code) => hasPermission(code) === true)
    : codes.some((code) => hasPermission(code) === true)
  return allowed ? 'allow' : 'forbidden'
}

/**
 * 工作空间快捷入口的权限过滤（复用 navigation.js 的 requiredPermissions 权威定义）：
 *  - 未登录或权限加载失败（unavailable）时展示全部概览入口（点击由守卫或页面自身门控兜底）；
 *  - 权限已加载（loaded）时严格按有效权限过滤，隐藏无权访问的链接及空卡片；
 *  - 权限尚在首次加载（idle/loading）时暂不展示需权限的链接，避免闪现无权入口。
 */
export function filterWorkspaceAreas(areas, navGroups, { isAuthenticated, permissionStatus, hasPermission }) {
  if (!Array.isArray(areas)) return []
  const byPath = new Map(
    (Array.isArray(navGroups) ? navGroups : [])
      .flatMap((group) => (Array.isArray(group?.items) ? group.items : []))
      .map((item) => [item.path, item]),
  )
  const canSeePath = (path) => {
    const entry = byPath.get(path)
    if (!entry) return false
    if (isAuthenticated !== true || permissionStatus === 'unavailable') return true
    if (permissionStatus !== 'loaded') return false
    return canSeeMenuItem(entry, hasPermission)
  }
  return areas
    .map((area) => ({
      ...area,
      links: (Array.isArray(area?.links) ? area.links : []).filter((link) => canSeePath(link.path)),
    }))
    .filter((area) => area.links.length > 0)
}

/**
 * 业务页面权限就绪态：区分「首次加载中」「加载失败」「已就绪」。
 *
 * <p>路由守卫在导航离开前或同页点击菜单时会触发 {@code session.refreshPermissions()}，
 * 使 {@code permissionStatus} 短暂从 {@code loaded} 切到 {@code loading} 再回到
 * {@code loaded}（期间 {@code permissionCodes} 仍保留本令牌最近一次快照）。
 * 若页面在已有快照时因 {@code loading} 立即把权限视作未就绪，会导致切页卸载前
 * 闪白、清空已选筛选条件并重发无效列表请求。</p>
 *
 * @param {object} state
 * @param {string} state.permissionStatus idle / loading / loaded / unavailable
 * @param {boolean} state.hasSnapshot 当前令牌是否已成功加载过至少一次权限快照
 * @returns {'ready' | 'loading' | 'unavailable'}
 */
export function resolvePagePermissionState({ permissionStatus, hasSnapshot }) {
  if (permissionStatus === 'loaded') return 'ready'
  if (permissionStatus === 'unavailable') return 'unavailable'
  if (hasSnapshot === true && permissionStatus === 'loading') return 'ready'
  return 'loading'
}

/**
 * 校验登录后回跳地址：只允许站内相对路径（以单斜杠开头、非 // 协议相对地址、
 * 不含反斜杠且不回跳到 /login 或 /forbidden），其余一律回落 /workspace。
 */
export function safeRedirectPath(raw) {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string') return '/workspace'
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return '/workspace'
  const pathname = value.split(/[?#]/)[0]
  if (pathname === '/login' || pathname === '/forbidden') return '/workspace'
  return value
}
