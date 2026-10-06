# TASK-10 开发与验收记录

最新检查点（2026-10-06）：可信marker、合作退出与Runner/Discovery接入已完成，锁内marker发现竞态已修；原TASK-10隔离开发验收达到VERIFIED_PENDING_COMMIT，提交定位TASK-10-C01。最新事实以docs/task.md及docs/work.md为准，下列轮次保持历史，不表示全项目或生产验收通过。

当前交付边界：STAGE-R02真实四角色/编译器控制面负例和正常执行，R16真实同JVM取消及新JVM容器恢复，CANCEL-CONTROL-R03定向470项/469过/0失败错误/1旧镜像skip、核心零skip。R28全量Java2400过/1既有TASK-18失败/2环境skip、Python142/142；受影响两模块以R03补验，不重复有效实验。完整资源回收、UNKNOWN收敛及生产参数分别归14/19/25/28，R14差异归27；受管占用/两卷/控制证据保留，productionSafe仍失败关闭，DEF-05/N2待整链关闭。

## 批准与阶段边界

2026-10-03，用户在TASK-10独立执行单元方案及环境确认请求后明确回复“批准”。据此确认可信监督器位于不可信执行单元之外、分阶段最小输入、隔离scratch、控制面不暴露的架构方向。没有将该回复解释为TASK-03/15参数批准或现有共享Docker可直接用于攻击测试。

本阶段先修复已确认的失败关闭漏洞：Linux外围探针及人工capabilityProbePassed标志不能证明可信控制面隔离。仅封住未隔离旧执行路径的生产入口，不宣称完整独立执行单元已实现，也不替代真实Linux身份/挂载/权限攻防验收。

## R01：原实现失败回归

先修改两个既有单元契约断言：外围限制均满足但同身份/整根挂载的Linux adapter不得productionSafe；Linux生产启动不得仅凭配置探针已通过而放行。测试使用受信命令替身，不运行容器，不是实测权限隔离。

计划命令：使用TASK-00既有受信Maven外层约束、全新unit wjc-task10-r01和新证据目录R01，定向执行LinuxContainerRunnerTest#outerContainerLimitsDoNotProveTrustedControlPlaneIsolation与ProductionSecurityStartupValidatorTest#linuxProdRejectsConfigurationOnlyCapabilityApproval。实际argv、环境、退出码、计数及输入哈希以R01采集为准；运行前不得写通过或失败计数。

实际结果：R01退出1，2套件/2项、0通过/2失败/0错误/0跳过。原Linux probe实际返回productionSafe=true，原Linux生产启动没有抛异常；不是编译失败或缺环境造成的红基线。证据保存于R01/baseline-summary.json及同轮输入/harness哈希，不覆盖。

## 首轮未完成门槛（R01历史）

独立可信监督器、阶段执行后端、持久runId归属/取消与恢复、安全产物交接、所有角色权限/FD/信号/进程负例、完整隔离实现的全量回归及本地提交均未完成。专用临时运行时已通过ENV-R15/R16良性预检，执行工具链镜像已在BUILD-R05从锁定官方APK断网构建并完成固定可信C++17编译/运行，见build.md；这不等于完整runner镜像或生产平台通过验收。

## R02/R03：失败关闭阶段回归（非完整任务验收）

| 轮次 | 范围 | Java总数 | 通过 | 失败 | 错误 | 跳过 | 退出码 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R02 | 7套件定向 | 116 | 115 | 0 | 0 | 1 | 0 |
| R03 | 全量42套件 | 389 | 387 | 0 | 0 | 2 | 0 |

R02套件：LinuxContainerRunnerTest、ProductionSecurityStartupValidatorTest、ProductionAuthenticationTest、AuthenticationSafeDefaultsTest、SandboxRunnerContractTest、SandboxExecutionManifestTest、JudgeSandboxOrchestrationTest。R03还实际执行Python unittest 11/11、零跳过，见R03/python-summary.json；不与Java总数重复相加。R02没有调用独立Python suite，不报告该轮Python通过。

实际改动：SandboxCapabilities新增缺省false的controlPlaneIsolated；当前共享工作目录Linux adapter明确不隔离、不productionSafe，artifact自称能力不能覆盖。start在任何prepare/manifest写入/运行时命令之前拒绝非精确trusted-local。Linux生产启动通过Spring注入选定runner并实际probe，缺bean/null/异常/不一致能力全部失败关闭，人工passed/required四种组合不能覆盖；异常message/cause不泄露探针秘密。其他provider未在本阶段获得新增安全背书。

测试保留原开发路径、协议/源码校验、manifest、事件及取消断言，只将旧成功合同明确标为trusted-local；新增非本地profile拒绝且四条宿主审计不变、无control/workspace创建/无命令调用。启动测试包含真实Spring装配与生命周期，但能力数据仍是明确命名的测试替身，不是容器安全证明；认证fixture只隔离本次新依赖，不放松认证规则。

