# 知屿 Knode

## 项目定位

知屿 Knode 是 YanBlog 的知识库模块升级版。

它保留原有博客的文章发布、用户和搜索能力，并增加两种知识库：

1. **系统知识库**：由全站公开文章组成，平台自动维护，所有用户都可以检索和问答。
2. **用户知识库**：由用户自己创建，可以收录站内文章，也可以保存自己的文档，默认只对创建者可见。

项目的核心不再是“发表文章”，而是让文章和个人资料能够被组织、检索、引用和持续更新。

## 核心体验

用户可以完成以下闭环：

```text
选择或创建知识库
        ↓
加入站内文章或个人文档
        ↓
文档切片并建立索引
        ↓
输入问题
        ↓
检索相关知识片段
        ↓
返回回答和来源引用
        ↓
用户反馈回答质量
```

管理员可以观察文章发布、文档更新和索引任务之间的状态：

```text
文章发布 / 文档修改
        ↓
创建索引可靠任务
        ↓
MySQL Outbox
        ↓
Redis Stream
        ↓
Elasticsearch
        ↓
知识库可检索
```

## 两种知识库

### 系统知识库

系统知识库使用固定的 `SYSTEM` 类型，全站只保留一个。

- 数据来源：所有公开且已发布的文章
- 维护方式：文章发布、编辑、删除时自动同步
- 权限：所有登录用户可查询，只有系统可以修改内容
- 数据范围：不包含草稿、下架文章和私密文档
- 查询方式：全站知识检索和问答

系统知识库的价值是把博客中的分散文章沉淀成平台级知识资产。

### 用户知识库

用户知识库使用 `PRIVATE` 类型，第一版默认只允许创建者访问。

用户可以：

- 创建多个主题知识库
- 从站内公开文章中选择内容加入知识库
- 创建或编辑个人 Markdown、纯文本文档
- 移除知识库中的文档，但不影响原文章
- 在当前知识库范围内检索和问答

站内文章不直接复制到用户知识库，而是通过关联关系引用。这样可以避免重复存储，也能保证原文章更新后，用户知识库可以感知版本变化。

## 领域模型

```text
KnowledgeBase
├── SYSTEM       全站系统知识库
└── PRIVATE      用户私有知识库

KnowledgeDocument
├── ARTICLE      站内文章
└── USER_DOCUMENT 用户个人文档

KnowledgeDocumentVersion
└── 文档内容的不可变版本

KnowledgeChunk
└── 文档版本切分后的检索片段

KnowledgeBaseDocument
└── 知识库与文档之间的关联关系

KnowledgeQuery
└── 用户检索和问答记录

KnowledgeFeedback
└── 用户对回答和来源的反馈
```

建议的核心表：

| 表 | 作用 |
|---|---|
| `yan_knowledge_base` | 知识库基本信息、类型和所有者 |
| `yan_knowledge_document` | 统一管理文章和个人文档 |
| `yan_knowledge_document_version` | 保存文档版本、内容摘要和处理状态 |
| `yan_knowledge_chunk` | 保存文档切片及其顺序、元数据 |
| `yan_knowledge_base_document` | 建立知识库与文档的关联 |
| `yan_knowledge_query` | 记录查询、检索范围和响应耗时 |
| `yan_knowledge_feedback` | 记录有帮助、无帮助和人工纠错 |

第一版可以先使用 Elasticsearch 保存检索文档和切片，MySQL 保存事实数据、权限、版本和任务状态。

## 模块设计

建议新增 `yan-knowledge` Module，仍然保持多模块单体，不拆成微服务。

知识库 Module 对外提供少量稳定的 Interface：

```text
KnowledgeBaseFacade
KnowledgeDocumentSource
KnowledgeIndexer
KnowledgeRetriever
AnswerGenerator
KnowledgeTaskGateway
```

职责划分：

- `KnowledgeBaseFacade`：创建知识库、管理文档关联、校验访问权限
- `KnowledgeDocumentSource`：读取文章或个人文档的当前版本
- `KnowledgeIndexer`：切片、构建索引文档、处理版本变化
- `KnowledgeRetriever`：根据知识库范围检索相关切片
- `AnswerGenerator`：根据问题和检索证据生成回答
- `KnowledgeTaskGateway`：将索引和批量处理提交为可靠任务

这些 Interface 形成后续替换检索引擎、模型供应商和任务队列的 Seam。

设计目标是让知识库 Module 有足够的 Depth：调用者只需要表达“加入文档”“检索知识”“生成回答”，版本判断、权限过滤、切片、索引同步、失败重试和引用组装都集中在 Module 内部，提升 Leverage 和 Locality。

现有 Elasticsearch、MySQL Outbox 和 Redis Stream 分别作为具体 Adapter 接入，不让业务代码直接依赖 Redis Stream 的细节。

