/**
 * 「分配角色」弹窗所需的角色候选项（OpenAPI GET /api/admin/roles，B 模块契约）。
 *
 * 这里只是按契约调用 B 的只读分页接口，供 A 的角色分配页选择启用角色；
 * 不实现 B 的角色管理功能，也不修改 B 的后端模块。B 后续做角色管理页可直接复用。
 * 契约要求：全量替换的每个角色都必须启用，故候选固定带 status=1。
 */
import { compactParams } from '../utils/organization.js'

const PAGE_SIZE = 100

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
     *  - total 缺失 / 非法 / 翻页间变化、空页未取满、超过页数上限，都视为目录不可信而抛错，
     *    由上层 ensureRoles 标记加载失败并禁用保存，绝不返回不完整候选让全量替换清空角色。
     *  - 真「零角色」（total=0 且首页空）是合法结果，正常返回空数组。
     */
    listAssignableRoles: async () => {
      const records = []
      const seenIds = new Set()
      const maxPages = 100
      let declaredTotal = null
      for (let current = 1; current <= maxPages; current += 1) {
        const page = await client.get('/admin/roles', {
          params: { current, size: PAGE_SIZE, status: 1 },
        })
        const rows = page?.records
        const rawTotal = page?.total
        // 契约 total 为 int64；部分 JSON 序列化会把长整型写成字符串。
        // 只接受「纯十进制整数字符串」，不接受小数/指数/空串，避免把脏值静默转成 0。
        const total = typeof rawTotal === 'string' && /^\d+$/.test(rawTotal.trim())
          ? Number(rawTotal.trim()) : rawTotal
        if (!Array.isArray(rows) || !Number.isSafeInteger(total) || total < 0) {
          throw new TypeError('角色目录分页数据异常，已中止加载以避免误删现有角色')
        }
        if (declaredTotal === null) {
          declaredTotal = total
        } else if (total !== declaredTotal) {
          throw new TypeError('角色目录分页总数不一致，已中止加载以避免误删现有角色')
        }
        if (rows.length === 0) {
          // 空页：只有「一条都不该有」才算完整，否则是提前截断。
          if (records.length !== declaredTotal) {
            throw new RangeError('角色目录提前返回空页，已中止加载以避免误删现有角色')
          }
          break
        }
        // 重复记录会让 records.length 误达 total 而掩盖真实缺失；缺 ID 同样无法
        // 证明目录完整，都按不可信中止（DeepSeek 审查 #2）。
        for (const row of rows) {
          if (row?.id === null || row?.id === undefined) {
            throw new TypeError('角色目录记录缺少 ID，已中止加载以避免误删现有角色')
          }
          const key = String(row.id)
          if (seenIds.has(key)) {
            throw new RangeError('角色目录出现重复角色 ID，已中止加载以避免误删现有角色')
          }
          seenIds.add(key)
          records.push(row)
        }
        if (records.length >= declaredTotal) break
      }
      // 达到页数上限仍未取满 = 目录不完整，抛错而非返回残缺候选；
      // 记录数超过声明 total 同样不可信（服务端翻页异常），单独给出错误信息。
      if (declaredTotal === null || records.length !== declaredTotal) {
        if (records.length > declaredTotal) {
          throw new RangeError('角色目录记录数超过声明 total，已中止加载以避免误删现有角色')
        }
        throw new RangeError('角色目录未完整加载，已中止以避免误删现有角色')
      }
      return { records, total: records.length }
    },
  }
}
