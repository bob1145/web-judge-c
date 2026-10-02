# 项目缺陷复审与修复清单

## 1. 基线与验证边界

- 整理日期：2026-10-02；审查基线：Git 提交 `f31d039`。
- 本文整理首轮深度评估与反证式复审，不代表编写文档时重新完成了全部验证。
- 文件路径相对项目根目录，行号对应审查基线，后续修改可能使行号偏移。
- 所有条目均未关闭；本次仅新增文档，没有修复业务代码。
- 2026-10-02 动态复核附录：见 `docs/defect-review-2026-10-02-dynamic-annex.md`。该轮已在真实环境运行动态验证：本文 23 项结论全部成立（12 项实测实锤），DEF-03、DEF-04 需按附录 §3 升级，另有新发现 N1–N8 与基线回归失败（附录 §4、N5）。
- 总体判断：工程基础较完整，但生产执行闭环、授权和资源边界仍有缺陷，不建议直接开放公网不可信代码执行。

### 证据等级与测试记录

- **源码确认**：调用链和分支支持结论，不等于真实环境复现。
- **有限动态验证**：此前复审提取模板实际 `connectAndSubscribeAsync()`，用 Node、成功 STOMP 桩和返回 429 的 fetch 桩执行，观察到 Promise 仍成功完成；不是浏览器 E2E。
- **部署待验证**：依赖镜像、代理、外部 Worker、浏览器或运行环境，仓库不足以独立定论。
- 未重新运行 Maven 回归、真实容器、Worker、浏览器端到端、资源耗尽或进程树攻击样例；此前环境未发现可用的 Java、Maven、Python、Docker 命令。
- 未进行依赖漏洞数据库审计。现有测试及历史通过记录不等于目标部署安全验收。

### 优先级

- **P1**：核心功能、结果正确性或安全边界缺陷；影响对应场景时应作为发布阻断项。
- **P2**：可靠性、错误处理、条件安全风险或长期资源增长，纳入近期修复。
- 有条件的 P1 不代表所有部署必然触发；关闭缺陷必须满足对应验收条件。

## 2. 缺陷索引

| ID | 优先级 | 缺陷 |
| --- | --- | --- |
| DEF-01 | P1 | 内置页面与账户认证契约不兼容 |
| DEF-02 | P1 | 宿主与容器任务路径契约未闭合 |
| DEF-03 | P1 | 空事件轮询被误判为任务结束 |
| DEF-04 | P1 | WebSocket 缺少任务级消息授权 |
| DEF-05 | P1 | 用户程序与控制文件共用可写目录 |
| DEF-06 | P1 | 宿主 JVM 无界读取事件文件 |
| DEF-07 | P1 | Python 完整收集输出后才限额 |
| DEF-08 | P1 | 父退出后遗留子进程可阻塞输出回收 |
| DEF-09 | P2 | 转发 IP 头可控时登录限流可绕过 |
| DEF-10 | P2 | 登录失败状态缺少容量与主动过期限制 |
| DEF-11 | P1 | Java/Python 普通比较语义不一致 |
| DEF-12 | P1 | 本地 double 比较误判大整数和 NaN |
| DEF-13 | P2 | CREATED 无取消/TTL，重启后不可恢复 |
| DEF-14 | P1 | 重启恢复遗漏 PENDING、COMPILING |
| DEF-15 | P1 | 配额预留非原子，调度缺用户运行许可 |
| DEF-16 | P2 | 前端忽略启动接口 HTTP 失败状态 |
| DEF-17 | P2 | Toast 初始化早于 DOM 节点 |
| DEF-18 | P2 | INFO 日志记录完整会话凭证 |
| DEF-19 | P2 | 会话 Cookie 固定不设置 Secure |
| DEF-20 | P2 | 登录 redirect 未限制站内路径 |
| DEF-21 | P2 | 取消/超时收尾覆盖真实统计 |
| DEF-22 | P2 | 进度节流 Map 不淘汰 |
| DEF-23 | P1 | cert-only 配置未兑现 mTLS 客户端认证 |

## 3. 缺陷详情

### DEF-01：内置页面与账户认证契约不兼容

