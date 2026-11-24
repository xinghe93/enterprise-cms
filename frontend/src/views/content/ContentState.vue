<script setup>
import { ElAlert, ElButton, ElEmpty, ElSkeleton } from 'element-plus'
import { useSessionStore } from '../../stores/session.js'
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/skeleton/style/css.mjs'

defineProps({
  allowed: Boolean,
  loading: Boolean,
  error: Object,
  retry: { type: Boolean, default: true },
})
defineEmits(['retry'])
const session = useSessionStore()
</script>

<template>
  <div v-if="!session.isAuthenticated" class="state-panel">
    <ElEmpty description="请登录后访问内容工作空间" :image-size="76">
      <RouterLink :to="{ name: 'login' }" class="login-link">前往账号登录 →</RouterLink>
    </ElEmpty>
  </div>
  <div v-else-if="!allowed" class="state-panel">
    <ElEmpty description="当前账号没有访问此功能的权限" :image-size="76" />
  </div>
  <div v-else-if="error" class="state-panel failure" role="status">
    <ElAlert :title="error.message" type="error" :closable="false" show-icon />
    <ElButton v-if="retry" @click="$emit('retry')">重新加载</ElButton>
  </div>
  <div v-else-if="loading" class="state-panel" aria-busy="true" aria-label="正在加载">
    <ElSkeleton :rows="4" animated />
  </div>
  <slot v-else />
</template>

<style scoped>
.state-panel { border: 1px solid var(--line); background: #fdfefa; border-radius: 10px; padding: 24px; }
.failure { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.failure .el-alert { flex: 1 1 250px; overflow-wrap: anywhere; }
.login-link { color: var(--el-color-primary); font-size: 14px; text-decoration: underline; text-underline-offset: 4px; }
</style>
