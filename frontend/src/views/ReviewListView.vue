<script setup>
// 审核工作台（D 模块）：待审核队列 + 通过/驳回 + 审核历史抽屉。
// 权限：进入页面先按 OpenAPI GET /api/auth/permissions 加载当前账号有效权限，
// 并写入会话（C 的文章详情页据此判断 article:read，从而能在审核流程中请求正文）。
// 只有权限加载成功后才显示审核入口，按 article:review-list / article:approve /
// article:reject / review:list 严格门控；加载失败时隐藏操作并提供重试，不按身份猜测。
// 正文：标题与审核对话框提供到 /admin/articles/:id 的正文入口（C 的文章详情页，article:read）。
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElDialog, ElDrawer, ElEmpty, ElInput, ElLoading, ElMessage,
  ElPagination, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag,
} from 'element-plus'
import {
  approveArticle, buildPendingQuery, buildReviewQuery, canChangePublication, fetchMyPermissions, getArticleDetail,
  formatLocalDateTime, formatReviewTime, isValidArticleId, listPendingReviews,
  listReviewHistory, rejectArticle, validatePendingFilters,
} from '../api/review.js'
import { useSessionStore } from '../stores/session.js'
import { countCodePoints } from '../utils/text.js'
import PublishPanel from './review/PublishPanel.vue'

const session = useSessionStore()
// idle / loading / loaded / unavailable：区分"权限加载中"与"已加载但为空"。
// 只有 loaded 才显示操作并按权限码严格门控（空数组=无任何权限，隐藏全部操作）；
// 加载中不显示操作；加载失败显示重试入口，不按登录身份猜测权限。
const permissionStatus = ref('idle')
const permissionsKnown = computed(() => permissionStatus.value === 'loaded')
const canListReviews = computed(() => permissionsKnown.value && session.hasPermission('article:review-list'))
const canApprove = computed(() => permissionsKnown.value && session.hasPermission('article:approve'))
const canReject = computed(() => permissionsKnown.value && session.hasPermission('article:reject'))
const canReadHistory = computed(() => permissionsKnown.value && session.hasPermission('review:list'))
const showReview = computed(() =>
  canListReviews.value || canApprove.value || canReject.value || canReadHistory.value)
const showPublish = computed(() => permissionsKnown.value && [false, true].some((batch) =>
  canChangePublication('publish', 'APPROVED', batch, session.hasPermission)
  || canChangePublication('offline', 'PUBLISHED', batch, session.hasPermission)))
const activeTab = ref('review')
watch([showReview, showPublish], ([review, publish]) => {
  if (activeTab.value === 'review' && !review && publish) activeTab.value = 'publish'
  if (activeTab.value === 'publish' && !publish && review) activeTab.value = 'review'
}, { immediate: true })

// 外部（侧栏同页点击菜单或侧栏重试）触发 session.refreshPermissions() 完成后，
// 同步本页权限态；仅在状态恢复或 article:review-list 权限发生变化时重查/清空待审队列。
watch(() => session.permissionStatus, (status) => {
  if (!initialized || loadingPermissionsLocally || status !== 'loaded') return
  const wasReady = permissionStatus.value === 'loaded'
  permissionStatus.value = 'loaded'
  const nextCanList = canListReviews.value
  if (!wasReady || nextCanList !== syncedCanListReviews) {
    syncedCanListReviews = nextCanList
    if (nextCanList) {
      void fetchPending()
    } else {
      pendingRequestSeq += 1
      pendingAbortController?.abort()
      rows.value = []
      total.value = 0
      loadError.value = ''
      loading.value = false
    }
  }
})
// 正文入口按 article:read 控制：自定义角色可能只有待审列表权限，没有 article:read。
const canReadArticle = computed(() => permissionsKnown.value && session.hasPermission('article:read'))
const vLoading = ElLoading.directive

