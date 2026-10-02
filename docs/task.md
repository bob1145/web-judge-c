# 缺陷修复开发任务清单

## 1. 使用范围与事实声明

- 制定日期：2026-10-02；当前执行状态见任务卡及work；TASK-00已取得批准并建立真实红基线，不是缺陷修复完成报告。
- 项目根目录为 `web-judge-c/`；任务文件 `docs/task.md`，逐次工作记录 `docs/work.md`。
- **R** 代表 `docs/defect-remediation-requirements-2026-10-02.md`；**D** 代表 `docs/defect-remediation-design-2026-10-02.md`。任务按 RQ/AC 和 D 小节精确关联。
- 缺陷输入为 `docs/defect-review-2026-10-02.md` 及动态附录；冲突以附录为准。审查基线 `f31d039`，实际开发必须另记真实 HEAD。
- 共 30 个任务（TASK-00～29），覆盖 31 个缺陷编号；开发任务完成不自动等于缺陷关闭，真实关闭证据由 TASK-24～29 汇总。
- 初始规划只制定任务与日志，全部任务初始NOT_STARTED；后续实施、实际测试及提交以work和Git联合核验。此前文档交付不算TASK-00完成，所有阶段不推送。
- 保留单实例文件存储和现有框架/API；不做数据库/分布式改造，不重写前端，不自动重跑历史代码。未验收 provider 不生产启用，不回退 direct。

## 2. 每个任务必须执行的闭环

### 2.1 状态、依赖与角色

状态为 `NOT_STARTED -> IN_PROGRESS -> VERIFIED_PENDING_COMMIT -> DONE`；缺决策/环境/依赖为 BLOCKED，解决后回到 IN_PROGRESS。代码写完不等于验收通过，提交失败不得 DONE。

每次只推进一个主任务到提交。可并行只读探索或不冲突实现，但明确文件所有权、不得回滚他人改动；共享 JudgeService、安全配置、DTO、runner、模板及日志时串行集成。仅主负责人整理 task/work 和提交，子代理不擅自提交。

每项可设置实施人和独立复核人；自审必须明示，不能伪造双人审核。最终安全放行需要独立复核。卡片提示词自动包含本节及 §3，不能因为卡片简写跳过公共要求。

### 2.2 逐任务九步流程

1. **读取与核查**：阅读任务卡、R/D、work、适用 AGENTS，检查 HEAD/工作区、前置真实提交和批准 DEC。不能只信表格勾选。
2. **开工汇报**：报告任务、角色、目标、文件、风险、测试，日志记录 IN_PROGRESS；新增需求先记录偏差，不默默扩大范围。
3. **先测后修**：最小回归证明原问题，保存预期失败；文档/审计任务用静态检查代替并说明。不得为复现向生产运行攻击。
4. **实现**：最小改动、复用现有契约，不以建议值替代批准值，不用 stub 冒充真实安全能力。拟新增测试先实际创建再写执行命令。
5. **严格测试**：执行本卡与 §3 的正负例、边界、异常、并发和回归，保留命令、环境、退出码、通过/失败/跳过数、脱敏证据。
6. **严格验收**：逐 AC 对照代码/契约/证据，检查安全、迁移和回滚；没做的明确未做，不能写应该通过。通过后才 VERIFIED_PENDING_COMMIT。
7. **先更新实际记录**：work 追加实做/未做、文件、测试、偏差、审核和提交定位键，task 更新候选状态；实现、测试、必要文档和 task/work 同次提交。
8. **仅本地提交**：依 §2.3 确认、显式暂存、审核、commit、核验真实 SHA。不得 push、自动建分支、amend、reset 或绕过钩子。
9. **即时完成汇报**：按 §2.5 汇报本项，再开始下一项；无需为普通汇报等待反馈，但下一项需审批/产品确认时必须停止等待。

### 2.3 Git 提交与危险操作确认

逐任务本地提交是用户明确要求的交付规则，实际操作仍遵守 AGENTS 的危险确认机制。开始开发时可请求一次明确覆盖 TASK-00～29 的本地提交授权，说明文件范围、不含 push 或其他高风险动作；未取得范围授权则每次 commit 前确认。不能把本计划当作已取得操作确认。

```text
⚠️ 危险操作检测！
操作类型：本地 git commit（不推送）
影响范围：TASK-XX 已验收列定文件、对应测试、docs/task.md、docs/work.md
风险评估：形成永久本地历史，需要排除凭证、临时产物及他人改动
请确认是否继续？[需要明确的“是”“确认”“继续”]
```

示例命令须把占位路径替换为审查过的真实文件：

```powershell
git status --short
git diff --check
git diff -- "src/本任务文件" "docs/task.md" "docs/work.md"
git add -- "src/本任务文件" "src/test/本任务测试" "docs/task.md" "docs/work.md"
git diff --cached --check
git diff --cached --name-only
git diff --cached
git commit -m "fix(scope): TASK-XX 实际变更 [TASK-XX-C01]"
git rev-parse HEAD
git show --stat --oneline HEAD
git status --short
```

- 每任务至少一个独立成功提交，默认一个，不攒到最后。补修新增 C02，不改写旧提交；主题按实际选择 fix/test/docs，含唯一定位键。
- 不使用 git add . / git add -A；发现他人暂存先沟通或阻断，不擅自取消暂存/夹带提交。不执行 push、不添加自动推送步骤。
- 当前四份 review/需求/设计输入未跟踪，TASK-00 先确认归属和批准范围再显式归档；不得一并提交 `.mimosa/`、`.playwright-mcp/` 等产物。
- commit/钩子失败或授权拒绝：保留失败记录，回到 VERIFIED_PENDING_COMMIT 或 IN_PROGRESS，不报完成、不跳钩子；修改后重跑受影响测试。

### 2.4 日志与自身 SHA 的处理

work 每次状态变化和完成都更新，以追加为主，保留失败历史。至少记录任务/RQ/AC/D、实际文件与行为、未做范围、命令/环境/退出码/统计、逐项验收、偏差风险、审核、父 SHA、提交定位键、用户汇报摘要。

同一次提交不能把自己的最终 SHA 写入自身文件。日志先保存唯一 `TASK-XX-C01` 与父 SHA，成功后用 `git log -1 --format=%H --fixed-strings --grep="[TASK-XX-C01]"` 定位，并去掉-1核验匹配恰好一个，在用户汇报提供真实 SHA；下一任务可以补记前次 SHA。执行尝试编号RNN与提交编号CNN独立，不能混用。不得伪造 hash、无限 amend 或为了填自己的 hash 留下未提交日志。

提交内 DONE/勾选是候选结果，仅在匹配真实提交存在、证据齐全时生效。提交失败立即把工作区状态改回未完成并记录原因。完成后核查 task/work 包含于该提交，工作区只允许已声明的任务外既有改动。

### 2.5 完成汇报格式

```text
TASK-XX：DONE / BLOCKED / VERIFIED_PENDING_COMMIT
对应：RQ-… / AC-… / D §…
实际完成：具体行为与关键文件（不能复制计划冒充实做）
严格测试：命令、通过/失败/跳过数、关键负例、证据位置
严格验收：已通过项、未做及原因、缺陷是否可关闭
Git：真实 SHA、主题，仅本地，未推送
日志：docs/work.md 对应定位键
后续：下一任务、依赖与待确认项
```

## 3. 严格测试与公共完成门槛

### 3.1 可复现执行约定

命令在项目根执行；Java 测试类位于 `src/test/java/com/example/demo/`。卡片的单测集替换为真实类名，逗号分隔：

```powershell
.\mvnw.cmd "-Dtest=单测集" test
if ($LASTEXITCODE -ne 0) { throw "定向测试失败" }
.\mvnw.cmd test
if ($LASTEXITCODE -ne 0) { throw "全量回归失败" }
git diff --check
```

Linux 为 `./mvnw '-Dtest=单测集' test`、`./mvnw test`，每步非零失败。记录 Surefire XML 的总数/失败/错误/跳过，不能只截 BUILD SUCCESS，不使用 skipTests 或禁用失败测试。

TASK-00 可准确记录历史红基线，不得声称通过；TASK-01 必须建立绿基线。之后每个代码任务执行定向测试及全量 Maven；纯文档任务至少静态引用/结构/契约检查，涉及配置/生成逻辑仍跑相关测试。TASK-29 重新全量执行。

