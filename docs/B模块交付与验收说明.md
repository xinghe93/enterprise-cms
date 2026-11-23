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
| 匿名 401 | 12 个接口未带 Token / Token 失效 / 用户停用 | `RoleControllerHttpTests`、`PermissionControllerHttpTests`、`RequirePermissionAspectTests` |
| 无权限 403 | 缺对应权限码访问各管理接口；`@RequirePermission` 任一命中即放行、全未命中 403 | `RequirePermissionAspectTests`（含缺码/停用权限场景） |
| 有权限 200 | 管理员走通角色/权限项 CRUD、启停、分配 | `RoleControllerHttpTests`（56 项）、`PermissionControllerHttpTests`（18 项） |
| 参数 400 | ID 非十进制/前导零/0/超 Long；未知字段；DTO 空白（含 NBSP U+3000）；`PermissionUpdate` 严格反序列化器；`expectedPermissionIds` 非数组/非法元素 | `RoleControllerHttpTests`、`PermissionControllerHttpTests`、`AuthorizationSnapshotTests`；`ContractWhitespace` 单测 |
| 不存在 404 | 角色不存在、权限项不存在 | Controller / Service 单测 |
| 状态冲突 409 | 请求含停用权限；编码/名称重复；**并发基线 `expectedPermissionIds` 与当前集合不一致** | `RolePermissionServiceImplTests`（基线不一致 409）、`RoleControllerHttpTests`（409 映射） |
| 审计成功 / 失败 | 写操作 SUCCESS 随事务提交；写失败回滚后 `afterCompletion` 独立事务补 FAILURE；幂等路径不记审计 | `RoleAdminAuditTransactionTests`、`PermissionAdminAuditTransactionTests`、`RolePermissionAuditTransactionTests` |
| 授权快照一致性 | 停用角色/权限不计入；启用用户无角色时双集合为空；用户不存在/停用抛 UNAUTHORIZED | `AuthorizationQueryServiceImplTests`、`AuthorizationSnapshotTests` |
| 并发安全（服务端） | 差集替换不死锁；锁序「用户 → 角色 → 权限」；基线 409 阻止陈旧覆盖 | `RolePermissionServiceImplTests`、`RolePermissionAuditTransactionTests`；评审多轮双会话逻辑验证 |
| 并发安全（前端） | 三类弹窗会话绑定；保存中取消/重开同一或不同记录；连续保存入口可用 | `permission-admin.test.js` 纯函数 + 静态源码断言（前端 20 项） |

## 3. 关键设计说明

1. **授权快照**：`AuthorizationQueryService#getForUser` 每请求重算，`enabledRoleCodes` 仅含启用用户关联的启用角色；`permissionCodes` 在此基础上再过滤停用权限。用户不存在或已停用抛 UNAUTHORIZED，绝不返回可被误认为已认证的空快照。
2. **AOP 鉴权**：先经 A 的认证入口确认身份（无 Token/会话失效/用户停用均为 401），再由快照按编码匹配；`@RequirePermission` 任一编码命中即放行，全部未命中 403。
3. **审计接入**：`@AuditLog` 只能标在事务边界内、经 Spring 代理调用的方法。角色/权限项写操作与「更新」类无状态变更路径用 `RoleWriteAuditMarker` / `PermissionWriteAuditMarker`：把真实写入以 `Runnable` 传入被 `@AuditLog` 环绕的代理方法，校验通过与差集非空才进入；写失败由切面 `afterCompletion` 用独立事务补记 FAILURE，不再漏记。
4. **ID 契约**：响应中业务 ID 一律字符串；路径/请求体 ID 只接受 `[1-9][0-9]{0,18}`、无前导零、≤ `Long.MAX_VALUE`；反序列化器对越界、null、非字符串元素一律 400，不落到 500。
5. **参数严格性**：未知 JSON 字段 400（`RolePermissionsUpdate` / `PermissionUpdate` 用自定义反序列化器显式拒绝）；名称/编码先按原始长度校验再去首尾空白并验证非空；`ContractWhitespace.strip` 识别 NBSP，`String.strip` 不认 NBSP 故禁用。MENU/BUTTON 的 `path` 是菜单路由：编辑必须原样提交数据库值（不 trim、不按 JS `.length` 复检），长度由后端按 Unicode 码点（≤255）裁决。
6. **分页**：统一由 `MybatisPlusConfig` 的 `PaginationInnerInterceptor`（`maxLimit = PageQuery.MAX_SIZE = 100`、`overflow = false`）追加；`keyword` 一律 `keyword == null ? null : ContractWhitespace.strip(keyword)`。
7. **并发安全 —— 服务端**：
   - 关联替换禁止「先 `DELETE WHERE role_id` 清空 + 整批 INSERT」（REPEATABLE READ 下空表两侧无匹配 DELETE 的间隙锁与插入意向锁互等 → 死锁 1213）。一律按完整复合主键做差集：只删 `current - target`、只插 `target - current`，空差集不发 DELETE。
   - 取锁顺序固定「操作人 `FOR SHARE` → 角色 `FOR UPDATE` → 权限行 `FOR SHARE`（按 ID 升序）」，与 A 的 `doReplaceRoles` 同序；操作人只用 S 锁，避免与角色编辑/审计外键形成锁环。
   - 全量替换请求新增**可选**字段 `expectedPermissionIds`（`IdList`，向后兼容）：服务端在角色行锁内读 `current` 后按集合比对，`expected != current` 立即 409，冲突不写库、不记审计。前端保存前重读只作快速失败，原子性由服务端保证。