R02的1项及R03的2项skip原因由当前实际报告核验：Linux缺cpp-judge-runner:latest镜像；Windows runtime是Linux。即使补齐镜像，当前共享控制面实现仍不得报告productionSafe；不能通过更改assumption把未实现隔离算通过。TASK-08历史RE/SYSTEM_ERROR偶发问题本轮未再现，根因仍未定位，不据本轮全绿关闭。

R02/R03各150个源码/runner/scripts/POM哈希、174个完整输入哈希及harness均匹配当时工作树，两轮清单相同；phase-one-hash-verification.json保存核验。核验后仅补充阶段文档与task/work/change-log，不改被测源码。系统服务的Memory peak不是JVM实际峰值，不作性能验收；受信Maven外层仍未隐藏宿主Docker socket，不可复用成攻击隔离证明。

审核：主代理集成及阶段证据核验；只读explorer复核adapter/DTO/合同和startup失败关闭路径，未发现阶段范围内阻断问题，未独立重跑测试。并行worker仅负责启动校验与3个关联测试文件，未提交。该协作代码审查不是TASK-29独立安全放行。KISS/DRY复用现有probe与Spring装配，未添加可手工放行的新配置、框架或业务额度。

## 专用环境方案（已批准，良性预检已执行）

用户随后明确“后续的都批准”，已授权以下所提实验环境及上限；不扩大为push、生产操作、既有资源清理或未列定业务默认。已实际执行ENV-R04～R15，R15良性预检退出0，细节及失败历史见environment.md。独立运行时与宿主仍共享内核，不等于独立虚拟机，也不证明完整TASK-10已安全。

- 已批单轮外层上限：所有监督器/运行时/测试子进程合计内存4GiB、swap 0、CPU最多2核、进程512、最长600秒、临时存储2GiB；实际ENV-R07起内存主动收紧至2GiB，覆盖全部tmpfs和嵌套容器。限制真实cgroup层级，不只限制Docker客户端。
- 专用运行时使用全新临时目录、独立socket/data/exec/pid状态；不改共享Docker配置，不停止/删除/进入已有容器，不把共享socket或宿主私有目录提供给执行单元。
- 镜像构建若需要网络，仅访问明确官方镜像/软件源；执行/攻击阶段断网。不得全局安装依赖或改变宿主权限；发现需要这些操作时另行审批。
- 生命周期仅创建与清理本任务新建且校验归属的临时资源；不清理任何既有用户产物。若环境无法满足外层限制/边界，不执行攻击、不放宽标准，改为请求外部专用环境。
- 这组数值只冻结实验外层，不充当生产监督器/编译/用例资源默认值。完整实现所需部署参数必须明确配置或另行批准，不填未经批准的业务默认。

本阶段没有暂存或提交；不能把拒绝所有旧Linux生产执行替代完整TASK-10，整体仍为5/30且不可发布。

## 后续实测汇总（2026-10-03，仍未完成）

- SUPERVISOR-BUILD-R03与SUPERVISOR-CONTAINER-R04已通过独立镜像构建、真实镜像内四配置/权限负例及AC1六阶段。详见supervisor-build.md，容器化R01～R03失败保留。仅实验UID0可信身份，不代表Java调用或生产UID映射已完成。
- RECOVERY-R02已验证真实晚到create时持久UNKNOWN及新executor失败关闭，且NoCopy保持stage目录身份；实验代理终结后harness清理不是产品恢复。详见stages.md。
- R07全量Java418项415通过/1失败/0错误/2环境skip，Python118/118零skip；诊断消息Judge executor rejected the task定位调度提交拒绝分支，未修复。R06一次全绿不是关闭证据。R07/source-hash-verification.json核对163项输入全部匹配。
- 尚缺Java适配、产品持久恢复及任务目录保护、真实取消和最终全绿回归。Linux/Windows生产门禁均未放行，TASK-10未提交、DEF-05/N2未关闭。

## 目录证据保护与最新全量（R08）

FileTaskStore已补固定受管层级的删除门禁，先有CLEANUP-R01 16项预期失败，再有CLEANUP-R02 32/32零skip；无handle或旧runner清理正常返回均不丢受管证据，legacy清理保留。所有受管标记（含CLEANED）保守保留，不是自动恢复/并发冻结，详见cleanup-protection.md。

R08全量Java439项437通过/零失败错误/2原环境skip，Python118/118零skip，Maven/unit0，163份hash匹配。独立SCHEDULER-PROBE-R01已确定性复现既有零队列执行器交接拒绝；R08一次未触发不构成修复，见scheduler-rejection.md。仍未提交或放行生产。

## Java启动接入与最新全量（R10）

已实现显式监督器配置、持久runtime journal、有界CLI执行器与launcher接入；task锁及删除门禁保留新旧受管证据。create前UNKNOWN、严格inspect后CONFIRMED、start前START_REQUESTED均有磁盘合同测试。stop仅STOPPED/CLEANUP_PENDING，cancel仍报恢复未完成；不删除资源或解除生产门禁。详见java-supervisor-integration.md。

