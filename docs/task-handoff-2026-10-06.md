# 任务交接：2026-10-06

本文件是当前接续入口，10-04交接保留为历史。更新时间2026-10-06（Asia/Shanghai）；第15节为最新断点。旧产品R19/R15取消红测已完成，当前已新增显式占位入口与取消契约测试，尚未编译/运行。上次修mock和建有界脚本的调用在解析阶段失败，二者未执行；现在从契约红测准备继续，而非重做Probe或直接宣称产品修复。

整体仍5/30、TASK-10 IN_PROGRESS/未提交、DEF-05/N2未关闭、生产门禁关闭。历史R07两入口94/94，R15辅助94+13+7通过但真实产品红测退出1；当前源码已不同于R19，尚无新Java契约测试、取消绿测或新全量。本次仅整理文档，未启动实验；完整Goal保持active。

## 1. 目标与状态

- 原始目标：C:/Users/29901/.codex/attachments/81b9031d-c4de-4390-8bd6-cd9f1d304db1/pasted-text-1.txt；完整目标说明：docs/project-goal-2026-10-04.md。
- 先读适用AGENTS.md、需求/设计、docs/task.md和docs/work.md；本快照不能替代开工时的HEAD/status/证据核验。
- 工作目录C:/Users/29901/Documents/dp/web-judge-c；HEAD为5f672c2faa9d02e2e3cd95c832ef4623bc222301（TASK-09-C01）。
- 整体5/30：TASK-00、01、02、08、09已完成；TASK-10仍IN_PROGRESS，未提交，DEF-05/N2未关闭，不可发布。TASK-03和15仍有具体参数待确认；其他任务依赖以task.md为准。
- Goal保持active。逐任务验收后独立本地提交，禁止push/amend/擅自建分支/reset或绕过钩子；本轮不具备TASK-10提交条件。
- 保留.idea/jarRepositories.xml和.idea/misc.xml，不提交；不读取或清理.mimosa/、.playwright-mcp/。

## 2. 已实现且不要重复的工作

| 组件 | 当前事实与证据 |
| --- | --- |
| 稳定文件准入 | TaskLifecycleGuard及Store/Launcher/JudgeService短准入已实现；独立JVM写入/删除竞争和晚到更新不复活有证据；不是永久ID墓碑 |
| 持久发现/分类 | LinuxAttemptDiscovery有界发现，LinuxRuntimeState严格Java v1分类；当前共19个合法快照，不是回收凭据 |
| 原生stage锁 | LinuxStageJournalGuard通过21项真实Python/JVM竞争及FD负例；已接入Java消费者，不再是“尚未接入” |
| Java闭门与stage消费 | LinuxStageRecoveryPreparation、LinuxStageEntryIo、LinuxStageRecord已落地；消费14种creation/state组合、20种形态，与Python持久闭门字节互通；STAGE-RECOVERY-R02为82/82 |
| host早期新start冻结 | STAGE-RECOVERY-R04：任何attempt残留拒绝新start，包括空/manifest-only；有意收紧legacy兼容性 |
| 真实域Java准备 | JVM-BUILD-R15→JAVA-SUPERVISOR-R09：新JVM在真实六stage产物上两次恢复准备、Python幂等复核、8入口拒绝及validator74+13+7；不是UNKNOWN屏障或资源回收 |
| 无marker残留删除保护 | FileTaskStore对attempts任何条目拒绝删除，不再只看runtime-marker；CLEANUP-R05为389/389，含独立子JVM |
| Java原子stage写回 | Lease.replaceStageRecord已实现真实FD相对原子写回；STAGE-UPDATE-R03为117/117，含66故障及18真实Python互通；不是资源回收 |
| CONFIRMED容器回收消费者 | 已实现且R09为1557/1557；R17→真实R11四个不同JVM完成正常/监督器SIGKILL后orphan回收及无操作重试，卷和控制证据保留；不是活跃取消或完整恢复 |
| stage完整ID归属 | LinuxStageContainerIdentity已实现；472项检查覆盖真实历史inspect形状、四标签/身份/限制及两层子卷挂载；不晋升UNKNOWN、不证明卷对象连续性 |
| 默认FS入口保护 | Launcher recover/既有stop及Runner recover先拒绝非默认FS、非绝对或非规范Path，再转换/resolve；真实ZIP FS三入口旧代码R04红，修复R05绿 |

历史组件见lifecycle-admission、stage-flock、stage-recovery、cleanup-protection、stage-update及其review；最新合同、红绿和真实证明限度见docs/acceptance/TASK-10/confirmed-container-recovery.md第8节及confirmed-container-recovery-review.md。原生guard持FD不让普通路径I/O自动安全；Python整个stage执行持flock，R11是监督器崩溃后遗留回收，不把它写成正在执行的活跃取消验收。

## 3. 开发轮历史与最新测试

这些是已结束开发轮的实跑结果，不是本次文档交付新运行的测试；失败与skip保留，不以重跑覆盖。

