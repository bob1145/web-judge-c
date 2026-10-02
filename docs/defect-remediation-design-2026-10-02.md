# 缺陷修复详细设计（2026-10-02）

## 1. 文档定位与设计基线

- 状态：待评审设计，不是实现说明或安全验收报告；本文不关闭任何缺陷。
- 配套需求：`defect-remediation-requirements-2026-10-02.md`。
- 证据输入：`defect-review-2026-10-02.md`、`defect-review-2026-10-02-dynamic-annex.md`，审查基线 `f31d039`。冲突时以动态附录升级后的结论为准。
- 本次核对了本地认证、消息、runner、文件存储、调度、配额、比较与前端实现。本文“新增”“建议”“调整”均指未来实现，不代表已有能力。
- Maven 193 项、2 失败、7 错误、2 跳过来自动态附录，不是本次重新运行结果；新方案的容器、Windows artifact、外部 Worker 和浏览器验收尚未执行。
- 覆盖 DEF-01～DEF-23、N1～N8。N1 与 DEF-04 共用实现、独立验收；N2 与 DEF-03/05/06 共用链路改造、单独追溯。

### 1.1 范围与非目标

保留 Spring Boot、现有页面、文件型 `TaskStore`、`SandboxRunner` provider 抽象和 HTTP 路径。首期仅支持单应用实例独占 storage base；不引入数据库、分布式锁、消息中间件或前端框架迁移。`trusted-local/direct` 仅用于可信代码，不因修复而变为公网不可信执行方案。

删除历史数据、批量迁移、生产配置变更和凭证轮换是后续需审批的运维动作，本次只编写文档。未通过隔离验收的 provider 在生产模式拒绝启动，不回退 direct。

## 2. 现状、目标架构与职责

### 2.1 复用与调整

以下 Java 路径相对 `src/main/java/com/example/demo/`。

| 现有组件 | 保留职责 | 调整点 |
| --- | --- | --- |
| `controller/AuthenticationController.java`、`interceptor/AuthenticationInterceptor.java` | 登录与认证 | 统一凭证、认证模式、Cookie、跳转与来源 |
| `config/WebSocketConfig.java`、`config/SecurityConfig.java` | 安全装配 | 握手认证、STOMP 授权、CSRF 与 Origin |
| `service/UserAccountService.java`、`service/AccessCodeService.java` | 账号/访问码 | 有界限流、移除凭证日志 |
| `service/JudgeService.java`、`service/JudgeScheduler.java` | 编排与调度 | 显式轮询状态、统一终态、用户许可 |
| `service/FileTaskStore.java`、`service/TaskCleanupService.java` | 持久化与回收 | 原子终态快照、全非终态恢复、CREATED TTL |
| `service/QuotaService.java` | 配额检查 | 原子预留、运行许可、恢复重建 |
| `service/SandboxEventIngestor.java`、`service/ResultAggregator.java` | 事件聚合 | 事件校验去重、冻结终态 |
| `service/ProgressPublisher.java` | 进度节流 | 有界缓存、终态释放、运行摘要 |
| `service/sandbox/*Runner.java` | provider 适配 | 路径转换、增量事件、活跃/退出判定 |

另调整仓库根目录下的 `runner/scripts/task_runner.py`、`src/main/resources/templates/auth.html` 和 `src/main/resources/templates/index.html`，不拆换整体技术栈。

### 2.2 目标数据流

```text
浏览器/API -> HTTP会话或WS握手 -> CSRF/任务授权
  -> JudgeService -> 原子配额预留 -> FileTaskStore
  -> JudgeScheduler -> 用户/全局运行许可 -> SandboxRunner
  -> 可信监督器 -> 受限编译器/生成器/答案程序/候选程序/SPJ
  -> 有界可信事件 -> SandboxEventIngestor -> ResultAggregator
  -> 单一终态提交 -> 文件权威快照 -> HTTP查询/WS通知
```

建议新增小型 `SessionCredentialResolver`、`ClientAddressResolver`、`TaskLifecycleService` 及共享增量事件读取器。前两者只统一安全解析，生命周期服务只负责状态仲裁与终态提交，避免创建新的大服务。文件存储是重启后的事实来源，缓存和消息是投影。

## 3. 认证、会话与消息安全设计

对应 DEF-01、DEF-04、DEF-09、DEF-10、DEF-18、DEF-19、DEF-20、DEF-23，以及 N1、N3、N4、N6、N7。

### 3.1 统一身份入口与兼容方式

1. 复用 `requiresAccountAuthentication()` 判断：启用账号认证或非 trusted-local 时使用账户表单，其他情况使用访问码。服务端渲染 `/auth` 模式标识，不暴露账户列表、密码或完整配置；保留 rememberMe。
2. 保留 `/auth/verify`、`/auth/session`、`/auth/logout`、`/auth/check` 及响应字段。前端同时检查 HTTP 状态和业务 success：账户 401、429、服务器异常与网络异常分别提示；访问码历史 HTTP 200 + success=false 也必须正确展示。本修复不强制统一两种模式的失败状态码。
3. 所有入口共用凭证提取：只接受 `JUDGE_SESSION` Cookie 和 `X-Session-ID`。移除 query sessionId 的认证能力，覆盖 MVC、Judge/Admin Controller 与 WS；Cookie 与 Header 同时存在且不同则拒绝。URL 参数不回显、不进入认证日志。
4. 复用现有会话存储与过期机制；登出、过期、禁用后 HTTP/WS 均失效。网页不把凭证写入 URL、本地存储、控制台或异常消息；程序客户端迁移到 Header。
5. 全量清理 INFO/WARN/DEBUG 的凭证输出，不只是附录的一处日志。使用 traceId、用户标识、原因码，必要时分配独立随机会话审计标识；不记录 sessionId、Cookie、认证 Header 或密码。历史泄露会话由运维失效，日志访问权限另行审查。

