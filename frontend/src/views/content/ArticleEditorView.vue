<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElOption, ElSelect, ElTag } from 'element-plus'
import { createArticle, getArticle, listCategories, listTags, updateArticle } from '../../api/content.js'
import AppIcon from '../../components/AppIcon.vue'
import { useSessionStore } from '../../stores/session.js'
import ContentState from './ContentState.vue'
import ContentTagSelect from './ContentTagSelect.vue'
import { articlePayload, articleStatuses, categoryOptions, isContentId, isEditableArticle } from './contentModel.js'
import { useContentQuery } from './useContentQuery.js'
import './content.css'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const can = (code) => session.isAuthenticated && session.hasPermission(code)
const editing = computed(() => route.name === 'article-edit')
const id = computed(() => route.params.id)
const allowed = computed(() => editing.value ? can('article:update') && can('article:read') : can('article:create'))
const validId = computed(() => !editing.value || isContentId(id.value))
const articleQuery = useContentQuery((config) => getArticle(id.value, config))
const categoryQuery = useContentQuery((config) => listCategories(undefined, config))
const article = articleQuery.data
const form = reactive({ title: '', summary: '', content: '', categoryId: '', tagIds: [] })
const saving = ref(false)
const conflict = ref(false)
const formError = ref('')
const countCodePoints = (value) => Array.from(value).length
const canListTags = computed(() => can('tag:list'))
const canListCategories = computed(() => can('category:tree'))
const canSave = computed(() => editing.value ? isEditableArticle(article.value?.status) : canListCategories.value)
const categories = computed(() => {
  const currentId = article.value?.categoryId
  const options = categoryOptions(categoryQuery.data.value ?? [], { activeOnly: true, currentId })
  if (currentId && !options.some((option) => option.id === currentId)) {
    options.unshift({ id: currentId, label: article.value.categoryName })
  }
  return options
})
const fetchActiveTags = (params, config) => listTags({ ...params, status: 1 }, config)
const back = computed(() => editing.value && can('article:read') && validId.value
  ? { name: 'article-detail', params: { id: id.value }, query: route.query }
  : { name: 'articles', query: route.query })

watch([allowed, editing, id], () => {
  articleQuery.clear()
  if (allowed.value && editing.value && validId.value) void articleQuery.load()
}, { immediate: true })

watch([allowed, canListCategories], () => {
  categoryQuery.clear()
  if (allowed.value && canListCategories.value) void categoryQuery.load()
}, { immediate: true })

watch(article, (record) => {
  Object.assign(form, {
    title: record?.title ?? '', summary: record?.summary ?? '', content: record?.content ?? '',
    categoryId: record?.categoryId ?? '', tagIds: record?.tags.map((tag) => tag.id) ?? [],
  })
  formError.value = ''
  conflict.value = false
}, { immediate: true })

async function save() {
  if (saving.value || !canSave.value || articleQuery.loading.value) return
  if (!form.title.trim() || !form.content.trim() || !isContentId(form.categoryId)) {
    formError.value = '请填写标题、正文并选择分类'
    return
  }

  if (countCodePoints(form.title) > 255 || countCodePoints(form.summary) > 1000) {
    formError.value = '标题最多 255 个字符，摘要最多 1000 个字符'
    return
  }

  const originalRoute = route.fullPath
  const wasEditing = editing.value
  const payload = articlePayload(form, article.value?.version)
  saving.value = true
  formError.value = ''
  try {
    const result = wasEditing ? await updateArticle(id.value, payload) : await createArticle(payload)
    ElMessage.success(wasEditing ? '文章已保存' : '草稿已创建')
    if (route.fullPath === originalRoute) {
      await router.replace(can('article:read')
        ? { name: 'article-detail', params: { id: result.id }, query: route.query }
        : { name: 'articles', query: route.query })
    }
  } catch (failure) {
    conflict.value = editing.value && failure.code === 409
  } finally {
    saving.value = false
  }
}

async function reloadArticle() {
  try {
    await ElMessageBox.confirm('重新载入会清除当前表单中尚未保存的修改。', '重新载入文章', {
      type: 'warning', confirmButtonText: '重新载入', cancelButtonText: '保留表单',
    })
    await articleQuery.load()
  } catch {
    // 用户取消，继续保留输入。
  }
}
</script>