Python/浏览器/容器脚本由相应任务实际创建并记录版本与命令，不虚构现有 npm/脚本入口。依赖安装尤其全局或核心依赖变更遵守审批。无环境则 BLOCKED，不能跳过关键测试声称通过。

### 3.2 每项 Definition of Done

- [ ] RQ/AC/D 逐项有实现与证据，适用性和产品语义已批准。
- [ ] 原缺陷回归、正常/拒绝、limit-1/limit/limit+1、错误/重试/取消等适用分支通过。
- [ ] 本项必需套件通过，零未解释失败/错误，关键安全用例不 skip；环境缺失不视作通过。
- [ ] 兼容、跨平台、持久化、并发及资源不变量按任务范围验证。
- [ ] 无秘密入库/日志泄漏/无限重试/无限缓存；真实环境实验有外层资源限制。
- [ ] 复核完成，无遗留阻断；开发验证与后续真实缺陷关闭范围明确分开。
- [ ] task/work 同步，成功独立本地 commit、真实 SHA 核验、逐任务汇报；无 push。

Mock 只证明单元契约，不能证明真实隔离/TLS/浏览器。卡片要求的真实实验必须在本项执行，其他平台闭环由 TASK-24～27 完成。中间提交一律不是公开发布许可。

### 3.3 决策、参数与证据

TASK-00 为 DEC-01～08 记录批准人/日期/结果；延期只阻断依赖该决策的任务，不可代理代批。TTL、清理预算、数字长度、事件容量、内存增长容差、WS撤销时限等阈值须测试前冻结，禁止测完再放宽。

证据建议放 `docs/acceptance/TASK-XX/`，由实施任务创建；包含脱敏命令、关键日志、有限 fixture、环境/镜像/Worker版本、profile、日期和代码基线。大产物记录位置及校验值，不提交秘密/编译目录/无限输出。

缺环境的平台仅在 DEC-07 明确批准禁用、配置/探针负例证明不可启用时，才能以范围收敛验收完成矩阵任务；相应 provider 仍为未验收，不能标安全通过。

## 4. 任务索引与依赖

前置均指已验收且有本地提交；范围表达式含起止任务。安全/runner 同组可独立提交，未完成整条链路前不得发布。

| 状态 | 任务 | 主题 | 前置任务 |
| --- | --- | --- | --- |
| [x] | TASK-00 | 决策与执行基线 | 无；[TASK-00-C01]核验后生效 |
| [x] | TASK-01 | 回归基线与解释器 | 00；[TASK-01-C01]核验后生效 |
| [ ] | TASK-02 | 凭证解析、安全默认、日志 | 00、01 |
| [ ] | TASK-03 | 可信代理、有界限流 | 02 |
| [ ] | TASK-04 | Cookie、重定向、CSRF | 02、03 |
| [ ] | TASK-05 | 双模式登录 | 04 |
| [ ] | TASK-06 | WebSocket 全链路授权 | 03、04 |
| [ ] | TASK-07 | 会话环境绑定 | 03、06 |
| [ ] | TASK-08 | Worker HTTPS 真实认证 | 01、02 |
| [ ] | TASK-09 | Runner v2 与跨平台路径 | 01 |
| [ ] | TASK-10 | 控制面身份隔离 | 09 |
| [ ] | TASK-11 | 增量事件读取与验真 | 09、10 |
| [ ] | TASK-12 | 轮询预算与退出收尾 | 11 |
| [ ] | TASK-13 | 在线输出限额 | 10、12 |
| [ ] | TASK-14 | 全进程域回收 | 12、13 |
| [ ] | TASK-15 | 比较向量与 Java 精度 | 01、09 |
| [ ] | TASK-16 | 跨 provider 比较贯通 | 08、12、14、15 |
| [ ] | TASK-17 | 权威终态提交 | 11、12 |
| [ ] | TASK-18 | CREATED 与幂等启动取消 | 14、17 |
| [ ] | TASK-19 | 全非终态重启恢复 | 14、17、18 |
| [ ] | TASK-20 | 原子创建配额 | 18、19 |
| [ ] | TASK-21 | 用户运行许可 | 20 |
| [ ] | TASK-22 | 前端错误恢复、Toast | 05、06、18 |
| [ ] | TASK-23 | 运行摘要、缓存生命周期 | 17、21、22 |
| [ ] | TASK-24 | 真实 HTTP/WS/浏览器验收 | 02～08、18～23 |
| [ ] | TASK-25 | 真实 Linux 隔离验收 | 09～21、23 |
| [ ] | TASK-26 | Windows/Worker 能力矩阵 | 08～21、23 |
| [ ] | TASK-27 | 故障注入、资源上界 | 24、25、26 |
| [ ] | TASK-28 | 部署迁移、回滚演练 | 27 |
| [ ] | TASK-29 | 最终严格验收 | 00～28 |

## 5. 详细任务卡

以下提交均是未来操作建议，每项必须附带 §2 日志更新、审批、本地提交和即时汇报。路径默认相对项目根；省略前缀的 Java 类须在实际仓库定位。

### TASK-00：冻结决策与执行基线

- **状态/角色**：DONE；技术负责人 + 用户决策批准。真实提交4c86d830edc9a927e32377291bac20641ac1e557，批准/延期及红基线见docs/acceptance/TASK-00/approval-and-baseline.md；不关闭缺陷。
- **需求/设计**：R §1/2/4/7、DEC-01～08；D §1/2/8/9/10；支撑全部 RQ。
- **依赖**：无；本卡不等于当前编写计划。
- **设计目标/预期效果**：建议值、既有事实和批准策略分开，可重放开发基线及发布范围。
- **实施/文件**：记录实际 HEAD/工作区、Java/Maven/Python/编译器/容器/浏览器；取得八项 DEC、定量阈值、平台范围与提交授权的明确记录；确认四份输入文档归属后显式归档，更新 task/work。
- **严格测试**：在可用环境运行基线并区分历史失败与环境缺失；静态检查31缺陷、RQ/AC、30任务、设计章节、依赖无环、现有测试入口。不得用附录数字冒充新运行。
- **严格验收**：每个决策有批准人/日期或延期及受阻任务；既有无关改动有清单；版本、缺失环境、测试失败分类可复查，不宣称修复完成。
- **提示词**：你是技术负责人，仅执行 TASK-00。先核对事实，再由人类批准产品/部署决策，不自行采用建议值、不改业务代码。按 §2 记录真实基线、提交和汇报。
- **提交**：`docs(plan): TASK-00 freeze execution baseline [TASK-00-C01]`。

### TASK-01：恢复回归基线与解释器发现

- **状态/角色**：DONE（提交候选，[TASK-01-C01]核验后生效）；测试基础设施工程师。动态边界及解释器发现已验证；定向28/28、全量205通过/2已解释provider跳过、显式路径19/19、无效显式路径按预期失败。详见work的TASK-01 / R01～R04；不关闭N5或宣称生产平台通过。
- **需求/设计**：RQ-V01/AC-V01/T-V01、N5、DEC-01/07；D §8.2、§9.1。
- **依赖**：00；输出额度已批准。
- **设计目标/预期效果**：配置与断言一致、Python跨平台可调用，建立每次提交可复验的绿基线。
- **实施/文件**：`ApplicationYamlExecutionProfileTest`、`JudgeBaselineTest`、`TaskRunnerArtifactContractTest`；夹具按生效额度生成，解释器显式路径优先再探测 python3/python 与版本，CI缺失明确失败。
- **严格测试**：以上三类定向+全量 Maven；额度下/等/上、显式解释器/仅python3/仅python/缺失矩阵；保留真实超限触发。
- **严格验收**：历史配置断言与解释器根因消除，不禁用测试、不把OLE期望改AC；关键用例实际执行，报告完整失败/错误/skip统计。
- **提示词**：你是测试工程师，执行 TASK-01。不能靠跳过、缩小超限输入、提高额度让构建变绿；保留批准语义并提交失败前后证据，按 §2 更新实做日志、提交、汇报。
- **提交**：`test(baseline): TASK-01 restore reproducible regression [TASK-01-C01]`。

### TASK-02：统一凭证、安全默认与脱敏日志

