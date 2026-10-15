# API 变更记录

## 2026-10-10 · React 前端初始化

- 新增 `GET /auth/bootstrap`，返回 `csrfToken`、`csrfHeader`、`accountAuthRequired`。
- 使用现有 Spring Security 会话绑定 CSRF 实现；响应 `Cache-Control: no-store`，不返回访问码、账号列表或认证会话 ID。
- 只读、同源、不需要登录；不添加 CORS 放行，不改变任何原有判题和认证接口。
- 无数据库迁移，无破坏性响应变更。新前端发布需更新并重启后端。
- 契约：`frontend-bootstrap.openapi.yaml`。
- 验证：`FrontendBootstrapHttpTest` 覆盖匿名初始化、无 CSRF 请求拒绝、登录与令牌轮换；原判题策略与安全默认值测试一起回归。

## 2026-10-10 · 并发提前停止与测试数据下载修复

- 修复 trusted-local 遇到首个非 AC 后漏收并发结果：停止新增测试，收齐已提交的结果后再发布终态，保留原并发上限。
- `GET /download/{judgeId}/all` 仅归档已保存的判定记录，按编号去重并排序；不再扫描目录并把未记录的并发产物当作已判数据。
- 所有 ZIP 统一保留 `.in`（输入）、`.out`（待测输出）、`.ans`（参考答案）。未生成的可选文件不伪造、不替代；SPJ 可能没有答案文件。
- **兼容提醒**：旧版全部下载把参考答案改名为 `.out`；依赖此错误行为的脚本须改为读取 `.ans`。不再回退使用待测输出冒充答案。
- 新增 `GET /download/{judgeId}/{caseNumber}/archive`，下载单点完整 ZIP（大批量任务亦可使用）。原 `GET /download/{judgeId}/{caseNumber}` 继续只返回 `.in`，不改变调用约定。
- 前端详情新增完整测试点下载，保留仅输入下载；对比表明确标识 `.ans/.out`。全部下载改名为“全部已判数据”，设置与指南说明并发停止的真实行为，未增加宣传文案或改变页面设计。
- 复用现有鉴权、审计、路径与符号链接安全检查以及流式 ZIP 实现；未添加依赖，未改变数据库结构。契约见 `judge-downloads.openapi.yaml`。
- 历史任务不改写：本次反馈任务只有 #2 的可靠判定记录；1、3、4 的文件存在不等于其判定和耗时可以恢复。修复后全部下载与该记录一致，新任务会收齐已启动的结果。
- 验证：直接相关后端 61 项，60 通过、1 项因 Windows 文件符号链接权限跳过；前端 64 项单测、14 项 Chromium E2E 通过，生产构建通过。
- 扩展回归共 169 项，0 断言失败、1 错误、5 跳过：已有 `FileTaskStoreTest.aFailedOrUnsafeChargeWriteCannotDeleteTheTask` 创建符号链接时遭遇 Windows 权限限制，未修改无关测试以掩盖该环境问题。
- 部署需同时更新前端构建和后端并重启服务；未擅自停止或重启用户正在使用的服务。
- 实机只读验证：调用修复后的 `JudgeFileService` 读取原任务，导出 `target/download-fixes/testcases-461bfe69-e334-4eaf-8ea1-9e748632db07.zip`，仅含 `2.in/2.out/2.ans`，逐项 SHA-256 与原文件一致；原任务全部 22 个文件导出前后哈希不变。
- 发布产物：前端构建已同步至 Spring Boot 资源，`mvn -DskipTests package` 成功；下载 OpenAPI YAML 已解析并核对四个控制器路由。