### 3.2 Cookie、重定向与 CSRF

- Cookie 创建/删除共用策略：HttpOnly、显式 SameSite=Lax、Path=/；保留 rememberMe 持久 Cookie 与普通会话 Cookie 的现有区别。生产 HTTPS 强制 Secure，仅 trusted-local loopback HTTP 允许例外。删除时匹配 Path/Domain 及安全属性。
- TLS 判定只信显式受信代理，不由任意 X-Forwarded-Proto 关闭 Secure。生产外部协议配置与启动校验一致；矛盾配置直接失败。
- 登录 GET 的 302 与 POST 响应共用 redirect 验证：只允许规范化后的站内绝对路径，拒绝 scheme、authority、双斜杠、反斜杠、控制字符、编码歧义和登录循环；非法回退 `/`。测试包括编码斜杠、多重编码，不使用“包含本站域名”的判断。
- Cookie 鉴权的状态变更启用会话绑定 CSRF token，模板/既有会话响应向页面提供 token，fetch 使用固定 Header。覆盖登录、登出、创建、启动、取消、清理和管理员操作；登录前 token 登录成功后轮换。SameSite 不是替代方案。
- 仅实际通过 `X-Session-ID` 认证的程序请求可走明确无 Cookie 模式；不能因为存在任意 Header 就跳过 CSRF，也不能 Header 失败后回退 Cookie。CORS 不允许任意来源携带凭证。
- WS/SockJS 应用精确 Origin 白名单，浏览器 STOMP CONNECT 带绑定 token。只对确有需要的 SockJS transport 做窄范围 HTTP CSRF 适配，不恢复全局禁用。

### 3.3 WebSocket 多层授权

| 层级 | 必须检查 | 失败行为 |
| --- | --- | --- |
| `/ws` 及 SockJS 握手/transport | 有效会话、允许的 Origin、可信来源 | 拒绝匿名可用连接，不只等订阅时认证 |
| CONNECT | 服务端绑定 Principal、会话有效、CSRF | ERROR/断开，不信客户端自报用户身份 |
| SUBSCRIBE | 严格匹配 `/topic/progress/{judgeId}`，复用 `QuotaService.canAccessTask` | 他人/不存在任务同类拒绝，通配主题拒绝 |
| SEND | 目前没有客户端生产进度的业务 | `/topic/**`、`/queue/**`、未开放 `/app/**` 全拒绝 |
| 既有订阅 | 登出、到期、撤销 | 服务端主动关闭，不等待客户端下一帧 |

握手拦截器绑定身份，入站 ChannelInterceptor 授权，维护有界 session-to-connection 索引，登出立即关闭，到期按 expiresAt 回收并定期兜底。仅 CONNECT/SUBSCRIBE 校验不足以阻止已失效订阅收消息。限制连接/订阅数、帧大小和速率，断连释放。管理员访问范围复用 HTTP 规则，不新增隐式越权。

### 3.4 可信来源与有界登录限流

`ClientAddressResolver` 默认用 socket remote address；仅当直接对端属于配置的 trusted-proxy CIDR，才解析指定转发头。从右向左剥离受信代理，取首个不受信地址；非法/超长链拒绝。不同时盲信多种头，避免框架与应用二次解析。边缘代理覆盖外部输入，部署禁止绕过代理直连。

账号级与来源级限制独立计算，不能只用 username+ip 组合；访问码至少来源级和全局保护。统一未知/有效账户的失败提示。将计数/时间合并为原子更新记录，两种认证模式共用有界 TTL 组件；保留现有 maxAttempts/attemptResetHours 含义。

容量满不能 LRU 逐出未过期封禁项：对新 key 保守拒绝或启用有界全局退避，已有锁定仍有效。主动过期不能依赖再次访问相同 key。用可替换时钟验证 TTL，指标不以每个随机用户名/IP 为标签，避免高基数转移到监控。

### 3.5 安全默认值与会话环境变化

删除未使用硬编码 Spring 账号，同时移除 `AuthConfiguration` 等代码中的可用访问码 fallback，仅改 YAML 不足。访问码模式必须外部显式提供凭证，账号模式不能被无用访问码配置阻塞。默认监听 127.0.0.1，生产开放监听必须显式配置并通过认证/沙箱校验；不打印首启长期可用口令。

N6 建议 OFF/AUDIT/STRICT 策略及管理员覆写；管理员来源/规范化 UA 变化再认证的默认值须产品与部署确认。STRICT 在真实校验服务中拒绝并失效会话/WS，不能外层改条件而内层始终返回 true。AUDIT 只记录脱敏事件；IP/UA 绑定不是 TLS 或防盗凭证的替代，需回归移动网络和代理切换。