| 轮次 | 实际结果 | 单元退出 / 运行时长 |
| --- | --- | --- |
| CLEANUP-R03 | 53项，45过/8失败/0错误/0skip；空及无marker残留真实误删 | 1 / 15.149秒 |
| CLEANUP-R04 | 386项，381过/5失败/0错误/0skip；旧CleanupService期望legacy删除 | 1 / 22.421秒 |
| CLEANUP-R05 | 七套件389/389，零skip | 0 / 20.493秒 |
| R20 | Java1635项，1632过/1失败/0错误/2skip；Python142/142零skip | 1 / 100.561秒 |
| STAGE-UPDATE-R01 | 6项，3过/0失败/3错误/0skip；3成功例被占位API拒绝 | 1 / 11.455秒 |
| STAGE-UPDATE-R02 | 115/115，零skip | 0 / 16.868秒 |
| R21 | Java1750项，1747过/1失败/0错误/2skip；Python142/142 | 1 / 105.514秒 |
| STAGE-UPDATE-R03 | 117/117，含真实rename成功后确认丢失2例，零skip | 0 / 19.060秒 |
| R22 | Java1752项，1749过/1失败/0错误/2skip；Python142/142零skip | 1 / 114.450秒 |
| CONFIRMED-RECOVERY-R01 | 2项，0过/1失败/1错误/0skip；占位API及移除意图红测 | 1 / 12.856秒 |
| CONFIRMED-RECOVERY-R02 | 1446项，1444过/1失败/1错误/0skip；configuration数值节点形态比较错误 | 1 / 22.716秒 |
| CONFIRMED-RECOVERY-R03 | 八套件1550/1550，零skip | 0 / 25.005秒 |
| R23 | Java2356项，2353过/1失败/0错误/2skip；Python142/142零skip | 1 / 105.725秒 |
| CONFIRMED-RECOVERY-R04 | 真实ZIP FS三入口3/3失败、零错误/skip；别名被误解为宿主路径 | 1 / 15.381秒 |
| CONFIRMED-RECOVERY-R05 | 八套件1553/1553，零失败/错误/skip；包含三入口FS修复 | 0 / 27.171秒 |
| R24 | Java2359项，2356过/1失败/0错误/2skip；Python142/142零skip | 1 / 120.649秒 |
| CONFIRMED-RECOVERY-R06 | 2/2失败、零错误/skip；不清阶段目录却发布CLEANED/改写NOT_REQUESTED | 1 / 20.091092秒 |
| CONFIRMED-RECOVERY-R07 | 八套件1555/1555，零skip；真实语义修复 | 0 / 27.301359秒 |
| R25 | Java2361项2358过/1失败/0错误/2skip；Python142/142 | 1 / 107.726123秒 |
| JVM-BUILD-R16 | 编译/类路径2/2；195输入/306资产，不是产品验收 | 0 / 38.572710秒 |
| JAVA-SUPERVISOR-R10 | 真实正常6阶段/AC1及准备后，新JVM回收stop回显失败，证据保留 | 1 / 79.075160秒 |
| CONFIRMED-RECOVERY-R08 | 新旧stop参数两入口2/2失败、零错误/skip | 1 / 16.601649秒 |
| CONFIRMED-RECOVERY-R09 | 八套件1557/1557，零skip；canonical -t修复 | 0 / 28.777256秒 |
| R26 | Java2363项2360过/1失败/0错误/2skip；Python142/142 | 1 / 117.162317秒 |
| JVM-BUILD-R17 | 编译2/2；195输入/306资产全量独立核验 | 0 / 51.248277秒 |
| JAVA-SUPERVISOR-R11 | 不同JVM正常/真实SIGKILL后orphan回收与无操作重试；79+13+7辅助全过 | 0 / 108.949013秒 |
| VALIDATOR-FAULT-R01 | 88方法；九个新方法子例触发25缺函数错误、0fail/0skip | 1 / 0.550186秒 |
| VALIDATOR-FAULT-R02 | loader88/88，未覆盖main计数 | 0 / 0.403440秒 |
| JVM-BUILD-R18 | 编译2/2；195输入/306资产全量独立核验 | 0 / 42.127757秒 |
| JAVA-SUPERVISOR-R12 | 88项过但旧main严格79计数失败，未启动daemon | 1 / 13.122432秒 |
| VALIDATOR-FAULT-R03 | loader和真实脚本入口均88/88，零skip | 0 / 0.569187秒 |
| JAVA-SUPERVISOR-R13 | 三类同现场失败恢复、七JVM、88+13+7辅助全过 | 0 / 103.318973秒 |

最新定向精确argv见CONFIRMED-RECOVERY-R09/baseline-summary.json；Launcher331、Runtime556、Identity472、Discovery58、Preparation23、原子写回117，共1557。R26为离线全量mvn test，Java退出1，Python142/142退出0。R06～R11的新时长来自systemd同一单元内monotonic端点，不是Maven耗时或Realtime差；原始双时钟端点保存在JAVA-SUPERVISOR-R11/post-observations.json，可重算，不据此推断跨WSL身份连续。

R11阶段原始证据均在docs/acceptance/TASK-10下（新R18/R13见第12节）：

- CONFIRMED-RECOVERY-R09/baseline-summary.json、R26/baseline-summary.json与R26/python-summary.json及两轮source-hashes/source-hash-verification。
- JVM-BUILD-R17/build-summary.json、bundle-manifest.json、source-hash-verification.json；二进制在忽略target，资产不是提交源。
- JAVA-SUPERVISOR-R11/steps.jsonl、java/四个recovery/retry目录的observation/commands/runtime/stages前后JSON、active下live/SIGKILL/orphan inspect、各scenario卷inspect及source-hash-verification.json。

R09/R26各198当前产品/测试源码独立匹配；R17的195构建输入/306资产、manifest/archive/Probe独立匹配；R11的12冻结harness/product及对应当前源匹配。清单范围不同，不能把195等同198。旧R05/R24的198只证明FS阶段当时快照，之后语义与CLI修复使其不覆盖当前源；旧红绿/skip及各自核验完整保留。

R26唯一失败仍JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe：期望RE，实际SYSTEM_ERROR / Judge executor rejected the task，归TASK-18，未修未屏蔽。两skip仍cpp-judge-runner:latest缺失与WSL非Windows，不代表平台验收；1557定向与真实R11限定通过不等于全量通过。

Stage CLEANED只在Python实际清阶段目录后成立，旧Java虚假清理声明已修为只到REMOVED、原CLEANED及NOT_REQUESTED保持，outcome不臆造。R10实际stop exit0/stderr0/stdout120不等于完整ID，STOP_REQUESTED保留；Docker29.1.3只读help确认弃用参数，-t修复不剥离警告/放宽断言，R11mutation原始stdout严格65字节完整ID加换行。

legacy manifest-only保留是兼容性收紧，可能占存储；不人工删attempt/lock绕过，不以成功cleanup回调或单元域拆除当任务删除凭据。初始FS独立复核与本轮主代理自查来源区分见第6节。

## 4. R11历史资产与运行快照