- **条件与影响**：账户认证开启或 profile 非 `trusted-local` 时，页面仍只发访问码，后端缺少账号密码而拒绝认证；页面把失败显示为网络故障。
- **证据**：`src/main/resources/templates/auth.html:665`；`src/main/java/com/example/demo/controller/AuthenticationController.java:75`；`src/main/java/com/example/demo/service/UserAccountService.java:30`。
- **限定**：源码确认。仅内置页面不能建立新账户会话；正确 API 调用、已有有效会话不受此缺陷影响。
- **建议**：根据认证模式渲染字段，区分认证失败、限流和网络异常。
- **验收**：两种模式的真实浏览器登录、401/429 提示及成功跳转均有断言。

### DEF-02：宿主与容器任务路径契约未闭合

- **条件与影响**：Java 原样序列化宿主绝对路径，目录却挂载为 `/work` 或 `C:\work`；自带 Python 直接使用原路径，可能无法创建目录、通过包含关系检查或读取源码。
- **证据**：`src/main/java/com/example/demo/service/sandbox/LinuxContainerRunner.java:152`；`src/main/java/com/example/demo/service/sandbox/WindowsHyperVContainerRunner.java:168`；`runner/scripts/task_runner.py:46`。
- **限定**：自带 Python 且路径不同时源码确认；路径相同或转换 wrapper 可避免。外部 Windows native artifact、Remote Worker 是否重映射待验证，不能断言全部部署必失败。
- **建议**：区分宿主与执行环境路径，优先使用任务根内相对路径，明确转换责任。
- **验收**：不同宿主/容器路径完成真实编译、判题与事件读取；Windows/Worker 分别提供转换证据。

### DEF-03：空事件轮询被误判为任务结束

- **条件与影响**：异步启动或编译中暂无事件，Provider 返回空列表，服务立即退出并发布 SYSTEM_ERROR，正常任务被提前终结。
- **证据**：`src/main/java/com/example/demo/service/JudgeService.java:423`；`src/main/java/com/example/demo/service/sandbox/LinuxContainerRunner.java:186`；`src/main/java/com/example/demo/service/sandbox/WindowsHyperVContainerRunner.java:202`。
- **限定**：容器路径源码确认；Remote 取决于外部返回语义。空列表路径缺少即时取消，调度收尾取消预算定时器，但不是永不清理：`src/main/java/com/example/demo/service/TaskCleanupService.java:97` 有过期残余回收，默认失败保留 24 小时、清理间隔 30 分钟。
- **建议**：区分暂无事件、进程退出和终态；设置轮询间隔、截止时间及失败回收。
- **验收**：覆盖“空→编译开始→空→结果→完成”、异常退出、取消与超时；容器及时回收，预算保护不提前丢失。

### DEF-04：WebSocket 缺少任务级消息授权

- **条件与影响**：已登录用户知道他人任务 ID 时，未见与 HTTP 等价的 STOMP 订阅所有权校验。
- **证据**：`src/main/java/com/example/demo/config/WebSocketConfig.java:29`；`src/main/java/com/example/demo/service/ProgressPublisher.java:78`。复审未找到任务级 SUBSCRIBE/SEND 授权实现。
- **限定**：源码确认授权缺口。存在 HTTP 认证拦截配置，不能说完全没有认证；匿名握手和消息注入是否成功待协议验证。Origin 白名单不替代任务授权。
- **建议**：绑定连接身份，订阅校验 owner/admin；禁止客户端向服务端发布主题发送消息，处理会话失效。
- **验收**：两账户交叉订阅、匿名连接、登出、过期会话和客户端 SEND 均有真实协议负向测试。

### DEF-05：用户程序与控制文件共用可写目录

- **条件与影响**：自带 Python 子进程继承 supervisor 身份，在包含事件、汇总、取消文件和可执行文件的可写目录运行，可污染本任务可信数据。
- **证据**：`runner/scripts/task_runner.py:46`；`runner/scripts/task_runner.py:184`；`runner/scripts/task_runner.py:319`；`runner/scripts/task_runner.py:348`。
- **限定**：该执行模型下源码确认。不是已证明的跨任务读写或容器逃逸；外部 native/remote 权限模型待验证。路径问题修复后仍需独立处理本问题。
- **建议**：隔离可信 supervisor 和用户进程身份、文件权限，控制通道不得由提交程序写入。
- **验收**：用户程序不能篡改权威事件、任务描述、取消信号和可信执行文件，工作区可写权限不破坏控制面。

### DEF-06：宿主 JVM 无界读取事件文件