- **状态/角色**：DONE（提交候选，[TASK-02-C01]核验后生效）；后端安全工程师。最终定向83/83、全量262项/260通过/0失败/0错误/2原provider跳过；证据docs/acceptance/TASK-02/R08、R09。HTTP凭证、安全默认与日志开发验收通过；DEF-18/N4待24/28/29关闭审计，N3仍缺06/24的WS全链路。
- **需求/设计**：RQ-A04/AC-A04a、AC-A04c/T-A06、T-A08；RQ-A05/AC-A05b/T-A10；DEF-18、N3、N4；D §3.1、§3.5、§8.1/8.3。
- **依赖**：00、01；DEC-06批准。
- **设计目标/预期效果**：HTTP所有入口共用真实身份，不接受URL凭证，无默认可用口令和原始token日志。
- **实施/文件**：新增小型凭证解析器；替换 AuthenticationInterceptor、AuthenticationController、JudgeController、AdminController 的提取；覆盖 AccessCodeService/SessionManagementService 日志；修改 application.yml 与 AuthConfiguration fallback，默认回环、材料缺失失败关闭，账号模式不被无用访问码阻塞。
- **严格测试**：ProductionAuthenticationTest、SecurityModeStartupValidatorTest、ProductionSecurityStartupValidatorTest；新增 SessionCredentialResolverTest/凭证日志测试。Cookie/Header正例、同值/冲突、query-only、普通/管理/下载、各日志级别、旧默认码、缺材料与两模式启动；全量回归。
- **严格验收**：冲突凭证拒绝，query-only不认证，敏感token不出现在日志/错误；配置与代码fallback一起修。WS全链路证据留06/24，不能提前关闭全入口N3。
- **提示词**：你是安全工程师，只做 TASK-02；统一提取，不新建第二套认证，不仅改YAML，不破坏原响应字段。实际扫描日志与测试token泄漏，按 §2交付记录/提交/汇报。
- **提交**：`fix(auth): TASK-02 unify credentials and safe defaults [TASK-02-C01]`。

### TASK-03：可信代理与有界认证限流

- **状态/角色**：BLOCKED；安全/并发工程师。TASK-02已提交；按TASK-00批准的延期门槛，可信代理CIDR、来源/账户阈值、状态容量/TTL及代理头上限仍待批准，不擅自补业务默认。可继续不依赖这些参数的TASK-08。
- **需求/设计**：RQ-A03/AC-A03a、AC-A03b/T-A04、T-A05；DEF-09、DEF-10；D §3.4、§9.1/9.3。
- **依赖**：02；可信代理与容量阈值批准。
- **设计目标/预期效果**：伪XFF不改变来源，换IP/账户不能绕过独立限制，认证状态规模有界。
- **实施/文件**：新增共享ClientAddressResolver及有界限流组件；UserAccountService/AccessCodeService计数与时间原子更新；默认socket来源、可信CIDR右向左解析、限制头长度；主动TTL，满容量保守拒绝新key，不能驱逐未到期封禁。
- **严格测试**：ProductionAuthenticationTest与现有限流测试；新增来源/限流测试。IPv4/IPv6、多跳/多头/非法链、直连伪造、同账号换IP/同IP换账号、并发阈值、高基数容量、无需再次访问的TTL；可控时钟+全量。
- **严格验收**：来源单一真相，框架不二次解析；容量满仍保留有效封禁；指标无随机key无限基数，正例正常登录。
- **提示词**：你是并发安全工程师，执行 TASK-03。不能用无界新Map替代旧Map，不能用LRU清除有效锁定；用时钟和并发屏障证明边界，按 §2闭环。
- **提交**：`fix(auth): TASK-03 bound rate limits and trust proxy sources [TASK-03-C01]`。

### TASK-04：Cookie、重定向与CSRF

- **状态/角色**：NOT_STARTED；应用安全工程师。
- **需求/设计**：RQ-A04/AC-A04b/T-A07、RQ-A05/AC-A05a/T-A09、RQ-A06/AC-A06b/T-A12；DEF-19、DEF-20、N7；D §3.2、§8.1。
- **依赖**：02、03；DEC-06批准。
- **设计目标/预期效果**：会话安全传输、登录只回本站、Cookie状态变更防伪，合法页面仍可用。
- **实施/文件**：AuthenticationController统一Cookie/redirect；SecurityConfig启用会话绑定CSRF、登录前token与成功轮换；auth/index模板所有状态变更fetch接入；生产Secure+HttpOnly+SameSite，保留rememberMe；Header豁免仅限真实无Cookie认证，不可任意Header绕过。
- **严格测试**：认证/模板既有测试；新增Cookie/redirect/CSRF集成。三登录分支相同攻击向量、编码绕过、Cookie创建删除属性、旧token/错误token/跨站POST、无效Header回退、CORS；真实本地HTTPS客户端及浏览器传输冒烟，全量回归。
- **严格验收**：覆盖登录/登出/创建/启动/取消/清理/管理；不保留全局csrf.disable，SockJS只有窄例外；伪协议头不能关Secure，重定向不出站。
- **提示词**：你是应用安全工程师，执行 TASK-04。SameSite不是CSRF替代，先补齐页面token再启用拒绝；不放宽生产TLS去迁就测试。记录真实HTTPS证据及 §2闭环。
- **提交**：`fix(security): TASK-04 secure cookies redirects and csrf [TASK-04-C01]`。

### TASK-05：双认证模式登录页面

- **状态/角色**：NOT_STARTED；前端工程师。
- **需求/设计**：RQ-A01/AC-A01/T-A01；DEF-01；D §3.1、§3.2、§8.1。
- **依赖**：04。
- **设计目标/预期效果**：账号/访问码模式均能真实登录、可见提示、站内跳转，页面不自行决定认证模式。
- **实施/文件**：复用requiresAccountAuthentication()渲染auth.html，分别提交username/password或accessCode；保留rememberMe、接口与字段；同时判断HTTP及success，分离401/429/5xx/断网及历史200+success=false。
- **严格测试**：ProductionAuthenticationTest、FrontendTemplateContractTest；新增并实际执行真实浏览器两模式成功/失败/登出/受保护页/重定向测试，伪造模式字段不得降级认证；全量回归。
- **严格验收**：每个错误实际可见、失败不跳转，CSRF正常；模板字符串通过不代替浏览器证据。
- **提示词**：你是前端工程师，仅执行 TASK-05，使用后端模式与原接口，不在控制台打印凭证。两模式真实浏览器闭环留证，按 §2更新work并提交汇报。
- **提交**：`fix(ui): TASK-05 support both authentication modes [TASK-05-C01]`。

### TASK-06：WebSocket握手、STOMP授权与撤销

- **状态/角色**：NOT_STARTED；实时通信安全工程师。
- **需求/设计**：RQ-A02/AC-A02a、AC-A02b/T-A02、T-A03；RQ-A04/AC-A04c；DEF-04、N1、N3；D §3.1～3.3。
- **依赖**：03、04。
- **设计目标/预期效果**：匿名不可连、越权不可订、客户端不可造进度，已失效连接不能继续接收。
- **实施/文件**：WebSocketConfig握手与启用SockJS transports复用身份/来源；CONNECT绑定Principal并验token；SUBSCRIBE精确目的地且复用canAccessTask；拒绝客户端SEND；有界连接/订阅索引，登出/到期/禁用主动关连接，前端CONNECT补token。
- **严格测试**：新增真实端口STOMP授权/撤销集成，复用QuotaAndAuthorizationTest。匿名/过期/query-only/Origin、A/B/admin/通配、已认证与匿名SEND、已订阅后登出与自然到期、反复断连/越限；全量回归。
- **严格验收**：N1握手/SEND与DEF-04任务授权各独立证据；无后续客户端帧也在批准时限内断连，连接索引回落；原生WS与每个启用回退传输都验收。
- **提示词**：你是实时安全工程师，执行 TASK-06。Origin、UUID、MVC拦截器不替代认证，仅CONNECT检查不足；必须真实协议验证，按 §2提交和汇报。
- **提交**：`fix(ws): TASK-06 enforce identity ownership and revocation [TASK-06-C01]`。

### TASK-07：会话环境绑定策略