### 3.6 Remote Worker 认证与 TLS

首期移除固定 `X-Judge-Worker-Mtls: configured` 的认证含义；非空证书字符串不代表认证能力。统一 `WorkerProperties`、Sandbox 嵌套 worker、Runner Options 三处校验与配置解析，冲突拒绝。生产要求 HTTPS、服务端证书/主机名验证及有效 Bearer/HMAC 配置，不允许重定向把凭证发送到其他端点。

首期可不新增证书管理：明确拒绝选择未实现的 mTLS/cert-only 模式，保留已有 Bearer/HMAC 协议和签名输入，记录迁移。这只表示伪认证被封堵，不表示已支持 mTLS。若部署必须 mTLS，则实现证书链/私钥或 keystore、truststore、SSLContext 与真实双向 TLS 握手；缺失/失效证书、错误服务端身份均拒绝。外部 Worker 的验签、重放保护和协议支持须由服务端证据证明，客户端单测不能背书。

## 4. Runner 协议、可信边界与资源约束

对应 DEF-02、DEF-03、DEF-05、DEF-06、DEF-07、DEF-08、N2。必须成组交付：仅改路径、改文件名或增加 sleep 无法修复完整链路。

### 4.1 存储布局与执行权限

建议新任务采用以下布局；历史任务无需立即搬迁：

```text
storageBase/judge-{judgeId}/
  metadata.json                  宿主状态，仅TaskStore写
  events.jsonl                    宿主审计日志，保留原有用途
  summary.json                    旧读兼容投影
  final.json                      新增：终态+摘要+revision权威提交
  control/                       仅宿主/可信监督器访问
    task-spec.json
    attempts/{attemptId}/runner-events.jsonl
  sources/                       校验后的源码，执行侧只读
  workspace/                     阶段/用例私有工作区
  artifacts/                     有界产物，下载前重新验证
```

- 宿主审计与 runner 流物理分离且单写者；metadata/final/audit 不挂进不可信执行环境。取消通过可信控制通道，不以用户可写 cancel.flag 为权威。
- 监督器与不可信程序不得共享可冒充身份、控制目录写权限、控制 FD 或签名密钥。改名/隐藏路径/同 UID chmod 不是权限隔离。
- 生产 Linux 首选监督器管理独立受限执行单元。若同容器运行，必须验证不同身份、目录权限、ptrace、`/proc/*/fd`、信号与提权限制，控制 FD 设置 close-on-exec。不能假定现有非 root + cap-drop ALL 的 Python 能安全切 UID。
- Windows 用实际 ACL、受限 token、Job Object 或等效独立执行域，不套 POSIX chmod。外部 native artifact 无法证明边界时禁用生产 provider，不把 Python 缺陷或修复自动推广给它。
- 编译器、GENERATOR、USER、ORACLE、SPECIAL_JUDGE 都是不可信执行，只获得当前阶段必要输入和 scratch；答案/生成器不因角色而可信。源码/产物拒绝 symlink、reparse point、穿越和校验后替换；下载入口同样验证。
- case 内存额度与监督器预算分开；若共用外层容器资源，显式保留有界监督开销，不无限提高容器内存来掩盖超额。

### 4.2 路径命名空间与协议版本

现有 `SandboxTaskSpec` 包含宿主绝对 workDir/sourcePaths，必须与执行清单分离。保留宿主 domain spec，由 adapter 显式生成 provider wire spec；新增协议版本/能力协商，不能用宿主 Path 解析另一个 OS 的绝对路径。

建议 v2 以相对路径描述源码角色，执行根由 provider 固定，Linux `/work`、Windows `C:\work` 仅表示执行空间。所有路径在执行侧根内规范化，拒绝绝对路径、父级、驱动器/UNC、链接越界、非本任务源码；先验证全部输入，再创建任何目录/控制文件。宿主 storageBase 不成为可执行路径。

v2 显式携带 protocolVersion、judgeId、attemptId、比较策略/precision、资源限额与保留策略；时间/字节单位固定，缺省不代表无界。新 attempt 使用新事件文件，不截断正在消费的流。Worker 必须明确支持该版本，否则拒绝；不能静默丢字段。host、镜像、Windows artifact、Worker 成套升级并保存兼容矩阵。

### 4.3 显式轮询状态与截止时间

当前 `pollEvents(handle): List<SandboxTaskEvent>` 无法表达仍在运行。建议内部接口替换为以下结果，所有 provider 与测试同步修改：

| 字段 | 语义 |
| --- | --- |
| events | 本次有界事件批次，可空 |
| executionState | ACTIVE / EXITED / UNKNOWN；UNKNOWN 不是退出 |
| exitCode | EXITED 时尽可能提供 |
| nextCursor | provider 的不透明游标，文件实现使用字节偏移 |
| hasMore | 当前仍有完整事件未读，辅助退出后的 drain |

编排使用单调时钟 deadline，覆盖启动/编译/执行及有限收尾：