- 最新已真实集成Java包为JVM-BUILD-R17，不再沿用缺新代码的R15/R16；195输入/306资产、bundle67962024字节、archive68587520字节全部独立匹配。
- manifest SHA256=2085d90611b042d99dc084319b69433425ce7b27fc10a4f6fe6805e84fdf10a9；archive SHA256=96d913ca1ac36a15374b9d071710eb3a686a183ea01e30d98ac1d7eaf0f9f42a；Probe SHA256=8af0d5072816b4bd884bdffa25112adb96fa945586903e410863dcd23b848f4c。运行包在忽略target/task10-runtime，不提交二进制。
- 监督器镜像仍SUPERVISOR-BUILD-R04，image sha256:6f7e70984cfff475fdd0fecc315cc7620c16b8f0e0faa23a22414b7271bbd901；执行器仍冻结BUILD-R05。旧构建/准备记录保留，不代替新源验收。
- 最新会话全终态：R06=94990、R07=57289、R25=98922、R16=5039、R10=9886、R08=27511、R09=97205、R26=9934、R17=47570、R11=51926；R06～R09为CONFIRMED回归，R16/R17为JVM构建，R10/R11为JAVA-SUPERVISOR。旧R01～R05/R23/R24及只读34125也终态，不能重启或轮询旧handle当活跃。
- 14:12:46+08:00事后只读实际docker314/containerd233，均active/running、NRestarts0、无loaded wjc-task10单元；原始观测与collector源码在R11。前序12:38:53的docker activating及多次PID变化保留历史，不由相同PID/零计数推导跨WSL连续或旧停止授权。
- 本轮未停止/重启共享daemon；私有R10/R11 daemon各自最后exit0。两卷在产品回收观察时仍保留描述/名称，随后整个私有域拆除不算产品卷回收或UNKNOWN权威证明，不把实验资源留存写成仍存在于共享Docker。
- 内联只读观察因Windows→WSL引号丢失在Python解析前失败，改为透明脚本后成功；不是测试观测超时后重启单元。新线程先查实际工具/服务状态，不假定旧session、PID、代理仍可用。

## 5. 实验预算与下一步

Maven worktree预算保持4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、NoNewPrivileges、ProtectSystem=strict、ProtectHome=read-only、只读repo和2GiB tmpfs；仅TASK-10证据可写。构建额外仅忽略target可写，真实域570+30秒/Delegate、1792MiB根tmpfs+最多3容器各64MiB shm+两个32MiB卷恰为2GiB。工具按规则申请require_escalated，不绕过sandbox，Maven/构建/真实域串行，不能提高预算或停止共享服务。

1. 第10节旧记录收尾已完成；先读取第12节最新R18/R13原始证据并按第9节核对。不重复原子写回、消费者、FS/语义/-t修复或三个已验收同现场故障切片，不盲信旧快照。
2. 下一实际开发/验收为其他步骤CLI失败、成功回显无效果、无记录/归属不符对象及tmp证据保留；R13只覆盖非零私有连接失败和两类真实rm后响应丢失注入。真实daemon故障等未覆盖项明确保留，不能以三个成功切片替代全链路。
3. 保留无记录/归属不符对象及每步失败/tmp证据，验证新进程锁内重读、managed集合与原始完整ID，不能写假REMOVED/CLEANED或虚构outcome。若修改Probe/产品，重建新冻结包，不复用R17覆盖新代码。
4. 然后继续卷对象连续性/可信域终结、UNKNOWN权威恢复、部分创建与临时失败证据恢复。卷名称/描述、空列表、flock、已停CLI或generation字符串都不是完整权威证明，域拆除不能当产品卷回收。
5. 实现可信任务级回收/删除凭据及attempt集合/记录身份重验，才放行Store删除；现有root锁与ContainerReclamation不提供凭据。活跃取消仍需完整链路；Python整个执行持flock，不能把R11 owner崩溃后场景替换为取消已完成。
6. 生产每attempt独占还是专用共享generation、停止权限、数据根/控制配置属主与UID/GID仍待具体确认；实验UID0/65532不是生产默认。没有生产generation不阻断所有已批准CONFIRMED实验，绝不停止共享Docker/containerd。
7. 全部TASK-10标准与证据、task/work/实现一致前不DONE、不提交、不关DEF-05/N2、不启生产provider；之后继续完整30任务依赖，TASK-18失败仍保留，禁止push。

## 6. 实现分工与独立审查

历史开发轮核查时，两名参与该切片的既有代理均completed。本次文档整理未查询、触发或新建代理；以下仅为历史分工，不是要求接续线程自动并行，也不能据此推定所有代理的当前状态。代理ID仅适用于原线程。

| 既有代理 | 历史开发轮完成内容与文件所有权 |
| --- | --- |
| /root/confirmed_recovery_seam | 新增LinuxStageContainerIdentity.java、对应472项测试与真实形状JSON夹具，共3文件；代理未编译/实跑，实际R03/R05回归由主代理串行执行 |
| /root/stage_record_update | RuntimeState分类器及其测试2文件，另对消费者只读审查并复核FS修复；未实跑、未编辑审查对象 |
| 主代理 | LinuxConfirmedContainerRecovery、Launcher/Runner接入、Launcher回归夹具、串行测试和证据集成；历史文档轮核验R05/R24各198份，本次重新核验R09/R26各198份源码 |

审查MAJOR的完整链路：

1. 发现非默认FS Path经toString后被误解为宿主绝对路径；影响Launcher recover、Runner recover及既有stop入口。不是单纯日志或提示问题。
2. 新增真实ZIP FS别名参数化红测，覆盖三入口；旧代码CONFIRMED-RECOVERY-R04为3/3失败、零错误/skip。
3. Launcher统一Path校验先确认非空、默认FileSystem引用身份、绝对及规范化，再转换字符串；recover和stop都走该门禁。Runner在resolve/转发前同样拒绝，原String spec契约不变。
4. 修复后CONFIRMED-RECOVERY-R05八套件1553/1553；主代理已独立核验R05/R24最终198份源码。只读复核确认原MAJOR源码缺陷已消除，限定范围无新增代码阻断；复核人未实跑，不称独立真实容器验收。

此前原子写回117例和独立FS审查仍保留，最新独立review已落盘为confirmed-container-recovery-review.md。本轮CLEANED语义与Docker29参数问题由主代理自查/真实R10发现，红绿及R11真实复验已记录，不冒称此前审查人覆盖了新变更；尚无新独立复核。Lease原子I/O不是业务转换、卷连续性或删除凭据，完整TASK-10仍未通过。

## 7. 已落地消费者合同与证明限度