- **状态/角色**：NOT_STARTED；后端认证工程师。
- **需求/设计**：RQ-A06/AC-A06a/T-A11；N6、DEC-04；D §3.5。
- **依赖**：03、06；管理员/普通用户策略已批准。
- **设计目标/预期效果**：严格模式真正拒绝环境变化，告警模式不被误称阻断，移动网络行为可预期。
- **实施/文件**：SessionManagementService与调用层共用OFF/AUDIT/STRICT及管理员覆写；可信来源/UA规范化，STRICT失效HTTP会话并联动WS关闭，AUDIT脱敏留证。
- **严格测试**：新增环境策略单测/集成；用户类型×同来源/IP变/UA变/代理切换×策略矩阵，STRICT后HTTP/WS均失效，AUDIT仍放行但留审计；全量。
- **严格验收**：内层真实返回拒绝，不只是外层改条件；批准矩阵全部通过，无未批准的默认强绑定。
- **提示词**：你是认证工程师，执行 TASK-07。策略未批准先BLOCKED，不以安全名义自造业务规则，不宣称IP/UA解决凭证盗用；按 §2完成闭环。
- **提交**：`fix(auth): TASK-07 apply approved session environment policy [TASK-07-C01]`。

### TASK-08：Worker HTTPS与伪mTLS拒绝

- **状态/角色**：DONE（提交候选，[TASK-08-C01]核验后生效）；后端集成/TLS工程师。最终定向49/49；全量复验297项/295通过/0失败/0错误/2原provider跳过。R06曾出现未定位的RE→SYSTEM_ERROR判题失败，原码单项及全量复验通过但不视作已修复，风险留N5/最终回归。18项真实本地TLS场景通过；外部Worker验签、重放保护及部署端点仍留26。
- **需求/设计**：RQ-A07/AC-A07/T-A13；DEF-23、DEC-05；D §3.6、§8.1/8.3。
- **依赖**：01、02。
- **设计目标/预期效果**：认证能力来自真实协议而非非空证书字符串，生产Worker连接验证服务端身份。
- **实施/文件**：WorkerProperties、SandboxProperties嵌套配置、RemoteWorkerRunner.Options统一解析，冲突失败；移除伪mTLS头含义并拒绝未支持cert-only；生产HTTPS、正常证书链/hostname、原Bearer/HMAC签名兼容、禁止携凭证跨端点重定向。
- **严格测试**：RemoteWorkerRunnerTest、ProductionSecurityStartupValidatorTest；新增真实TLS集成，覆盖HTTP/cert-only/错误认证/过期或不可信证书/错hostname/配置冲突/重定向不泄密，支持认证正例；全量。
- **严格验收**：无trust-all、不关hostname、不回退匿名/direct；客户端TLS证据完整，实际外部Worker验签由26补齐，不预先宣称远端已认证安全。
- **提示词**：你是TLS集成工程师，执行 TASK-08。首期明确拒绝未实现mTLS，若人类要求真mTLS先批准扩展任务/设计；不要偷偷扩大范围或放松生产配置，按 §2留证提交。
- **提交**：`fix(worker): TASK-08 reject nominal auth and enforce tls [TASK-08-C01]`。

### TASK-09：Runner v2执行清单与跨平台路径

- **状态/角色**：NOT_STARTED；契约/跨平台后端工程师。
- **需求/设计**：RQ-R01/AC-R01/T-R01、RQ-R03、RQ-C01参数贯通；DEF-02；D §2、§4.1/4.2、§5.2、§8.1/8.3。
- **依赖**：01；DEC-03/07的字段格式、目标平台已批准，比较算法留15/16。
- **设计目标/预期效果**：宿主domain spec与执行清单分离，宿主根不同于执行根时源码仍可达，未知协议失败关闭。
- **实施/文件**：SandboxTaskSpec/Capabilities/RunHandle、三个provider adapter和task_runner.py；v2显式版本/judgeId/attemptId、相对源码路径、precision字符串/模式/单位/限额；路径全部验证后才建文件；新attempt独立流，不截断旧流。
- **严格测试**：SandboxRunnerContractTest、LinuxContainerRunnerTest、WindowsHyperVContainerRunnerTest、RemoteWorkerRunnerTest、TaskRunnerArtifactContractTest；绝对/父级/UNC/驱动器/链接逃逸/非本任务路径、缺字段/未知版本/不支持能力；授权隔离环境验证不同根映射，不覆盖根外文件；全量。
- **严格验收**：不使用宿主Path解释异OS路径，危险路径失败前后根外快照不变；旧provider不静默丢精度/限额。各平台最终执行证据留25/26，不提前宣称均支持。
- **提示词**：你是契约工程师，执行 TASK-09。保留宿主模型，显式生成wire spec，不原地篡改宿主路径。拒绝不兼容能力，保持构建可运行，不用stub隐藏未实现安全能力；按 §2闭环。
- **提交**：`feat(runner): TASK-09 version execution manifests and paths [TASK-09-C01]`。

### TASK-10：可信控制面与不可信执行身份隔离

- **状态/角色**：NOT_STARTED；沙箱/操作系统安全工程师。
- **需求/设计**：RQ-R03/AC-R03/T-R03、RQ-R04/AC-R04/T-R04；N2、DEF-05；D §4.1/4.2、§8.3、§9.2。
- **依赖**：09；DEC-07确认实际执行域与监督器方案。
- **设计目标/预期效果**：用户代码不能改可信事件、摘要、取消控制或工具，宿主审计与runner事件不共用可写流。
- **实施/文件**：provider挂载/身份、task_runner.py、实际镜像构建文件、生产能力探针；宿主metadata/final/audit不挂入不可信域；独立监督器或真实身份隔离，必要输入只读、scratch可写、控制FD不继承；编译器及四角色全部纳入。
- **严格测试**：既有provider/artifact/生产沙箱测试，新增真实控制面隔离IT；先宿主写四条audit，再启动校验不丢历史/首事件；各不可信角色尝试改事件/描述/摘要/cancel/工具、伪SUMMARY、控制FD/信号/进程访问，正常编译与临时文件正例；全量。
- **严格验收**：至少目标主平台真实UID/token、挂载/ACL、镜像版本、权限负例证据齐全；改名、chmod同UID、mock命令不合格。未支持平台明确禁用，完整事件验真留11。
- **提示词**：你是沙箱安全工程师，执行 TASK-10。同UID文件改名不算隔离，生成器/答案/SPJ也不可信。不得假定非root进程能安全切UID；需真实身份负向验证，缺环境BLOCKED，按 §2提交汇报。
- **提交**：`fix(sandbox): TASK-10 isolate trusted control plane [TASK-10-C01]`。

### TASK-11：有界增量事件读取与验真

- **状态/角色**：NOT_STARTED；流式协议/安全工程师。
- **需求/设计**：RQ-R03/AC-R03、RQ-R05/AC-R05/T-R03、T-R05，支撑RQ-R04；N2、DEF-06；D §4.4、§6.2、§9.2。
- **依赖**：09、10；事件各项预算已批准。
- **设计目标/预期效果**：读取成本随增量变化，内存受配置限制，重复/恶意/截断事件不导致伪结果或统计回放。
- **实施/文件**：共享文件reader、Linux/Windows adapter、SandboxTaskEvent、SandboxEventIngestor；文件身份+attempt+字节offset，有限尾行，UTF8跨块；单行/批字节/批条数/总量/深度限额；类型、归属、序号、case、摘要一致性验证。
- **严格测试**：SandboxEventIngestorTest、provider测试、新增BoundedRunnerEventReaderTest；半行/无换行/中文跨块、超长/海量/非法JSON/深度、错任务/attempt、重复/乱序/缺号、文件缩短/替换、矛盾SUMMARY；断言字节/缓冲/磁盘上界，全量。
- **严格验收**：无readAllLines/全文件collect，同attempt截断受控失败且保留已接收统计，不重置回放；新attempt重置有明确身份，伪摘要不得覆盖可信聚合。
- **提示词**：你是流式协议工程师，执行 TASK-11。半行等待不报错，完整非法行不能静默吞；序号去重不能重复计数；资源上界用实际计数证明。按 §2记录并提交。
- **提交**：`fix(events): TASK-11 bound and validate incremental ingestion [TASK-11-C01]`。

### TASK-12：显式轮询状态、预算与退出drain

