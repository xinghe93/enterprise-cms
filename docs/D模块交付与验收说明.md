# D 模块交付与验收说明（审核 / 发布 / 操作日志）

> 适用对象：组长验收、组员联调。
> 维护人：D 同学（杨朋骏）。最后更新：2026-11-22。

## 1. 模块范围

| 项 | 内容 |
| --- | --- |
| 数据表（3 张） | `cms_review`（审核记录）、`cms_article_tag`（文章-标签关联）、`sys_operation_log`（操作日志/审计） |
| 跨模块服务 | `com.xinghe.cms.review.service.ArticleTagService`（`replaceBindings`/`findByArticleIds`，MANDATORY 事务）、`com.xinghe.cms.log.annotation.AuditLog` + 审计切面 |
| 依赖的他人交付 | A：`CurrentUserProvider`/登录与 Token；B：`@RequirePermission`/`AuthorizationQueryService`；C：`ArticleWorkflowService`/`ArticleAccessService`/`ArticleSnapshot`/`ArticleState`/`TagView`/分类与文章查询 |

### 1.1 接口清单

| # | 方法 | 路径 | 权限码 | 所属 PR/分支 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/api/admin/logs` | `log:list` | #35（后由 #52 统一注解） |
| 2 | GET | `/api/admin/logs/{id}` | `log:read` | #35（后由 #52 统一注解） |
| 3 | GET | `/api/admin/articles/{id}/reviews` | `review:list` | #30 |
| 4 | POST | `/api/admin/articles/{id}/approve` | `article:approve` | #30 |
| 5 | POST | `/api/admin/articles/{id}/reject` | `article:reject` | #30 |
| 6 | POST | `/api/admin/articles/{id}/publish` | `article:publish` | #30 |
| 7 | POST | `/api/admin/articles/{id}/offline` | `article:offline` | #30 |
| 8 | POST | `/api/admin/articles/batch/publish` | `article:publish-batch` | #30 |
| 9 | POST | `/api/admin/articles/batch/offline` | `article:offline-batch` | #30 |
| 10 | GET | `/api/admin/reviews/pending` | `article:review-list` | #44 |
| 11 | PUT | `/api/admin/articles/{id}/tags` | `article:bind-tag` | #57（已合入 dev `920c1d8`） |

> 标签查询（`GET /api/admin/tags` 等）属 C 的标签字典模块，D 的审核/日志页按契约复用，不重复实现。

## 2. 验收矩阵

| 场景 | 覆盖用例 | 证据 |
| --- | --- | --- |
| 匿名 401 | 日志列表/详情、审核/发布命令入口 | `OperationLogPermissionTests`（2 项）；e2e `logs-anonymous-401`、`log-detail-anonymous-401` |
| 无权限 403 | 普通员工读日志（`log:list`/`log:read`）、editor 审批、editor 对待审列表 | `OperationLogPermissionTests`（4 项）；e2e `logs-editor-403`、`log-detail-editor-403`、`logs-staff-403`、`pending-editor-403`；PR #30 Controller 测试反射断言 6 个权限码 |
| 有权限 200 | 管理员日志列表/详情、待审列表、审核历史 | e2e `logs-admin-200`、`pending-admin-200-excludes-own`、`pending-filter-author-200`；`ReviewQueryServiceMysqlIT` |
| 参数 400 | size 越界、result 非法、时间区间倒置、未知参数、详情 ID 非十进制/前导零/0/超 Long、`operation` 超 100 | `OperationLogControllerTests`、`OperationLogQueryTests`；e2e `logs-size-101-400` 等 6 条 |
| 状态冲突 409 | 旧版本审批、重复标签名 | `ReviewDecisionServiceTests`、`ArticleTagServiceTests`；e2e `tag-create-duplicate-conflict`（FAILURE 审计）、`approve-stale-version-409` |
| 不存在 404 | 日志详情不存在、文章不存在、标签不存在 | `OperationLogServiceTests`、`ReviewQueryServiceTests`、`ArticleTagServiceTests`；e2e `log-detail-404` |
| 审计成功/失败 | `tag.create` 成功写 SUCCESS（userId、requestUri 只路径）、重名 409 写 FAILURE | e2e `audit-success-recorded-with-operator-and-path`、`audit-failure-recorded`；`AuditLogTransactionProxyTests`（真实 Spring 事务代理：成功随提交、失败回滚业务） |
| 跨模块 MANDATORY | `replaceBindings` 必须在事务内、`IllegalTransactionStateException` | `ArticleTagServiceImplTransactionTests` |

