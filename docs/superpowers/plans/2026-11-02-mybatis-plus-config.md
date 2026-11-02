# MyBatis-Plus 公共配置 Implementation Plan

**Goal:** 统一分页、Mapper/XML 扫描和实体映射规则，为四人分模块开发提供可复用的数据访问基础。

**Architecture:** Java 配置注册乐观锁及 MySQL 分页拦截器（分页最后，单页上限 100）；YAML 明确 XML 路径、驼峰映射、自增主键和逻辑删除标记值。公共 `PageQuery` 提供默认页码 1、页大小 10，并拒绝非正数和超过 100 的请求；数据库继续维护时间字段。

**Tech Stack:** Java 25、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、MySQL、JUnit、MockMvc；仅补齐分页必需的同版本 `mybatis-plus-jsqlparser` 模块。

## 已确认范围

- 用户已确认上述公共配置范围，并自行创建 `feature/mybatis-plus-config` 分支。
- 不实现或修改四人的业务实体与接口，不变更现有数据库表结构，不提交、推送或合并。
- Mapper 使用 `@Mapper` 标记，集中扫描 `com.xinghe.cms`；XML 放在 `resources/mapper/<模块>/`。
- 现有单主键表使用自增 `Long`；复合主键关联表使用明确 SQL，不强行套用 BaseMapper 的单主键方法。
- 逻辑删除仅由实体显式 `@TableLogic` 启用；乐观锁由 `@Version` 启用。时间字段使用 `LocalDateTime` 和数据库默认值，不增加重复的自动填充器。

## Task 1：基线与测试

- [x] 阅读项目设计、现有代码、配置、12 表 SQL 和官方分页插件说明。
- [x] 确认分支及工作区；基线 41 项测试通过。
- [x] 新增 `backend/src/test/java/com/xinghe/cms/config/MybatisPlusConfigTests.java`，检查扫描不把普通业务接口注册成 Mapper、拦截器生效及 YAML 约定；先运行确认缺少配置时失败。
- [x] 新增 `backend/src/test/java/com/xinghe/cms/common/request/PageQueryTests.java`，覆盖默认值、分页边界、无效参数及独立 Page 实例。

## Task 2：公共实现

- [x] `backend/pom.xml` 增加 `mybatis-plus-jsqlparser:${mybatis-plus.version}`。
- [x] 新增 `backend/src/main/java/com/xinghe/cms/config/MybatisPlusConfig.java`，仅扫描 `@Mapper` 接口，注册乐观锁及上限 100 的 MySQL 分页插件。
- [x] 新增 `backend/src/main/java/com/xinghe/cms/common/request/PageQuery.java`，实现默认值及 `toPage()` 参数校验，失败使用现有 `BusinessException(BAD_REQUEST)`。
- [x] `backend/src/main/resources/application.yaml` 增加 XML 路径、驼峰转换、自增主键及逻辑删除值配置。

## Task 3：实际 SQL 验证与文档

- [x] 新增测试专用实体、Mapper 和 XML；普通测试不需要数据库连接。
- [x] 新增 `backend/src/test/java/com/xinghe/cms/config/MybatisPlusMysqlIT.java`，通过显式 `-Dtest=MybatisPlusMysqlIT` 运行。仅使用当前连接的临时表和测试事务，验证分页上限及越界、XML 查询、主键/时间映射、逻辑删除、乐观锁冲突；不访问 12 张业务表。
- [x] 新增 `docs/MyBatis-Plus 公共配置.md`，提供各模块接入范例、复合主键处理、事务及并发规则、时间字段管理、真实 MySQL 测试与 Apifox 检查方法。
- [x] 定向测试、真实 MySQL 集成测试、全量 `clean verify`、静态审查及 `git diff --check`。

## 执行结果

- 初始配置测试因缺少 `MybatisPlusInterceptor` 断言失败，分页请求测试在实现前因缺少 `PageQuery` 类型编译失败。
- 实现后 11 项定向测试通过；独立临时 MySQL 8.4 容器中的 6 项真实数据库测试通过。
- 最终执行 `./mvnw -o -Daether.syncContext.named.factory=rwlock-local '-Dtest=*Tests,MybatisPlusMysqlIT' clean verify`：58 项测试（52 项普通测试 + 6 项 MySQL 集成测试），0 失败、0 错误、0 跳过，可执行 JAR 打包成功。
- 只读代码审查未发现重要问题；文档已强调统一使用 `PageQuery.toPage()`，避免手工配置 Page 覆盖公共限制。
- 数据库密码仅经环境变量传入临时实例；不写入仓库。代码保持本地未提交状态。
