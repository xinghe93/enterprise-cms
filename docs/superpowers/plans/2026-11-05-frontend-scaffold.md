# 前端公共骨架实施计划

**目标：** 在独立的 `frontend` 工程搭建可启动、可打包的公共基础，供四名成员分别开发页面。

**方案：** Vue 3 单页工程，知识门户与后台共用响应式布局。深青色导航、米白色内容区，首页只提供模块入口，不展示虚构业务数据。业务页面与登录页为明确的占位页，不发起未实现接口请求。

**技术：** Vite、Vue Router 4、Pinia、Axios、按需显式引入 Element Plus；使用 JavaScript 和 Node 原生测试，不引入 Python 或额外测试框架。

## 实施步骤

- [x] 在 `frontend/package.json` 固定依赖版本，添加 `vite.config.js`、`index.html`、`.env.example`、`.nvmrc`。开发代理保留 `/api` 前缀，指向可配置的 8080 后端。
- [x] 在 `frontend/tests/request.test.js` 先验证响应解包、字符串 ID、Bearer、400/401/403/500、超时、网络异常和取消；使用 Axios adapter 隔离网络。实现 `src/api/client.js`，保留可等待的 Promise 和统一错误对象，避免重复提示。
- [x] 在 `frontend/tests/session.test.js` 验证会话重置及权限更新。实现 `src/stores/session.js`，令牌仅在内存保存，重载后需重新登录，不生成模拟身份。
- [x] 创建 `src/router/index.js`、模块导航配置、`src/layouts/AppLayout.vue`、公共占位页面、首页、登录入口、404 页面；路由懒加载，移动端可展开导航，支持键盘操作。
- [x] 在 `src/main.js` 接入 Pinia、Router、中文 Element Plus 配置和错误提示；401 清理会话并跳转登录入口。请求基础设施不承诺已实现后端认证和权限保护。
- [x] 编写 `frontend/README.md`，说明安装、启动、构建、环境变量、生产 history 回退与 `/api` 反向代理、目录分工和接口调用示例。
- [x] 运行 `npm test`、`npm run build`，验证实际 HTTP 路由和代理；尝试浏览器检查桌面/移动端页面；审查源代码，不自动提交或推送。

## 边界

不开发四个成员的业务 CRUD、登录接口、动态菜单和鉴权指令，不修改后端、数据库或已对齐的接口契约。所有菜单目前均为骨架入口，最终可见性由有效权限接入，接口权限始终由后端强制校验。

## 验证记录

- `npm test`：15 项通过；`npm run build`：通过，无构建警告。
- 真实 Axios → 临时 Vite → 本地模拟后端：路径 `/api`、分页参数、Bearer 和 403 错误响应检查通过，首页及 4 个深层路径均返回入口 HTML。此检查不代表已验证浏览器路由渲染。
- 独立代码审查发现菜单关闭后焦点恢复需等待 Vue 更新，已添加 `await nextTick()`。
- 可视化验收未完成：内置浏览器两次等待 webview 连接超时，尚未实际验证桌面/手机渲染、导航点击和键盘交互；交付时说明此限制。
- 没有修改后端，没有提交或推送。
