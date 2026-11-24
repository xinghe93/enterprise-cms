<script setup>
import { computed, ref, watch } from 'vue'
import { ElOption, ElSelect } from 'element-plus'
import { useContentQuery } from './useContentQuery.js'
import 'element-plus/es/components/select/style/css.mjs'

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

watch(model, (ids) => {
  const selected = props.multiple ? ids : [ids]
  chosen.value = options.value.filter((tag) => selected?.includes(tag.id))
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