## 3. 关键设计说明

1. **审计接入位置**：`@AuditLog` 只能标在事务边界内、经 Spring 代理被调用的方法（Controller → `@Transactional` Service → 独立审计 bean，或审计 bean 方法标 `@Transactional(MANDATORY)`）；禁止标在 Controller（事务尚未开启，会走无事务分支，业务提交后写 SUCCESS 失败也不回滚）。审核/发布均抽为 `ReviewAuditRecorder`/`PublishAuditRecorder`，被审计方法内部覆盖"状态流转 + 审核记录写入"，流转异常也落在切面内记 FAILURE。
2. **事务语义**：有外层事务时成功日志在 `beforeCommit` 随业务事务写入、回滚一起撤销；业务回滚或方法抛异常时在 `afterCompletion` 用 `REQUIRES_NEW` 独立写 FAILURE。
3. **操作人**：从 A 的 `CurrentUserProvider.requireCurrentUser()` 取，不接受请求参数 `userId`（伪造参数测试证明无效）。
4. **ID 契约**：JSON 响应中业务 ID 一律字符串；路径/查询 ID 只接受 1–19 位正十进制、无前导零、≤ `Long.MAX_VALUE`（溢出 400「请求参数错误」/「资源 ID 格式不正确」）。
5. **分页与 LIMIT**：统一由 `MybatisPlusConfig` 的 `PaginationInnerInterceptor`（`maxLimit=PageQuery.MAX_SIZE=100`、`overflow=false`）追加，XML 不手写 LIMIT；service 层再做参数校验。
6. **参数严格性**：未知查询参数 400（日志列表 7 个白名单、详情 0 个；待审 5 个）；时间只接受本地格式 `yyyy-MM-dd'T'HH:mm:ss[.SSS]`（带 `Z` 或偏移 400）；关键词先按原始长度校验（≤255 code points）再 strip 与转义（`!`/`%`/`_`）。

## 4. 可复现验证入口

| 入口 | 命令/文件 | 前置条件 | 最近结果 |
| --- | --- | --- | --- |
| 日志模块单测 | `mvn -B -o clean test "-Dtest=com.xinghe.cms.log.**"` | JDK25、`SPRING_DATASOURCE_PASSWORD` | **63 项全过** |
| 审核模块单测 | `mvn -B -o clean test "-Dtest=com.xinghe.cms.review.**"` | 同上 + Docker MySQL（含 IT） | **170 项全过**（#30、#56、#57 均已合入 dev） |
| 全量后端测试 | 完整命令见 §4.1（自动生成 Mockito 排除项） | JDK25、Docker MySQL、`SPRING_DATASOURCE_PASSWORD` | **321 项全过**（dev `c834385` 实测，2026-11-22，含 #57 标签绑定） |
| 真实登录态手工验收 | 可直接执行的步骤见 §4.2 | **前置条件：`database/005_seed_review_log_demo.sql`（#53）已导入目标库**；Docker mysql8/redis、jar、admin/editor/staff 账号 | 2026-11-22 用临时审核员账号走通登录→权限→待审→正文→通过/驳回→审核历史（证据 `项目指导/evidence/reviewer_flow_20261122_085611.txt`），随后在隔离库复核种子（`docs/操作日志说明.md` §9.2） |
| 真实登录态 e2e 脚本 | 仓库外脚本 `课程设计/项目指导/scripts/run_e2e_review_log.ps1`（需本机 e2e 账号密码，按要求不入库） | Docker mysql8/redis、`005` 种子、jar、`SPRING_DATASOURCE_PASSWORD` 与账号密码 | **35 PASS / 0 FAIL / 0 SKIP**（2026-11-21，含审核历史、审核/发布命令、标签绑定；证据 `e2e_review_log_20261121_001357.txt`） |
| 压测 | 仓库外脚本 `run_pressure_logs.ps1` | 同上 | PASS，见 §5 |

