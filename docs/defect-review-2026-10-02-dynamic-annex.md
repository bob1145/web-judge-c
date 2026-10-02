# 项目缺陷复审动态验证附录（2026-10-02）

## 1. 附录说明

- 关联文档：`docs/defect-review-2026-10-02.md`（下称"主文档"）。审查基线同为 Git 提交 `f31d039`，本轮未修改任何业务代码。
- 本附录是第二轮复核：把系统在真实运行环境中跑起来，对主文档结论做动态验证，并补充首轮遗漏的问题。主文档中的"源码确认/有限动态验证"等级，部分已在本轮升级为"动态实锤"。
- 验证环境：WSL Ubuntu 24.04（OpenJDK 21.0.12、Maven 3.8.7、g++ 13、Python 3.12、Node 22），默认 `trusted-local` 配置（`0.0.0.0:1234`，访问码模式，`judge.sandbox.production.provider=direct`）。本地 g++ 编译 + 对拍主链路端到端可用（40 用例任务正常 AC）。
- 验证手段：HTTP/原生 WebSocket + STOMP 协议级攻击、`task_runner.py` 隔离攻击实验、真实浏览器（Chromium）加载页面、Maven 全量回归。
- 攻击脚本与输出保存在测试机临时目录（`%TEMP%\wjc-tests`），未纳入仓库；本文数字均为当次实测记录。测试后服务已停止；WSL 侧测试副本 `~/web-judge-run` 与 `/tmp/online-judge` 任务残留为可清理产物。

## 2. 主文档 23 项结论验证汇总

| ID | 本轮验证方式 | 结论 |
| --- | --- | --- |
| DEF-01 | 页面 DOM + 源码 | 成立（登录页实测仅访问码输入框；401 被显示为网络故障为源码确认） |
| DEF-02 | task_runner 实测 | 成立且实锤：路径不一致时 exit=30 SECURITY_VIOLATION，且 runner 先在容器内创建了宿主样式目录 |
| DEF-03 | 源码推演（确定性） | 成立但被低估，应升级（见 §3.2：容器判题路径近乎必死） |
| DEF-04 | 协议级实测 | 成立但被低估，应升级（见 §3.1：WS 通道整体无认证，匿名可连可订可注入） |
| DEF-05 | task_runner 实测 | 成立且实锤：用户程序伪造全 AC 的 SUMMARY/COMPLETED 事件、覆盖 summary.json、自造取消信号全部成功 |
| DEF-06 | 源码复核 | 成立（`readAllLines` 全量读；与 N2 叠加） |
| DEF-07 | task_runner 实测 | 成立且实锤：限额 1MB、程序输出 300MB，监督进程峰值 RSS 414MB 后才检查限额 |
| DEF-08 | task_runner 实测 | 成立且实锤：2 秒限时用例实际耗时 26.1 秒（孤儿子进程持管道，第二次 communicate 无超时） |
| DEF-09 | HTTP 实测 | 成立且实锤：固定来源第 6 次锁定；轮换 X-Forwarded-For 连续 12 次错误不锁、随后成功登录 |
| DEF-10 | 源码复核 | 成立；补充：访问码模式 `AccessCodeService` 的两张 IP-keyed Map 同样无界，叠加 XFF 伪造更易堆积 |
| DEF-11 | 双路径实测 | 成立且实锤：同一输出对（`5.0000000001` vs `5`，精度 1e-9）Java 路径 AC、Python runner WA |
| DEF-12 | HTTP 实测 | 成立且实锤：`NaN` vs `5` 判 AC；`9007199254740992` vs `9007199254740993` 判 AC |
| DEF-13 | HTTP 实测 | 成立且实锤：CREATED 任务取消返回 `accepted:false`、`"Task is not queued or running"` |
| DEF-14 | 源码复核 | 成立（重启恢复仅处理 RUNNING/QUEUED；PENDING/COMPILING 计入运行配额却无恢复路径） |
| DEF-15 | 源码复核 | 成立（额度检查与落盘非原子；调度仅全局并发，无用户级运行许可） |
| DEF-16 | 源码复核（含主文档既有 Node 验证） | 成立（start 失败状态码被忽略；429 后任务停留 CREATED 且无恢复消息） |
| DEF-17 | 浏览器实测 | 成立，疑问已定论（见 §5.1：页面 JS 不报废，toast 静默失效；P2 定级维持） |
| DEF-18 | 日志实测 | 成立且实锤：INFO 日志含完整且仍有效的 sessionId |
| DEF-19 | 响应头实测 | 成立且实锤：`Set-Cookie` 含 HttpOnly、无 Secure、无 SameSite |
| DEF-20 | HTTP 实测 | 成立且实锤：已登录 GET 分支 302 跳 `https://evil.example.com`；登录响应回显任意 redirect 供前端跳转 |
| DEF-21 | HTTP + 磁盘实测 | 成立且实锤：取消后 API 缓存 ac=12/样本 12，磁盘 summary.json 被占位汇总覆盖为 ac=0/样本清零（completedCases 保留） |
| DEF-22 | 源码复核 | 成立（节流 Map 全库无任何 remove） |
| DEF-23 | 源码复核 | 成立（仅发送 `X-Judge-Worker-Mtls: configured` 头，HttpClient 未配置任何 SSLContext；cert-only 可通过启动校验） |