- **状态/角色**：NOT_STARTED；并发编排工程师。
- **需求/设计**：RQ-R02/AC-R02/T-R02，联合RQ-R03/R05；DEF-03；D §4.3/4.4、§6.2。
- **依赖**：11。
- **设计目标/预期效果**：空批不早退，进程退出不丢末批事件，UNKNOWN不假成功，启动至收尾全程有界。
- **实施/文件**：SandboxRunner及全部实现/fake、JudgeService编排、取消令牌；返回events/state/exitCode/cursor/hasMore；ACTIVE可取消等待，EXITED有限drain/flush，UNKNOWN有限退避，单调总deadline覆盖外部调用；统一幂等清理接口与残余记录，不另造权威summary。
- **严格测试**：JudgeSandboxOrchestrationTest、SandboxRunnerContractTest、三个provider测试；可控时钟模拟空→编译→空→结果→完成、延迟启动、最后批/flush、半行永不完成、无终态退出、Worker卡住、取消/预算竞争；断言轮询数、deadline、清理次数，全量。
- **严格验收**：无忙循环、无空批break、无无限HTTP/睡眠/清理；可信终态只接受一次，正常退出零码也不替代协议终态。真实全链路由25/26再验收。
- **提示词**：你是并发工程师，执行 TASK-12。空事件不是退出，UNKNOWN不是成功；所有等待受剩余预算约束，保留末批并拒绝晚到结果。别新增多写者终态，按 §2留证提交汇报。
- **提交**：`fix(runner): TASK-12 model polling and bounded exit drain [TASK-12-C01]`。

### TASK-13：在线输出限制与有界产物

- **状态/角色**：NOT_STARTED；运行时资源工程师。
- **需求/设计**：RQ-R06/AC-R06a/T-R06、RQ-V01；DEF-07、关联N5；D §4.5、§8.2、§9.1/9.2。
- **依赖**：10、12；DEC-01/07额度与合计规则批准。
- **设计目标/预期效果**：双流在线计数，首超额字节触发停止，监督器与磁盘不随任意输出增长。
- **实施/文件**：task_runner.py与实际ProcessRunner实现、结果映射/配置；stdout/stderr并行drain，有限preview/spool，覆盖生成器/答案/候选/SPJ及编译诊断，Java/Python同语义。
- **严格测试**：ProcessRunnerTest、TaskRunnerArtifactContractTest、JudgeBaselineTest及实际Python suite；limit-1/limit/limit+1、纯stderr/stdout/交替双流、输入/诊断超限、输出与超时竞争；受控单进程测RSS/磁盘/preview，监督器存活，下一用例可用；全量。
- **严格验收**：恰等合法，超限稳定OLE、不误AC，不能communicate全收集后检查，不能把无限内存转成无限临时文件；恶意后代强回收由14联合验收。
- **提示词**：你是运行时工程师，执行 TASK-13。按生效限额生成真实超限样例，双流持续排空，不能提高限额掩盖缺陷。保留阶段范围与峰值证据，按 §2完成提交汇报。
- **提交**：`fix(runtime): TASK-13 enforce streaming output budgets [TASK-13-C01]`。

### TASK-14：全进程域治理与有界回收

- **状态/角色**：NOT_STARTED；OS/沙箱资源工程师。
- **需求/设计**：RQ-R06/AC-R06b及AC-R06a/T-R07、T-R06；DEF-08、联合DEF-07；D §4.1/4.3/4.5、§6.1。
- **依赖**：12、13；目标域和清理预算批准。
- **设计目标/预期效果**：父退出/子脱组/持管道都不绕过限时，清理所有后代且不会无限二次等待。
- **实施/文件**：provider/runtime启动和terminate、执行句柄、cleanup；Linux进程组+cgroup/等效域，Windows Job Object kill-on-close且禁breakaway/等效能力；deadline覆盖drain/join/wait，清理失败持久记录并保留占用，能力不足失败关闭。
- **严格测试**：ProcessRunner/artifact/provider测试及真实域IT；父先退出子持管道、孙进程/setsid、编译子进程、取消/超时/超输出、监督器异常、重复清理；外层watchdog下实测耗时不超deadline+grace+批准容差、残余PID/句柄零；全量。
- **严格验收**：至少目标主平台真实恶意进程域实验通过，mock terminate/根PID消失不合格；未验收平台不声称支持，最终矩阵由26复核。
- **提示词**：你是OS工程师，执行 TASK-14。killpg单独不证明恶意后代可控，父退出仍必须清理；不允许二次communicate无限等，不把残留清理失败当容量已释放。按 §2记录真实实验并提交。
- **提交**：`fix(sandbox): TASK-14 reap complete execution domains [TASK-14-C01]`。

### TASK-15：普通比较黄金向量与Java/API精度

- **状态/角色**：NOT_STARTED；数值正确性/后端API工程师。
- **需求/设计**：RQ-C01/AC-C01、AC-C02/T-C01、T-C02；DEF-11、DEF-12、DEC-03；D §5.1/5.2、§4.2、§8.1。
- **依赖**：01、09；词法、空白、非有限规则、输入错误映射与解析上界批准。
- **设计目标/预期效果**：Java不再double误判大整数/NaN，precision从API接收起不丢精度，统一可移植判定向量。
- **实施/文件**：新增共享JSON fixture与比较器，JudgeService普通比较、JudgeRequest/参数校验/策略、index精度输入；整数精确、十进制绝对误差、文本规则、数字长度/指数限额；保留旧JSON数字兼容，区分缺省0/null/显式0，页面1e-9不暗改。
- **严格测试**：JudgeBaselineTest及新增比较/请求契约测试；2^53邻整数、NaN/5、5.0000000001/5@1e-9、空白/中文/空串/CRLF/Unicode空白、边界误差、负零/指数、非法编码/超长数/负精度/null/缺省；API→policy→spec无损和SPJ独立回归；全量。
- **严格验收**：同一fixture Java全部通过，非法参数创建前拒绝；不得double舍入后再stringify冒充精确，不暗加相对误差；跨语言关闭留16/25/26。
- **提示词**：你是数值工程师，执行 TASK-15。黄金向量用十进制字符串，语义由DEC-03决定；完整区分API缺省与页面默认，避免parseFloat先损精度，不改变SPJ。按 §2闭环。
- **提交**：`fix(compare): TASK-15 preserve exact numeric semantics [TASK-15-C01]`。

### TASK-16：Python/Worker跨路径比较贯通

- **状态/角色**：NOT_STARTED；Python/跨语言契约工程师。
- **需求/设计**：RQ-C01/AC-C01、AC-C02/T-C01、T-C02，RQ-R01能力要求；DEF-11、DEF-12；D §4.2、§5.1/5.2、§8.3。
- **依赖**：08、12、14、15。
- **设计目标/预期效果**：普通比较各支持路径相同，精度/模式不静默丢失，未知能力拒绝而不是退回trim比较。
- **实施/文件**：task_runner.py、v2 adapter、RemoteWorkerRunner与实际Python测试；复用15的fixture，使用可证明精确的系数/指数或足够Decimal精度并拒绝舍入；词法、限额、异常映射同Java；外部Worker变更列明外部依赖。
- **严格测试**：TaskRunnerArtifactContractTest、SandboxRunnerContractTest、RemoteWorkerRunnerTest、已有/新增Python suite；同向量Java/direct和真实Python逐条对比，缺模式/精度/版本拒绝，高精度/缺省0/显式0/1e-9贯通；全量。
- **严格验收**：生成向量ID×路径×版本×结果矩阵，无默认Decimal舍入；外部平台未验收不能算通过，声明启用的由26真实补证，不支持者fail-closed。
- **提示词**：你是跨语言工程师，执行 TASK-16。不复制易漂移的向量，不以Python默认Decimal证明精确；缺Worker能力拒绝，不能伪造仓库外实现。按 §2记录矩阵、提交和汇报。
- **提交**：`fix(compare): TASK-16 align runner comparison contract [TASK-16-C01]`。

### TASK-17：单一权威终态与崩溃一致提交