<template>
  <section class="cms-page content-page">
    <RouterLink :to="back" class="content-back"><AppIcon name="arrow" :size="15" />{{ editing && validId ? '返回文章' : '返回文章管理' }}</RouterLink>
    <header class="content-heading">
      <div class="page-heading">
        <p class="eyebrow">CONTENT EDITOR</p>
        <h1>{{ editing ? '编辑文章' : '新建草稿' }}</h1>
        <p>{{ editing ? '修改文章内容、分类与标签绑定，保存后自动递增版本号。' : '撰写新的知识草稿，配置所属分类和标签后可保存或提交审核。' }}</p>
      </div>
      <ElTag v-if="article" :type="articleStatuses[article.status]?.type" effect="plain">{{ articleStatuses[article.status]?.label }}</ElTag>
    </header>

    <ContentState
      :allowed="allowed" :loading="!article && articleQuery.loading.value"
      :error="validId ? (!article ? articleQuery.error.value : null) : { message: '文章 ID 无效' }" :retry="validId"
      @retry="articleQuery.load()"
    >
      <ElAlert v-if="!canSave" :title="editing ? '当前文章状态无法编辑' : '新建文章需要读取分类的权限'" type="warning" :closable="false" show-icon />
      <ElForm v-else :key="article?.id ?? 'new'" class="content-panel editor-form" label-position="top" @submit.prevent="save">
        <ElFormItem label="标题" required>
          <ElInput v-model="form.title" aria-label="标题" :maxlength="255" :count-graphemes="countCodePoints" show-word-limit :disabled="saving" />
        </ElFormItem>
        <div class="form-grid">
          <ElFormItem label="分类" required>
            <ElSelect
              v-model="form.categoryId" aria-label="分类" filterable placeholder="选择一个分类"
              :loading="categoryQuery.loading.value" :disabled="!canListCategories || saving"
              :no-data-text="categoryQuery.error.value?.message || '暂无启用的分类'"
            >
              <ElOption v-for="option in categories" :key="option.id" :value="option.id" :label="option.label" />
            </ElSelect>
            <ElButton v-if="categoryQuery.error.value" link type="danger" @click="categoryQuery.load()">加载分类失败，点击重试</ElButton>
          </ElFormItem>
          <ElFormItem label="标签">
            <ContentTagSelect v-if="canListTags" v-model="form.tagIds" :fetch-tags="fetchActiveTags" :initial-tags="article?.tags ?? []" :disabled="saving" multiple />
            <div v-else class="read-only-tags">
              <ElTag v-for="tag in article?.tags ?? []" :key="tag.id" type="info" effect="plain">{{ tag.tagName }}{{ tag.status === 0 ? '（已停用）' : '' }}</ElTag>
              <p class="field-help">当前账号没有查询标签的权限。</p>
            </div>
          </ElFormItem>
        </div>
        <ElFormItem label="摘要">
          <ElInput v-model="form.summary" aria-label="摘要" type="textarea" :rows="3" :maxlength="1000" :count-graphemes="countCodePoints" show-word-limit :disabled="saving" />
        </ElFormItem>
        <ElFormItem label="正文" required>
          <ElInput v-model="form.content" aria-label="正文" type="textarea" :rows="13" :disabled="saving" />
        </ElFormItem>
        <ElAlert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
        <div v-if="conflict" class="conflict-notice">
          <p class="field-help">保存遇到业务冲突，当前输入仍保留。可先复制修改，再重新载入最新文章。</p>
          <ElButton link type="primary" :loading="articleQuery.loading.value" @click="reloadArticle">重新载入文章</ElButton>
          <ElAlert v-if="articleQuery.error.value" :title="articleQuery.error.value.message" type="error" :closable="false" show-icon />
        </div>
        <div class="editor-actions">
          <ElButton :disabled="saving" @click="router.push(back)">取消</ElButton>
          <ElButton type="primary" native-type="submit" :loading="saving" :disabled="articleQuery.loading.value">{{ editing ? '保存修改' : '保存草稿' }}</ElButton>
        </div>
      </ElForm>
    </ContentState>
  </section>
</template>

<style scoped>
.editor-form { padding: clamp(16px, 3vw, 28px); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.el-select { width: 100%; }
.read-only-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.read-only-tags p { flex-basis: 100%; }
.editor-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 22px; }
.editor-actions .el-button { margin: 0; }
.conflict-notice { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; background: #f5efe3; padding: 10px 13px; border-radius: 5px; }
@media (max-width: 600px) {
  .form-grid { grid-template-columns: minmax(0, 1fr); gap: 0; }
}
</style>
