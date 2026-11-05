import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router/index.js'
import { pinia } from './stores/index.js'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-tag.css'
import 'element-plus/theme-chalk/el-message.css'
import './styles/main.css'

createApp(App).use(pinia).use(router).mount('#app')
