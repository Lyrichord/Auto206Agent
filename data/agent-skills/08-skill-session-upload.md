# Skill：本会话上传材料（`session-upload`）

## 何时触发

- 用户通过界面上传 txt/md/doc/docx/pdf 后，要求分析、总结、提取简历字段等
- 系统提示或历史中出现「上传到知识库成功」「绑定当前会话」类说明

## 必选工具

- **`queryInternalDocs`**（Milvus 按 `_session_id` 过滤，**仅当前会话**可检索该上传）

## 会话隔离说明（须向用户说明）

- **新建对话**后，上一会话上传的文件**不会**自动可见，需重新上传
- 全局组内文档（`aiops-docs`）任意会话均可检索

## 禁止

- 断言其他会话或未上传文件中的内容
- 未检索即编造简历/合同中的姓名、数字

## 支持格式

- `txt, md, markdown, doc, docx, pdf`（由 `DocumentTextExtractionService` 解析后分片入库）