## 3. 主文档需修正或升级的条目

### 3.1 DEF-04 应升级：WebSocket 通道整体无认证，不止缺任务级授权

- **实测**：(a) 不带任何凭证的匿名 WebSocket 握手被直接接受；(b) 匿名连接订阅他人任务主题 `/topic/progress/{judgeId}` 收到 6 条进度消息；(c) 客户端向 `/topic/progress/{judgeId}` SEND 伪造消息，被简单代理原样广播给订阅者。
- **根因**：`AuthenticationInterceptor` 经 MVC `WebMvcConfigurer` 注册，只作用于 MVC 处理器映射；`/ws` 的 SockJS/WebSocket 握手由 WebSocket 专属 HandlerMapping 处理，完全不经过该拦截器。`SecurityConfig` 又对所有请求 `permitAll`。
- **影响界定**：前端对状态文本做了 `escapeHtml`，注入不构成 XSS；影响是进度数据可被任意监听与伪造（在受害者页面上展示伪造的任务状态/进度）。
- **建议**：主文档 DEF-04 的"条件与影响"应改写为"WS 通道无认证"，验收增加"匿名握手必须拒绝、客户端向 `/topic/**` 的 SEND 必须被拒"。修复方式：注册 `HandshakeInterceptor` 或在 `configureClientInboundChannel` 做连接身份 + 订阅/发送授权。

### 3.2 DEF-03 应升级：容器判题路径按当前代码近乎必死

三个根因叠加，任一单独存在即足以失败：

1. **轮询热循环无间隔**：`JudgeService.java:422-435` 的 `while` 循环没有任何 sleep，宿主以微秒级节奏连续读事件文件。
2. **事件文件双写**：`FileTaskStore` 的元数据事件与容器 runner 的事件是**同一个** `events.jsonl`（同 workDir、同名）。任务启动前宿主已预写 ≥4 行（CREATED/QUEUED/RUNNING/PENDING）；第一次轮询把这些 `type=null` 的行当沙箱事件消费掉后，第二次轮询时容器尚未完成启动（秒级）→ 返回空列表 → `JudgeService.java:424` 立即 break → 发布 SYSTEM_ERROR "SandboxRunner finished without a terminal event"。
3. **runner 截断文件导致游标倒挂**：`task_runner.py:71` 启动即清空 `events.jsonl`；宿主按"行数"记游标，文件变短后 `cursor >= lines.size()` 恒成立（事件被跳过），或读到半写行触发 JSON 解析异常 → `LinuxContainerRunner.java:207-213` 直接返回终态 SYSTEM_ERROR。

