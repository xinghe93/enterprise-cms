# B 模块交付与验收说明（角色 / 权限项 / 授权快照 / AOP 鉴权）

> 适用对象：组长验收、组员联调。
> 维护人：B 同学。最后更新：2026-11-23。
> 基线：`feature/B/permission-frontend` 已合入 `dev`；契约以 `docs/api/enterprise-cms.openapi.json` 与《接口与协作约定 V1.0》《跨模块服务接口 V1.0》为准。

## 1. 模块范围

| 项 | 内容 |
| --- | --- |
| 数据表（3 张） | `sys_role`、`sys_permission`、`sys_role_permission`（复合主键 `(role_id, permission_id)`） |
| 对外服务 | `com.xinghe.cms.permission.service.AuthorizationQueryService#getForUser(Long)` —— 返回 `AuthorizationSnapshot(enabledRoleCodes, permissionCodes)`，每请求重算、不跨请求缓存 |
| 鉴权基础设施 | `com.xinghe.cms.permission.aspect.RequirePermissionAspect`（`@RequirePermission`） |
| 依赖的他人交付 | A：`CurrentUserProvider` / 登录与 Token / `sys_user` 操作人行；D：`@AuditLog` 审计切面；C：无直接依赖 |

### 1.1 接口清单（B 负责 12 个）

| # | 方法 | 路径 | 权限码 | 所属 PR / 分支 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/api/auth/permissions` | `AUTHENTICATED` | #60（feature/B/auth-permissions） |
| 2 | GET | `/api/admin/roles` | `role:list` | #46 / #58 |
| 3 | POST | `/api/admin/roles` | `role:create` | #46 |
| 4 | GET | `/api/admin/roles/{id}` | `role:read` | #46 |
| 5 | PUT | `/api/admin/roles/{id}` | `role:update` | #46 |
| 6 | PUT | `/api/admin/roles/{id}/status` | `role:status` | #46 |
| 7 | GET | `/api/admin/roles/{id}/permissions` | `role:read-permission` | #58 |
| 8 | PUT | `/api/admin/roles/{id}/permissions` | `role:assign-permission` | #58 + 前端会话修复 |
| 9 | GET | `/api/admin/permissions` | `permission:list` | #59 |
| 10 | POST | `/api/admin/permissions` | `permission:create` | #59 |
| 11 | PUT | `/api/admin/permissions/{id}` | `permission:update` | #59 |
| 12 | PUT | `/api/admin/permissions/{id}/status` | `permission:status` | #59 |

权限码共 12 个：上表 12 个接口各一个（`AUTHENTICATED` 不是权限表记录，仅表示登录即可）。

### 1.2 审计操作码（7 个）

`role.create`、`role.update`、`role.status`、`role.assign-permission`、`permission.create`、`permission.update`、`permission.status`。

操作人一律从 A 的 `CurrentUserProvider` 取，不接受请求参数 `userId`；无认证上下文（内部调用/单测）时审计 `user_id` 写 `null`。

## 2. 验收矩阵

