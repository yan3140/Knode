# YanBlog k6 压测脚本

四个脚本均可独立运行，默认目标为 `http://localhost:8989`、10 VU、30 秒，并要求错误率低于 1%、检查成功率高于 99%、P95 低于 500 ms。阈值是 Day 8 的初始验收线，不代表已经取得的性能结果。

```text
k6 run scripts/k6/article-detail.js
k6 run scripts/k6/hot-article-list.js
k6 run scripts/k6/comment-list.js
k6 run scripts/k6/search.js
```

参数通过 `-e` 传入：

```text
k6 run -e BASE_URL=http://localhost:8989 -e ARTICLE_ID=1 -e VUS=20 -e DURATION=60s -e P95_MS=500 scripts/k6/article-detail.js
k6 run -e BASE_URL=http://localhost:8989 -e KEYWORD=SpringSecurity -e VUS=20 -e DURATION=60s scripts/k6/search.js
```

可用参数：`BASE_URL`、`VUS`、`DURATION`、`P95_MS`、`SLEEP_SECONDS`；文章和评论脚本额外支持 `ARTICLE_ID`，搜索脚本额外支持 `KEYWORD`。

正式压测前固定机器规格、数据量和依赖版本，先以低并发预热，再逐级增加 VU。每次保存完整 k6 输出，Day 8 只引用带环境说明的真实结果。