- **结论**：`linux-container` / `windows-container` 路径不是"异步启动或编译中暂无事件"的偶发误判，而是**确定性失败的主路径故障**；DEF-02 的路径映射修复后仍会因本条失败。主文档阶段 A 的"目标环境完成编译判题"在修复本条前不可达。
- **建议**：轮询加间隔与截止时间、区分"暂无事件/进程退出/终态"；元数据事件与沙箱事件分文件；游标按字节偏移并容忍截断。

### 3.3 证据等级与回归状态描述已过时

主文档"未重新运行 Maven 回归"不再成立：本轮在基线提交上执行 `mvn test`，结果为 **193 项：2 失败 + 7 错误 + 2 跳过，BUILD FAILURE**（详见 N5）。主文档 §1 的证据等级与测试记录一节应据此更新。

## 4. 新增缺陷（主文档遗漏）

优先级沿用主文档口径（P1=安全/正确性边界，P2=可靠性/条件性风险，P3 为产品缺口类）。

### N1（P1）WebSocket 握手完全绕过认证

- **条件与影响**：见 §3.1。此为 DEF-04 的上位缺陷：不是"已登录用户越权"，而是**任何人**可连、可订、可注入。judgeId 为 UUID 难以猜测，但会出现在共享/协作场景与日志中。
- **证据**：`WebSocketConfig.java:27-38`（无握手/入站拦截）、`SecurityConfig.java:19-25`（permitAll）、实测记录 §3.1。
- **建议**：与 DEF-04 合并修复，但验收单列：匿名握手拒绝、登出/过期会话拒绝、客户端 SEND 拒绝。

### N2（P1）宿主控制面与沙箱事件面共用 events.jsonl

- **条件与影响**：`FileTaskStore.appendEvent`（`FileTaskStore.java:409-413`、`:449-451`）与容器 runner 事件文件为同一路径。除导致 DEF-03 必死外，还意味着：宿主元数据被容器进程可写（与 DEF-05 同源）、runner 截断/半写直接破坏宿主游标。
- **证据**：两处代码共用 `workDir/events.jsonl`；`task_runner.py:71` 截断行为实测可见。
- **建议**：控制面事件与沙箱事件分文件存放（或事件文件移出挂载目录），游标机制改字节偏移。

### N3（P2）会话凭证可经 URL 查询参数传递

- **条件与影响**：`AuthenticationInterceptor.java:146-149`、`JudgeController.java:334-337`、`AdminController.java:231-234` 均接受 `?sessionId=`。实测带参访问受保护页面返回 200。凭证会进入访问日志、代理日志、Referer 与浏览器历史（CWE-598）。
- **建议**：仅保留 Cookie 与显式 Header 通道；如需程序化调用，统一走 `X-Session-ID`。

### N4（P2）仓库硬编码凭证与默认弱访问码

- **条件与影响**：`application.yml:5-7` 提交了 Spring Security 账号 （实际账号/口令已脱敏）（当前 `permitAll` 下未被使用，属敏感信息入库 + 死配置）；`application.yml:20-21` 默认访问码 （实际值已脱敏） 且 `server.address: 0.0.0.0`——实测用该默认访问码直接登录成功。`SecurityModeStartupValidator` 仅对非 `trusted-local` profile 拒绝默认码，而默认 profile 恰是 trusted-local。
- **建议**：删除硬编码账号；默认配置不含可用口令（强制首启设置）；trusted-local 也仅绑定 127.0.0.1。

### N5（P2）基线回归测试为红