// 权限请求保护：旧页面实例/被替代的请求/已切换的登录会话都不能再写会话权限，
// 否则快速离开再进入时，旧实例的迟到响应会把新页面的权限覆盖回旧值。
let permissionRequestSeq = 0
let permissionAbortController = null
let disposed = false
let initialized = false
let loadingPermissionsLocally = false
let syncedCanListReviews = false

async function loadPermissions() {
  // 每次进入页面都向后端取一次有效权限：管理员可能已增删该账号的权限，
  // 会话里的旧权限码不能作为"已加载"依据（否则新增权限不出现、撤销权限仍显示按钮）。
  const requestSeq = ++permissionRequestSeq
  const tokenAtStart = session.accessToken
  permissionAbortController?.abort()
  permissionAbortController = new AbortController()
  permissionStatus.value = 'loading'
  try {
    loadingPermissionsLocally = true
    const codes = await fetchMyPermissions({ signal: permissionAbortController.signal })
    if (disposed || requestSeq !== permissionRequestSeq || tokenAtStart !== session.accessToken) return
    session.setPermissions(Array.isArray(codes) ? codes : [])
    permissionStatus.value = 'loaded'
    syncedCanListReviews = canListReviews.value
  } catch {
    // 请求被取消（页面卸载/被新请求替代）不算加载失败，直接忽略。
    if (disposed || requestSeq !== permissionRequestSeq || tokenAtStart !== session.accessToken) return
    if (permissionAbortController?.signal.aborted) return
    permissionStatus.value = 'unavailable'
  } finally {
    loadingPermissionsLocally = false
  }
}

onUnmounted(() => {
  disposed = true
  // 卸载时取消在途请求：账号 A 的 401 迟到响应不能再清掉账号 B 刚建立的会话。
  permissionAbortController?.abort()
  pendingAbortController?.abort()
  historyAbortController?.abort()
  articlePreviewAbortController?.abort()
  decisionAbortController?.abort()
})

