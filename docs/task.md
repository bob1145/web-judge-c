# 缺陷修复开发任务清单

## 1. 使用范围与事实声明

- 制定日期：2026-10-02；当前执行状态见任务卡及work；TASK-00已取得批准并建立真实红基线，不是缺陷修复完成报告。
- 项目根目录为 `web-judge-c/`；任务文件 `docs/task.md`，逐次工作记录 `docs/work.md`。
- **R** 代表 `docs/defect-remediation-requirements-2026-10-02.md`；**D** 代表 `docs/defect-remediation-design-2026-10-02.md`。任务按 RQ/AC 和 D 小节精确关联。
- 缺陷输入为 `docs/defect-review-2026-10-02.md` 及动态附录；冲突以附录为准。审查基线 `f31d039`，实际开发必须另记真实 HEAD。
- 共 30 个任务（TASK-00～29），覆盖 31 个缺陷编号；开发任务完成不自动等于缺陷关闭，真实关闭证据由 TASK-24～29 汇总。
- 初始规划只制定任务与日志，全部任务初始NOT_STARTED；后续实施、实际测试及提交以work和Git联合核验。此前文档交付不算TASK-00完成，所有阶段不推送。
- 保留单实例文件存储和现有框架/API；不做数据库/分布式改造，不重写前端，不自动重跑历史代码。未验收 provider 不生产启用，不回退 direct。

### 1.1 当前执行策略（2026-10-07纠偏）

- 沿用原Goal和TASK-00～29全部适用任务，使31个缺陷有明确结论及有效证据；不新建Goal、不重启项目、不擅自删减平台或需求。本节及project-goal第3/6节替代旧附件/任务卡的重复测试、冻结与文档频次要求，不降低AC、安全底线或原交付门槛。
- 最小必要实现，复用现有接口、锁、状态机、校验、超时和测试设施。充分红测已有则直接修复；新问题按归属登记，不扩当前任务，不重开已交付TASK-10，不另建通用管理器或验证框架。
- 局部改动只跑受影响套件及缺陷回归；任务交付一次必要全量及相关真实验收；最终集中验证跨模块、适用平台、资源上界和回滚，复用未失效证据。仅相关源码、配置、依赖或环境变化才复验受影响部分；文档只检查文档，不重建、重测或重新冻结产品。
- 采用现有测试报告和参数化边界用例，确认关键套件实际执行、失败正确传播、非预期跳过被发现；不扩固定测试总数门禁。已知无关失败归对应任务，不反复重跑，不放宽业务/安全断言；关键真实验收不能用mock替代。
- 日常仅简短记录修改、验证和剩余阻断，不新增重复Goal/交接/总结。阶段必须有实际修复、关键验证或明确阻断；连续两阶段无功能进展时检查方法并报告真正阻断，不用文档或测试工具建设代替推进。必要验收、记录一致及原约定交付完成后才DONE，整个Goal不提前complete。

## 2. 每个任务必须执行的闭环

### 2.1 状态、依赖与角色

状态为 `NOT_STARTED -> IN_PROGRESS -> VERIFIED_PENDING_COMMIT -> DONE`；缺决策/环境/依赖为 BLOCKED，解决后回到 IN_PROGRESS。代码写完不等于验收通过，提交失败不得 DONE。

每次只推进一个主任务到提交。可并行只读探索或不冲突实现，但明确文件所有权、不得回滚他人改动；共享 JudgeService、安全配置、DTO、runner、模板及日志时串行集成。仅主负责人整理 task/work 和提交，子代理不擅自提交。

每项可设置实施人和独立复核人；自审必须明示，不能伪造双人审核。最终安全放行需要独立复核。卡片提示词自动包含本节及 §3，不能因为卡片简写跳过公共要求。

### 2.2 逐任务九步流程

