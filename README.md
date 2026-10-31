# CreatorDesk

CreatorDesk 是技术文章工作流中的 C2 工作台：维护任务、操作者提交的资料摘录、不可变草稿版本、人工审核、发布意图与回执，并通过 HTTP 调用独立的 Agent、内容源和搜索服务。本包对应 Java 21、Spring Boot、PostgreSQL 实现；不包含那些外部服务的源码。

## 可核对的个人实现范围

本包中可按源码逐项核对的实现包括：

- `CreatorDeskController`：任务、资料、版本、审核、生成、发布确认/恢复和搜索 REST API。
- `CreatorWorkflow`：版本、人工审核、C1 生成同步和发布状态机。
- `DeskStore`：PostgreSQL JSONB 任务聚合、修订号并发保护、事件事务和数据库实例锁。
- `OwnerRegistry`、`SecurityConfig`、`PeerClients`、`PeerHttp`：owner Bearer 鉴权与有界外部 HTTP 调用。
- `web/app.ts`：中文工作台源代码；部署运行的 JS、HTML、CSS 已随 Java 包置于 `backend-java/src/main/resources/static/`。

本仓库只说明 CreatorDesk 拥有的 C2 逻辑。C1 Agent、ContentPlatform 内容真源和 C3 搜索由独立服务通过 HTTP 提供；不会共享数据库，也不会由 CreatorDesk 启动。生成、审核、发布分别受显式 API 和人工确认控制。

## 阅读顺序

1. [RESULTS.md](RESULTS.md)：2026-10-30 的 40,500 请求负载测量与边界。
2. [GPT_CONTEXT.md](GPT_CONTEXT.md)：任务定义、可支持的主张和状态字段。
3. [ARCHITECTURE.md](ARCHITECTURE.md)：模块、接口、不变量和源码地图。
4. `backend-java/pom.xml` 与 `backend-java/src/main/`：可运行实现。
5. `backend-java/src/test/`：要求隔离 PostgreSQL 与私有 owner 配置的集成测试。

## 构建、启动与只读核验

需要 Java 21、Maven 3.9 或更高版本，以及 PostgreSQL。服务默认只监听 `127.0.0.1:8814`，启动时通过 Flyway 应用数据库迁移。Java 服务不需要 Node.js；内置的静态页面已经在 Java resources 中。

从仓库根目录打包（此命令跳过测试）：

```powershell
mvn -f backend-java/pom.xml -DskipTests package
```

配置本地 PostgreSQL 及 owner/peer 映射。下面的 token 全是无效占位符；生成、发布和搜索只有在配置可访问的真实 peer 服务及其独立凭据后才可用。

```powershell
$env:CREATOR_DESK_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/creator_desk'
$env:CREATOR_DESK_DB_USERNAME = 'creator_desk'
$env:CREATOR_DESK_DB_PASSWORD = 'REPLACE_WITH_LOCAL_DATABASE_PASSWORD'
$env:CREATOR_DESK_OWNERS_JSON = '[{"owner":"local-demo","access_token":"PLACEHOLDER_ONLY_C2_NOT_A_SECRET","c1_base_url":"http://127.0.0.1:19101","c1_token":"PLACEHOLDER_ONLY_C1_NOT_A_SECRET","content_base_url":"http://127.0.0.1:18080","content_token":"PLACEHOLDER_ONLY_CONTENT_NOT_A_SECRET","search_base_url":"http://127.0.0.1:19200","target_account_id":"local-demo-author"}]'
java -jar backend-java/target/creator-desk-backend-1.0.0.jar
```

修改工作台后，可在仓库根目录执行 `npm install --ignore-scripts` 与 `npm run build:web`，用固定 TypeScript 编译器重建 Java 内置的 `app.js`；无需其他工作区目录。

另开终端检查匿名健康端点：

```powershell
curl.exe -fsS http://127.0.0.1:8814/healthz
```

完整 Java 集成测试命令为 `mvn -f backend-java/pom.xml test`；它需要隔离的测试 PostgreSQL 和 `CREATOR_DESK_OWNERS_JSON`，会执行真实数据库状态检查。不要将示例配置用于生产，也不要将真实 token 写入仓库、命令历史或日志。此包没有生产部署、TLS 网关、多实例或容量资格声明。
