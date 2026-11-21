<script setup>
// 审核工作台（D 模块）：待审核队列 + 通过/驳回 + 审核历史抽屉。
// 权限：进入页面先按 OpenAPI GET /api/auth/permissions 加载当前账号有效权限，
// 并写入会话（C 的文章详情页据此判断 article:read，从而能在审核流程中请求正文）。
// 只有权限加载成功后才显示审核入口，按 article:review-list / article:approve /
// article:reject / review:list 严格门控；加载失败时隐藏操作并提供重试，不按身份猜测。
// 正文：标题与审核对话框提供到 /admin/articles/:id 的正文入口（C 的文章详情页，article:read）。
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ElAlert, ElButton, ElDialog, ElDrawer, ElEmpty, ElInput, ElLoading, ElMessage,
  ElPagination, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
// 骨架项目未在 main.js 全局注册 Element Plus，本页按需引入组件并加载样式。
import 'element-plus/dist/index.css'
import {
  approveArticle, buildPendingQuery, buildReviewQuery, fetchMyPermissions,
  formatLocalDateTime, formatReviewTime, isValidArticleId, listPendingReviews,
  listReviewHistory, rejectArticle, validatePendingFilters,
} from '../api/review.js'
import { useSessionStore } from '../stores/session.js'
import { useRouter } from 'vue-router'

const session = useSessionStore()
const router = useRouter()
// idle / loading / loaded / unavailable：区分"权限加载中"与"已加载但为空"。
// 只有 loaded 才显示操作并按权限码严格门控（空数组=无任何权限，隐藏全部操作）；
// 加载中不显示操作；加载失败显示重试入口，不按登录身份猜测权限。
const permissionStatus = ref('idle')
const permissionsKnown = computed(() => permissionStatus.value === 'loaded')
const canListReviews = computed(() => permissionsKnown.value && session.hasPermission('article:review-list'))
const canApprove = computed(() => permissionsKnown.value && session.hasPermission('article:approve'))
const canReject = computed(() => permissionsKnown.value && session.hasPermission('article:reject'))
const canReadHistory = computed(() => permissionsKnown.value && session.hasPermission('review:list'))
// 正文入口按 article:read 控制：自定义角色可能只有待审列表权限，没有 article:read。
const canReadArticle = computed(() => permissionsKnown.value && session.hasPermission('article:read'))
const vLoading = ElLoading.directive

async function loadPermissions() {
  // 每次进入页面都向后端取一次有效权限：管理员可能已增删该账号的权限，
  // 会话里的旧权限码不能作为"已加载"依据（否则新增权限不出现、撤销权限仍显示按钮）。
  permissionStatus.value = 'loading'
  try {
    const codes = await fetchMyPermissions()
    session.setPermissions(Array.isArray(codes) ? codes : [])
    permissionStatus.value = 'loaded'
  } catch {
    permissionStatus.value = 'unavailable'
  }
}

// 权限加载失败后重试：成功后立即按已通过校验的筛选条件刷新待审核列表。
async function retryPermissions() {
  await loadPermissions()
  if (permissionsKnown.value) void fetchPending()
}

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
// 已通过校验的查询条件：翻页/修改每页数量/重新加载都沿用它，
// 避免用户改了非法 ID 但没点查询时，分页把非法值丢弃、扩大查询范围。
const appliedFilters = reactive({ keyword: '', categoryId: '', authorId: '' })
const historyForm = reactive({ articleId: '' })
const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)
const loading = ref(false)
const loadError = ref('')
const lastLoadedAt = ref('')
// 列表请求序号：快速翻页时只允许最新一次请求回写页面状态。
let pendingRequestSeq = 0
// 审核历史请求序号：切换文章或快速翻页时只允许最新一次请求回写抽屉状态。
let historyRequestSeq = 0

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

// 审核结果标签：只认 APPROVED/REJECTED，未知值按 info 展示原值，避免误判为"驳回"。
function reviewResultMeta(result) {
  if (result === 'APPROVED') return { type: 'success', label: '通过' }
  if (result === 'REJECTED') return { type: 'danger', label: '驳回' }
  return { type: 'info', label: result || '未知' }
}

async function fetchPending() {
  const requestSeq = ++pendingRequestSeq
  if (permissionStatus.value === 'loading' || permissionStatus.value === 'idle') {
    rows.value = []
    total.value = 0
    loading.value = false
    return
  }
  if (!permissionsKnown.value) {
    rows.value = []
    total.value = 0
    loading.value = false
    loadError.value = '权限列表加载失败，无法确认审核权限；请点上方「重试加载权限」。'
    return
  }
  if (!canListReviews.value) {
    rows.value = []
    total.value = 0
    loading.value = false
    loadError.value = '当前账号缺少 article:review-list 权限，无法查看待审核队列。'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const page = await listPendingReviews(buildPendingQuery({
      keyword: appliedFilters.keyword, categoryId: appliedFilters.categoryId,
      authorId: appliedFilters.authorId, current: current.value, size: size.value,
    }))
    if (requestSeq !== pendingRequestSeq) return
    rows.value = Array.isArray(page?.records) ? page.records : []
    total.value = Number.isFinite(Number(page?.total)) ? Number(page.total) : 0
    current.value = Number(page?.current) > 0 ? Number(page.current) : current.value
    size.value = Number(page?.size) > 0 ? Number(page.size) : size.value
    lastLoadedAt.value = formatLocalDateTime(new Date())
  } catch {
    if (requestSeq !== pendingRequestSeq) return
    // 错误提示由全局 api 客户端统一弹出；这里只复位数据并给出可重试提示。
    rows.value = []
    total.value = 0
    loadError.value = '待审核列表加载失败，请稍后重试。'
  } finally {
    if (requestSeq === pendingRequestSeq) loading.value = false
  }
}