| 场景 | 覆盖用例 | 证据 |
| --- | --- | --- |
| 匿名 401 | 按实际自动化层次列明：缺 Bearer / 失效或撤销 Token / 停用用户 → 401 由过滤器测试覆盖；`/api/auth/permissions` 匿名 → 401 由查询控制器测试覆盖。**其余 11 个管理接口的逐接口 401 未做自动化覆盖**（两个 Controller HTTP 测试的 `setUp` 固定模拟已登录管理员），属待手工验收项（见 §6 场景 7） | `BearerTokenAuthenticationFilterTests`（`missingBearerTokenReturnsUnauthorizedEnvelope`、`revokedTokenIsUnauthorized`、`disabledUserIsRejectedWithConditionalRevokeUsingObservedEpoch`）；`PermissionQueryControllerTests.rejectsAnonymousRequestWithUnauthorized` |
| 无权限 403 | `@RequirePermission` 命中任一编码放行、全未命中 403、快照为空 fail-closed | `RequirePermissionAspectTests`（切面层单测：`permitsWhenSnapshotContainsRequiredCode`、`permitsAnyOfMultipleRequiredCodes`、`deniesWithForbiddenWhenCodeMissing`、`failsClosedOnEmptyCodes`）；**逐接口 403 未做自动化**，见 §6 场景 7 |
| 有权限 200（HTTP 层） | 各端点请求/响应封装与 200 + `data` 语义；Service 被替换为 Mockito 桩，不连库 | `RoleControllerHttpTests`（56 项）、`PermissionControllerHttpTests`（18 项）；**真实 CRUD/启停/分配的端到端行为**由 Service 单测（Mockito）+ 手工验收（§6 场景 1–2）共同覆盖 |
| 参数 400 | ID 非十进制/前导零/0/超 Long；未知字段；DTO 空白（含 NBSP U+3000）；`PermissionUpdate` 严格反序列化器；`expectedPermissionIds` 非数组/非法元素 | 均在两个 Controller HTTP 测试内：`RoleControllerHttpTests`（`unknownBodyFieldReturns400`、`updateRejectsUnknownBodyField`、`updateNbspOnlyRoleNameReturns400`、`currentBeyondInt64RangeReturns400`、`setPermissionsRejectsMalformedIdListWith400`、`setPermissionsRejectsMalformedExpectedBaselineWith400` 等）、`PermissionControllerHttpTests`（`createUnknownFieldReturns400`、`createWrongFieldTypeReturns400`、`updateUnknownFieldOrWrongTypeReturns400`、`setStatusInvalidBodyReturns400` 等）；`ContractWhitespace` 单测。`AuthorizationSnapshotTests` 验的是快照集合契约，不属于 HTTP 参数校验，已从本行移除 |
| 不存在 404 | 角色不存在、权限项不存在 | HTTP 层：Controller 测试（Service 桩抛 404 验状态码映射）；业务层：`RoleAdminServiceImplTests`、`PermissionAdminServiceImplTests`、`RolePermissionServiceImplTests` |
| 状态冲突 409 | 请求含停用权限；**角色/权限编码重复**（`001_init.sql` 只对 `role_code`、`permission_code` 设唯一约束，服务层也按编码判冲突；用不同编码创建同名角色/权限项是允许操作，不得当作 409 验收）；**并发基线 `expectedPermissionIds` 与当前集合不一致** | 编码重复：`RoleAdminServiceImplTests.duplicateRoleCodeBecomes409`、`PermissionAdminServiceImplTests.duplicateCodeBecomes409`、`RoleControllerHttpTests.duplicateRoleCodeReturns409` / `whitespacePaddedDuplicateRoleCodeReturns409`；停用权限：`RolePermissionServiceImplTests.replacePermissionsThrows409WhenPermissionDisabledEvenIfSetUnchanged`、`RoleControllerHttpTests.setPermissionsDisabledPermissionReturns409`；基线冲突：`RolePermissionServiceImplTests.replacePermissionsThrows409WhenExpectedDiffersFromCurrent` / `replacePermissionsThrows409WhenNewGrantAppearedAfterBaseline`、`RoleControllerHttpTests.setPermissionsBaselineConflictReturns409` |
| 审计成功 / 失败 | 写操作 SUCCESS 随事务提交；业务异常回滚后 `afterCompletion` 独立事务补 FAILURE。**例外（勿期待）**：`role.status` 先执行条件 `UPDATE` 成功后才调用空审计锚点 `roleStatusChanged()`，若 `UPDATE` 本身抛 SQL 异常，切面尚未进入、不补记 `role.status`/FAILURE；`permission.status` 与 `role.assign-permission` 把实际写入以 `Runnable` 传入审计代理内部，写失败会补 FAILURE。**免审计仅限两类明确跳过的路径**：启停「重复设置同一状态（含条件更新影响 0 行）」与角色权限「相同集合幂等」；`RoleAdminServiceImpl.update` / `PermissionAdminServiceImpl.update` 直接标 `@AuditLog`，提交相同字段仍执行更新并记录 SUCCESS | `RoleAdminAuditTransactionTests`（含幂等跳过、审计写失败回滚、404 补 FAILURE）、`PermissionAdminAuditTransactionTests`、`RolePermissionAuditTransactionTests` |
| 授权快照一致性 | 停用角色/权限不计入；启用用户无角色时双集合为空；用户不存在/停用抛 UNAUTHORIZED | `AuthorizationQueryServiceImplTests`、`AuthorizationSnapshotTests` |
| 并发安全（服务端） | 差集替换不死锁；锁序「用户 → 角色 → 权限」；基线 409 阻止陈旧覆盖 | `RolePermissionServiceImplTests`、`RolePermissionAuditTransactionTests`；评审多轮双会话逻辑验证 |
| 并发安全（前端） | 程序化会话状态机：保存期间关闭/重开（同一或不同记录）的旧响应作废、基线不混用、连续保存后入口可用。**注意这是纯函数 + 源码断言（node 不渲染 Vue），不是浏览器端到端**；浏览器侧只验保存期控件禁用（§6 场景 4） | `permission-admin.test.js`（`isAssignSessionCurrent` / `isDialogSessionCurrent` / `shouldReleaseAssignSaving` / `permissionBaseline*` 等纯函数用例） |
| 菜单 / 路由权限一致（MENU 映射） | 无声明入口恒显；声明后**任一编码命中**即可见（组合页面）；空分组整组隐藏；未登录跳登录、已加载无权限跳 403、未就绪/加载失败放行不误判；**每次进入需权限页面（含已放行页面）都强制刷新快照**；**登录完成/令牌变化即初始化**（workspace 免权限）；**同页重复点击菜单直接刷新**（Vue Router `duplicated` 不过守卫）；权限加载失败（`unavailable`）时侧栏显示「权限加载失败，点击重试」（正常态不常驻额外按钮）；导航声明的权限码必须在 OpenAPI 权限目录内 | `menu-permission.test.js`（10 项：纯函数 6 + 组合页面并集 + 权限码与目录一致性 + 守卫/菜单/登录接线断言）；浏览器侧手工见 §6 场景 8、9 |