- 入口为LinuxSupervisorLauncher.recoverConfirmedContainers(Path workDir, String judgeId, String attemptId)及LinuxContainerRunner.recoverRetainedConfirmedContainers(Path storageRoot, String judgeId, String attemptId)。从持久日志恢复，不依赖live handle/runs；准备接口prepareRetainedStageRecovery仍只做准备，StageFinding或返回boolean不能当删除凭据。
- 锁序为root短准入→task lock→释放root→runtime journal→Python兼容stage目录flock；运行时调用不持全局root锁。严格重读runtime/configuration与完整stage Parsed及原bytes，复用现有准入和持久闭门，不另造锁体系。
- volume、holder、supervisor均须CONFIRMED；部分创建、缺stage目录、UNKNOWN、异常/tmp证据、重复完整ID或超限均保留。UNKNOWN stage在任何运行时观察/操作前拒绝。持久记录与attempt/managed标签枚举合并，无记录对象不能静默忽略。
- 容器观察使用成功、有界、严格解析的container ls --all --quiet --no-trunc --filter id=<完整ID>，只接受0/1个完整64hex ID；存在时要求成功inspect、单对象数组及Id精确相等。任何CLI非零、stderr、短/重复/损坏/超限输出或失败inspect都不是Absent；成功空列表也不能解除UNKNOWN。
- stage核验完整ID/name、精确四标签、image、UID/GID、安全限制与CPU/PIDs。input只读、scratch可写；Mounts.Source绑定卷根，子路径用VolumeOptions.Subpath，NoCopy=true。不能把supervisor全部RW规则直接套到stage。
- journal未保存动态Cmd或原memory值；仅验证合法字符串argv、Memory正整数及MemorySwap同值，不猜旧请求。IPC/cgroupns是当前真实profile门禁，不是历史journal证明；合法OOM/Dead/Error不等于身份未知。
- runtime v1从13个快照扩为19个。新增6个容器回收快照允许CONFIRMED完整ID容器的REMOVAL_REQUESTED/REMOVED与holder停止写序，始终固定volume=CREATED、global=CLEANUP_PENDING；不含卷删除、全局CLEANED或UNKNOWN终结。旧Java包会拒绝新值，回滚保持失败关闭，不删记录“兼容”。
- 顺序为闭门/全身份核验→supervisor stop→stage stop/rm→supervisor rm→holder stop/rm；每步先持久意图、只用完整ID，成功观察缺失后写REMOVED。所有stop用canonical -t 0，拒绝非纯ID回显/错误/stderr；stage只到REMOVED，原CLEANED及NOT_REQUESTED不改，creationState/ID/outcome保持，不虚构目录清理或业务结果。R11原始mutation stdout每次完整ID+换行65字节。
- 返回LinuxSupervisorLauncher.ContainerReclamation(confirmedStages, retainedVolumeName)。计数包含已经清理的CONFIRMED持久记录，不是本轮rm次数；卷及全部控制证据保留，成功不授权FileTaskStore删除，也不解除新start冻结。
- RecordingRuntime为R09关联回归中的有状态模拟CLI，不替代Docker。R17/R11是历史正常/孤儿成功后重试；当前R18/R13已真实验证非零连接失败和两类实际rm之后的受控响应丢失同现场重试。其他步骤失败/无效果、真实daemon故障、卷连续性、UNKNOWN或活跃取消仍未完成，不能扩大三类切片结论。
- 真实形状fixture来自历史UNKNOWN晚到create的inspect字节，仅复用字段形状，不把历史UNKNOWN晋升为CONFIRMED。缺少generation不能一概否定冻结可信边界中的完整ID容器回收；但卷只有名称，labels/options/Mountpoint不能证明对象连续性或防止同名重建。generation字符串也不够，未证明时保留卷。

准备/保留、模拟CLI容器切片回归、真实容器回收、卷及UNKNOWN完整恢复、最终任务删除授权是不同验收层级。协作flock及可信UID/procfs不保护不合作root，不证明硬文件系统时间上界或断电持久结果；不能合并宣称RECOVERED或TASK-10已完成。

## 8. R11开发轮边界（历史）

- 上一文档轮之后，按完整active Goal进行了真实开发：同步初次消费者/FS记录，修复虚假CLEANED语义及Docker29弃用参数，完成两轮红绿、两全量回归、R16/R17冻结资产及失败R10/成功R11真实域。后半轮部分日志/设计尚未收尾，具体见第10节，不把开发事实与记录齐全混为一谈。
- 已实际修改两产品文件LinuxConfirmedContainerRecovery/Launcher及LauncherTest，另扩展JavaSupervisorProbe、runtime_preflight与五项验证器。未做新HTTP/API schema或DB迁移，不修改全局配置、IDE或禁止工具目录；未创建/触发代理。
- R11是新JVM、无handle的真实容器回收与supervisor SIGKILL后的遗留stage恢复。正常6条与崩溃现场5条CONFIRMED保留真实可选outcome，两次不同JVM无操作重试；不是活跃取消、失败确认丢失、UNKNOWN/卷权威终结或最终删除。
- 只操作本轮新私有域容器，未停止共享daemon。全部执行会话终态；只读服务/时间collector也终态，语法失败未执行服务操作。私有域拆除不算产品卷回收，原始失败/skip不覆写。
- 完整Goal保持active、不重复创建/暂停/complete，5/30、TASK-10未提交、DEF-05/N2未关闭、生产门禁关闭。没有暂存/提交/推送，HEAD仍TASK-09。本轮限定进展不缩减原始全部30任务/31缺陷目标。

## 9. 接续开工检查清单

1. 读原始附件、适用AGENTS/技能、Goal第2节需求设计/任务/日志/缺陷评审，核对最新指令与具体批准范围。已有active Goal不重复创建；新线程无Goal时采用第1节完整目标。
2. 正确仓库只读核查：

   ~~~powershell
   Set-Location "C:/Users/29901/Documents/dp/web-judge-c"
   git rev-parse HEAD
   git status --short
   git diff --cached --name-only
   git log -8 --format="%h %s"
   ~~~

   若HEAD/状态与第1节不同，先核查新增变更，不能reset回快照；保留所有TASK-10未提交实现及IDE改动，不读取/清理.mimosa/、.playwright-mcp/。
