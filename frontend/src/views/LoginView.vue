<script setup>
import { ref } from 'vue'
import { ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElTag } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import AppIcon from '../components/AppIcon.vue'
import { api } from '../api/index.js'
import { createAuthApi } from '../api/auth.js'
import { safeRedirectPath } from '../utils/menuPermission.js'
import { countCodePoints } from '../utils/text.js'
import { useSessionStore } from '../stores/session.js'

const route = useRoute()
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
    // 登录完成即初始化有效权限（评审 P2）：workspace 免权限、守卫不会刷新，若不在此
    // 加载，跳过去后权限仍是 idle、侧栏隐藏全部业务菜单，直到手工进业务页才恢复。
    // 与 AppLayout 的令牌 watch 双保险；ensurePermissions 同令牌去重，不会重复请求。
    await session.ensurePermissions()
    form.value.password = ''
    await router.push(safeRedirectPath(route.query.redirect))
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
    ElMessage.success({ message: '已退出登录', grouping: true })
  } catch {
    // 401 时公共层会清空会话并跳转登录页；此处仍保证本地状态被清理。
  } finally {
    session.clear()
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-stage">
    <div class="auth-card">
      <aside class="auth-brand-pane" aria-hidden="true">
        <div class="auth-brand-top">
          <span class="auth-badge">ENTERPRISE CMS</span>
          <h2>企业内部知识与<br />内容管理平台</h2>
          <p>统一管理员工组织、角色权限、知识文档与审核发布全流程，让每一次沉淀可追溯、可复用。</p>
        </div>
        <ul class="auth-pillars">
          <li><AppIcon name="book" :size="16" /><span>结构化知识门户与多级分类检索</span></li>
          <li><AppIcon name="check" :size="16" /><span>严谨的草稿审核与发布下架闭环</span></li>
          <li><AppIcon name="shield" :size="16" /><span>细粒度角色权限与实时操作审计</span></li>
        </ul>
      </aside>

      <div class="auth-form-pane">
        <template v-if="isAuthenticated">
          <div class="auth-status-card">
            <div class="empty-illustration"><AppIcon name="shield" :size="36" /></div>
            <ElTag type="success" effect="plain" round>已通过企业身份认证</ElTag>
            <h1>欢迎回来，{{ user?.realName || user?.username || '当前账号' }}</h1>
            <p>账号 <span class="cms-mono">{{ user?.username || '—' }}</span> 当前处于有效会话中，可继续访问已授权的工作空间与业务模块。</p>
            <div class="login-actions">
              <ElButton type="primary" @click="router.push('/workspace')">进入工作空间</ElButton>
              <ElButton :loading="submitting" @click="signOut">退出登录</ElButton>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="auth-form-header">
            <p class="eyebrow">ACCOUNT SIGN IN</p>
            <h1>账号登录</h1>
            <p>请使用企业内部账号登录以访问知识与协作服务。</p>
          </div>
          <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" class="login-form" @submit.prevent="submit">
            <ElFormItem label="登录账号" prop="username">
              <!-- maxlength 按 count-graphemes 逐代码点计数（老师复审 P1）：与创建接口
                   的 Unicode「字符」语义一致，增补平面字符不再被按 UTF-16 代码单元截半。 -->
              <ElInput v-model="form.username" size="large" autocomplete="username" :maxlength="64" :count-graphemes="countCodePoints" placeholder="请输入登录账号" />
            </ElFormItem>
            <ElFormItem label="登录密码" prop="password">
              <ElInput v-model="form.password" size="large" type="password" show-password autocomplete="current-password" :maxlength="128" :count-graphemes="countCodePoints" placeholder="请输入密码" />
            </ElFormItem>
            <ElButton type="primary" size="large" native-type="submit" :loading="submitting" class="login-submit">登录工作空间</ElButton>
          </ElForm>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.auth-stage { display: grid; place-items: center; min-height: calc(100vh - 200px); padding: 12px 0; }
.auth-card {
  width: min(920px, 100%); display: grid; grid-template-columns: 1.05fr 1fr;
  border: 1px solid var(--line); border-radius: 18px; overflow: hidden;
  background: var(--surface); box-shadow: var(--shadow-md);
}
.auth-brand-pane {
  display: flex; flex-direction: column; justify-content: space-between; gap: 32px; padding: 40px 36px;
  background: linear-gradient(155deg, #16443f 0%, #113430 60%, #0d2926 100%); color: #e4efe8;
}
.auth-badge {
  display: inline-block; padding: 4px 10px; border-radius: 999px;
  background: rgba(229, 236, 186, 0.16); color: var(--accent);
  font-family: "Avenir Next", sans-serif; font-size: 11px; font-weight: 600; letter-spacing: 1.8px;
}
.auth-brand-pane h2 {
  font-family: "Songti SC", "Noto Serif CJK SC", "SimSun", serif;
  font-size: 28px; line-height: 1.4; font-weight: 600; margin: 18px 0 14px; color: #f6faf6;
}
.auth-brand-pane p { font-size: 13.5px; line-height: 1.8; color: #b5ccc0; }
.auth-pillars { list-style: none; margin: 0; padding: 18px 0 0; border-top: 1px solid rgba(255, 255, 255, 0.1); display: grid; gap: 12px; }
.auth-pillars li { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #d3e3da; }
.auth-pillars svg { color: var(--accent); }
.auth-form-pane { display: flex; flex-direction: column; justify-content: center; padding: 44px 40px; background: var(--surface); }
.auth-form-header h1 { font-size: 24px; font-weight: 600; color: var(--ink); margin: 8px 0 8px; }
.auth-form-header > p:last-child { font-size: 13.5px; color: var(--muted); line-height: 1.65; }
.login-form { margin-top: 26px; }
.login-form :deep(.el-form-item) { margin-bottom: 20px; }
.login-submit { width: 100%; margin-top: 6px; }
.auth-status-card { display: flex; flex-direction: column; align-items: center; text-align: center; }
.auth-status-card h1 { font-size: 22px; font-weight: 600; margin: 14px 0 10px; color: var(--ink); }
.auth-status-card p { font-size: 13.5px; color: var(--muted); line-height: 1.75; margin-bottom: 22px; }
.login-actions { display: flex; justify-content: center; gap: 10px; flex-wrap: wrap; }
@media (max-width: 780px) {
  .auth-card { grid-template-columns: 1fr; }
  .auth-brand-pane { padding: 28px 24px; gap: 20px; }
  .auth-form-pane { padding: 28px 24px; }
}
</style>