## 3. 关键设计说明

1. **授权快照**：`AuthorizationQueryService#getForUser` 每请求重算，`enabledRoleCodes` 仅含启用用户关联的启用角色；`permissionCodes` 在此基础上再过滤停用权限。用户不存在或已停用抛 UNAUTHORIZED，绝不返回可被误认为已认证的空快照。
2. **AOP 鉴权**：先经 A 的认证入口确认身份（无 Token/会话失效/用户停用均为 401），再由快照按编码匹配；`@RequirePermission` 任一编码命中即放行，全部未命中 403。
3. **审计接入**：`@AuditLog` 只能标在事务边界内、经 Spring 代理调用的方法。有幂等跳过路径的写操作才用 `RoleWriteAuditMarker` / `PermissionWriteAuditMarker`，**只在确实发生变更时**进入——启停「目标状态与当前不同且条件更新影响 1 行」、角色权限「差集非空」。免审计的仅限上述两类幂等跳过路径；`RoleAdminServiceImpl.update` / `PermissionAdminServiceImpl.update` 创建/修改类无跳过路径，直接标 `@AuditLog`，即使提交相同字段也执行更新并记录 SUCCESS。
   **失败审计的差异（文档口径与实现一致，勿混用）**：`permission.status` 与 `role.assign-permission` 把真实写入以 `Runnable` 传入受 `@AuditLog` 环绕的代理方法内部执行，切面先注册同步器再写库——**写库抛异常也能在 `afterCompletion` 用独立事务补记 FAILURE**。而 `role.status` 是先在服务方法内执行条件 `UPDATE`，成功后才调用空锚点 `roleStatusChanged()`：若该 `UPDATE` 本身抛 SQL 异常，切面从未进入、不会补记 `role.status`/FAILURE（业务未生效，属可接受省略）；`UPDATE` 成功但后续审计写入失败时，事务回滚并补记 FAILURE（由 `RoleAdminAuditTransactionTests` 覆盖）。