1. **读取与核查**：阅读任务卡、R/D、work、适用 AGENTS，检查 HEAD/工作区、前置真实提交和批准 DEC。不能只信表格勾选。
2. **开工汇报**：报告任务、角色、目标、文件、风险、测试，日志记录 IN_PROGRESS；新增需求先记录偏差，不默默扩大范围。
3. **先测后修**：已有充分红测直接复用，否则用现有设施补最小回归并保存预期失败，不为同一问题建立第二套复现；文档/审计任务只做相关静态检查。不得为复现向生产运行攻击。
4. **实现**：最小改动、复用现有契约，不以建议值替代批准值，不用 stub 冒充真实安全能力。拟新增测试先实际创建再写执行命令。
5. **风险分层测试**：依 §1.1 和 §3 执行受影响回归及本项必要验收，复用未失效证据；保留命令、环境、退出码、通过/失败/跳过数，不重复无关全量或同一真实实验。
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
git diff --check
```

Linux 定向为 `./mvnw '-Dtest=单测集' test`，每步非零失败。任务交付或最终验收需要全量时使用 `.\mvnw.cmd test` / `./mvnw test`，非零退出必须传播。记录 Surefire XML 的总数/失败/错误/跳过，不能只截 BUILD SUCCESS，不使用 skipTests 或禁用失败测试。

TASK-00 可准确记录历史红基线，不得声称通过；TASK-01 必须建立绿基线。局部代码改动只执行受影响定向测试和缺陷回归；任务交付执行一次必要全量及相关真实验收，结果未失效不重复。纯文档改动只检查文档；配置/生成逻辑改变则验证受影响行为。TASK-29 集中最终全量及跨模块验收，复用仍有效的阶段证据。

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
| [x] | TASK-02 | 凭证解析、安全默认、日志 | 00、01；[TASK-02-C01]已核验 |
| [ ] | TASK-03 | 可信代理、有界限流 | 02 |
| [ ] | TASK-04 | Cookie、重定向、CSRF | 02、03 |
| [ ] | TASK-05 | 双模式登录 | 04 |
| [ ] | TASK-06 | WebSocket 全链路授权 | 03、04 |
| [ ] | TASK-07 | 会话环境绑定 | 03、06 |
| [x] | TASK-08 | Worker HTTPS 真实认证 | 01、02；[TASK-08-C01]已核验 |
| [x] | TASK-09 | Runner v2 与跨平台路径 | 01；[TASK-09-C01]真实提交核验后生效 |
| [x] | TASK-10 | 控制面身份隔离 | 09 |
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

- **状态/角色**：BLOCKED；安全/并发工程师。默认直连来源切片已修复并验证[TASK-03-R01]：两认证路径仅取socket地址，关闭框架转发头解析，两模式真实HTTP含伪XFF/X-Real-IP/Forwarded仍绑定回环来源；定向45/45、零失败/错误/skip。可信代理CIDR、来源/账户阈值、状态容量/TTL及代理头上限仍待具体批准；未提交，不代表完整限流验收，不擅自采用业务默认。
- **原子尝试记录**：[TASK-03-ATTEMPT-ATOMIC-R02]；两服务仅合并原失败次数/时间记录，在原ConcurrentHashMap.compute内完成同键重置、锁定检查、凭据判断及成功清零；保持既有key、maxAttempts/attemptResetHours、返回值和日志，不加管理器或默认数值。受控并发两参数先红，修复后六套110/110、零失败错误skip；同键后继锁定、不同来源可继续、成功只清自身记录均通过，真实HTTP/TLS及63项认证回归有效。独立账户/来源限制、容量/主动TTL及代理批准仍缺，不代表完整03或DEF-09/10关闭。
- **需求/设计**：RQ-A03/AC-A03a、AC-A03b/T-A04、T-A05；DEF-09、DEF-10；D §3.4、§9.1/9.3。
- **依赖**：02；可信代理与容量阈值批准。
- **设计目标/预期效果**：伪XFF不改变来源，换IP/账户不能绕过独立限制，认证状态规模有界。
- **实施/文件**：新增共享ClientAddressResolver及有界限流组件；UserAccountService/AccessCodeService计数与时间原子更新；默认socket来源、可信CIDR右向左解析、限制头长度；主动TTL，满容量保守拒绝新key，不能驱逐未到期封禁。
- **严格测试**：ProductionAuthenticationTest与现有限流测试；新增来源/限流测试。IPv4/IPv6、多跳/多头/非法链、直连伪造、同账号换IP/同IP换账号、并发阈值、高基数容量、无需再次访问的TTL；可控时钟+全量。
- **严格验收**：来源单一真相，框架不二次解析；容量满仍保留有效封禁；指标无随机key无限基数，正例正常登录。
- **提示词**：你是并发安全工程师，执行 TASK-03。不能用无界新Map替代旧Map，不能用LRU清除有效锁定；用时钟和并发屏障证明边界，按 §2闭环。
- **提交**：`fix(auth): TASK-03 bound rate limits and trust proxy sources [TASK-03-C01]`。

### TASK-04：Cookie、重定向与CSRF

- **状态/角色**：BLOCKED；应用安全工程师。站内重定向、Cookie属性及HTTP会话绑定CSRF已修复；复用Spring设施，登录前token、成功登录/登出轮换、两模板POST接入；仅无任何Cookie且有效X-Session-ID认证的请求豁免。R04 Cookie/TLS客户端63/63；R06受影响11套145/145，R08当前CSRF真实HTTP14/14，均零失败/错误/skip，实际Cookie创建→启动→良性C++ AC、登出/旧token拒绝及CORS通过。R07 CREATED取消accepted=false归18，成功断言原样保留，不放宽04或重做TASK-10。03整体前置、生产TLS启动/代理及完整任务验收仍未闭环；不冒称生产整体/隔离provider通过，未提交不标DONE，DEF-19/20及N7最终关闭留24/29。
- **真实HTTPS浏览器**：[TASK-04-HTTPS-BROWSER-R01]；复用原浏览器入口及WorkerTlsCertificates，临时NSS库仅信任本次CA，不忽略证书错误。R01五项全PASS、pageErrors0、退出0：未信任CA真实拒绝；受信TLS1.3登录，实际Cookie为Secure/HttpOnly/SameSite=Lax及30天remember；HTTPS创建/启动携带Cookie、真实C++一例AC、WSS/HTTP整终态一致；登出Cookie按原安全属性删除、保护查询401；原实际Secure Cookie不改标志复制到无HSTS新浏览器，同host真实HTTP请求未发送该Cookie。target/task04-https-browser-r01.json及completed图；仅临时工具/夹具，无产品或核心依赖改动，不重跑原有效回归。该浏览器环境阻断解除，但不代表生产TLS/代理或隔离provider验收。
- **需求/设计**：RQ-A04/AC-A04b/T-A07、RQ-A05/AC-A05a/T-A09、RQ-A06/AC-A06b/T-A12；DEF-19、DEF-20、N7；D §3.2、§8.1。
- **依赖**：02、03；DEC-06批准。
- **设计目标/预期效果**：会话安全传输、登录只回本站、Cookie状态变更防伪，合法页面仍可用。
- **实施/文件**：AuthenticationController统一Cookie/redirect；SecurityConfig启用会话绑定CSRF、登录前token与成功轮换；auth/index模板所有状态变更fetch接入；生产Secure+HttpOnly+SameSite，保留rememberMe；Header豁免仅限真实无Cookie认证，不可任意Header绕过。
- **严格测试**：认证/模板既有测试；新增Cookie/redirect/CSRF集成。三登录分支相同攻击向量、编码绕过、Cookie创建删除属性、旧token/错误token/跨站POST、无效Header回退、CORS；真实本地HTTPS客户端及浏览器传输冒烟，全量回归。
- **严格验收**：覆盖登录/登出/创建/启动/取消/清理/管理；不保留全局csrf.disable，SockJS只有窄例外；伪协议头不能关Secure，重定向不出站。
- **提示词**：你是应用安全工程师，执行 TASK-04。SameSite不是CSRF替代，先补齐页面token再启用拒绝；不放宽生产TLS去迁就测试。记录真实HTTPS证据及 §2闭环。
- **提交**：`fix(security): TASK-04 secure cookies redirects and csrf [TASK-04-C01]`。

### TASK-05：双认证模式登录页面

- **状态/角色**：BLOCKED（04整体前置与必要交付回归未完成）；前端工程师。双认证表单、HTTP/业务错误、CSRF、rememberMe与文本提示已实现；既有75项回归有效，现有浏览器能力不再阻断。实际源码修复与分轮真实验证见[TASK-05-BROWSER-VISIBILITY-R04]；未C01、不标DONE，DEF-01完整关闭仍待交付。
- **真实浏览器与可见性修复**：[TASK-05-BROWSER-VISIBILITY-R04]；R02两模式14项业务/DOM检查通过，真实401/429、历史200失败、旧CSRF403、站内redirect、密码前后空格与30天Cookie、登出及未认证均验；503仅故障呈现注入，断网为请求abort，不冒充服务端503。截图揭示表单被根级fade-in与现有GSAP/错误shake叠加淡化，故R02不作为完整可见性通过。只移除auth-container上的fade-in，保留原动效入口、错误DOM与安全语义；原模板认证测试加根因断言，受影响套件33/33、零失败错误skip、退出0。真实双模式×普通/减少动效/无GSAP六组合分轮绿，父级合成不透明度≥0.95且截图实看：账号R03三项绿，后因交互等待达外层预算被KILL，无完整JSON/正常退出证明；去掉不必要暂停后仅补验访问码R04三项绿、退出0、pageErrors0、应用SIGTERM143，无强杀。R01非2xx响应体读取卡住的124及R03超时均保留；复用未受影响14项业务与后端证据，不重跑全矩阵。证据target/task05-browser-r01/r02.json、R02反例截图、R03账号三截图/实际输出、target/task05-browser-visibility-r04.json及三截图；旧IAB/Edge失败历史不重试。

- **需求/设计**：RQ-A01/AC-A01/T-A01；DEF-01；D §3.1、§3.2、§8.1。
- **依赖**：04。
- **设计目标/预期效果**：账号/访问码模式均能真实登录、可见提示、站内跳转，页面不自行决定认证模式。
- **实施/文件**：复用requiresAccountAuthentication()渲染auth.html，分别提交username/password或accessCode；保留rememberMe、接口与字段；同时判断HTTP及success，分离401/429/5xx/断网及历史200+success=false。
- **严格测试**：ProductionAuthenticationTest、FrontendTemplateContractTest；新增并实际执行真实浏览器两模式成功/失败/登出/受保护页/重定向测试，伪造模式字段不得降级认证；全量回归。
- **严格验收**：每个错误实际可见、失败不跳转，CSRF正常；模板字符串通过不代替浏览器证据。
- **提示词**：你是前端工程师，仅执行 TASK-05，使用后端模式与原接口，不在控制台打印凭证。两模式真实浏览器闭环留证，按 §2更新work并提交汇报。
- **提交**：`fix(ui): TASK-05 support both authentication modes [TASK-05-C01]`。

### TASK-06：WebSocket握手、STOMP授权与撤销

- **状态/角色**：BLOCKED（03/04完整前置、空闲连接主动撤销、连接/订阅预算及真实浏览器未闭环）；实时通信安全工程师。完成SEND、身份/精确订阅、CONNECT CSRF、账户状态复核及发送前撤销切片，未C01、不标DONE。
- **SEND最小修复**：[TASK-06-SEND-R02]；原WebSocketConfig用标准入站ChannelInterceptor拒绝客户端MESSAGE/SEND，topic/queue/未开放app一律失败关闭，服务器发布通道不受影响；无新管理器、依赖或生产参数。R01真实六参数5通过/1夹具共用owner触发原429，隔离参数身份且不改配额/断言后，原生WS、SockJS WS、XHR streaming/polling四入口×三目的地12/12，零失败错误skip、退出0。真实ERROR拒绝及服务器进度DTO推送已验，未代替匿名握手、owner/admin订阅、CSRF/Principal或既有订阅撤销验收，不关闭DEF-04/N1整体。
- **身份/订阅最小修复**：[TASK-06-IDENTITY-R06]；原安全链后置WebSocketSessionGuard，/ws及全部transport复用SessionCredentialResolver/getSession绑定服务端Principal，禁重复Servlet注册；CONNECT/SUBSCRIBE复核活会话，精确进度主题复用canAccessTask和原TaskStore ID校验，客户端ADMIN声明无效，Principal仅公开审计指纹。四入口owner/同owner新会话/admin实际推送、无效凭据HTTP拒绝及XHR跨会话读/发送拒绝已验；R02其余29项与R01认证/授权/启动61项有效结果复用，R06定向订阅28/28，零失败错误skip、退出0。26项真实ERROR、2项轮询3000/Go away!关闭，按原ERROR/断开契约验收，普通传输异常/超时仍失败，原SEND的ERROR断言不变。该轮未证明真实账户登录、Header全协议矩阵、CONNECT CSRF、既有订阅主动撤销或资源上界，不关闭整缺陷、不交付C01。
- **CONNECT CSRF最小修复**：[TASK-06-CSRF-R02]；复用原HTTP CSRF仓库，标准握手/XOR入站拦截器校验native token，前端CONNECT携带原meta token并关闭帧debug；只对三类SockJS POST窄适配，REST与Origin不放宽。新增受Boot管理的messaging 6.5.1模块、核心版本未升级。R01 152项148通过/4关闭证据竞态；仅修future选择并独立保留越权数据断言，R02定向40/40、零失败错误skip、退出0，复用其余112项。四入口缺失/错误/跨会话token拒绝，Cookie/Header正例无HTTP CSRF Header仍推送，Header正例以JSESSIONID绑定CSRF、不宣称完全无Cookie；非transport POST和未批准Origin仍403。完整真实账户->WS撤销及浏览器/资源验收仍未完成。
- **账户状态最小修复**：[TASK-06-ACCOUNT-R02]；真实账户登录在发布会话前绑定账户来源，原getSession/isSessionValid复用当前账户查询，禁用/移除、稳定ID替换及管理员撤权即失效并删除旧会话；恢复账户不复活，旧普通会话不自动提权，访问码模式保留。R01受影响五套168/168、R02仅新增协议8/8，均零失败错误skip、Maven退出0；真实Cookie登录及四入口下一次订阅/新握手拒绝已验。两轮退出阶段仍有XHR流读取超时与Surefire 30秒强杀fork，显式客户端断连未解决；不作为无帧主动关闭或资源回收通过证据，不继续扩测试工具。剩余主动撤销、批准的关闭时限/连接与订阅预算及完整验收仍阻断。
- **出站撤销 [TASK-06-OUTGOING-REVOKE-R03]**：复用原会话复核与Spring传输装饰器，每次实际发送前检查服务端Principal；会话失效则丢弃数据并POLICY_VIOLATION关闭，无新连接表/调度器/预算。四transport×禁用/管理员撤权/真实登出/测试会话实际到期16项通过，已订阅连接无需新客户端帧，受保护消息零送达、框架用户索引回落；轮询1006仅在实测HTTP401且索引回落时认可，普通传输错误仍失败。R02协议106项82过/24因管理员负例CSRF请求UA错误而302；两行修正夹具后仅R03订阅28/28，复用其余78项，106项断言分轮有效通过。R01/R02仍有旧XHR退出读取超时及Surefire30秒强杀dump，不当资源/完整退出验收；无任何发送的空闲主动关闭及批准时限/容量仍阻断。
- **需求/设计**：RQ-A02/AC-A02a、AC-A02b/T-A02、T-A03；RQ-A04/AC-A04c；DEF-04、N1、N3；D §3.1～3.3。
- **依赖**：03、04。
- **设计目标/预期效果**：匿名不可连、越权不可订、客户端不可造进度，已失效连接不能继续接收。
- **实施/文件**：WebSocketConfig握手与启用SockJS transports复用身份/来源；CONNECT绑定Principal并验token；SUBSCRIBE精确目的地且复用canAccessTask；拒绝客户端SEND；有界连接/订阅索引，登出/到期/禁用主动关连接，前端CONNECT补token。
- **严格测试**：新增真实端口STOMP授权/撤销集成，复用QuotaAndAuthorizationTest。匿名/过期/query-only/Origin、A/B/admin/通配、已认证与匿名SEND、已订阅后登出与自然到期、反复断连/越限；全量回归。
- **严格验收**：N1握手/SEND与DEF-04任务授权各独立证据；无后续客户端帧也在批准时限内断连，连接索引回落；原生WS与每个启用回退传输都验收。
- **提示词**：你是实时安全工程师，执行 TASK-06。Origin、UUID、MVC拦截器不替代认证，仅CONNECT检查不足；必须真实协议验证，按 §2提交和汇报。
- **提交**：`fix(ws): TASK-06 enforce identity ownership and revocation [TASK-06-C01]`。

### TASK-07：会话环境绑定策略

- **状态/角色**：BLOCKED；后端认证工程师。按2026-10-02 DEC-04落实管理员STRICT与普通默认AUDIT/可配置STRICT；03/06整体、代理/UA规范化及完整撤销验收仍缺，未交付不DONE。
- **实际切片 [TASK-07-POLICY-R04]**：复用原配置/校验服务，STRICT删除会话并真实false，HTTP拦截器不再忽略拒绝，auth页面/check/session和WS握手共用；管理员不被普通AUDIT配置降级。AUDIT仅记录指纹/变化布尔量，无新索引、通知框架或资源默认值；当前沿用原来源/UA值比较，OFF未启用且原要求未删。15项真实账号Cookie/直连来源变化与原生Upgrade全过；其余有效回归复用共113项，失败及最小夹具修正见work。四transport正常owner/admin、TLS与CSRF有效；不冒称无帧关闭、生产代理、规范化或全N6关闭。
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

- **状态/角色**：DONE（提交候选；[TASK-09-C01]真实提交核验后生效）；契约/跨平台后端工程师；R05定向124项/122通过/2原环境跳过，R06全量360项/358通过/2原环境跳过，均0失败/0错误；真实Python suite 11/11。证据见docs/acceptance/TASK-09。DEF-02待25/26真实平台验收，N2及比较算法未关闭；用户要求本项提交后暂停。
- **需求/设计**：RQ-R01/AC-R01/T-R01、RQ-R03、RQ-C01参数贯通；DEF-02；D §2、§4.1/4.2、§5.2、§8.1/8.3。
- **依赖**：01；DEC-03/07的字段格式、目标平台已批准，比较算法留15/16。
- **设计目标/预期效果**：宿主domain spec与执行清单分离，宿主根不同于执行根时源码仍可达，未知协议失败关闭。
- **实施/文件**：SandboxTaskSpec/Capabilities/RunHandle、三个provider adapter和task_runner.py；v2显式版本/judgeId/attemptId、相对源码路径、precision字符串/模式/单位/限额；路径全部验证后才建文件；新attempt独立流，不截断旧流。
- **严格测试**：SandboxRunnerContractTest、LinuxContainerRunnerTest、WindowsHyperVContainerRunnerTest、RemoteWorkerRunnerTest、TaskRunnerArtifactContractTest；绝对/父级/UNC/驱动器/链接逃逸/非本任务路径、缺字段/未知版本/不支持能力；授权隔离环境验证不同根映射，不覆盖根外文件；全量。
- **严格验收**：不使用宿主Path解释异OS路径，危险路径失败前后根外快照不变；旧provider不静默丢精度/限额。各平台最终执行证据留25/26，不提前宣称均支持。
- **提示词**：你是契约工程师，执行 TASK-09。保留宿主模型，显式生成wire spec，不原地篡改宿主路径。拒绝不兼容能力，保持构建可运行，不用stub隐藏未实现安全能力；按 §2闭环。
- **提交**：`feat(runner): TASK-09 version execution manifests and paths [TASK-09-C01]`。

### TASK-10：可信控制面与不可信执行身份隔离

- **状态/角色**：DONE（实际提交a9d2e11768972977fec03e512a9dc02bd91f3ae7）；沙箱/操作系统安全工程师。按原卡完成Linux独立阶段身份/挂载隔离、宿主与runner双事件面、可信取消及持久归属防护；真实四角色权限负例、正常执行和取消控制路径已有证据。生产provider仍失败关闭，DEF-05/N2的整链关闭留11、25/29；完整域回收和重启收敛按原依赖分别留14、19，不以其扩大TASK-10。
- **最近已验收切片（2026-10-06；历史）**：产品源码未改；新增严格受控故障Probe/验证器。R18编译2/2、195输入/306资产独立核验；R13真实unit0/103.318973秒，真实私有CLI连接失败和supervisor/stage实际rm之后的响应丢失注入，在同attempt由新JVM恢复。PID1742→2161→2697→3407与producer3893、5101→5669→6426，三条完整runtime/stages现场链接相等，七恢复PID互异；11次成功mutation严格完整ID回显，连接错误exit1/stdout空/stderr222字节。持久STOP_REQUESTED/REMOVAL_REQUESTED/stage REMOVING后才新进程重验，卷/控制证据保留，无删除凭据。R12旧==79前置门禁失败保留，修为严格==88后R03入口和R13辅助88+13+7过。不是真实daemon故障、所有步骤失败、活跃取消或完整卷/UNKNOWN/删除；新独立复核仍缺，完整任务不DONE、不提交。
- **交付边界（2026-10-06）**：STAGE-R02四角色编译/执行、控制文件/工具/FD/进程/信号负例及四条宿主audit保存已核实；隔离命令/输入与task_runner/volume路径未改，后续journal/准入加固已有回归。JAVA-SUPERVISOR-R16真实Runner同JVM取消得到空0600可信marker、合作ExitCode20及USER实际CANCELLED/CLEANED/阶段回收，新JVM容器恢复和无操作重试通过。最新CANCEL-CONTROL-R03四套件470项/469过/0失败错误/1旧镜像skip，取消核心零skip；覆盖扫描后新增marker的锁内校验。R28全量Java2400过/1既有TASK-18失败/2环境skip、Python142/142，受影响Journal/Discovery以R03补验，不重复全量/冻结。R20/R16为其冻结版本，不冒称当前所有Java源码对应旧bundle。两卷/控制证据保留、UNKNOWN不猜失败、不发删除凭据，受管目录删除仍阻断；全程调度/资源占用保留由12/14/19/21必验，不能以此证据冒称已证明；未证明完整productRecovery/productionAcceptance，生产身份/代际/终结权限留25/28冻结。提交定位TASK-10-C01，父HEAD5f672c2，实际SHAa9d2e11768972977fec03e512a9dc02bd91f3ae7已核验，未推送。
- **需求/设计**：RQ-R03/AC-R03/T-R03、RQ-R04/AC-R04/T-R04；N2、DEF-05；D §4.1/4.2、§8.3、§9.2。
- **依赖**：09；DEC-07确认实际执行域与监督器方案。
- **设计目标/预期效果**：用户代码不能改可信事件、摘要、取消控制或工具，宿主审计与runner事件不共用可写流。
- **实施/文件**：provider挂载/身份、task_runner.py、实际镜像构建文件、生产能力探针；宿主metadata/final/audit不挂入不可信域；独立监督器或真实身份隔离，必要输入只读、scratch可写、控制FD不继承；编译器及四角色全部纳入。
- **严格测试**：既有provider/artifact/生产沙箱测试，新增真实控制面隔离IT；先宿主写四条audit，再启动校验不丢历史/首事件；各不可信角色尝试改事件/描述/摘要/cancel/工具、伪SUMMARY、控制FD/信号/进程访问，正常编译与临时文件正例；全量。
- **严格验收**：至少目标主平台真实UID/token、挂载/ACL、镜像版本、权限负例证据齐全；改名、chmod同UID、mock命令不合格。未支持平台明确禁用，完整事件验真留11。
- **提示词**：你是沙箱安全工程师，执行 TASK-10。同UID文件改名不算隔离，生成器/答案/SPJ也不可信。不得假定非root进程能安全切UID；需真实身份负向验证，缺环境BLOCKED，按 §2提交汇报。
- **提交**：`fix(sandbox): TASK-10 isolate trusted control plane [TASK-10-C01]`。

### TASK-11：有界增量事件读取与验真

- **状态/角色**：BLOCKED（有界reader预算前置未批准）；流式协议/安全工程师。09/10真实提交已满足；已完成不依赖待定阈值的协议验真切片，未交付、未标DONE。
- **实际修复**：真实handle绑定v2/attempt、连续sequence验真及重复去重；case范围/字段一致性/唯一性；SUMMARY只验证而不覆盖宿主聚合、不提前终结，COMPLETED不得补造缺失结果；提前停止仅沿用原请求授权。复用既有Session/聚合器、参数化测试和Python emitter，无新通用框架。
- **最近验证（2026-10-06）**：R04两套43/43；v2接线后R05四套90/90（Ingestor53、Orchestration8、Artifact9、Remote20），均零失败/错误/skip；真实Python/C++正常AC及首个WA停止→Java验真/入库通过。统一case范围检查后R06仅重验Ingestor55/55及改过的Python单例1/1；标准Surefire/unittest结果保留，不把切片当完整11/生产provider验收。
- **JSON解码补齐（R08）**：复用Jackson ObjectReader及内置类型约束，三个provider不再将小数/字符串截为整数，不接受枚举序号、文本强转、重复键或尾随JSON；应用ObjectMapper保持原配置。R07b新契约18项17失败证明缺陷，最小修复后R08五套92项90过/0失败错误/2已解释环境skip；协议27及真实Runner9零skip，正常AC/首错停止→Java入库仍通过。
- **必填字段补齐（R10）**：仅在现有事件Mapper内添加Jackson构造字段约束，缺失摘要计数不得静默补零、缺失结果状态拒绝解码，显式零值与可选首错/停止原因仍正常；不改变应用DTO解析。R09b新参数化回归13项10失败，R10受影响契约41、三个provider各1及真实Runner2，共46/46、零失败/错误/skip；未带入旧Probe。标准证据及失败历史见work [TASK-11-R10]，预算/reader门槛不变，未提交不标DONE。
- **文件身份切片**：[TASK-11-FILE-IDENTITY-R02]；原RunState记录已见文件身份/长度，同状态内串行校验与游标提交；只读常规且可识别的文件，NOFOLLOW打开，读取后再核对。已见文件消失、身份更换、缩短或丢失已见行走原宿主异常，不伪造Runner事件、不重置游标。两provider同类五参数覆盖启动前缺文件、正常追加及截断/替换/移走/符链；R01新10项2过8红，R02四套160项158过/零失败错误/2既有环境skip，十个新参数均实际通过。真实临时文件系统、外部容器命令替身，不冒称容器/Windows验收；未新增reader框架、数值或权限。
- **编译状态验真 [TASK-11-COMPILE-STATUS-R02]**：原COMPILE_FINISHED把任意未知状态当编译成功；仅在现有分支加三行拒绝校验，保留Runner实际OK、无状态兼容与原编译失败别名。七个非法状态红测全失败；最小修复后四个受影响选择器98/98、零失败错误skip，拒绝不发布/落摘要/推进序号，同序号合法事件可继续。真实Python/C++正常三AC及编译失败→Java入库通过；不替代reader预算、平台容器或完整11验收，仍BLOCKED。
- **待确认门槛**：TASK-00审批仍将256KiB/256条批次、128KiB行上限列为候选；事件累计字节、字段/深度上限亦未冻结。此前“事件各项预算已批准”是任务前置条件，不是实际数值授权；有界reader/生产配置待具体确认，已明确的协议归属校验可独立推进。
- **剩余实现**：Linux/Windows仍全文件逐行读取/行游标；字节增量reader、字段/schema与深度限额、半行/UTF8跨块、并发替换与实际资源上界仍待。R02仅证明已见文件身份/缩短的静态边界，同inode内容覆盖未证明，不把去掉readAllLines调用当成有界读取。JSON整数/文本类型硬化已有R08证据，不重复搭同类复现；原R16/R20冻结产物不当作完整当前v2事件产品。11全量/必要真实reader验收留到交付，未关闭N2/DEF-06。
- **需求/设计**：RQ-R03/AC-R03、RQ-R05/AC-R05/T-R03、T-R05，支撑RQ-R04；N2、DEF-06；D §4.4、§6.2、§9.2。
- **依赖**：09、10；事件各项预算已批准。
- **设计目标/预期效果**：读取成本随增量变化，内存受配置限制，重复/恶意/截断事件不导致伪结果或统计回放。
- **实施/文件**：共享文件reader、Linux/Windows adapter、SandboxTaskEvent、SandboxEventIngestor；文件身份+attempt+字节offset，有限尾行，UTF8跨块；单行/批字节/批条数/总量/深度限额；类型、归属、序号、case、摘要一致性验证。
- **严格测试**：SandboxEventIngestorTest、provider测试、新增BoundedRunnerEventReaderTest；半行/无换行/中文跨块、超长/海量/非法JSON/深度、错任务/attempt、重复/乱序/缺号、文件缩短/替换、矛盾SUMMARY；断言字节/缓冲/磁盘上界，全量。
- **严格验收**：无readAllLines/全文件collect，同attempt截断受控失败且保留已接收统计，不重置回放；新attempt重置有明确身份，伪摘要不得覆盖可信聚合。
- **提示词**：你是流式协议工程师，执行 TASK-11。半行等待不报错，完整非法行不能静默吞；序号去重不能重复计数；资源上界用实际计数证明。按 §2记录并提交。
- **提交**：`fix(events): TASK-11 bound and validate incremental ingestion [TASK-11-C01]`。

### TASK-12：显式轮询状态、预算与退出drain

- **状态/角色**：BLOCKED（11整体前置及轮询/收尾预算未批准）；并发编排工程师。已修失败句柄归属、宿主轮询错误及无句柄未知启动的运行占用保留，未交付、不标DONE。
- **未知启动保留**：[TASK-12-UNKNOWN-START-R02]；JudgeService仅用本次startRequested布尔值，在已调用start且未获句柄时复用原cleanupPending，保留全局/owner占用并阻止后继派发；实际源码写入在启动前失败则不保留，无新状态框架/接口/数值。原throw/null两参数证实running错误归零；修复后编排18/18、零失败/错误/skip、退出0，原AC统计与清理失败保留不退化。仅进程内保守隔离，真实未知启动对账、持久归属/进程重建及回收释放仍由12/14/19闭环。
- **实际修复（TASK-12-R03）**：复用完整SandboxRunHandle作为本地失败缓存键，同/不同judge及attempt不覆盖；失败事件只消费一次，取消只释放本句柄缓存、后续轮询不访问虚构Worker run。运行中HTTP/解码/空值批次错误走既有宿主异常收尾，不再伪造runner事件或序号，InterruptedException恢复中断标记。保留v2身份、原失败wire值和幂等接口，无新状态框架。
- **关键验证**：原R01五项全红，R02三套51/51；新增最小修复后R03 RemoteWorkerRunnerTest31、RemoteWorkerTlsTest18、JudgeSandboxOrchestrationTest9，共58/58、退出0、零失败/错误/skip，XML关键方法实际执行。真实隔离回环HTTPS验证错误响应/合法请求，不冒称外部Worker部署或实际资源回收。
- **角色任务预算切片**：[TASK-12-ROLE-BUDGET-R01]；真实30秒角色突破8秒总预算的红测已修：编译/执行复用原monotonic和maxTaskRuntimeMillis，原执行器timeout取剩余任务预算，不误判TLE/编译失败，不改取消优先、参数或权限。Python定向10/10；当前Runner真实契约11/11，预算exit21/8.172秒且保留此前AC/WA，零失败/错误/skip。8秒仅夹具，未新增生产数值或通用框架。
- **本地读流错误切片（2026-10-07 +08:00 / 2026-10-06 UTC）**：[TASK-12-LOCAL-READ-R01]；Linux/Windows复用原宿主异常收尾，不再将文件/JSON读取失败伪造为缺归属和序号的Runner事件；公开错误消息不附加私有数据，cause保留。四个参数化错误路径均执行通过，恢复合法v2首条事件后游标未误推进；受影响四套91项/89通过/0失败错误/2原环境skip，退出0。真实文件读取与解析、容器命令替身，不冒称平台容器验收；剩余状态/drain/deadline阻断不变。
- **剩余阻断**：ACTIVE/EXITED/UNKNOWN、退出末批drain/flush、宿主启动/poll/drain/cleanup的单调总deadline与传输错误有限重试仍未完成；当前传输错误仍立即宿主失败关闭，缓存释放/cleanup调用不代表真实执行域已回收。不得据此关闭DEF-03或代替11/14/25/26验收。
- **需求/设计**：RQ-R02/AC-R02/T-R02，联合RQ-R03/R05；DEF-03；D §4.3/4.4、§6.2。
- **依赖**：11。
- **设计目标/预期效果**：空批不早退，进程退出不丢末批事件，UNKNOWN不假成功，启动至收尾全程有界。
- **实施/文件**：SandboxRunner及全部实现/fake、JudgeService编排、取消令牌；返回events/state/exitCode/cursor/hasMore；ACTIVE可取消等待，EXITED有限drain/flush，UNKNOWN有限退避，单调总deadline覆盖外部调用；统一幂等清理接口与残余记录，不另造权威summary。
- **严格测试**：JudgeSandboxOrchestrationTest、SandboxRunnerContractTest、三个provider测试；可控时钟模拟空→编译→空→结果→完成、延迟启动、最后批/flush、半行永不完成、无终态退出、Worker卡住、取消/预算竞争；断言轮询数、deadline、清理次数，全量。
- **严格验收**：无忙循环、无空批break、无无限HTTP/睡眠/清理；可信终态只接受一次，正常退出零码也不替代协议终态。真实全链路由25/26再验收。
- **提示词**：你是并发工程师，执行 TASK-12。空事件不是退出，UNKNOWN不是成功；所有等待受剩余预算约束，保留末批并拒绝晚到结果。别新增多写者终态，按 §2留证提交汇报。
- **提交**：`fix(runner): TASK-12 model polling and bounded exit drain [TASK-12-C01]`。

### TASK-13：在线输出限制与有界产物

- **状态/角色**：BLOCKED（12前置未交付、有限drain与资源验收未完成）；运行时资源工程师。Python在线捕获、Java合计预算及各角色OLE映射已修，未交付、不标DONE。
- **最小修复（2026-10-06 UTC / 日志2026-10-07 +08:00）**：[TASK-13-COMBINED-R03]；复用run_process按stdout+stderr合计判断，恰等合法，超限返回前缀合计不超过原额度；不改超时/内存/取消优先序和编译64KiB旧值。真实验收暴露OLE无法成为宿主终态，补齐原JudgeStatus枚举及OLE别名，沿用原状态机/持久化/清理，不另建管理器。
- **前阶段验证（历史）**：合计4097/限额4096误AC及OLE终态缺失先后得到真实红测；R03四套153/153及Python22/22通过。后续源码变动受影响部分由下条新证据替代，未受影响持久化/清理验证复用，失败历史保留。
- **最新闭环（2026-10-06 UTC / 日志2026-10-07 +08:00）**：[TASK-13-STREAM-R02]；Python原函数并行read1/喂输入，锁内共享合计及前缀预算、累计饱和，首超额调用原终止器，不再communicate全收集；Java原捕获器共享原maxOutputBytes预算且保留maxErrorBytes次限额，不改接口/默认值/超时或清理机制。Python两套24/24及新增IO故障定向1/1；真实C++持管道超限→OLE→v2入库Artifact12/12；Java独立合计红测六参数两失败后，ProcessRunner15+Baseline44=59/59，包含内存/文件输出limit±1及恰等、真实NUL→WA。均零失败/错误/skip、退出0，标准报告见work同键，无全量/冻结/旧Probe重复。
- **阶段OLE闭环（实际日志2026-10-07 +08:00 / 2026-10-07 UTC）**：[TASK-13-STAGE-R02]；生成器超限保留有界输入且不派发USER/答案/SPJ，答案/SPJ超限均记OLE；编译诊断仍按退出10/COMPILE_ERROR契约，但明确超限原因和角色、不伪造用例。Java答案/SPJ复用原失败分类。真实红测6项5失败后，Python两套27/27、Artifact16+Baseline46=62/62，零失败/错误/skip、退出0；四角色真C++与真g++诊断经v2验真/入库，未重复旧Probe/取消红测。非OLE的SPJ基础设施分类另归16，不扩13。
- **捕获故障闭环**：[TASK-13-CAPTURE-IO-R03]；DirectProcessRunner复用原私有捕获结果及checked异常，IO失败立即调用原终止器，不再伪造OLE；等待错误与真实超输出区分，TLE/MLE/OLE优先保留。两参数原码全红；R02受影响64项63通过/1新增公开状态拼写断言失败，按原System Error契约修正并加强磁盘SYSTEM_ERROR断言后，R03仅真实C++新增项1/1，其余63项复用，零非预期skip。真实IO错误计systemError=1/OLE=0，不冒称全域回收；其余阻断不变，未交付。
- **剩余阻断**：Python杀后wait及pump.join仍无界，后代持管道不能据此宣称清理成功；实际RSS/磁盘上界及适用平台验收未闭环。12的退出/drain预算、14的进程域治理和生产诊断额度仍按原卡，不新增默认参数；C01未提交，DEF-07不关闭。
- **需求/设计**：RQ-R06/AC-R06a/T-R06、RQ-V01；DEF-07、关联N5；D §4.5、§8.2、§9.1/9.2。
- **依赖**：10、12；DEC-01/07额度与合计规则批准。
- **设计目标/预期效果**：双流在线计数，首超额字节触发停止，监督器与磁盘不随任意输出增长。
- **实施/文件**：task_runner.py与实际ProcessRunner实现、结果映射/配置；stdout/stderr并行drain，有限preview/spool，覆盖生成器/答案/候选/SPJ及编译诊断，Java/Python同语义。
- **严格测试**：ProcessRunnerTest、TaskRunnerArtifactContractTest、JudgeBaselineTest及实际Python suite；limit-1/limit/limit+1、纯stderr/stdout/交替双流、输入/诊断超限、输出与超时竞争；受控单进程测RSS/磁盘/preview，监督器存活，下一用例可用；全量。
- **严格验收**：恰等合法，超限稳定OLE、不误AC，不能communicate全收集后检查，不能把无限内存转成无限临时文件；恶意后代强回收由14联合验收。
- **提示词**：你是运行时工程师，执行 TASK-13。按生效限额生成真实超限样例，双流持续排空，不能提高限额掩盖缺陷。保留阶段范围与峰值证据，按 §2完成提交汇报。
- **提交**：`fix(runtime): TASK-13 enforce streaming output budgets [TASK-13-C01]`。

### TASK-14：全进程域治理与有界回收

- **状态/角色**：BLOCKED（12/13完整前置、目标域及清理预算未收敛）；OS/沙箱资源工程师。已修已返回句柄的统一收尾与未确认停止的容量保留，整域真实验收及恢复释放未完成，未C01、不标DONE。
- **最小收尾切片**：[TASK-14-TERMINAL-CLEANUP-R03]；JudgeService将原句柄清理移入finally，正常AC/取消/预算与异常都收尾；清理失败不覆盖真实终态/统计，复用原cleanupPending保留运行占用及完整句柄。SandboxRunner默认取消返回不再假报清理成功，显式报未确认停止；只有不启动OS域的LocalFake及原高容量替身显式本地清理。七项原码全红；R02六套96项95过/一旧v1替身失败，最小补其v2句柄/序号后R03仅该项1/1，其余95项复用，均无错误/skip。真实回环HTTPS五种取消应答均不能替代停止确认；100/10000/100000例仅原模拟容量链路，不冒称真实Worker或OS域资源验收。
- **后端归属检查**：[TASK-14-PROVIDER-GUARD-R02]；Linux/Windows/Remote取消入口拒绝空值和外来provider，外部命令/HTTPS请求前失败关闭，防止历史换配句柄送错后端。九参数原码全红；三套85项83过、零失败/错误、2环境skip（Linux缺默认镜像、Windows运行域为Linux），九项安全回归均执行通过；不冒称同provider代际验证或真实整域回收完成。
- **剩余实现/验收**：真实主平台父退出/setsid/持管道整域停止、有限drain及残余PID/句柄为零，stage/holder/volume回收和回收后许可释放仍未闭环；默认未确认清理保守保留，不从取消应答、终态或空列表推导回收成功。无句柄启动UNKNOWN及fallback未区分已知未启动/未知由12接续，进程重建/就绪由19接续；不得据此关闭DEF-08。
- **既有待收尾（来自TASK-10）**：可信取消已经合作停止并回收活USER阶段；Runner仍准确报告stage/holder/volume恢复pending，受管证据阻止目录删除。用现有持久身份、锁和剩余预算完成整域资源回收，真实确认后才能释放占用；当前v1卷删除未实现，不从cancel marker或空列表推导删除授权。
- **需求/设计**：RQ-R06/AC-R06b及AC-R06a/T-R07、T-R06；DEF-08、联合DEF-07；D §4.1/4.3/4.5、§6.1。
- **依赖**：12、13；目标域和清理预算批准。
- **设计目标/预期效果**：父退出/子脱组/持管道都不绕过限时，清理所有后代且不会无限二次等待。
- **实施/文件**：provider/runtime启动和terminate、执行句柄、cleanup；Linux进程组+cgroup/等效域，Windows Job Object kill-on-close且禁breakaway/等效能力；deadline覆盖drain/join/wait，清理失败持久记录并保留占用，能力不足失败关闭。
- **严格测试**：ProcessRunner/artifact/provider测试及真实域IT；父先退出子持管道、孙进程/setsid、编译子进程、取消/超时/超输出、监督器异常、重复清理；外层watchdog下实测耗时不超deadline+grace+批准容差、残余PID/句柄零；全量。
- **严格验收**：至少目标主平台真实恶意进程域实验通过，mock terminate/根PID消失不合格；未验收平台不声称支持，最终矩阵由26复核。
- **提示词**：你是OS工程师，执行 TASK-14。killpg单独不证明恶意后代可控，父退出仍必须清理；不允许二次communicate无限等，不把残留清理失败当容量已释放。按 §2记录真实实验并提交。
- **提交**：`fix(sandbox): TASK-14 reap complete execution domains [TASK-14-C01]`。

### TASK-15：普通比较黄金向量与Java/API精度

- **状态/角色**：BLOCKED；数值正确性/后端API工程师。01/09真实提交已满足；2026-10-06已批准的整数/非有限文本切片完成最小修复：整数符号/前导零规范化后精确比较，NaN/Inf按大小写敏感文本比较。R01向量23项8失败，R02受影响基线32/32、零失败/错误/skip，含两例真实C++误判→WA。token字节/指数上界及非法输入错误映射仍待具体批准，未提交、不代表完整15或DEF-12关闭。
- **词法/空白切片（2026-10-06 UTC / 日志2026-10-07 +08:00）**：[TASK-15-LEXICAL-R02]；原比较器按已批准六种ASCII空白提取token，不再trim吞掉NUL/其他控制字符；仅十进制词法进入原数值路径，Java专属hex及f/d后缀按文本精确比较。原共享fixture增加11条，R01有7条词法向量及真实C++ NUL输出误判AC，共8失败；最小修复后仅重验Baseline44/44、零失败错误skip、退出0，34条共享向量和真实NUL输出→WA→文件详情保留字节通过。无新比较框架、任意精度运算或业务参数。
- **剩余实现**：有限小数/指数仍走旧double比较，API/页面precision仍有double/parseFloat损失；严格UTF8、解析预算及输入错误映射未闭环。本轮不在预算缺失时启用无界BigDecimal/BigInteger运算；跨语言验收仍归16，15未交付、不标DONE。
- **需求/设计**：RQ-C01/AC-C01、AC-C02/T-C01、T-C02；DEF-11、DEF-12、DEC-03；D §5.1/5.2、§4.2、§8.1。
- **依赖**：01、09；词法、空白、非有限规则、输入错误映射与解析上界批准。
- **设计目标/预期效果**：Java不再double误判大整数/NaN，precision从API接收起不丢精度，统一可移植判定向量。
- **实施/文件**：新增共享JSON fixture与比较器，JudgeService普通比较、JudgeRequest/参数校验/策略、index精度输入；整数精确、十进制绝对误差、文本规则、数字长度/指数限额；保留旧JSON数字兼容，区分缺省0/null/显式0，页面1e-9不暗改。
- **严格测试**：JudgeBaselineTest及新增比较/请求契约测试；2^53邻整数、NaN/5、5.0000000001/5@1e-9、空白/中文/空串/CRLF/Unicode空白、边界误差、负零/指数、非法编码/超长数/负精度/null/缺省；API→policy→spec无损和SPJ独立回归；全量。
- **严格验收**：同一fixture Java全部通过，非法参数创建前拒绝；不得double舍入后再stringify冒充精确，不暗加相对误差；跨语言关闭留16/25/26。
- **提示词**：你是数值工程师，执行 TASK-15。黄金向量用十进制字符串，语义由DEC-03决定；完整区分API缺省与页面默认，避免parseFloat先损精度，不改变SPJ。按 §2闭环。
- **提交**：`fix(compare): TASK-15 preserve exact numeric semantics [TASK-15-C01]`。

### TASK-16：Python/Worker跨路径比较贯通

- **状态/角色**：BLOCKED（12、14、15整体前置未交付）；Python/跨语言契约工程师。仅完成下列独立异常映射切片，未提交、不标DONE。
- **SPJ最小修复**：[TASK-16-SPJ-R02]；Python run_special_judge的非SUCCESS基础设施状态按原spec/Java契约记System Error，不再误记WA；取消/预算先仲裁、OLE单独分类和SUCCESS非零退出仍为WA的原协议保持不变。仅补两行条件，不新增管理器、数值或比较模式。
- **关键验证（实际日志2026-10-07 +08:00）**：同一既有Artifact夹具参数化真实SPJ成功、拒绝、超时、内存超限，R01四项两失败证明误WA；R02受影响Artifact20/20及Python StageDispatch16/16，零失败/错误/skip、退出0。真C++/Python、v2事件验真和入库均执行，标准XML确认四参数及原四角色OLE/编译诊断实际执行；Java未变化证据复用。未做Worker整链、跨语言精度或真实域清理，不能据此关闭DEF-11/12。
- **需求/设计**：RQ-C01/AC-C01、AC-C02/T-C01、T-C02，RQ-R01能力要求；DEF-11、DEF-12；D §4.2、§5.1/5.2、§8.3。
- **依赖**：08、12、14、15。
- **设计目标/预期效果**：普通比较各支持路径相同，精度/模式不静默丢失，未知能力拒绝而不是退回trim比较。
- **实施/文件**：task_runner.py、v2 adapter、RemoteWorkerRunner与实际Python测试；复用15的fixture，使用可证明精确的系数/指数或足够Decimal精度并拒绝舍入；词法、限额、异常映射同Java；外部Worker变更列明外部依赖。
- **严格测试**：TaskRunnerArtifactContractTest、SandboxRunnerContractTest、RemoteWorkerRunnerTest、已有/新增Python suite；同向量Java/direct和真实Python逐条对比，缺模式/精度/版本拒绝，高精度/缺省0/显式0/1e-9贯通；全量。
- **严格验收**：生成向量ID×路径×版本×结果矩阵，无默认Decimal舍入；外部平台未验收不能算通过，声明启用的由26真实补证，不支持者fail-closed。
- **提示词**：你是跨语言工程师，执行 TASK-16。不复制易漂移的向量，不以Python默认Decimal证明精确；缺Worker能力拒绝，不能伪造仓库外实现。按 §2记录矩阵、提交和汇报。
- **提交**：`fix(compare): TASK-16 align runner comparison contract [TASK-16-C01]`。

### TASK-17：单一权威终态与崩溃一致提交

- **状态/角色**：BLOCKED（11/12前置未交付，完整交付验收未完成）；后端持久化/一致性工程师。终态字段/旧数据兼容已批准，原子final及定向恢复切片已验证；未交付、不标DONE。
- **原子final闭环（2026-10-08）**：[TASK-17-ATOMIC-FINAL-R07]；保留JudgeProgress全部字段并加attemptId/revision/finalizedAt，复用原存储锁提交唯一final；内容force、同卷原子替换、POSIX目录force，终态不回退普通移动。Publisher/调度停止入口不再先写终态metadata；final优先冷读，投影失败可恢复，旧有效summary保持且不从事件补final。R05定向47/47并复用R03未受影响200项；nobody真实HTTP/C++9/9及四种STOMP4/4，零失败错误skip。取消3/2/1、预算17/9/8，final/HTTP/冷读及真实AC的WS整快照相等；失败与夹具修正历史见work。首次取消IO失败保持CREATED，原ID重试提交final，非伪终态。仅终态revision=1，不冒称完整23或Windows/provider/整进程重启验收。
- **宿主失败摘要**：[TASK-17-HOST-ERROR-R02]；原作业异常/执行器拒绝仅写metadata，冷读优先summary导致缺摘要或仍RUNNING。两个原失败入口复用原停止摘要，保留实盘聚合/样本/进度及首个已存消息，不覆盖其他已存终态；无结果才真实零兜底。R01五参数2过/1失败/2缺摘要错误；最小修复后六个相关套件150/150、零失败错误skip，四个作业异常参数及拒绝分支实际执行，真实文件落盘/冷读与既有HTTP-C++停止/许可/摘要回归通过。不是原子final、缓存/WS或崩溃一致性验收，完整17仍BLOCKED。
- **最近切片（2026-10-07 +08:00 / 2026-10-06 UTC）**：[TASK-17-STOPPING-R02]；执行中取消/预算定时器只发令牌，不提前终结；取消不再写100%无摘要缓存，调度收尾不改已有终态，必要兜底保留持久聚合。R01受影响60/60；测试夹具调整后R02调度器17/17、真实HTTP/C++4/4，零失败/错误/skip，复用R01其余43项。真实混合AC/WA后取消completed=3/ac=2/wa=1，超预算17/9/8；样本、firstFailedCase、停止原因、磁盘与清缓存HTTP一致。仅trusted-local直接执行证据，不冒称WS、重启或所有provider验收。
- **终态不可覆盖/失败不伪报**：[TASK-17-TERMINAL-SNAPSHOT-R04]；复用原任务锁冻结首次已存终态的metadata/message/finishedAt/events和summary，发布与缓存使用真实已存快照；写盘失败传播且不发送成功，原错误清理移至finally。八参数原码全红；R03七套186项184过/2相关边界错误，强化不存在任务的异常断言且保留不得创建/启动原断言后R04编排18/18，其余168项复用、均零skip。真实C++取消3/2/1、预算17/9/8，样本与冷HTTP逐字段相等。未新增字段/接口/管理器；metadata/summary仍非原子final，完整崩溃一致性/兼容批准与WS验收未完成，不交付17或关闭DEF-21。
- **需求/设计**：RQ-L03/AC-L03/T-L04，R §4可靠性；DEF-21；D §6.2、§4.4、§8.3。
- **沙箱切片**：[TASK-17-SANDBOX-R02]；停止摘要改为现有可信聚合快照，同Session首个终态后重复/晚到回调不再写统计。R01三套76/76；必要真实Python/C++取消与预算验收exit20/21，均completed=2、AC=1、WA=1、首错2、宿主样本1/2，冷读逐字段一致；R02受影响Runner11/11。预算红测按12最小修复，未新增采样额度、终态字段或管理器；仍非完整17/DEF-21关闭。
- **依赖**：11、12；终态字段与旧数据兼容方案已于2026-10-08批准，其余预算/平台批准不由此推定。
- **设计目标/预期效果**：调度器不再用零统计覆盖真实结果，终态只提交一次，缓存/文件/重启读取相同权威快照。
- **实施/文件**：复用FileTaskStore/TaskStore现有任务锁及JudgeService/JudgeScheduler聚合/进度入口，不另建通用生命周期框架；任务互斥冻结summary，final临时写+flush+原子替换，metadata/summary投影可重建；拒绝晚到事件，真实摘要优先，只有无结果才零兜底。
- **严格测试**：FileTaskStoreTest、JudgeSchedulerTest、ResultAggregatorTest、SandboxEventIngestorTest、ProgressPublisherTest；AC/WA/样本后取消或预算超限、重复/相冲终态、晚到RUNNING；final写前/替换后/投影前故障，缓存清除/模拟重启/真实临时文件系统恢复；全量。
- **严格验收**：分类和等于completed，样本/firstFailedCase/stoppedReason保留；final写失败不发布已持久成功，投影失败可重建；不声称多个JSON具有数据库事务，旧有效summary不清空。
- **提示词**：你是持久化工程师，执行 TASK-17。终态单写者，scheduler只发信号不能重造结果；必须注入每个提交边界故障，证明原子快照与读优先级。不要顺便换数据库，按 §2提交汇报。
- **提交**：`fix(lifecycle): TASK-17 commit authoritative final snapshots [TASK-17-C01]`。

### TASK-18：CREATED取消/TTL与幂等启动

- **状态/角色**：BLOCKED；任务生命周期/API工程师。CREATED首次/重复取消、已调度重复start、终态start409、执行器交接及请求侧15分钟TTL已修；14/17前置、独立TTL扫描/清理预算及完整三方竞态/持久化验收仍未闭环，未交付、不标DONE。
- **实际修复（TASK-18-R01）**：复用JudgeScheduler既有monitor，在与enqueue同一锁内确认CREATED并持久化CANCELLED和真实零已执行摘要后才accepted=true；不分配执行槽、不运行代码。复用原TaskContext/摘要逻辑，运行中统计实现不扩改；JudgeService成功取消后释放pending并由持久摘要回答CREATED状态，重复取消accepted=false，不复活任务、不把其他任务占用误报成本任务running/queued。
- **真实绿测**：TASK-04 R07的既有红测不重跑、不放宽。R01六套79/79、退出0、零失败/错误/skip；CreatedTaskCancellationHttpTest实际Cookie+CSRF完成创建→首次取消true/CANCELLED→读取total=1/completed=0→重复取消false/CANCELLED。调度器回归证明取消后不能入队、没有执行或槽位，原运行取消/预算及跨账户拒绝仍绿；不冒称浏览器、三方竞态、原子final或实际运行域清理验收。
- **关联缺陷已修（TASK-18-R03）**：R28的执行器交接拒绝及既有确定性Probe不重跑。AsyncConfig仅为已获运行许可任务配置同现有并发数的有界交接缓冲，线程数/业务队列上限及AbortPolicy不变；Scheduler在同一锁内释放本任务并预留FIFO后继，避免新请求抢越旧队列。R02六套67/67，包含实际C++ RE原断言及Baseline32项；四参数向量用真实生产执行器验证并发1/2、队满重试、取消、FIFO和线程/队列上界，不以扩大线程或CallerRuns掩盖。
- **启动语义补齐**：JudgeService pending缺失时只验证真实已调度任务，不重建执行；Scheduler锁内拒绝任何已持久化终态，Controller仅映射专用异常到409，保留200/429/授权隐藏。R03六套37/37、退出0、零失败/错误/skip：真实Cookie+CSRF取消→晚到start409/CANCELLED→重复取消false；受控Running重复start只启动一个Runner，原真实RE、权限及有界交接仍绿。原Baseline比较向量未变化直接复用R02，不重做全量/冻结；证据与剩余边界见work [TASK-18-R03]。
- **排队取消竞态 [TASK-18-QUEUED-CANCEL-R03]**：原取消出队与终态落盘锁外窗口已有两参数红测；只在已有monitor内完成令牌、持久化及出队，写盘失败保留队列供重试，运行/回收占用策略不改。R02五套56项51过/5因Policy共享根遗留CREATED触发正确安全拒绝；仅复用现有隔离TaskStore修正该夹具，R03受影响Policy/真实HTTP-C++10/10，其余46项复用，当前56项有效绿色、零失败错误skip。三新调度回归实际执行；不冒称TTL三方竞争、原子final或全域停止，14/17及未批准预算仍阻断。
- **交接窗口取消**：[TASK-18-HANDOFF-CANCEL-R02]；仅在原runTask开始及调用作业前复核现有取消令牌，已收到取消的预留/晋升任务不再调用作业，沿用原取消摘要、finally及FIFO/owner许可交接。直接预留/队列晋升×执行前/启动落盘后四参数原来全部误调用一次；修复后五个受影响套件60/60、零失败错误skip，四参数实际执行，真实HTTP取消/晚到start409及C++取消、预算、owner许可、摘要回归通过。仅闭合已观测交接窗口，不代替TTL三方竞态、原子final或运行域停止证明，完整18仍BLOCKED。
- **请求侧TTL [TASK-18-TTL-REQUEST-R03]**：沿用已批准CREATED TTL15分钟，在原调度monitor内于start/cancel检查到期；仅未调度、无startedAt/运行句柄的CREATED转STALE并复用零已执行摘要。晚到start409，cancel返回false/STALE，JudgeService释放pending及通道缓存；不启动/停止Runner或增加扫描器。14/15/16分钟×两入口红测6项2过/4失败，修复后Scheduler41/Store67/Orchestration18/CreatedHTTP3/Policy5的R02共134项及Quota13/RunningHTTP5的R03共18项均通过（152项有效绿色、零失败错误skip）；真实Cookie/CSRF/HTTP证明两入口STALE、无启动句柄、pending释放且同owner配额可复用。R02另5项启动错误源于Quota共享根遗留未终结任务，仅两行复用现有IsolatedTaskStore隔离该夹具后重验受影响18项，不放宽恢复门禁、不删目录。独立扫描、无请求空闲回收及完整三方竞态未验收，DEF-13及完整18仍BLOCKED。
- **终态取消重试 [TASK-18-TERMINAL-PENDING-R02]**：摘要落盘失败但metadata已CANCELLED时，原ID重试返回accepted=false却遗留pending；JudgeService仅复用原终态枚举清理该任务注册，不改运行许可、授权或终态写入。R01单项红测失败；R02编排20+调度41及nobody真实HTTP4共65/65，零失败错误skip。真实文件故障首次500，恢复空目录夹具后重试200/false/CANCELLED、pending释放、metadata不变、无启动句柄、晚到start409；摘要仍缺失，未伪造补齐，权威提交/恢复仍归17，完整18仍BLOCKED。
- **需求/设计**：RQ-L01/AC-L01/T-L01、RQ-U01恢复契约；DEF-13、DEC-02/08；D §6.1、§6.2、§7.1、§8.1/8.2。
- **请求侧并发观测 [TASK-18-REQUEST-RACE-R01]**：原编排套件只补14/16分钟两项start/cancel/requireScheduled屏障竞争，2/2、零失败错误skip、退出0；实测分别CANCELLED/STALE、completed=0/Runner启动0，pending与调度占用归零，其他CREATED注册不变，重复cancel/start不改变metadata/summary字节。Runner为既有替身，不是独立扫描、全部竞争顺序或真实OS域验收，完整18仍BLOCKED；上轮65项有效结果复用。
- **依赖**：14、17；CREATED TTL15分钟及重复调用语义已批准，扫描间隔/清理预算仍待具体批准。
- **设计目标/预期效果**：创建未启动不永久占用，同任务启动/取消/过期竞争只产生一个合法转移，网络重试不重复执行。
- **实施/文件**：JudgeService pending/start/cancel、JudgeController、TaskCleanupService、状态配置/DTO；CREATED可取消、TTL转STALE，独立扫描；start已运行/排队幂等200，终态按批准409；已终态取消按批准accepted语义返回原快照，无权/不存在仍404。
- **严格测试**：JudgeSchedulerTest、FileTaskStoreTest、TaskCleanupServiceTest、JudgeControllerPolicyTest、QuotaAndAuthorizationTest；三方屏障竞争start/cancel/TTL、创建后刷新/不启动、重复start/cancel、队满429后原任务重试/取消；时钟驱动TTL，全量。
- **严格验收**：不存在取消后又执行/重复入队，pending与占用清理一致；运行中accepted不是已清理，终态提交后才完成取消；双响应格式与授权隐藏语义不变。
- **提示词**：你是生命周期工程师，执行 TASK-18。DEC未批准先阻断；不要把CREATED问题通过自动重跑解决。用任务互斥处理竞态，取消复用17真实摘要，按 §2实测提交。
- **提交**：`fix(tasks): TASK-18 close created-task lifecycle [TASK-18-C01]`。

### TASK-19：覆盖全部非终态的重启恢复

- **状态/角色**：BLOCKED（14/17/18完整前置及全部恢复验收未闭环）；恢复/运行可靠性工程师。已修已知句柄回收失败的启动门槛及状态保留，未C01、不标DONE。
- **启动失败关闭**：[TASK-19-READINESS-R02]；TaskCleanupService复用原扫描/清理入口处理全部已存句柄（含非运行状态和已有终态），有失败先报错、不先写STALE；PostConstruct异常传播，不能仅警告后继续初始化。七状态原码全红；修复后恢复12、清理14、真实HTTP1共27/27、零失败/错误/skip、退出0。实际Spring初始化失败/上下文不活跃，metadata、句柄、状态及case数据保留；Runner失败仍为原替身注入，不冒称真实域已回收或进程重启已验收。
- **全非终态及未知执行**：[TASK-19-NONTERMINAL-R02]；复用isTerminal覆盖五非终态，已知句柄清理返回后记录STALE/RESTART_INTERRUPTED；先预检所有缺句柄任务，非终态（含CREATED/QUEUED）、已有startedAt或受管证据均失败关闭，空runId不再跳过清理。15项原码全红；存储64、清理14、恢复26、真实HTTP1共105/105、零失败/错误/skip。真实Spring/磁盘验证五状态及再次初始化字节稳定，回收仍为原替身或无OS域LocalFake，不冒称产品进程重启/真实整域验收。
- **孤立受管目录**：[TASK-19-ORPHAN-EVIDENCE-R02]；原loadAllTasks扫描复用受管证据校验，无metadata但有启动锁/attempt时抛错并阻止就绪，普通无受管证据的未完成创建仍保留原行为。原三参数1过/2失败；修复后存储64、清理14、恢复29、配额13共120/120，零失败/错误/skip，真实Spring/文件验证证据不删除、不伪造metadata或猜执行身份。
- **剩余实现/验收**：UNKNOWN权威对账与持久占用重建、final修复/统计一致及真实进程重启未闭环；缺句柄非终态或已知受管孤立目录目前保守阻止就绪，不自行推定未启动或释放占用。14实际停止证明和17权威终态依赖仍未完成，未发现文件不能替代完整运行域对账，不把已有终态当资源停止证明。
- **既有待收尾（来自TASK-10）**：复用LinuxAttemptDiscovery、stage闭门与CONFIRMED容器消费者；所有非终态及创建UNKNOWN仍须在真实运行代际/权威终结依据下收敛，无依据则继续容量隔离。不得用临时实验域结束冒充产品重启恢复。
- **需求/设计**：RQ-L01/AC-L01/T-L02、RQ-L03统计保留；DEF-14、DEF-13、关联DEF-21；D §6.1/6.2、§8.3、§9.2。
- **依赖**：14、17、18。
- **设计目标/预期效果**：CREATED/QUEUED/PENDING/COMPILING/RUNNING全部收敛，不重跑，不遗留可占额度的孤儿任务。
- **实施/文件**：FileTaskStore恢复、TaskCleanupService、就绪门槛、持久句柄/终态入口；先校验final修投影，回收残余执行，再无final非终态STALE+RESTART_INTERRUPTED；保留有效统计，清理失败重试与容量隔离，不接新流量。
- **严格测试**：FileTaskStoreTest、TaskCleanupServiceTest、ProductionCleanupTest；五非终态参数化，final存在/缺失/坏投影、句柄缺失/失效/回收失败、反复重启；真实临时storage进程重启测试验证不再执行旧源码，全量。
- **严格验收**：同目录重启结果稳定，残余清理不靠一天后TTL；清理失败就绪不报健康/可用容量，无PENDING/COMPILING遗漏；终态/真实摘要不倒退。
- **提示词**：你是恢复工程师，执行 TASK-19。全部非终态从统一枚举处理，不能继续只查RUNNING/QUEUED；日志警告不等于清理成功。先安全收敛再接流量，按 §2记录故障实验并提交。
- **提交**：`fix(recovery): TASK-19 reconcile every nonterminal state [TASK-19-C01]`。

### TASK-20：用户原子创建配额与每日计量

- **状态/角色**：BLOCKED（18/19整体前置未满足）；并发/配额工程师。已完成创建竞态、失败回滚、最小每日计费及已保留运行占用的创建配额切片，未完整验收、未交付、不标DONE。
- **创建切片（2026-10-07 +08:00 / 2026-10-06 UTC）**：[TASK-20-CREATE-R02]；QuotaService固定条带锁按原stableUserId归属，JudgeService原子区覆盖原配额检查、CREATED落盘和pending注册，不持锁执行编译/网络。四参数8会话争最后1额度，原码成功8/8/6/6导致4项红；修复后每项仅1成功、7个原429，真实磁盘和pending只新增成功任务，管理员不豁免、另一用户仍可创建。定向四套27/27、退出0、零失败错误skip，含既有真实Cookie/CSRF/TCP创建取消验收；未更改生产配额或每日计量口径。
- **创建落盘回滚切片**：[TASK-20-ROLLBACK-R02]；FileTaskStore仅接管本次原子新建目录，已有目录/任务失败关闭且不覆盖；元数据move或事件append失败时，在原任务锁/lifecycle lease内复用原安全删除路径，清理本次目录、保留外部任务和storage锁身份，同ID可重试。managed证据仍拒绝删除，原失败携带suppressed回滚失败，不假报回收；无新管理器/权限/数值。五项原码全红，修复后受影响10套130/130、退出0、零失败错误skip，含现有真实HTTP与Python/C++→宿主落盘验收；未关闭完整20。
- **回滚身份自审补齐**：[TASK-20-ROLLBACK-R03]；捕获创建时judgeId，失败路径不再读取可变输入对象决定删除目标；实际落盘失败接缝中变更输入ID仍只回滚原目录，另一运行任务逐字节不变。R03只重验存储54及真实HTTP1，共55/55、零失败错误skip、退出0；复用R02其余76项，不重跑Runner/全量/冻结，剩余阻断不变。
- **pending注册切片（2026-10-06 UTC / 日志2026-10-07 +08:00）**：[TASK-20-PENDING-R02]；原create/lease内执行同步pending注册，注册前/后RuntimeException走同一落盘回滚；putIfAbsent拒绝覆盖已有注册，失败只移除本次同一实例，等值旧条目也保留。保留原create单参契约，无新事务管理器/数值/REST接口。三参数原码全红；修复后五套84/84、退出0、零失败错误skip，实测失败不留本次CREATED/pending、不占最后1个queued额度，可同ID重试且仅1条事件；既有真实HTTP创建取消通过。
- **每日计费切片（2026-10-06 UTC / 日志2026-10-07 +08:00）**：[TASK-20-DAILY-R03]；成功创建生成提交标识，安全删除前原子保留当日最小计费凭据，失败创建回滚不计费；原stable owner仅保留SHA指纹，不复制会话凭证。live/retained同提交去重、同judgeId重建另计，损坏/符链/写失败拒删；沿用系统日期和请求case/估计runtime及legacy默认策略，累计饱和防溢出。凭据过期复用原配额查询/定时清理，无新TTL或调度框架。两参数退款红测后，R02七套114/114；接入清理后R03仅重验三套20/20，复用其余95项，均零失败错误skip、退出0，含真实Cookie/CSRF/TCP删后404及再创建429、真实磁盘TTL/冷读/日期参数回归。
- **已保留占用的创建配额**：[TASK-20-HELD-QUOTA-R02]；创建原owner原子区内读取Scheduler可信owner的运行ID快照，与磁盘运行ID合并去重；终态但清理失败仍拒绝同owner创建，另一owner不受误伤，成功清理可创建，同ID不双计。只复用原monitor及局部Set，不另建许可表/锁/管理器。原三项回归2过1红；最小修复后八套128/128、零失败/错误/skip、退出0，含现有真实Cookie/CSRF/C++四任务AC。清理失败仍为Runner替身注入；回收后释放及进程恢复归14/19/21，不冒称闭环。
- **剩余实现/验收**：18/19完整生命周期、进程重启及残余执行/占用重建仍未闭环；本次冷读不冒称完整进程恢复。启动429/重试链路及任务交付全量待前置收敛后验收，本地C01未交付。进程崩溃及回滚遇managed/路径/IO问题的残留不得当作已清理；21的实际派发用户许可留原任务。此切片不关闭完整DEF-15，不做冻结或重复旧Probe。
- **需求/设计**：RQ-L02/AC-L02/T-L03；DEF-15；D §6.3、§8.1、§9.2。
- **依赖**：18、19；每日计量口径按原契约保留。
- **设计目标/预期效果**：同用户并发创建不能越额，创建失败不遗留占位，成功创建的每日额度不因取消/启动重试错误退款或重收。
- **实施/文件**：QuotaService、JudgeService创建、FileTaskStore提交边界；固定条带用户锁覆盖检查/预留/落盘/pending注册，失败回滚仅本次创建；按稳定owner计数；daily请求case/估计runtime和系统日期不改为实际消耗，回收不得重置当日计费。
- **严格测试**：QuotaAndAuthorizationTest、FileTaskStoreTest、JudgeControllerPolicyTest；屏障竞争最后额度、多用户互不干扰、落盘/pending失败、日期边界/重启重建、重复start/取消/TTL不退款，管理员无隐式豁免；全量。
- **严格验收**：成功数<=额度，失败无CREATED/pending/预留残留；已创建start429仍保留queued/daily且不重收；固定锁不新增无界Map，create时running满拒绝语义不偷改。
- **提示词**：你是配额工程师，执行 TASK-20。只给assertCanCreate加锁不够，原子区必须覆盖提交；创建失败回滚与启动失败保留必须分开。保持每日请求预算口径，按 §2证明竞态与提交。
- **提交**：`fix(quota): TASK-20 reserve task creation atomically [TASK-20-C01]`。

### TASK-21：调度准入的用户运行许可

- **状态/角色**：BLOCKED（20整体前置未交付，全部执行域停止确认及回收后占用恢复未闭环）；调度/并发工程师。两级准入、已知句柄清理失败保留、预算及执行器拒绝收尾切片已修复、验证，未C01交付、不标DONE。
- **执行器拒绝/落盘失败**：[TASK-21-REJECT-WRITE-R02]；只在原execute拒绝分支为终态落盘加finally释放，未接纳job时写盘失败不能泄漏全局/owner预留；异常仍传播，失败状态保持QUEUED，不冒称终态已持久化。原两参数1过1红（running仍1）；修复后Scheduler24/24、零失败/错误/skip、退出0，同owner双会话后继能取得预留、job调用0。正常执行/真实CPP及已知清理失败保留直接复用BUDGET-R02，拒绝分支之外不重跑。
- **预算提交拒绝**：[TASK-21-BUDGET-R02]；scheduleBudget移入原runTask的try/finally，复用已有空Future及异常终态入口；预算执行器拒绝时job未调用，安全释放本实例的全局/owner预留并派发后继，不假造已启动域的停止确认。原单项红测退出1；修复后调度22、编排14、真实HTTP/C++1共37/37、零失败/错误/skip、退出0，四真C++均AC，原清理失败占用仍保留。创建识别该占用见20的HELD-QUOTA-R02；完整14/19恢复阻断不变。
- **最小修复**：[TASK-21-ADMISSION-R03]；原monitor内的runningTasks同时充当全局/用户预留，立即执行与出队共用canStartLocked，按可信任务的稳定owner快照及原maxRunningTasksPerUser限额计数；匿名和缺ownership旧任务保守同组。出队选择最早可运行任务，跳过满额owner而不阻塞其他用户；重复释放同一实例不再派发。无用户计数Map、许可管理器、新锁、默认数值或公共接口变更，创建时running满仍拒绝的业务不变。
- **前阶段验证（2026-10-07；历史）**：账户跨会话、用户限额1/2、匿名/旧数据四参数先四项越额红测；最小修复后真实生产ThreadPoolTaskExecutor和FileTaskStore验证两级峰值、双入口、队首公平及同任务只执行一次。R02七套62项60过/两项旧私有构造反射夹具错误；仅适配夹具并重验Orchestration13/13，另49项绿测复用。多用户真实C++由下条补验，完整DEF-15不关闭。
- **真实C++闭环**：[TASK-21-CPP-R05]；R04 Cookie/CSRF/TCP验收发现全局2/每任务Case1仍共享单线程Case池，另一用户10秒内无法开始。仅修AsyncConfig原Case池容量为原全局任务上限×每任务Case上限，乘法溢出失败关闭；默认全局1行为、原队列/拒绝策略及CaseBatchRunner每任务在途限额不变，无新参数/抽象。R05 HTTP5+Batch9=14/14、零失败/错误/skip，四个真C++任务全部AC；同账号双Cookie共享1任务许可、两账号USER程序同时存活、跳过满额队首，旧同owner PID退出后新PID才出现，末尾实际调度占用归零。仅trusted-local/Linux证据，不代表恶意全域清理、清理失败保留或重启验收，20/14/19剩余阻断及C01未交付不变。
- **需求/设计**：RQ-L02/AC-L02/T-L03；DEF-15；D §6.3、§6.2、§9.2。
- **清理失败保留**：[TASK-21-RETENTION-R02]；已有poll异常红测证明cleanup失败仍running0。仅在原TaskContext标记已知清理失败，原runningTasks释放入口拒绝移除该实例，不另建锁/许可表；保留完整持久化句柄及全局/owner占用，后继不能取得新许可。R02五套58/58、零失败/错误/skip，成功/失败两参数与真实Cookie/CSRF/C++四任务AC实际执行；正常释放、取消/预算及公平出队不退化。该回归的清理失败由原Runner替身注入，不冒称真实域清理或回收后许可恢复，未知start、正常Runner终态统一清理及14/19/20边界仍待闭环。
- **依赖**：20。
- **设计目标/预期效果**：全局并发再高也不突破用户上限，所有退出路径只释放一次，单用户满额不阻塞其他用户。
- **实施/文件**：JudgeScheduler立即执行/出队、QuotaService许可、TaskLifecycleService/清理回调；固定锁顺序或单调度临界区取得全局+用户许可，不持锁做编译/网络；仅实际执行域停止后释放，清理失败保留占用；支持跳过暂不可运行队首。
- **严格测试**：JudgeSchedulerTest、QuotaAndAuthorizationTest、ProductionCleanupTest；全局>用户上限、同用户多任务/多用户公平、executor拒绝、编译失败/正常/取消/预算、重复回调、清理失败/重启恢复、死锁超时；全量。
- **严格验收**：实测峰值不超两级上限，无负许可/重复释放/假回收；无任务获许可却未进入可追踪状态，队满429行为一致。
- **提示词**：你是调度工程师，执行 TASK-21。用户限制必须在实际派发而非只在创建检查；回收资源之前不能释放运行许可。用并发屏障和所有失败注入证明，按 §2闭环。
- **提交**：`fix(scheduler): TASK-21 enforce per-user execution permits [TASK-21-C01]`。

### TASK-22：前端错误恢复与Toast可见性

- **状态/角色**：BLOCKED（05/06/18整体前置未交付、必要真实验收未完成）；前端/交互测试工程师。源码已补原任务重试/取消、通道退出及提示兜底，未C01、不标DONE。
- **最小修复/验证**：[TASK-22-START-R02]；复用原Toast DOM和showSuccess/showError，只将节点移至初始化脚本前，保留样式、转义与ARIA。原连接函数仅建立连接/注册订阅，启动POST收拢到原submit单一位置并检查response.ok，HTTP拒绝进入原失败按钮收尾，不再因连接降级重复启动或进入轮询；提交前显示准备中而非运行中。R01模板21/21；补启动修复后仅重验R02模板22/22，零失败/错误/skip。这些是源码契约回归，不代替浏览器可见性/网络交互证据。
- **原ID恢复**：[TASK-22-RETRY-R04]；只在原currentJudgeId上标记待确认/启动，重试先查status，仅CREATED才再次start，已运行/终态只恢复查询；无ID创建响应未知明确提示、不自动重发。补JSON Accept以获得真实401，区分HTTP错误与请求阶段，查询429不误报尚未启动；刷新CREATED可重试，晚到start接受不覆盖已收到终态。R03/R04契约各29/29，主脚本语法及429三阶段消息函数直接执行通过，不替代浏览器网络证据。
- **取消/通道**：[TASK-22-LIFECYCLE-R06]；原ID取消复用现有Cookie/CSRF接口并查询权威状态，accepted不等于已停止。原连接/轮询内加入实例及ID检查，切换/失败/终态/退出关闭旧通道，轮询串行、可abort，沿用原1秒间隔及现有重连退避/次数；Toast组件缺失用原DOM/textContent可见兜底。R05共32项31过/一项旧完整代码块断言失败，仅扩入真实QUEUED文案分支并单项R06通过，其他31项复用；语法检查通过，未建调度/测试框架。
- **节流尾帧**：[TASK-22-THROTTLE-R07]；原300ms helper增加cancel并接入stopProgressTracking，新帧先取消旧延期，终态复用isTerminalProgress立即处理。既有三项实际JS红测（切任务旧尾帧污染、终态延迟、过期尾帧逆序）全部转绿；受影响FrontendTemplateContractTest 33/33、零失败/错误/skip，主脚本语法通过。纯函数与模板报告不替代真实浏览器/DOM验收，不重测未变后端。
- **真实浏览器切片**：[TASK-22-BROWSER-R03]；复用现有classes、隔离临时存储与模板固定版本静态资产，用WSL Chromium149.0.7827.55（Playwright1.61.1、nobody、沙箱开启）真实验收。6/6检查通过、pageErrors=0、脚本退出0：访问码200/success=false可见且不建会话；空代码错误Toast不发create；真实创建后丢响应仅1次create/0次start、无虚构ID，刷新不重发；owner查询CREATED并实际取消；显式再提交后仅1次start、固定良性C++三例AC、4条真实SockJS/WS进度与HTTP整终态相等、成功摘要可见；真实登出后受保护HTTP401且浏览器返回登录页。R01历史协议误判及R02刷新后隐藏表单/孤立Promise夹具失败保留，仅修夹具，不改产品或业务断言。证据target/task22-browser-r03.json及对应截图；不冒称完整22/24验收。
- **真实队满/原ID重试**：[TASK-22-START-RETRY-R04]；真实容量1的队满429保留CREATED/原ID并关闭通道。R03查明旧异步页面等待未等到占位任务完成，二次429不是产品重试缺陷；仅修夹具为有截止的真实HTTP等待。R04两项全PASS、pageErrors0、退出0：占位任务真实AC后仅1次create/2次同ID start，原任务一例真实AC，HTTP/WS整终态相等、成功Toast可见、连接峰值1/最终0；target/task22-start-retry-r04.json及截图。产品未变，已有受影响回归复用，不重复全量/冻结。
- **网络未知/对账恢复**：[TASK-22-NETWORK-RECOVERY-R03]；真实start已返回200后丢响应，首次status也注入断网，保留原ID并可见提示未知、不重发create/start。R02真实红测发现对账为运行中后旧待启动取消按钮仍显示；只在handleProgress已有已接受状态入口隐藏该按钮，补原契约方法断言。受影响模板33/33、零失败错误skip；R03两项全PASS、pageErrors0、退出0：显式重试只查询/恢复轮询，1次create/1次start、一例真实AC，HTTP与浏览器整终态相等，查询并发峰值1、终态后停止，WS归零。target/task22-network-r03.json及截图；仅网络故障注入，不冒称服务端5xx证明。
- **WS不可用/CSRF刷新恢复**：[TASK-22-WS-CSRF-R01]；复用原产物和验收入口，两场景各2项全PASS、pageErrors0、退出0。WS故障为明确网络注入：既有有限6次连接尝试后仅1次真实start，此前服务端CREATED/页面准备中且不轮询；HTTP降级一例真实AC、整终态与浏览器相等、单轮询终态停止。CSRF由真实服务端拒绝无效头403，原ID保持CREATED/提示可见/旧WS关闭；刷新不自动create/start，显式同ID重试403→200、一例真实AC、HTTP/WS整终态相等、连接峰值1/最终0。target/task22-ws-fallback-r01.json与target/task22-csrf-recovery-r01.json及截图；未改产品、不重复已有回归或冻结。
- **断线/终态冲突**：[TASK-22-DISCONNECT-CONFLICT-R02]；现有入口disconnect/conflict各2项全PASS、pageErrors0、退出0。真实已连接WS客户端关闭后同ID HTTP降级，一例真实AC、整终态一致、查询并发峰值1/终态停止；不冒称服务端故障。真实owner先取消CREATED后start409，页面查询CANCELLED、不自动重启；显式新提交使用不同真实ID并AC，HTTP/WS整终态一致，连接峰值1/最终0。conflict R01仅夹具硬编码终态文案失败，保留退出1报告；R02改为匹配实际响应文案，不放宽业务断言。target/task22-ws-disconnect-r01.json、target/task22-conflict-r02.json及截图；产品未改，已有定向结果复用。
- **HTTP错误恢复切片**：[TASK-22-HTTP-ERRORS-R02]；原入口参数化覆盖create401/503、start401/404/503、status401/404/503。R01创建两场景4项通过后，夹具未切回代码页导致按钮可见性断言失败，退出1报告保留；最小修夹具，R02只跑其余六场景12项全PASS、pageErrors0、退出0，已通过创建证据复用。401/404为实际匿名/不同owner Cookie及CSRF请求的真实服务端拒绝，不冒称真实会话撤销；503为明确HTTP注入。八场景均真实C++一例AC、浏览器/HTTP整终态一致、原ID重试或404显式新建无自动重发、轮询峰值≤1/终态停止、连接峰值1/最终0。target/task22-http-errors-r01.json、r02.json及四张关键截图；产品未改，不重跑33项契约/全量/冻结。
- **真实会话恢复/旧提示清理**：[TASK-22-SESSION-RECOVERY-R05]；账户模式真实登出后下一次出站关闭旧WS、HTTP401停止跟踪并保留代码/原ID；同账户真实重登录仅查询原任务，不再create/start。R04核心两项通过，但实看截图发现恢复AC后仍显示旧401；仅showSuccess增加一行复用原错误Toast关闭控件，现有fallback契约补断言。受影响33/33、零失败错误skip；R05两项全PASS、pageErrors0、退出0，真实C++一例AC、原页面401→200/查询峰值1/终态停止、连接峰值1/最终0、旧错误隐藏。target/task22-session-recovery-r05.json及截图；R01～03夹具等待/异步计数失败保留，不放宽业务或权限断言、不全量/冻结。
- **持续查询失败/限流恢复**：[TASK-22-STATUS-RECOVERY-R02]；原浏览器入口参数化补创建429及持续查询429/503/断网，HTTP拒绝与网络失败均明确注入。R01前三场景6项通过，持续查询断网真实页面却误报“启动结果未知”；仅在原消息函数补查询阶段文案，原模板契约补断言。受影响33/33、零失败错误skip；R02只复验失败断网分支，2项全PASS、pageErrors0、退出0，先前6项有效结果复用。查询失败按既有5次上限退避并停止/关闭通道，原ID和输入保留；显式重试只查原任务取得真实C++一例AC，create/start=1/1、查询峰值1、终态停止、旧错误隐藏。target/task22-status-recovery-r01.json保留红测，r02.json及两张绿测图已实看；不全量/冻结、不新增框架。
- **连点/晚到响应/运行中刷新**：[TASK-22-INTERACTION-R02]；R01真实双击仅1次create/start，WS先于被延迟的真实启动200取得C++ AC，一项PASS。之后夹具额外等待Response.finished未推进；专项批准核对私有PID/cgroup后SIGTERM其浏览器，退出1报告保留，只移除该多余等待。R02复用连点证据，仅续验其余三项，全PASS、pageErrors0、12.858秒退出0：旧启动200晚到不污染新任务ID/缓存/按钮；刷新真实COMPILING任务保持输入/原ID、只恢复鉴权查询，无重复create/start；原任务真实AC、HTTP/浏览器整终态相同，查询峰值1、连接峰值1/最终0、终态停止。target/task22-interaction-r01.json、r02.json与完成图（已实看）；产品源码未改，最新33项及其他有效证据复用，不构建/全量/冻结。
- **剩余阻断/验收**：浏览器基础能力已通过真实沙箱启动及上述应用切片解除，不再列“环境不可用”；CUA空列表、旧IAB/Edge失败历史仍保留，未重试或安装浏览器。双模式/多身份其余矩阵、其余创建/启动持续故障及网络未知边界、其余刷新/任务切换与重连/计时器交付回归仍待执行；上述已通过场景直接复用，05/06/18与C01门槛不变，DEF-16/17不关闭。
- **需求/设计**：RQ-U01/AC-U01/T-U01、T-U02；DEF-16、DEF-17；D §7.1/7.2、§8.1。
- **依赖**：05、06、18；批准幂等响应。
- **设计目标/预期效果**：HTTP失败不会伪装运行，错误与成功可见，原任务可重试/取消，通道/计时器不重复。
- **实施/文件**：index.html小型HTTP helper/UI状态机/DOM初始化；create/start/status检查状态与业务值，429保留ID；已知ID网络未知先status，create响应丢失无ID则明确未知且不自动重发；WS降级轮询有退避/停止；toast DOM就绪与可见兜底。
- **严格测试**：FrontendTemplateContractTest加真实浏览器；200/401/403-CSRF/404/409/429/5xx/断网、创建响应丢失、WS失败/重连、双击/刷新/任务切换；断言消息可见、按钮正确、一任务最多一轮询和有效订阅，全量。
- **严格验收**：start发出不等于运行，retryAfter文本不当数字；保留原ID重试可完成，未知create不虚构查找API；文字仍转义，无整页重写，辅助技术可感知提示。
- **提示词**：你是前端工程师，执行 TASK-22。不能只用源码字符串证明toast修好，不自动重复create，不把WS连接成功当start成功。真实浏览器验证并按 §2记录实际效果、提交汇报。
- **提交**：`fix(ui): TASK-22 recover failed starts and visible feedback [TASK-22-C01]`。

### TASK-23：运行摘要与有界进度缓存

- **状态/角色**：BLOCKED（17/21/22前置及缓存容量/TTL未冻结）；后端实时进度工程师。已完成direct运行摘要、保序和重复轮询缓存移除切片，未交付、不标DONE。
- **实际修复（TASK-23-R03）**：JudgeService仅在原RUNNING发布点附带现有ResultAggregator.toSummary快照，结果回调/节流/统计源不变，不带全量results；复用原HTTP缓存和WS发布入口，不新建聚合器或消息框架。N8附录40例无摘要的真实红证据直接复用，不重跑旧红测。
- **必要验证**：R01五个未受夹具影响套件54项全绿；两项真实HTTP初因未发送Accept而误读兼容纯文本ID，R02因夹具filesystem不兼容既有C++14而失败，均保留，未改产品/放宽断言。仅修夹具后R03两项真实40例AC/WA全绿、退出0、零失败/错误/skip，实际Cookie+CSRF与C++执行观察首例completed=1，后续计数单调、分类和等于completed、样本不超过测试冻结3/2、无全量results，终态completed=40；54项报告runLog已核验并直接复用。
- **终态回收闭环**：[TASK-23-THROTTLE-RELEASE-R01]；复用原ConcurrentMap.compute序列化同judge的持久快照与节流决策，终态删除本键，不因重复/晚到或在途RUNNING重建永久条目；活跃任务及原节流保留，不新增TTL/容量数值。发布器17+真实HTTP4=21/21、零失败错误skip、退出0；六终态/别名各32任务、并发/发送故障已验证，真C++的AC/WA各40例及取消/预算本键均释放，原统计/冷HTTP相等断言保持。
- **发送顺序最小修复**：[TASK-23-SEND-ORDER-R02]；原发布器在compute外发送，旧RUNNING可能被新终态抢越。仅将broker发送调用移入已有同judge的compute串行点，持久化先于对应通知，终态随后释放节流键，发送异常仍告警；不新增锁、队列、管理器或生产参数。受控并发AC/取消/系统错误顺序回归及原套件20/20、真实HTTP5/5；四入口40例真实C++/STOMP最终4/4，运行态无全量results、计数不倒退/分类和相等、HTTP/WS最终整快照相等。R01新夹具错误地禁止了非运行态兼容results，29项25过/4失败；仅纠正断言适用范围后R02四项通过，原业务/统计断言不放宽，25项复用。
- **目录清理接入**：[TASK-23-CACHE-CLEANUP-R01]；原TaskCleanupService在FileTaskStore确认deleteTaskDirectory=true之后调用发布器删除本键；false/IOException/受管证据拒删均保留，不清其它活任务，不新增扫描、TTL或容量参数。旧构造入口保留，生产Spring入口接入现有发布器。发布器20、清理18、生产清理29、真实HTTP创建取消1，共68/68，零失败错误skip、退出0。四参数覆盖真实临时目录删除、false、异常和证据保留，迟到发布失败不能重建键、重复清理幂等；不据此声明进程域或受管卷已回收。
- **出站保序闭环**：[TASK-23-OUTBOUND-ORDER-R02]；原发布器调用已串行，但默认clientOutboundChannel仍可抢越。产品仅在WebSocketConfig启用现有Spring setPreservePublishOrder(true)，无新队列实现/管理器或生产参数。复用原四transport/40例真实C++夹具，在既有出站执行器阻住首个非空RUNNING，待真实HTTP达到40 AC后检查后帧未抢越，再释放并保留原计数单调、分类和、无运行态全results及HTTP/STOMP整终态相等断言。R01四参数全红；R02四参数全绿、零失败错误skip，native/SockJS WS/XHR streaming/polling均实跑。不伪造终态；框架保序缓冲/积压上界仍待预算验收。
- **轮询权威源最小修复**：[TASK-23-AUTHORITATIVE-POLL-R02]；受控并发红测证实原JudgeService重复缓存会被晚到RUNNING覆盖：磁盘AC/completed=1，查询却RUNNING/completed=0。仅删除judgeStatusMap及其投影/60分钟延迟任务，HTTP统一复用既有TaskStore摘要与metadata兜底；direct/沙箱发布、授权和持久化契约不变，不新增锁、管理器、TTL或revision字段。R01单项红测报告保留；R02受影响编排19/发布器20及nobody真实HTTP-C++5，共44/44、零失败错误skip。实际40例AC/WA、取消completed=3/ac2/wa1、预算completed=17/ac9/wa8，完整统计与文件/冷HTTP相同；原两级运行许可及队首跳过仍通过。标准报告target/surefire-reports/*-task23-cache-r02.xml；不代表完整23/DEF-21/22关闭。
- **剩余阻断**：17/21/22完整前置、非终态TTL/容量、有限重试/发送与出站积压预算、完整非终态revision仍缺；受管目录删除依然按14/19/28保留证据与批准门槛。同会话broker进度出站串行已有R02定向证明，HTTP/重连交错、框架缓冲容量及发送阻塞上界未证明；不冒称生产provider、浏览器或完整N8/DEF-22关闭，未C01、不标DONE。
- **需求/设计**：RQ-U02/AC-U02a、AC-U02b/T-U03、T-U04，RQ-L03；DEF-22、N8、关联DEF-21；D §7.2、§6.2。
- **依赖**：17、21、22。
- **设计目标/预期效果**：运行中HTTP/WS看到同源摘要，历史任务不会永久留节流状态，终态即时且不可被晚到消息覆盖。
- **实施/文件**：JudgeService direct路径、ResultAggregator、ProgressPublisher、进度DTO/前端revision；快照复制、有界样本、不传全results；终态/清理回收，发送失败有限重试/TTL，去重不保留无限历史，终态拒绝晚到非终态。
- **严格测试**：ProgressPublisherTest、ResultAggregatorTest、SandboxEventIngestorTest、HighVolumeJudgeIntegrationTest；40例首例后摘要、HTTP/WS同revision、最后RUNNING completed=total合法、终态绕节流、重复/晚到/断链、海量短任务后容量回落；全量。
- **严格验收**：分类和=completed、计数不倒退，终态与文件一致，缓存数量受活跃量/批准TTL容量约束；发送失败不损持久化，可通过查询恢复。
- **提示词**：你是进度工程师，执行 TASK-23。复用聚合器，不建第二套统计；不能为了去重永久保留任务，也不能禁止合法最后RUNNING统计。按 §2提供容量与一致性证据并提交。
- **提交**：`fix(progress): TASK-23 publish bounded snapshots and release state [TASK-23-C01]`。

### TASK-24：真实HTTP、WS与浏览器安全闭环验收

- **状态/角色**：BLOCKED（02～08、18～23整体前置未交付，独立最终矩阵未执行）；独立安全测试 + 浏览器E2E工程师。
- **环境事实**：[TASK-22-BROWSER-R03]已证明缓存Chromium沙箱及真实隔离应用可运行，访问码/Toast/丢响应/良性C++/SockJS终态/登出切片复用其报告；不重跑旧CUA/IAB/Edge失败路径，不将单模式切片当本任务交付。
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
- **既有验收边界**：TASK-10的Linux开发隔离、四角色负例及可信取消证据可复用；真实完整编排、资源释放和生产配置仍在本卡验收。当前productionSafe/control-plane探针失败关闭，不从实验root UID推导生产身份或启用许可。
- **需求/设计**：RQ-R01～R06、RQ-C01、RQ-L01～L03；T-R01～R07、T-C01/C02、T-L01～L04；DEF-02/03/05/06/07/08/11/12、N2；D §4～6、§9。
- **依赖**：09～21、23；授权的Linux容器测试环境就绪。
- **设计目标/预期效果**：证明生产Linux路径真实可运行且可信控制/资源边界有效，不由trusted-local成功推导容器安全。
- **实施/文件**：真实镜像构建与digest记录、受控攻击fixture/脚本、Linux验收报告；存储根不同于容器根，使用拟上线安全profile、非root/网络禁用/资源限制。
- **严格测试**：真实编译判题/WA/CE/OLE/TLE/取消、空轮询/末批、四角色控制文件/FD攻击、事件截断/伪摘要、双流超限、孤儿/setsid、共同比较向量；确认网络与权限探针，不运行无外层预算的历史大攻击。
- **严格验收**：完整链路正常，恶意程序无法污染宿主控制面，资源耗用在预定阈值、残余为零；镜像/代码/环境与证据可复现。若DEC-07明确Linux不在发布范围，只能完成显式禁用负例并注明未验收，不能标Linux通过。
- **提示词**：你是Linux独立验收工程师，执行 TASK-25。mock Docker参数不证明隔离，需真实镜像与身份/进程域攻击；外层watchdog先部署。逐缺陷留证，按 §2提交并报告平台适用范围。
- **提交**：`test(sandbox): TASK-25 verify real linux isolation [TASK-25-C01]`。

### TASK-26：Windows与Remote Worker能力矩阵验收

- **状态/角色**：BLOCKED；跨平台/远程Worker集成验收工程师。已按DEC-07落实Windows/Remote生产失败关闭；完整08～21/23及发布/部署验收尚缺，未交付不DONE。
- **已验证切片 [TASK-26-DISABLE-R02]**：Windows/Remote均DISABLED_UNVERIFIED；原启动验证器对非trusted-local的provider及执行profile双向拒绝，未知/空/交叉profile、probe开关、自报安全能力不能绕过，且验证器不调用禁用provider探针。认证、Worker配置与Linux真实安全探针保持；trusted-local仅开发可用。R01五套141项140过/1构造注入失败、零错误skip；只补Windows配置构造Autowired后，R02完整真实应用windows-prod/worker-prod启动均以DISABLED_UNVERIFIED拒绝，2/2、零失败错误skip，其余有效结果复用。不冒称Windows artifact/外部Worker部署通过，保留原平台验收边界。
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
- **阶段环境提示**：[TASK-23-AUTHORITATIVE-POLL-R02]真实测试的只读cwd使默认logs/judge-system.log未创建，控制台/XML结果有效但不能作文件日志验收；离线Maven前缀解析提示clean3.4.1/jar3.4.2描述符未缓存，不等于已完成打包。本项仍NOT_STARTED；最终验收使用已批准可写日志路径并核对实际打包依赖，不为补日志重跑已有效业务测试。
- **既有待查（R14）**：JAVA-SUPERVISOR-R14故障注入报告“unreachable CLI changed actual container state”，未保存足以定位差异的四份原始快照；R15/R16后续成功不关闭该历史风险。核对故障不变量与状态观测边界，不放宽归属/安全断言。
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
- **部署参数门槛**：需具体冻结生产writer/supervisor/workload UID-GID、运行代际及专属域/终结权限；实验UID0/65532不作默认，不停止共享Docker/containerd，不伪造删除授权。此部署缺口不阻止已验收的TASK-10隔离实现本地提交。
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
