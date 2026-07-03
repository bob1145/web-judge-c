# Requirements Document

## Introduction

当前项目是一个 Java Spring Boot + Thymeleaf 的 C++ 在线对拍 Web 项目。用户提交数据生成器、待测程序、暴力或 Special Judge 代码，后端编译并运行大量随机测试点，通过 WebSocket/轮询展示结果。

本规格把目标从“本地/可信内网工具”升级为“可部署上线、同时兼顾 Windows 和 Linux 服务器、经常运行 100000+ 测试点的大样例对拍平台”。核心风险是：系统会编译和执行用户提供的 C++，因此上线版本必须把编译、运行、文件访问、网络访问、资源消耗和结果输出放进受控边界。

核心原则：

- Web 服务只负责任务入口、鉴权、排队、状态、结果展示；不直接信任或裸跑用户 C++。
- Windows 和 Linux 都必须通过统一的 sandbox runner 合约接入，平台差异留在 runner 实现内。
- Windows 上线优先使用 Hyper-V 隔离容器或独立 Worker VM；Job Object 只能作为进程资源控制层，不能单独当安全沙箱。
- Linux 上线优先使用容器隔离、cgroup v2、seccomp/AppArmor、禁网、只读根文件系统和非 root 用户；不能只依赖普通 `ProcessBuilder`。
- 100000+ case 是正式业务场景，必须用任务级 runner、批量/流式事件、摘要结果、配额和压测验证支撑。

## Alignment with Product Vision

仓库当前没有发现正式 `product.md`/`tech.md`/`structure.md` steering 文档。本规格按现有产品形态对齐：

- 服务对象：算法竞赛/刷题/出题用户，需要快速发现随机数据下的 WA/TLE/MLE/RE。
- 产品价值：让大规模对拍成为一键可运行、可取消、可复现、可审计的服务能力。
- 部署边界：支持 Windows 和 Linux 生产部署；公网或跨团队开放必须启用强认证、强沙箱、资源配额和审计。
- 非目标：本规格不包含题库、排行榜、支付、多语言平台化、分布式集群调度；这些应另开规格。

## Requirements

### Requirement 1: 跨平台生产沙箱边界

**User Story:** 作为部署者，我希望 Windows 和 Linux 都有明确且可验证的沙箱边界，这样上线后不会因为平台差异退化成裸跑用户代码。

#### Acceptance Criteria

1. WHEN 应用以生产 profile 启动 THEN 系统 SHALL 要求 `judge.sandbox.required=true`。
2. WHEN `judge.sandbox.required=true` AND 当前平台 runner 不可用 THEN 系统 SHALL 拒绝启动或拒绝创建任务，并返回可定位的配置错误。
3. IF 当前平台是 Windows THEN 系统 SHALL 支持 `windows-hyperv-container` 或 `remote-worker` provider；不得把 Job Object-only 模式标记为生产安全。
4. IF 当前平台是 Linux THEN 系统 SHALL 支持 `linux-container` 或 `remote-worker` provider，并验证 cgroup、禁网、非 root、seccomp/AppArmor 或等价策略。
5. WHEN runner 初始化 THEN 系统 SHALL 运行 capability probe，验证 provider、隔离模式、镜像/worker 版本、禁网、挂载目录和资源限制能力。
6. WHEN capability probe 失败 THEN 系统 SHALL 输出结构化失败原因，且不得静默降级到 direct runner。

### Requirement 2: 统一 Sandbox Runner 合约

**User Story:** 作为维护者，我希望业务代码只依赖统一的 runner 合约，这样 Windows/Linux/远程 Worker 可以替换而不影响 Controller 和任务流程。

#### Acceptance Criteria