> 本机完整 `mvn verify` 会因宿主机不允许外部进程 attach 而在 A/B/C 的 Mockito 测试类上报 `MockMaker` 失败（非 D 模块问题），§4.1 给出了可复现的排除方式。

### 4.1 全量后端测试（团队可直接执行）

本机（Windows + JDK25）不允许 Mockito 自 attach，需排除“引用了 Mockito 的测试类”。下面命令自动生成排除项，不需要手工维护类名清单：

```powershell
cd D:\大三第一学期\JAVA框架技术（一）\课程设计\enterprise-cms\backend
$env:JAVA_HOME = "E:\Java\jdk-25.0.5.0.3"
$env:SPRING_DATASOURCE_PASSWORD = "<本机 MySQL root 密码>"

# 生成排除项：测试源码中出现 org.mockito / @MockitoBean 的类，转成 surefire 的 !全限定名
$ex = (Get-ChildItem src/test/java -Recurse -Filter *.java |
        Select-String -Pattern 'org\.mockito|@MockitoBean' |
        ForEach-Object { $_.Path } | Sort-Object -Unique |
        ForEach-Object {
            $rel = $_ -replace [regex]::Escape((Resolve-Path 'src/test/java').Path + '\'), ''
            '!' + ($rel -replace '\\', '.' -replace '\.java$', '')
        }) -join ','

mvn -B -o clean verify "-Dtest=$ex" "-Dsurefire.failIfNoSpecifiedTests=false"
```

结果应为 **Tests run: 321, Failures: 0, Errors: 0**。在允许 attach 的机器或 CI 上可去掉 `-Dtest` 直接 `mvn verify`，A/B/C 的 Mockito 测试类会一起执行。

### 4.2 手工验收步骤（真实登录态）

前置条件：`database/005_seed_review_log_demo.sql`（PR #53）已导入目标库；种子在隔离库的导入命令与幂等复核见 `docs/操作日志说明.md` §9.2。种子使用固定 ID：文章 `990301`–`990305`、审核历史 `990601`/`990602`。

```powershell
# 1) 启动后端（Docker mysql8/redis 已运行，001–005 已导入目标库）
cd D:\大三第一学期\JAVA框架技术（一）\课程设计\enterprise-cms\backend
# 当前 pom 版本 0.0.1-SNAPSHOT、未覆盖 finalName，实际产物为 enterprise-cms-backend-0.0.1-SNAPSHOT.jar
$jar = (Get-ChildItem target\enterprise-cms-backend-*.jar | Select-Object -First 1).Name
$env:JAVA_HOME="E:\Java\jdk-25.0.5.0.3"; $env:SPRING_DATASOURCE_PASSWORD="<root 密码>"; $env:TZ="Asia/Shanghai"
java -jar "target\$jar"   # 默认端口 8080

# 2) 登录拿 Token（字段是 accessToken；账号密码由本机提供，不写入仓库）
$login = Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/auth/login -ContentType 'application/json' `
  -Body '{"username":"admin","password":"<admin 密码>"}'
$h = @{ Authorization = "Bearer $($login.data.accessToken)"; 'Content-Type' = 'application/json' }

