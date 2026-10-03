# TASK-09：Runner v2 清单与路径验收记录

## 开工基线

- 2026-10-03（Asia/Shanghai）；父提交 ea51d2ab8a936ccd392d9665ed78c086a554e369；TASK-01/02/08 的真实提交与记录已核验。
- 工作区仅既有 `.mimosa/`、`.playwright-mcp/`，保留并排除提交。
- 范围：宿主模型与 wire manifest 分离、相对源码与路径先验、版本/能力协商、独立 attempt 文件和比较参数贯通。
- TASK-15/16 才实现无损 API 接收与统一比较算法；本项不得把 double 转字符串称为端到端无损，也不能宣称 Python 已支持十进制比较。未实现能力必须显式拒绝。
- 控制面权限隔离、增量事件验真、轮询预算分别留 TASK-10/11/12；真实各 provider 发布验收留 TASK-25/26。
- 测试沿用 TASK-00 的受限 WSL 离线 Maven 采集器，证据目录逐次新增；Python suite 另行实际执行。不把命令 mock 当真实容器验收。

## 执行结果

- R01：原生产实现前测2项，0通过/2失败/0错误/0跳过，退出1。实际复现宿主四行审计被覆盖，以及拒绝前已创建根外目录。
- R02：首轮迁移检查在testCompile失败，0报告/0测试；测试辅助方法未声明PythonInterpreter.resolve的InterruptedException。补声明，不改生产行为或断言；此轮不是业务失败复现证据。
- 本段为早期记录；最终开发验收结论见下节，完整 DEF-02/N2/DEF-11/DEF-12 仍不关闭。

## 最终验证（2026-10-03，Asia/Shanghai）

| 轮次 | Java测试 | 通过 | 失败 | 错误 | 跳过 | 退出码 | 解释 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| R01 | 2 | 0 | 2 | 0 | 0 | 1 | 原实现：宿主审计被覆盖、拒绝前创建根外目录 |
| R02 | 0 | 0 | 0 | 0 | 0 | 1 | testCompile失败，测试辅助方法异常声明缺失；不是产品复现 |
| R03 | 63 | 59 | 2 | 0 | 2 | 1 | Linux命令替身把协议探针当旧安全探针，返回PROBE_UID文本而非JSON |
| R04 | 118 | 116 | 0 | 0 | 2 | 0 | 收紧替身匹配到/bin/sh，增加真实Python/路径/能力矩阵 |
| R05 | 124 | 122 | 0 | 0 | 2 | 0 | 最终定向：补版本类型拒绝、precision策略/编排贯通 |
| R06 | 360 | 358 | 0 | 0 | 2 | 0 | 最终全量，42套件；源码与R05完全一致 |

- R04/R05/R06均实际执行Python unittest：11项、0失败、0错误、0跳过、退出0；独立结果见各轮python-summary.json。Java总数包含1个Python suite桥接测试，不把Python子测试混加到Java统计。
- 最终定向类：SandboxRunnerContractTest、SandboxExecutionManifestTest、LinuxContainerRunnerTest、WindowsHyperVContainerRunnerTest、RemoteWorkerRunnerTest、TaskRunnerArtifactContractTest、TaskRunnerPythonSuiteTest、RemoteWorkerTlsTest、TaskPolicyResolverTest、JudgeSandboxOrchestrationTest。
- 实际Maven argv保存在baseline-summary.json：R05为离线Maven加上述-Dtest选择，R06为mvn -o -B -Dstyle.color=never test。Python argv是/usr/bin/python3 -B runner/tests/run_contract_suite.py target/runner-python-summary.json；该脚本实际discover并运行runner/tests/test_*.py。
- 环境：Ubuntu24.04.1 WSL、OpenJDK21.0.12.1、Maven3.8.7离线缓存、Python3.12.3、g++13.3.0；未安装/升级依赖，非Wrapper3.9.10。
- 两项原环境跳过：cpp-judge-runner:latest镜像不存在；当前Docker runtime为Linux而非Windows Hyper-V。没有跳过本项路径、协议、TLS或Python安全负例。
- 外层仍为TASK-00批准的4GiB/无swap/2CPU/512任务/600s systemd服务、2GiB tmpfs、独立网络空间、系统/家目录/源码只读，仅本项证据目录可写。各轮unit-properties.txt和environment.txt保留实际约束；Docker socket未隔绝，不当作生产恶意代码隔离证明。systemd显示的Memory peak不用于JVM/性能验收。

