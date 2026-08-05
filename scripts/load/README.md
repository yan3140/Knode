# YanBlog 高并发专项压测

本目录只测试当前项目里真正做过性能/架构优化的链路，不覆盖普通 CRUD 接口。

## 1. 准备和异步链路压测

默认会保留压测数据：10,000 篇文章、10,000 条评论、1,000 普通作者粉丝、10,000 大 V 作者粉丝，并投递 10,000 条 ES outbox。

```bash
python scripts/load/high_concurrency_db.py measure-async --articles 10000 --comments 10000 --es-events 10000 --regular-followers 1000 --large-followers 10000 --timeout 180
```

结果会写入：

- `scripts/load/results/latest-seed-env.json`
- `scripts/load/results/<本次prefix>/seed-env.json`
- `scripts/load/results/<本次prefix>/async-results.json`

重点看：

- ES outbox 是否全部 `SUCCEEDED`，以及耗时。
- 普通作者 1,000 粉丝是否成功写扩散到 `yan_user_notification`。
- 大 V 10,000 粉丝是否走 `yan_author_publish_event`，避免一次发布直接写 10,000 条通知。

## 2. HTTP 高并发压测

用上一步输出的 `ARTICLE_IDS`、`COMMENT_ARTICLE_ID`、`KEYWORD` 跑 k6。示例：

```bash
D:\programfile\k6\k6.exe run -e TARGET=article -e VUS=300 -e ITERATIONS=10000 -e ARTICLE_IDS=1,2,3 scripts/load/high-concurrency-http.js
D:\programfile\k6\k6.exe run -e TARGET=view -e VUS=300 -e ITERATIONS=10000 -e ARTICLE_IDS=1,2,3 scripts/load/high-concurrency-http.js
D:\programfile\k6\k6.exe run -e TARGET=comment -e VUS=300 -e ITERATIONS=10000 -e COMMENT_ARTICLE_ID=1 scripts/load/high-concurrency-http.js
D:\programfile\k6\k6.exe run -e TARGET=search -e VUS=300 -e ITERATIONS=10000 -e KEYWORD=load_20260722_120000 scripts/load/high-concurrency-http.js
```

`TARGET=mixed` 可以把文章详情、浏览量、评论、搜索、热门文章混合打到 10,000 次。

## 验收口径

- `checks` 成功率大于 99%。
- HTTP 失败率小于 1%。
- 本地开发机初始线：P95 小于 1500 ms；如果机器或 ES 虚拟机负载高，先看错误率和异步最终一致性，再调并发。