- **条件与影响**：Provider 每次整体读取 events.jsonl，无字节或单行上限；过大事件文件可能造成宿主 JVM 内存与解析压力，反复全量读取增加 I/O。
- **证据**：`src/main/java/com/example/demo/service/sandbox/LinuxContainerRunner.java:190`；`src/main/java/com/example/demo/service/sandbox/WindowsHyperVContainerRunner.java:206`。
- **限定**：源码确认，未复现 JVM OOM；与 DEF-05 组合可放大风险。这与 Python 输出缓冲消耗容器内存不是同一问题。
- **建议**：按偏移有界增量读取，限制单行、单批和总额度；保留未完成尾行，避免部分写入被当作完整 JSON。
- **验收**：超大行、大量事件、分段写入和无换行尾部均受控，内存不随整个文件大小线性分配。

### DEF-07：Python 完整收集输出后才限额

- **条件与影响**：communicate 无界收集 stdout/stderr，返回后才检查截断；可能先耗尽容器预算，导致预期输出超限退化为 Runner 系统故障。
- **证据**：`runner/scripts/task_runner.py:376`；`runner/scripts/task_runner.py:389`。子 PID 内存监测无法准确约束 Python 父进程缓冲。
- **限定**：源码确认，资源影响未压测；容器内存隔离仍有效，不表示宿主内存无限增长，不泛化到外部 native/remote 实现。
- **建议**：边读边计数，超限终止执行组，只保留有界缓冲，同时覆盖 stdout/stderr。
- **验收**：隔离环境中的受控输出程序超过小额度即停止，产生稳定超限状态，supervisor 保持存活。

### DEF-08：父退出后遗留子进程可阻塞输出回收

- **条件与影响**：父退出但子进程持有输出管道，首次 communicate 超时；清理因父已退出直接返回，第二次无超时 communicate 可持续等待。
- **证据**：`runner/scripts/task_runner.py:376`；`runner/scripts/task_runner.py:379`；`runner/scripts/task_runner.py:394`。
- **限定**：静态控制流确认，未运行真实子进程样例。正常外层预算可能兜底，但 DEF-03 可提前解除保护；不能据此推断外部 Windows Job Object 实现同样有问题。
- **建议**：按进程组/cgroup/Job Object 管理生命周期，父退出仍回收后代，最终管道排空也设置超时。
- **验收**：父退出、子持管道时任务有界结束，无残余进程且不污染后续用例。

### DEF-09：转发 IP 头可控时登录限流可绕过

- **条件与影响**：控制器无条件优先采用转发头；后端可直连或代理未净化头时，修改来源标记即可改变限流 key。
- **证据**：`src/main/java/com/example/demo/controller/AuthenticationController.java:239`；`src/main/java/com/example/demo/service/UserAccountService.java:97`。
- **限定**：代码信任问题确认，利用依赖部署；可信代理强制重写头且禁止直连可缓解，不能宣称线上必然可绕过。
- **建议**：建立受信代理边界，分别设置账户级、来源级限流，避免仅用组合 key。
- **验收**：伪造头不能改变可信来源，同账户更换来源标记不能无限尝试；代理解析行为有正反向断言。

### DEF-10：登录失败状态缺少容量与主动过期限制

- **条件与影响**：随机用户名产生不同 Map 条目，清理依赖再次访问同一 key，状态随唯一 key 数量增长。
- **证据**：`src/main/java/com/example/demo/service/UserAccountService.java:27`；`src/main/java/com/example/demo/service/UserAccountService.java:73`。
- **限定**：账户模式不依赖 IP 伪造即可积累；会话定时清理不清这些 Map。增长源码确认，未测量 OOM 门槛。
- **建议**：采用容量有界、主动过期的缓存或限流存储，增加来源总速率限制。
- **验收**：大量不同 key 不突破容量上界；旧记录无需再次访问即可过期，淘汰不能轻易绕过限流。

### DEF-11：Java/Python 普通比较语义不一致

- **条件与影响**：Java 按空白分词和数值精度比较，Python 仅 trim 后比字符串；精度没有进入任务契约，同一普通判题请求可能获得不同结果。
- **证据**：`src/main/java/com/example/demo/service/JudgeService.java:702`；`runner/scripts/task_runner.py:220`；`src/main/java/com/example/demo/dto/SandboxTaskSpec.java:12`。
- **限定**：源码确认。Python 符合自身文档，缺口是产品 UI 与执行路径不统一；不泛化到自定义 SPJ。
- **建议**：统一空白、数值、编码与精度规则，必要参数进入契约。
- **验收**：共享测试向量覆盖重复空白、换行、小数容差和精确文本，所有支持的普通比较路径结果一致。