3. 当前按R19的195输入/306资产/新Probe、R15的12冻结harness/product与各自manifest/source-hashes核验；R07严格94项不是旧R03/R13的88项。R14保留旧失败快照范围，当前harness已做失败前留证修正，不能倒称R14已验证新版本；产品变更后必须新包/新证据。
4. 继承旧会话及新R19 session46996、R14 session54755、R15 session83405已终态；R07也是unit0终态。接续前重新只读查实际单元/服务，不重启终态handle或复用PID授权；16:40:33事后观察不是连续性证明，不停止共享服务。
5. 按第14节直接进行可信取消marker/合作退出的产品修复与Discovery严格marker发现，真实旧产品红测已由R15证明，不重复已完成Probe/包冻结/红测；R14漂移风险及全部原Goal余项保留。
6. 下一候选R27、新定向轮、JVM-BUILD-R20、JAVA-SUPERVISOR-R16，先确认目录/target/单元未占用再执行；所有旧轮保留，Maven/构建/真实域串行。逗号Maven参数整体双引号，使用透明有界脚本，不以旧包证明新产品。
7. 完整TASK-10验收后才按唯一键精确暂存、独立本地提交并核验真实SHA；未满足条件不DONE、不关DEF-05/N2，禁止push。继续原始其余依赖任务，不以组件绿缩减Goal。

## 10. 记录同步与剩余事实缺口

本轮已追加change-log、恢复设计第19/20节、stage-recovery第9/10节，补录旧work在途终态并同步R18/R12/R13；task、消费者第9节、review来源、Java集成、build与Goal/本交接均跟随真实结果。旧R05/R24/R15等待描述保留历史，不再是接续入口。

剩余是实质开发/验收而非这三份记录缺口：其他CLI步骤失败/成功回显无效果、无记录/归属不符/tmp、卷连续性/可信域终结、UNKNOWN/部分创建、可信删除事务/集合重验与活跃取消。新探针/验证器及之前语义/CLI没有新独立复核；主代理实跑和自查不冒称独立审查。

每个新增切片仍需同步task/work、变更日志、验收/设计与Goal/交接，保留失败/skip/来源和风险。没有真实SHA前不填SHA，文档或组件绿不能标DONE。

## 11. 上次文档交付范围（历史）

- 只更新`docs/project-goal-2026-10-04.md`、本交接并追加`docs/work.md`；复用原入口，不创建重复Goal或新日期的旁支交接。
- 重新读取R09/R26结果，独立重算各198项当前源码均匹配；核对R11四个JVM observation与原始mutation回显：PID1744/4220分别4/6次stop/rm，PID2542/5072均零mutation；同单元monotonic差为108.949013秒。这些是历史证据核对，不是重新启动真实验收。
- 不运行Maven/Python测试、构建或容器实验，不操作共享服务，不改产品源码、IDE文件、Git index或全局配置，未暂存/提交/推送。
- Goal继续active，项目仍5/30；本次请求完成后结束本轮文档工作，不自动开始下一开发切片。后续恢复完整项目时，使用Goal第6节与本交接第9、10节。
- 本次静态检查通过：3份交付文档UTF-8/空白/围栏及12个直接本地引用检查通过，git diff --check退出0；R09/R26源码、IDE与index保持不变，work历史字节前缀保留。其余开发轮文档仍按第10节收尾，不称全部记录已验收。


## 12. R18/R13同现场失败恢复（历史，2026-10-06）

- 最新验收入口`docs/acceptance/TASK-10/confirmed-container-recovery.md`第9节；原始`JAVA-SUPERVISOR-R13/steps.jsonl`及java七场景的commands、failure-observation/observation、runtime/stages前后、容器/卷独立前后，active下owner SIGKILL/orphan inspect；R18/R12/R13独立source-hash-verification与R13透明collector/post-observations。
- 正常现场：PID1742真实CLI访问私有不存在socket，exit1/stdout空/stderr222字节且实际容器无副作用，留下STOP_REQUESTED；2161真实supervisor rm后丢回应留下REMOVAL_REQUESTED；2697从同现场完成，3407零mutation。孤儿现场：producer3893退出/私有supervisor SIGKILL后，5101真实stage stop/rm丢回应留下REMOVING/null outcome；5669同现场完成，6426零mutation。七恢复PID互异，三条完整runtime/stages失败after→下一before严格相等。
- 七份trace内11次成功mutation均为完整ID+换行65字节/exit0/stderr空，另一次为实际非零连接错误；响应丢失为受控executor注入，不冒称真实daemon故障。R13 unit0/103.318973秒，88+13+7辅助过；R12在启动daemon前因旧main严格==79失败unit1/13.122432秒，改为严格==88后R03 loader/入口都88/88，失败轮不覆盖。R01的25缺函数错误来自九个新方法子例，不用88-25伪算通过数。
- R18编译2/2、unit0/42.127757秒，195输入/306资产/manifest/archive/Probe独立匹配；manifest=c96576a7ffbbab8c2bc3ce94ff7d5ef963101f810a9c82ffa04804bdcb6ac87a，archive=176eb83277a76f0b0eed5e09d03923d24dd6a20d6010e0c5528a00d06413cb54，68597760字节。R13的12冻结副本/当前源均匹配，R12快照匹配但当前计数修复后一文件不同，不能混同范围。
- 产品源未改，R09/R26各198源码仍匹配；R26历史TASK-18失败及两个skip保留，不是新全量回归。两卷/控制证据保留CLEANUP_PENDING、无删除凭据；域拆除不是产品卷回收，owner崩溃后恢复不是活跃取消。14:58:53+08:00docker314/containerd233均active/running、NRestarts0、无loaded TASK-10单元，只是当次观察。
- 下一实际开发：其他CLI步骤失败/成功回显无效果、无记录或归属不符集合/tmp证据，再推进卷连续性/可信域终结、UNKNOWN/部分创建、可信删除事务/attempt集合重验、活跃取消和独立复核。继续完整30任务Goal，不停共享daemon、不猜生产generation/UID/权限，不因三例通过关闭DEF-05/N2或提交TASK-10；新轮开工先确认未占用，不复用终态session或覆盖原证据。


## 13. 活跃取消开工交接（历史，2026-10-06）

### 13.1 当前任务、工作区与交付范围

- 本轮按用户“写一个goal以及任务的交接文档”请求更新目标、交接、task/work，不继续修改产品或运行实验。原Goal保持active；不得重复创建未完成Goal，也不得为交接标记complete。
- 本次只读核查HEAD为`5f672c2faa9d02e2e3cd95c832ef4623bc222301`，index无暂存路径；真实本地提交对应已完成TASK-00、01、02、08、09。当前5/30，TASK-10 IN_PROGRESS/未提交，定位键仍TASK-10-C01。
- 保留全部现有未提交实现、测试、证据与文档；两份IDE用户改动不提交，不读取/清理`.mimosa/`或`.playwright-mcp/`。不commit、push、reset、建分支或改全局配置。
- DEF-05/N2尚未关闭，生产门禁关闭；卷和控制证据仍保留，无删除凭据。此文档不能替代严格验收、独立复核或任务本地提交。

