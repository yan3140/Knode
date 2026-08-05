# YanBlog Java 后端简历与面试项目资料

> 生成依据：当前仓库代码、`docs/` 文档、`docs/sql/` 迁移脚本、前端 API/路由、测试代码、压测记录和可读取的 Git 记录。  
> 真实性原则：只写代码中能证明的内容；涉及性能数据时只引用仓库中已有压测结果；Kafka、WebSocket、MinIO、Docker/Nginx 等未落地内容不写成已使用技术。

## 一、项目名称

YanBlog 博客内容平台。

代码依据：

- Maven 根工程：`pom.xml`
- 应用信息：`yan-admin/src/main/resources/application.yml`
- 单启动类：`yan-admin/src/main/java/com/yan/BlogAdminApplication.java`

## 二、项目背景

YanBlog 面向两类用户：

1. 普通博客访问用户：阅读文章、搜索文章、查看作者主页、评论/回复、关注作者、接收通知。
2. 内容运营/管理员：管理文章、分类、标签、友链、用户、角色、菜单，以及观察异步任务状态。

项目解决的问题：

- 为博客内容提供前台阅读、搜索、评论、关注和通知能力。
- 为后台管理提供内容管理、系统管理、上传和异步任务运维入口。
- 对阅读量、评论、搜索、文章发布通知等高频或异步场景做缓存、批量、可靠任务和压测优化。

主要使用场景：

- 用户在首页浏览文章、查看热门文章、进入文章详情。
- 用户搜索文章，并通过“加载更多”分页拉取结果。
- 用户发布文章后，系统异步同步 Elasticsearch，并给粉丝生成通知。
- 用户评论或回复文章后，系统给作者或被回复人生成通知。
- 管理员在后台维护内容、用户角色菜单，并查看/重试 outbox 异步任务。

代码依据：

- 前台文章接口：`yan-blog/src/main/java/com/yan/blog/controller/ArticleController.java`
- 前台搜索接口：`yan-blog/src/main/java/com/yan/blog/controller/SearchController.java`
- 前台关注/通知接口：`yan-blog/src/main/java/com/yan/blog/controller/UserFollowController.java`、`yan-blog/src/main/java/com/yan/blog/controller/UserNotificationController.java`
- 后台内容/系统接口：`yan-admin/src/main/java/com/yan/admin/controller/ArticleController.java`、`yan-admin/src/main/java/com/yan/admin/controller/UserController.java`、`yan-admin/src/main/java/com/yan/admin/controller/RoleController.java`、`yan-admin/src/main/java/com/yan/admin/controller/MenuController.java`
- 前台页面路由：`frond/sg-blog-vue/src/router/index.js`
- 后台动态路由：`frond/sg-vue-admin/src/store/modules/permission.js`

## 三、项目技术栈

### 后端

- Java 17：`pom.xml`
- Spring Boot 3.5.6：`pom.xml`
- Spring Web：`yan-framework/pom.xml`
- Spring Security：`yan-framework/pom.xml`、`yan-admin/src/main/java/com/yan/admin/config/SecurityConfig.java`
- Spring AOP：`yan-framework/pom.xml`
- Spring Data Redis：`yan-framework/pom.xml`
- MyBatis-Plus 3.5.9：`pom.xml`、`yan-framework/src/main/java/com/yan/config/MybatisPlusConfig.java`
- MyBatis XML 复杂 SQL：`yan-content/src/main/resources/mapper/*.xml`
- JWT：`jjwt` 依赖见 `pom.xml`，工具类见 `yan-framework/src/main/java/com/yan/utils/JwtUtil.java`
- Micrometer + Actuator + Prometheus：`yan-admin/pom.xml`、`yan-admin/src/main/resources/application.yml`
- Springdoc OpenAPI：`pom.xml`、`yan-blog/src/main/java/com/yan/blog/config/SwaggerConfig.java`

### 前端

- 前台：Vue 2.5、Vue Router、Vuex、Element UI 1.x、Axios、mavon-editor。依据：`frond/sg-blog-vue/package.json`
- 后台：Vue 2.6、Vue Router、Vuex、Element UI 2.x、Axios、NProgress、mavon-editor。依据：`frond/sg-vue-admin/package.json`

### 数据库

- MySQL：数据源配置见 `yan-admin/src/main/resources/application.yml`
- MySQL 表结构/索引迁移：`docs/sql/*.sql`

### 中间件

- Redis：登录态、缓存、限流、浏览量计数、Redis Stream 可靠任务。配置见 `yan-admin/src/main/resources/application.yml`
- Elasticsearch：文章全文搜索。配置与客户端见 `yan-content/src/main/java/com/yan/search/config/ElasticsearchConfig.java`

### 文件存储

- 阿里云 OSS：依赖见 `yan-framework/pom.xml`，上传实现见 `yan-content/src/main/java/com/yan/service/impl/UploadServiceImpl.java`

### 部署和工程工具

- Maven 多模块工程：`pom.xml`
- Maven Wrapper：`.mvn/`、`mvnw.cmd`
- k6 压测脚本：`scripts/load/high-concurrency-http.js`、`scripts/k6/*.js`
- 当前仓库未发现 Dockerfile、docker-compose、Nginx 配置文件；不能写成项目已落地部署能力。

## 四、系统架构

项目是多模块单体架构，不是微服务架构。

后端只有一个 Spring Boot 启动入口：`yan-admin/src/main/java/com/yan/BlogAdminApplication.java`。该启动类通过 `@SpringBootApplication(scanBasePackages = "com.yan")` 扫描全部业务模块，并通过 `@MapperScan("com.yan.mapper")` 扫描 Mapper。

模块关系：

- `yan-admin`：统一启动入口、后台管理 Controller、安全配置、请求日志、健康检查。
- `yan-blog`：前台博客 Controller、前台 Swagger 配置、浏览量定时刷新任务。
- `yan-content`：文章、分类、标签、评论、友链、上传、关注、通知、搜索、可靠任务等核心业务。
- `yan-system`：用户、角色、菜单、登录用户加载。
- `yan-framework`：Spring Security、Redis 工具、缓存工具、限流、异常处理、MyBatis-Plus 配置。
- `yan-common`：Entity、DTO、VO、常量、通用响应、JWT/Bean 工具等。

用文字描述系统架构图：

```text
浏览器
├─ 前台 Vue 项目 sg-blog-vue
│  └─ /api/blog/** 或兼容旧路径 /article、/comment、/search 等
├─ 后台 Vue 项目 sg-vue-admin
│  └─ /api/admin/** 或兼容旧路径 /content、/system、/upload 等
│
└─ Spring Boot 单体应用 yan-admin:8989
   ├─ Security Filter：解析 token，读取 Redis 登录态，写入 SecurityContext
   ├─ Controller：前台 yan-blog；后台 yan-admin
   ├─ Service：yan-content / yan-system
   ├─ Mapper：MyBatis-Plus 简单查询 + MyBatis XML 复杂查询
   ├─ MySQL：业务表、权限表、outbox 表
   ├─ Redis：登录态、缓存、限流、浏览量计数、Redis Stream
   ├─ Elasticsearch：文章搜索索引
   └─ Aliyun OSS：图片上传
```

代码依据：

- 统一启动：`yan-admin/src/main/java/com/yan/BlogAdminApplication.java`
- 安全链：`yan-admin/src/main/java/com/yan/admin/config/SecurityConfig.java`
- JWT 过滤：`yan-admin/src/main/java/com/yan/admin/filter/JwtAuthenticationTokenFilter.java`
- Redis Stream 配置：`yan-content/src/main/java/com/yan/reliabletask/redisstream/ReliableTaskRedisStreamProperties.java`

## 五、核心业务模块

### 1. 登录与权限认证模块

解决的问题：区分前台用户和后台管理员，保护后台管理接口和登录用户接口。

主要功能：

- 前台登录、退出。
- 后台登录、退出、获取用户信息、获取菜单路由。
- JWT 校验和 Redis 登录态校验。
- 后台接口需要管理员权限。

核心接口：

- `POST /login`、`POST /api/blog/auth/login`
- `POST /user/login`、`POST /api/admin/auth/login`
- `GET /getInfo`、`GET /api/admin/auth/me`
- `GET /getRouters`、`GET /api/admin/auth/routers`

涉及数据表：

- `sys_user`
- `sys_role`
- `sys_menu`
- `sys_user_role`
- `sys_role_menu`

相关代码：

- `yan-blog/src/main/java/com/yan/blog/controller/BlogLoginController.java`
- `yan-admin/src/main/java/com/yan/admin/controller/AdminLoginController.java`
- `yan-framework/src/main/java/com/yan/service/impl/BlogLoginServiceImpl.java`
- `yan-framework/src/main/java/com/yan/service/impl/LoginServiceImpl.java`
- `yan-admin/src/main/java/com/yan/admin/filter/JwtAuthenticationTokenFilter.java`
- `yan-admin/src/main/java/com/yan/admin/config/SecurityConfig.java`
- `yan-system/src/main/java/com/yan/service/impl/UserDetailsServiceImpl.java`

### 2. 文章内容模块

解决的问题：提供博客文章发布、管理、列表、详情、置顶、草稿/发布状态等能力。

主要功能：

- 前台文章列表、热门文章、文章详情、写博客。
- 后台文章新增、编辑、删除、状态修改、分页查询。
- 文章和分类、标签、作者信息组装。
- 发布/修改/删除后触发缓存失效、浏览量计数同步、ES 同步 outbox、粉丝通知 outbox。

核心接口：

- `GET /article/hotArticleList`
- `GET /article/articleList`
- `GET /article/{id}`
- `PUT /article/updateViewCount/{id}`
- `POST /article`
- `POST /content/article`
- `GET /content/article/list`
- `PUT /content/article`
- `DELETE /content/article/{ids}`
- `PUT /content/article/{id}/status`

涉及数据表：

- `yan_article`
- `yan_article_tag`
- `yan_category`
- `yan_tag`
- `sys_user`
- `yan_article_search_outbox`
- `yan_notification_outbox`

相关代码：

- `yan-blog/src/main/java/com/yan/blog/controller/ArticleController.java`
- `yan-admin/src/main/java/com/yan/admin/controller/ArticleController.java`
- `yan-content/src/main/java/com/yan/service/impl/ArticleServiceImpl.java`
- `yan-content/src/main/java/com/yan/mapper/ArticleMapper.java`
- `yan-content/src/main/resources/mapper/ArticleMapper.xml`
- 前台写作页：`frond/sg-blog-vue/src/pages/WriteArticle.vue`
- 后台写作页：`frond/sg-vue-admin/src/views/content/article/write/index.vue`

### 3. 分类、标签、友链模块

解决的问题：支撑文章归类、标签管理和友情链接展示/管理。