### DEF-12：本地 double 比较误判大整数和 NaN

- **条件与影响**：数字 token 均转 double，`9007199254740992` 与 `9007199254740993` 可能丢失差异；NaN 的差值不满足“大于精度才拒绝”，可能错误接受。
- **证据**：`src/main/java/com/example/demo/service/JudgeService.java:710`；`src/main/java/com/example/demo/service/JudgeService.java:712`。
- **限定**：本地普通比较源码结论，Java 回归待补；这是独立于跨 Runner 差异的错误接受问题。
- **建议**：明确整数精确比较、浮点容差、非有限数和 precision 合法性规则，避免统一降精度。
- **验收**：上述相邻大整数不能判等，NaN 与普通数字不能错误判等，负精度、非有限输入和边界容差有明确断言。

### DEF-13：CREATED 无取消/TTL，重启后不可恢复

- **条件与影响**：创建未启动任务占 queued 配额，取消只查调度队列/运行表，非终态又不参与到期清理；重启丢失 pending 请求 Map 后，持久化 CREATED 无现有 API 恢复路径。
- **证据**：`src/main/java/com/example/demo/service/JudgeService.java:221`；`src/main/java/com/example/demo/service/JudgeService.java:233`；`src/main/java/com/example/demo/service/JudgeScheduler.java:163`；`src/main/java/com/example/demo/service/TaskCleanupService.java:135`。
- **限定**：源码确认。同一进程仍可 start 并完成释放额度，不能笼统称永久占额；重启才使恢复缺口进一步暴露。
- **建议**：支持 CREATED 取消和未启动 TTL，定义重启后恢复请求或转终态策略，回收内存与额度。
- **验收**：创建后取消、断网、刷新、过期及重启不产生无恢复路径的占额任务。

### DEF-14：重启恢复遗漏 PENDING、COMPILING

- **条件与影响**：这些状态实际落盘且计入 running 配额，但恢复只处理 RUNNING/QUEUED；中断重启后可能留下不可启动、取消或过期清理的任务和残余句柄。
- **证据**：`src/main/java/com/example/demo/service/FileTaskStore.java:244`；`src/main/java/com/example/demo/service/TaskCleanupService.java:59`；`src/main/java/com/example/demo/service/QuotaService.java:163`。
- **限定**：源码确认；已有恢复机制对 RUNNING/QUEUED 有效，问题是非终态覆盖不完整。
- **建议**：集中定义非终态恢复策略，统一处理状态、句柄、配额和结果。
- **验收**：逐个非终态注入重启，确认合法恢复或终结、残余执行回收及用户配额恢复。

### DEF-15：配额预留非原子，调度缺用户运行许可

- **条件与影响**：同用户并发创建可同时通过额度检查再分别落盘；先创建多个任务再启动，可在全局并发高于用户上限时突破运行配额。
- **证据**：`src/main/java/com/example/demo/service/JudgeService.java:200`；`src/main/java/com/example/demo/service/JudgeService.java:217`；`src/main/java/com/example/demo/service/JudgeScheduler.java:77`。
- **限定**：源码确认。按 judgeId 的锁不能保护同用户不同任务；全局队列仍受限。默认全局并发为 1 会掩盖运行许可问题，但不消除创建竞态。
- **建议**：额度检查和预留原子化；执行时获取用户许可，失败、取消和完成均正确释放。
- **验收**：同步屏障下争抢最后额度，只有允许数量成功；提高全局并发后仍遵守用户上限；异常回滚不泄漏许可。

### DEF-16：前端忽略启动接口 HTTP 失败状态

- **条件与影响**：start 返回 429 等非 2xx 仍 resolve；新任务未入队时没有后续消息恢复页面，回退轮询也不会自动启动任务。
- **证据**：`src/main/resources/templates/index.html:3122`；`src/main/resources/templates/index.html:3046`；`src/main/java/com/example/demo/controller/JudgeController.java:142`。
- **限定**：源码确认及前轮 Node 有限动态验证。不是所有非 2xx 都永久卡住，实际运行任务可能被后续终态恢复；不是浏览器 E2E。
- **建议**：统一检查创建、启动、查询 HTTP 状态；失败恢复按钮和提示，不可恢复错误停止轮询。
- **验收**：真实页面处理 401、404、429、网络断开，拒绝启动不持续显示运行，重试正常。

