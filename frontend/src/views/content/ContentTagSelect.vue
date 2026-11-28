<script setup>
import { computed, ref, watch } from 'vue'
import { ElOption, ElSelect } from 'element-plus'
import { useContentQuery } from './useContentQuery.js'

const props = defineProps({
  fetchTags: { type: Function, required: true },
  initialTags: { type: Array, default: () => [] },
  multiple: Boolean,
  disabled: Boolean,
})
const model = defineModel({ type: [String, Array] })
const keyword = ref('')
const chosen = ref([])
const { data, loading, error, load } = useContentQuery((config) =>
  props.fetchTags({ current: 1, size: 100, keyword: keyword.value.trim() || undefined }, config))
const options = computed(() => {
  const tags = [...props.initialTags, ...chosen.value, ...(data.value?.records ?? [])]
  return [...new Map(tags.map((tag) => [tag.id, tag])).values()]
})

function selectedIds(ids) {
  if (props.multiple) return Array.isArray(ids) ? ids.filter(Boolean) : []
  return ids ? [ids] : []
}

function syncChosen(ids) {
  const selected = selectedIds(ids)
  chosen.value = options.value.filter((tag) => selected.includes(tag.id))
}

watch(model, (ids) => {
  syncChosen(ids)
  // 带 ?tagId= 刷新或从详情返回时，已选 ID 尚未在 options 中：主动拉取一次标签目录回显名称，
  // 避免下拉框直接显示裸数字 ID；无已选值或 initialTags 已包含时仍保持懒加载。
  const missing = selectedIds(ids).some((id) => !options.value.some((tag) => tag.id === id))
  if (missing && !data.value && !loading.value) void load()
}, { immediate: true })

watch(data, () => {
  syncChosen(model.value)
})

function search(value = '') {
  keyword.value = Array.from(value).slice(0, 64).join('')
  void load()
}
</script>

<template>
  <ElSelect
    v-model="model" :multiple="multiple" :multiple-limit="100" :disabled="disabled"
    filterable remote clearable :remote-method="search" :loading="loading"
    :no-data-text="error?.message || '没有匹配的标签'" placeholder="输入标签名称检索" aria-label="标签"
    @visible-change="(open) => open && search()"
  >
    <ElOption
      v-for="tag in options" :key="tag.id" :value="tag.id"
      :label="tag.status === 0 ? tag.tagName + '（已停用）' : tag.tagName"
    />
    <template v-if="error || data?.total > 100" #footer>
      <small>{{ error ? '加载标签失败，可关闭后重开或更换关键词重试。' : '结果超过 100 项，请输入名称缩小范围。' }}</small>
    </template>
  </ElSelect>
</template>
