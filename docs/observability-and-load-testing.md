# YanBlog 观测与压测运行说明

## Day 7 完成边界

Day 7 接入 Spring Boot Actuator、Micrometer 与 Prometheus 输出，补充请求 ID、HTTP 完成/慢请求日志和搜索业务指标，并提供四个可独立运行的 k6 脚本。当前仍是单机开发环境，不部署 Grafana 大盘，也不把烟雾测试数字当作正式性能结论。

本机没有 Docker Engine，MySQL、Redis、Elasticsearch 位于虚拟机，因此未增加会让普通 `mvn test` 依赖 Docker 的 Testcontainers 测试。核心逻辑由单元测试覆盖，依赖连通性和指标由真实启动后的应用验证。

## Actuator 端点与安全边界

| 端点 | 匿名访问 | 用途 |
| --- | --- | --- |
| `/actuator/health` | 允许 | 应用整体健康状态 |
| `/actuator/health/liveness` | 允许 | 存活探针 |
| `/actuator/health/readiness` | 允许 | 就绪探针 |
| `/actuator/info` | 允许 | 应用名称、说明和版本 |
| `/actuator/metrics` | 禁止，需管理员 token | 指标名称与单项诊断 |
| `/actuator/prometheus` | 禁止，需管理员 token | Prometheus 抓取格式 |

健康检查包含 `db`、`redis`、`elasticsearch`、磁盘空间以及 liveness/readiness。匿名健康响应不暴露组件细节，管理员身份可查看详细组件状态。除 health/info 外的 Actuator 端点沿用后台管理员权限边界。

## 指标

框架自动指标包括：

- `http.server.requests`：请求次数、总耗时、最大耗时、状态码、URI、method、outcome 和 exception 标签。
- `jvm.*`：内存、GC、线程、类加载和缓冲区。
- `process.*`、`system.*`：进程运行时间以及进程/系统 CPU。
- `hikaricp.*`、`jdbc.*`：数据库连接池容量、活跃、空闲、等待和获取耗时。
- `lettuce.*`：Redis 命令首响应和完成耗时。

业务指标包括：

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `blog.search.query` | Timer | `result=success/failure` | 文章搜索次数与耗时 |
| `blog.search.sync` | Counter | `result=success/failure` | 单篇索引同步结果 |
| `blog.search.rebuild` | Counter | `result=success/failure` | 全量重建结果 |

所有指标带 `application=YanBlog` 公共标签。Prometheus 中对应名称使用下划线，例如 `blog_search_query_seconds_*`、`blog_search_sync_total`。

## 请求日志

- 客户端可传入 `X-Request-ID`；只接受 1–64 位字母、数字、点、下划线和连字符，不安全的值会被 UUID 替换。
- 响应始终回传 `X-Request-ID`，日志 MDC 使用同一值；搜索异步同步线程会继承调用方 MDC。
- 每个请求输出 `event=http_request_completed` 结构化键值日志；耗时达到 500 ms 时改为 WARN 级别的 `event=slow_http_request`。
- 日志只记录 method、path、status、duration 和必要的 clientIp，不记录查询参数、token 或请求体。
- 日志文件为 `logs/yanblog.log`。

示例：

```text
event=slow_http_request method=POST path=/api/admin/content/search/sync/12 status=200 durationMs=557 clientIp=127.0.0.1
```

## k6 脚本

脚本位于 `scripts/k6/`：

| 脚本 | 默认接口 |
| --- | --- |
| `article-detail.js` | `/api/blog/article/1` |
| `hot-article-list.js` | `/api/blog/article/hotArticleList` |
| `comment-list.js` | `/api/blog/comment/commentList` |
| `search.js` | `/api/blog/search` |

默认参数为 10 VU、30 秒、请求间隔 1 秒；初始阈值为检查成功率大于 99%、HTTP 错误率低于 1%、P95 低于 500 ms。通过 `BASE_URL`、`VUS`、`DURATION`、`P95_MS`、`SLEEP_SECONDS`、`ARTICLE_ID` 和 `KEYWORD` 调整，完整命令见 `scripts/k6/README.md`。

## 真实验证（2026-07-19）

- `mvn test`：21 个测试，0 失败；新增搜索事件、请求 ID 和 Elasticsearch 健康检查测试。
- `/actuator/health` 匿名返回 200/UP，并回传请求 ID；管理员详情包含 MySQL、Redis、Elasticsearch，全部 UP。
- 匿名访问 `/actuator/metrics` 返回 401；管理员可以读取 HTTP、JVM、HikariCP、Lettuce 和搜索业务指标。
- `/actuator/prometheus` 返回 200，确认存在 `http_server_requests_seconds`、`blog_search_query_seconds` 和 `blog_search_sync_total`。
- 日志捕获到请求 ID，并将一次 557 ms 的单篇同步记录为慢请求。
- 四个脚本通过 Node 模块语法检查，并使用官方 k6 v2.0.0 完成 1 VU、3 秒烟雾运行：

| 脚本 | iterations | HTTP 失败率 | P95 |
| --- | ---: | ---: | ---: |
| article-detail | 14 | 0% | 95.22 ms |
| comment-list | 13 | 0% | 82.82 ms |
| hot-article-list | 15 | 0% | 16.53 ms |
| search | 15 | 0% | 13.30 ms |

烟雾运行只验证脚本、检查、阈值和接口能够工作。Day 8 将固定环境、数据规模、预热和并发梯度后重新压测，再形成可用于简历的正式报告。
