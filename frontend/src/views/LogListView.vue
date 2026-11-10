<script setup>
// TODO 权限：B 交付 @RequirePermission 与后端权限校验后，按 session.permissionCodes 的
// log:list / log:read 控制菜单入口与"详情"按钮是否可见；当前先保持页面可访问，便于联调验收。
import { computed, onMounted, ref } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElLoading,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import 'element-plus/dist/index.css'
import { formatLogTime, getLog, listLogs } from '../api/log.js'

const vLoading = ElLoading.directive

const filters = ref({ userId: '', operation: '', result: '', timeRange: null })
const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const lastLoadedAt = ref('')

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

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
  filters.value.userId = String(value ?? '').replace(/\D/g, '').slice(0, 18)
}

async function fetchLogs() {
  loading.value = true
  try {
    const data = await listLogs({ ...filters.value, current: current.value, size: size.value })
    rows.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total ?? 0)
    lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    // 失败提示由全局 ElMessage 统一处理，页面不重复弹窗。
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  current.value = 1
  fetchLogs()
}

function handleReset() {
  filters.value = { userId: '', operation: '', result: '', timeRange: null }
  current.value = 1
  fetchLogs()
}

function handlePageChange(page) {
  current.value = page
  fetchLogs()
}

function handleSizeChange(newSize) {
  size.value = newSize
  current.value = 1
  fetchLogs()
}

async function openDetail(row) {
  detail.value = row
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const data = await getLog(row.id)
    if (data) detail.value = data
  } catch {
    // 详情失败时保留列表行数据展示，错误提示由全局 ElMessage 处理。
  } finally {
    detailLoading.value = false
  }
}

onMounted(fetchLogs)
</script>

<template>
  <section class="module-page log-page">
    <div class="page-heading">
      <p class="eyebrow">OPERATION AUDIT</p>
      <h1>操作日志</h1>
      <p>查询关键操作的执行记录，追溯内容审核、发布与权限变更。</p>
    </div>

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
        <label class="filter-field">
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
        <ElButton type="primary" :loading="loading" :disabled="loading" @click="handleSearch">查询</ElButton>
        <ElButton :disabled="loading" @click="handleReset">重置</ElButton>
      </div>
    </section>

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
              <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
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
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </section>

    <ElDrawer v-model="drawerVisible" title="日志详情" size="440px" append-to-body>
      <div v-loading="detailLoading" class="detail-body">
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
.log-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.filter-card,
.table-card {
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 14px;
  background: var(--surface, #fff);
  padding: 18px 20px;
}

.filter-card {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 16px;
  justify-content: space-between;
}

.filter-grid {
  display: grid;
  flex: 1 1 640px;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 14px 16px;
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--muted, #6b7280);
}

.filter-field > span {
  font-weight: 600;
  letter-spacing: 0.02em;
}

.filter-actions {
  display: flex;
  gap: 10px;
}

.table-meta {
  display: flex;
  justify-content: space-between;
  padding: 0 2px 10px;
  font-size: 13px;
  color: var(--muted, #6b7280);
}

.table-wrap {
  min-height: 180px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.detail-body {
  min-height: 160px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 108px 1fr;
  gap: 10px 14px;
  margin: 0;
}

.detail-grid dt {
  color: var(--muted, #6b7280);
  font-size: 13px;
}

.detail-grid dd {
  margin: 0;
  color: var(--ink, #1f2937);
  font-size: 14px;
  word-break: break-all;
}

@media (max-width: 640px) {
  .filter-card {
    padding: 14px;
  }

  .pagination-wrap {
    justify-content: center;
  }
}
</style>