1. 校验并接收一批事件，可信终态获胜后不再接收晚到结果。
2. ACTIVE+空批次：可中断等待后继续，绝不结束；取消/任务预算到期立即进入统一收尾。
3. EXITED：有界 drain 剩余完整事件并给有限 flush 宽限；读尽仍无终态才 SYSTEM_ERROR，不能丢弃退出时最后一批 SUMMARY/COMPLETED。
4. UNKNOWN/短暂传输异常：有限重试和退避，达到连续失败预算或任务 deadline 就终结；不能无限续期。
5. 成功、取消、异常、超时均调用幂等清理；清理失败保留待回收记录，重启可凭持久化句柄继续。

建议工程初值：poll interval 100ms、退出 flush 宽限 1s、强制清理宽限 2s。数值待目标容量测试定版，不是性能承诺；各次调用、等待、drain 均受剩余预算限制，测试使用可控时钟，生产不忙轮询。

### 4.4 有界增量事件读取

共享 Linux/Windows 文件读取器，状态为文件身份、attemptId、字节 offset、最多一行尾缓冲。逐块读取，不使用 readAllLines/全文件字符串/从头扫描后 collect。建议单轮 256KiB 或 256 条事件，单行 128KiB；正式值需容纳已有有界样本并两端一致。

- 大输出只传有限 preview 和经过验证的产物引用，不内嵌全量输出；增加任务事件总字节/磁盘限额，防止内存有界而磁盘无界。
- 半行暂存，等换行再解析；UTF-8 跨块不能损坏。退出后残留半行超过 flush 宽限为协议错误。
- 完整行 JSON 非法、类型未知、任务/attempt 不匹配、case 越界、字段或 JSON 深度超限均拒绝；不能静默吞 type=null 并推进游标。
- 事件增加 protocolVersion、attemptId、单调 sequence；attemptId+sequence 去重，重复不增加统计，乱序/缺号按协议错误处理。任务已有 runId 与 attempt 映射须持久化。
- 同 attempt 文件缩短/替换不是正常空轮询：探测后受控终结、保留已有统计，不重置为零回放；只有新 attempt 才允许重置。这是安全容错，不是接受任意截断。
- 检查 completedCases 不大于 totalCases、分类计数之和、样本上限；SUMMARY 不任意覆盖宿主聚合器，完成消息与累计结果矛盾则协议错误。

### 4.5 在线输出限制与全进程树治理

以增量 stdout/stderr 消费替代 Python 全量 communicate，两个流同时 drain 防死锁。保留有限前缀或有界 spool，首个超额字节立即终止执行域，不把无限数据转移到临时文件。适用于生成器、答案、候选程序、SPJ、编译诊断。

保持 Python 当前单次子进程 stdout+stderr 合计限额语义，`<=limit` 合法；生成器 stdout 成为输入后也不得超过配置。Java 路径若存在差异，契约测试需统一并记录兼容影响，而不是分别解释同一参数。编译诊断独立有限额度；并发总峰值由并发数、各流预算和固定缓冲共同约束。超限必须呈现 OUTPUT_LIMIT_EXCEEDED 用例结果/对应停止原因，不能报告 AC。

Linux 使用进程组加 cgroup/等效执行域覆盖所有后代，父退出、fork 后 setsid、持有管道都不能逃离；仅 killpg 不足以对抗恶意进程树。Windows 使用 kill-on-close Job Object 并禁用 breakaway 或等效机制。取消/超时/超输出停止派发、有限等待、强制终止，二次 drain/join/wait 也有 deadline。缺乏能力的 provider 生产探针失败。

验收在有外层 watchdog/内存/磁盘限制的隔离环境：输出配置上限+小增量即终止；父退出后子持管道样例在 case deadline+cleanup grace+测量容差内结束；记录监督器 RSS、磁盘峰值、残余进程数。原附录 300MB/414MB、2s/26.1s 是历史证据，不直接作为新性能目标。

## 5. 跨执行路径普通比较设计

对应 DEF-11、DEF-12。SPJ 保留独立协议，不套普通比较。

### 5.1 单一语义（建议，经产品评审冻结）

| 情况 | 规则 |
| --- | --- |
| 编码/空白 | UTF-8 严格解码；ASCII 空白集合（空格、Tab、CR、LF、FF、VT）分词；空白-only 与空串等价 |
| token 数不同 | WA |
| 两个整数 token | 任意精度十进制整数精确比较，允许正负号/前导零，不转 double |
| 其他有限十进制数字对 | 小数/科学计数法以精确十进制计算绝对误差，abs(a-b) <= precision |
| 文本/NaN/Infinity 等非有限字面量 | 普通文本区分大小写精确比较；NaN 与 5 必为 WA |
| precision | 有限非负，缺省沿用现有值；负值/NaN/Infinity/解析越界在创建前拒绝 |

两端数值词法一致。Java 使用 BigInteger/BigDecimal，Python 不能用默认 Decimal context 隐式舍入；采用精确系数/指数比较，或依据受限数字长度设置足够精度并对舍入报错。限制 token 字节数和指数绝对值，避免任意精度 DoS；上限同步冻结，不能一端当文本一端拒绝。