主要功能：

- 前台分类列表、标签列表、友链展示。
- 后台分类分页、全部分类、标签 CRUD、友链 CRUD 和状态修改。

核心接口：

- `GET /category/getCategoryList`
- `GET /tag/listAllTag`
- `GET /link/getAllLink`
- `GET /content/category/list`
- `GET /content/tag/list`
- `POST /content/tag`
- `PUT /content/tag`
- `DELETE /content/tag/{ids}`
- `GET /content/link/list`
- `POST /content/link`
- `PUT /content/link`
- `DELETE /content/link/{ids}`

涉及数据表：

- `yan_category`
- `yan_tag`
- `yan_link`
- `yan_article_tag`

相关代码：

- `yan-content/src/main/java/com/yan/service/impl/CategoryServiceImpl.java`
- `yan-content/src/main/java/com/yan/service/impl/TagServiceImpl.java`
- `yan-content/src/main/java/com/yan/service/impl/LinkServiceImpl.java`
- `yan-admin/src/main/java/com/yan/admin/controller/CategoryController.java`
- `yan-admin/src/main/java/com/yan/admin/controller/TagController.java`
- `yan-admin/src/main/java/com/yan/admin/controller/LinkController.java`

### 4. 评论与回复模块

解决的问题：文章评论、留言评论、多级回复展示，以及评论通知。

主要功能：

- 根评论分页。
- 本页全部子评论一次查询并组装树。
- 批量查询评论用户昵称，避免循环查库。
- 评论或回复后通知作者或被回复人。
- 热点文章评论第一页缓存。

核心接口：

- `GET /comment/commentList`
- `POST /comment`
- `GET /comment/linkCommentList`

涉及数据表：

- `yan_comment`
- `yan_article`
- `sys_user`
- `yan_notification_delivery_outbox`
- `yan_user_notification`

相关代码：

- `yan-blog/src/main/java/com/yan/blog/controller/CommentController.java`
- `yan-content/src/main/java/com/yan/service/impl/CommentServiceImpl.java`
- `docs/sql/V20260718__comment_and_article_query_indexes.sql`

### 5. 搜索模块

解决的问题：文章标题、摘要、正文和标签搜索，降低 MySQL 模糊查询压力。

主要功能：

- 前台文章搜索。
- Elasticsearch 索引初始化。
- 管理端重建索引、重试同步、单篇同步。
- 搜索参数治理：关键词长度、页大小、页码深度、排序。
- 搜索结果缓存：只缓存第一页、小页码、非随机关键词，并对空结果设置较短 TTL。
- 文章变更后通过 outbox + Redis Stream 异步同步 ES。

核心接口：

- `GET /search`
- `POST /content/search/rebuild`
- `POST /content/search/retry`
- `POST /content/search/sync/{articleId}`

涉及数据表：

- `yan_article`
- `yan_article_tag`
- `yan_category`
- `yan_tag`
- `yan_article_search_outbox`

相关代码：

- `yan-blog/src/main/java/com/yan/blog/controller/SearchController.java`
- `yan-admin/src/main/java/com/yan/admin/controller/SearchController.java`
- `yan-content/src/main/java/com/yan/search/service/impl/ArticleSearchServiceImpl.java`
- `yan-content/src/main/java/com/yan/search/service/SearchQueryPlanner.java`
- `yan-content/src/main/java/com/yan/search/service/ArticleSearchCache.java`
- `yan-content/src/main/java/com/yan/search/repository/ElasticsearchArticleSearchRepository.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxService.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxConsumer.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxProcessor.java`
- 前台搜索页：`frond/sg-blog-vue/src/pages/Search.vue`

### 6. 用户主页与关注模块

解决的问题：用户可以查看自己或他人的主页、文章列表、关注关系；关注动作可产生通知。

主要功能：

- 查看用户公开主页和发表文章。
- 关注、取消关注、查询关注状态。
- 查看我的关注、我的粉丝、他人的关注列表。

核心接口：

- `GET /user/profile/{id}`
- `PUT /follow/{followeeId}`
- `DELETE /follow/{followeeId}`
- `GET /follow/status/{followeeId}`
- `GET /follow/following`
- `GET /follow/followers`
- `GET /follow/{userId}/following`

涉及数据表：

- `sys_user`
- `yan_article`
- `yan_user_follow`
- `yan_notification_delivery_outbox`
- `yan_user_notification`

相关代码：

- `yan-blog/src/main/java/com/yan/blog/controller/UserController.java`
- `yan-blog/src/main/java/com/yan/blog/controller/UserFollowController.java`
- `yan-content/src/main/java/com/yan/service/impl/UserProfileServiceImpl.java`
- `yan-content/src/main/java/com/yan/service/impl/UserFollowServiceImpl.java`
- 前台主页页：`frond/sg-blog-vue/src/pages/UserProfile.vue`
- 前台关注 API：`frond/sg-blog-vue/src/api/social.js`

### 7. 通知模块

解决的问题：用户能收到关注、评论、回复、文章发布等通知，并支持查看、已读、全部已读、删除。

主要功能：

- 通知收件箱分页。
- 未读数量。
- 标记单条已读、全部已读。
- 删除通知。
- 点对点通知可靠投递。
- 文章发布通知粉丝 fanout；大作者发布文章记录事件，避免发布瞬间写爆通知表。

核心接口：

- `GET /notification/list`
- `GET /notification/unread-count`
- `PUT /notification/{id}/read`
- `PUT /notification/read-all`
- `DELETE /notification/{id}`

涉及数据表：

- `yan_user_notification`
- `yan_notification_delivery_outbox`
- `yan_notification_outbox`
- `yan_author_publish_event`
- `yan_user_follow`

相关代码：

- `yan-blog/src/main/java/com/yan/blog/controller/UserNotificationController.java`
- `yan-content/src/main/java/com/yan/service/impl/UserNotificationServiceImpl.java`
- `yan-content/src/main/java/com/yan/notification/NotificationDeliveryOutboxService.java`
- `yan-content/src/main/java/com/yan/notification/NotificationFanoutOutboxService.java`
- `yan-content/src/main/java/com/yan/notification/NotificationDeliveryOutboxConsumer.java`
- `yan-content/src/main/java/com/yan/notification/NotificationFanoutOutboxConsumer.java`
- 前台通知页：`frond/sg-blog-vue/src/pages/Notifications.vue`

### 8. 上传模块

解决的问题：文章封面或 Markdown 正文图片上传。

主要功能：

- 后台图片上传。
- 使用 OSS 生成文件路径并上传。

核心接口：

- `POST /upload`
- `POST /api/admin/upload`

涉及数据表：

- 当前上传实现未发现独立文件表。

相关代码：

- `yan-admin/src/main/java/com/yan/admin/controller/UploadController.java`
- `yan-content/src/main/java/com/yan/service/impl/UploadServiceImpl.java`
- 后台写作页图片上传：`frond/sg-vue-admin/src/views/content/article/write/index.vue`

### 9. 异步任务运维模块

解决的问题：让搜索同步、通知 fanout、通知投递、缓存失效补偿等后台任务可观察、可重试。

主要功能：

- 查看 outbox 汇总。
- 查看搜索和通知任务分页。
- 手动重试搜索、通知或全部 pending/failed 任务。
- 统计 pending、processing、expired processing、failed、process_count、Redis Stream 长度和 pending 数。

核心接口：

- `GET /ops/outbox/summary`
- `GET /ops/outbox/search`
- `GET /ops/outbox/notification`
- `POST /ops/outbox/search/retry`
- `POST /ops/outbox/notification/retry`
- `POST /ops/outbox/retry`

涉及数据表：

- `yan_article_search_outbox`
- `yan_notification_outbox`
- `yan_notification_delivery_outbox`
- `yan_cache_invalidation_outbox`

相关代码：

- `yan-admin/src/main/java/com/yan/admin/controller/AsyncTaskOpsController.java`
- `yan-content/src/main/java/com/yan/operations/AsyncTaskOpsService.java`

## 六、完整业务流程

### 流程 1：前台登录

用户操作 → 用户在前台登录页输入账号密码。  
前端请求 → `frond/sg-blog-vue/src/api/user.js` 调用 `POST /auth/login`。  
Controller → `BlogLoginController.login` 接收 `User`。  
Service → `BlogLoginServiceImpl.login` 调用 `AuthenticationManager` 校验用户名密码。  
数据库/中间件 → `UserDetailsServiceImpl.loadUserByUsername` 通过 `UserMapper` 查 `sys_user`；登录成功后生成 blog scope JWT，并把 `LoginUser` 写入 Redis。  
返回结果 → 返回 token 和 `UserInfoVo`。  
前端展示 → 前端保存 token，顶部展示头像、通知入口、写博客入口。  

代码依据：

- `frond/sg-blog-vue/src/api/user.js`
- `yan-blog/src/main/java/com/yan/blog/controller/BlogLoginController.java`
- `yan-framework/src/main/java/com/yan/service/impl/BlogLoginServiceImpl.java`
- `yan-system/src/main/java/com/yan/service/impl/UserDetailsServiceImpl.java`

### 流程 2：发布文章并异步同步搜索和通知粉丝

用户操作 → 用户在前台或后台写文章并点击发布。  
前端请求 → 前台 `frond/sg-blog-vue/src/pages/WriteArticle.vue` 调用 `publishArticle`；后台 `frond/sg-vue-admin/src/views/content/article/write/index.vue` 调用 `addArticle`。  
Controller → 前台 `ArticleController.publishArticle` 或后台 `ArticleController.addArticle`。  
Service → `ArticleServiceImpl.addArticle` 校验标题、正文、分类、标签、状态、置顶和评论开关。  
数据库/中间件 → 保存 `yan_article`，批量保存 `yan_article_tag`；提交后清理文章详情、热榜、分类、评论第一页、搜索缓存；写入 `yan_article_search_outbox`；如果是发布状态，写入 `yan_notification_outbox`。  
异步任务 → `ArticleSearchOutboxService.deliverPending` claim 任务并发布到 Redis Stream；`ArticleSearchOutboxConsumer.consume` 批量消费；`ArticleSearchOutboxProcessor.process` 调用 `ArticleSearchServiceImpl.synchronizeForOutbox`；`ElasticsearchArticleSearchRepository.bulkSynchronize` 使用 ES Bulk API 同步索引。通知链路由 `NotificationFanoutOutboxService` 按粉丝游标批量写入通知或记录大作者发文事件。  
返回结果 → 文章发布接口先返回成功，ES 与通知异步最终完成。  
前端展示 → 发布成功跳转文章列表或用户主页，后续搜索可搜到文章，粉丝通知中心可看到文章发布通知。  

代码依据：

