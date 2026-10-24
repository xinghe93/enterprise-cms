# EnterpriseCMS 企业内部知识与内容管理平台

**《JAVA 框架技术（一）》课程项目设计计划书 · V1.0 · 2026-10-20**

技术栈：Spring Boot、MySQL、MyBatis-Plus、Apifox、Vue 3、Element Plus；测试扩展：Redis、JMeter。


## 一、项目概述与建设目标

### 1.1 项目定位
EnterpriseCMS 是面向中小型企业内部员工的知识与内容管理平台，以“员工身份—文章分类—编辑提交—审核发布—知识检索—操作审计”为主要业务闭环。系统既保留实验二 EMP 员工管理 MIS 的业务基础，也覆盖课程评分表对企业级 CMS 的文章、分类、审核、权限、检索和日志要求。

本项目不是大型 ERP。工资核算、仓储采购、客户订单、复杂组织审批等不纳入首期范围，以保证四人能够完成端到端开发、接口联调和性能测试。


### 1.2 建设目标
- 业务目标：让企业员工统一创建、审核、发布和查找内部公告、规章制度、技术文档及培训资料。
- 技术目标：完成 Spring Boot 分层架构、MyBatis-Plus CRUD 与高级查询、AOP 权限控制、统一异常处理和 RESTful 接口。
- 协作目标：四人各承担 3 张主要责任表，至少独立完成 2 个可演示的完整业务功能；每人涉及单表与多表查询。
- 测试目标：使用 Apifox 完成功能与接口验证，使用 Redis 对高频查询做缓存实验，使用 JMeter 做分级压力测试，并完成 SQL 优化前后对比。
- 交付目标：可启动的前后端源代码、数据库脚本、接口文档、需求分析、E-R 图、业务流程图、测试报告、AI 使用与修改记录。


### 1.3 目标用户及角色
| 角色 | 典型使用场景 | 权限边界 |
|---|---|---|
| 普通员工 | 登录门户，按权限检索和阅读已发布知识文章 | 不可创建、审核或发布文章 |
| 内容编辑 | 编写文章、管理草稿、提交审核 | 只能修改有权管理的文章，不可越权审核 |
| 审核人员 | 浏览待审核内容，填写审核意见并作出决定 | 仅审核具有审核权限及数据范围内的内容 |
| 系统管理员 | 维护员工、部门、角色权限和基础配置 | 关键操作记录审计日志 |


## 二、需求范围与核心业务场景

### 2.1 四个业务子系统
| 子系统 | 必须实现的业务 | 展示页面 |
|---|---|---|
| 人员与组织 | 账号登录退出、员工 CRUD/启停、部门维护、用户角色分配 | 登录页、员工列表、部门管理 |
| 权限与安全 | 角色 CRUD、权限分配、AOP 接口鉴权、异常处理 | 角色列表、权限配置 |
| 知识内容 | 文章草稿、分类树、标签管理、分页模糊查询、文章详情 | 知识门户、文章列表、编辑页、分类/标签页 |
| 审核与运维 | 提交审核、审核通过/驳回、发布/下架、审核历史、操作日志 | 待审核列表、审核详情、日志列表 |


### 2.2 MVP（第一阶段必须交付）
- 登录/退出、四类角色可用、员工与部门管理、角色和接口权限生效。
- 分类新增/编辑/排序/启停；文章创建、草稿保存、编辑、分页检索、逻辑删除、批量发布或下架。
- 作者提交审核；审核员通过或驳回；审核通过的内容由具备发布权限者发布；保留完整审核记录。
- 文章标签绑定；门户仅展示当前用户可访问的已发布文章；关键变更写入操作日志。
- 每人完成单表 + 多表查询、至少 2 个完整业务功能，以及 Apifox 测试用例。


### 2.3 可选扩展（不影响第一阶段验收）
部门级文章可见范围、热门文章排行榜、统计图表、附件上传、文章版本回滚等可按进度择一实现。复杂多级工作流、搜索引擎和微服务暂不引入。


## 三、核心业务流程与规则

### 3.1 内容全生命周期
> **文章状态流转**  DRAFT（草稿）→ PENDING_REVIEW（待审核）→ APPROVED（审核通过）→ PUBLISHED（已发布）→ OFFLINE（已下架）。审核拒绝时进入 REJECTED（已驳回），由作者修改后重新提交。