1. WHEN `JudgeService` 启动任务 THEN 系统 SHALL 通过 `SandboxRunner` 接口提交任务，而不是直接在 Web 进程里编译或运行用户 C++。
2. WHEN runner 接收任务 THEN 系统 SHALL 获得完整 `SandboxTaskSpec`，包含 judgeId、工作目录、源码文件、case 数、时间/内存/输出/进程/网络/磁盘限制、结果保留策略。
3. WHEN runner 返回事件 THEN 系统 SHALL 使用统一 `SandboxTaskEvent` 协议表达 compile started、case progress、case result sample、summary、cancelled、failed、security violation。
4. IF 平台 provider 不同 THEN 事件协议、最终 summary 和错误码 SHALL 保持一致。
5. WHEN 发生 runner 内部错误 THEN Web 层 SHALL 将任务标记为 `SYSTEM_ERROR` 或 `SANDBOX_UNAVAILABLE`，不得让任务永远 `RUNNING`。

### Requirement 3: Windows 上线沙箱

**User Story:** 作为 Windows 部署者，我希望用户代码在 Hyper-V 隔离容器或专用 Worker VM 中运行，这样即使代码恶意也不直接接触 Web 服务宿主机。

#### Acceptance Criteria

1. WHEN provider 为 `windows-hyperv-container` THEN 系统 SHALL 使用 Hyper-V isolation 启动任务容器，且验证实际 isolation mode。
2. WHEN 任务容器启动 THEN 系统 SHALL 禁用网络，挂载唯一任务目录，限制 CPU、内存、进程数、任务总时长和磁盘输出。
3. WHEN 编译或运行子进程 THEN 容器内 runner SHALL 使用 Windows Job Object 或等价机制限制进程树、内存、CPU 时间，并在取消/超时时终止整棵进程树。
4. IF 容器运行时只支持 process isolation THEN 生产 profile SHALL 拒绝使用，除非配置显式标记为可信内网且风险确认。
5. WHEN Windows container 或 Hyper-V 不可用 THEN 系统 SHALL 支持切换到 `remote-worker`，把任务发往 Linux/Windows Worker VM；不得退回宿主机直跑。
6. WHEN Windows runner 完成任务 THEN 系统 SHALL 清理容器、临时挂载和残留进程，并记录清理结果。

### Requirement 4: Linux 上线沙箱

**User Story:** 作为 Linux 部署者，我希望用容器、cgroup 和系统调用限制隔离用户代码，这样平台能稳定承受大规模对拍和恶意代码。

#### Acceptance Criteria

1. WHEN provider 为 `linux-container` THEN 系统 SHALL 使用容器运行任务，且启用非 root 用户、禁网、只读根文件系统、临时工作目录、cgroup v2 资源限制。
2. WHEN Linux runner 启动 THEN 系统 SHALL 验证 memory/cpu/pids/io 限制可用，并验证禁网有效。
3. WHEN 运行用户程序 THEN 系统 SHALL 使用 seccomp/AppArmor 或等价机制阻断危险系统调用和越权文件访问。
4. WHEN 任务结束、取消或异常 THEN 系统 SHALL 终止容器和所有子进程，回收 cgroup 和临时目录。
5. IF 容器引擎不可用或安全 profile 未加载 THEN 生产 profile SHALL 拒绝任务。
6. WHEN Linux runner 产生安全违规 THEN 系统 SHALL 记录违规类型，但前端只展示安全、简洁的错误信息。

### Requirement 5: 任务级大样例执行模型

**User Story:** 作为算法用户，我希望经常跑 100000+ 测试点时服务仍然稳定，而不是为每个 case 创建大量 Java future、WebSocket 消息或 DOM 节点。

#### Acceptance Criteria

1. WHEN `testCases >= largeModeThreshold` THEN 系统 SHALL 进入 high-volume 模式，并通过任务级 runner 在沙箱内循环执行 case。
2. WHEN high-volume 模式运行 THEN Web 服务 SHALL 不为每个 case 创建 Java future。
3. WHEN high-volume 模式运行 THEN WebSocket SHALL 推送节流后的进度和样本事件，而不是 100000+ 条完整 case 事件。
4. WHEN high-volume 模式完成 THEN 最终 payload SHALL 包含 summary、失败样本、慢样本和首个失败点；不得包含 100000+ 条完整结果。
5. WHEN 任务需要保留复现数据 THEN 系统 SHALL 只保留失败样本、首个失败点和配置允许的样本集；全量保留必须由管理员配置开启。
6. WHEN 运行 100000、200000 或配置上限 case THEN 验证 SHALL 证明 peak Java heap、payload size、DOM node count 和 runner in-flight 数都受上限控制。