- **状态/角色**：NOT_STARTED；后端持久化/一致性工程师。
- **需求/设计**：RQ-L03/AC-L03/T-L04，R §4可靠性；DEF-21；D §6.2、§4.4、§8.3。
- **依赖**：11、12；终态字段与旧数据兼容方案批准。
- **设计目标/预期效果**：调度器不再用零统计覆盖真实结果，终态只提交一次，缓存/文件/重启读取相同权威快照。
- **实施/文件**：新增小型TaskLifecycleService，FileTaskStore/TaskStore、JudgeService/JudgeScheduler、聚合/进度接入；任务互斥冻结summary，final临时写+flush+原子替换，metadata/summary投影可重建；拒绝晚到事件，真实摘要优先，只有无结果才零兜底。
- **严格测试**：FileTaskStoreTest、JudgeSchedulerTest、ResultAggregatorTest、SandboxEventIngestorTest、ProgressPublisherTest；AC/WA/样本后取消或预算超限、重复/相冲终态、晚到RUNNING；final写前/替换后/投影前故障，缓存清除/模拟重启/真实临时文件系统恢复；全量。
- **严格验收**：分类和等于completed，样本/firstFailedCase/stoppedReason保留；final写失败不发布已持久成功，投影失败可重建；不声称多个JSON具有数据库事务，旧有效summary不清空。
- **提示词**：你是持久化工程师，执行 TASK-17。终态单写者，scheduler只发信号不能重造结果；必须注入每个提交边界故障，证明原子快照与读优先级。不要顺便换数据库，按 §2提交汇报。
- **提交**：`fix(lifecycle): TASK-17 commit authoritative final snapshots [TASK-17-C01]`。

### TASK-18：CREATED取消/TTL与幂等启动

- **状态/角色**：NOT_STARTED；任务生命周期/API工程师。
- **需求/设计**：RQ-L01/AC-L01/T-L01、RQ-U01恢复契约；DEF-13、DEC-02/08；D §6.1、§6.2、§7.1、§8.1/8.2。
- **依赖**：14、17；TTL/扫描间隔及重复调用语义批准。
- **设计目标/预期效果**：创建未启动不永久占用，同任务启动/取消/过期竞争只产生一个合法转移，网络重试不重复执行。
- **实施/文件**：JudgeService pending/start/cancel、JudgeController、TaskCleanupService、状态配置/DTO；CREATED可取消、TTL转STALE，独立扫描；start已运行/排队幂等200，终态按批准409；已终态取消按批准accepted语义返回原快照，无权/不存在仍404。
- **严格测试**：JudgeSchedulerTest、FileTaskStoreTest、TaskCleanupServiceTest、JudgeControllerPolicyTest、QuotaAndAuthorizationTest；三方屏障竞争start/cancel/TTL、创建后刷新/不启动、重复start/cancel、队满429后原任务重试/取消；时钟驱动TTL，全量。
- **严格验收**：不存在取消后又执行/重复入队，pending与占用清理一致；运行中accepted不是已清理，终态提交后才完成取消；双响应格式与授权隐藏语义不变。
- **提示词**：你是生命周期工程师，执行 TASK-18。DEC未批准先阻断；不要把CREATED问题通过自动重跑解决。用任务互斥处理竞态，取消复用17真实摘要，按 §2实测提交。
- **提交**：`fix(tasks): TASK-18 close created-task lifecycle [TASK-18-C01]`。

### TASK-19：覆盖全部非终态的重启恢复

- **状态/角色**：NOT_STARTED；恢复/运行可靠性工程师。
- **需求/设计**：RQ-L01/AC-L01/T-L02、RQ-L03统计保留；DEF-14、DEF-13、关联DEF-21；D §6.1/6.2、§8.3、§9.2。
- **依赖**：14、17、18。
- **设计目标/预期效果**：CREATED/QUEUED/PENDING/COMPILING/RUNNING全部收敛，不重跑，不遗留可占额度的孤儿任务。
- **实施/文件**：FileTaskStore恢复、TaskCleanupService、就绪门槛、持久句柄/终态入口；先校验final修投影，回收残余执行，再无final非终态STALE+RESTART_INTERRUPTED；保留有效统计，清理失败重试与容量隔离，不接新流量。
- **严格测试**：FileTaskStoreTest、TaskCleanupServiceTest、ProductionCleanupTest；五非终态参数化，final存在/缺失/坏投影、句柄缺失/失效/回收失败、反复重启；真实临时storage进程重启测试验证不再执行旧源码，全量。
- **严格验收**：同目录重启结果稳定，残余清理不靠一天后TTL；清理失败就绪不报健康/可用容量，无PENDING/COMPILING遗漏；终态/真实摘要不倒退。
- **提示词**：你是恢复工程师，执行 TASK-19。全部非终态从统一枚举处理，不能继续只查RUNNING/QUEUED；日志警告不等于清理成功。先安全收敛再接流量，按 §2记录故障实验并提交。
- **提交**：`fix(recovery): TASK-19 reconcile every nonterminal state [TASK-19-C01]`。

### TASK-20：用户原子创建配额与每日计量

- **状态/角色**：NOT_STARTED；并发/配额工程师。
- **需求/设计**：RQ-L02/AC-L02/T-L03；DEF-15；D §6.3、§8.1、§9.2。
- **依赖**：18、19；每日计量口径按原契约保留。
- **设计目标/预期效果**：同用户并发创建不能越额，创建失败不遗留占位，成功创建的每日额度不因取消/启动重试错误退款或重收。
- **实施/文件**：QuotaService、JudgeService创建、FileTaskStore提交边界；固定条带用户锁覆盖检查/预留/落盘/pending注册，失败回滚仅本次创建；按稳定owner计数；daily请求case/估计runtime和系统日期不改为实际消耗，回收不得重置当日计费。
- **严格测试**：QuotaAndAuthorizationTest、FileTaskStoreTest、JudgeControllerPolicyTest；屏障竞争最后额度、多用户互不干扰、落盘/pending失败、日期边界/重启重建、重复start/取消/TTL不退款，管理员无隐式豁免；全量。
- **严格验收**：成功数<=额度，失败无CREATED/pending/预留残留；已创建start429仍保留queued/daily且不重收；固定锁不新增无界Map，create时running满拒绝语义不偷改。
- **提示词**：你是配额工程师，执行 TASK-20。只给assertCanCreate加锁不够，原子区必须覆盖提交；创建失败回滚与启动失败保留必须分开。保持每日请求预算口径，按 §2证明竞态与提交。
- **提交**：`fix(quota): TASK-20 reserve task creation atomically [TASK-20-C01]`。

### TASK-21：调度准入的用户运行许可

- **状态/角色**：NOT_STARTED；调度/并发工程师。
- **需求/设计**：RQ-L02/AC-L02/T-L03；DEF-15；D §6.3、§6.2、§9.2。
- **依赖**：20。
- **设计目标/预期效果**：全局并发再高也不突破用户上限，所有退出路径只释放一次，单用户满额不阻塞其他用户。
- **实施/文件**：JudgeScheduler立即执行/出队、QuotaService许可、TaskLifecycleService/清理回调；固定锁顺序或单调度临界区取得全局+用户许可，不持锁做编译/网络；仅实际执行域停止后释放，清理失败保留占用；支持跳过暂不可运行队首。
- **严格测试**：JudgeSchedulerTest、QuotaAndAuthorizationTest、ProductionCleanupTest；全局>用户上限、同用户多任务/多用户公平、executor拒绝、编译失败/正常/取消/预算、重复回调、清理失败/重启恢复、死锁超时；全量。
- **严格验收**：实测峰值不超两级上限，无负许可/重复释放/假回收；无任务获许可却未进入可追踪状态，队满429行为一致。
- **提示词**：你是调度工程师，执行 TASK-21。用户限制必须在实际派发而非只在创建检查；回收资源之前不能释放运行许可。用并发屏障和所有失败注入证明，按 §2闭环。
- **提交**：`fix(scheduler): TASK-21 enforce per-user execution permits [TASK-21-C01]`。

### TASK-22：前端错误恢复与Toast可见性

- **状态/角色**：NOT_STARTED；前端/交互测试工程师。
- **需求/设计**：RQ-U01/AC-U01/T-U01、T-U02；DEF-16、DEF-17；D §7.1/7.2、§8.1。
- **依赖**：05、06、18；批准幂等响应。
- **设计目标/预期效果**：HTTP失败不会伪装运行，错误与成功可见，原任务可重试/取消，通道/计时器不重复。
- **实施/文件**：index.html小型HTTP helper/UI状态机/DOM初始化；create/start/status检查状态与业务值，429保留ID；已知ID网络未知先status，create响应丢失无ID则明确未知且不自动重发；WS降级轮询有退避/停止；toast DOM就绪与可见兜底。
- **严格测试**：FrontendTemplateContractTest加真实浏览器；200/401/403-CSRF/404/409/429/5xx/断网、创建响应丢失、WS失败/重连、双击/刷新/任务切换；断言消息可见、按钮正确、一任务最多一轮询和有效订阅，全量。
- **严格验收**：start发出不等于运行，retryAfter文本不当数字；保留原ID重试可完成，未知create不虚构查找API；文字仍转义，无整页重写，辅助技术可感知提示。
- **提示词**：你是前端工程师，执行 TASK-22。不能只用源码字符串证明toast修好，不自动重复create，不把WS连接成功当start成功。真实浏览器验证并按 §2记录实际效果、提交汇报。
- **提交**：`fix(ui): TASK-22 recover failed starts and visible feedback [TASK-22-C01]`。