1. 内容编辑创建文章，选择分类、标签并保存为草稿；草稿只对作者及具管理权限的员工可见。
2. 编辑提交审核后，文章状态变为 PENDING_REVIEW；普通编辑不得直接将其设为 PUBLISHED。
3. 审核人员查看待审核列表，写入 cms_review 审核记录，并将文章状态变为 APPROVED 或 REJECTED。
4. 审核通过后，具发布权限者执行发布；系统写入 published_at，文章可进入知识门户。
5. 发布后可下架；如需重大修改并再次公开，应进入重新审核流程，不直接覆盖已发布内容的审批结论。


### 3.2 关键业务约束
- 权限在后端校验：前端隐藏菜单不能代替接口鉴权；未登录、无权限或已停用账号均不得访问受保护接口。
- 审核幂等与状态校验：只允许审核 PENDING_REVIEW 状态的文章，同一轮审核不能重复通过或驳回。
- 历史不可覆盖：每轮审核新增记录，保留审核人、结果、意见和时间；操作日志保留历史。
- 删除安全：文章采用逻辑删除；分类删除前检查子分类和在用文章；停用员工不得清空历史文章。
- 并发控制：状态变更使用事务及状态条件更新，必要时对文章使用乐观锁 version 防止并发覆盖。
- 访问范围：首期实现“全体员工可见”内容；若扩展部门权限，后端查询与缓存键必须同步纳入部门访问范围。


## 四、系统架构与工程结构

### 4.1 技术架构
| 层次 | 技术与职责 |
|---|---|
| 前端 | Vue 3 + Element Plus + Vue Router + Pinia + Axios；后台页面与员工知识门户共用一个前端工程。 |
| 服务端 | Spring Boot；Controller 接收请求与校验参数，Service 实现事务与业务规则，Mapper 访问数据库。 |
| 数据持久化 | MySQL + MyBatis-Plus；单表 CRUD、分页与条件查询使用 MP，多表关联和性能 SQL 由自定义 Mapper/XML 实现。 |
| 认证授权 | JWT 或等效登录态方案 + Spring AOP 权限注解；接口后端强制校验角色权限和账号状态。 |
| 性能与缓存 | Redis + Spring Cache（或显式 RedisTemplate）；仅对适宜共享的高频读取数据缓存。 |
| 测试协作 | Apifox 统一接口文档、请求示例与用例；JMeter 压测；Git 分支、PR 审查、自动化或人工测试。 |


### 4.2 推荐目录
仓库建议采用一个 monorepo：/backend、/frontend、/sql、/docs、/tests。后端按 user、permission、content、review、log 五个业务包划分；每个业务包内部再按 controller / service / mapper / entity / dto / vo 分层。common、config、security 为共享基础模块。


### 4.3 API 与异常规范
统一 RESTful 路径前缀：/api/auth、/api/admin、/api/portal。统一响应体推荐包含 code、message、data、timestamp；分页响应包含 records、total、current、size。错误码区分参数错误、未登录、无权限、资源不存在和业务状态冲突。


## 五、数据库设计（12 张核心表）

### 5.1 表结构清单与负责归属
| 负责人 | 表名 | 关键字段（建议）及用途 |
|---|---|---|
| A | sys_user | id、username、password_hash、real_name、department_id、status；员工账号、身份信息 |
| A | sys_department | id、parent_id、dept_code、dept_name、sort_order、status；组织与多级部门 |
| A | sys_user_role | user_id、role_id；员工与角色的多对多关联 |
| B | sys_role | id、role_code、role_name、status；系统角色 |
| B | sys_permission | id、permission_code、permission_name、permission_type、path、method；接口/菜单权限 |
| B | sys_role_permission | role_id、permission_id；角色与权限的多对多关联 |
| C | cms_article | id、title、summary、content、category_id、author_id、status、visibility、published_at、version、is_deleted；知识文章 |
| C | cms_category | id、parent_id、category_name、sort_order、status；文章分类树 |
| C | cms_tag | id、tag_name、status；知识标签 |
| D | cms_review | id、article_id、reviewer_id、result、comment、reviewed_at；审核历史 |
| D | cms_article_tag | article_id、tag_id；文章和标签的多对多关联 |
| D | sys_operation_log | id、user_id、operation、request_uri、http_method、result、duration_ms、created_at；审计日志 |

公共字段建议：需要的业务表统一 created_at、updated_at；可逻辑删除的表增加 is_deleted。关联表优先使用复合唯一约束；主键类型、时间类型、逻辑删除策略和编码规范在建库前统一约定。