无效 UTF-8、超长 token、指数越界为明确比较输入错误，不得判 AC，映射用契约测试固定。保留绝对误差意图，不暗中加入相对误差。`5` 与 `5.0` 数值等价，两个相邻超大整数必不相等；相同 NaN 文本是否视为文本相等属于已列出的产品决策。

### 5.2 参数贯通与共享向量

precision/compareMode 从 JudgeRequest 进入策略快照、宿主 spec、执行清单及 Worker；precision 以十进制字符串跨语言传输，避免 JSON 数字先损失精度。旧 provider 不支持则拒绝，不能静默采用字符串比较。

当前页面默认 precision=1e-9，而 JudgeRequest 是 primitive double，API 缺字段时为 0，两者不是同一默认值。建议保留页面 1e-9、旧 API 缺省 0，并在 DEC-03 明确批准；若统一默认属于可见行为变更。服务端接收层改为保留十进制精度的类型/解析方式，兼容既有 JSON 数字；可兼容新增十进制字符串输入，但不得仅在已经 double 舍入后再转字符串声称端到端精确。显式 null/非法值返回参数错误，区别于缺省字段；前端同步避免 parseFloat 先损失用户输入精度。

新增语言无关 JSON fixture，Java/Python/支持的 Worker 共用：空白/CRLF、空输出、token 数差、零精度、恰等边界/略大、负数、科学计数、负零、混合文本、大整数、NaN/Inf、非法 precision/编码、Unicode 空白、超长数字/指数。数字以字符串保存；每条向量逐路径结果一致才可关闭 DEF-11/12。

## 6. 生命周期、终态一致性与配额

对应 DEF-13、DEF-14、DEF-15、DEF-21。

### 6.1 状态转换与恢复

保留现有 JudgeStatus，不增加新业务状态。正常阶段改为单调前进，不再先 RUNNING 后退到 PENDING。

| 状态 | 合法正常下一步 | 取消/过期/重启 |
| --- | --- | --- |
| CREATED | QUEUED | 取消为 CANCELLED；TTL 为 STALE；重启为 STALE，原因 RESTART_INTERRUPTED |
| QUEUED | PENDING | 可取消；重启清理/终结并释放占用 |
| PENDING | COMPILING / RUNNING | 可取消；重启回收句柄后 STALE |
| COMPILING | RUNNING / COMPILATION_ERROR | 可取消；编译超时保留原错误契约；重启回收后 STALE |
| RUNNING | 既有合法终态 | 可取消；任务总预算耗尽 BUDGET_EXCEEDED；重启回收后 STALE |
| 任意非终态 | 适用的 SYSTEM_ERROR / SECURITY_VIOLATION / SANDBOX_UNAVAILABLE 等终态 | 保留已得摘要、停止原因及清理记录 |
| 任意终态 | 不再改变状态/摘要 | 重复取消/终态事件幂等；保留期后清理 |

CREATED TTL 建议 15 分钟，以服务端创建时间计算；设置独立较短扫描周期（建议 1 分钟），不能沿用 30 分钟清理使短 TTL 失效。start、CREATED cancel、TTL 在同一任务互斥区竞争，只能一方转移成功。TTL/重启终结清理 pending 请求，不恢复或重跑用户代码；现有内存请求不具备重启恢复基础。

start 幂等是本次新增契约：已经排队/执行返回兼容 200，不重复入队，当前状态通过 status 获取；已终态建议 409，按需求 DEC-08 评审。无权/不存在仍统一 404。取消区分 accepted 与已完成：CREATED/QUEUED 首次 accepted=true 并可立即终结，执行中先接受令牌，资源清理并最终提交后才显示 CANCELLED；已终态重复取消建议 accepted=false 并返回原状态，不重新写摘要，按 DEC-08 冻结测试。

### 6.2 单一终态写入与崩溃一致性

建议 `TaskLifecycleService.finalizeTask` 为唯一终态入口。执行服务提供真实聚合，scheduler 只发取消/预算信号，不无条件写全零占位汇总；service 的取消缓存也不得另写无 summary 的终态。

提交顺序：

1. 在任务互斥区冻结聚合快照并仲裁首个合法终态，拒绝晚到 RUNNING/结果；核验计数/样本。
2. 优先真实聚合器或持久化有效摘要，只有确实无结果时用显式零统计兜底。不能保留 completedCases 却清空分类与样本。
3. 将 status、message、summary、attemptId、revision、finalizedAt 写入唯一临时文件，flush 后同卷原子替换为 final.json；上线验证目标文件系统支持，临时文件/目标防路径替换。
4. final 成功后更新 metadata/summary 兼容投影、缓存和通知。final 失败不能宣称持久化完成；投影失败可由 final 重建，不重复执行任务。
5. 排队占用在终结后释放；运行许可仅在实际执行域确认停止后释放。清理失败即使已有终态也保留独立待回收记录和隔离占用，阻止仍运行时发新许可。

终态查询以 final 为准，metadata 与 summary 的短暂分叉不暴露给 API。重启先读 final 修复投影，随后处理无 final 的非终态；历史有效 summary 保留。这里是单文件提交，不声称多个 JSON 文件具备数据库事务。首次合法终态获胜，重复只返回已提交快照。

