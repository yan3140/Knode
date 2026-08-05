# 数据访问规范

## 统一原则

- **MyBatis-Plus**：单表 CRUD、`LambdaQueryWrapper` 条件查询、分页、单实体状态更新、逻辑删除。
- **MyBatis XML**：联表查询、聚合统计、`UNION`、游标分页、批量写入、`INSERT IGNORE`、任务领取/租约、数据库函数和任何超过简单单表条件组合的 SQL。

## Mapper 约定

1. Mapper 接口继承 `BaseMapper<T>`，为简单操作提供 MyBatis-Plus seam。
2. 复杂方法只在 Mapper 接口声明，在 `src/main/resources/mapper/*Mapper.xml` 实现；不使用 `@Select`、`@Update`、`@Insert`、`@Delete` 承载复杂 SQL。
3. Service 先判断是否可用 MyBatis-Plus 表达；不能清晰表达时才调用 XML Mapper 方法。
4. 批量和异步任务 SQL 必须在 XML 中保留明确的幂等、排序和领取条件。

## 现有重构顺序

1. 通知 Outbox 与通知收件箱（已迁移）。
2. 文章列表与文章搜索 Outbox（已迁移）。
3. 关注/粉丝关系查询（已迁移）。
4. 其余系统模块；每个模块完成后运行对应模块测试与启动验证。
