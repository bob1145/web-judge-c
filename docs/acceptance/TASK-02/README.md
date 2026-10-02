# TASK-02 验收证据

## 结论与范围

- 日期：2026-10-02；状态：开发验收通过，DONE提交候选在真实提交核验后生效；唯一提交定位键：TASK-02-C01。
- 父提交：bcf1d5ea6f40829dac9ef02ce6d526f2eef81d57。最终实现对应 R08/R09 的 worktree 快照，不能把父提交当成包含本项修复。
- 对应：RQ-A04/AC-A04a、AC-A04c/T-A06、T-A08；RQ-A05/AC-A05b/T-A10；D §3.1、§3.5、§8.1/8.3。
- HTTP 和应用日志的本项开发验收通过，不等于生产发布验收。DEF-18、N4为 IMPLEMENTED_PENDING_VALIDATION；N3仅HTTP子集已实现，WS留TASK-06/24，最终关闭留TASK-24/28/29。

## 实际运行

全部复用 TASK-00/run-baseline.sh；离线 Maven 3.8.7、Ubuntu 24.04.1/WSL、OpenJDK 21.0.12.1、Python 3.12.3。每次命令实际参数、环境、退出码、全部套件计数/失败原因和快照哈希分别见运行目录，不使用旧评审计数。

| 运行 | 范围 | 退出码 | 总数/通过/失败/错误/跳过 | 说明 |
| --- | --- | --- | --- | --- |
| R01 | 新增3类前测 | 1 | 0/0/0/0/0，0报告 | 测试编译失败；不可算产品失败 |
| R02 | 同前测，加脱敏编译诊断采集 | 1 | 0/0/0/0/0，0报告 | 错误的断言重载；改为两个单参数断言 |
| R03 | 原生产实现上的3类前测 | 1 | 39/7/31/1/0 | 认证/默认值/日志失败；其中3项包含错误HTTP状态假设，见work |
| R04 | 修复后10类定向 | 1 | 80/77/3/0/0 | 3项错误状态假设；保持原接口契约并强化业务断言 |
| R05 | 10类定向 | 0 | 80/80/0/0/0 | 第一轮通过 |
| R06 | 全量 | 0 | 260/258/0/0/2 | 原provider探针跳过 |
| R07 | Spring默认账号回退前测 | 1 | 2/1/1/0/0 | 删除YAML账号后仍生成inMemoryUserDetailsManager |
| R08 | 最终11类定向 | 0 | 83/83/0/0/0 | 加入无Spring默认账号及回环配置绑定验证 |
| R09 | 最终全量 | 0 | 262/260/0/0/2 | 38套件，无关键用例跳过 |

R08/R09最终源码/runner/scripts/POM共137个哈希、完整输入157个哈希及采集脚本哈希，均已逐项与当时工作树核对一致。随后只更新验收/task/work/change-log及脱敏归档，不改变被测源码、配置、测试或实际契约输入README。

两个跳过均为已有真实provider探针：Linux缺少cpp-judge-runner:latest镜像；当前runtime为Linux，Windows Hyper-V探针不适用。没有新建skip、禁用用例或把这两项计作通过。

## 逐项验收

| 条件 | 真实验证 | 边界 |
| --- | --- | --- |
| 统一HTTP凭证 | SessionCredentialResolverTest、SessionCredentialHttpTest | Cookie/Header分别及同值可用；不同值、空白、重复冲突拒绝；query不参与；普通页/管理/状态/下载及无MVC拦截器的直接控制器均验证 |
| 原认证响应兼容 | /auth/check success=false；/auth/session 200+valid=false且无sessionId；冲突logout不注销任一服务端会话 | 无身份的logout仍只清客户端Cookie，保留原200响应；没有为测试改状态码 |
| Token日志脱敏 | SessionCredentialLoggingTest开启应用DEBUG，实际执行两模式登录、Cookie、登出、无效/过期/清理、IP/UA告警、续期和审计；逐条检查原始会话、访问码、密码不出现，并确认DEBUG/INFO/WARN均产生 | SHA-256指纹可稳定关联；访问码审计userId及session/cookie/authorization键脱敏；原会话/所有权/配额标识不变；未清理历史生产日志 |
| 安全认证材料 | AuthenticationSafeDefaultsTest、原生产认证及两启动校验器测试 | Java/YAML无可用fallback；旧默认值、空白/null、自定义退役哨兵均拒绝；账号模式不因闲置访问码阻塞；本地账号模式同样校验有效BCrypt和启用账户；真实Spring上下文缺材料失败 |
| 不存在Spring默认账号 | Demo18ApplicationTests | R07失败证明回退；排除闲置UserDetailsServiceAutoConfiguration后无UserDetailsService bean，未新增第二套认证 |
| 回环默认与回归 | 静态YAML读取和实际Spring配置绑定；83项定向、262项全量 | 不宣称真实生产监听/TLS/浏览器/WS/容器通过；显式外部配置仍可覆盖监听地址，由后续部署验收负责 |

## 复跑示例（最终全量）

以下命令是在Windows PowerShell调用现有WSL，证据目录必须尚不存在。复跑时更换unit名及R目录，不能覆盖本次结果。

~~~powershell
wsl -d Ubuntu -- systemd-run --wait --pipe --collect --unit=wjc-task02-recheck -p MemoryMax=4G -p MemorySwapMax=0 -p CPUQuota=200% -p TasksMax=512 -p RuntimeMaxSec=600 -p PrivateNetwork=yes -p ProtectSystem=strict -p ProtectHome=read-only -p NoNewPrivileges=yes -p TemporaryFileSystem=/tmp:rw,size=2G -p ReadOnlyPaths=/mnt/c/Users/29901/Documents/dp/web-judge-c -p ReadWritePaths=/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-02 /bin/bash "/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-00/run-baseline.sh" "/mnt/c/Users/29901/Documents/dp/web-judge-c" "/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-02/recheck" worktree
~~~

定向时追加一个独立且加双引号的参数：

~~~text
-Dtest=SessionCredentialHttpTest,AuthenticationSafeDefaultsTest,SessionCredentialLoggingTest,SessionCredentialResolverTest,ProductionAuthenticationTest,SecurityModeStartupValidatorTest,ProductionSecurityStartupValidatorTest,ProductionSandboxBaselineTest,AuditAndAdminTest,ApplicationYamlExecutionProfileTest,Demo18ApplicationTests
~~~

外层限制为4GiB内存、无swap、2核配额、512任务、600秒看门狗、私有网络、2GiB临时文件系统和只读源码。宿主Docker socket并未隔绝，家目录只读不代表秘密不可见；仅用于已批准的受信回归，不作为恶意代码生产沙箱证明。

## 迁移与剩余风险

- 本地部署须从受控环境或外部配置提供随机访问码；不恢复弱码。账户模式仍使用现有应用账户，需显式启用并提供BCrypt材料，闲置访问码不参与校验。
- 程序调用迁移到X-Session-ID，浏览器使用JUDGE_SESSION；禁止凭证URL。重复同值接受，空白/不同值不回退另一来源。
- 会话JSON字段保持兼容，身份/配额不迁移；审计指纹不可用作认证凭证。
- 历史日志访问审查和会话轮换需独立运维批准，本项没有删除、轮换或修改生产数据/系统配置。
- 回滚只能在受控离线/停服情况下退回父版本，不得重新对外开放旧URL凭证、默认码或日志泄露路径。未执行部署回滚演练，留TASK-28。
- Cookie Secure、CSRF、重定向、来源/限流、WS、会话绑定和生产provider均有后续任务；仍不可发布。
