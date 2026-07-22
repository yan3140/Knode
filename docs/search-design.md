# YanBlog 文章全文搜索设计

## 完成边界

Day 6 使用虚拟机 Docker 中的 Elasticsearch 9.4.3，不引入 Docker Compose、RabbitMQ 或新 Maven 模块。MySQL 始终是文章事实来源，Elasticsearch 只保存可公开搜索的已发布文章。

项目使用与服务端同版本的官方 Java API Client 9.4.3，业务仓库通过类型安全的 Java DSL 调用建索引、Index、Bulk、Search、Delete 和 Delete By Query API；REST 5 Client 只作为 Java API Client 内部传输层，不再由业务代码拼接请求 JSON。中文及中英文混合内容使用同版本的 Elastic 官方 `analysis-smartcn` 插件，不再依赖第三方 IK 插件。

原 Elasticsearch 7.12.1 容器保留为停止状态的 `es-7-backup`，原 `es-data`、`es-plugins` 卷均未修改。9.4.3 使用独立的 `yanblog-es-9-data`、`yanblog-es-9-plugins` 卷，然后从 MySQL 全量重建业务索引；没有跨大版本直接复用旧数据目录。

## 索引 Mapping

索引名：`yan_blog_articles`  
分片：1 个主分片、0 个副本。当前是单节点开发环境，0 副本可使该业务索引保持 `green`。

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `id` | `long` | 文章 ID，也是 ES 文档 `_id` |
| `title` | `text` | SmartCN 中文/中英文分词，搜索权重 5 |
| `summary` | `text` | SmartCN 分词，搜索权重 3 |
| `content` | `text` | SmartCN 分词，搜索权重 1 |
| `categoryId` | `long` | 分类 ID |
| `categoryName` | `text` | SmartCN 分词，搜索权重 2 |
| `tagNames` | `text` | 标签名集合，SmartCN 分词，搜索权重 2 |
| `thumbnail` | `keyword` | 只随结果返回，不参与索引 |
| `viewCount` | `long` | 热度排序 |
| `createTime` | `date/epoch_millis` | 最新排序 |

索引只保存已发布且未逻辑删除的文章，因此查询不需要再维护一份状态过滤条件。

## 搜索接口

公开接口：

```text
GET /api/blog/search?keyword=Spring&pageNum=1&pageSize=10&sort=relevance
```

`sort` 支持：

- `relevance`：相关度优先，发布时间作为次级排序。
- `latest`：发布时间优先，相关度作为次级排序。
- `hot`：浏览量优先，相关度作为次级排序。

响应使用统一 `PageVo`，每条数据包含标题、摘要、分类、标签、浏览量、创建时间、分数以及 `title/summary/content/categoryName/tagNames` 的高亮片段。分页大小最大为 50，空关键词或非法排序返回参数错误。

## 同步与补偿（Day 8 Outbox）

```text
文章变更 / 浏览量批量落库
        ↓ 同一 MySQL 事务写入 yan_article_search_outbox
PENDING 事件持久化
        ↓
定时消费者原子抢占为 PROCESSING（带租约）
        ↓
重新读取 MySQL 当前状态
   ├─ 已发布：重新组装分类/标签并覆盖 ES 文档
   └─ 草稿、下架、删除：删除 ES 文档
        ↓
成功：标记 SUCCEEDED
失败：回到 PENDING，记录 retryCount、lastError、nextRetryAt
        ↓
固定延迟自动重试，也可由后台手工提前重放
```

Outbox 表为 `yan_article_search_outbox`，每次文章变更都新增一条事件，不依赖 JVM 内存事件或 Redis 失败集合。文章新增、编辑、上下架、删除，以及浏览量 dirty set 成功刷入 MySQL 后，都会在对应 MySQL 事务中写入事件。消费者始终按 article ID 读取 MySQL 当前状态；ES 文档 ID 固定为文章 ID，因此重复 upsert/delete 是幂等的，连续编辑最终会收敛到最新状态。

Day 9 将消费者升级为批处理：每轮最多读取 100 个待处理事件，并逐条条件抢占，避免多实例重复消费；按文章 ID 去重后一次读取 MySQL，通过一次 ES Bulk 请求同时 upsert 已发布文章、delete 草稿/下架/删除文章。Bulk 响应中的单条失败只会重试对应文章事件，成功事件立即完成。

