# TASK-08 验收证据

## 结论与边界

- 日期：2026-10-02；开发验收通过，DONE提交候选在真实提交核验后生效；唯一提交定位键TASK-08-C01，父SHA为6f3b479b8cd37bc29cb862495515318066e88618。
- 对应RQ-A07/AC-A07/T-A13、DEF-23、DEC-05；D §3.6/8.1/8.3。先期仅支持HTTPS上的现有Bearer/HMAC协议，任何实际非空mTLS配置明确拒绝，不宣称支持mTLS。
- 最终定向49/49，全量复验295通过/2项既有provider跳过。R06真实出现一次RE→SYSTEM_ERROR失败，未改实现/断言的单项及全量复验通过；根因未确认，不能宣称已经解决该不稳定风险或抹去第一次失败。
- DEF-23仅客户端实现/开发验收完成，状态IMPLEMENTED_PENDING_VALIDATION。这里的本地TLS协议fixture不代表外部Worker验签、重放保护、执行隔离或真实部署端点已验收；这些仍由TASK-26/29提供证据。整体不可发布。

## 实际运行记录

环境仍为受限Ubuntu 24.04.1/WSL、OpenJDK 21.0.12.1、Maven 3.8.7离线缓存、Python3.12.3；未安装/升级依赖。每次完整argv、环境、套件计数、失败类型与原因、源码和输入哈希见对应目录。

| 运行 | 范围 | 退出码 | 总数/通过/失败/错误/跳过 | 结论 |
| --- | --- | --- | --- | --- |
| R01 | 新配置前测+原客户端/启动用例 | 1 | 29/15/14/0/0 | 复现HTTP/伪mTLS/配置冲突/URI错误回显/启动忽略主配置 |
| R02 | 加入TLS后的首次编译 | 1 | 0/0/0/0/0，0报告 | 一处既有测试构造器迁移遗漏，修正后重跑，不算TLS结果 |
| R03 | 配置+真实TLS+既有回归 | 0 | 42/42/0/0/0 | 13个TLS场景初步通过 |
| R04 | 补HTTPS重定向目标、HMAC与装配 | 0 | 49/49/0/0/0 | 18个TLS、17个配置场景通过 |
| R05 | 强化具体TLS拒绝原因断言 | 0 | 49/49/0/0/0 | 最终定向通过 |
| R06 | 首次全量 | 1 | 297/294/1/0/2 | 原JudgeBaselineTest单个RE状态异常；保留失败 |
| R07 | 原码单独诊断RE用例 | 0 | 1/1/0/0/0 | 未稳定复现，不当成根因已解决 |
| R08 | 原码完整复验 | 0 | 297/295/0/0/2 | 40套件，核心用例均未跳过 |

R05/R06/R07/R08的141个源码/runner/scripts/POM哈希均与同一工作树逐项匹配，证明没有在失败后改代码、改断言或额度来制造通过。R08的162个完整输入哈希及采集脚本哈希也全部匹配；之后只更新验收/task/work/change-log，不改被测实现、测试、YAML或runbook契约输入。

两项skip未新增：Linux缺cpp-judge-runner:latest镜像；Windows Hyper-V探针因当前runtime为Linux不适用。它们不计为通过，不能凭本项开启生产provider。

## 逐项客户端证据

| 验收项 | 测试与实际行为 |
| --- | --- |
| 三处配置统一 | WorkerAuthenticationConfigurationTest：主配置/嵌套/Options共享解析；主配置单独生效、同值/单来源通过，endpoint/Bearer/HMAC冲突拒绝且错误不含值；真实Spring容器可装配RemoteWorkerRunner和启动校验器 |
| 伪mTLS拒绝 | cert-only、mTLS与Bearer/HMAC混用均拒绝；两配置对象不将证书路径视作认证；真实TLS成功请求要求不存在X-Judge-Worker-Mtls头 |
| 安全端点 | HTTP/FTP、缺host、无效port、user-info、query/fragment及非法URI被拒绝；不输出原始URI/认证值；没有匿名或direct补救分支 |
| Bearer/HMAC正例 | 3种模式（Bearer、HMAC、两者）在临时CA信任下完成真实HTTPS GET和含JSON正文的POST；本地协议fixture核验原method+换行+pathAndQuery+换行+body签名输入 |
| 错误认证 | 2种错误凭证实际到达已建立TLS的服务端并返回401；客户端不可用，计数只有1次，不匿名重试 |
| 服务端身份反例 | 过期、SAN不匹配、不可信CA共3项；独立检查过期证书确已过期，再要求实际PKIX或subject alternative错误，不能用连接拒绝冒充；HTTP handler计数0，认证信息未进入应用请求 |
| 重定向 | 301/302/303/307/308×HTTP/HTTPS不同端口目标共10项；源端收到正确Bearer/HMAC，目标端请求数0，客户端返回不可用而不跨端点携凭证；显式NEVER是约束与回归保护，不声称前测已证明旧客户端发生泄密 |
| 既有协议兼容 | RemoteWorkerRunnerTest原6项迁移为真正HTTPS，仍覆盖能力/提交/轮询/取消/不可用事件/缺认证拒绝；ProductionSecurityStartupValidatorTest原8项保持生产隔离和账户门槛 |