### 13.2 上轮已实际写入，但未全部验证的变化

| 文件 | 实际状态 |
| --- | --- |
| `docs/work.md` | 已追加“活跃取消控制路径开工”，不是验收成功记录 |
| `docs/acceptance/TASK-10/test_runtime_preflight.py` | 新增5个测试方法；main仍严格校验实际方法数，88→93，不放宽门禁 |
| `docs/acceptance/TASK-10/runtime_preflight.py` | R04红测后已实现取消模式、观察验证与调度；尚未跑绿 |
| `docs/acceptance/TASK-10/JavaSupervisorProbe.java` | 仍是R18的旧Probe，仅有`--start-active-only`，没有`--cancel-active-only` |
| Journal/Launcher/Runner产品代码 | 本次取消子任务尚未修改；新方法和合作退出路径只是待实现方案 |

新Python逻辑：`JAVA_ACTIVE_CANCEL`默认False；仅接受outer/namespace/pivot/daemon四phase的`-java-supervisor-cancel`模式，切换模式时重置状态，拒绝未知phase/组合。`phase_mode`传播后缀；`run_java_supervisor_probe`在新模式中以`run_java_active_cancel`替换旧孤儿恢复fixture，而非新增第三fixture/卷。

`run_java_active_cancel`已计划调用`--cancel-active-only`，再由不同JVM执行active-recovery/active-retry。它要求真实同JVMstart/cancel、取消前实际stage运行和flock持有、可信空marker、CANCELLED outcome、真实目录清理后CLEANED、stage容器独立缺失，以及产品stop前监督器合作退出20/Pid0/非OOM的原始inspect。当前Java参数不存在，因此现在不能运行并宣称真实取消通过。

文档轮只读计算的当前SHA-256：

| 当前文件 | SHA-256 |
| --- | --- |
| `runtime_preflight.py` | `d24838c577605b28b19abe0590cc81eaaad0e03e258f33038d84faf6834717aa` |
| `test_runtime_preflight.py` | `68443762fc9f4ff90ccc9b1cf664581ecca60a94fed373e84607a254c84f70b5` |
| `JavaSupervisorProbe.java` | `4fc63b3d425740c183446a8777302dc89da1e055a26b337453d544ec0d98c67c` |

上述摘要不是通过证明。R04冻结runtime摘要为`603c81a66ba6f0d8dbff95250036f41434049e2b7f6e116d58b262629af59e12`，与当前实现不同；R04测试摘要仍匹配。R13冻结harness与当前Python文件也不同，旧证明只覆盖当时版本；产品195/198项未变沿用上轮记录，本次没有重新核验全部源码。

### 13.3 新红测及必须保留的历史结论

- `docs/acceptance/TASK-10/VALIDATOR-FAULT-R04/summary.json`已读取核对：loader与真实入口各93方法、2失败/8错误/0skip，退出码均1。对应`unittest.log`、`entrypoint.log`、`source-hashes.json`齐全。errors可含子例，不从93减失败/error伪造通过方法数。
- R04之后的预检实现未再运行；无93项绿测，无JVM-BUILD-R19或JAVA-SUPERVISOR-R14真实结果。本次不启动这些候选轮次，不预填成功。R04尚无本次核验的原始单元双时钟端点；Python日志时长不能写成单元monotonic时长。
- 历史R18编译2/2、195输入/306资产已独立核验；manifest为`c96576a7ffbbab8c2bc3ce94ff7d5ef963101f810a9c82ffa04804bdcb6ac87a`，archive为`176eb83277a76f0b0eed5e09d03923d24dd6a20d6010e0c5528a00d06413cb54`、68597760字节。历史R13真实unit0、monotonic103.318973秒，88+13+7辅助通过；完整原始证据见第12节和消费者验收第9节。
- R12的旧main严格==79门禁失败保留；R03/R13当时严格88通过，不能替代现今严格93。旧session22387/91614/24535已有终态，不重启旧handle或覆盖证据。
- 最新全量仍为历史R26：Java2363项、2360过/1失败/0错误/2环境skip，Python142/142。`JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe`的RE→SYSTEM_ERROR/executor rejected属于TASK-18，未修未屏蔽。
- 14:58:53+08:00的docker314/containerd233 active/running、NRestarts0、无loaded TASK-10单元只是上轮当次观察。本次没有新服务观测，接续前必须重新只读核查，不凭相同PID推断授权或连续性。

### 13.4 已定位的取消缺口与最小修复方案（未实施）

现有`LinuxContainerRunner.cancel`依赖本JVM的`runs`，调用`supervisorLauncher.stop`后仍抛出`Linux supervisor stopped; stage and volume recovery remains pending`。`LinuxSupervisorLauncher.stopLocked`直接stop监督器，未发布可信取消文件；Python的`linux_stage_executor._stage`在整个stage执行期持目录flock。直接杀owner不能完成正在执行的stage取消，R13已证明stage可遗留。

Python已有合作取消协议：`task_runner`检查attempt目录的`cancel.requested`；执行进程取消后真实kill/rm stage，持久化CANCELLED outcome，实际rmtree后才CLEANED；可信监督器正常取消退出码为`task_runner.EXIT_CANCELLED`（20）。不能通过先让producer退出、SIGKILL owner或手工写marker冒充真实Runner.cancel。

拟按现有职责做最小改动：

1. **Journal负责可信控制文件。** 拟新增`LinuxRuntimeJournal.requestCancellation()`，复用既有锁、目录身份、`writeNewFile`、force和poison机制。marker必须空、regular、非symlink、nlink1、精确0600、writer UID/GID正确；已有文件严格核验后幂等保留。拒绝symlink/hardlink/非空/权限错误/tmp证据，失败关闭并保留。现有`writeControlFile`只允许`supervisor-config.json`，不能直接借它绕过新入口约束。
2. **Launcher负责合作退出与停止边界。** 拟新增明确的取消+stop入口，复用stop的完整持久身份、配置、容器核验；先持久STOP_REQUESTED/CLEANUP_PENDING，再发布marker，按已有`options.commandTimeout`有界观察合作退出。活owner持flock期间不得获取stage目录锁。超时/未知可走已有完整ID stop，但必须保留待恢复语义，不能伪造绿色CANCELLED。既有独立`stop`契约不变，不给旧stop测试平白增加等待。
3. **Runner只接入新取消入口。** 保留原pending异常和信息；不在cancel中自动删除监督器/holder或卷。之后不同JVM持久消费者回收保留容器，卷仍保留，不发删除凭据。
4. **测试覆盖职责边界。** 补Journal marker安全/幂等/故障，Launcher合作退出/超时/锁与负例，Runner原pending语义。正常Probe的`verifyPostCompletionStop`仍两次cancel并要求原pending消息、仅监督器STOPPED、其他资源和事件/摘要保留；不得破坏已完成正常现场的旧契约。