4. **ID 契约**：响应中业务 ID 一律字符串；路径/请求体 ID 只接受 `[1-9][0-9]{0,18}`、无前导零、≤ `Long.MAX_VALUE`；反序列化器对越界、null、非字符串元素一律 400，不落到 500。
5. **参数严格性**：未知 JSON 字段 400（`RolePermissionsUpdate` / `PermissionUpdate` 用自定义反序列化器显式拒绝）；名称/编码先按原始长度校验再去首尾空白并验证非空；`ContractWhitespace.strip` 识别 NBSP，`String.strip` 不认 NBSP 故禁用。MENU/BUTTON 的 `path` 是菜单路由：编辑必须原样提交数据库值（不 trim、不按 JS `.length` 复检），长度由后端按 Unicode 码点（≤255）裁决。
6. **分页**：统一由 `MybatisPlusConfig` 的 `PaginationInnerInterceptor`（`maxLimit = PageQuery.MAX_SIZE = 100`、`overflow = false`）追加；`keyword` 一律 `keyword == null ? null : ContractWhitespace.strip(keyword)`。
7. **并发安全 —— 服务端**：
   - 关联替换禁止「先 `DELETE WHERE role_id` 清空 + 整批 INSERT」（REPEATABLE READ 下空表两侧无匹配 DELETE 的间隙锁与插入意向锁互等 → 死锁 1213）。一律按完整复合主键做差集：只删 `current - target`、只插 `target - current`，空差集不发 DELETE。
   - 取锁顺序固定「操作人 `FOR SHARE` → 角色 `FOR UPDATE` → 权限行 `FOR SHARE`（按 ID 升序）」，与 A 的 `doReplaceRoles` 同序；操作人只用 S 锁，避免与角色编辑/审计外键形成锁环。
   - 全量替换请求新增**可选**字段 `expectedPermissionIds`（`IdList`，向后兼容）：服务端在角色行锁内读 `current` 后按集合比对，`expected != current` 立即 409，冲突不写库、不记审计。前端保存前重读只作快速失败，原子性由服务端保证。