### DEF-17：Toast 初始化早于 DOM 节点

- **条件与影响**：同步脚本立即读取 Toast 和消息节点，但节点 HTML 位于脚本之后；至少消息引用为 null，提示失效。
- **证据**：`src/main/resources/templates/index.html:2605`；`src/main/resources/templates/index.html:2607`；`src/main/resources/templates/index.html:3977`。
- **限定**：源码确认；后续 load 监听不重新初始化。Bootstrap 是否抛异常并中止更大范围初始化，未做真实浏览器验证。
- **建议**：节点渲染后初始化，或统一在 DOM 就绪后初始化应用。
- **验收**：浏览器无初始化异常，提交校验、请求失败和成功完成提示可见。

### DEF-18：INFO 日志记录完整会话凭证

- **条件与影响**：访问码和账户登录进入同一会话创建方法，INFO 日志包含可认证的完整 sessionId。
- **证据**：`src/main/java/com/example/demo/service/AccessCodeService.java:113`；`src/main/java/com/example/demo/controller/AuthenticationController.java:87`。
- **限定**：源码确认；利用需要日志读取权限和仍有效的会话，IP/UA 不一致仅日志提示。本文不复制任何实际凭据。
- **建议**：不记录 bearer 凭证，使用不可逆关联指纹；评估历史日志暴露及会话失效需求。
- **验收**：捕获两种登录模式日志，确认没有完整凭证且审计关联仍可用。

### DEF-19：会话 Cookie 固定不设置 Secure

- **条件与影响**：应用硬编码 Secure=false，存在 HTTP 可达条件时有效会话可能随明文请求发送。
- **证据**：`src/main/java/com/example/demo/controller/AuthenticationController.java:208`；`src/main/java/com/example/demo/controller/AuthenticationController.java:227`。
- **限定**：代码确认，实际暴露待部署验证；HttpOnly 已设置，HSTS/代理改写可能缓解，不等于已发生泄露。
- **建议**：生产强制 Secure，明确 SameSite 和代理信任策略，统一创建与失效属性。
- **验收**：生产 Cookie 属性、真实浏览器 HTTP/HTTPS 行为及登出符合预期，开发模式兼容策略明确。

### DEF-20：登录 redirect 未限制站内路径

- **条件与影响**：已登录 GET 分支直接使用外部 redirect，登录成功响应也透传，可用于可信域登录后的站外跳转。
- **证据**：`src/main/java/com/example/demo/controller/AuthenticationController.java:50`；`src/main/java/com/example/demo/controller/AuthenticationController.java:96`。
- **限定**：源码确认开放重定向；不自动等同会话泄露或账号接管。
- **建议**：统一允许站内相对路径，拒绝绝对 URL、协议相对 URL 及非预期协议。
- **验收**：已登录 GET 和两种登录成功分支拒绝站外目标，合法站内返回地址可用。

### DEF-21：取消/超时收尾覆盖真实统计

- **条件与影响**：执行层发布真实部分汇总，调度器随后按取消/预算 token 写占位汇总；同终态仍可覆盖，完成数保留而判定计数和样本清零。
- **证据**：`src/main/java/com/example/demo/service/JudgeService.java:348`；`src/main/java/com/example/demo/service/JudgeScheduler.java:209`；`src/main/java/com/example/demo/service/JudgeScheduler.java:326`。
- **限定**：源码确认。不是全部正常汇总丢失；缓存可暂时掩盖，清缓存或重启后才显露磁盘覆盖。
- **建议**：统一终态汇总写入责任；调度器仅在无有效结果时兜底，不覆盖真实统计。
- **验收**：若干 AC/WA 后取消或超时，对比实时、磁盘、清缓存及重启结果，计数、样本、停止原因一致。

### DEF-22：进度节流 Map 不淘汰

