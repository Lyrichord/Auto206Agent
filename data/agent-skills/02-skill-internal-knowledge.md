# Skill：组内知识检索（`internal-knowledge`）

## 何时触发

- 问导师、学生、邮箱、研究方向、论文、在研项目（如江淮汽车、基金项目）
- 问运维文档：磁盘满、内存高、CPU 高、GPU、服务不可用、响应慢等 **处置步骤**
- 问「某某是谁」「组里有哪些人」
- 用户消息**未**含「【背景摘录】」且问题可能已在 `aiops-docs` 或本会话上传中

## 必选工具

1. **`queryInternalDocs`** — 检索 Milvus 知识库（全局 `aiops-docs` + 本会话上传）
2. 检索 query 须包含**全名或完整主题**；可附加 `members`、`group`、`projects` 等词提高命中

## 禁止

- 未调用工具即断言成员身份、论文题目、项目对接人
- 把对话历史里的运维标题冒充用户原话

## 知识来源说明（答复时可简述）

- 全局：`aiops-docs/`（含 `group/members.md`、`publications.md`、`projects.md` 等）
- 会话：用户经上传接口绑定 `sessionId` 的文件
