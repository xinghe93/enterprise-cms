/**
 * 「分配角色」弹窗所需的角色候选项（OpenAPI GET /api/admin/roles，B 模块契约）。
 *
 * 这里只是按契约调用 B 的只读分页接口，供 A 的角色分配页选择启用角色；
 * 不实现 B 的角色管理功能，也不修改 B 的后端模块。B 后续做角色管理页可直接复用。
 * 契约要求：全量替换的每个角色都必须启用，故候选固定带 status=1。
 */
import { compactParams } from '../utils/organization.js'

const PAGE_SIZE = 100

/**
 * 目录拉取的绝对安全上限（页）：服务端可能合法地返回不足一页的非空页（如翻页间
 * 有增删导致分页偏移），页数无法由 total 精确推导；此上限只为防病态服务端死循环
 * （100 页 × 100 条 = 1 万条，远超角色业务规模），超限按「未完整加载」抛错禁用保存。
 */
const MAX_PAGES = 100

export function createRoleApi(client) {
  return {
    listRoles: (params = {}) => client.get('/admin/roles', { params: compactParams(params) }),
    /**
     * 分配弹窗用：分页拉全部启用角色，只保留 records 数组。
     * 不能只取第一页——员工若持有第二页的启用角色，用不完整候选做全量替换会误删该角色。
     *
     * 候选必须「完整且可信」才可用于全量替换，这里对分页做防御：
     *  - 以服务端 total 为推进依据，不能用 rows.length < size 提前退出：
     *    某页若因故返回不足一页但后面仍有数据，提前退出会截断候选；
     *  - total 缺失 / 非法 / 翻页间变化、空页未取满、超过 MAX_PAGES 安全上限，都视为
     *    目录不可信而抛错，由上层 ensureRoles 标记加载失败并禁用保存，绝不返回不完整候选
     *    让全量替换清空角色。
     *  - 跨页出现重复角色 ID（翻页间有角色更新时可能发生）同样视为不可信而抛错：
     *    若放过重复项，候选长度会与 total 相等但实际漏掉其他角色，保存时会误删漏掉的角色。
     *  - 真「零角色」（total=0 且首页空）是合法结果，正常返回空数组。
     */
    listAssignableRoles: async () => {
      const records = []
      const seenRoleIds = new Set()
      let declaredTotal = null
      for (let current = 1; current <= MAX_PAGES; current += 1) {
        const page = await client.get('/admin/roles', {
          params: { current, size: PAGE_SIZE, status: 1 },
        })
        const rows = page?.records
        const rawTotal = page?.total
        if (!Array.isArray(rows) || !Number.isSafeInteger(rawTotal) || rawTotal < 0) {
          throw new TypeError('角色目录分页数据异常，已中止加载以避免误删现有角色')
        }
        if (declaredTotal === null) {
          declaredTotal = rawTotal
        } else if (rawTotal !== declaredTotal) {
          throw new TypeError('角色目录分页总数不一致，已中止加载以避免误删现有角色')
        }
        if (rows.length === 0) {
          // 空页：只有「一条都不该有」才算完整，否则是提前截断。
          if (records.length !== declaredTotal) {
            throw new RangeError('角色目录提前返回空页，已中止加载以避免误删现有角色')
          }
          break
        }
        // 逐项检查 ID 唯一性：重复会让「已取条数」虚高、掩盖漏取的角色。
        for (const row of rows) {
          const roleId = String(row?.id ?? '')
          if (!roleId) {
            throw new TypeError('角色目录存在缺失 ID 的记录，已中止加载以避免误删现有角色')
          }
          if (seenRoleIds.has(roleId)) {
            throw new TypeError('角色目录出现重复角色 ID，已中止加载以避免误删现有角色')
          }
          seenRoleIds.add(roleId)
          records.push(row)
        }
        if (records.length >= declaredTotal) break
      }
      // 达到页数上限仍未取满 = 目录不完整，抛错而非返回残缺候选；
      // 记录数超过声明 total 同样不可信（服务端翻页异常），单独给出错误信息。
      if (records.length !== declaredTotal) {
        if (records.length > declaredTotal) {
          throw new RangeError('角色目录记录数超过声明 total，已中止加载以避免误删现有角色')
        }
        throw new RangeError('角色目录未完整加载，已中止以避免误删现有角色')
      }
      return { records, total: records.length }
    },
  }
}