8. **并发安全 —— 前端**：角色、权限项、分配权限三类弹窗各自维护会话序号（打开/关闭均换号，`flush: 'sync'`）；保存请求开始时冻结会话与记录 ID 快照，每个 await 后与成功点校验，旧响应只作用于原会话，不关闭刚打开的另一条记录弹窗；`shouldReleaseAssignSaving` 区分「本次成功关闭」与「被新会话接管」以正确清理 submitting/saving；保存期间 UI 禁用关闭/重开入口。
9. **权限状态门控**：前端进页面先拉 `/auth/permissions`，按 `idle/loading/loaded/unavailable` 四态门控；只有 `loaded` 才按本次权限编码判断，空权限不发起会被拒绝的列表请求；写操作成功后重载权限并刷新列表。
10. **MENU 权限与前端路由/菜单映射**（《接口与协作约定》3.4）：菜单入口在 `router/navigation.js` 声明 `requiredPermissions`（**数组，任一命中即可进入**），**复用 `x-permission-catalog` 的 API 编码**（不另建 MENU 权限项，避免两套码不同步）。**组合页面必须按子功能并集声明**：分类与标签 = `category:tree` 或 `tag:list`（只有标签权限也能进）、角色与权限 = `role:list` 或 `permission:list`（权限项管理员也能进）、岗位分配 = `user:list`/`user:read`/`user:update`/`department:tree`/`user:assign-role`、审核与发布 = `article:review-list`/`review:list`/`article:approve`/`article:reject`；页面内部再按子功能各自门控。`utils/menuPermission.js` 提供 `requiredCodes`（归一化）、`canSeeMenuItem`、`visibleNavigation`（组内全空整组隐藏）、`resolveMenuAccess`、`shouldRefreshOnNavigation`；路由 `meta` 与菜单同源，`beforeEach` 守卫拦直达 URL，未授权跳 `forbidden` 页。四条异步约束：① **每次进入声明了权限的业务页面（含已放行的页面）都 `refreshPermissions()` 强制刷新**（`shouldRefreshOnNavigation` 判定，`force` 忽略 `loaded`）——只在即将判 403 前刷新不够：旧快照已满足组合页入口条件时守卫不会刷新，会话期间新授予的子功能权限（如只有 `category:tree` 的账号新获 `tag:list`）永远读不到；权限接口只返回字符串数组、轻量，同令牌并发导航由 `inflight` 复用同一请求；② 未就绪（`idle`）与上次失败（`unavailable`）的首次加载/重试由同一刷新覆盖；③ 刷新后仍不可用则放行、由页面门控兜底（不把「不可用」当成「没有」）；④ 已加载无权限才跳 403。菜单侧：`loaded` 严格过滤、`unavailable` 展示全部并给出「权限加载失败，点击重试」入口。**⑤ 登录初始化与同页刷新**：`LoginView` 登录成功与 `AppLayout` 令牌 watch（`immediate`）都会 `ensurePermissions()`（刷新浏览器恢复令牌时同时调用 `ensureUser()` 恢复当前用户）——workspace 免权限、守卫不会为其刷新，否则登录后侧栏一直只显示工作空间；菜单点击用 `shouldRefreshOnSamePageClick` 识别「同页重复点击」（Vue Router 判 `duplicated`、不执行 `beforeEach`）直接 `refreshPermissions()`；正常状态不在侧栏常驻额外按钮，仅在加载失败（`unavailable`）时显示「权限加载失败，点击重试」。**前端隐藏只是展示一致（设计计划书 3.2 明确不构成鉴权），接口安全仍由 `@RequirePermission` 保证。**

## 4. 历史实现清单（B 模块此前各 PR 合入 dev 的 12 个文件）

> **本 PR（`docs/permission-delivery`）仅新增本文档 `docs/B模块交付与验收说明.md` 一个文件**，对运行时无任何影响。下表是 B 模块历史实现的文件清单（分属 PR #46 / #58 / #59 / #60 及 `feature/B/permission-frontend`，均已合入 dev），供定位代码与验收使用，**不是本 PR 的改动范围**；请勿按本 PR 的 GitHub 差异去查找下表文件。

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 前端 | `src/api/permission.js` | 透传 `expectedPermissionIds` |
| 前端 | `src/router/viewComponents.js` | 注册角色权限页映射（与 A 的 assignments 入口并存） |
| 前端 | `src/utils/rolePermission.js` | 基线签名/ID 数组、状态漂移、会话判定等纯函数 |
| 前端 | `src/views/role/RolePermissionView.vue` | 三类弹窗会话绑定、409 处理、写入后刷新、路径原样保留 |
| 前端 | `tests/permission-admin.test.js` | B 模块纯函数与接线断言（20 项） |
| 前端 | `tests/router-views.test.js` | 与 A 的 assignments 入口合并后的路由断言 |
| 后端 | `RolePermissionsUpdateRequest.java` | record 增加 `expectedPermissionIds`（可选，兼容旧调用） |
| 后端 | `RolePermissionsUpdateRequestDeserializer.java` | 白名单加新字段、复用 IdList 严格校验 |
| 后端 | `RolePermissionServiceImpl.java` | 角色行锁内基线校验，冲突 409 |
| 后端测试 | `RoleControllerHttpTests.java` | 基线透传、409、非法基线 400 |
| 后端测试 | `RolePermissionServiceImplTests.java` | 基线一致/撤销冲突/新增覆盖 |
| 契约 | `docs/api/enterprise-cms.openapi.json` | `RolePermissionsUpdate` 增加可选 `expectedPermissionIds` |