### TASK-23：运行摘要与有界进度缓存

- **状态/角色**：NOT_STARTED；后端实时进度工程师。
- **需求/设计**：RQ-U02/AC-U02a、AC-U02b/T-U03、T-U04，RQ-L03；DEF-22、N8、关联DEF-21；D §7.2、§6.2。
- **依赖**：17、21、22。
- **设计目标/预期效果**：运行中HTTP/WS看到同源摘要，历史任务不会永久留节流状态，终态即时且不可被晚到消息覆盖。
- **实施/文件**：JudgeService direct路径、ResultAggregator、ProgressPublisher、进度DTO/前端revision；快照复制、有界样本、不传全results；终态/清理回收，发送失败有限重试/TTL，去重不保留无限历史，终态拒绝晚到非终态。
- **严格测试**：ProgressPublisherTest、ResultAggregatorTest、SandboxEventIngestorTest、HighVolumeJudgeIntegrationTest；40例首例后摘要、HTTP/WS同revision、最后RUNNING completed=total合法、终态绕节流、重复/晚到/断链、海量短任务后容量回落；全量。
- **严格验收**：分类和=completed、计数不倒退，终态与文件一致，缓存数量受活跃量/批准TTL容量约束；发送失败不损持久化，可通过查询恢复。
- **提示词**：你是进度工程师，执行 TASK-23。复用聚合器，不建第二套统计；不能为了去重永久保留任务，也不能禁止合法最后RUNNING统计。按 §2提供容量与一致性证据并提交。
- **提交**：`fix(progress): TASK-23 publish bounded snapshots and release state [TASK-23-C01]`。

### TASK-24：真实HTTP、WS与浏览器安全闭环验收

- **状态/角色**：NOT_STARTED；独立安全测试 + 浏览器E2E工程师。
- **需求/设计**：RQ-A01～A07、RQ-U01/U02、相关RQ-L01/L03；T-A01～A13、T-U01～U04；D §3、§6、§7、§9。
- **依赖**：02～08、18～23。
- **设计目标/预期效果**：从真实入口验证身份、页面与任务状态，不以单测/mock/模板检查替代最终用户行为。
- **实施/文件**：新增可重跑E2E/协议测试入口、有限固定源码夹具、脱敏验收报告；启动隔离应用/HTTPS代理和真实浏览器，两模式及两普通账户/管理员分别走登录→创建→订阅→启动→查询→取消/完成→登出。
- **严格测试**：全量Maven+真实浏览器/原生WS/SockJS；CSRF、Cookie、redirect、query拒绝、伪XFF、限流、跨任务权限、SEND污染、既有订阅撤销、401/429/断网/未知create、toast/运行摘要；执行命令/浏览器版本/截图与协议断言留档。
- **严格验收**：每启用transport都覆盖；越权与不存在同类反馈；新会话可工作、旧会话无后续受保护消息；错误实际可见、无重复执行/轮询，日志无测试凭证。
- **提示词**：你是独立安全验收工程师，执行 TASK-24。不能仅复述开发者测试；用真实应用端口、浏览器和协议客户端复核，发现问题回退关联任务补修提交，不在报告勾通过。按 §2提交证据与汇报。
- **提交**：`test(e2e): TASK-24 verify authenticated browser and realtime flows [TASK-24-C01]`。

### TASK-25：真实Linux容器隔离验收

- **状态/角色**：NOT_STARTED；Linux沙箱安全验收工程师。
- **需求/设计**：RQ-R01～R06、RQ-C01、RQ-L01～L03；T-R01～R07、T-C01/C02、T-L01～L04；DEF-02/03/05/06/07/08/11/12、N2；D §4～6、§9。
- **依赖**：09～21、23；授权的Linux容器测试环境就绪。
- **设计目标/预期效果**：证明生产Linux路径真实可运行且可信控制/资源边界有效，不由trusted-local成功推导容器安全。
- **实施/文件**：真实镜像构建与digest记录、受控攻击fixture/脚本、Linux验收报告；存储根不同于容器根，使用拟上线安全profile、非root/网络禁用/资源限制。
- **严格测试**：真实编译判题/WA/CE/OLE/TLE/取消、空轮询/末批、四角色控制文件/FD攻击、事件截断/伪摘要、双流超限、孤儿/setsid、共同比较向量；确认网络与权限探针，不运行无外层预算的历史大攻击。
- **严格验收**：完整链路正常，恶意程序无法污染宿主控制面，资源耗用在预定阈值、残余为零；镜像/代码/环境与证据可复现。若DEC-07明确Linux不在发布范围，只能完成显式禁用负例并注明未验收，不能标Linux通过。
- **提示词**：你是Linux独立验收工程师，执行 TASK-25。mock Docker参数不证明隔离，需真实镜像与身份/进程域攻击；外层watchdog先部署。逐缺陷留证，按 §2提交并报告平台适用范围。
- **提交**：`test(sandbox): TASK-25 verify real linux isolation [TASK-25-C01]`。

### TASK-26：Windows与Remote Worker能力矩阵验收

- **状态/角色**：NOT_STARTED；跨平台/远程Worker集成验收工程师。
- **需求/设计**：RQ-R01～R06、RQ-C01、RQ-A07、R §4平台约束；相关T-R/T-C/T-A13；DEF-23及runner适用缺陷；D §3.6、§4/5、§8.3、§9。
- **依赖**：08～21、23；DEC-05/07平台决定和外部交付可用。
- **设计目标/预期效果**：每个宣称支持provider有自己的真实证据，不将Python/Linux结论泛化到native或外部Worker。
- **实施/文件**：provider兼容/能力矩阵、Windows artifact版本/ACL/Job Object证据、Worker服务端版本与认证协议证据；部署可重跑黑盒套件或明确禁用配置与启动验证。
- **严格测试**：启用Windows则真实Hyper-V/目标artifact执行路径、权限、全后代、输出/事件/比较/取消；启用Remote则真实HTTPS/Bearer或HMAC服务端正负例、无效身份/重放策略、版本/字段/游标/断连/取消和共同向量。若批准真实mTLS，必须额外无证书/错证书/过期/正确握手矩阵。
- **严格验收**：每provider标SUPPORTED_VERIFIED或DISABLED_UNVERIFIED并附依据；启用但缺服务端/Windows证据就是BLOCKED。禁用须DEC批准且测试不可被配置意外启用，无降级；不能用跳过标通过。
- **提示词**：你是跨平台验收工程师，执行 TASK-26。不伪造仓库外Worker实现或证书验签，不拿模拟服务代替部署证明；缺环境走阻断或批准禁用，不自行缩范围。按 §2提交矩阵并明确实际验证内容。
- **提交**：`test(providers): TASK-26 audit windows and worker capabilities [TASK-26-C01]`。

### TASK-27：故障注入、并发与资源上界综合验收

- **状态/角色**：NOT_STARTED；性能/可靠性工程师 + 安全复核人。
- **需求/设计**：RQ-A03、RQ-R02/R05/R06、RQ-L01～L03、RQ-U02、R §4；相关T-A04/05、T-R02/05/06/07、T-L01～04、T-U03/04；D §9.2/9.3、§4、§6/7。
- **依赖**：24、25、26；容量阈值与测量方法在测试前批准。
- **设计目标/预期效果**：证明系统在高历史量、并发竞争和崩溃下仍有界，终态/许可/结果不分叉。
- **实施/文件**：受控负载与故障注入脚本、指标采集/报告；设置CPU/内存/磁盘/进程外层限制，低基数指标和清理检查；预定义负载、次数、耗时/RSS增长容差，不看结果改阈值。
- **严格测试**：随机登录key容量/TTL、海量短任务缓存、事件增长、双流输出并发；同用户最后额度/两级许可、多用户公平；五非终态重启与final提交各崩溃点；磁盘写失败/满盘、Worker不可达、清理失败、取消/完成竞争。全量回归后重复核心竞态至少按批准次数执行，不隐去失败重跑。
- **严格验收**：实际峰值与阈值表逐项通过；无超配额、死锁、无界增长、永久占用、样本清零；记录失败原始数据与修复提交，所有残余回收，就绪行为正确。
- **提示词**：你是可靠性验收工程师，执行 TASK-27。不要用平均值掩盖峰值，不把最终OLE当内存受限证明；每个崩溃窗口要真实可复现。测试仅授权环境，按 §2提交证据和未覆盖风险。
- **提交**：`test(resilience): TASK-27 prove bounded failure and concurrency behavior [TASK-27-C01]`。