## 功能实施阶段

### 阶段一：系统知识库

目标：让全站公开文章自动成为可检索知识。

- 创建系统知识库
- 文章发布时创建索引任务
- 文章编辑时生成新版本并重新索引
- 文章删除或下架时移除索引
- 支持标题、正文、标签和分类检索
- 返回文章来源、更新时间和匹配片段
- 展示文档索引状态

验收标准：文章发布成功后不依赖 Elasticsearch 立即成功；即使索引暂时失败，文章主业务仍然成功，恢复后可以自动补偿。

### 阶段二：用户知识库

目标：让用户能够建立自己的知识空间。

- 创建、编辑和删除知识库
- 添加和移除站内公开文章
- 创建和编辑个人 Markdown、纯文本文档
- 查看文档处理状态
- 私有权限校验
- 按当前知识库范围检索

第一版不做知识库协作、复杂角色和组织权限，避免权限模型过早膨胀。

### 阶段三：知识库问答

目标：从“搜索知识”升级到“基于来源回答问题”。

- 输入自然语言问题
- 检索相关文档切片
- 展示检索证据
- 生成回答
- 回答中保留来源引用
- 保存问答历史
- 支持用户标记回答是否有帮助

检索能力按三步演进：

1. Elasticsearch 全文检索和元数据过滤。
2. 增加向量检索。
3. 使用关键词和向量的混合检索。

没有模型服务配置时，系统仍然可以展示检索结果和来源，不让问答模型成为项目启动的硬依赖。

### 阶段四：知识质量和任务观测

目标：让知识库具备可维护性。

- 展示索引任务积压
- 展示失败任务和重试次数
- 支持手动重试和回放
- 展示文档版本与索引版本差异
- 统计高频查询和无结果问题
- 统计经常被用户标记为无帮助的回答
- 展示文档命中次数和知识库使用情况

## 前端页面

### 用户端

- `/knowledge`：知识库首页和最近查询
- `/knowledge/system`：系统知识库问答
- `/knowledge/mine`：我的知识库列表
- `/knowledge/:id`：指定知识库问答和文档列表
- `/knowledge/:id/documents`：文档管理
- `/knowledge/history`：问答历史和反馈

### 管理端

- 知识库总览
- 文档处理状态
- 索引任务列表
- 失败任务和死信
- 查询趋势和无结果问题
- 文档版本与索引一致性

后台的核心页面应当是知识库控制台和任务时间线，而不是传统的文章 CRUD 页面。

## 接口规划

用户端接口可以按以下范围设计：

```text
GET    /api/blog/knowledge/system
GET    /api/blog/knowledge/mine
POST   /api/blog/knowledge
PUT    /api/blog/knowledge/{id}
DELETE /api/blog/knowledge/{id}

POST   /api/blog/knowledge/{id}/documents
DELETE /api/blog/knowledge/{id}/documents/{documentId}
POST   /api/blog/knowledge/{id}/query
GET    /api/blog/knowledge/{id}/history
POST   /api/blog/knowledge/query/{queryId}/feedback
```

管理端接口复用现有异步任务运维能力，增加知识库索引任务的统计、重试和回放。

## 版本一的边界

版本一必须完成：

- 系统知识库
- 用户私有知识库
- 站内文章关联
- 个人 Markdown 或纯文本文档
- Elasticsearch 检索
- 来源引用
- 索引任务 Outbox
- 失败重试和后台观测

版本一暂不实现：

- 多人协作知识库
- PDF、Word 和网页复杂解析
- 多模型评测平台
- 独立向量数据库
- 微服务拆分
- 复杂组织权限

## 最终演示流程

1. 管理员发布一篇文章。
2. 系统知识库自动生成索引任务。
3. 用户通过问题检索文章，并看到来源引用。
4. 用户创建“Java 并发”知识库。
5. 用户加入几篇站内文章，并上传自己的学习笔记。
6. 用户在私有知识库中提问，结果只来自当前知识库。
7. 模拟 Elasticsearch 故障。
8. 查看索引任务失败、重试和积压。
9. 恢复 Elasticsearch 后任务自动完成。
10. 用户反馈回答质量，后台展示高频问题和无帮助回答。

## 验收指标

- 系统知识库只包含公开且已发布的文章。
- 用户无法读取其他用户的私有文档。
- 文章发布不依赖索引服务同步成功。
- 同一文档版本不会重复建立有效索引任务。
- 索引失败后能够自动重试和人工回放。
- 查询结果能够显示明确来源。
- 用户知识库移除文章不会删除原文章。
- 文档内容更新后，知识库最终能够追平最新版本。
- 关键链路具备接口测试、集成测试或压测证据。
