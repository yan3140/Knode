# YanBlog 性能优化验证记录

> 本文只记录已经在当前本地环境执行过的结果。完整压测环境、QPS、P95/P99 和错误率将在 Day 8 使用固定脚本补充。

## Day 5：评论查询与数据库索引

验证日期：2026-07-18  
环境：MySQL 8.0，本地 `yan_blog`；`yan_comment` 31 行，`yan_article` 4 行。

### 评论 SQL 数量

旧实现先查询根评论及其用户，再循环每个根评论查询直接回复，并对每个非空回复集合再查一次用户。非空页面的 SQL 数量为：

```text
3 + 根评论数 R + 存在直接回复的根评论数 C
```

最坏为 `3 + 2R`，并且使用 `to_comment_id = 根评论 ID` 会漏掉回复的回复。

新实现的非空页面固定为 4 条 SQL：

1. 根评论分页计数。
2. 根评论分页数据。
3. 使用 `root_id IN (...)` 一次查询本页全部子评论。
4. 一次查询根评论、子评论的作者及被回复用户。

真实请求：`GET /api/blog/comment/commentList?articleId=1&pageNum=1&pageSize=10`。返回 10 个根评论、总数 15；根评论 2 的子评论为 `[9, 10, 11, 16, 17]`，包含多级回复；后端日志共记录 4 条 `Preparing` SQL。

### EXPLAIN 对比

| 查询场景 | 优化前 | 优化后 | 结论 |
| --- | --- | --- | --- |
| 文章根评论分页 | `ALL`，扫描 31 行，`Using filesort` | `ref`，命中 `idx_comment_root_page`，估算 15 行，`Using index condition` | 等值过滤与时间排序由联合索引支撑 |
| 本页子评论批量查询 | `ALL`，扫描 31 行，`Using filesort` | 当前仍为 `ALL`，扫描 31 行 | 当前一次命中行占比高且表很小，优化器判断全表扫描更便宜；`idx_comment_children` 为数据增长后的按根评论查询提供访问路径 |
| 公开文章列表 | `ALL`，扫描 4 行，`Using filesort` | `ref`，命中 `idx_article_public_list`，`Backward index scan` | 状态、逻辑删除和置顶排序匹配索引 |
| 热门文章列表 | `ALL`，扫描 4 行，`Using filesort` | `ref`，命中 `idx_article_hot`，`Backward index scan` | 状态、逻辑删除和浏览量排序匹配索引 |

`yan_article_tag` 已有联合主键 `(article_id, tag_id)`，能够覆盖当前文章详情回显、标签替换和按文章删除关系的查询，因此没有重复建索引。

索引脚本见 [`docs/sql/V20260718__comment_and_article_query_indexes.sql`](sql/V20260718__comment_and_article_query_indexes.sql)。该脚本是版本化的单次迁移，不应重复执行。

### 回归结果

- `CommentServiceImplTest`：2 个用例通过，覆盖多级回复、昵称批量填充和空页短路。
- 根目录 `mvnw test`：10 个测试全部通过，0 失败、0 错误。
- 后端可执行 jar 启动成功，评论真实接口返回 HTTP 200。

