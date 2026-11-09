<script setup>
import { ref } from 'vue'
import { ElButton, ElForm, ElFormItem, ElInput, ElTag } from 'element-plus'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import AppIcon from '../components/AppIcon.vue'
import { api } from '../api/index.js'
import { createAuthApi } from '../api/auth.js'
import { useSessionStore } from '../stores/session.js'

const router = useRouter()
const session = useSessionStore()
const { isAuthenticated, user } = storeToRefs(session)
const authApi = createAuthApi(api)

const formRef = ref(null)
const form = ref({ username: '', password: '' })
const submitting = ref(false)
const rules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  if (submitting.value) return
  // 先置位再 await，防止回车与表单 submit 在校验期间并发触发两次登录请求。
  submitting.value = true
  try {
    // Element Plus 的 validate() 在校验不通过时 reject，按框架约定转为 false。
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return
    const result = await authApi.login(form.value.username, form.value.password)
    session.setToken(result.accessToken)
    // LoginResult.user 与 GET /auth/me 返回的是同一个 User 契约，无需重复请求。
    session.setUser(result.user)
    form.value.password = ''
    await router.push('/workspace')
  } catch {
    // api/index.js 的 onError 已统一用 ElMessage 提示（含 401「用户名或密码错误」），
    // 组件只负责结束加载状态，不重复弹窗、不吞掉请求异常本身。
  } finally {
    submitting.value = false
  }
}

async function signOut() {
  if (submitting.value) return
  submitting.value = true
  try {
    await authApi.logout()
  } catch {
    // 401 时公共层会清空会话并跳转登录页；此处仍保证本地状态被清理。
  } finally {
    session.clear()
    submitting.value = false
  }
}
</script>

<template>
  <section class="empty-panel standalone-panel login-panel">
    <div class="empty-illustration"><AppIcon name="shield" :size="44" /></div>

    <template v-if="isAuthenticated">
      <ElTag effect="plain" round>已登录</ElTag>
      <h1>欢迎回来</h1>
      <p>{{ user?.realName || user?.username || '当前账号' }}，你已通过企业身份验证。</p>
      <div class="login-actions">
        <ElButton type="primary" :loading="submitting" @click="signOut">退出登录</ElButton>
        <ElButton @click="router.push('/workspace')">返回工作空间</ElButton>
      </div>
    </template>

    <template v-else>
      <ElTag effect="plain" round>企业账号</ElTag>
      <h1>账号登录</h1>
      <p>使用企业账号访问知识与协作服务。</p>
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" class="login-form" @submit.prevent="submit">
        <ElFormItem label="账号" prop="username">
          <ElInput v-model="form.username" autocomplete="username" maxlength="64" placeholder="请输入登录账号" />
        </ElFormItem>
        <ElFormItem label="密码" prop="password">
          <ElInput v-model="form.password" type="password" show-password autocomplete="current-password" maxlength="128" placeholder="请输入密码" />
        </ElFormItem>
        <ElButton type="primary" native-type="submit" :loading="submitting" class="login-submit">登录</ElButton>
      </ElForm>
    </template>
  </section>
</template>

<style scoped>
.login-panel :deep(.login-form) { width: min(360px, 100%); margin: 24px auto 0; text-align: left; }
.login-panel :deep(.el-form-item) { margin-bottom: 18px; }
.login-panel :deep(.login-submit) { width: 100%; }
.login-panel .login-actions { display: flex; justify-content: center; gap: 8px; flex-wrap: wrap; margin-top: 20px; }
</style>
