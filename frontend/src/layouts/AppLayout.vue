<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AppIcon from '../components/AppIcon.vue'
import { navigation } from '../router/navigation.js'
import { useSessionStore } from '../stores/session.js'
import { shouldRefreshOnSamePageClick, visibleNavigation } from '../utils/menuPermission.js'

const route = useRoute()
const menuOpen = ref(false)
const menuToggle = ref(null)
const sidebar = ref(null)
const mobileQuery = window.matchMedia('(max-width: 640px)')
const isMobile = ref(mobileQuery.matches)
const session = useSessionStore()
const { user } = storeToRefs(session)

// 菜单按后端有效权限过滤（《接口与协作约定》3.4）：导航编码与路由 meta 同源。
// 权限已加载（loaded）才严格过滤；加载失败（unavailable）时无法判断，展示全部入口
// 并由页面自身门控兜底，同时在菜单底部给出重试入口——不把「不可用」误当成「没有权限」。
const permissionFailed = computed(() => session.permissionStatus === 'unavailable')
const menuGroups = computed(() => (permissionFailed.value
  ? navigation
  : visibleNavigation(navigation, session.hasPermission)))

async function retryPermissions() {
  await session.refreshPermissions()
}

// ① 布局感知令牌变化：登录/切换账号后主动初始化权限。workspace 不声明权限、
// 守卫不会为其刷新，若不在这里加载，侧栏会一直只显示工作空间（评审 P2）。
watch(() => session.accessToken, async (token) => {
  if (token && session.permissionStatus !== 'loaded') await session.ensurePermissions()
}, { immediate: true })

// ② 同页重复点击菜单：Vue Router 判为 duplicated 导航、不执行 beforeEach，
// 守卫里的刷新不会发生；这里直接刷新，让会话内新增的子功能权限（tag:list）即时生效。
async function onMenuClick(item) {
  if (shouldRefreshOnSamePageClick(item.path, route.path)) await session.refreshPermissions()
}

watch(() => route.fullPath, async () => {
  if (menuOpen.value) {
    menuOpen.value = false
    await nextTick()
    document.querySelector('#main-content')?.focus()
  }
})
function updateViewport(event) {
  isMobile.value = event.matches
  menuOpen.value = false
}
onMounted(() => mobileQuery.addEventListener('change', updateViewport))
onUnmounted(() => mobileQuery.removeEventListener('change', updateViewport))

async function toggleMenu() {
  menuOpen.value = !menuOpen.value
  if (menuOpen.value) {
    await nextTick()
    sidebar.value?.querySelector('a')?.focus()
  }
}

async function closeMenu() {
  if (!menuOpen.value) return
  menuOpen.value = false
  await nextTick()
  menuToggle.value?.focus()
}

function trapFocus(event) {
  if (!menuOpen.value || event.key !== 'Tab') return
  const targets = sidebar.value.querySelectorAll('a, button')
  const first = targets[0]
  const last = targets[targets.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <div class="app-shell" @keydown.esc="closeMenu">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <button v-if="menuOpen" class="nav-backdrop" aria-label="关闭导航" @click="closeMenu" />
    <aside id="app-navigation" ref="sidebar" class="sidebar" :class="{ 'is-open': menuOpen }" :inert="isMobile && !menuOpen" @keydown="trapFocus">
      <button v-if="isMobile" class="icon-button nav-close" aria-label="关闭导航菜单" @click="closeMenu"><AppIcon name="close" /></button>
      <RouterLink to="/workspace" class="brand" aria-label="EnterpriseCMS 工作空间">
        <span class="brand-mark">E<span> /</span></span>
        <span>Enterprise<span class="brand-cms">CMS</span><small>企业知识与内容管理</small></span>
      </RouterLink>
      <nav aria-label="主要导航">
        <div v-for="group in menuGroups" :key="group.title" class="nav-group">
          <p class="nav-group-title">{{ group.title }}</p>
          <RouterLink v-for="item in group.items" :key="item.path" :to="item.path" class="nav-link" active-class="is-active" @click="onMenuClick(item)">
            <AppIcon :name="item.icon" :size="18" /><span>{{ item.title }}</span><span class="nav-active-dot" />
          </RouterLink>
        </div>
        <!-- 公共权限刷新入口：常驻，覆盖同页重复点击（不触发守卫）与 loaded 下
             会话内新增授权两种场景；加载失败时文案强调重试，不做静默降级。 -->
        <div class="nav-group">
          <p class="nav-group-title">权限</p>
          <button type="button" class="nav-link" @click="retryPermissions">
            {{ permissionFailed ? '权限加载失败，点击重试' : '刷新权限' }}
          </button>
        </div>
      </nav>
      <div class="sidebar-note"><span class="status-dot" /><span>让知识成为团队的共同资产</span></div>
    </aside>

    <div class="main-shell" :inert="isMobile && menuOpen">
      <header class="topbar">
        <div class="breadcrumb">
          <button ref="menuToggle" type="button" class="icon-button menu-toggle" :aria-expanded="menuOpen" aria-controls="app-navigation" aria-label="切换导航菜单" @click="toggleMenu"><AppIcon :name="menuOpen ? 'close' : 'menu'" /></button>
          <span class="breadcrumb-root">EnterpriseCMS</span><span class="breadcrumb-divider">/</span><span>{{ route.meta.title }}</span>
        </div>
        <RouterLink to="/login" class="account-link"><span class="account-avatar"><AppIcon name="users" :size="17" /></span><span>{{ user?.realName || '未登录' }}</span></RouterLink>
      </header>
      <main id="main-content" class="page-content" tabindex="-1"><RouterView /></main>
      <footer class="app-footer"><span>EnterpriseCMS</span><span>企业内部知识与内容管理平台</span></footer>
    </div>
  </div>
</template>