### TASK-28：部署、数据迁移与安全回滚演练

- **状态/角色**：NOT_STARTED；发布/运维工程师。
- **需求/设计**：R §4/6、DEC-06/07，关联全部安全兼容需求；D §8.1～8.3、§9/10。
- **依赖**：27；维护窗口/备份/变更审批与脱敏测试数据准备完成。
- **设计目标/预期效果**：可从旧布局/配置受控切换，旧有效统计保留，不因回滚重新开放已知漏洞。
- **实施/文件**：更新现有 `docs/change-log.md`、安全边界及Linux/Windows/production runbook；新增兼容矩阵/操作清单；写明停止接单、排空/终结、备份、版本绑定、新布局、旧只读、会话/弱码轮换、Header/CSRF客户端迁移、就绪与回滚条件。
- **严格测试**：隔离副本上演练旧终态与五非终态迁移、final优先/投影修复、旧混合events不重放、客户端失败提示；备份可恢复校验、host/镜像/Worker不兼容拒绝；回滚后不能出现匿名WS/弱码/伪mTLS/direct降级。所有删除/迁移/凭证操作先获明确授权。
- **严格验收**：操作命令实测而非仅文字承诺，恢复数据校验一致；历史不可信结果不伪装已验证；安全回滚不成立则手册明确停服而非带漏洞上线。
- **提示词**：你是发布工程师，执行 TASK-28。只在经授权的隔离副本演练，不自动动生产/删除数据；成套版本和数据格式验证，安全无法保障时保持关闭。按 §2记录实际步骤、提交与汇报。
- **提交**：`docs(ops): TASK-28 validate migration and safe rollback [TASK-28-C01]`。

### TASK-29：最终全量回归、独立验收与关闭审计

- **状态/角色**：NOT_STARTED；独立质量负责人 + 安全/发布复核人。
- **需求/设计**：全部RQ/AC，31缺陷，特别RQ-V01/AC-V01；R §5/6，D §9/10。
- **依赖**：00～28有真实提交及有效证据；八项DEC最终状态明确。
- **设计目标/预期效果**：给出可审计的发布结论，每个缺陷能从需求追到实际代码、测试、平台证据和提交，而不是总结式宣称全修好。
- **实施/文件**：生成最终验收报告与逐编号关闭台账；提交前核对00～28真实提交及29候选报告，提交后核验29自身再形成30任务总审计；核对需求设计偏差、平台、风险/回滚，不在审计时掩盖新缺陷，问题回所属任务补修提交再复验。
- **严格测试**：候选代码重新全量Maven、Python/shared vectors、启用provider真实链路、HTTP/WS/浏览器关键安全集、故障资源冒烟；记录候选父SHA与受测树，最终本任务提交只增加报告/状态，若再改代码必须重新相关测试。
- **严格验收**：每个适用缺陷有正例/负例/边界、版本/环境、修复提交、复核人和日期；P1/安全阻断零未解决，关键套件不skip；公网不可信执行发布至少有一个真实验收的隔离provider，全部禁用不等于生产可发布。不适用平台保持禁用及未验收，不虚称31项全平台关闭；门槛不满足则BLOCKED并报告不可发布。确认提交仅本地、没有push操作。
- **提示词**：你是独立验收负责人，执行 TASK-29。逐条验证而非相信DONE；测试缺失/日志与commit不符/未批准偏差立即阻断。清楚报告可发布或不可发布、适用范围和残余风险，按 §2提交最终报告并汇报真实SHA，仍不推送。
- **提交**：`test(release): TASK-29 record final remediation acceptance [TASK-29-C01]`。

## 6. 缺陷—任务—关闭证据矩阵

开发实现任务与最终关闭任务分开；全部31编号独立留证。证据未齐时标 OPEN/IMPLEMENTED_PENDING_VALIDATION/BLOCKED，不因某提交存在而关闭。

| 缺陷 | 对应需求/验收 | 主要实现任务 | 最终关闭证据任务 |
| --- | --- | --- | --- |
| DEF-01 | RQ-A01 / AC-A01 | 05 | 24、29 |
| DEF-02 | RQ-R01 / AC-R01 | 09 | 25、26、29 |
| DEF-03 | RQ-R02 / AC-R02 | 09～12 | 25、26、29 |
| DEF-04 | RQ-A02 / AC-A02b | 06 | 24、29 |
| DEF-05 | RQ-R04 / AC-R04 | 10、11 | 25、26、29 |
| DEF-06 | RQ-R05 / AC-R05 | 11 | 25～27、29 |
| DEF-07 | RQ-R06 / AC-R06a | 13、14 | 25～27、29 |
| DEF-08 | RQ-R06 / AC-R06b | 14 | 25～27、29 |
| DEF-09 | RQ-A03 / AC-A03a | 03 | 24、27、29 |
| DEF-10 | RQ-A03 / AC-A03b | 03 | 24、27、29 |
| DEF-11 | RQ-C01 / AC-C01 | 15、16 | 25、26、29 |
| DEF-12 | RQ-C01 / AC-C02 | 15、16 | 25、26、29 |
| DEF-13 | RQ-L01 / AC-L01 | 18、19 | 24、27、29 |
| DEF-14 | RQ-L01 / AC-L01 | 19 | 27、29 |
| DEF-15 | RQ-L02 / AC-L02 | 20、21 | 27、29 |
| DEF-16 | RQ-U01 / AC-U01 | 22 | 24、29 |
| DEF-17 | RQ-U01 / AC-U01 | 22 | 24、29 |
| DEF-18 | RQ-A04 / AC-A04a | 02 | 24、29 |
| DEF-19 | RQ-A04 / AC-A04b | 04 | 24、29 |
| DEF-20 | RQ-A05 / AC-A05a | 04 | 24、29 |
| DEF-21 | RQ-L03 / AC-L03 | 17、19、23 | 24、27、29 |
| DEF-22 | RQ-U02 / AC-U02a | 23 | 27、29 |
| DEF-23 | RQ-A07 / AC-A07 | 08 | 26、29 |
| N1 | RQ-A02 / AC-A02a | 06 | 24、29 |
| N2 | RQ-R03 / AC-R03 | 09～12 | 25、26、29 |
| N3 | RQ-A04 / AC-A04c | 02、06 | 24、29 |
| N4 | RQ-A05 / AC-A05b | 02 | 24、28、29 |
| N5 | RQ-V01 / AC-V01 | 01、13（输出边界关联14） | 25～27、29 |
| N6 | RQ-A06 / AC-A06a | 07 | 24、29 |
| N7 | RQ-A06 / AC-A06b | 04、06 | 24、29 |
| N8 | RQ-U02 / AC-U02b | 23 | 24、27、29 |

## 7. 执行恢复、变更控制与交付要求

中断后先读work最后记录、任务状态、实际git log/status与证据，再决定继续点；不能仅靠聊天历史。日志说DONE但找不到定位提交时视作未完成；提交存在但测试证据过期/被后续改动影响，则补验证，不自动沿用。

任务过大可在实施前经确认细分为 `TASK-XX-A/B`，每个子任务仍独立测试、work更新、commit与汇报；父任务只有全部子项及原验收通过才DONE，须同步依赖/矩阵/台账。发现新业务需求或高风险架构变更，先更新R/D与批准记录，不绕过需求设计直接实现。

每项提交必须包含其实际交付，不允许空提交伪造完成；验收任务的交付为真实可重跑测试/报告/证据索引，不只是改勾选。没有业务代码变更的任务也必须证明其文档/验收工作实际完成。

最终交付为：实现与测试、全部适用缺陷证据、部署迁移/回滚记录、持续更新的task/work、逐任务本地提交历史和完成汇报。**所有阶段均不推送远端；需要推送必须由用户另行明确要求并确认。**