# 3) 标签 ID 取自标签列表（分页结构在 data.records，ID 为字符串）
$tagIds = @((Invoke-RestMethod -Method Get http://127.0.0.1:8080/api/admin/tags -Headers $h).data.records |
  Select-Object -First 2 | ForEach-Object { $_.id })
```

| 步骤 | 请求 | 期望 |
| --- | --- | --- |
| 匿名访问 | `GET /api/admin/logs` 不带 Token | 401「请先登录」 |
| 当前用户权限 | `GET /api/auth/permissions`（带 Token） | 200，返回权限码数组 |
| 待审列表 | `GET /api/admin/reviews/pending?current=1&size=10` | 200，含 `990301`/`990302` |
| 查看正文 | `GET /api/admin/articles/990301` | 200，`content` 非空 |
| 审核通过 | `POST /api/admin/articles/990301/approve`，body `{"version":0}` | 200，状态 `APPROVED`、`version=1` |
| 审核历史 | `GET /api/admin/articles/990301/reviews` | 200，含当前审核人记录 |
| 审核驳回 | `POST /api/admin/articles/990302/reject`，body `{"version":0,"comment":"演示驳回"}` | 200，状态 `REJECTED` |
| 标签绑定 | `PUT /api/admin/articles/990305/tags`，body `{"version":1,"tagIds":["<tagIdA>","<tagIdB>"]}` | 200，状态仍为 `REJECTED`、`version=2`，`tagIds` 升序；990305 初始为 `REJECTED`，而 BIND_TAGS 只允许 `DRAFT`/`REJECTED`/`OFFLINE` |
| 发布/下架 | `POST /api/admin/articles/990303/publish`，body `{"version":1}` → `PUBLISHED`/`version=2`；再 `POST .../offline` body `{"version":2}` → `OFFLINE`/`version=3` | 均 200；每一步都用上一步返回的 `version` |
| 日志查询 | `GET /api/admin/logs?current=1&size=10` | 200，能看到上述 `article.approve`/`article.reject` 等 SUCCESS 记录 |
| 普通员工越权 | 用 `staff` 账号登录后 `GET /api/admin/logs` | 403「缺少访问权限: log:list」 |
| 未知参数 | `GET /api/admin/logs?foo=1` | 400「请求参数错误」 |

> 版本约定：审核裁决、发布/下架、标签绑定都会自增 `version`，后续步骤必须使用**接口返回的新 `version`**；如果沿用种子里的旧版本会得到 409。

**复验记录（2026-11-22）：** 在 Docker mysql8 的隔离库 `enterprise_cms_e2e` 上按本节顺序实测（真实 `ADMIN` 与 `EMPLOYEE` 账号、真实 HTTP 请求），16 项检查全部 PASS、0 FAIL；其中包括标签绑定落在 `REJECTED` 的 `990305` 上返回 `version=2`、`990303` 发布后再用新版本下架成功、审计日志记录 `article.approve`/`article.reject`/`article.bind-tag`/`article.publish`/`article.offline` 五类 SUCCESS。原始输出见仓库外 `项目指导/evidence/e2e_reviewer_isolated_20261122_150929.txt`。

## 5. 压测结果（2026-11-19）

环境：Windows PowerShell 5.1 / JDK25 / MySQL 8.4（Docker，同机部署）/ 数据量 article=4、review=2、log=0（004 种子不写成功日志，日志查询压测的是空表链路的固定开销）/ 每接口预热 20 次、实测 200 次 / 并发 1 与 4。

| 接口 | 并发=1 平均(ms) | p95 | p99 | max | 错误 | 并发=4 平均 | p95 | p99 | max | 错误 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /api/admin/logs?size=10` | 16.8 | 22.2 | 35.3 | 40.2 | 0 | 15.6 | 23.6 | 42.4 | 54.9 | 0 |
| `GET /api/admin/logs?size=50&result=SUCCESS` | 13.3 | 17.0 | 25.4 | 36.9 | 0 | 17.6 | 50.3 | 68.4 | 131.1 | 0 |
| `GET /api/admin/reviews/pending?size=10` | 16.6 | 25.5 | 40.2 | 50.6 | 0 | 17.0 | 20.5 | 22.1 | 38.4 | 0 |
| `GET /api/admin/articles/{id}/reviews?size=10` | 待重跑（审核历史接口已随 #56 合入 dev） | — | — | — | — | — | — | — | — | — |

判定：**PASS**（错误数 0；并发=1 时各接口 p95 均 < 300ms）。

说明：数据量很小，结果只证明接口自身开销（鉴权 + 参数校验 + 分页 SQL）稳定；#30、#56、#57 均已合入 dev，标签绑定接口已在 dev 上具备，正式压测待演示数据充足后统一重跑，并把审核/发布写接口、审核历史与标签绑定纳入。

## 6. 已知限制与待办

| 项 | 现状 | 待办 |
| --- | --- | --- |
| 日志接口权限实现 | `@RequirePermission("log:list")`/`@RequirePermission("log:read")`（PR #52 已合入 dev，旧 `LogPermissionGuard` 已删除），403 文案「缺少访问权限: …」 | 无待办 |
| 审核/发布命令 | 已随 PR #30 合入 dev（`78b7174`），含 `@RequirePermission` 与事务内审计 bean；e2e 已 PASS | 重跑压测纳入 |
| 审核历史查询接口 | 已随 PR #56 合入 dev（`ed620c3`），`GET /api/admin/articles/{id}/reviews` 可用（e2e PASS） | 无待办；压测纳入 |
| 标签绑定接口 | 已随 PR #57 合入 dev（`920c1d8`），`PUT /api/admin/articles/{id}/tags` 可用；严格 JSON 类型（`5a7ede6`）与重复标签 ID 前置校验（`c394feb`）已按复审补齐 | 无待办；e2e/压测纳入 |
| 前端权限门控 | 前端日志页/审核页已从 `GET /api/auth/permissions` 取权限并做严格门控（#34 已合入 dev `17ba15d`），加载失败提供重试 | 无待办；该接口契约 owner=B，D 侧临时接线已获组长复审确认 |
| 演示种子 | `005_seed_review_log_demo.sql` 在 PR #53（`feature/D/e2e-seed`）；已按复审改为不写 `USE`、固定 ID（文章 990301–990305），关联数据只引用身份匹配的演示文章；隔离库连续导入两次 0 行变化，同名/ID 冲突时跳过关联（复核见 `docs/操作日志说明.md` §9.2） | 合并后可直接导入；e2e/压测均以该种子为前置条件 |
| 本机 Mockito | JDK25 + Windows 不允许外部 attach | 加 javaagent 或 CI 指定 JDK |
| 日志数据范围 | 全局审计，首期按权限码全量可读 | 若要求部门级范围需另行评审 |

## 7. 相关提交（关键节点）

`5bbf940`（日志实体/Mapper）→ `f1aa97c`（查询 DTO/XML/Service）→ `18612f5`（日志接口）→ `bbaef92`（审计切面）→ `724d600`（日志文档/Apifox）→ `bb5ad44`（审计事务语义与参数契约）→ `20dfab6`（复审修正）→ `b1c73f3`（操作人接入）→ `2a98125`（ArticleTagService）→ `7db8f24`/`cbd9ddb`/`24a1954`/`a6cf468`（标签服务复审修正）→ `b5be056`（审核历史）→ `9c6ec67`（审核与发布命令）→ `3edbcf2`/`e15abc9`（审核复审修正）→ `5392416`/`b32a0ab`（发布/下架）→ `3475cf8`（审核历史外连接与写入校验）→ `567e45c`（日志权限改走注解）→ `0a9a9f9`（演示种子与登录态验收）→ `1030b50`/`e1af3a1`/`3fddf29`（审核历史接口，已合入 `ed620c3`）→ `0ac8cb0`（标签绑定接口）→ `5a7ede6`/`c394feb`（严格 JSON 类型与重复标签 ID 前置校验）→ PR #57 合入 dev `920c1d8`。