增加可选 revision，HTTP/WS 同值，前端丢弃旧版本。非终态摘要可周期性做有界检查点，崩溃只承诺最后落盘统计，不虚构丢失用例；正常取消/超时必须提交已完成的全部统计。终态保留 total/completed、全部 verdict 计数、firstFailedCase、failureSamples、slowSamples 和 stoppedReason。

### 6.3 单实例原子配额与运行许可

- 同用户临界区覆盖检查、预留、CREATED 持久化、pending 注册/提交全过程；任何失败回滚本次预留与未完成创建记录，不留下占额任务。固定条带锁避免无界 userId-lock Map。
- 按稳定 owner userId 计数，缺省身份兼容需测试现有 sessionId 回退；不通过换会话突破账号额度。维持管理员无隐式创建配额豁免。
- CREATED/QUEUED 占 queued，PENDING/COMPILING/RUNNING 占 running。立即执行和出队共用准入，原子获取全局+用户许可；不能只在 create 时检查。
- 保留当前创建时 running 已满即拒绝的业务规则；允许“满运行但仍可提交排队”属于独立产品变更，不在修复中偷改。
- 队列满返回现有 429，任务仍是可重试 CREATED、受 TTL 约束。单任务只能入队一次，无幽灵许可；外部编译/HTTP 调用不持有用户锁。
- 固定锁顺序或单一调度临界区，避免全局/用户/任务锁反向嵌套。用户额度耗尽时可跳过该用户任务选择其他可运行任务，避免全队头阻塞。
- 每日额度按当前系统时区的创建日期，累计请求 case 数与估计 runtime，不是实际消耗结算；取消不退还每日额度，只释放在途容量。修改日期时区或退款规则需另评审。
- 每日计数也需并发原子并防止任务 TTL 清理提前重置当天已计费总量；可保留最小按用户/日累计账本并设明确保留期，或确保计费记录覆盖完整计费窗口。账本变更与创建必须以同一提交标识幂等，重启从持久记录重建，不重复计费。
- executor 拒绝、编译失败、正常完成、取消、超预算与异常均归还许可恰好一次。重启先回收残余执行并重建占用，再对外就绪；清理失败不得假装容量可用。

## 7. 前端、运行摘要与缓存

对应 DEF-16、DEF-17、DEF-22、N8；登录页面另见 §3.1。

### 7.1 可恢复提交/启动流程

保留创建、订阅、启动流程，抽取小型 HTTP helper 和页面状态函数。创建成功收到响应后保存 judgeId；已知 ID 的 start/查询网络结果不确定时先 status 对账。若创建响应丢失且没有 ID，则提示创建结果未知、禁止自动重发，可能落盘任务由 CREATED TTL 回收；本期不新增查找未知任务接口。页面状态为 idle、creating、created、starting、queued/running、terminal；失败回到可恢复状态，不长期假运行。

| start 结果 | 页面行为 |
| --- | --- |
| 2xx | 显示已接受/排队，执行状态仍以服务端为准 |
| 401 | 停止无效轮询/订阅，提示重新登录，保留非敏感输入 |
| 403/CSRF | 显示会话/授权失败，重新获取有效 token 后显式重试 |
| 404 | 显示任务不可用，结束旧订阅，允许新建 |
| 409（若采用） | 查询既有终态，禁止再次启动 |
| 429 | 明确尚未启动，保留原 judgeId，提供重试启动/取消 |
| 5xx/网络失败 | 分别提示服务/网络异常，先 status 对账再重试 |

现有 429 的 retryAfter 是提示文本，不当作数字倒计时；如需标准 Retry-After 响应头，应兼容新增并测试。后端无权任务继续 404，403 行专指认证/CSRF 等已公开安全失败，不泄露他人任务存在性。

WS 不可用可降级鉴权 HTTP 轮询，带间隔/退避/停止条件；同一任务最多一个轮询器和一个有效订阅，切换、重试、终态和退出均释放旧资源。按钮防连点不替代后端幂等，不能把“发出 start”当“已运行”。

### 7.2 Toast、摘要与节流生命周期

Toast 节点必须先于初始化，或统一 DOMContentLoaded 后初始化；缺组件时降级可见页内提示，不静默 return。维持状态文本转义。附录已确认不是整个页面 JS 崩溃，验收应断言提示可见而非只断言无控制台报错。

direct 运行进度使用已有 ResultAggregator.toSummary() 的复制快照，和 sandbox 保持结构一致；HTTP/WS 同 revision 统计一致，运行态不传全量 results，样本复用现有限额。计数不回退，分类之和等于 completedCases，仅统计实际完成用例；最后一次 RUNNING 摘要可合法 completedCases=totalCases，但不能在终态提交前伪造任务已完成。

ProgressPublisher 终态发送后删除活跃节流条目，物理清理兜底；发送失败使用有界 TTL/重试，不影响终态落盘，前端可查询恢复。统一生命周期拒绝终态后晚到非终态，避免重新插入 Map；需要终态去重时使用有限 TTL/容量或权威 revision，不永久保存所有历史任务。终态通知绕过普通节流。

## 8. 契约、配置与迁移

### 8.1 API 兼容清单