## 实施与逐项验收

| 要求 | 实现与实际证据 | 边界 |
| --- | --- | --- |
| RQ-R01 / AC-R01、DEF-02 | 独立SandboxExecutionManifest、共享SandboxManifestFactory；宿主spec仍保留原绝对路径，wire仅相对路径；Java序列化/不变性断言；实际Python把同一manifest复制到不同执行根并完成编译判题，原宿主整树快照不变 | Linux/Windows容器命令映射仅单元契约；真实平台最终证据留25/26 |
| 创建前拒绝路径 | POSIX绝对、父级、点组件、UNC、盘符、反斜杠、控制/工作区路径、设备名、缺源码、他任务源码、源码/控制目录/根链接负例；实际Python逐例比较文件树字节及链接快照 | 不将同UID路径检查当作竞态/权限隔离，TASK-10仍必需 |
| v2版本、字段、能力 | 必填字段、未知版本、缺能力、错误比较模式及版本字符串/小数均拒绝；本地不写清单，Worker不发送run/source；回执必须匹配版本/judgeId/attemptId/provider/runId | Worker真实服务端验证/重放保护/隔离未验收 |
| RQ-R03事件分面 | control/attempts/UUID/task-spec.json、runner-events.jsonl和runner-summary.json；workspace/UUID独立，句柄持久化版本与attempt；宿主四条audit、summary及旧attempt保持字节不变，重放attempt拒绝 | 增量/半行/截断/sequence验真留11；完整N2不关闭 |
| RQ-C01参数贯通 | JudgeRequest现有精度进入ResolvedTaskPolicy、宿主spec、v2清单及真实TLS Worker请求体；默认/显式0、页面1e-9断言；内部高精度字符串不经额外double转换；时间毫秒/字节/retention显式 | API仍为double，不能声称端到端无损；算法与解析上界留15/16 |
| 能力不伪报 | Python仅声明实际LEGACY_TEXT与SPJ；普通host请求固定DECIMAL_ABSOLUTE，在当前artifact上显式拒绝，不静默丢precision | 本中间提交不恢复普通生产容器判题；必须完成16及25/26才能放行 |
| Worker路径独立 | v2请求实际附带按相对源码名映射的Base64字节，不发送宿主根/userId；本地真实TLS验证三认证模式、完整请求体及回执绑定 | 外部Worker须实现自己的任务根、先验路径/集合校验和落盘，不用fixture代替外部实现 |
| 迁移回滚 | runner/task-runner-spec.md、runner/README.md、生产runbook明确成套升级、旧任务只读、不复用旧混合流、不滚动混跑及安全回滚限制 | 未实际部署/搬迁/删除历史数据 |

## 哈希、审核与剩余风险

- final-hash-verification.json记录：R05/R06各150个源码/runner/scripts/POM哈希、172个完整输入哈希和harness哈希逐一匹配当时工作树，两轮列表一致。此后仅追加本验收记录、task/work/change-log，不修改被测代码/配置/测试/harness。
- 主代理自审，未宣称独立第三方审核：复核宿主与wire职责、创建顺序、原审计保护、旧协议拒绝、字段类型、TLS签名输入、无额外认证/路径泄漏。发现并补测版本数值隐式转换风险；共享工厂/比较快照遵循DRY/SRP，保留现有框架、存储及HTTP路径，不引入新依赖。
- DEF-02为IMPLEMENTED_PENDING_VALIDATION；N2只完成路径与文件分面；DEF-11/12仍未关闭。Windows/native/外部Worker未验收，整体不可发布。
- TASK-08 R06偶发RE期望却得到SYSTEM_ERROR的根因仍未确认；本轮全量通过不能把该风险关闭，继续留N5及最终回归记录。
- TASK-09开发验收通过，按task.md自身SHA协议提交候选生效；用户明确要求本项提交核验后暂停，不启动下一项。