### 5.2 主要关系与约束
| 关系 | 类型 | 设计约束 |
|---|---|---|
| sys_department → sys_user | 一对多 | user.department_id 关联部门；必要时索引 department_id |
| sys_user ↔ sys_role | 多对多 | 经 sys_user_role；联合唯一 (user_id, role_id) |
| sys_role ↔ sys_permission | 多对多 | 经 sys_role_permission；联合唯一 (role_id, permission_id) |
| cms_category → cms_article | 一对多 | 文章关联分类；删除分类前校验子分类、被引用文章 |
| sys_user → cms_article | 一对多 | author_id 关联创建员工，历史作者信息保留 |
| cms_article → cms_review | 一对多 | 保存多轮审核，reviewer_id 关联用户 |
| cms_article ↔ cms_tag | 多对多 | 经 cms_article_tag；联合唯一 (article_id, tag_id) |
| sys_user → sys_operation_log | 一对多 | 关键行为记录操作人，日志按时间与操作人检索 |

关联说明：部门、用户、角色、权限、文章、分类、标签、审核与日志之间的查询均以实际业务外键字段为依据；以正式建表脚本作为最终事实来源。


### 5.3 索引与一致性规范
- sys_user.username、sys_role.role_code、sys_permission.permission_code、sys_department.dept_code：唯一索引。
- cms_article：按真实过滤条件考虑 (status, published_at)、category_id、author_id 等索引；避免无依据地重复建索引。
- cms_review：可考虑 (article_id, reviewed_at) 以支持审核历史查询；sys_operation_log：按 user_id、created_at 检索设计索引。
- 多表关联中维持主外键字段类型一致；能使用真实外键约束的表结合删除策略评估，在应用层同时进行业务校验。
- 存储密码哈希而非明文密码；登录敏感数据不进入普通操作日志；动态 SQL 必须使用参数绑定。


## 六、四人分工与个人验收标准
执行原则：每个人负责 3 张主要数据表、至少 2 个完整业务功能、独立提交前后端代码与测试用例；各模块可以跨表查询，但涉及他人维护的实体或字段必须通过评审协作修改。

| 成员 | 主要责任表 | 至少 2 个完整功能 | 必须展示的单表 + 多表查询 |
|---|---|---|---|
| A 用户组织 | sys_user / sys_department / sys_user_role | ① 登录、账号创建/修改/启停/查询；② 部门管理和员工部门分配；另完成用户角色关联操作 | 员工单表分页；用户—部门 JOIN；用户—用户角色—角色 JOIN |
| B 权限安全 | sys_role / sys_permission / sys_role_permission | ① 角色新增、编辑、停用和分页查询；② 权限项维护、角色权限分配与查询；另实现 AOP 接口鉴权 | 角色单表分页；角色—角色权限—权限 JOIN |
| C 内容分类 | cms_article / cms_category / cms_tag | ① 文章草稿 CRUD、详情和高级搜索；② 多级分类与标签管理、文章列表检索 | 分类/文章单表查询；文章—分类—作者 JOIN |
| D 审核日志 | cms_review / cms_article_tag / sys_operation_log | ① 文章审核、发布/下架、审核历史；② 操作日志自动记录、分页筛选及详情；另实现文章标签绑定 | 日志单表筛选；文章—审核记录—审核人 JOIN；文章—标签 JOIN |


### 6.1 完整功能的共同判定口径
1. 前端有可操作页面，涉及查询、表单、数据验证、权限反馈和成功/失败提示。
2. 后端有 Controller / Service / Mapper，使用统一 DTO/VO 与响应结构，核心写操作有事务边界。
3. 数据库能正确持久化并具备至少一条可说明的单表及多表查询。
4. Apifox 有正常路径、错误参数、无权限或非法状态至少三类接口用例。
5. 在 Git 中能追踪开发者的提交、PR 或代码评审记录，能现场讲解该模块的实现。


### 6.2 跨成员接口边界
| 协作双方 | 交互事项 | 接口或数据约定 |
|---|---|---|
| A ↔ B | 用户角色分配、权限加载 | A 维护 user-role 关联，B 维护 role/permission；权限计算由 B 提供服务接口 |
| A ↔ C | 文章作者、员工所属部门 | C 通过 user.id / department_id 查询作者和部门，不能复制员工信息为第二份数据源 |
| C ↔ D | 文章状态、审核、标签绑定 | C 负责文章 CRUD/提交审核，D 负责审核/发布/下架及 article-tag 关联；统一文章状态枚举 |
| B ↔ 全员 | 受保护业务接口 | B 提供权限注解及编码规范，全员在业务接口加注解并编写越权测试 |
| D ↔ 全员 | 操作日志收集 | D 提供日志注解或 AOP 审计能力，其他模块标记关键操作；注意不记录密码和令牌 |


