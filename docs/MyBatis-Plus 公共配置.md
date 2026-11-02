# MyBatis-Plus 公共配置

公共配置位于 `com.xinghe.cms.config.MybatisPlusConfig`；分页请求位于 `com.xinghe.cms.common.request.PageQuery`。所有模块使用同一 `SqlSessionFactory`，无需重复声明分页或乐观锁插件。

## 依赖与 Mapper 目录

保留现有 Java 25、Spring Boot 4.1.1 和 MyBatis-Plus 3.5.17。仅增加同版本 `mybatis-plus-jsqlparser`：这是[官方分页插件要求的独立模块](https://baomidou.com/plugins/pagination/)，不需要再引入另一套 MyBatis Starter。

Mapper 放在各业务包的 `mapper` 子包，并标记 `@Mapper`：

```java
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
```

以上 `Article` 表示 C 成员维护的文章实体。集中扫描覆盖 `com.xinghe.cms`，只注册带 `org.apache.ibatis.annotations.Mapper` 的接口，避免把普通 Service 接口误当成 Mapper。

XML 放在 `backend/src/main/resources/mapper/<业务模块>/`，例如 `mapper/content/ArticleMapper.xml`。配置使用 `classpath*:/mapper/**/*.xml`；XML 的 `namespace` 必须等于对应 Mapper 的完整包名，语句 `id` 对应 Mapper 方法名。`resultType` 使用完整 VO/实体类名；本次不配置覆盖整个项目的类型别名扫描。

单表常规 CRUD 使用 `BaseMapper`。多表 JOIN 使用明确 SQL/XML，查询条件采用 `#{参数名}` 绑定，不能把用户输入拼接到 `${}` 中。多参数方法显式使用 `@Param`。涉及一对多数据时避免直接 JOIN 导致重复记录和分页总数偏差；可先分页主表，再批量查关联数据。

## 分页规则

| 参数 / 行为 | 约定 |
| --- | --- |
| `current` | 从 1 开始，默认 1 |
| `size` | 默认 10，范围 1～100 |
| 非法请求参数 | 返回 HTTP 400，使用现有统一异常响应 |
| 页码超过总页数 | 保持请求页码，返回空 `records`，保留真实 `total` |
| 分页偏移量溢出 | 返回 HTTP 400，避免生成负数偏移量 |
| 数据库方言 | MySQL |
| 插件顺序 | 乐观锁在前，分页最后 |

Controller 用 `@Valid @ModelAttribute PageQuery query` 接收查询参数，业务查询通过 `query.<Article>toPage()` 创建新的分页对象。已有查询 DTO 也可以继承 `PageQuery`，加入各模块自己的筛选字段。请求体 DTO 可使用 `@Valid @RequestBody`。

Service 查询示例（`mapper` 和 `Article` 为模块已有 Mapper 与实体）：

```java
Page<Article> page = query.toPage();
mapper.selectPage(page, new LambdaQueryWrapper<Article>()
        .orderByDesc(Article::getId));
return PageResult.from(page);
```

上例使用 `com.baomidou.mybatisplus.extension.plugins.pagination.Page`、`com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper` 和现有 `PageResult`。Controller 将结果包入 `ApiResponse.success(...)`。需要返回 VO 时，先转换记录再输出，不能把密码哈希等内部字段直接序列化给前端。

`PageQuery` 的校验注解提供 MVC 参数反馈，`toPage()` 再检查参数和偏移量，因此非 Controller 调用也不会生成非法分页。不要直接把客户端请求绑定为 MyBatis-Plus `Page`，不要让客户端控制 `maxLimit`、`searchCount` 或排序 SQL。排序字段使用服务端白名单，并增加唯一字段作为排序兜底。

分页插件设置单页上限 100，直接调用 Mapper 时通常会将超出该上限的 `Page.size` 缩减到 100；公共请求 DTO 对 `size>100` 的处理则是返回 400。内部手动设置 `Page.maxLimit` 会影响插件上限，应统一使用 `PageQuery` 并遵守公共限制。无分页的 `selectList()` 不受分页插件上限控制。

## 实体映射与现有 SQL

以 `database/001_init.sql` 为准，已明确启用下划线到驼峰的映射：`department_id → departmentId`、`created_at → createdAt`。表名仍显式使用 `@TableName("实际表名")`。

| SQL 类型 / 字段 | Java 与注解约定 |
| --- | --- |
| 单列自增主键 `BIGINT UNSIGNED` | `Long id`，显式 `@TableId`；全局 `IdType.AUTO` |
| 外键 ID | `Long`，不要使用无法区分未赋值的基本类型 |
| `DATETIME(3)` | `LocalDateTime` |
| 启停 `TINYINT` | `Integer`，保持现有 0/1 约定 |
| 文章 `version` | `Integer`，显式 `@Version`，新建实体初始化为 0 |
| 文章 `is_deleted` | `Integer isDeleted`，显式 `@TableLogic`，初始值 0 |

Java `Long` 的正数范围小于 MySQL 无符号 BIGINT 的完整范围；项目生成和传输 ID 按 `Long` 可表示的范围使用。主键不从客户端任意指定，插入后读取 MyBatis 回填的 ID。

`sys_user_role`、`sys_role_permission`、`cms_article_tag` 使用复合主键。MyBatis-Plus 的单主键 API 不能直接代表它们的复合键：关联操作使用同时包含两个键的明确 SQL/XML，不能新增虚构的自增 ID 或任选一个键执行 `updateById/deleteById`。

## 时间字段由数据库维护

现有 SQL 已设置 `CURRENT_TIMESTAMP(3)`，并为适用表设置 `ON UPDATE CURRENT_TIMESTAMP(3)`。不再增加 Java `MetaObjectHandler` 或 `FieldFill`。实体时间字段使用以下映射：

```java
@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
private LocalDateTime createdAt;

@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
private LocalDateTime updatedAt;
```

`TableField` 和 `FieldStrategy` 来自 `com.baomidou.mybatisplus.annotation`。只有 SQL 中存在的字段才需要声明；例如审核表使用 `reviewed_at`，日志表仅有 `created_at`。同样由数据库赋默认时间的字段可采用上述只读映射。需要业务指定的发布时间 `published_at` 应由发布流程维护，不能一律标记为 `NEVER`。

`FieldStrategy.NEVER` 只约束 MP 自动生成的字段赋值；自定义 XML、显式 Wrapper 的 `set` 仍需遵守相同规则。数据库生成时间不会自动回填到刚插入/更新的 Java 对象，响应需要它时重新查询。

`DATETIME` 本身不保存时区，`LocalDateTime` 也没有时区。开发与部署机器的数据库会话/JDBC 时间约定需保持一致；这些业务字段不要直接当作 UTC `Instant`。统一响应中的 `timestamp` 继续使用 UTC ISO 8601，不受本次配置改变。

## 逻辑删除和乐观锁

逻辑删除标记值统一为 0（正常）和 1（已删除），不全局指定逻辑删除字段名。只有声明 `@TableLogic` 的实体才启用该功能，当前业务表中是文章；不能给所有实体强加 `isDeleted` 字段。

使用带 `@TableLogic` 的 Mapper 执行删除时，MP 将其转换为逻辑更新，并为生成的常规查询过滤已删除数据。**自定义 XML/JOIN 不会自动补齐逻辑删除条件**，文章关联查询必须显式包含 `a.is_deleted = 0`；门户还必须过滤发布状态和可见范围。

乐观锁只对带 `@Version` 的实体及受支持的 MP 更新方法生效，例如 `updateById(entity)`。读取当前版本后更新，成功时递增版本；影响行数为 0 不能当作成功。文章模块应结合资源存在性、业务状态及版本检查，按场景抛出 `BusinessException(ResultCode.CONFLICT, "文章已被修改，请刷新后重试")` 等安全提示。

审核、发布等自定义状态更新 SQL 仍必须显式包含预期旧状态及必要的 `version` 条件，并递增版本、检查影响行数；插件不自动补全任意 XML。涉及文章和审核记录的多表写操作由 Service 的 `@Transactional` 保证一致性；插件不替代事务和权限判断。每次操作创建新的 Wrapper，避免复用更新条件。

## 验证与 Apifox

普通构建在 `backend/` 运行，无需连接数据库：

```bash
./mvnw clean verify
```

`PageQueryTests` 覆盖默认值、边界和溢出，`MybatisPlusConfigTests` 验证实际工厂中的插件、Mapper 扫描、XML 加载和全局映射约定。测试 fixture 不进入生产 JAR。

真实 MySQL 集成测试显式运行，使用现有数据源环境变量 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`（密码保存在本机环境，不写入 Git）。使用可连接的 MySQL 8.4 空测试库，并允许测试账号创建临时表：

```bash
./mvnw -Dtest=MybatisPlusMysqlIT test
```

该测试仅创建连接级 `TEMPORARY TABLE enterprise_cms_config_probe`，各测试在事务中运行并回滚。不会读写 12 张业务表，不会删除、清空或重置数据库；连接关闭后临时表自然释放。集成测试覆盖实际 LIMIT/COUNT、分页上限、越界空页、XML 参数绑定、主键回填、时间字段、逻辑删除和旧版本写入冲突。`*IT` 不属于默认测试匹配范围，需要显式执行，未运行时不能据普通构建声称数据库功能已验证。

本次没有新增业务 HTTP 接口。各模块接入后通过 Apifox 验证：

1. 省略分页参数，检查 `current=1`、`size=10`，响应使用现有四字段外层和 `PageResult`。
2. 查询第 2 页，确认与第 1 页记录不同且排序稳定，`total` 是符合条件的记录总数。
3. 分别发送 `current=0`、`size=0`、`size=101` 和非数字参数，确认 HTTP 400 及统一错误体。
4. 请求超过最后一页的页码，确认 `records=[]`，保留请求页码和真实总数。
5. 文章模块实现后逻辑删除文章，确认后台常规查询和门户均不返回已删除内容。
6. 文章更新接口接入版本检查后，用同一旧版本提交两次更新，确认第二次按业务约定返回冲突，内容未被覆盖。

配置依据：[分页插件](https://baomidou.com/plugins/pagination/)、[注解配置](https://baomidou.com/reference/annotation/)。
