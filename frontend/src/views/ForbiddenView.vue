<script setup>
import { ElButton } from 'element-plus'
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <section class="empty-panel standalone-panel"><p class="error-number">403</p><h1>没有访问该页面的权限</h1><p>当前账号的有效权限不包含该页面所需权限。如需访问，请联系管理员调整角色权限后刷新或重新进入页面。</p><ElButton type="primary" @click="router.push('/workspace')">返回工作空间</ElButton></section>
</template>
