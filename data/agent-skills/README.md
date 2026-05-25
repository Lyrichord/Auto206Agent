# Agent Skills 目录

本目录下的 **根级 `*.md`**（不含本 README）会在应用启动时由 `ProjectSkillLoader` 加载，按**文件名排序**合并进主对话**系统提示**。

## 与知识库（RAG）的区别

| Skills（本目录） | 知识库（`aiops-docs`、上传文件） |
|------------------|----------------------------------|
| 注入系统提示，指导「用哪个工具、怎么答」 | 写入 Milvus，经 `queryInternalDocs` 检索 |
| 改文件后需 **重启应用** | 改文档后需 **重新向量化** |

## 文件约定

- **`00-skill-router.md`**：Step 1 路由表，列出所有 Skill ID 与触发条件（应先读）
- **`01-lab-conduct.md`**：全局行为规范
- **`02-` ~ `10-skill-*.md`**：各业务 Skill 的触发语与必选工具

新增 Skill：复制模板新建 `NN-skill-xxx.md`，在 `00-skill-router.md` 表格中登记一行。

## 配置

`application.yml` → `app.skills.enabled`、`app.skills.directory`、`app.skills.max-chars`（默认 8000，超长截断）。
