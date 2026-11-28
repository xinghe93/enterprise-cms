<script setup>
// 操作日志查询页（D 模块）：按会话有效权限（log:list / log:read）严格门控，
// 区分权限加载中 / 加载失败 / 已加载无权，不在未确认权限或空权限时误放行查询。
import { computed, onUnmounted, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElDatePicker,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElLoading,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import {
  createLatestRequestGuard, formatLogTime, getLog, isValidLogId, listLogs, normalizeTotal, validateLogFilters,
} from '../api/log.js'
import { useSessionStore } from '../stores/session.js'
import { resolvePagePermissionState } from '../utils/menuPermission.js'

const vLoading = ElLoading.directive

const session = useSessionStore()
const hasPermissionSnapshot = ref(session.permissionStatus === 'loaded')
watch([() => session.accessToken, () => session.permissionStatus], ([token, status], previous) => {
  if (!token || (previous && token !== previous[0])) {
    hasPermissionSnapshot.value = status === 'loaded'
  } else if (status === 'loaded') {
    hasPermissionSnapshot.value = true
  } else if (status === 'unavailable') {
    hasPermissionSnapshot.value = false
  }
})
const pagePermissionState = computed(() => resolvePagePermissionState({
  permissionStatus: session.permissionStatus,
  hasSnapshot: hasPermissionSnapshot.value,
}))
const permissionsReady = computed(() => pagePermissionState.value === 'ready')
const permissionsLoading = computed(() => pagePermissionState.value === 'loading')
const permissionsUnavailable = computed(() => pagePermissionState.value === 'unavailable')
const canListLogs = computed(() => permissionsReady.value && session.hasPermission('log:list'))
const canReadLog = computed(() => permissionsReady.value && session.hasPermission('log:read'))

const filters = ref({ userId: '', operation: '', result: '', timeRange: null })
// 已通过校验并生效的筛选条件：翻页/改页大小/重新加载均沿用它，
// 避免改了输入框未点查询时被翻页带走，或把非法 ID 静默忽略。
const appliedFilters = ref({ userId: '', operation: '', result: '', timeRange: null })
const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const lastLoadedAt = ref('')
const loadError = ref('')

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailError = ref('')
let detailRequestSeq = 0
const listRequestGuard = createLatestRequestGuard()
let listAbortController = null
let detailAbortController = null

const detailItems = computed(() => {
  if (!detail.value) return []
  const row = detail.value
  return [
    { label: '日志 ID', value: row.id ?? '—', mono: true },
    { label: '操作人 ID', value: row.userId ?? '—', mono: true },
    { label: '操作人', value: row.userName ?? '—' },
    { label: '操作类型', value: row.operation ?? '—', mono: true },
    { label: '请求路径', value: row.requestUri ?? '—', mono: true },
    { label: '请求方法', value: row.httpMethod ?? '—', mono: true },
    { label: '执行结果', value: row.result === 'SUCCESS' ? '成功' : row.result === 'FAILURE' ? '失败' : '—' },
    { label: '耗时(ms)', value: row.durationMs ?? '—' },
    { label: '记录时间', value: formatLogTime(row.createdAt), mono: true },
  ]
})

function formatOperator(row) {
  if (row.userName) return row.userName
  if (row.userId) return row.userId
  return '—'
}

function onUserIdInput(value) {
  filters.value.userId = String(value ?? '').replace(/\D/g, '').slice(0, 19)
}

async function retryPermissions() {
  await session.refreshPermissions()
}

async function fetchLogs() {
  const requestSeq = listRequestGuard.next()
  listAbortController?.abort()
  if (!canListLogs.value) {
    rows.value = []
    total.value = 0
    loadError.value = ''
    loading.value = false
    return
  }
  const controller = new AbortController()
  listAbortController = controller
  loading.value = true
  loadError.value = ''
  try {
    const data = await listLogs(
      { ...appliedFilters.value, current: current.value, size: size.value },
      { signal: controller.signal },
    )
    // 只允许最新一次请求写回页面状态，避免快速翻页时旧响应覆盖新结果。
    if (!listRequestGuard.isLatest(requestSeq) || controller.signal.aborted) return
    rows.value = Array.isArray(data?.records) ? data.records : []
    total.value = normalizeTotal(data?.total)
    lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (!listRequestGuard.isLatest(requestSeq) || controller.signal.aborted) return
    // 全局 ElMessage 已提示一次错误；页面再给出可重试的显式状态，并清理旧数据避免误导。
    rows.value = []
    total.value = 0
    loadError.value = error?.message ? `日志加载失败：${error.message}` : '日志加载失败，请稍后重试。'
  } finally {
    if (listRequestGuard.isLatest(requestSeq)) loading.value = false
  }
}

function handleSearch() {
  if (!canListLogs.value) return
  const errors = validateLogFilters(filters.value)
  if (errors.length > 0) {
    ElMessage.warning({ message: errors[0], grouping: true })
    return
  }
  appliedFilters.value = {
    userId: filters.value.userId.trim(),
    operation: filters.value.operation.trim(),
    result: filters.value.result,
    timeRange: Array.isArray(filters.value.timeRange) ? [...filters.value.timeRange] : null,
  }
  current.value = 1
  void fetchLogs()
}

function handleReset() {
  if (!canListLogs.value) return
  filters.value = { userId: '', operation: '', result: '', timeRange: null }
  appliedFilters.value = { userId: '', operation: '', result: '', timeRange: null }
  current.value = 1
  void fetchLogs()
}

function handlePageChange(page) {
  current.value = page
  void fetchLogs()
}

function handleSizeChange(newSize) {
  size.value = newSize
  current.value = 1
  void fetchLogs()
}

async function openDetail(row) {
  if (!canReadLog.value) return
  if (!isValidLogId(row?.id)) {
    ElMessage.warning({ message: '日志 ID 无效，无法查看详情。', grouping: true })
    return
  }
  const requestSeq = ++detailRequestSeq
  detailAbortController?.abort()
  const controller = new AbortController()
  detailAbortController = controller
  detail.value = row
  detailError.value = ''
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const data = await getLog(row.id, { signal: controller.signal })
    // 只接受最后一次点击的响应，避免快速切换行时旧响应覆盖新详情。
    if (requestSeq === detailRequestSeq && !controller.signal.aborted && data) detail.value = data
  } catch (error) {
    if (requestSeq === detailRequestSeq && !controller.signal.aborted) {
      detailError.value = error?.message ? `日志详情加载失败：${error.message}` : '日志详情加载失败，请稍后重试。'
    }
  } finally {
    if (requestSeq === detailRequestSeq) detailLoading.value = false
  }
}