### Requirement 6: 生产级资源预算和配额

**User Story:** 作为平台管理员，我希望每个用户、每个任务和整个平台都有资源预算，这样大样例和恶意请求不会拖垮服务。

#### Acceptance Criteria

1. WHEN 用户创建任务 THEN 系统 SHALL 计算策略快照，包含 case 上限、任务总时长、单 case 时限、任务内存、输出上限、磁盘上限、并发上限、保留策略。
2. WHEN 用户达到并发任务数、排队任务数、每日 case 数或 CPU 时间配额 THEN 系统 SHALL 拒绝新任务并返回可理解错误。
3. WHEN 全站运行任务数达到上限 THEN 系统 SHALL 排队或拒绝，不得在请求线程中执行判题。
4. WHEN 输出、磁盘、stderr、编译日志超过上限 THEN 系统 SHALL 截断或终止，并标记 `OUTPUT_LIMIT_EXCEEDED`、`DISK_LIMIT_EXCEEDED` 或 `COMPILE_OUTPUT_LIMIT_EXCEEDED`。
5. WHEN 任务超过总预算 THEN 系统 SHALL 取消 runner，保存 partial summary，并标记 `BUDGET_EXCEEDED`。

### Requirement 7: 强认证、授权和会话安全

**User Story:** 作为平台管理员，我希望上线服务不是简单访问码保护，这样可以区分用户、限制配额、追踪操作并降低滥用风险。

#### Acceptance Criteria

1. WHEN 生产 profile 启动 THEN 系统 SHALL 禁止默认访问码 `123` 和硬编码示例账号密码。
2. WHEN 用户登录 THEN 系统 SHALL 使用账号体系和安全密码哈希，或接入明确配置的外部认证；不得只依赖共享访问码。
3. WHEN 用户访问任务详情、下载或取消任务 THEN 系统 SHALL 验证任务归属或管理员权限。
4. WHEN WebSocket 连接建立 THEN 系统 SHALL 校验认证状态和 Origin；生产 profile 禁止 `allowed-origins: "*"`。
5. WHEN 用户创建任务 THEN 系统 SHALL 记录用户 ID、IP、User-Agent、任务策略和审计事件。
6. WHEN 鉴权失败、配额拒绝或安全违规 THEN 系统 SHALL 记录审计日志并进行限流。

### Requirement 8: 文件、挂载和下载安全

**User Story:** 作为平台管理员，我希望任务目录、样例下载和容器挂载都被严格限制，这样用户不能读写服务端任意文件。

#### Acceptance Criteria

1. WHEN 创建任务 THEN 系统 SHALL 为 judgeId 创建唯一工作目录，且目录路径必须位于配置的 storage base 内。
2. WHEN runner 挂载目录 THEN 系统 SHALL 只挂载该任务目录，且使用最小读写权限。
3. WHEN 用户请求详情或下载 THEN 系统 SHALL 从 TaskStore 查任务并校验归属，再解析 case 文件；不得接受客户端传入路径。
4. WHEN 请求 caseNumber 非法、越界或不存在 THEN 系统 SHALL 返回 400/404，不泄漏绝对路径。
5. WHEN 下载全部样例 THEN 系统 SHALL 使用流式 zip 或分片下载；不得一次性把 zip 写入内存。
6. WHEN 清理任务 THEN 系统 SHALL 验证路径仍在 storage base 内，并防止 symlink/junction/reparse point 逃逸。

### Requirement 9: 可观测性、审计和运维控制

**User Story:** 作为维护者，我希望知道每个任务为什么慢、为什么失败、消耗多少资源，这样上线后可以定位问题和调整容量。