## 5. 可复现验证入口

| 入口 | 命令 | 前置条件 | 最近结果 |
| --- | --- | --- | --- |
| 前端全量 | `cd frontend && node --test tests/*.test.js` | Node 22+ | **126 / 126 通过**（2026-11-24 在 `feature/B/menu-permission` 分支复跑，含菜单/路由权限映射 10 项；`npm run build` 同期通过） |
| 后端全量 | `cd backend && $env:JAVA_HOME="D:\Java\jdk25"; & .\mvnw.cmd clean verify` | JDK 25、Docker MySQL/Redis、`SPRING_DATASOURCE_PASSWORD` | **754 / 754 通过，BUILD SUCCESS**（2026-11-23 在 `docs/permission-delivery` 分支本机复跑，日志留存于本机 `%TEMP%\ecms-verify-doc.log`；较功能 PR 时的 751 多出的 3 项来自 dev 上其他模块同期合入。**该校验不由文档 PR 自动执行，验收时请在本机重新跑一次**） |
| 角色权限定位测试 | `& .\mvnw.cmd "-Dtest=RolePermissionServiceImplTests,RoleControllerHttpTests,RolePermissionAuditTransactionTests" test` | 同上 | 85 项通过（56 + 22 + 7） |

> Windows 上 `JAVA_HOME` 必须在 PowerShell 内用 `$env:JAVA_HOME = "D:\Java\jdk25"` 设置；bash 里 `JAVA_HOME=D:\Java\jdk25 ./mvnw.cmd` 会报未定义正确。

## 6. 手工验收建议（双会话，真实登录态）

前置：`database/001_init.sql` + `database/002_seed_permission_baseline.sql` + `003/004` 已导入；Docker mysql8/redis 运行；后端 jar 启动；admin 账号登录取 `accessToken`。