生产UID/GID、generation、拓扑和停止权限仍须具体确认；当前UID0/65532仅属已批准实验，不是产品默认。

### 13.5 第一可执行点与红绿顺序

1. 核查当前状态、批准范围及空闲预算，读上述代码与现有测试。串行执行，不创建或触发代理；如后续指令明确要求并行，另行划分所有权。
2. 实现`JavaSupervisorProbe --cancel-active-only`：复用`startActiveFromFreshJvm`的真实无限USER fixture，在同一JVM保留实际runner/handle后调用cancel。路径限定`/lab/java-storage/judge-task10-java-active-recovery`和`/lab/evidence/java/active`；取消前须观测USER/execute、CONFIRMED、STARTING及实际Running。
3. 通过受限Python子进程对实际stage目录执行`fcntl.flock(LOCK_EX|LOCK_NB)`并观察失败，证明锁被真实owner持有；及时关闭FD，不阻塞、不只依据源码推断。保存取消前后raw inspect、完整ID列表、runtime/stages等实际证据；旧cancel若stage仍活，先保留事实再断言失败，不能只靠消息差异造红。
4. 用既有`run-recovery-validator-tests.sh`在有界单元执行当前预检loader与真实main；候选VALIDATOR-FAULT-R05须确认未占用。严格统计实际93方法、fail/error/skip与两入口退出码，若不绿先修验证器，不放宽计数。
5. 用`run-java-supervisor-build.sh`冻结新Probe与**旧产品**，候选JVM-BUILD-R19；独立核验源、manifest、资产、archive与Probe。随后在新私有域跑候选JAVA-SUPERVISOR-R14的取消模式，保留真实旧产品红测及终态，不重启旧session。
6. 红测证明真实活stage未取消后，再实施13.4产品方案及定向测试；按任务卡做新全量回归（候选R27等），原TASK-18失败/环境skip必须保留，不处理为通过。
7. 产品变更后重新冻结包和真实域（候选JVM-BUILD-R20/JAVA-SUPERVISOR-R15，须先查占用）。实际executor recorder在产品supervisor stop前保存`java/active/supervisor-before-product-stop.json`，要求当时owner已退出20/Pid0/非OOM，避免SIGKILL伪装合作取消。
8. 独立核验marker、真实CLEANED/outcome、stage缺失、不同JVM恢复及零mutation重试、原始双时钟/退出码。完成后同步task/work/change-log、验收、设计和Goal/交接；这些仍只是取消控制路径的限定切片，不是完整TASK-10或卷删除验收。

当前验证器要求`processId`为真正int而非bool，judge/attempt精确一致，`liveStageId`为完整64hex ID；要求sameJvmStartAndCancel、runnerCancelInvoked、liveStageVerifiedBeforeCancel、stageFlockHeldBeforeCancel、trustedCancelMarkerObserved、stageOutcomeCancelled、stageContainerAbsent、realRuntimeCommands、pendingRecoveryReported均True，productRecoveryComplete、productionAcceptance、activeCancellationComplete、deletionCredentialIssued均False。真实probe不能仅写True字段，必须有对应独立实物核验。

### 13.6 预算、权限与实验终态

- 不提高既有预算：4GiB、swap0、2CPU、512pids；测试总预算600秒，构建/真实域RuntimeMaxSec570秒加TimeoutStopSec30秒。Maven/构建/真实域串行，不因失败扩容。
- 构建沿用2GiB /tmp；真实域根tmpfs1792MiB，至多三个各64MiB shm的容器与**两个**32MiB卷，总计至多2GiB。新取消模式替换旧active fixture，不加第三卷。
- 沿用PrivateNetwork、NoNewPrivileges、ProtectSystem=strict、ProtectHome=read-only、仓库只读/列定证据可写；构建仅列定忽略target可写，真实域所需Delegate=yes。WSL/systemd执行按权限规则使用require_escalated，不绕过审批；本次文档轮没有执行。
- 只操作本轮新私有域已核验的完整ID对象；不停止/重启共享Docker/containerd。观察超时不是单元终态，沿真实handle收取退出码与双时钟；目录/单元名未占用再启动新轮。
- 私有域拆除不等于产品卷回收；不把CLI非零、未知、空列表或配置标签当删除凭据。不猜生产参数，不扩大授权范围。

### 13.7 完整任务余项与可复制接续指令

活跃取消之后仍需：其他CLI步骤失败/成功回显无效果；无记录对象/归属不符/tmp证据和managed集合重验；卷对象连续性或可信域终结；UNKNOWN/部分创建；可信删除事务与attempt集合重验；完整取消和预算闭环；新独立复核。TASK-10严格验收与独立本地提交完成后，继续其余依赖满足的任务，直至全部30任务及31缺陷的原始Goal闭环。

> 接续完整项目Goal：先读project-goal-2026-10-04.md第1/9节及本交接第13节，再核查原附件、适用指令、HEAD/index/工作区与冻结证据。当前5/30、TASK-10 IN_PROGRESS未提交，93项预检R04红测之后的Python实现未跑绿，Java --cancel-active-only和产品合作取消未实现。第一实际开发点是实现同JVM真实Runner.start/cancel及flock证明Probe，然后有界验证93项双入口、冻结旧产品并保存真实活跃取消红测，再按13.4最小安全方案修复和回归。保持既有预算、串行和生产门禁；保留失败/skip，不重复R13切片，不停共享daemon，不发删除凭据。全任务验收后才逐任务独立本地提交，禁止push，不提前把Goal标记complete。


## 14. 旧产品真实活跃取消红测成立（历史，2026-10-06）