## TLS环境与复跑

- 用JDK自带keytool在JUnit临时目录生成RSA2048测试CA和签发证书；CA从前一天起有效30天，有效叶证书从前一天起有效2天，过期叶证书从10天前起有效2天，错主机证书SAN为不同DNS名称。每次重新生成，私钥、CSR、keystore和keytool原始输出不入提交。
- 使用真实HttpsServer/Java HttpClient、标准KeyManagerFactory/TrustManagerFactory。信任库仅在测试的SSLContext内，保留PKIX和hostname校验；不使用trust-all、不关闭hostname、不修改全局SSLContext或系统信任库。
- TLS证书反例的具体错误文本在本次OpenJDK21环境断言，不宣称Windows、其它JDK或生产部署已实测。真实网络均在独立网络命名空间内的127.0.0.1临时端口。
- 外层限制：4GiB内存、无swap、2核CPU配额、512任务、600秒上限、2GiB tmpfs、源码只读，只有本任务证据目录可写；keytool子进程30秒上限及强制结束检查。宿主Docker socket仍未隔绝，仅用于受信回归，不能作为恶意代码生产隔离证明。

PowerShell复跑示例，须使用新的unit名及不存在的证据目录：

~~~powershell
wsl -d Ubuntu -- systemd-run --wait --pipe --collect --unit=wjc-task08-recheck -p MemoryMax=4G -p MemorySwapMax=0 -p CPUQuota=200% -p TasksMax=512 -p RuntimeMaxSec=600 -p PrivateNetwork=yes -p ProtectSystem=strict -p ProtectHome=read-only -p NoNewPrivileges=yes -p TemporaryFileSystem=/tmp:rw,size=2G -p ReadOnlyPaths=/mnt/c/Users/29901/Documents/dp/web-judge-c -p ReadWritePaths=/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-08 /bin/bash "/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-00/run-baseline.sh" "/mnt/c/Users/29901/Documents/dp/web-judge-c" "/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-08/recheck" worktree "-Dtest=WorkerAuthenticationConfigurationTest,RemoteWorkerRunnerTest,RemoteWorkerTlsTest,ProductionSecurityStartupValidatorTest"
~~~

去掉最后的-Dtest参数为全量；原单项诊断参数为-Dtest=JudgeBaselineTest#runtimeErrorIsRecordedAsSingleCaseRe，PowerShell中同样必须使用双引号。

## 迁移与未完成项

- 只保留一个明确配置来源，或确保主/嵌套重叠字段相同；新增嵌套signing-secret用于与主配置相同的HMAC模式。不得把证书路径字符串、伪mTLS头或capability标志当作已认证证明。
- 使用最终HTTPS地址及经过审批的可信服务端证书；HTTP即使回环开发也拒绝。移除mtls-certificate配置并使用实际支持的认证材料，不把未实现mTLS悄悄降级为其他模式。
- 所有重定向都不跟随；部署迁移应显式更新端点，不依靠转发认证头。原Bearer与HMAC签名输入保持不变，配置错误不回显材料。
- 未调用外部Worker/生产API，未变更部署信任库/代理/生产会话，未做生产回滚。回滚不得重新对外启用HTTP或伪认证；无安全回滚条件时保持服务关闭，演练留TASK-28。
- TASK-03限流/可信代理参数仍待批准；N5新增RE状态不稳定风险需要后续定位。不能把一次全量复验通过等同于全局稳定性问题已关闭。
