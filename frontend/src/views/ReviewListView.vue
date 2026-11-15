<script setup>
// 审核工作台（D 模块）：待审核队列 + 通过/驳回 + 审核历史抽屉。
// TODO 权限软门控：B 交付 @RequirePermission 后，按 session.permissionCodes 的
// article:review-list / article:approve / article:reject 控制入口与按钮。
// 说明：本页只使用契约中的四个接口；后端接口合入 dev 后方可联调。
import { onMounted, reactive, ref } from 'vue'
import {
  ElAlert, ElButton, ElDialog, ElDrawer, ElEmpty, ElInput, ElLoading, ElMessage,
  ElPagination, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
// 骨架项目未在 main.js 全局注册 Element Plus，本页按需引入组件并加载样式。
import 'element-plus/dist/index.css'
import {
  approveArticle, buildPendingQuery, buildReviewQuery, formatLocalDateTime,
  formatReviewTime, listPendingReviews, listReviewHistory, rejectArticle,
} from '../api/review.js'

const vLoading = ElLoading.directive

const STATUS_LABELS = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  PUBLISHED: '已发布',
  OFFLINE: '已下架',
}
const STATUS_TAG_TYPES = {
  DRAFT: 'info',
  PENDING_REVIEW: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  PUBLISHED: 'success',
  OFFLINE: 'info',
}

const filters = reactive({ keyword: '', categoryId: '', authorId: '' })
const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const loadError = ref('')
const lastLoadedAt = ref('')

const decisionDialog = reactive({
  visible: false, mode: 'approve', row: null, comment: '', submitting: false,
})
const historyDrawer = reactive({
  visible: false, articleId: '', articleTitle: '', rows: [], total: 0,
  current: 1, size: 10, loading: false, error: '',
})

function statusLabel(status) {
  return STATUS_LABELS[status] ?? status ?? '—'
}

function statusTagType(status) {
  return STATUS_TAG_TYPES[status] ?? 'info'
}

function onNumericInput(field) {
  filters[field] = filters[field].replace(/\D/g, '').slice(0, 19)
}

async function fetchPending() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await listPendingReviews(buildPendingQuery({
      keyword: filters.keyword, categoryId: filters.categoryId,
      authorId: filters.authorId, current: current.value, size: size.value,
    }))
    rows.value = Array.isArray(page?.records) ? page.records : []
    total.value = Number.isFinite(Number(page?.total)) ? Number(page.total) : 0
    current.value = Number(page?.current) > 0 ? Number(page.current) : current.value
    size.value = Number(page?.size) > 0 ? Number(page.size) : size.value
    lastLoadedAt.value = formatLocalDateTime(new Date())
  } catch {
    // 错误提示由全局 api 客户端统一弹出；这里只复位数据并给出可重试提示。
    rows.value = []
    total.value = 0
    loadError.value = '待审核列表加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  current.value = 1
  void fetchPending()
}

function handleReset() {
  filters.keyword = ''
  filters.categoryId = ''
  filters.authorId = ''
  current.value = 1
  void fetchPending()
}

function handleSizeChange(nextSize) {
  size.value = nextSize
  current.value = 1
  void fetchPending()
}

function handleCurrentChange(nextPage) {
  current.value = nextPage
  void fetchPending()
}

function openDecision(row, mode) {
  decisionDialog.visible = true
  decisionDialog.mode = mode
  decisionDialog.row = row
  decisionDialog.comment = ''
  decisionDialog.submitting = false
}

function closeDecision() {
  if (decisionDialog.submitting) return
  decisionDialog.visible = false
  decisionDialog.row = null
  decisionDialog.comment = ''
}