- `yan-content/src/main/java/com/yan/service/impl/ArticleServiceImpl.java` 的 `addArticle`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxService.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxConsumer.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxProcessor.java`
- `yan-content/src/main/java/com/yan/search/repository/ElasticsearchArticleSearchRepository.java`
- `yan-content/src/main/java/com/yan/notification/NotificationFanoutOutboxService.java`

### 流程 3：文章搜索与加载更多

用户操作 → 用户在顶部搜索框或搜索页输入关键词。  
前端请求 → `frond/sg-blog-vue/src/pages/Search.vue` 调用 `searchArticles`，默认 `pageSize=5`，滚动到底部后递增 `pageNum` 加载更多。  
Controller → `SearchController.search` 接收 `keyword/pageNum/pageSize/sort`。  
Service → `ArticleSearchServiceImpl.search` 使用 `SearchQueryPlanner.plan` 做参数治理，先读 `ArticleSearchCache`，未命中再查 ES。  
数据库/中间件 → ES 查询文章索引；可缓存的第一页搜索结果写入 Redis，空结果使用较短 TTL，且缓存前有 admission 计数，避免大量随机词直接写爆 Redis。  
返回结果 → 返回 `PageVo(rows,total)`。  
前端展示 → 搜索页追加渲染文章列表，直到 `articles.length >= total`。  

代码依据：

- `frond/sg-blog-vue/src/pages/Search.vue`
- `frond/sg-blog-vue/src/api/search.js`
- `yan-blog/src/main/java/com/yan/blog/controller/SearchController.java`
- `yan-content/src/main/java/com/yan/search/service/impl/ArticleSearchServiceImpl.java`
- `yan-content/src/main/java/com/yan/search/service/SearchQueryPlanner.java`
- `yan-content/src/main/java/com/yan/search/service/ArticleSearchCache.java`

### 流程 4：评论或回复后生成通知

用户操作 → 用户在文章详情页评论或回复。  
前端请求 → `frond/sg-blog-vue/src/api/comment.js` 调用 `POST /comment`。  
Controller → `CommentController.addComment` 接收 `AddCommentDto` 并转为 `Comment`。  
Service → `CommentServiceImpl.addComment` 校验内容非空、保存评论、删除评论第一页缓存、创建评论通知。  
数据库/中间件 → 保存 `yan_comment`；读取文章判断接收人：普通评论通知作者，回复通知被回复用户；调用 `UserNotificationServiceImpl.notify`；如果存在 `NotificationDeliveryOutboxService`，写入 `yan_notification_delivery_outbox`。  
异步任务 → `NotificationDeliveryOutboxService.deliverPending` 或 Redis Stream 消费者投递通知，最终 `INSERT IGNORE` 写入 `yan_user_notification`，并用 `dedupe_key` 保证幂等。  
返回结果 → 评论接口返回成功。  
前端展示 → 评论区刷新后看到评论；通知接收人进入通知中心可查看评论内容，并可跳转文章或回复。  

代码依据：

- `yan-blog/src/main/java/com/yan/blog/controller/CommentController.java`
- `yan-content/src/main/java/com/yan/service/impl/CommentServiceImpl.java`
- `yan-content/src/main/java/com/yan/service/impl/UserNotificationServiceImpl.java`
- `yan-content/src/main/java/com/yan/notification/NotificationDeliveryOutboxService.java`
- `yan-content/src/main/resources/mapper/UserNotificationMapper.xml`
- 前台通知页：`frond/sg-blog-vue/src/pages/Notifications.vue`

### 流程 5：文章详情与浏览量写入

用户操作 → 用户打开文章详情页。  
前端请求 → `frond/sg-blog-vue/src/api/article.js` 调用 `GET /article/{id}` 获取详情，并调用 `PUT /article/updateViewCount/{id}` 增加浏览量。  
Controller → `ArticleController.getArticleDetail`、`ArticleController.updateViewCount`。  
Service → `ArticleServiceImpl.getArticleDetail` 先查 Redis 缓存，未命中查库；`ArticleServiceImpl.updateViewCount` 调用 `ArticleCounterServiceImpl.increaseViewCount`。  
数据库/中间件 → 详情缓存使用 Redis；浏览量使用 Redis Hash 聚合、Set 标记脏数据、Lua 脚本做去重和原子递增；定时任务 `UpdateViewCountJob.updateViewCount` 批量刷回 MySQL。  
返回结果 → 返回文章详情和最新浏览量。  
前端展示 → 详情页显示文章内容、作者、分类、浏览量等。  

代码依据：

- `yan-content/src/main/java/com/yan/service/impl/ArticleServiceImpl.java`
- `yan-content/src/main/java/com/yan/service/impl/ArticleCounterServiceImpl.java`
- `yan-blog/src/main/java/com/yan/blog/job/UpdateViewCountJob.java`

## 七、后端请求处理流程

一个请求进入系统后的主要链路：

1. HTTP 请求进入 Spring Boot 内置 Tomcat。Tomcat 端口和线程/连接配置见 `yan-admin/src/main/resources/application.yml`。
2. CORS、JSON、Web 配置由 `yan-framework/src/main/java/com/yan/config/WebConfig.java` 处理。
3. `JwtAuthenticationTokenFilter.doFilterInternal` 从请求头 `token` 解析 JWT，校验 scope，按 scope + userId 从 Redis 读取 `LoginUser`，写入 `SecurityContextHolder`。
4. `SecurityConfig.securityFilterChain` 判断路径权限：公开 GET 接口放行；后台 `/api/admin/**`、`/system/**`、`/content/**` 等要求管理员 authority；其他接口要求登录。
5. Controller 接收参数并调用 Service。
6. Service 做业务校验，例如 `ArticleServiceImpl.validateArticle` 校验文章标题、正文、分类、标签、状态字段；`CommentServiceImpl.addComment` 校验评论内容。
7. 简单查询主要用 MyBatis-Plus 的 `LambdaQueryWrapper`、`Page`、`ServiceImpl`；复杂查询和批量更新使用 Mapper XML。
8. Redis 操作包括登录态、缓存、限流、浏览量计数、Redis Stream。
9. 异常由 `GlobalExceptionHandler` 统一转成 `ResponseResult`。
10. Controller 返回统一响应结构 `ResponseResult(code,msg,data)`。
11. 前端 Axios 响应拦截器根据 `code` 处理成功、401 重新登录、500 错误提示。

代码依据：

- 安全配置：`yan-admin/src/main/java/com/yan/admin/config/SecurityConfig.java`
- JWT 过滤：`yan-admin/src/main/java/com/yan/admin/filter/JwtAuthenticationTokenFilter.java`
- 异常处理：`yan-framework/src/main/java/com/yan/handler/exception/GlobalExceptionHandler.java`
- 统一响应：`yan-common/src/main/java/com/yan/domain/ResponseResult.java`
- 前台请求封装：`frond/sg-blog-vue/src/utils/request.js`
- 后台请求封装：`frond/sg-vue-admin/src/utils/request.js`

## 八、数据库设计

### 核心数据表

| 表名 | 作用 | 主键 | 关联字段与说明 | 已有索引依据 |
|---|---|---|---|---|
| `sys_user` | 用户表 | `id` | 与 `sys_user_role.user_id`、`yan_article.create_by`、`yan_comment.create_by`、关注/通知用户字段关联 | 当前读取到主键 |
| `sys_role` | 角色表 | `id` | 与 `sys_user_role.role_id`、`sys_role_menu.role_id` 关联 | 当前读取到主键 |
| `sys_menu` | 菜单/权限表 | `id` | `parent_id` 形成菜单树；`perms` 表示权限标识 | 当前读取到主键 |
| `sys_user_role` | 用户角色关系 | 复合主键 `user_id,role_id` | 多对多关系表 | 当前读取到复合主键 |
| `sys_role_menu` | 角色菜单关系 | 复合主键 `role_id,menu_id` | 多对多关系表 | 当前读取到复合主键 |
| `yan_article` | 文章表 | `id` | `category_id` 关联分类；`create_by` 关联作者 | `idx_article_public_list(status,del_flag,is_top)`、`idx_article_hot(status,del_flag,view_count)` |
| `yan_article_tag` | 文章标签关系 | 复合主键 `article_id,tag_id` | 文章和标签多对多 | 复合主键覆盖按文章查标签 |
| `yan_category` | 分类表 | `id` | `pid` 支持父子分类 | 当前读取到主键 |
| `yan_tag` | 标签表 | `id` | 与 `yan_article_tag.tag_id` 关联 | 当前读取到主键 |
| `yan_comment` | 评论/回复表 | `id` | `article_id` 关联文章；`root_id` 表示根评论；`to_comment_user_id`/`to_comment_id` 表示回复对象 | `idx_comment_root_page(type,root_id,del_flag,article_id,create_time)`、`idx_comment_children(root_id,del_flag,create_time)` |
| `yan_link` | 友情链接 | `id` | 无强制外键 | 当前读取到主键 |
| `yan_user_follow` | 关注关系 | `id` | `follower_id` 关注 `followee_id` | `uk_yan_user_follow_pair(follower_id,followee_id)`、`idx_yan_user_follow_followee(followee_id,id)` |
| `yan_user_notification` | 用户通知收件箱 | `id` | `receiver_id`、`actor_id`、`article_id`、`comment_id` | `uk_user_notification_dedupe_key(dedupe_key)`、`idx_yan_user_notification_receiver_article_type`、`idx_yan_user_notification_comment` |
| `yan_article_search_outbox` | 文章搜索同步 outbox | `id` | `article_id` 关联文章；`task_key` 幂等 | `uk_article_search_outbox_task_key`、pending/claim/article 索引 |
| `yan_notification_outbox` | 文章发布粉丝通知 fanout outbox | `id` | `author_id`、`article_id`、`cursor_follower_id` | `uk_notification_outbox_task_key`、pending 索引 |
| `yan_notification_delivery_outbox` | 点对点通知投递 outbox | `id` | 通知落库所需 receiver/actor/article/comment 字段 | `uk_notification_delivery_task_key`、`idx_notification_delivery_pending` |
| `yan_author_publish_event` | 大作者发文事件 | `id` | `author_id`、`article_id` | `uk_author_publish_article(article_id)`、`idx_author_publish_author(author_id,id)` |
| `yan_cache_invalidation_outbox` | 缓存删除失败补偿 | `id` | `cache_key`、`pattern_flag` | `uk_cache_invalidation_task_key`、pending 索引 |

### 表关系分析

- 用户、角色、菜单是典型 RBAC：`sys_user` ↔ `sys_user_role` ↔ `sys_role` ↔ `sys_role_menu` ↔ `sys_menu`。
- 文章归属一个分类：`yan_article.category_id` → `yan_category.id`。
- 文章和标签多对多：`yan_article` ↔ `yan_article_tag` ↔ `yan_tag`。
- 评论属于文章，回复通过 `root_id` 挂在根评论下，通过 `to_comment_id` 和 `to_comment_user_id` 指向回复目标。
- 关注关系通过 `yan_user_follow.follower_id/followee_id` 表示用户间关系。
- 通知由 `yan_user_notification.receiver_id` 指向接收人，由 `actor_id` 指向触发人，并可关联文章或评论。
- Outbox 表与业务表通过业务 ID 弱关联，使用 `task_key` 或 `dedupe_key` 做幂等。

代码/SQL 依据：

- 索引脚本：`docs/sql/V20260718__comment_and_article_query_indexes.sql`
- 通知投递表：`docs/sql/V20260726_3__notification_delivery_outbox.sql`
- 通知幂等修复：`docs/sql/V20260728__fix_notification_dedupe_unique_key.sql`
- Mapper XML：`yan-content/src/main/resources/mapper/*.xml`

## 九、项目难点

### 难点 1：文章发布后 ES 索引同步的可靠性

业务场景：文章新增、修改、删除、状态变更后，搜索索引必须最终与 MySQL 一致。  
出现的问题：如果在文章事务内直接调用 ES，ES 异常会影响主流程；如果只异步调用，可能丢任务。  
问题原因：MySQL 和 Elasticsearch 不在同一个事务中，天然存在分布式一致性问题。  
解决方案：文章写入成功后写 `yan_article_search_outbox`，后台定时任务 claim，Redis Stream 转发，消费者批量消费，再调用 ES Bulk API。  
核心实现：

- `ArticleServiceImpl.addArticle/updateArticle/deleteArticle/updateArticleStatus` 调用 `articleSearchOutboxService.enqueue`。
- `ArticleSearchOutboxMapper.xml` 使用 `task_key` 唯一键、`PENDING/PROCESSING/SUCCEEDED/FAILED` 状态、`locked_until` 租约、`retry_count/max_retry` 重试。
- `ArticleSearchOutboxConsumer.consume` 批量消费 Redis Stream。
- `ArticleSearchOutboxProcessor.process` 批量调用 `ArticleSearchServiceImpl.synchronizeForOutbox`。
- `ElasticsearchArticleSearchRepository.bulkSynchronize` 使用 ES Bulk API。

相关代码位置：

- `yan-content/src/main/java/com/yan/service/impl/ArticleServiceImpl.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxService.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxConsumer.java`
- `yan-content/src/main/java/com/yan/search/outbox/ArticleSearchOutboxProcessor.java`
- `yan-content/src/main/resources/mapper/ArticleSearchOutboxMapper.xml`

方案优点：主事务不依赖 ES；任务可重试、可恢复过期 claim、可死信；批量消费后吞吐更高。  
方案不足：实现复杂度高于直接同步；需要运维关注 outbox 积压和死信；MySQL 到 ES 是最终一致而非强一致。

### 难点 2：大作者发布文章的粉丝通知写扩散

业务场景：一个粉丝很多的作者发布文章，要通知粉丝。  
出现的问题：如果发布事务内一次性给所有粉丝写通知，粉丝量大时会拖垮发布接口和数据库。  
问题原因：文章发布是单次写入，但通知是按粉丝数放大的写扩散。  
解决方案：发布文章时只写通知 outbox；后台按 `cursor_follower_id` 分批 fanout；当粉丝数达到大作者阈值时，记录 `yan_author_publish_event`，避免直接写入大量通知。  
核心实现：

- `ArticleServiceImpl.addArticle` 发布状态下调用 `notificationFanoutOutboxService.enqueue`。
- `NotificationFanoutOutboxService.deliver` 使用 `countFollowers` 判断是否达到 `largeAuthorThreshold`。
- 普通作者通过 `selectFollowerIdsAfter` 按游标分页写通知。
- 大作者调用 `insertAuthorPublishEvent` 后标记 outbox 成功。

相关代码位置：

- `yan-content/src/main/java/com/yan/service/impl/ArticleServiceImpl.java`
- `yan-content/src/main/java/com/yan/notification/NotificationFanoutOutboxService.java`
- `yan-content/src/main/resources/mapper/NotificationOutboxMapper.xml`
- `docs/sql/V20260720_3__notification_outbox_and_author_stream.sql`

方案优点：发布接口不会因粉丝量暴涨而同步写爆数据库；普通作者通知可最终送达；大作者事件可作为后续“粉丝拉取/懒加载通知”的基础。  
方案不足：大作者 `yan_author_publish_event` 目前只是事件落库，后续如果要让所有粉丝都有显式通知，还需要补拉取侧或懒加载侧实现。

### 难点 3：评论多级回复查询与 N+1 查询问题

业务场景：文章详情页展示根评论和多级回复。  
出现的问题：按根评论循环查询子评论和用户信息会导致 SQL 数量随根评论数增长。  
问题原因：旧思路容易按 `to_comment_id` 查直接回复，漏掉回复的回复，并产生 N+1 查询。  
解决方案：先分页查询根评论，再用 `root_id IN (...)` 一次查询本页全部子评论；把根评论和子评论涉及的用户 ID 合并后批量查用户昵称。  
核心实现：

- `CommentServiceImpl.loadCommentList` 查询根评论和本页全部子评论。
- `CommentServiceImpl.loadUserNames` 批量加载创建人和被回复人昵称。
- `CommentServiceImpl.assembleCommentTree` 按 `root_id` 组装树。
- `idx_comment_root_page` 和 `idx_comment_children` 支撑根评论分页和子评论查询。

相关代码位置：

- `yan-content/src/main/java/com/yan/service/impl/CommentServiceImpl.java`
- `docs/sql/V20260718__comment_and_article_query_indexes.sql`
- `docs/性能优化验证报告.md`

方案优点：非空页面 SQL 数量固定，能正确展示多级回复；用户昵称批量加载减少 DB 压力。  
方案不足：当前子评论是一次查出本页根评论下全部子评论，极端热点根评论下仍可能返回较大结果；后续可对子评论再做分页/折叠加载。

### 难点 4：Redis 缓存穿透、击穿、雪崩和删除失败补偿

业务场景：文章详情、热门文章、分类、评论第一页等高频读取接口需要缓存。  
出现的问题：缓存未命中会打到数据库；热点 key 失效可能击穿；大量 key 同时过期会雪崩；数据库写成功但缓存删除失败会造成脏读。  
问题原因：简单 get/set/delete 无法处理高并发和 Redis 异常边界。  
解决方案：封装 `CacheClient.queryWithMutex`：空值缓存防穿透、互斥锁防击穿、TTL 抖动防雪崩、Redis 异常降级数据库；删除缓存支持事务提交后执行，删除失败记录 `yan_cache_invalidation_outbox` 补偿。  
核心实现：

- `CacheClient.queryWithMutex` 使用 `__CACHE_NULL__`、`lock:`、随机 TTL。
- `CacheClient.deleteAfterCommit/deletePatternAfterCommit` 事务提交后删除缓存。
- `CacheClient.recordInvalidation` 删除失败时调用 `CacheInvalidationRecorder`。
- `CacheInvalidationOutboxService.retryPending` 定时重试删除失败的 key 或 pattern。

相关代码位置：

- `yan-framework/src/main/java/com/yan/utils/CacheClient.java`
- `yan-content/src/main/java/com/yan/cache/CacheInvalidationOutboxService.java`
- `docs/sql/V20260727__cache_invalidation_outbox.sql`
- `docs/缓存一致性与失效补偿设计.md`

方案优点：高频读取更稳，Redis 异常不会直接打断业务；缓存删除失败有补偿闭环。  
方案不足：互斥锁是简单 Redis 锁，不适合复杂分布式锁语义；pattern 删除依赖扫描 key，key 数很大时仍需谨慎。

### 难点 5：浏览量高并发写入与去重

业务场景：文章详情页每次访问都会更新浏览量。  
出现的问题：如果每次访问都直接 update MySQL，会造成数据库写热点；重复刷新会虚增浏览量。  
问题原因：浏览量是典型高频计数场景，直接写库成本高且并发冲突多。  
解决方案：用 Redis Hash 存文章浏览量，用 Redis Set 标记脏文章，用 Lua 脚本原子判断是否已发布、是否已有计数、是否重复访问，再 `HINCRBY`；定时批量刷回 MySQL。  
核心实现：

- `ArticleCounterServiceImpl.INCREMENT_SCRIPT` 做去重和原子递增。
- `ArticleCounterServiceImpl.flushDirtyCounters` 批量读取脏文章计数并 `updateViewCounts`。
- `UpdateViewCountJob.updateViewCount` 定时刷库。

相关代码位置：

- `yan-content/src/main/java/com/yan/service/impl/ArticleCounterServiceImpl.java`
- `yan-blog/src/main/java/com/yan/blog/job/UpdateViewCountJob.java`
- `yan-content/src/main/resources/mapper/ArticleMapper.xml`

方案优点：把高频写转为 Redis 原子操作和批量落库，降低 MySQL 压力；有访问指纹去重。  
方案不足：Redis 与 MySQL 最终一致；应用异常退出前未刷完的脏数据依赖下次任务继续处理。

### 难点 6：通知投递幂等与假成功修复

业务场景：同一用户可能在同一文章下收到多条评论或回复通知。  
出现的问题：压测发现 outbox 显示 10000 条成功，但通知表只新增 100 条。  
问题原因：旧唯一键 `(receiver_id, article_id, type)` 过粗，`INSERT IGNORE` 静默忽略后服务层仍标记 outbox 成功。  
解决方案：通知幂等统一改为 `dedupe_key`；删除错误唯一键，改为普通索引；`NotificationDeliveryOutboxService.deliver` 检查 `insertIgnore` 返回行数，插入 0 行时必须确认相同 `dedupe_key` 已存在，否则进入重试。  
核心实现：

- `docs/sql/V20260728__fix_notification_dedupe_unique_key.sql` 删除错误唯一键。
- `UserNotificationMapper.xml` 提供 `countByDedupeKey`。
- `NotificationDeliveryOutboxService.deliver` 校验 `insertIgnore` 结果。

相关代码位置：

- `yan-content/src/main/java/com/yan/notification/NotificationDeliveryOutboxService.java`
- `yan-content/src/main/resources/mapper/UserNotificationMapper.xml`
- `docs/sql/V20260728__fix_notification_dedupe_unique_key.sql`
- `docs/高强度压测报告-20260728.md`

方案优点：避免“业务数据没落库但任务成功”的严重一致性问题；幂等粒度更准确。  
方案不足：仍依赖业务方设计正确的 `dedupe_key`；如果 key 设计不合理，仍可能过度去重或重复通知。

## 十、项目优化点

### 优化点 1：Maven 多模块和统一依赖管理

优化前的问题：模块边界不清晰、依赖版本容易分散。  
问题定位方式：检查根 `pom.xml` 和子模块依赖。  
具体优化方案：根 `pom.xml` 使用 `<packaging>pom</packaging>`，统一模块列表和版本；子模块按职责依赖。  
相关代码位置：`pom.xml`、各模块 `pom.xml`。  
优化后的效果：形成类似若依的多模块单体：一个启动类管理多个业务模块，依赖版本集中。

### 优化点 2：MyBatis-Plus 简单查询 + MyBatis XML 复杂查询

优化前的问题：全部用一种持久层方式容易出现两种极端：简单 CRUD XML 冗余，复杂 SQL 用 Wrapper 不清晰。  
问题定位方式：检查 Service 和 Mapper XML。  
具体优化方案：简单分页/条件查询用 MyBatis-Plus；批量更新、claim、重试、批量插入、复杂 join 用 XML。  
相关代码位置：`yan-content/src/main/java/com/yan/service/impl/*.java`、`yan-content/src/main/resources/mapper/*.xml`。  
优化后的效果：代码可读性更好，复杂 SQL 可控、可调优。

### 优化点 3：评论查询 SQL 数量优化和索引优化

优化前的问题：评论树可能产生 N+1 查询。  
问题定位方式：`docs/性能优化验证报告.md` 记录旧实现 SQL 数量为 `3 + R + C`，最坏 `3 + 2R`。  
具体优化方案：根评论分页 + 子评论 `root_id IN` 批量查询 + 用户批量查询；新增评论和文章列表联合索引。  
相关代码位置：`CommentServiceImpl.loadCommentList/loadUserNames/assembleCommentTree`、`docs/sql/V20260718__comment_and_article_query_indexes.sql`。  
优化后的效果：文档记录非空评论页固定 4 条 SQL；公开文章列表和热门文章列表 EXPLAIN 命中联合索引。

### 优化点 4：文章详情、热榜、评论第一页缓存

优化前的问题：热点文章详情、热榜、评论第一页直接查库会增加数据库压力。  
问题定位方式：检查高频读取 Service。  
具体优化方案：`ArticleServiceImpl.hotArticleList/getArticleDetail`、`CommentServiceImpl.commentList` 使用 `CacheClient.queryWithMutex`。  
相关代码位置：`yan-content/src/main/java/com/yan/service/impl/ArticleServiceImpl.java`、`yan-content/src/main/java/com/yan/service/impl/CommentServiceImpl.java`、`yan-framework/src/main/java/com/yan/utils/CacheClient.java`。  
优化后的效果：热点读取走 Redis；无真实单项前后对比百分比，不编造。

### 优化点 5：缓存穿透、击穿、雪崩防护

优化前的问题：不存在数据、大热点 key 失效、集中失效都会增加 DB 压力。  
问题定位方式：检查缓存工具实现和搜索空结果压测。  
具体优化方案：空值缓存、互斥锁、TTL 抖动；搜索缓存加入 admission，避免随机不存在关键词大量写 Redis。  
相关代码位置：`CacheClient.queryWithMutex/writeCache`、`ArticleSearchCache.put/admitted`、`SearchQueryPlanner.cacheCandidate/looksRandom`。  
优化后的效果：`docs/高强度压测报告-20260728.md` 记录 300 VU / 10000 次搜索空结果通过，压测后 Redis key 数和内存未异常膨胀。

### 优化点 6：浏览量 Redis 聚合和定时批量落库

优化前的问题：每次阅读都写 MySQL 会形成热点写。  
问题定位方式：阅读量接口是高频写接口。  
具体优化方案：Redis Hash 计数、Set 记录 dirty ID、Lua 原子去重和递增、定时批量更新 DB。  
相关代码位置：`ArticleCounterServiceImpl.increaseViewCount/flushDirtyCounters`、`UpdateViewCountJob.updateViewCount`、`ArticleMapper.xml.updateViewCounts`。  
优化后的效果：`docs/高强度压测报告-20260728.md` 记录浏览量写入聚合 300 VU / 10000 次 HTTP 失败 0。

### 优化点 7：ES 搜索链路与结果分页加载

优化前的问题：MySQL LIKE 不适合文章全文检索；一次返回大量搜索结果会增加响应体和服务端压力。  
问题定位方式：检查搜索需求和前端页面。  
具体优化方案：使用 Elasticsearch 搜索；后端限制页大小和最大页码；前端默认 `pageSize=5`，滚动加载更多。  
相关代码位置：`ArticleSearchServiceImpl.search`、`SearchQueryPlanner.plan`、`ElasticsearchArticleSearchRepository.search`、`frond/sg-blog-vue/src/pages/Search.vue`。  
优化后的效果：减少一次性返回数据量；搜索空结果和搜索命中均有压测记录，但搜索命中仍需观察业务失败码。

### 优化点 8：ES Outbox + Redis Stream + Bulk API

优化前的问题：万级 ES 同步任务积压严重，早期压测 240 秒只完成少量任务。  
问题定位方式：`docs/高强度压测报告-20260728.md` 记录 P1-3：ES 同步消费者吞吐不足。  
具体优化方案：Redis Stream 消费侧按批 claim，`ArticleSearchOutboxProcessor.process(List)` 一次批处理，`ElasticsearchArticleSearchRepository.bulkSynchronize` 使用 Bulk API；配置 consumer-count 和 batch-size。  
相关代码位置：`ArticleSearchOutboxConsumer`、`ArticleSearchOutboxProcessor`、`ElasticsearchArticleSearchRepository`、`yan-admin/src/main/resources/application.yml`。  
优化后的效果：复测文件 `scripts/load/results/load_20260728_170328/async-results.json` 记录 10000/10000 ES outbox 最终成功。

### 优化点 9：通知可靠投递与幂等修复

优化前的问题：通知 outbox 假成功，通知表实际落库少。  
问题定位方式：压测对比 outbox 成功数和 `yan_user_notification` 实际写入数。  
具体优化方案：以 `dedupe_key` 做唯一幂等；`insertIgnore` 后校验返回行数；异常进入重试/死信。  
相关代码位置：`NotificationDeliveryOutboxService.deliver`、`UserNotificationMapper.xml.countByDedupeKey`、`docs/sql/V20260728__fix_notification_dedupe_unique_key.sql`。  
优化后的效果：复测文件记录直接通知投递 10000/10000 成功。

### 优化点 10：接口限流与防刷

优化前的问题：登录、搜索、评论、浏览量等接口容易被高频请求刷。  
问题定位方式：关键词扫描确认限流注解接入 Controller。  
具体优化方案：`@RateLimit` 注解 + AOP 切面 + Redis Lua 固定窗口限流；支持 IP、用户、参数等维度。  
相关代码位置：`yan-framework/src/main/java/com/yan/ratelimit/RateLimit.java`、`RateLimitAspect.java`、`RedisFixedWindowRateLimiter.java`，以及前台登录、搜索、评论、文章接口。  
优化后的效果：具备接口级防刷能力；仓库未记录限流前后 QPS 对比，不编造。

### 优化点 11：Actuator、Prometheus 指标和请求日志

优化前的问题：高并发测试后很难定位是 HTTP 失败、业务失败还是异步积压。  
问题定位方式：压测文档中提到需要补充业务码观测。  
具体优化方案：暴露 health/info/metrics/prometheus；搜索模块接入 Micrometer 指标；请求日志加入 requestId；k6 脚本加入 `business_failures`。  
相关代码位置：`yan-admin/src/main/resources/application.yml`、`yan-content/src/main/java/com/yan/search/metrics/ArticleSearchMetrics.java`、`yan-admin/src/main/java/com/yan/admin/filter/RequestLoggingFilter.java`、`scripts/load/high-concurrency-http.js`。  
优化后的效果：`docs/高强度压测报告-20260728.md` 能区分 HTTP 失败和业务码失败。

### 优化点 12：Tomcat 高并发参数调整

优化前的问题：1000 VU 混合压测出现连接拒绝。  
问题定位方式：k6 结果中的 `http_req_failed` 和压测报告。  
具体优化方案：调整 `accept-count=1000`、`max-connections=10000`、`threads.max=400`、`threads.min-spare=50`。  
相关代码位置：`yan-admin/src/main/resources/application.yml`。  
优化后的效果：`docs/高强度压测报告-20260728.md` 记录 1000 VU / 100000 warm 复测连接拒绝 34/100000；相较同规模优化前 218/100000 有明显下降。该数据来自仓库压测报告。

## 十一、项目亮点

### 业务亮点

1. 内容发布闭环：前台/后台均支持写文章，文章发布后会触发搜索同步和粉丝通知。依据：`ArticleServiceImpl.addArticle`、`frond/sg-blog-vue/src/pages/WriteArticle.vue`、`frond/sg-vue-admin/src/views/content/article/write/index.vue`。
2. 社交互动闭环：关注、主页、粉丝/关注列表、评论/回复通知、文章发布通知已形成链路。依据：`UserFollowServiceImpl`、`UserNotificationServiceImpl`、`CommentServiceImpl.createCommentNotification`。
3. 管理后台闭环：内容管理、系统用户/角色/菜单管理、异步任务运维均有 Controller 和前端页面/API。依据：`yan-admin/src/main/java/com/yan/admin/controller/*`、`frond/sg-vue-admin/src/views/*`。

### 技术亮点

1. Redis Stream 手写轻量消息队列能力：搜索同步、通知 fanout、通知投递均使用 outbox + Redis Stream + consumer group。依据：`ReliableTaskRedisStreamProperties`、`ArticleSearchOutboxConsumer`、`NotificationFanoutOutboxConsumer`、`NotificationDeliveryOutboxConsumer`。
2. 可靠任务模型：任务有唯一 `task_key`、状态机、租约、重试、指数退避、死信、处理次数统计。依据：`ReliableTaskPolicy`、各 Outbox Mapper XML、`AsyncTaskOpsService`。
3. ES 搜索链路：使用新版 `elasticsearch-java` 客户端、索引初始化、Bulk 同步、搜索缓存和指标。依据：`ElasticsearchConfig`、`SearchIndexInitializer`、`ElasticsearchArticleSearchRepository`、`ArticleSearchMetrics`。
4. Redis 缓存治理：空值缓存、互斥锁、TTL 抖动、缓存删除失败补偿。依据：`CacheClient`、`CacheInvalidationOutboxService`。
5. 浏览量高并发聚合：Redis Lua 原子递增、访问指纹去重、dirty set 批量刷库。依据：`ArticleCounterServiceImpl`、`UpdateViewCountJob`。
6. 评论查询优化：固定 SQL 数量、批量组装评论树、联合索引。依据：`CommentServiceImpl`、`docs/sql/V20260718__comment_and_article_query_indexes.sql`、`docs/性能优化验证报告.md`。

### 工程亮点

1. 多模块单体工程：根 POM 统一依赖版本，一个 `yan-admin` 启动类加载所有模块。依据：`pom.xml`、`BlogAdminApplication`。
2. 前后台统一后端端口：安全配置同时兼容 `/api/blog/**`、`/api/admin/**` 和旧路径。依据：`SecurityConfig`、前端 request/proxy 配置。
3. 测试和压测资产完整：有单元测试、k6 脚本、数据库造数脚本和压测报告。依据：`yan-content/src/test/**`、`yan-framework/src/test/**`、`scripts/load/**`、`docs/高强度压测报告-20260728.md`。
4. 可观测性：Actuator/Prometheus、请求 requestId、搜索指标、异步任务运维接口。依据：`application.yml`、`RequestLoggingFilter`、`ArticleSearchMetrics`、`AsyncTaskOpsController`。

## 十二、我可能负责的工作

由于 Git 记录较少，且当前工作区有大量未提交/未跟踪改动，无法从 Git 严格证明每一块均由你个人完成。以下内容适合写入简历前由本人确认。

可写成“需要本人确认”的个人职责：

1. 参与 YanBlog 从普通博客项目向高性能内容平台的重构，完成 Maven 多模块拆分与统一依赖管理。需要本人确认。依据：`pom.xml`。
2. 负责文章发布链路优化，接入 ES 搜索同步 outbox 和 Redis Stream 消费模型。需要本人确认。依据：`ArticleServiceImpl`、`ArticleSearchOutboxService`、`ArticleSearchOutboxConsumer`。
3. 负责通知系统可靠化，设计关注、评论/回复、文章发布通知和点对点通知 outbox。需要本人确认。依据：`UserNotificationServiceImpl`、`NotificationDeliveryOutboxService`、`NotificationFanoutOutboxService`。
4. 负责 Redis 缓存治理，处理缓存穿透、击穿、雪崩和缓存删除失败补偿。需要本人确认。依据：`CacheClient`、`CacheInvalidationOutboxService`。
5. 负责评论查询优化和联合索引设计，解决多级回复展示和 N+1 查询问题。需要本人确认。依据：`CommentServiceImpl`、`docs/sql/V20260718__comment_and_article_query_indexes.sql`。
6. 负责高并发压测脚本与结果分析，覆盖文章详情、浏览量、评论、搜索、热榜和异步任务。需要本人确认。依据：`scripts/load/**`、`docs/高强度压测报告-20260728.md`。

Git 记录依据：

- `git log` 当前可读到：`8511cf1 chore: integrate YanBlog optimization work`、`e6c77af fix(test): test`、`06bd864 feat(...)`。
- 当前 `git status` 显示大量修改和未跟踪文件，因此职责归属不能仅凭当前仓库自动判定。

## 十三、简历项目描述

项目介绍：YanBlog 是一个基于 Spring Boot 3 + Vue2 的博客内容平台，包含前台文章阅读/搜索/评论/关注/通知，以及后台内容管理、系统管理、上传和异步任务运维能力。后端采用 Maven 多模块单体架构，使用 MySQL、Redis、Elasticsearch 和阿里云 OSS。

技术栈：Java 17、Spring Boot 3、Spring Security、MyBatis-Plus、MyBatis XML、MySQL、Redis、Redis Stream、Elasticsearch Java Client、Aliyun OSS、Vue2、Element UI、Maven、k6。

可用于简历的 8 条描述：

1. 重构项目为 Maven 多模块单体架构，统一管理 Spring Boot、MyBatis-Plus、Redis、ES、OSS 等依赖版本，解决模块职责混乱和依赖分散问题。
2. 基于 Spring Security + JWT + Redis 实现前台用户和后台管理员两套登录态，通过 token scope 区分 blog/admin 权限边界，保护后台管理接口。
3. 设计文章发布后的 ES 同步 outbox，结合 Redis Stream consumer group 和 Elasticsearch Bulk API，实现文章索引的异步可靠同步。
4. 优化评论列表查询，将根评论分页、子评论批量查询和用户昵称批量查询组合，解决多级回复展示和 N+1 查询问题，并补充评论联合索引。
5. 封装 Redis 缓存工具，使用空值缓存、互斥锁、TTL 随机抖动和缓存失效补偿，降低热点文章详情、热榜、分类和评论第一页的数据库压力。
6. 基于 Redis Lua 实现文章浏览量原子递增、访问指纹去重和 dirty set 批量刷库，避免高频阅读请求直接写 MySQL。
7. 设计通知可靠投递链路，支持关注、评论、回复和文章发布通知，使用 `dedupe_key` 保证幂等，并修复 `INSERT IGNORE` 导致的 outbox 假成功问题。
8. 编写 k6 与 Python 压测脚本，对文章详情、浏览量、评论、搜索、热榜和异步 outbox 进行千级/万级/10 万级压测，并根据结果调整 Tomcat 和异步消费配置。

项目难点：分布式一致性、缓存一致性、搜索同步可靠性、大粉丝量通知 fanout、评论树查询优化、浏览量高并发聚合。

项目亮点：Redis Stream 轻量消息队列、Outbox 可靠任务、ES Bulk 同步、缓存治理、限流防刷、可观测压测闭环。

## 十四、面试介绍稿

### 30 秒项目介绍

YanBlog 是一个 Spring Boot 3 + Vue2 的博客内容平台，前台支持文章阅读、搜索、评论、关注和通知，后台支持文章、分类、标签、友链、用户角色菜单管理。后端是 Maven 多模块单体架构，一个 `yan-admin` 启动类加载所有模块。我主要会讲它的几个优化点：Redis 缓存治理、浏览量 Redis 聚合、评论查询优化、Elasticsearch 搜索同步，以及基于 Outbox + Redis Stream 的可靠异步任务。

### 1 分钟项目介绍

YanBlog 是一个面向博客内容发布和阅读的平台，分为前台博客和后台管理。前台用户可以看文章、搜索、评论回复、关注作者、接收通知，也可以写博客；后台管理员可以维护文章、分类、标签、友链、用户、角色和菜单。后端采用 Spring Boot 3 多模块单体架构，用 Spring Security + JWT + Redis 做登录认证，用 MyBatis-Plus 处理简单查询，复杂查询和批量任务用 MyBatis XML。项目里比较有技术价值的是：文章发布后通过 Outbox + Redis Stream 异步同步 ES；评论通知和粉丝通知也做了可靠投递和幂等；热点文章详情、热榜、评论第一页做了 Redis 缓存；浏览量用 Redis Lua 聚合后定时批量落库。最后我也用 k6 做了千级、万级和 10 万级压测，并根据压测结果修复了通知 outbox 假成功和 ES 消费吞吐不足的问题。

### 3 分钟项目介绍

YanBlog 是一个博客内容平台，前台面向普通用户，后台面向管理员。普通用户可以浏览文章、查看热门文章、搜索文章、评论或回复、关注作者、进入个人主页查看文章和关注列表，也能收到关注、评论、回复和文章发布通知。后台主要负责文章、分类、标签、友链、用户、角色、菜单管理，以及搜索索引和异步任务运维。

后端架构上，它不是微服务，而是 Maven 多模块单体。根工程统一管理依赖版本，`yan-admin` 是唯一启动模块，`yan-blog` 放前台接口，`yan-content` 放内容、搜索、通知等核心业务，`yan-system` 放用户角色菜单，`yan-framework` 放安全、Redis、缓存、限流、异常等基础能力，`yan-common` 放 DTO/VO/Entity 和工具。前后端通过同一个 8989 后端端口交互，前台走 `/api/blog/**`，后台走 `/api/admin/**`，同时兼容旧路径。

技术上我重点做了几条优化链路。第一是文章搜索：文章发布、修改、删除后不直接同步 ES，而是写 `yan_article_search_outbox`，后台任务 claim 后发布到 Redis Stream，消费者批量消费并使用 Elasticsearch Bulk API 同步，实现最终一致和失败重试。第二是通知系统：评论、回复、关注这类点对点通知进入 delivery outbox；文章发布通知进入 fanout outbox，普通作者按粉丝游标批量写通知，大作者先记录发文事件，避免一次发布直接写爆通知表。第三是缓存：文章详情、热榜、分类和评论第一页使用 Redis 缓存，封装了空值缓存、互斥锁和 TTL 抖动，还做了缓存删除失败后的 outbox 补偿。第四是浏览量：用 Redis Lua 做原子递增和访问去重，用 dirty set 定时批量刷回 MySQL，避免每次访问都写库。第五是评论查询：根评论分页后批量查询本页全部子评论，再批量查用户昵称，解决 N+1 和多级回复展示问题。

项目最后有测试和压测验证。仓库里保留了单元测试、k6 脚本、数据库造数脚本和压测报告。压测发现过 ES 同步吞吐不足、通知 outbox 假成功、后台任务依赖登录态等问题，后续分别通过批量消费、`dedupe_key` 幂等校验、后台线程系统用户兜底等方式修复。这个项目适合展示我对 Java 后端工程分层、缓存、异步可靠任务、搜索、数据库索引和高并发压测的理解。

## 十五、面试追问题

1. 问：这个项目是微服务吗？  
   答：不是，是 Maven 多模块单体架构。只有 `yan-admin` 一个 Spring Boot 启动类，其他模块作为依赖被扫描加载。依据是根 `pom.xml` 和 `BlogAdminApplication`。

2. 问：为什么做成多模块单体而不是微服务？  
   答：项目规模还没到必须拆微服务的程度，多模块能先把边界理清，降低部署复杂度；文章、评论、搜索、通知都在一个进程内，事务和调试更简单。

3. 问：前台和后台怎么区分权限？  
   答：JWT 里有 scope，前台是 blog，后台是 admin；Redis 登录态 key 也按 scope + userId 存储；`SecurityConfig` 对后台路径要求 admin authority。

4. 问：token 过期或 Redis 登录态丢失会怎样？  
   答：`JwtAuthenticationTokenFilter` 解析失败或 Redis 查不到 `LoginUser` 会返回 401，前端 Axios 拦截器会引导重新登录。

5. 问：后台菜单路由怎么来？  
   答：后端有 `AdminLoginController.getRouters` 和 `MenuServiceImpl.getRouters`，前端后台有动态路由模块；但当前前端 `permission.js` 中也存在静态路由注入逻辑，动态路由部分需要结合实际运行确认。

6. 问：为什么简单查询用 MyBatis-Plus，复杂查询用 MyBatis？  
   答：简单 CRUD 和分页用 MP 可以减少模板代码；outbox claim、批量插入、批量更新、复杂 join 需要精确 SQL 和索引控制，所以写 XML。

7. 问：评论查询怎么优化的？  
   答：根评论分页，子评论通过 `root_id IN` 一次查出，再批量查用户昵称并组装树；避免按根评论循环查子评论和用户导致 N+1。

8. 问：评论表索引怎么设计？  
   答：根评论分页使用 `(type, root_id, del_flag, article_id, create_time)`；子评论使用 `(root_id, del_flag, create_time)`。依据是 `V20260718__comment_and_article_query_indexes.sql`。

9. 问：文章列表索引怎么设计？  
   答：公开列表使用 `(status, del_flag, is_top)`，热门列表使用 `(status, del_flag, view_count)`，对应状态过滤和排序字段。

10. 问：Redis 在项目里用在哪些地方？  
    答：登录态、缓存、搜索结果缓存、限流、浏览量计数、Redis Stream 可靠任务。

11. 问：怎么解决缓存穿透？  
    答：`CacheClient` 对数据库查不到的数据写入 `__CACHE_NULL__`，并设置较短 TTL，避免不存在数据重复打到数据库。

12. 问：怎么解决缓存击穿？  
    答：`CacheClient.queryWithMutex` 使用 Redis `setIfAbsent` 互斥锁，一个线程回源数据库并写缓存，其他线程短暂等待后重读缓存。

13. 问：怎么解决缓存雪崩？  
    答：缓存写入时在基础 TTL 上增加随机 jitter，避免大量 key 同时过期。

14. 问：缓存和数据库如何保持一致？  
    答：写数据库后不立即在事务内删缓存，而是 `deleteAfterCommit` 在事务提交后删除；如果 Redis 删除失败，则记录缓存失效 outbox，由后台任务补偿重试。

15. 问：浏览量为什么不直接写 MySQL？  
    答：浏览量是高频写，直接写 MySQL 会形成热点。项目用 Redis Hash 聚合，Lua 原子递增，dirty set 标记待刷库文章，再定时批量更新 MySQL。

16. 问：浏览量如何防重复刷新？  
    答：Lua 脚本中用 `ARTICLE_VIEWED_PREFIX + articleId + visitorFingerprint` 做去重 key，设置过期时间，重复访问直接返回 0。

17. 问：搜索为什么用 Elasticsearch？  
    答：文章标题、摘要、正文和标签适合全文检索；MySQL LIKE 在数据变大后性能和相关性都不理想。

18. 问：ES 索引和 MySQL 如何一致？  
    答：文章变更后写 outbox，后台异步同步 ES；失败会重试，超过最大次数进 FAILED/死信，是最终一致。

19. 问：为什么还要 Redis Stream，直接扫 outbox 不行吗？  
    答：直接扫 outbox 可以做兜底，但 Redis Stream 提供消费者组、pending、ack 等能力，适合把已 claim 的任务分发给多个消费者，提高吞吐。

20. 问：Redis Stream 队列上限是多少？  
    答：配置类默认 `maxLen=100000`，`application.yml` 也配置 `blog.reliable-task.redis-stream.max-len: 100000`。具体生产值可按内存和积压能力调整。

21. 问：消费者数量是多少？  
    答：配置文件中 ES stream consumer-count 为 4，通知 fanout 和通知 delivery 默认配置为 2；代码会按 consumer-count 循环生成 consumerName。

22. 问：任务如何避免多个消费者重复处理？  
    答：先从 outbox 里通过 `claim` 把 `PENDING` 改成 `PROCESSING` 并设置 `locked_until`，只有更新成功的消费者才处理；Stream 消费后 ack。

23. 问：任务处理失败怎么办？  
    答：调用 `markForRetry`，根据 `ReliableTaskPolicy` 计算指数退避延迟；超过 `maxRetry` 后状态变为 `FAILED` 并记录 `dead_letter_at`。

24. 问：如果消费者宕机，PROCESSING 任务怎么办？  
    答：Mapper 有 `recoverExpiredClaims`，当 `locked_until < now` 时把任务恢复为 PENDING，后续重新消费。

25. 问：通知为什么会出现 outbox 假成功？怎么修复？  
    答：旧唯一键 `(receiver_id, article_id, type)` 过粗，`INSERT IGNORE` 忽略了后续通知，但服务仍标成功。修复后以 `dedupe_key` 为唯一幂等，并检查 `insertIgnore` 返回行数。

26. 问：大 V 发布文章怎么通知百万粉丝？  
    答：本项目已实现大作者阈值判断和 `yan_author_publish_event` 事件落库，避免发布事务直接写扩散。若要真正百万粉丝全部可见，可继续做粉丝收件箱懒加载、分片 fanout 或读扩散方案。

27. 问：项目有没有 Kafka？  
    答：本项目代码没有 Kafka 依赖和实现。这里使用 Redis Stream 手写轻量消息队列。Kafka 可以作为另一个项目或后续演进方案讲，但不应写成本项目已使用。

28. 问：Redis Stream 和 Kafka 怎么选？  
    答：当前项目消息量和部署复杂度适合 Redis Stream，复用已有 Redis；Kafka 更适合跨服务、高吞吐、长期日志保留、多订阅的大规模消息系统。

29. 问：文件上传怎么做？  
    答：后台上传接口接收 `MultipartFile`，`UploadServiceImpl` 校验后上传到阿里云 OSS，并把 URL 返回给前端。当前未发现 MinIO 或本地文件表。

30. 问：异常处理怎么做？  
    答：业务异常抛 `SystemException`，`GlobalExceptionHandler` 捕获后返回统一 `ResponseResult`；普通异常返回系统错误码和消息。

31. 问：接口限流怎么实现？  
    答：`@RateLimit` 注解标记接口，AOP 切面生成 Redis key，`RedisFixedWindowRateLimiter` 用 Lua 执行 `INCR + EXPIRE`，超过限制返回 429。

32. 问：限流 Redis 挂了怎么办？  
    答：`RateLimitAspect` 捕获 Redis 失败后 fail-open，记录 warn 日志并放行请求，避免 Redis 故障导致核心业务不可用。

33. 问：项目如何做压测？  
    答：用 `scripts/load/high_concurrency_db.py` 造万级文章、评论、粉丝和 outbox 数据，用 k6 脚本压文章详情、浏览量、评论、搜索、热榜和混合流量，结果保存在 `scripts/load/results/`。

34. 问：压测中发现了什么问题？  
    答：发现过 ES 同步吞吐不足、通知 outbox 假成功、后台任务依赖登录态、1000 VU 瞬时连接拒绝等问题。文档 `docs/高强度压测报告-20260728.md` 有记录。

35. 问：压测后做了什么优化？  
    答：批量化 ES 消费、修复通知幂等唯一键和 `insertIgnore` 校验、后台任务无登录上下文使用系统用户兜底、调整 Tomcat 连接和线程参数、k6 增加业务失败计数。

36. 问：有没有 Docker 或 Nginx 部署？  
    答：当前仓库没有 Dockerfile、docker-compose、Nginx 配置文件；不能写成已落地。可以说本地依赖 MySQL、Redis、ES，部署配置后续可补。

37. 问：项目还有哪些不足？  
    答：大作者通知事件后续拉取侧还可增强；搜索命中高并发业务失败码需要继续细分；前端两个 Vue2 项目版本较老；缺少完整 Docker/Nginx 部署资产；部分后台动态路由逻辑需要结合实际菜单数据继续完善。

38. 问：如果让你继续优化，你会做什么？  
    答：优先做三件事：完善大作者通知的懒加载/分片 fanout；给 outbox 做更完整的管理后台和告警；补充 Docker/Nginx/CI 部署链路，并继续压测搜索命中和评论热点场景。

## 十六、真实性检查

### 代码中能够明确证明的内容

- Maven 多模块单体架构，单启动类在 `yan-admin`。依据：`pom.xml`、`BlogAdminApplication`。
- Spring Security + JWT + Redis 登录态。依据：`SecurityConfig`、`JwtAuthenticationTokenFilter`、`LoginServiceImpl`、`BlogLoginServiceImpl`。
- 前台和后台两个 Vue2 项目。依据：`frond/sg-blog-vue/package.json`、`frond/sg-vue-admin/package.json`。
- MySQL、Redis、Elasticsearch、阿里云 OSS 被真实使用。依据：`application.yml`、`ElasticsearchConfig`、`UploadServiceImpl`。
- 文章、分类、标签、友链、评论、用户、角色、菜单、关注、通知、搜索、上传、异步任务运维模块存在。依据：对应 Controller、Service、Mapper。
- Redis Stream 可靠任务存在。依据：`ReliableTaskRedisStreamProperties`、`ArticleSearchOutboxConsumer`、`NotificationFanoutOutboxConsumer`、`NotificationDeliveryOutboxConsumer`。
- Outbox 状态、重试、死信、租约、幂等 key 存在。依据：`ReliableTaskPolicy`、`docs/sql/*.sql`、各 Outbox Mapper XML。
- 评论查询和索引优化有文档与代码依据。依据：`CommentServiceImpl`、`docs/sql/V20260718__comment_and_article_query_indexes.sql`、`docs/性能优化验证报告.md`。
- 高并发压测记录存在。依据：`scripts/load/results/**`、`docs/高强度压测报告-20260728.md`。

### 根据代码推断的内容

- 项目定位为“博客内容平台”，由前台博客、后台管理、搜索、通知和关注等代码组合推断。
- “适合 Java 后端实习/校招项目”是根据技术栈、工程结构、优化点和测试资产推断。
- “大作者通知后续可做拉取侧或懒加载”是基于 `yan_author_publish_event` 已落库但未发现完整粉丝拉取展示逻辑的推断。

### 无法确认、需要我补充的内容

- 每个模块是否均由你本人独立完成；当前 Git 记录不足，需要本人确认。
- 项目真实线上用户量、文章量、并发量和运行时长；仓库只能证明本地压测数据。
- 阿里云 OSS 的真实 bucket、访问策略和生产环境配置；仓库只证明使用了 OSS SDK 和上传服务。
- Docker、Nginx、CI/CD、线上监控告警是否在仓库外存在；当前仓库未发现相关配置。
- 后台动态菜单是否完全由数据库驱动；代码中有后端菜单接口，也有前端静态/动态路由逻辑，需要结合运行数据确认。

### 不建议写入简历的内容

- 不建议写“使用 Kafka 实现消息队列”：本项目没有 Kafka 代码和依赖。
- 不建议写“使用 MinIO 文件存储”：本项目没有 MinIO 代码和依赖，实际是阿里云 OSS。
- 不建议写“使用 WebSocket 实时通知”：本项目没有 WebSocket 代码，通知是列表查询和未读数接口。
- 不建议写“微服务架构”：本项目是多模块单体。
- 不建议写“Docker Compose 一键部署”或“Nginx 反向代理已配置”：当前仓库未发现相关文件。
- 不建议编造线上 QPS、用户量、响应时间下降百分比；只能引用 `docs/高强度压测报告-20260728.md` 和 `scripts/load/results/` 中已有的本地压测数据。

## 附录：对标高含金量开源项目后的补强方向

本节不是当前项目已完成内容，不能直接写进“已完成亮点”。它用于规划下一轮能真正提高简历含金量的优化路线。

### 1. 对标项目观察

| 开源项目 | 项目定位 | 值得学习的点 | 对 YanBlog 的启发 |
|---|---|---|---|
| [Halo](https://github.com/halo-dev/halo) | 开源建站/CMS 工具，覆盖个人博客、知识库、企业官网等场景 | 主题、插件、应用生态，把“博客”做成“内容管理平台” | YanBlog 不应只停留在 CRUD 博客，可向内容发布、审核、版本、定时发布、插件式扩展靠拢 |
| [mall](https://github.com/macrozheng/mall) | 电商系统，包含前台商城和后台管理 | 业务闭环完整：推荐、搜索、商品、购物车、订单、会员、后台运营 | 简历项目要有完整业务闭环，不是只堆 Redis/ES；YanBlog 应补“内容生产 → 分发 → 互动 → 数据反馈”闭环 |
| [mall-swarm](https://github.com/macrozheng/mall-swarm) | 微服务商城系统 | 网关、认证中心、监控中心、配置中心、Docker/K8s 部署 | YanBlog 不一定要拆微服务，但可以补部署、监控、异步任务运维和健康检查闭环 |
| [RuoYi-Vue-Pro](https://github.com/YunaiV/ruoyi-vue-pro) | 企业后台管理系统 | RBAC、数据权限、SaaS 多租户、工作流、三方登录、支付、短信等企业能力 | YanBlog 后台目前有 RBAC，但还缺数据权限、操作审计、内容审核流 |
| [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) | 面向分布式集群和多租户场景的后台管理系统 | Sa-Token、MyBatis-Plus、OSS、SpringDoc、工作流、多租户 | YanBlog 可以补 SpringDoc 接口文档治理、数据权限、操作日志、部署文档，而不是盲目追微服务 |
| [JeecgBoot](https://github.com/jeecgboot/JeecgBoot) | AI 低代码平台 | 代码生成、在线表单、减少重复 CRUD | YanBlog 不适合硬做低代码，但可以做后台管理工程规范：统一 CRUD 模板、统一校验、统一日志 |
| [Apache Answer](https://github.com/apache/answer) | Q&A/知识社区平台 | 插件系统、社区知识沉淀、Docker 快速启动 | YanBlog 可把评论/关注/通知升级为“社区互动体系”，例如点赞、收藏、关注流、用户贡献数据 |
| [ThingsBoard](https://github.com/thingsboard/thingsboard) | IoT 平台 | 大规模事件采集、规则引擎、消息处理和可视化 | YanBlog 可以借鉴“事件驱动”和“规则引擎”思想，把文章发布、评论、关注、通知、缓存失效统一为领域事件 |

### 2. 当前 YanBlog 和高含金量项目的差距

当前 YanBlog 已经有不少技术优化，但距离高含金量项目还差三类东西：

1. 业务纵深不够尖：现在是“博客 + 搜索 + 通知”，但还没形成非常强的业务主线。
2. 后台运营能力偏弱：有管理界面，但缺内容审核、数据权限、操作审计、发布流程。
3. 工程交付能力还不完整：有压测、有 Actuator，但缺 Docker/Nginx/部署文档/环境隔离/更完整的可观测面板。

不要盲目补：

- 不建议为了显得厉害强行拆微服务。
- 不建议为了中间件而上 Kafka/RabbitMQ；当前 Redis Stream 方案已经可以讲清楚。
- 不建议强行做商城、支付、多租户，这会让项目定位发散。

YanBlog 最适合的升级定位应该是：

> 面向内容创作者的高性能内容分发与互动平台。

这个定位比“博客系统”更好，因为它能自然承接文章发布、搜索、关注流、通知、评论互动、内容审核、数据统计和可靠异步任务。

### 3. 最值得继续做的 5 条路线

#### 路线 1：关注流 Feed 系统

优先级：最高。

为什么有含金量：

- 这条线和当前已有的关注、通知、文章发布 outbox、Redis Stream 高度衔接。
- 能讲清楚“百万粉丝作者发布文章”的推拉模型，而不是只讲通知表插入。
- 类似黑马点评里的关注流，但你可以结合自己的 Redis Stream 和 Outbox，讲得更完整。

建议实现：

1. 新增用户关注流接口：`GET /feed/following`。
2. 普通作者发布文章时，fanout 到粉丝 Redis ZSet：`feed:user:{userId}`。
3. 大作者发布文章时，不直接写入所有粉丝 feed，而是写 `yan_author_publish_event`，用户拉取 feed 时合并大作者事件。
4. Feed 使用滚动分页：`maxTime + offset`，避免深分页。
5. 增加 feed 修复任务：当 fanout 失败时可从 outbox 或 publish_event 回补。

可新增表：

- `yan_user_feed_outbox`
- 或复用 `yan_author_publish_event`，再增加处理状态字段。

可写成简历亮点：

> 设计关注流 Feed 系统，基于 Redis ZSet 实现普通作者写扩散，针对大 V 作者采用写事件 + 读时合并策略，解决粉丝量差异下的文章分发性能问题。

注意：只有实现后才能写为已完成。

#### 路线 2：内容审核与版本化发布工作流

优先级：高。

为什么有含金量：

- 对标 Halo 的 CMS 能力和 RuoYi 系的工作流能力。
- 能让 YanBlog 从“个人博客”升级成“内容平台”。
- 面试官会觉得这是业务设计，而不只是技术堆叠。

建议实现：

1. 文章状态从简单 `0/1` 扩展为：草稿、待审核、审核拒绝、已发布、已下架、定时发布。
2. 新增文章版本表，支持编辑历史和回滚。
3. 新增审核记录表，记录审核人、审核意见、审核时间。
4. 定时发布任务扫描到期文章，发布后触发 ES 同步和粉丝通知。
5. 后台增加“待审核文章”“审核记录”“版本对比/回滚”页面。

可新增表：

- `yan_article_version`
- `yan_article_audit_record`
- `yan_article_publish_schedule`

可写成简历亮点：

> 设计文章版本化发布流程，支持草稿、审核、定时发布和版本回滚，并在发布成功后通过可靠事件触发搜索索引同步和粉丝分发。

#### 路线 3：搜索平台 2.0

优先级：高。

为什么有含金量：

- 当前 ES 已经接入，但还像“搜索功能”；继续做可以升级成“搜索平台能力”。
- 面试很好讲：索引别名、零停机重建、热词、自动补全、慢查询、缓存准入。

建议实现：

1. 使用 ES index alias：`yan_blog_articles_read`、`yan_blog_articles_write`。
2. 重建索引时写新索引，完成后切 alias，实现零停机重建。
3. 记录搜索日志：关键词、命中数、耗时、用户、时间。
4. 做热门搜索词接口。
5. 做搜索建议/自动补全。
6. 后台展示索引版本、文档数、最后同步时间、失败任务数。

可新增表：

- `yan_search_log`
- `yan_search_hot_keyword`
- `yan_search_index_version`

可写成简历亮点：

> 基于 Elasticsearch alias 设计零停机索引重建方案，并沉淀搜索日志、热词统计和自动补全能力，提升搜索链路的可用性和运营价值。

#### 路线 4：后台数据权限与操作审计

优先级：中高。

为什么有含金量：

- 对标 RuoYi-Vue-Pro / RuoYi-Vue-Plus 的企业后台能力。
- 这比继续加普通 CRUD 更有后端味道。

建议实现：

1. 管理员只能管理自己创建的文章，超级管理员可管理全部。
2. 分类、文章、用户等后台操作记录操作日志。
3. 登录日志记录 IP、UA、成功失败、失败原因。
4. 敏感字段脱敏，例如手机号、邮箱。
5. 后台提供操作日志查询。

可新增表：

- `sys_oper_log`
- `sys_login_log`

可写成简历亮点：

> 为后台管理系统补充数据权限和操作审计，通过用户角色、资源归属和操作日志实现内容管理的可追溯与权限隔离。

#### 路线 5：工程交付与可观测闭环

优先级：中。

为什么有含金量：

- mall、mall-swarm、Apache Answer 都有较明确的部署/运行入口。
- 你的项目现在有压测和 Actuator，但“能不能别人一键跑起来”还不够强。

建议实现：

1. 增加 Dockerfile。
2. 增加 `docker-compose.yml`，只用于可选本地部署，不影响你已有虚拟机环境。
3. 增加 Nginx 配置：前台、后台、后端 API 统一反代。
4. 增加 Prometheus + Grafana 面板配置。
5. 增加生产/开发 profile。
6. 写一份“从零启动项目”的中文部署文档。

可写成简历亮点：

> 完善项目交付链路，提供 Docker Compose、Nginx 反向代理、Actuator/Prometheus 指标和压测脚本，实现从本地启动到性能验证的闭环。

### 4. 我建议的下一阶段顺序

如果目标是“简历含金量最大化”，建议不要平均用力，按这个顺序做：

1. 先做关注流 Feed 系统。
2. 再做内容审核与版本化发布。
3. 再做搜索平台 2.0。
4. 然后做后台数据权限与操作审计。
5. 最后做 Docker/Nginx/Prometheus 交付闭环。

理由：

- Feed 系统最贴近高并发，也能接上你已经做过的关注、通知、Redis Stream、Outbox。
- 内容审核与版本化发布能把项目定位从博客提升到 CMS/内容平台。
- 搜索 2.0 能把 ES 从“用了一个中间件”变成“设计了一套搜索子系统”。
- 数据权限和审计能补企业后台味道。
- 部署可观测是工程加分项，但不应该压过核心业务。

### 5. 下一版简历最好长这样

如果上面前三条做完，项目描述可以从：

> 基于 Spring Boot 的博客系统，支持文章、评论、搜索、通知。

升级为：

> YanBlog 是一个面向内容创作者的高性能内容分发与互动平台，支持文章创作、审核发布、全文搜索、关注流分发、评论互动和可靠通知。项目基于 Spring Boot 3 多模块单体架构，使用 MySQL、Redis、Redis Stream、Elasticsearch 和 OSS，实现了缓存治理、搜索索引可靠同步、Feed 推拉结合分发、通知幂等投递和高并发压测验证。

这版会比“博客系统”明显更像一个能打的 Java 后端项目。