function handleSearch() {
  // 非法筛选 ID 不静默丢弃：提示具体字段并保留当前结果。
  const errors = validatePendingFilters({ categoryId: filters.categoryId, authorId: filters.authorId })
  if (errors.length > 0) {
    ElMessage.warning({ message: errors[0], grouping: true })
    return
  }
  // 只有通过校验的输入才进入"已应用条件"，翻页/改页大小沿用它。
  appliedFilters.keyword = filters.keyword.trim()
  appliedFilters.categoryId = filters.categoryId.trim()
  appliedFilters.authorId = filters.authorId.trim()
  current.value = 1
  void fetchPending()
}

// 按文章 ID 查历史：审核完成后（行已从待审队列移除）仍可核对审核记录。
function openHistoryById() {
  if (!canReadHistory.value) return
  const rawId = historyForm.articleId.trim()
  if (!isValidArticleId(rawId)) {
    ElMessage.warning({ message: '文章 ID 无效：请输入 1–19 位正整数，且不超过 Long 上限', grouping: true })
    return
  }
  openHistory({ id: rawId, title: '' })
}

// 正文入口：跳转 C 的文章详情页（路由 article-detail，权限 article:read）。
function openArticle(row) {
  if (!row?.id) return
  router.push({ name: 'article-detail', params: { id: row.id } })
}

function handleReset() {
  filters.keyword = ''
  filters.categoryId = ''
  filters.authorId = ''
  appliedFilters.keyword = ''
  appliedFilters.categoryId = ''
  appliedFilters.authorId = ''
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
  const requestSeq = ++historyRequestSeq
  historyDrawer.loading = true
  historyDrawer.error = ''
  try {
    const page = await listReviewHistory(historyDrawer.articleId, buildReviewQuery({
      current: historyDrawer.current, size: historyDrawer.size,
    }))
    if (requestSeq !== historyRequestSeq) return
    historyDrawer.rows = Array.isArray(page?.records) ? page.records : []
    historyDrawer.total = Number.isFinite(Number(page?.total)) ? Number(page.total) : 0
  } catch {
    if (requestSeq !== historyRequestSeq) return
    historyDrawer.rows = []
    historyDrawer.total = 0
    historyDrawer.error = '审核历史加载失败，请稍后重试。'
  } finally {
    if (requestSeq === historyRequestSeq) historyDrawer.loading = false
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

onMounted(async () => {
  await loadPermissions()
  void fetchPending()
})
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
      <ElInput v-model="filters.categoryId" class="filter-input" clearable maxlength="64" placeholder="分类 ID（提交时校验）" />
      <ElInput v-model="filters.authorId" class="filter-input" clearable maxlength="64" placeholder="作者 ID（提交时校验）" />
      <ElButton type="primary" @click="handleSearch">查询</ElButton>
      <ElButton @click="handleReset">重置</ElButton>
      <span v-if="lastLoadedAt" class="filter-meta">更新于 {{ lastLoadedAt }}</span>
    </div>

    <div v-if="canReadHistory" class="review-history-lookup">
      <span class="filter-meta">审核完成后仍可核对记录：</span>
      <ElInput v-model="historyForm.articleId" class="filter-input" clearable maxlength="64" placeholder="文章 ID（提交时校验）" />
      <ElButton @click="openHistoryById">查看该文章审核历史</ElButton>
    </div>

    <ElAlert v-if="permissionStatus === 'loading'" class="review-alert" type="info" :closable="false" show-icon
      title="正在加载当前账号权限…" />
    <ElAlert v-else-if="permissionStatus === 'unavailable'" class="review-alert" type="warning" :closable="false" show-icon
      title="权限列表加载失败，审核入口已隐藏；请重试加载权限。">
      <ElButton size="small" @click="retryPermissions">重试加载权限</ElButton>
    </ElAlert>
    <ElAlert v-if="permissionsKnown && !canListReviews" class="review-alert" type="warning" :closable="false" show-icon
      title="当前账号缺少 article:review-list 权限，无法查看待审核队列。" />
    <ElAlert v-if="loadError" class="review-alert" :title="loadError" type="error" :closable="false" show-icon>
      <ElButton size="small" @click="fetchPending">重新加载</ElButton>
    </ElAlert>

    <div v-loading="loading" class="review-table-shell">
      <ElTable :data="rows" border stripe empty-text="暂无待审核内容">
        <ElTableColumn prop="id" label="文章 ID" width="120" />
        <ElTableColumn label="标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <RouterLink v-if="canReadArticle" class="article-link" :to="{ name: 'article-detail', params: { id: row.id } }">{{ row.title }}</RouterLink>
            <span v-else>{{ row.title }}</span>
          </template>
        </ElTableColumn>
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
            <ElButton v-if="canApprove" link type="success" @click="openDecision(row, 'approve')">通过</ElButton>
            <ElButton v-if="canReject" link type="danger" @click="openDecision(row, 'reject')">驳回</ElButton>
            <ElButton v-if="canReadHistory" link type="primary" @click="openHistory(row)">历史</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <div class="review-pagination">
        <ElPagination
          :current-page="current" :page-size="size" :page-sizes="[10, 20, 50]" :total="total"
          :disabled="loading"
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
      <p class="dialog-tip">
        <ElButton v-if="canReadArticle" link type="primary" @click="openArticle(decisionDialog.row)">在新页面查看正文</ElButton>
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
              <ElTag :type="reviewResultMeta(row.result).type" effect="light" round>
                {{ reviewResultMeta(row.result).label }}
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
.review-history-lookup { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.article-link { color: var(--el-color-primary, #409eff); text-decoration: none; }
.article-link:hover { text-decoration: underline; }
</style>