1. **真实状态。** 原Goal active、5/30、TASK-10-C01 IN_PROGRESS/未提交；HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，生产门禁关闭。产品Journal/Launcher/Runner仍未改，旧代码即时stop且无可信marker已由真实行为证明；第13节为上一文档快照，不再是当前断点。
2. **已做且不要重复。** 新Java `--cancel-active-only`同JVM复用实际runner/handle和无限USER fixture；真实Python非阻塞flock、卷内目录/marker、容器前后和产品stop前owner原始证据均实现。R05残余非法-extra后缀红保留，最小修正后R06两入口93/93；故障原始快照移至断言前并加回归后R07两入口94/94，零skip。
3. **新冻结包。** R19编译2/2、195输入/306资产独立匹配；manifest=e53e528130b39b727eec71721e472c2e08ee5550bf6176777a021a8298750509，archive=b20085b24f71e91d81baa0e55bdab97a737e21a50ed9b1a92b0498d629ce7cf6（68608000字节），Probe=4cfbe6105f7a118ca396faddcdb15850e39bf209dc65ad44c6e801fec98a5762。产品sourceSnapshot仍31145c66633bde8dca805a15cec27b485de700968ffc96291342b1de8e88388d，属于旧产品；新产品必须另构建，不能把R19当修复证明。
4. **失败史及未定位风险。** R14 unit1/82.542951秒，在正常主入口exit0之后旧completed-stop-unreachable完整容器前后相等断言失败，未到active；旧harness在断言后才写快照，不能猜漂移字段。只修“失败前留证”并保持原断言；R15同场景严格通过，不把重跑写成已修R14根因。R14/source-hash-verification和原始双时钟保留，漂移/新代码仍需独立复核。
5. **真正的产品红测。** R15 unit1/108.029503秒，94+13+7辅助过；JVM3901实际Runner.cancel，Python4946实际flock竞争失败。stage完整ID=d3cb51fa063a8cf6733672fd7e829249660cda5f8b1562b6bf478b0ae0d83d26，前后Running=true/Pid4910；唯一mutation为supervisor完整ID3c9f924c35b351d08b86ca60451b7d2062d8b254d35da67b80b0156eb0b02088的stop -t 0、exit0/stdout65字节/stderr空。runtime到CLEANUP_PENDING/supervisor STOPPED，live stage仍STARTING，无可信marker或CANCELLED，目录未删；原pending异常信息正确。这是旧产品缺口，不是活跃取消成功。
6. **证据入口。** `docs/acceptance/TASK-10/active-cancellation.md`含原始文件导航与验收边界；R15/java/active的flock、live inspect前后、commands、runtime/stages、marker、failure-observation和source-hash-verification已由主代理另外重核，12冻结源匹配，不是新独立review。R19 session46996、R14 session54755、R15 session83405均沿真实handle收取终态，不重启；R14/R15透明collector保存双时钟/boot IDs。16:40:33+08:00当次共享314/233正常、无loaded TASK-10单元，不证明跨WSL连续性。
7. **下一实际代码动作。** 直接实施第13.4节Journal.requestCancellation（拟新增）、Launcher取消+stop有界合作观察、Runner接入，保持独立stop与原pending契约；活owner持锁时不取stage flock。补marker空/0600/单链接/writer身份/幂等/force/poison/tmp、合作退出/超时/锁和正常已完成两次cancel回归。另有新查得的集成缺口：`LinuxAttemptDiscovery.ATTEMPT_FILES`不接受cancel.requested，需严格合法marker发现与非法拒绝测试，不能只白名单放行或造删除权限。
8. **执行顺序与最终门槛。** 新定向+全量（候选R27等须先查占用）→新产品冻结（候选JVM-BUILD-R20）→新私有域真实绿测（候选JAVA-SUPERVISOR-R16）。绿测必须owner合作退出20/Pid0/非OOM、真实Python CANCELLED及目录删除后CLEANED、stage独立缺失、不同JVM后续恢复/零mutation重试，卷/控制证据仍保留。沿用原4GiB/swap0/2CPU/512pids/570+30秒/两卷2GiB预算，串行，不创建代理、不停共享daemon、不猜生产参数；没有完整验收与独立复核不提交TASK-10，不关DEF-05/N2。其余完整30任务目标、CLI/归属/tmp、卷/UNKNOWN/删除/取消/预算和R26 TASK-18失败/两skip不缩减。

> 从原始完整项目Goal继续。先核验本节与active-cancellation.md的R19/R15证据。真实旧产品活跃取消红测已成立；第一安全动作是按第7项补产品可信marker/合作退出/Runner与Discovery严格发现及测试，而不是再次造Probe或重复R15红测。保留R14未定位漂移和所有失败/skip，按原预算完成新定向/全量、新包和真实绿测；完整任务闭环前Goal仍active、TASK-10仍IN_PROGRESS，禁止push。

## 15. 当前接续：TASK-10隔离交付与TASK-11（2026-10-06）

- 沿用原active Goal和完整30任务/31缺陷；TASK-10-C01为VERIFIED_PENDING_COMMIT，先核验其唯一真实本地提交及task/work，再将前置视为满足；不得伪填SHA、push或提前complete。
- 当前可信取消不是占位：Journal/Launcher/Runner/Discovery均接入，锁内marker时序竞态已修。最新定向CANCEL-CONTROL-R03为470项/469过/0失败错误/1旧镜像skip、核心零skip；R28 Java2400过/1既有TASK-18失败/2环境skip、Python142/142。
- 可复用STAGE-R02四角色真实控制面负例、正常编译和双事件面，以及R16真实同JVM取消/合作ExitCode20/USER CANCELLED+CLEANED/实际阶段回收及新JVM重试。R20/R16只代表各自冻结源码；新Java改动以R03补验，不冒称旧bundle是完整当前产物。
- 原卡边界：11负责增量有界事件/验真；14负责整域预算与剩余卷资源回收；19负责全部非终态及UNKNOWN权威收敛；25/28冻结生产UID-GID/代际/专属域权限；27保留R14未定位差异。待回收占用和证据不释放，生产provider继续关闭。
- 保护IDE改动，不读取/清理.mimosa与.playwright-mcp；串行、无新代理；不停止共享runtime、不猜生产参数、不增预算、不伪造删除凭据。日常仅简短更新task/work，局部改动跑受影响测试，复用有效全量/真实证据。
