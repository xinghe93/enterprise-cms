/**
 * 菜单与路由的权限映射（MENU 权限接入，《接口与协作约定》3.4：
 * 「MENU/BUTTON 权限和前端路由由 B 与前端骨架阶段共同补齐」）。
 *
 * <p>职责边界：<b>前端隐藏菜单不构成鉴权</b>（设计计划书 3.2），接口安全由后端
 * {@code @RequirePermission} 保证；本模块只保证「前端展示与后端有效权限一致」——
 * 没有该页面权限的账号看不到入口、直达 URL 会被路由守卫拦到 403 页。</p>
 *
 * <p>全部为纯函数，不依赖 Vue/Pinia，可直接在 Node 原生测试中覆盖。</p>
 */

/**
 * 单个菜单入口是否对当前账号可见。
 *
 * <p>{@code requiredPermission} 缺省表示「登录即可见」（如工作空间）；
 * 一旦声明，则必须由 {@code hasPermission(code)} 返回 true 才可见。</p>
 *
 * @param {{ requiredPermission?: string } | null} item 菜单入口
 * @param {(code: string) => boolean} hasPermission 会话权限判定
 */
export function canSeeMenuItem(item, hasPermission) {
  if (!item || typeof item !== 'object') return false
  const required = item.requiredPermission
  if (!required) return true
  return typeof hasPermission === 'function' && hasPermission(required) === true
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
 * 路由访问决策：`allow` 放行 / `login` 跳登录 / `forbidden` 跳 403。
 *
 * <p>权限状态只有 {@code loaded} 时才按编码裁决；{@code idle}/{@code loading}/
 * {@code unavailable} 一律放行，由页面自身门控兜底——避免登录后首次直达深链时
 * 因权限尚未返回，把本来有权限的账号误判成 403（评审同类问题的教训：
 * 不要用「尚未知」当成「没有」）。</p>
 *
 * @param {object} state
 * @param {string} [state.requiredPermission] 路由声明的权限编码
 * @param {boolean} state.isAuthenticated 是否已登录
 * @param {string} state.permissionStatus idle / loading / loaded / unavailable
 * @param {(code: string) => boolean} state.hasPermission 会话权限判定
 * @returns {'allow' | 'login' | 'forbidden'}
 */
export function resolveMenuAccess({ requiredPermission, isAuthenticated, permissionStatus, hasPermission }) {
  if (!requiredPermission) return 'allow'
  if (isAuthenticated !== true) return 'login'
  if (permissionStatus !== 'loaded') return 'allow'
  return typeof hasPermission === 'function' && hasPermission(requiredPermission) === true
    ? 'allow'
    : 'forbidden'
}
