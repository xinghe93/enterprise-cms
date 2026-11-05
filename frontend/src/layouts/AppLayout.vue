<script setup>
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AppIcon from '../components/AppIcon.vue'
import { navigation } from '../router/navigation.js'
import { useSessionStore } from '../stores/session.js'

const route = useRoute()
const menuOpen = ref(false)
const menuToggle = ref(null)
const sidebar = ref(null)
const mobileQuery = window.matchMedia('(max-width: 640px)')
const isMobile = ref(mobileQuery.matches)
const { user } = storeToRefs(useSessionStore())
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
        <div v-for="group in navigation" :key="group.title" class="nav-group">
          <p class="nav-group-title">{{ group.title }}</p>
          <RouterLink v-for="item in group.items" :key="item.path" :to="item.path" class="nav-link" active-class="is-active">
            <AppIcon :name="item.icon" :size="18" /><span>{{ item.title }}</span><span class="nav-active-dot" />
          </RouterLink>
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