## 七、建议的接口规划（第一版）
| 责任模块 | 接口示例 | 功能 |
|---|---|---|
| A | POST /api/auth/login；POST /api/auth/logout；GET /api/auth/me | 登录、退出、查询当前用户 |
| A | GET/POST /api/admin/users；PUT /api/admin/users/{id} | 员工分页、创建、修改、启停 |
| A | GET/POST /api/admin/departments；PUT /api/admin/users/{id}/roles | 部门操作、角色分配 |
| B | GET/POST /api/admin/roles；PUT /api/admin/roles/{id}/permissions | 角色查询与权限分配 |
| B | GET/POST /api/admin/permissions；GET /api/auth/permissions | 权限项维护、获取当前用户的有效权限编码 |
| C | GET/POST /api/admin/articles；PUT /api/admin/articles/{id} | 文章搜索、创建与编辑 |
| C | POST /api/admin/articles/{id}/submit | 提交审核 |
| C | GET/POST /api/admin/categories；GET/POST /api/admin/tags | 分类树和标签管理 |
| D | GET /api/admin/reviews/pending；POST /api/admin/articles/{id}/approve | 待审核查询、审核通过 |
| D | POST /api/admin/articles/{id}/reject；POST /api/admin/articles/{id}/publish | 审核驳回、内容发布 |
| D | POST /api/admin/articles/{id}/offline；PUT /api/admin/articles/{id}/tags | 文章下架、标签绑定 |
| D | GET /api/admin/logs | 操作日志分页检索 |
| 门户 | GET /api/portal/articles；GET /api/portal/articles/{id} | 员工门户文章列表和详情 |

接口路径为讨论稿，最终以 Apifox 接口文档为准；状态变更操作应校验权限、资源归属及文章当前状态。分页与排序参数统一命名，所有批量操作需明确空列表、部分失败和事务回滚规则。


## 八、测试计划与性能优化

### 8.1 功能与权限测试
| 测试项 | 代表场景 | 预期结果 |
|---|---|---|
| 登录与账号 | 正确登录、错误密码、停用账号请求 | 仅合规身份成功登录；无效身份被拒绝 |
| 权限 | 普通员工调用审核/发布接口 | 服务端返回无权限，不发生数据变更 |
| 内容流转 | 草稿提交—通过—发布—下架 | 状态按规则流转，历史审核记录可追溯 |
| 非法业务 | 未审核直接发布、已审核重复审核 | 返回业务冲突，数据库状态无异常 |
| 查询 | 多条件组合、分页边界、文章作者分类 JOIN | 返回结果准确，无越权数据 |
| 日志 | 文章审核、用户停用、角色权限修改 | 有记录且不包含密码或令牌 |


### 8.2 MyBatis-Plus 与 SQL 专项优化
验证 MyBatis-Plus 的 Page 分页、LambdaQueryWrapper 动态筛选、自动填充、逻辑删除；通过 SQL 日志观察查询次数，使用 EXPLAIN 分析重要 SQL。典型 N+1 场景为文章列表逐条查询作者和分类：优化为文章、用户和分类的联合查询；文章标签可采用分页后批量查询，避免一对多 JOIN 破坏分页。保存优化前后 SQL 数、耗时和解释。


### 8.3 Redis 缓存测试
| 缓存目标 | 建议做法 | 正确性要求 |
|---|---|---|
| 已发布、全员可见文章详情 | 缓存键按文章 ID；设置合理 TTL | 文章修改、下架、删除后清理或更新缓存 |
| 分类树 | 缓存分类树查询结果 | 分类变更立即失效，不能长期展示旧分类 |
| 首页热门文章（选做） | 缓存高频列表，限定有效期 | 浏览量与列表数据允许的延迟须事先明确 |

1. 测试组 A：关闭缓存，同一数据集下重复请求，记录 P95、平均响应时间和数据库 SQL 次数。
2. 测试组 B：开启缓存并完成预热，再次测试同样请求，记录 Redis 命中与性能指标。
3. 一致性检查：编辑/下架文章、更新分类后请求同一接口，确保没有展示已失效内容。
4. 安全检查：敏感或部门限定内容不直接共享全局缓存；首期仅缓存全体员工可见且已发布的文章。


### 8.4 JMeter 压力测试
| 负载等级（建议起点） | 主要场景 | 采集指标 |
|---|---|---|
| 20 并发用户 | 门户文章分页 + 文章详情 | 平均响应时间、P95、TPS、错误率 |
| 50 并发用户 | 重复相同热点文章 + 不同文章 | 同上，附 CPU/内存/DB 连接监控 |
| 100 并发用户 | 混合页面访问与高级检索 | 同上，识别瓶颈并输出优化结论 |