JAVA-CONTRACT-R01的2错误及R03的60项inspect类型预期失败保留；修复后R04七套件427项426过/1环境skip。最新R10全量Java817项815过/0失败错误/2原环境skip，Python118/118零skip，Maven/unit0、85.219秒；R04/R10的171份源码hash匹配。独立只读复核确认5字段类型修复，不代表真实Java容器验收。仍缺产品恢复、并发删除冻结、真实取消，TASK-10未完成/未提交/不可发布。


## 真实Java入口通过（JAVA-SUPERVISOR-R03）

R03在专用daemon/namespace/pivot_root内使用真实LinuxContainerRunner及默认有界executor，start→六阶段→poll完成AC1和六事件；独立inspect、audit/metadata/final/source/manifest/config保持不变检查通过，unit0、74.739秒。辅助合同64+13+7均通过，冻结包276资产/96jar及168份产品输入hash匹配。最新包为JVM-BUILD-R05；此前构建R01/R02及真实入口R01/R02失败保留，详见java-supervisor-integration.md。

这是trusted-local显式root实验身份的真实adapter验收，不是HTTP全链路、UNKNOWN注入、产品恢复或取消完成。supervisor/holder/volume由专用域销毁而非产品回收；生产门禁继续关闭。recovery-consumer-design.md仅是后续权威屏障/归属回收/稳定删除锁的研究。TASK-10仍IN_PROGRESS、未提交、5/30、不可发布。

## 最新：生命周期准入与R11、新版真实入口

此前R10与JAVA-SUPERVISOR-R03为历史版本结果；生命周期实现已新增稳定storage-root锁，接入Store写入/删除、Launcher短准入及JudgeService源码准备。LIFECYCLE-R05六套件245/245、零skip，含真实子JVM竞争。详细边界见lifecycle-admission.md，不能声称所有legacy文件操作已冻结。

最新全量R11为Java868项865过/1失败/0错误/2原环境skip，Python118/118；唯一失败是基线RE用例收到执行器拒绝，Maven/unit1、102.427秒。174份源码hash与生命周期版本匹配；保持全量红结论，不以此前R10绿色覆盖。

JVM-BUILD-R06的279资产/171份输入核验通过；JAVA-SUPERVISOR-R04使用新包真实start/poll、六阶段/六事件/AC1、控制文件不变及独立inspect通过，unit0、76.091秒，辅助64+13+7全过。只证明Launcher新版准入可用，不是完整产品恢复、真实cancel、HTTP全链路或生产身份验收。所有受管marker保留；TASK-10仍IN_PROGRESS、未提交、5/30，DEF-05/N2未关闭、不可发布。

## 最新：严格分类器与完成后重复停止（R13、JAVA-SUPERVISOR-R05）

新增LinuxRuntimeState，在停止前严格分类Java v1实际13种持久快照，统一Launcher/Options/State路径规则，保留43项损坏状态及4项NBSP/DEL路径红基线。RECOVERY-STATE-R04四套件956/956零skip，实际writer序列合同通过。详见recovery-state-classification.md。

最新R13全量Java1457项1454过/1失败/0错误/2原环境skip，Python118/118，unit1、109.851秒；176份源码hash匹配。唯一RE基线仍收到执行器拒绝，不宣称全量绿色或修复TASK-18。

JVM-BUILD-R07→JAVA-SUPERVISOR-R05使用最终分类器版本，真实六阶段/六事件/AC1及正常退出后的两次stop通过，unit0、71.605秒；辅助64+13+7全过、12快照hash匹配。两次均明确报告恢复待完成，保持CLEANUP_PENDING/STOPPED，控制文件和marker身份不变；独立inspect确认原两容器和卷仍在。这不是活跃负载取消、持久恢复或UNKNOWN屏障验收。TASK-10仍IN_PROGRESS、未提交、5/30，生产门禁关闭、不可发布。


## 2026-10-06接续：R18/R13（历史）

- 最新真实同现场故障恢复、范围与风险：`confirmed-container-recovery.md`第9节。
- R18新冻结包→R13七个恢复JVM/三条失败现场链接通过；R12前置计数失败保留，严格88修复后通过。原始入口`JAVA-SUPERVISOR-R13/steps.jsonl`、java七场景、`post-observations.json`及各自source-hash-verification。
- 产品未改；R26历史失败/skip保留，不是Goal/TASK-10完成，不证明活跃取消、卷连续性、UNKNOWN或完整删除。下一接续见`docs/task-handoff-2026-10-06.md`第12节。

## 2026-10-06最新接续：R19/R15真实活跃取消红测

- `active-cancellation.md`记录真实同JVM/实际flock/用户stage前后仍运行的旧产品红测；R15 unit1，不是取消通过。
- R06严格93/93，新增失败前原始快照保留回归后R07严格94/94；R14旧快照漂移未定位，原断言和失败历史保留。R19编译2/2及195源/306资产核验只是旧产品/新Probe，不替代新产品包。
- 下一步Journal/Launcher/Runner可信marker与合作退出、Discovery严格marker发现及新回归/新包/真实绿测；共享daemon不操作、预算不增加、卷/证据保留、不发删除凭据。仍5/30、未提交、生产门禁关闭。