| # | 场景 | 操作 | 期望 |
| --- | --- | --- | --- |
| 1 | 基础 CRUD | 管理员新建角色→编辑名称→启停→分页查询；新建权限项→编辑→启停 | 全部 200；审计日志有对应 SUCCESS 记录 |
| 2 | 分配与回显 | 给角色勾选若干权限保存，重开弹窗 | 回显勾选与保存一致；停用项显示「保存后移除」 |
| 3a | 并发撤销（P1）· **浏览器** | 会话甲、乙打开同一角色权限弹窗；甲撤销权限 X 保存；乙在弹窗内新增 Y 后点保存 | **前端保存前重读拦截**（`RolePermissionView.vue` 的 `getRolePermissions` + 基线比对）：乙看到「已被其他管理员修改，请重新加载」，**不发 PUT**；X 不被恢复。浏览器只能验到这一层拦截 |
| 3b | 并发撤销（P1）· **服务端 409**（Apifox） | 用 Apifox 直接 `PUT /api/admin/roles/{id}/permissions`。**必须先固定目标集合**：服务端在校验目标权限（存在 404 / 启用 409）**之后**才比对基线（`RolePermissionServiceImpl` 第 3 步 → 4.5 步），所以 `permissionIds` 要设为 **`[]`**（空集合不发 IN 查询，直接进入基线比对）或**明确存在且启用的权限 ID**，绝不能填任意/不存在的 ID。`expectedPermissionIds` 保留甲撤销 X 前读到的**过期旧集合**（如 `[X]`，而当前已为空） | 返回 **409**，message 为「角色权限已被其他管理员修改，请重新加载后再分配」（注意与「权限已停用，无法分配」的 409 区分，后者说明目标集合含停用项、本用例失败），且不写库、不记审计。此步单独验证服务端，浏览器因前端拦截看不到 409 |
| 4 | 会话重开（P2）· 控件禁用检查 | 打开任一编辑/分配弹窗并开始保存，观察保存期间的交互控件 | `RolePermissionView.vue` 在保存期间禁用「取消」「X 关闭」「ESC」「重新加载」及列表内其他记录的「编辑/新建/分配权限」入口（见 §3.8），**手工无法完成「取消并重开」**。验收时检查这些控件确实禁用；旧响应不误关新弹窗的行为由 `permission-admin.test.js` 的程序化会话状态机测试覆盖（非手工） |
| 5 | 连续保存 | 同一角色连续保存两次，再换另一角色保存 | 每次成功后按钮恢复可用，列表与当前权限刷新 |
| 6 | 只读账号 | 验收账号需**同时具备 `role:list` 与 `role:read-permission`，且不具备 `permission:list` 及角色/权限编辑类权限**（`role:list` 必给：缺它时角色列表被清空、分配弹窗入口位于角色行内，根本打不开；`role:read-permission` 用于读已分配） | 角色列表可见；打开分配弹窗只展示已分配表格、不发目录请求、保存按钮禁用；编辑相关权限仍不可用 |
| 7 | 401 / 403（**自动化未逐接口覆盖，必须手工**） | 依次不带 Token 请求 12 个接口中的管理端点（如 `GET /api/admin/roles`、`GET /api/admin/permissions`、`PUT /api/admin/roles/{id}/permissions`）；再用缺 `role:list` 的普通账号请求角色分页 | 分别 401（「请先登录」）与 403。自动化仅覆盖过滤器层的通用认证与 `/api/auth/permissions` 匿名用例，12 个接口的逐接口 401 需手工确认 |
| 8 | 菜单与直达 URL 的权限一致（MENU 映射） | 分别用 ADMIN / EDITOR / REVIEWER / 普通员工登录，查看左侧菜单；再手动在地址栏直达一个当前账号无权限的页面（如编辑账号直达 `/admin/roles`）。**重点补两个组合页**：只有 `tag:list` 的账号进 `/admin/categories`、只有 `permission:list` 的账号进 `/admin/roles` | 菜单只显示该账号有权限的入口（工作空间恒显）；组合页在任一子功能权限下可见且页面自动切到有权限的 Tab；无权限直达被重定向到 403 页；ADMIN 菜单齐全 |
| 9 | 权限快照的异步边界 | ① **正常登录**（跳 workspace，免权限）后直接看侧栏；② 用只有 `category:tree` 的账号登录，管理员在会话期间授予 `tag:list`，**停留在本页**、再点一次当前「分类与标签」菜单或刷新浏览器；③ 管理员给当前账号授予 `role:list`，直接点「角色与权限」；④ 断开后端后进入任意管理页再恢复，点侧栏底部「权限加载失败，点击重试」入口 | ① 登录后侧栏即显示已授权业务菜单（登录/令牌变化初始化权限）；② 同页重复点击触发刷新（Vue Router `duplicated` 不过守卫，由菜单 click 直接刷新）或刷新浏览器恢复会话并重载权限，「标签」Tab 出现；③ 首次点击即进入，不被旧权限挡在 403；④ 恢复后点击重试可正常加载菜单，成功后重试入口自动隐藏 |

## 7. 已知边界与注意

- 种子数据：`002_seed_permission_baseline.sql` 含 4 个角色、默认权限基线；若目标库未导入，管理员账号可能无权限码，需先按脚本导入。
- 单测为 Mockito 桩、不连真实库，**真实锁行为（间隙锁、锁序、死轮询）仍建议在 MySQL 8.4 上按双会话 SQL 复核**。
- 前端会话/并发测试为纯函数 + 源码静态断言（node 环境不渲染 Vue），交互层建议按 §6 手工复核。
- A 模块的 `doReplaceRoles` 仍为「先删后插」，理论上存在同类间隙锁风险；不在 B 的 PR 范围，改动需先提方案确认。