- **条件与影响**：每个发布过进度的任务留下 SentState，任务结束、状态缓存回收和磁盘 TTL 清理都不删除该 Map 条目。
- **证据**：`src/main/java/com/example/demo/service/ProgressPublisher.java:41`；`src/main/java/com/example/demo/service/ProgressPublisher.java:104`。
- **限定**：源码确认历史任务数相关增长；每项是小型状态，未测量实际内存影响，不能宣称少量任务就会 OOM。
- **建议**：在安全终态生命周期点回收，或使用有界 TTL 缓存，保留重复终态处理约定。
- **验收**：大量短任务完成后条目数有可验证上界，运行中节流和终态即时通知正确。

### DEF-23：cert-only 配置未兑现 mTLS 客户端认证

- **条件与影响**：非空 mtls-certificate 可通过相关认证配置检查，但 Runner 未据此加载证书/私钥或 SSLContext，只发送固定 `X-Judge-Worker-Mtls: configured` 头；这些检查也未强制 HTTPS。
- **证据**：`src/main/java/com/example/demo/config/WorkerProperties.java:24`；`src/main/java/com/example/demo/config/SandboxProperties.java:46`；`src/main/java/com/example/demo/service/sandbox/RemoteWorkerRunner.java:58`；`src/main/java/com/example/demo/service/sandbox/RemoteWorkerRunner.java:160`。
- **限定**：源码确认配置与实现缺口。JVM 全局 TLS 或代理可能另行提供 mTLS；有效 Bearer/HMAC 不能据此判为无认证。只确认认证配置检查放行，不表示已实测绕过全部生产启动检查。
- **建议**：实现真实证书/私钥加载、TLS 配置和 HTTPS 约束；完成前拒绝 cert-only 模式，不把固定头当作认证。
- **验收**：任意证书字符串不能被视为已认证；真实 mTLS 握手验证身份，缺失/无效证书被拒；Bearer/HMAC 独立回归。

## 4. 复审纠偏记录

1. 撤回“残余容器永不清理”的泛化说法：确认缺少即时回收，但存在过期清理。
2. 不把自带 Python 缺陷推广到仓库外 Windows native artifact 或远程 Worker。
3. 不把 WebSocket 授权缺失直接等同已复现匿名握手、消息注入。
4. 不把同进程仍可启动的 CREATED 描述为无条件永久占额；重启恢复缺口单列。
5. 限流绕过依赖代理和后端直连边界，必须检查实际部署。
6. 无界增长不等于已复现 OOM；本任务控制文件污染不等于容器逃逸。
7. Cookie、重定向和凭证日志风险不等于已发生账号接管。
8. 模拟 Runner、事件规模测试和前端字符串断言不等于真实执行与浏览器验收。

## 5. 修复顺序与发布门槛

| 阶段 | 范围 | 退出条件 |
| --- | --- | --- |
| A：真实闭环 | DEF-01、02、03、16、17 | 目标环境完成登录、提交、编译、判题、查询、取消、清理；失败分支可恢复。 |
| B：安全边界 | DEF-04～10、18～20、23 | 身份、任务授权、可信控制通道、资源额度、实际代理/TLS 均有正反向验收。 |
| C：结果与生命周期 | DEF-11～15、21、22 | 比较跨路径一致；非终态重启可恢复或合法终结；配额、统计、缓存生命周期正确。 |
| D：目标主机验收 | 所有适用缺陷 | 真实镜像/Worker/浏览器、受控负载和故障注入通过，记录容量边界及残余风险。 |

阶段可并行，但先打通功能不等于可以提前开放公网；发布必须同时满足功能与安全门槛。

### 架构建议，不计为额外已确认缺陷

- 集中状态转换与终态汇总责任，减少调度内存、服务缓存、metadata、summary 的多处状态真相。
- 明确当前单实例支持边界；确有多实例需求后再引入共享存储、原子配额与协调，避免无需求重构。
- 配额全量扫描随留存任务增长，按实测决定是否增加索引或独立账本。
- 前端先提取 API 错误处理、任务状态控制和结果渲染，不必为修复立即迁移整个框架。

## 6. 缺陷关闭记录模板

每项修复后追加以下记录，不能仅以代码已修改视为关闭：

```text
缺陷 ID：
负责人：
修复提交：
适用模式与部署条件：
回归测试及结果：
真实环境证据（如适用）：
未覆盖范围与残余风险：
复核人及关闭日期：
```

测试必须在隔离、资源有界的环境执行；本文不是对生产环境运行资源耗尽或不可信进程样例的授权。