#### Acceptance Criteria

1. WHEN 任务创建、排队、开始、编译、运行、取消、完成、失败或清理 THEN 系统 SHALL 记录结构化事件。
2. WHEN runner 上报进度 THEN 系统 SHALL 保存 completed cases、status counters、case/s、峰值内存、峰值磁盘、输出截断次数、容器 ID 或 worker ID。
3. WHEN 安全违规发生 THEN 系统 SHALL 记录违规类型、runner provider、judgeId、userId 和处置结果。
4. WHEN 管理员查询任务 THEN 系统 SHALL 能看到队列深度、运行任务、历史失败原因和资源耗用摘要。
5. WHEN 服务启动 THEN 系统 SHALL 标记历史 running/queued 任务为 stale 或重新对账，不得保留假运行状态。

### Requirement 10: 压测、故障注入和上线验收

**User Story:** 作为负责人，我希望每项改动都有强验证，这样不会因为“看起来能跑”就把危险实现部署上线。

#### Acceptance Criteria

1. WHEN 完成任一 sandbox provider THEN 系统 SHALL 提供自动化 capability test，验证禁网、资源限制、路径隔离、超时杀进程和输出限制。
2. WHEN 完成 high-volume runner THEN 系统 SHALL 通过 100、10000、100000 case 自动化验收，并至少提供 200000 case 可选压测脚本。
3. WHEN 完成 Windows provider THEN 验证 SHALL 证明 Hyper-V isolation 生效，Job Object 能杀子进程树，容器退出后无残留进程。
4. WHEN 完成 Linux provider THEN 验证 SHALL 证明 cgroup/pids/memory/cpu 限制、禁网、非 root、seccomp/AppArmor 或等价策略生效。
5. WHEN 完成生产安全配置 THEN 验证 SHALL 证明 insecure profile 无法在生产启动，未认证/越权请求被拒绝。
6. WHEN 完成所有任务 THEN 系统 SHALL 运行全量回归 `mvn test`、平台专项 smoke test、压力测试和文档验收；任何一项失败不得标记完成。

## Non-Functional Requirements

### Code Architecture and Modularity

- **Single Responsibility Principle**: `JudgeService` 不再直接负责编译、进程执行、下载、安全策略和平台细节；这些职责拆到 focused services。
- **Modular Design**: `SandboxRunner` 是平台边界；Windows/Linux/remote worker provider 不影响 Controller/API 合约。
- **Dependency Management**: 新依赖必须服务于隔离、资源控制、认证、审计或测试；不得为了包装命令行引入重型框架。
- **Clear Interfaces**: Task spec、event、summary、错误码、capability probe 结果都必须有稳定 DTO/record。

### Performance

- 100000+ case 不得产生线性增长的 WebSocket payload、DOM node、Java future 或内存结果集合。
- high-volume 进度默认每秒最多 1 次普通推送；状态变更立即推送。
- 任务级 runner 应支持长任务稳定执行，并持续写入可恢复 summary。
- 全量下载必须流式或分片，不得全量入内存。

### Security

- 生产环境必须启用强沙箱和强认证。
- Windows Job Object-only、Linux direct process-only 都不得作为生产安全边界。
- 编译、运行、Special Judge、生成器全部按不可信代码处理。
- 默认口令、wildcard origin、禁用沙箱、宿主机直跑在生产 profile 中必须被拒绝。

### Reliability

- 任务可取消、可超时、可恢复查询、可清理。
- runner 崩溃、容器启动失败、Worker 断连都必须落到明确终态。
- 服务重启后历史任务不得卡在 running/queued。
- 清理失败必须可见，并在下次启动补偿。

### Usability

- 普通用户看到的是任务排队、运行、失败样本、下载复现和配额提示，不暴露底层容器命令。
- 管理员看到 provider 状态、队列深度、资源耗用、失败原因和清理状态。
- 错误文案使用业务语言，例如“当前账号今日可运行 200000 个测试点，已用完”，而不是只展示异常类名。