- **条件与影响**：基线 `f31d039` 上 `mvn test` 失败：
  - `ApplicationYamlExecutionProfileTest.defaultProfileKeepsSmallLocalOutputCap`：期望 `max-output-bytes-per-case=1048576`（1MB），实际 10485760（10MB）。
  - `JudgeBaselineTest.generatorOutputLimitIsReportedAsOutputLimitExceededInsteadOfSystemError`：期望 OUTPUT_LIMIT_EXCEEDED，实际 AC。
  - 两者同根因：默认输出上限从 1MB 提高到 10MB 后未同步测试（后者的"超大输出生成器"按超出 1MB 设计）。
  - 另 7 个错误为环境问题：`TaskRunnerArtifactContractTest` 调用 `python` 命令，环境仅有 `python3`。
- **建议**：同步测试与配置预期；测试改为探测可用的 Python 解释器；将"基线测试绿"设为发布门槛之一（主文档 §5 阶段 D）。

### N6（P3）会话 IP/UA 绑定仅告警不拦截

- **条件与影响**：`AuthenticationInterceptor.java:95-98` 对 IP/UA 不匹配仅 `log.warn`。实测伪造 XFF 登录后用真实 IP 访问，日志出现"IP地址不匹配"警告后照常放行。会话被盗后没有网络环境变化的阻断手段。
- **建议**：至少对管理员会话启用强校验；普通会话可配置阈值（如 IP 段变化）。

### N7（P3）CSRF 全局关闭

- **条件与影响**：`SecurityConfig.java:20` 禁用 CSRF，判题创建/启动/取消等状态变更 POST 仅凭 Cookie。当前主要靠浏览器默认 SameSite=Lax 兜底（Cookie 未显式设置 SameSite）；WS 通道与 URL 传参通道（N1/N3）不受 SameSite 约束。
- **建议**：显式设置 SameSite；对状态变更接口补 CSRF token 或要求自定义 Header。

### N8（P3）运行中进度消息不携带统计

- **条件与影响**：`JudgeService.java:342` 运行中进度使用三参构造（无 summary），轮询端在终态前拿不到任何中间统计（实测轮询 40 用例任务全程 summary 为空）。属产品缺口而非安全问题，也影响 DEF-21 类问题的可观测性。
- **建议**：节流发布时附带当前聚合摘要。

## 5. 已定论的疑问（主文档遗留验证点）

1. **DEF-17**：Bootstrap 5.3.0 的 BaseComponent 对 null 元素静默早退、不抛异常。浏览器实测：页面 JS 正常初始化（CodeMirror 正常、load 监听执行、无控制台错误）；`showSuccess/showError`（`index.html:3917-3929`）的 null 守卫使**全部成功/错误提示静默失效**。P2 定级维持，"是否中止更大范围初始化"的疑问关闭（不会）。
2. **DEF-04 的"匿名握手待验证"**：已验证，匿名握手成功（升级见 §3.1）。
3. **DEF-21 的"清缓存或重启后才显露磁盘覆盖"**：已验证缓存与磁盘分叉（API ac=12/样本 12 vs 磁盘 ac=0/样本清零），与推断一致。

## 6. 对主文档修复顺序的增量建议

- 阶段 A（真实闭环）应加入 **N2 与 DEF-03 升级项**：不修则容器路径判题不可达，阶段 A 退出条件无法满足；N1/DEF-04 修复也应前置到阶段 A（否则联调期间进度通道即可被任意污染）。
- 阶段 B 增加 N3、N4、N7；阶段 C/回归门槛增加 N5（基线测试绿）。
- 主文档 §6 关闭记录模板继续适用于本附录条目（N1–N8）。

## 7. 结论

主文档全部 23 项结论成立，无虚构与夸大；本轮动态验证将其中 12 项升级为实测实锤，并确认两处被低估（DEF-03、DEF-04）。新增 N1–N8 后，总体判断不变并加强：**在 N1、N2、DEF-03/04/09/18/19/20 与 N4 修复并验收前，系统不可对公网提供不可信代码执行服务**；当前默认配置（弱默认访问码（实际值已脱敏） + 0.0.0.0）在内网部署同样需要立即修改。