| 契约 | 保留项 | 行为变化 |
| --- | --- | --- |
| `/auth`、`/auth/verify` | 路径、AuthRequest/AuthResponse、rememberMe | 按模式展示、CSRF、安全 redirect、可见错误 |
| Cookie/Header | JUDGE_SESSION、X-Session-ID | query 不再认证；冲突凭证拒绝；安全 Cookie |
| `POST /judge` | Accept 控制 JSON/纯文本 judgeId 两种成功格式 | 原子配额、参数边界、CSRF |
| `/judge/start/{judgeId}` | 成功纯文本与队满 429；无权/不存在404 | 幂等启动，终态冲突需评审 |
| `/judge/cancel/{judgeId}` | CancelJudgeResponse 的既有字段 | CREATED 可取消、重复无副作用 |
| `/judge/status/{judgeId}`、WS | 既有状态/摘要字段 | 运行摘要、可选 revision、权威终态 |
| `/details/**`、`/download/**`、管理/清理 | 既有路径、任务权限 | 统一凭证、路径安全、状态变更 CSRF |
| runner 内部协议 | provider 抽象 | v2、路径/比较/事件字段及显式轮询状态，成套升级 |

复用 JUDGE_QUEUE_FULL、JUDGE_QUOTA_EXCEEDED 等既有错误码/字段。不为修复整体改 `/api/v2` 路由或全面替换错误模型；需要新增稳定错误码/traceId 时兼容扩展。当前未发现 OpenAPI 单一契约文件，实施时可从真实控制器补充，不能宣称已有。实现合并时追加 docs/change-log.md 和部署文档，本设计不是已上线日志。

### 8.2 待确认参数与策略

| 项目 | 建议 | 评审重点 |
| --- | --- | --- |
| 默认输出上限 | 保持现配置 10485760 字节（10MiB） | 不因旧测试自动回退 1MiB；样例随配置 |
| CREATED TTL/扫描 | 15分钟/1分钟 | 刷新重连容忍时间、清理上界 |
| 普通比较 | 精确整数、十进制绝对容差、非有限当文本 | 冻结跨语言 fixture/数字资源上限 |
| 重启策略 | 全非终态回收后 STALE，RESTART_INTERRUPTED | 不自动重跑；失败清理时不接单 |
| 会话环境绑定 | 可配置策略，管理员更严格 | 移动网络/可信代理误拒绝风险 |
| mTLS | 首期拒绝未支持模式 | 如必须启用，增真实 TLS 工作包 |
| start/cancel 幂等响应 | start运行中200、终态建议409；终态重复cancel建议accepted=false | 按需求DEC-08批准并冻结兼容测试 |
| 有界资源参数 | §3/4 的缓存、事件、连接、时间限额 | 目标容量定版，工程初值不是承诺 |

新增属性名称由相关 Properties 类统一定义，文档列单位、默认值、profile、影响。零/负值/空白秘密/矛盾配置不得被解释为无限或放行。

### 8.3 数据与部署切换

1. 维护窗口停止新任务、排空或受控终结活跃任务；备份数据与非敏感配置，记录 host/镜像/Worker 版本。
2. 旧终态只读兼容：有 final 读 final，否则读旧 summary/metadata，不覆盖已有统计。孤儿非终态按 §6 恢复，残余执行优先回收。
3. 新任务使用新布局/协议；旧 events.jsonl 混合宿主与 runner 行，不自动重放为可信流。历史来源不可信结果不能通过迁移变成已证明正确。
4. 经运维批准移除默认/入库凭证，失效日志暴露会话，更新 Header 客户端、CSRF、代理/TLS 配置。
5. 先维护访问完成真实闭环与负向安全验收，再恢复流量；无混跑兼容保证时不滚动替换。

回滚要成套匹配 host/镜像/Worker/数据格式。旧版本不理解 final/v2 时，不直接覆盖二进制读写新任务；保留新数据只读，在隔离环境用维护备份核对。不得为回滚重新开放弱口令、匿名 WS、伪 mTLS、共享控制目录；无法安全回滚就保持关闭。

## 9. 验证、可观测性与关闭证据

### 9.1 自动化测试分层

以下均为实施后的计划，不是本次已运行结果。测试路径均位于 `src/test/java/com/example/demo/`。

| 范围 | 优先扩展现有测试 | 增量验收 |
| --- | --- | --- |
| 认证/配置 | ProductionAuthenticationTest、SecurityModeStartupValidatorTest、ProductionSecurityStartupValidatorTest | 模式、Cookie、redirect、query禁用、默认凭证、CSRF |
| 来源/限流 | 新增共享解析与限流单测，复用认证测试设施 | 直连伪头、可信多跳、IPv4/IPv6、并发计数、主动过期、满额不驱逐锁定 |
| WS | 新增真实端口 STOMP 集成测试 | 原生与启用的SockJS transports、匿名握手/越权订阅/SEND、既有订阅登出/到期 |
| runner | LinuxContainerRunnerTest、WindowsHyperVContainerRunnerTest、RemoteWorkerRunnerTest、SandboxRunnerContractTest、JudgeSandboxOrchestrationTest | 空批、退出drain、半行/截断、deadline、版本不兼容 |
| artifact/比较/资源 | TaskRunnerArtifactContractTest、JudgeBaselineTest、ProcessRunnerTest | 同一比较fixture、控制文件不可写、输出/进程树有界 |
| 生命周期/配额 | JudgeSchedulerTest、FileTaskStoreTest、TaskCleanupServiceTest、QuotaAndAuthorizationTest | 全非终态重启、并发创建/启动、落盘失败、许可恰好释放 |
| 聚合/进度 | ResultAggregatorTest、SandboxEventIngestorTest、ProgressPublisherTest | 重复/晚到事件、真实摘要不覆盖、缓存容量与运行摘要 |
| 页面 | FrontendTemplateContractTest + 新增真实浏览器流程 | 双模式登录、可见toast、401/404/429/断网、原任务重试、无重复订阅 |
| N5 | ApplicationYamlExecutionProfileTest、JudgeBaselineTest、TaskRunnerArtifactContractTest | 输出配置预期一致、超限样例动态派生、Python解释器探测 |