失败等待采用指数退避：10 秒、20 秒、40 秒……最大 300 秒；事件保留 `retryCount`、`lastError` 与 `nextRetryAt`。`blog.search.outbox{result=success|failure}` 记录每批完成和重试的事件数量。`FAILED` 死信终态、积压量与任务年龄指标仍留给 Day 13。

后台接口：

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/admin/content/search/sync/{articleId}` | 按 MySQL 当前状态同步单篇文章 |
| `POST /api/admin/content/search/retry` | 将全部 `PENDING` Outbox 事件设为立即可执行，等待调度器处理 |
| `POST /api/admin/content/search/rebuild` | Bulk 覆盖全部已发布文章，并删除 MySQL 中已不存在的脏文档 |

全量重建不会删除再创建整个索引，而是先 Bulk 覆盖当前文章，再用 `_delete_by_query` 清理不在当前 ID 集合中的文档，避免重建期间索引短暂不存在。

## 故障边界

- ES 不可用不会回滚已经提交的文章事务；公开搜索返回受控错误 517。
- Outbox 与文章写入在同一 MySQL 事务提交，应用在提交后崩溃时，未完成事件仍保留在数据库；过期租约会被后续调度器回收。
- 当前实现只保证单体内的可靠投递，仍是至少一次语义；ES 写入必须保持幂等。批量消费、指数退避和 `FAILED` 死信终态属于后续 Day 9/Day 13 工作。
- 全量重建仍保留为灾难恢复工具，不承担日常失败补偿。

## 真实验证（2026-07-19）

- 官方 Elasticsearch 9.4.3 容器与 `analysis-smartcn` 9.4.3 插件启动成功，Mapping 中 5 个文本字段均使用 SmartCN；业务索引健康状态为 `green`。
- 项目升级到官方 Java API Client 9.4.3；删除原业务索引后，由类型化 Create Index API 重新创建 Mapping，再由类型化 Bulk API 从 MySQL 重建，证明不是继续复用旧的手写 REST 请求。
- 全量重建从 MySQL 写入 3 篇已发布文章；此前的脏文档清理场景仍由同一重建实现覆盖。
- `SpringSecurity` 搜索命中文章 1，标题、摘要和正文均返回 `<em>` 高亮；中文“基础”命中文章 12 并返回标题、摘要和正文高亮。
- 同一关键词按 `latest` 返回 `[12, 1]`，按 `hot` 返回 `[1, 12]`。
- 文章 12 临时改名后 ES 立即更新；下架后文档返回 404；重新发布后恢复；最后 MySQL 和 ES 均还原原始标题。
- Day 8 后，Redis 失败集合已替换为 MySQL Outbox；旧的事务后 JVM 事件监听器不再参与索引同步。
- 9.4.3 上再次调用文章 12 单篇同步成功，补偿队列为空；旧 7.12.1 容器和数据卷仍可作为人工回退点。
- 重启 9.4.3 容器后，新数据卷中的 3 篇文章保持不变；应用侧 Java API Client 自动恢复连接，公开搜索继续返回 200。
- Java API Client 重构后再次验证：类型化 Bulk 重建返回 `indexed=3`，单篇 Index 同步文章 12 成功，`SpringSecurity` 与中文“基础”搜索、高亮正确，索引状态为 `green`。
- Day 8 故障演练：停止 ES 后，通过后台接口更新文章 112 状态，接口仍返回 200；`yan_article_search_outbox` 写入事件并记录重试原因。随后停止并重启 YanBlog，事件仍保持 `PENDING` 并继续重试；恢复 ES 后，该事件转为 `SUCCEEDED`，应用健康检查恢复 `UP`。
- Day 9 批量验证：以 20 个客户端并发提交 100 条已发布文章状态变更，100 个请求均返回 200，提交耗时 0.828 秒；调度器分两批（30、70）使用 Bulk 同步，12 秒后 100 条新增 Outbox 事件全部为 `SUCCEEDED`，关键词“压测样本文章”搜索总数仍为 100。