watch(canListLogs, (allowed) => {
  if (allowed) {
    void fetchLogs()
  } else {
    listRequestGuard.next()
    listAbortController?.abort()
    rows.value = []
    total.value = 0
    loadError.value = ''
    loading.value = false
  }
}, { immediate: true })

onUnmounted(() => {
  listAbortController?.abort()
  detailAbortController?.abort()
})
</script>

<template>
  <section class="cms-page log-page">
    <header class="cms-page-header">
      <div class="page-heading">
        <p class="eyebrow">OPERATION AUDIT</p>
        <h1>操作日志</h1>
        <p>查询关键操作的执行记录，追溯内容审核、发布与权限变更。</p>
      </div>
    </header>

    <ElAlert
      v-if="permissionsLoading"
      type="info"
      :closable="false"
      show-icon
      title="正在加载当前账号权限…"
    />
    <ElAlert
      v-else-if="permissionsUnavailable"
      type="warning"
      :closable="false"
      show-icon
      title="权限列表加载失败，无法确认日志查询权限；请重试。"
    >
      <template #default>
        <ElButton size="small" @click="retryPermissions">重试加载权限</ElButton>
      </template>
    </ElAlert>
    <ElAlert
      v-else-if="!canListLogs"
      type="warning"
      :closable="false"
      show-icon
      title="没有日志查询权限"
      description="当前账号缺少 log:list 权限，无法查询操作日志；请联系管理员分配权限后再试。"
    />

    <section class="filter-card" aria-label="日志筛选">
      <div class="filter-grid">
        <label class="filter-field">
          <span>操作人 ID</span>
          <ElInput :model-value="filters.userId" placeholder="例如 990101" clearable inputmode="numeric" @input="onUserIdInput" @keyup.enter="handleSearch" />
        </label>
        <label class="filter-field">
          <span>操作类型</span>
          <ElInput v-model="filters.operation" placeholder="例如 article.approve" clearable maxlength="100" @keyup.enter="handleSearch" />
        </label>
        <label class="filter-field">
          <span>执行结果</span>
          <ElSelect v-model="filters.result" placeholder="全部" clearable>
            <ElOption label="全部" value="" />
            <ElOption label="成功" value="SUCCESS" />
            <ElOption label="失败" value="FAILURE" />
          </ElSelect>
        </label>
        <label class="filter-field filter-field-range">
          <span>时间范围</span>
          <ElDatePicker
            v-model="filters.timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :clearable="true"
          />
        </label>
      </div>
      <div class="filter-actions">
        <ElButton type="primary" :loading="loading" :disabled="loading || !canListLogs" @click="handleSearch">查询</ElButton>
        <ElButton :disabled="loading || !canListLogs" @click="handleReset">重置</ElButton>
      </div>
    </section>

    <ElAlert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
    >
      <template #default>
        <ElButton link type="primary" :disabled="loading || !canListLogs" @click="fetchLogs">重新加载</ElButton>
      </template>
    </ElAlert>

    <section class="table-card" aria-label="日志列表">
      <div class="table-meta">
        <span>共 {{ total }} 条记录</span>
        <span v-if="lastLoadedAt">最近查询：{{ lastLoadedAt }}</span>
      </div>
      <div v-loading="loading" class="table-wrap">
        <ElTable :data="rows" row-key="id" border stripe @row-dblclick="openDetail">
          <ElTableColumn label="日志 ID" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
          </ElTableColumn>
          <ElTableColumn label="操作人" min-width="150">
            <template #default="{ row }">{{ formatOperator(row) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作类型" min-width="160">
            <template #default="{ row }"><span class="mono">{{ row.operation }}</span></template>
          </ElTableColumn>
          <ElTableColumn prop="requestUri" label="请求路径" min-width="220" show-overflow-tooltip />
          <ElTableColumn prop="httpMethod" label="方法" width="90" />
          <ElTableColumn label="结果" width="96">
            <template #default="{ row }">
              <ElTag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
                {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="durationMs" label="耗时(ms)" width="104" />
          <ElTableColumn label="时间" min-width="172">
            <template #default="{ row }"><span class="mono">{{ formatLogTime(row.createdAt) }}</span></template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="84" fixed="right">
            <template #default="{ row }">
              <ElButton v-if="canReadLog" link type="primary" @click="openDetail(row)">详情</ElButton>
              <span v-else class="muted-text">无权限</span>
            </template>
          </ElTableColumn>
          <template #empty><ElEmpty description="暂无日志数据" /></template>
        </ElTable>
      </div>
      <div class="pagination-wrap">
        <ElPagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :current-page="current"
          :page-size="size"
          :page-sizes="[10, 20, 50]"
          :disabled="loading"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </section>

    <ElDrawer v-model="drawerVisible" title="日志详情" size="440px" append-to-body>
      <div v-loading="detailLoading" class="detail-body">
        <ElAlert
          v-if="detailError"
          type="error"
          :closable="false"
          show-icon
          :title="detailError"
          class="detail-alert"
        />
        <ElEmpty v-if="!detail" description="暂无详情" />
        <dl v-else class="detail-grid">
          <template v-for="item in detailItems" :key="item.label">
            <dt>{{ item.label }}</dt>
            <dd :class="{ mono: item.mono }">{{ item.value }}</dd>
          </template>
        </dl>
      </div>
    </ElDrawer>
  </section>
</template>

<style scoped>
.filter-card {
  display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 14px 16px;
  border: 1px solid var(--line); border-radius: var(--radius-lg);
  background: var(--surface); padding: 16px 20px; box-shadow: var(--shadow-xs);
}
.filter-grid {
  display: grid; flex: 1 1 640px;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px 14px;
}
.filter-field { display: flex; flex-direction: column; gap: 6px; font-size: 12.5px; font-weight: 500; color: var(--ink-secondary); min-width: 0; }
.filter-field-range { grid-column: span 2; }
.filter-field :deep(.el-select), .filter-field :deep(.el-input), .filter-field :deep(.el-date-editor) { width: 100%; }
.filter-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.filter-actions .el-button + .el-button { margin-left: 0; }
.table-card {
  border: 1px solid var(--line); border-radius: var(--radius-lg);
  background: var(--surface); box-shadow: var(--shadow-xs); overflow: hidden;
}
.table-meta {
  display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;
  padding: 12px 20px; border-bottom: 1px solid #ebf0e6; font-size: 12.5px; color: var(--muted);
}
.table-wrap { min-height: 180px; }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace; font-size: 12.5px; color: var(--ink-secondary); }
.pagination-wrap {
  display: flex; justify-content: flex-end; padding: 14px 20px;
  border-top: 1px solid #ebf0e6; background: var(--surface);
}
.detail-body { min-height: 160px; }
.detail-alert { margin-bottom: 14px; }
.muted-text { color: var(--muted); font-size: 12.5px; }
.detail-grid {
  display: grid; grid-template-columns: 104px 1fr; gap: 12px 14px; margin: 0;
  padding: 16px 18px; border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--surface-elevated);
}
.detail-grid dt { color: var(--muted); font-size: 12.5px; font-weight: 500; }
.detail-grid dd { margin: 0; color: var(--ink); font-size: 13.5px; word-break: break-all; }
@media (max-width: 720px) {
  .filter-field-range { grid-column: span 1; }
}
@media (max-width: 640px) {
  .filter-card { padding: 14px; }
  .pagination-wrap { justify-content: center; padding: 12px 14px; }
}
</style>
