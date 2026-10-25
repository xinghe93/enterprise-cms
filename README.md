# EnterpriseCMS

面向企业内部员工的知识与内容管理平台，规划覆盖人员与组织、角色权限、文章分类、审核发布和操作审计。

当前处于项目初始化阶段：已有 Spring Boot 后端骨架、MySQL/Redis 连接配置和 12 张核心表的建表脚本；业务接口和前端应用尚未实现。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 25、Spring Boot 4.1.1、MyBatis-Plus 3.5.17 |
| 数据与缓存 | MySQL、Redis |
| 基础能力 | Spring MVC、参数校验、AspectJ、Lombok |
| 前端（规划） | Vue 3、Element Plus、Vue Router、Pinia、Axios |
| 构建 | Maven Wrapper（Maven 3.10.0） |

## 目录结构

```text
enterprise-cms/
├── backend/       # Spring Boot 后端
│   ├── .mvn/      # Maven Wrapper 配置
│   ├── config/    # 本地连接配置示例，真实密码文件由 Git 忽略
│   ├── src/main/  # 应用源码与配置
│   ├── src/test/  # 测试源码
│   └── pom.xml    # 依赖与构建配置
├── docs/          # 项目设计文档
├── database/      # 编号数据库脚本
├── .gitignore     # 仓库统一忽略规则
└── README.md
```

前端工程后续放在 `frontend/` 下；Git 不跟踪空目录。

## 环境准备

- 安装 JDK 25，并确认 `java -version` 使用正确版本。
- 首次执行 Maven Wrapper 需要联网下载 Maven 和项目依赖，无需单独安装 Maven。
- 准备 MySQL 8.4 和 Redis。本地开发可以直接使用已有 Docker 容器。
- 当前开发环境使用 `mysql8`（`127.0.0.1:3306`）和 `redis`（`127.0.0.1:6379`）；其他成员可按自己的地址调整配置。

## 数据库初始化

在数据库工具中连接 MySQL，执行 [database/001_init.sql](database/001_init.sql)。脚本创建 `enterprise_cms` 数据库和设计文档中的 12 张核心表。

macOS/Linux 下，如果容器名为 `mysql8` 且创建容器时设置了 `MYSQL_ROOT_PASSWORD`，也可以在仓库根目录执行：

```bash
docker exec -i mysql8 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4' < database/001_init.sql
```

脚本包含外键、唯一约束、查询索引、文章状态和逻辑删除字段；初始业务表为空，未创建默认登录账号。主键使用自增 `BIGINT UNSIGNED`，关联表使用复合主键，时间字段使用 `DATETIME(3)`，字符集为 `utf8mb4`。

重复执行不会删除现有表或数据，也不会升级已存在的表结构。后续表结构变更使用新的编号脚本，例如 `002_add_article_field.sql`；部门和分类的循环父子关系、文章审核状态流转等业务规则由后续业务代码校验。

## 本地连接配置与启动

在仓库根目录执行：

```bash
cd backend
cp config/application-local.properties.example config/application-local.properties
```

编辑 `config/application-local.properties`，填写自己的 MySQL 用户名和密码。Redis 无认证时，用户名和密码留空；有认证时填写对应配置。真实配置文件已被 Git 忽略，位于源码目录之外，不会打进应用 JAR。

默认连接 `127.0.0.1:3306/enterprise_cms` 和 `127.0.0.1:6379`。如果地址不同，可在本地文件中补充 `spring.datasource.url`、`spring.data.redis.host` 和 `spring.data.redis.port`。

在 `backend/` 目录启动：

```bash
./mvnw spring-boot:run
```

Windows 用户可以手动复制并编辑配置文件，再在 `backend/` 目录的 PowerShell 中使用 `.\mvnw.cmd spring-boot:run`。在 IDE 中运行时，也将工作目录设置为 `backend/`。

也支持通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_DATA_REDIS_USERNAME`、`SPRING_DATA_REDIS_PASSWORD` 和 `SPRING_DATA_REDIS_DATABASE` 环境变量覆盖配置。

默认 HTTP 端口为 `8080`。当前尚未实现业务接口，访问根路径返回 404 属于预期行为。建表脚本需要手动导入，应用不会自动执行 SQL 初始化。

## 构建与测试

在 `backend/` 目录中执行：

```bash
# 编译、运行测试并打包
./mvnw verify

# 单独运行测试
./mvnw test
```

默认测试仅检查应用上下文加载。完成建表和连接配置后，可启用真实数据服务测试，验证 JDBC 连接、12 张核心表和 Redis PING：

```bash
# macOS/Linux，在 backend/ 目录执行
CMS_DATA_INTEGRATION_TESTS=true ./mvnw verify
```

PowerShell 中先执行 `$env:CMS_DATA_INTEGRATION_TESTS='true'`，再运行 `.\mvnw.cmd verify`。

本地环境文件 `.env` 和 `.env.*` 同样被 Git 忽略，但 Spring Boot 默认不会自动加载它们。本项目使用上述 `config/application-local.properties` 或环境变量提供连接信息。

## 项目文档

- [项目设计计划书 V1.0](docs/EnterpriseCMS%20项目设计计划书%20V1.0.md)