async function submitDecision() {
  const row = decisionDialog.row
  if (!row) return
  const comment = decisionDialog.comment.trim()
  if (decisionDialog.mode === 'reject' && !comment) {
    ElMessage.warning({ message: '驳回意见不能为空', grouping: true })
    return
  }
  if (comment.length > 1000) {
    ElMessage.warning({ message: '审核意见不能超过 1000 字', grouping: true })
    return
  }
  decisionDialog.submitting = true
  try {
    if (decisionDialog.mode === 'approve') {
      await approveArticle(row.id, { version: row.version, comment })
      ElMessage.success({ message: '审核通过，文章状态已更新', grouping: true })
    } else {
      await rejectArticle(row.id, { version: row.version, comment })
      ElMessage.success({ message: '已驳回并记录审核意见', grouping: true })
    }
    decisionDialog.visible = false
    decisionDialog.row = null
    decisionDialog.comment = ''
    await fetchPending()
  } catch (error) {
    // 409 表示版本/状态已变化：全局提示已给出，这里刷新列表让用户基于最新数据重试。
    if (error?.status === 409 || error?.code === 409) {
      decisionDialog.visible = false
      decisionDialog.row = null
      await fetchPending()
    }
  } finally {
    decisionDialog.submitting = false
  }
}

function openHistory(row) {
  historyDrawer.visible = true
  historyDrawer.articleId = row.id
  historyDrawer.articleTitle = row.title
  historyDrawer.rows = []
  historyDrawer.total = 0
  historyDrawer.current = 1
  historyDrawer.size = 10
  historyDrawer.error = ''
  void fetchHistory()
}

async function fetchHistory() {
  historyDrawer.loading = true
  historyDrawer.error = ''
  try {
    const page = await listReviewHistory(historyDrawer.articleId, buildReviewQuery({
      current: historyDrawer.current, size: historyDrawer.size,
    }))
    historyDrawer.rows = Array.isArray(page?.records) ? page.records : []
    historyDrawer.total = Number.isFinite(Number(page?.total)) ? Number(page.total) : 0
  } catch {
    historyDrawer.rows = []
    historyDrawer.total = 0
    historyDrawer.error = '审核历史加载失败，请稍后重试。'
  } finally {
    historyDrawer.loading = false
  }
}

function handleHistorySizeChange(nextSize) {
  historyDrawer.size = nextSize
  historyDrawer.current = 1
  void fetchHistory()
}

function handleHistoryCurrentChange(nextPage) {
  historyDrawer.current = nextPage
  void fetchHistory()
}

onMounted(() => { void fetchPending() })
</script>