以上并发量为建议起点，不是老师规定的指标。保持线程启动速率、持续时间、基础数据规模、网络环境一致，避免把第一次冷启动的结果与预热后的结果直接比较。至少提供一组缓存前后和一组 SQL 优化前后结果；不预设性能一定提升或能达到的 TPS。


## 九、开发协作、里程碑与交付

### 9.1 Git 与接口协作规范
- 仓库分支：main 存放稳定版，dev 用于集成，feature/user、feature/permission、feature/content、feature/review 等开发分支按模块维护。
- PR 必须描述功能、数据库变更、接口调整和测试结果；至少由一名其他成员审查再合入 dev。
- 共享 SQL 变更采用编号脚本，禁止多人直接修改同一个数据库且没有脚本记录；接口先在 Apifox 定义再开发。
- 统一命名：数据库 snake_case；Java 类 PascalCase、方法 camelCase；RESTful URI 使用小写名词。
- 统一字段约定：主键、时间、状态、分页和返回结构固定，避免临时各自定义不同枚举。
- 每次修复 bug 留下对应 issue、提交、测试截图或简短排错说明，用于课程过程材料。


### 9.2 五阶段实施计划（按实际课时调整）
| 阶段 | 工作重点 | 阶段产出 |
|---|---|---|
| 阶段 1｜需求与设计 | 需求边界、12 表 E-R 图、状态机、权限矩阵、接口契约 | 需求分析、表结构初稿、分工表、接口草案 |
| 阶段 2｜公共基础 | 后端工程、前端骨架、登录鉴权、统一异常、初始化脚本 | 可启动系统、统一响应、登录页面、测试数据 |
| 阶段 3｜并行开发 | A/B/C/D 完成各自至少 2 个完整功能；每周合并一次 | 四模块前后端、单表与多表查询、接口用例 |
| 阶段 4｜集成验收 | 完成文章审核闭环、角色权限联动、日志记录；修复集成问题 | 完整演示流程、Apifox 测试结果 |
| 阶段 5｜优化交付 | Redis 缓存、JMeter 压测、SQL/N+1 优化、文档整理 | 压测与缓存对比报告、最终 SQL、结题报告 |


### 9.3 验收演示脚本
1. 管理员登录，新增部门与员工，分配内容编辑和审核员角色，并配置接口权限。
2. 编辑人员登录，创建技术文档，选择分类、绑定标签，保存草稿，再提交审核。
3. 审核员登录，查看待审核队列，先演示驳回；编辑修改后重新提交，审核员通过。
4. 具发布权限者发布文章；普通员工在知识门户按分类、关键词、标签检索并查看文章。
5. 在 Apifox 中使用普通员工令牌调用审核接口，展示后端拒绝越权请求。
6. 查看审核历史与操作日志；展示分页、多表 JOIN、N+1 优化和缓存/压测对比数据。


### 9.4 最终交付清单
- 源代码：backend、frontend、清晰 README、启动与环境配置说明。
- 数据库：初始化 DDL、必要的初始化演示数据、字段/索引说明、E-R 图。
- 设计材料：项目概述、需求分析、业务流程图、系统架构图、分工与权限矩阵。
- 接口与测试：Apifox 导出文档、功能与异常用例、JMeter 测试计划及结果、Redis 缓存命中及失效测试结果。
- 优化与过程：SQL 优化记录、AI 使用及人工修改痕迹、PR/提交记录、项目总结与部署演示说明。


## 十、主要风险、注意事项与待确认问题
| 风险 | 建议措施 |
|---|---|
| 课程实际要求与选题范围不一致 | 提交教师确认：是否允许在 EMP 场景上扩展为企业知识型 CMS，是否必须使用特定 SSM 工程形式。 |
| 12 表跨模块耦合较高 | 第一周冻结核心主键、状态枚举与接口契约；跨模块修改走 PR。 |
| 权限和审批业务不完整 | 优先实现最小审核状态机；权限拦截、失败处理、重复操作是验收必测。 |
| 缓存出现脏数据或权限泄露 | 首期只缓存已发布且全体员工可见的内容；写操作明确失效策略。 |
| 测试数据太少、优化无法体现 | 准备合理的数据规模和可复现实验，保留 EXPLAIN、SQL 日志与压测文件。 |
| 分工不均或文档集中到一人 | 每人承担独立前后端功能、接口用例和个人开发记录，共同完成设计与结题材料。 |

> **小组第一次会议应确认**  ① 教师是否认可该选题与技术栈升级；② 是否需要独立的前台门户；③ 内容可见范围是否做到部门级；④ 统一 Java / Spring Boot 版本；⑤ 课程截止日期、演示机器及部署方式。