N5 不能仅改断言变绿：默认额度确定后生成“实际上限+小增量”样例，断言 OUTPUT_LIMIT_EXCEEDED，并补恰等上限通过。Python 先使用显式配置，再探测 python3/python 和所需版本；本地缺依赖可明确记录不具备验证条件，但声称支持 Python runner 的 CI/发布 job 必须安装并运行，不以跳过替代通过。

建议实施后先按工作包运行 `./mvnw.cmd -Dtest=... test`（Windows）或 `./mvnw -Dtest=... test`（Linux），再全量 `test`；真实 provider、TLS、浏览器单独运行。发布报告必须区分断言失败、环境错误、跳过与通过，不沿用旧测试数字。

### 9.2 故障注入与量化断言

- 同用户同时竞争最后一个创建额度：成功数不超限，失败无占用泄漏；全局并发高于用户上限时，实际执行数仍受用户许可约束。
- CREATED/QUEUED/PENDING/COMPILING/RUNNING 分别重启：状态合法、残余执行回收、额度正确；未完成恢复不接流量。
- 在冻结快照前、final 原子替换前后、兼容投影前、释放许可前分别模拟崩溃，不发生统计清零、重复执行或重复释放。
- 至少产生 AC 与 WA/失败样本后取消和超预算：比较实时 WS、HTTP、summary/final、清缓存、重启的全部计数、样本、停止原因。
- 事件量/登录 key/历史任务数增长时，单轮读取量、单行大小、缓存条数、活跃句柄有明确可断言上限；记录监督器/JVM RSS、磁盘峰值和轮询 CPU。
- 攻击负向测试使用隔离环境与外层 watchdog，设 CPU/内存/磁盘限制并验证残留为零；不得向生产发送资源耗尽样例。

### 9.3 指标与关闭记录

建议低基数指标：拒绝原因、活跃 WS/订阅、限流缓存容量、空轮询/延迟、事件字节/协议错误、输出截断、孤儿清理失败、状态任务数、许可总占用、终态写失败、进度缓存条数。日志关联 judgeId/attemptId/traceId，不记录凭证、全量程序输出或秘密配置。

每个缺陷记录需求ID、修复提交、profile/provider、测试名称/结果、镜像/Worker版本、脱敏真实环境证据、未覆盖范围、复核人/日期。Mock 容器命令不能替代真实权限验证，模板字符串不能替代浏览器，客户端 HTTPS 测试不能替代 Worker 服务端认证证据。

## 10. 实施拆分与发布门槛

| 工作包 | 内容 | 前置/退出条件 |
| --- | --- | --- |
| W0 | 冻结产品决策、修 N5 基线与依赖探测、共享比较向量 | 区分历史失败/环境错误，输出与比较规则明确 |
| W1 | HTTP/WS认证、Cookie/CSRF/代理/限流/日志/跳转、安全默认、mTLS收敛 | 正反向安全测试通过，DEF-04/N1独立留证 |
| W2 | 控制/执行权限、v2路径与事件、增量轮询、输出和进程树 | DEF-02/03/05/06/07/08/N2成套真实链路验收 |
| W3 | 生命周期原子提交、CREATED TTL、全非终态恢复、配额许可 | 基于可信事件，崩溃/并发验收通过 |
| W4 | 普通比较跨路径统一、前端错误恢复/toast/摘要/缓存 | 可并行开发，最终依赖v2与权威终态 |
| W5 | Linux/Windows/Worker/浏览器矩阵、容量与运维演练 | 所有拟启用provider通过，不具备证据的明确禁用 |

W0/W1 可先并行，W2/W3 先冻结协议与终态责任。需求文档阶段 A/B/C/D 是业务验收分组，本表 W0～W5 是实施任务分组，不是两套互相替代的发布顺序。

公网不可信执行发布必须同时满足：全部适用 P1、附录明确阻断的 P2、默认凭证与 CSRF 等安全工作包通过；N5 基线绿，必需套件不得靠跳过；所有启用 provider 有目标环境证据。功能可用不意味着能先开放公网。N6/N8 如延期，应单独记录批准的范围/风险，不能标记关闭。

本方案以统一身份解析和终态职责体现 DRY/SRP，复用存储/调度/provider 保持 KISS，以单实例和拒绝未支持模式贯彻 YAGNI；新增抽象只服务实际重复与安全边界，不做无关重构。