<template>
  <section class="module-page review-page">
    <header class="page-heading">
      <p class="eyebrow">REVIEW &amp; PUBLISH</p>
      <h1>审核工作台</h1>
      <p>待审核队列：审核通过或驳回员工提交的内容，并查看每一次审核历史。</p>
    </header>

    <div class="review-filter">
      <ElInput v-model="filters.keyword" class="filter-input" clearable maxlength="255" placeholder="标题关键词" />
      <ElInput :model-value="filters.categoryId" class="filter-input" clearable maxlength="19" placeholder="分类 ID" @update:model-value="filters.categoryId = $event; onNumericInput('categoryId')" />
      <ElInput :model-value="filters.authorId" class="filter-input" clearable maxlength="19" placeholder="作者 ID" @update:model-value="filters.authorId = $event; onNumericInput('authorId')" />
      <ElButton type="primary" @click="handleSearch">查询</ElButton>
      <ElButton @click="handleReset">重置</ElButton>
      <span v-if="lastLoadedAt" class="filter-meta">更新于 {{ lastLoadedAt }}</span>
    </div>

    <ElAlert v-if="loadError" class="review-alert" :title="loadError" type="error" :closable="false" show-icon>
      <ElButton size="small" @click="fetchPending">重新加载</ElButton>
    </ElAlert>

    <div v-loading="loading" class="review-table-shell">
      <ElTable :data="rows" border stripe empty-text="暂无待审核内容">
        <ElTableColumn prop="id" label="文章 ID" width="120" />
        <ElTableColumn prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <ElTableColumn prop="categoryName" label="分类" width="140" show-overflow-tooltip />
        <ElTableColumn prop="authorName" label="作者" width="120" />
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)" effect="light" round>{{ statusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="version" label="版本" width="80" />
        <ElTableColumn label="更新时间" width="170">
          <template #default="{ row }">{{ formatLocalDateTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton link type="success" @click="openDecision(row, 'approve')">通过</ElButton>
            <ElButton link type="danger" @click="openDecision(row, 'reject')">驳回</ElButton>
            <ElButton link type="primary" @click="openHistory(row)">历史</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <div class="review-pagination">
        <ElPagination
          :current-page="current" :page-size="size" :page-sizes="[10, 20, 50]" :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange" @current-change="handleCurrentChange"
        />
      </div>
    </div>

    <ElDialog
      :model-value="decisionDialog.visible" :title="decisionDialog.mode === 'approve' ? '审核通过' : '驳回文章'"
      width="480px" append-to-body @close="closeDecision"
    >
      <p class="dialog-tip">
        文章：<strong>{{ decisionDialog.row?.title || '—' }}</strong>
        <span class="dialog-version">当前版本 {{ decisionDialog.row?.version ?? '—' }}</span>
      </p>
      <ElInput
        v-model="decisionDialog.comment" type="textarea" :rows="4" maxlength="1000" show-word-limit
        :placeholder="decisionDialog.mode === 'reject' ? '驳回意见（必填）' : '审核意见（可选）'"
      />
      <template #footer>
        <ElButton :disabled="decisionDialog.submitting" @click="closeDecision">取消</ElButton>
        <ElButton
          :type="decisionDialog.mode === 'approve' ? 'success' : 'danger'" :loading="decisionDialog.submitting"
          @click="submitDecision"
        >{{ decisionDialog.mode === 'approve' ? '确认通过' : '确认驳回' }}</ElButton>
      </template>
    </ElDialog>

    <ElDrawer v-model="historyDrawer.visible" :title="`审核历史 · ${historyDrawer.articleTitle || historyDrawer.articleId}`" size="520px">
      <ElAlert v-if="historyDrawer.error" :title="historyDrawer.error" type="error" :closable="false" show-icon />
      <div v-loading="historyDrawer.loading">
        <ElTable :data="historyDrawer.rows" border empty-text="暂无审核历史">
          <ElTableColumn prop="id" label="审核 ID" width="110" />
          <ElTableColumn prop="reviewerName" label="审核人" width="110" />
          <ElTableColumn label="结果" width="100">
            <template #default="{ row }">
              <ElTag :type="row.result === 'APPROVED' ? 'success' : 'danger'" effect="light" round>
                {{ row.result === 'APPROVED' ? '通过' : '驳回' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="comment" label="意见" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.comment || '—' }}</template>
          </ElTableColumn>
          <ElTableColumn label="审核时间" width="170">
            <template #default="{ row }">{{ formatReviewTime(row.reviewedAt) }}</template>
          </ElTableColumn>
        </ElTable>
        <div class="review-pagination">
          <ElPagination
            :current-page="historyDrawer.current" :page-size="historyDrawer.size" :page-sizes="[10, 20, 50]"
            :total="historyDrawer.total" layout="total, sizes, prev, pager, next"
            @size-change="handleHistorySizeChange" @current-change="handleHistoryCurrentChange"
          />
        </div>
      </div>
    </ElDrawer>
  </section>
</template>

<style scoped>
.review-page { display: grid; gap: 18px; }
.review-filter { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.filter-input { width: 180px; }
.filter-meta { color: var(--muted, #6b7280); font-size: 12px; }
.review-alert { margin-top: 4px; }
.review-table-shell { border: 1px solid var(--line, #e5e7eb); border-radius: 12px; padding: 12px; background: var(--surface, #fff); }
.review-pagination { display: flex; justify-content: flex-end; margin-top: 12px; }
.dialog-tip { margin: 0 0 12px; color: var(--ink, #111827); }
.dialog-version { margin-left: 8px; color: var(--muted, #6b7280); font-size: 12px; }
</style>