// 权限加载失败后重试：成功后立即按已通过校验的筛选条件刷新待审核列表。
async function retryPermissions() {
  await loadPermissions()
  if (canListReviews.value) void fetchPending()
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
// 列表请求取消器：翻页/重查/卸载时取消在途请求，避免旧会话的 401 影响新会话。
let pendingAbortController = null
// 审核历史请求序号：切换文章或快速翻页时只允许最新一次请求回写抽屉状态。
let historyRequestSeq = 0
let historyAbortController = null

const decisionDialog = reactive({
  visible: false, mode: 'approve', row: null, comment: '', submitting: false, instanceKey: 0,
})
// 正文预览：在审核弹窗内加载正文，提交中的意见与弹窗状态不会因跳转丢失。
const articlePreview = reactive({ visible: false, loading: false, error: '', data: null })
let articlePreviewAbortController = null
// 审核提交请求：离开工作台时取消，旧会话的迟到 401 不清理新会话。
let decisionAbortController = null
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
  pendingAbortController?.abort()
  pendingAbortController = new AbortController()
  try {
    const page = await listPendingReviews(buildPendingQuery({
      keyword: appliedFilters.keyword, categoryId: appliedFilters.categoryId,
      authorId: appliedFilters.authorId, current: current.value, size: size.value,
    }), { signal: pendingAbortController.signal })
    if (requestSeq !== pendingRequestSeq) return
    rows.value = Array.isArray(page?.records) ? page.records : []
    total.value = Number.isFinite(Number(page?.total)) ? Number(page.total) : 0
    current.value = Number(page?.current) > 0 ? Number(page.current) : current.value
    size.value = Number(page?.size) > 0 ? Number(page.size) : size.value
    const lastPage = Math.max(1, Math.ceil(total.value / size.value))
    if (current.value > lastPage) {
      current.value = lastPage
      void fetchPending()
      return
    }
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

// 正文入口：在审核弹窗内加载正文（不跳转），避免已填写的审核意见丢失。
async function toggleArticlePreview() {
  if (!canReadArticle.value) return
  if (articlePreview.visible) {
    articlePreviewAbortController?.abort()
    articlePreview.visible = false
    articlePreview.data = null
    articlePreview.error = ''
    return
  }
  const articleId = decisionDialog.row?.id
  if (!articleId) return
  articlePreview.visible = true
  articlePreview.loading = true
  articlePreview.error = ''
  articlePreview.data = null
  articlePreviewAbortController?.abort()
  const controller = new AbortController()
  articlePreviewAbortController = controller
  try {
    const detail = await getArticleDetail(articleId, { signal: controller.signal })
    if (controller.signal.aborted) return
    articlePreview.data = detail ?? null
  } catch {
    if (controller.signal.aborted) return
    articlePreview.error = '正文加载失败，请稍后重试。'
  } finally {
    if (!controller.signal.aborted) articlePreview.loading = false
  }
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
  // instanceKey 递增让弹窗在"父组件 visible 仍为 true、但 Element 内部已关闭"的
  // 异常状态下也能重新挂载显示，避免提交失败后再次点击无反应。
  decisionDialog.instanceKey += 1
  decisionDialog.visible = true
  decisionDialog.mode = mode
  decisionDialog.row = row
  decisionDialog.comment = ''
  decisionDialog.submitting = false
  // 每次打开新文章重新加载正文，不复用上一篇文章的内容。
  articlePreviewAbortController?.abort()
  articlePreview.visible = false
  articlePreview.data = null
  articlePreview.error = ''
  articlePreview.loading = false
}

// 提交中禁止 Esc / 遮罩 / 关闭按钮关闭弹窗：Element Plus 若先关闭而 @close 因
// submitting 提前返回，父组件 visible 会停在 true，接口失败后无法再次打开。
function handleDecisionBeforeClose(done) {
  if (decisionDialog.submitting) return
  done()
}

function closeDecision() {
  if (decisionDialog.submitting) return
  articlePreviewAbortController?.abort()
  articlePreview.visible = false
  articlePreview.data = null
  articlePreview.error = ''
  articlePreview.loading = false
  decisionDialog.visible = false
  decisionDialog.row = null
  decisionDialog.comment = ''
}

async function submitDecision() {
  if (decisionDialog.submitting) return
  const row = decisionDialog.row
  if (!row) return
  const comment = decisionDialog.comment.trim()
  if (decisionDialog.mode === 'reject' && !comment) {
    ElMessage.warning({ message: '驳回意见不能为空', grouping: true })
    return
  }
  if (countCodePoints(comment) > 1000) {
    ElMessage.warning({ message: '审核意见不能超过 1000 字', grouping: true })
    return
  }
  decisionDialog.submitting = true
  decisionAbortController?.abort()
  decisionAbortController = new AbortController()
  const requestConfig = { signal: decisionAbortController.signal }
  try {
    if (decisionDialog.mode === 'approve') {
      await approveArticle(row.id, { version: row.version, comment }, requestConfig)
      ElMessage.success({ message: '审核通过，文章状态已更新', grouping: true })
    } else {
      await rejectArticle(row.id, { version: row.version, comment }, requestConfig)
      ElMessage.success({ message: '已驳回并记录审核意见', grouping: true })
    }
    if (requestConfig.signal.aborted) return
    decisionDialog.visible = false
    decisionDialog.row = null
    decisionDialog.comment = ''
    await fetchPending()
  } catch (error) {
    // 已取消（离开工作台）：不提示、不再改写页面状态。
    if (requestConfig.signal.aborted) return
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
  historyAbortController?.abort()
  historyAbortController = new AbortController()
  try {
    const page = await listReviewHistory(historyDrawer.articleId, buildReviewQuery({
      current: historyDrawer.current, size: historyDrawer.size,
    }), { signal: historyAbortController.signal })
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
  if (session.permissionStatus === 'loaded') {
    permissionStatus.value = 'loaded'
  } else {
    await loadPermissions()
  }
  if (disposed) return
  syncedCanListReviews = canListReviews.value
  initialized = true
  if (canListReviews.value) void fetchPending()
})
</script>

<template>
  <section class="cms-page review-page">
    <header class="cms-page-header">
      <div class="page-heading">
        <p class="eyebrow">REVIEW &amp; PUBLISH</p>
        <h1>审核与发布</h1>
      </div>
    </header>

    <ElAlert v-if="permissionStatus === 'idle' || permissionStatus === 'loading'"
      type="info" :closable="false" show-icon title="正在加载当前账号权限…" />
    <ElAlert v-else-if="permissionStatus === 'unavailable'" type="warning" :closable="false" show-icon
      title="权限列表加载失败，请重试。">
      <ElButton size="small" @click="retryPermissions">重试加载权限</ElButton>
    </ElAlert>
    <ElAlert v-else-if="!showReview && !showPublish" type="warning" :closable="false" show-icon
      title="当前账号没有审核或发布权限。" />

    <ElTabs v-if="showReview || showPublish" v-model="activeTab">
      <ElTabPane v-if="showReview" label="待审核" name="review">
        <div class="cms-page">
          <section class="cms-filter-bar" aria-label="待审核筛选">
            <div class="cms-filter-fields">
              <label class="cms-filter-field">
                <span>标题关键词</span>
                <ElInput v-model="filters.keyword" clearable maxlength="255" placeholder="输入文章标题关键词" @keyup.enter="handleSearch" />
              </label>
              <label class="cms-filter-field">
                <span>分类 ID</span>
                <ElInput v-model="filters.categoryId" clearable maxlength="64" placeholder="分类 ID（提交时校验）" @keyup.enter="handleSearch" />
              </label>
              <label class="cms-filter-field">
                <span>作者 ID</span>
                <ElInput v-model="filters.authorId" clearable maxlength="64" placeholder="作者 ID（提交时校验）" @keyup.enter="handleSearch" />
              </label>
            </div>
            <div class="cms-filter-actions">
              <ElButton type="primary" :loading="loading" :disabled="!canListReviews" @click="handleSearch">查询</ElButton>
              <ElButton :disabled="loading || !canListReviews" @click="handleReset">重置</ElButton>
            </div>
          </section>

          <section v-if="canReadHistory" class="review-history-bar" aria-label="按文章 ID 核对审核历史">
            <span class="history-bar-label">按文章 ID 核对历史记录</span>
            <ElInput v-model="historyForm.articleId" class="history-id-input" clearable maxlength="64" placeholder="输入文章 ID（提交时校验）" @keyup.enter="openHistoryById" />
            <ElButton @click="openHistoryById">查看审核历史</ElButton>
          </section>

          <ElAlert v-if="permissionsKnown && !canListReviews" type="warning" :closable="false" show-icon
            title="当前账号缺少 article:review-list 权限，无法查看待审核队列。" />
          <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
            <ElButton size="small" @click="fetchPending">重新加载</ElButton>
          </ElAlert>

          <section v-loading="loading" class="cms-surface-card" aria-label="待审核队列">
            <div class="cms-card-meta">
              <span>共 {{ total }} 篇待审核文章</span>
              <span v-if="lastLoadedAt">最近查询：{{ lastLoadedAt }}</span>
            </div>
            <ElTable :data="rows" stripe empty-text="暂无待审核内容">
              <ElTableColumn label="文章 ID" width="120">
                <template #default="{ row }"><span class="cms-mono">{{ row.id }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="标题" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">
                  <RouterLink v-if="canReadArticle" class="cms-inline-link" :to="{ name: 'article-detail', params: { id: row.id }, query: { from: 'reviews' } }">{{ row.title }}</RouterLink>
                  <span v-else>{{ row.title }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn prop="categoryName" label="分类" width="140" show-overflow-tooltip />
              <ElTableColumn prop="authorName" label="作者" width="120" />
              <ElTableColumn label="状态" width="110">
                <template #default="{ row }">
                  <ElTag :type="statusTagType(row.status)" effect="plain" size="small">{{ statusLabel(row.status) }}</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn prop="version" label="版本" width="80" />
              <ElTableColumn label="更新时间" width="170">
                <template #default="{ row }"><span class="cms-mono">{{ formatLocalDateTime(row.updatedAt) }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="210" fixed="right">
                <template #default="{ row }">
                  <div class="cms-row-actions">
                    <ElButton v-if="canApprove" link type="success" @click="openDecision(row, 'approve')">通过</ElButton>
                    <ElButton v-if="canReject" link type="danger" @click="openDecision(row, 'reject')">驳回</ElButton>
                    <ElButton v-if="canReadHistory" link type="primary" @click="openHistory(row)">历史</ElButton>
                  </div>
                </template>
              </ElTableColumn>
            </ElTable>
            <div class="cms-pagination">
              <ElPagination
                background
                :current-page="current" :page-size="size" :page-sizes="[10, 20, 50]" :total="total"
                :disabled="loading"
                layout="total, sizes, prev, pager, next, jumper"
                @size-change="handleSizeChange" @current-change="handleCurrentChange"
              />
            </div>
          </section>
        </div>
      </ElTabPane>
      <ElTabPane v-if="showPublish" label="发布与下架" name="publish" lazy>
        <PublishPanel :active="activeTab === 'publish'" />
      </ElTabPane>
    </ElTabs>

    <ElDialog
      :key="decisionDialog.instanceKey"
      :model-value="decisionDialog.visible" :title="decisionDialog.mode === 'approve' ? '审核通过' : '驳回文章'"
      width="480px" append-to-body
      :before-close="handleDecisionBeforeClose"
      :close-on-press-escape="!decisionDialog.submitting"
      :close-on-click-modal="!decisionDialog.submitting"
      @close="closeDecision"
    >
      <p class="dialog-tip">
        文章：<strong>{{ decisionDialog.row?.title || '—' }}</strong>
        <span class="dialog-version">当前版本 {{ decisionDialog.row?.version ?? '—' }}</span>
      </p>
      <p class="dialog-tip">
        <ElButton v-if="canReadArticle" link type="primary" @click="toggleArticlePreview">
          {{ articlePreview.visible ? '收起正文' : '在本页查看正文' }}
        </ElButton>
        <span class="dialog-version">正文在弹窗内查看，已填写的审核意见不会丢失</span>
      </p>
      <div v-if="articlePreview.visible" class="article-preview" v-loading="articlePreview.loading">
        <ElAlert v-if="articlePreview.error" :title="articlePreview.error" type="error" :closable="false" show-icon />
        <template v-else-if="articlePreview.data">
          <p class="preview-title">{{ articlePreview.data.title }}</p>
          <p v-if="articlePreview.data.summary" class="preview-summary">{{ articlePreview.data.summary }}</p>
          <div class="preview-content">{{ articlePreview.data.content || '（正文为空）' }}</div>
        </template>
      </div>
      <ElInput
        v-model="decisionDialog.comment" type="textarea" :rows="4" :maxlength="1000"
        :count-graphemes="countCodePoints" show-word-limit
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
.review-history-bar {
  display: flex; flex-wrap: wrap; align-items: center; gap: 10px 12px;
  padding: 12px 20px; border: 1px solid var(--line); border-radius: var(--radius-md);
  background: var(--surface-subtle);
}
.history-bar-label { font-size: 12.5px; font-weight: 500; color: var(--ink-secondary); }
.history-id-input { width: 220px; }
.review-pagination { display: flex; justify-content: flex-end; margin-top: 14px; }
.dialog-tip { margin: 0 0 12px; font-size: 13.5px; color: var(--ink); }
.dialog-version { margin-left: 8px; color: var(--muted); font-size: 12px; }
.article-preview {
  max-height: 300px; overflow: auto; padding: 14px 16px; margin-bottom: 14px;
  border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--surface-subtle);
}
.preview-title { margin: 0 0 6px; font-weight: 600; color: var(--ink); }
.preview-summary { margin: 0 0 8px; color: var(--muted); font-size: 13px; }
.preview-content { white-space: pre-wrap; word-break: break-word; line-height: 1.75; font-size: 13.5px; color: var(--ink); }
@media (max-width: 640px) {
  .history-id-input { width: 100%; }
}
</style>
