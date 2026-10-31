# GPT_CONTEXT — CreatorDesk

## 任务与组件边界

CreatorDesk C2 是创作流程工作台，保存 task、操作者提供的 source excerpt、不可变 draft version、review、generation/publication 状态和事件。身份取自配置的 Bearer token，不接受请求体指定 owner。

| 组件 | CreatorDesk 的边界 |
|---|---|
| C1 Agent 服务 | 按显式请求创建 session/run；CreatorDesk 不托管模型、工具或 Memory。 |
| ContentPlatform | CreatorDesk 调用作者查询与幂等内容写入；内容平台拥有账号、公开内容和其下游事件。 |
| C3 搜索服务 | CreatorDesk 调用搜索和内容详情读取；不拥有索引、不运行后台 relay。 |
| PostgreSQL | C2 独占自己的数据库状态，不读写其他组件的数据库。 |

Peer 地址和每个角色的凭据通过运行环境中的 `CREATOR_DESK_OWNERS_JSON` 配置。HTTP 只允许 loopback；非 loopback peer 必须使用 HTTPS。构建和启动 Java C2 不会自动启动或探测 peers。

## 状态字段

| 字段 | 当前值 | 证据边界 |
|---|---|---|
| canonical implementation | Java 21 / Spring Boot / PostgreSQL | 本包源码和 `backend-java/pom.xml`。 |
| history-depth measurement | `COMPLETE_WITH_BOUNDARIES` | 2026-10-30 固定合成负载，40,500/40,500 正式请求通过业务校验；见 [RESULTS.md](RESULTS.md)。 |
| production qualification | `NOT_QUALIFIED` | 共享服务器上的固定负载不能说明生产容量、SLA 或多租户运行能力。 |
| model/content quality | `NOT_EVALUATED_BY_LOAD_CAMPAIGN` | 该负载不调用模型，不生成或发布内容，也不包含真人写作对照。 |
| task-list pagination | `NOT_IMPLEMENTED` | 当前列表路径读取 owner 下完整任务聚合；结果中存在随历史深度加重的列表退化。 |
| external service source | `OUT_OF_REPOSITORY` | C1、ContentPlatform、C3 为独立 HTTP 服务，本包只描述本组件实际使用的合同。 |

## 可支持的主张

- CreatorDesk Java 服务实现 owner-scoped REST 工作流、PostgreSQL 持久化、版本并发检查和发布恢复状态。
- 在本包汇总的固定服务器条件下，三种初始历史深度共完成 40,500 个正式 HTTP 请求，均通过响应和业务不变量校验。
- 10 版初始历史、并发 16 时，任务列表 P95 为 108.831 ms；列表接口无分页，完整历史加深时测得列表吞吐下降。

## 不支持的主张

- 不把合成请求率或请求延迟表述为模型推理速度、生成质量、人工效率、真实用户容量或生产 SLA。
- 不把不同顺序运行在共享服务器上的历史深度差异表述为隔离硬件上的因果估计或优化收益。
- 不把源摘录称为自动抓取或核实的资料；CreatorDesk 接受操作者提交的摘录，来源核对由审核者明确确认。
- 不把 `published` 状态表述为内容正确、已被搜索索引、获得传播效果或实现任意下游 exactly-once。
- 2026-10-22 的历史 Node/SQLite 单篇真实模型与发布记录，不是本 Java/PostgreSQL 负载测量，也不扩展为生产资格。

## 读者路由

先看 [README.md](README.md) 的范围与启动条件，再看 [RESULTS.md](RESULTS.md) 的聚合指标和限制，然后看 [ARCHITECTURE.md](ARCHITECTURE.md) 定位符号。原始逐请求文件、启动收据、数据库和服务器日志不属于此公开汇总，也不是阅读入口。