8. **并发安全 —— 前端**：角色、权限项、分配权限三类弹窗各自维护会话序号（打开/关闭均换号，`flush: 'sync'`）；保存请求开始时冻结会话与记录 ID 快照，每个 await 后与成功点校验，旧响应只作用于原会话，不关闭刚打开的另一条记录弹窗；`shouldReleaseAssignSaving` 区分「本次成功关闭」与「被新会话接管」以正确清理 submitting/saving；保存期间 UI 禁用关闭/重开入口。
9. **权限状态门控**：前端进页面先拉 `/auth/permissions`，按 `idle/loading/loaded/unavailable` 四态门控；只有 `loaded` 才按本次权限编码判断，空权限不发起会被拒绝的列表请求；写操作成功后重载权限并刷新列表。

## 4. 本 PR 改动范围（相对 origin/dev 共 12 个文件）

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
| 前端全量 | `cd frontend && node --test tests/*.test.js` | Node 22+ | **109 / 109 通过**（2026-11-23） |
| 后端全量 | `cd backend && $env:JAVA_HOME="D:\Java\jdk25"; & .\mvnw.cmd clean verify` | JDK 25、Docker MySQL/Redis、`SPRING_DATASOURCE_PASSWORD` | **751 / 751 通过，BUILD SUCCESS**（2026-11-23） |
| 角色权限定位测试 | `& .\mvnw.cmd "-Dtest=RolePermissionServiceImplTests,RoleControllerHttpTests,RolePermissionAuditTransactionTests" test` | 同上 | 85 项通过 |

> Windows 上 `JAVA_HOME` 必须在 PowerShell 内用 `$env:JAVA_HOME = "D:\Java\jdk25"` 设置；bash 里 `JAVA_HOME=D:\Java\jdk25 ./mvnw.cmd` 会报未定义正确。

## 6. 手工验收建议（双会话，真实登录态）

前置：`database/001_init.sql` + `database/002_seed_permission_baseline.sql` + `003/004` 已导入；Docker mysql8/redis 运行；后端 jar 启动；admin 账号登录取 `accessToken`。

| # | 场景 | 操作 | 期望 |
| --- | --- | --- | --- |
| 1 | 基础 CRUD | 管理员新建角色→编辑名称→启停→分页查询；新建权限项→编辑→启停 | 全部 200；审计日志有对应 SUCCESS 记录 |
| 2 | 分配与回显 | 给角色勾选若干权限保存，重开弹窗 | 回显勾选与保存一致；停用项显示「保存后移除」 |
| 3 | 并发撤销（P1） | 会话甲、乙打开同一角色权限弹窗；甲撤销权限 X 保存；乙在弹窗内新增 Y 保存 | 乙收到 409 与「已被其他管理员修改，请重新加载」；X 不被恢复 |
| 4 | 会话重开（P2） | 弹窗保存请求挂起期间取消，打开另一条记录并填写；旧响应返回 | 新弹窗不被关闭、草稿不丢失；无成功提示误弹 |
| 5 | 连续保存 | 同一角色连续保存两次，再换另一角色保存 | 每次成功后按钮恢复可用，列表与当前权限刷新 |
| 6 | 只读账号 | 用只有 `role:read-permission`、无 `permission:list` 的账号打开分配弹窗 | 只展示已分配表格、不发目录请求、保存按钮禁用 |
| 7 | 401 / 403 | 匿名访问管理接口；普通员工访问角色分页 | 分别 401 / 403 |

## 7. 已知边界与注意

- 种子数据：`002_seed_permission_baseline.sql` 含 4 个角色、默认权限基线；若目标库未导入，管理员账号可能无权限码，需先按脚本导入。
- 单测为 Mockito 桩、不连真实库，**真实锁行为（间隙锁、锁序、死轮询）仍建议在 MySQL 8.4 上按双会话 SQL 复核**。
- 前端会话/并发测试为纯函数 + 源码静态断言（node 环境不渲染 Vue），交互层建议按 §6 手工复核。
- A 模块的 `doReplaceRoles` 仍为「先删后插」，理论上存在同类间隙锁风险；不在 B 的 PR 范围，改动需先提方案确认。
