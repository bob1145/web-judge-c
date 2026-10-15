# 缺陷修复开发工作日志

## 1. 用途与真实性约束

- 日期：2026-10-02；任务入口：`docs/task.md`。
- 需求依据：`docs/defect-remediation-requirements-2026-10-02.md`；设计依据：`docs/defect-remediation-design-2026-10-02.md`。
- 历史证据依据：主评审与动态附录；证据冲突以动态附录为准。
- 本文件是追加式执行账本，不是计划副本。记录实际动作、结果、失败与证据，不把预期效果写成已实现效果。
- 每个任务完成或受阻时更新；复测另开记录，保留首次失败、环境错误和跳过，不覆盖历史使其看似一直通过。
- 命令、环境、退出码、通过/失败/错误/跳过数量必须来自实际输出；未执行填“未执行”，未知填“未知”，不预填零或通过。
- 证据只存脱敏日志/截图/报告路径，不记录会话、访问码、密钥、完整秘密配置或不必要的用户数据。

## 2. 状态与提交协议

- `NOT_STARTED`：尚未实施；`IN_PROGRESS`：正在实施；`BLOCKED`：前置、授权或环境阻断，记录解除条件。
- `VERIFIED_PENDING_COMMIT`：全部适用测试及验收通过，尚无匹配本地提交；不得汇报已完成。
- `DONE`：全部门槛满足、工作记录完整且匹配本地提交确实存在；文件声明与Git证据联合核验，单独改状态不构成完成。
- 必需测试跳过、环境不具备、mock通过、模板检查均不等于真实验收通过；平台排除须有批准记录和明确禁用证据。
- 提交前复核工作区与差异，精确暂存本任务文件及本日志，不夹带其他任务或用户未提交修改。
- 实现、测试、`docs/task.md`状态及本次日志必须进入同一个本地提交；每任务完成后提交，不推送、不amend、不擅自建分支。
- 保存唯一提交主题标识，如 `fix(auth): TASK-02 credential security [TASK-02-C01]`；提交编号C01/C02递增，同时记录提交前父SHA。执行尝试编号R01/R02独立，多次失败/复测记录可以关联同一个待提交C编号，不能混作提交定位键。
- 本次日志自身SHA填“由唯一主题定位，尚未生成”；禁止伪填、自引用，禁止为回填自身SHA amend。
- 提交后执行 `git log -1 --format=%H --fixed-strings --grep="[TASK-02-C01]"` 获取真实SHA；另去掉 `-1` 检查匹配数量必须唯一。
- 另核对父SHA、提交差异和日志归属；提交成功向用户汇报真实SHA，后续任务可追加前次SHA，无须只为回填自身SHA额外提交。
- 提交失败或核验不符，恢复VERIFIED_PENDING_COMMIT，追加错误和工作区状态，不报告DONE、不强制重试/重置/清理。
- 准备提交快照可填DONE和完成计数，但匹配提交存在前声明不生效；失败必须撤回状态并记录失败。

### 2.1 高风险授权

逐任务本地提交是执行目标，不等于已满足危险操作确认。执行前按用户规定确认；可一次明确确认覆盖列定TASK-00～29的本地提交，其他危险操作另行确认。

| 授权范围 | 当前状态 | 授权人/时间/原话 | 不包含 |
| --- | --- | --- | --- |
| TASK-00～29验收后的本地commit | 已批准精确范围逐任务提交 | 用户，2026-10-02，会话原话“批准”；对应前轮明确确认请求，详见TASK-00 R03/R04 | push、amend、reset、建分支、无关修改、删除、系统/生产操作 |
| 删除/批量修改/环境或数据操作 | 未授权 | 按实际影响单独确认 | 不被提交授权涵盖 |

```text
⚠️ 危险操作检测！
操作类型：验收完成后的本地git commit
影响范围：明确列出任务、仓库、实现/测试、docs/task.md及docs/work.md；不推送、不amend、不建分支
风险评估：形成持久本地历史；提交前核验范围，避免夹带无关文件和敏感信息
请确认是否继续？[需要明确的“是”、“确认”、“继续”]
```

## 3. 开发任务总台账

任务完成：**6/30**；TASK-00/01/02/08/09/10真实提交已核验，10交付详见[TASK-10-C02]。11协议验真切片已实际修复/验证，有界reader预算待批准；03/15参数仍未冻结。缺陷最终关闭和生产验收尚未完成，Goal active，DONE须有§2的提交证据。

| 任务 | 名称 | 状态 | 最新记录/提交定位 |
| --- | --- | --- | --- |
| TASK-00 | 决策与执行基线 | DONE | 4c86d830edc9a927e32377291bac20641ac1e557；[TASK-00-C01] |
| TASK-01 | 回归基线与解释器 | DONE | bcf1d5ea6f40829dac9ef02ce6d526f2eef81d57；[TASK-01-C01] |
| TASK-02 | 凭证解析安全默认日志 | DONE | 6f3b479b8cd37bc29cb862495515318066e88618；[TASK-02-C01] |
| TASK-03 | 可信代理有界限流 | BLOCKED | 代理/限流定量参数待批准；TASK-00延期边界，见task对应卡 |
| TASK-04 | Cookie重定向CSRF | BLOCKED | R06受影响145/145，R08真实HTTP CSRF14/14；03前置、浏览器/生产完整验收未闭环 |
| TASK-05 | 双模式登录 | BLOCKED | R01模式/错误修复及75/75；04前置与真实浏览器鉴权入口未闭环 |
| TASK-06 | WebSocket全链路授权 | BLOCKED | [TASK-06-ACCOUNT-R02]；SEND、身份/精确订阅、CSRF及账户复核已有证据；03/04、无帧主动撤销、预算及浏览器未闭环 |
| TASK-07 | 会话环境绑定 | NOT_STARTED | — |
| TASK-08 | Worker真实HTTPS认证 | DONE | d40192bca8aa63011b60e6fa7d68d057b37be14f；[TASK-08-C01] |
| TASK-09 | Runner v2与路径 | DONE | 5f672c2faa9d02e2e3cd95c832ef4623bc222301；[TASK-09-C01] |
| TASK-10 | 控制面身份隔离 | DONE | a9d2e11768972977fec03e512a9dc02bd91f3ae7；799fc1cbb85ceae91327d6e2710eabb47afd602b；[TASK-10-C02] |
| TASK-11 | 增量事件验真 | BLOCKED（reader预算前置） | [TASK-11-R08]协议切片已验证；未提交/未交付 |
| TASK-12 | 轮询预算与退出收尾 | BLOCKED | [TASK-12-UNKNOWN-START-R02]；预算/真实未知启动对账未闭环，未交付 |
| TASK-13 | 在线输出限额 | BLOCKED | [TASK-13-CAPTURE-IO-R03]；在线额度及捕获错误分类已验证，12/14与资源/平台验收未闭环 |
| TASK-14 | 全进程域回收 | BLOCKED | [TASK-14-PROVIDER-GUARD-R02]；真实整域回收/清理预算未闭环，未交付 |
| TASK-15 | 比较向量与Java精度 | BLOCKED | R02整数/非有限切片32/32；完整十进制/precision/解析上界与错误映射未闭环 |
| TASK-16 | 跨Provider比较贯通 | BLOCKED | [TASK-16-SPJ-R02]；原SPJ异常映射切片已验证，12/14/15及完整跨Provider比较未闭环 |
| TASK-17 | 权威终态提交 | BLOCKED | [TASK-17-TERMINAL-SNAPSHOT-R04]；现有快照保护/失败传播已验证，原子final/兼容批准及11/12前置未闭环 |
| TASK-18 | CREATED与幂等启动取消 | BLOCKED | [TASK-18-R01]；CREATED真实HTTP已绿，TTL/竞态及14/17前置未完成 |
| TASK-19 | 全非终态重启恢复 | BLOCKED | [TASK-19-ORPHAN-EVIDENCE-R02]；UNKNOWN/持久占用/final/真实进程恢复未闭环，未交付 |
| TASK-20 | 原子创建配额 | BLOCKED | 创建/回滚/每日计费/保留占用切片已验证，18/19整体前置未满足；见task卡 |
| TASK-21 | 用户运行许可 | BLOCKED | [TASK-21-REJECT-WRITE-R02]；20前置及实际停止后的占用恢复未闭环，未交付 |
| TASK-22 | 前端错误恢复Toast | BLOCKED | [TASK-22-LIFECYCLE-R06]；原ID重试/取消和通道退出已修，前置及真实浏览器验收未闭环 |
| TASK-23 | 运行摘要缓存生命周期 | BLOCKED | [TASK-23-CACHE-CLEANUP-R01]；发送竞态/真实四入口统计/确认删除后的缓存回收已验，前置、TTL/容量、发送预算及版本/出站有序未闭环 |
| TASK-24 | 真实HTTP WS浏览器验收 | NOT_STARTED | — |
| TASK-25 | 真实Linux隔离验收 | NOT_STARTED | — |
| TASK-26 | Windows Worker能力矩阵 | NOT_STARTED | — |
| TASK-27 | 故障注入资源上界 | NOT_STARTED | — |
| TASK-28 | 部署迁移回滚演练 | NOT_STARTED | — |
| TASK-29 | 最终严格验收 | NOT_STARTED | — |

## 4. 产品与部署决策账本

完整建议及批准门槛以需求§7为准。未批准不等于默认接受；影响验收语义的决策未落定，对应任务不得DONE。

| 决策 | 待批准内容 | 状态 | 批准人/时间/依据及最终值 |
| --- | --- | --- | --- |
| DEC-01 | 输出额度、双流合计方式、生成器范围 | 已批准；诊断预算延期 | 用户/2026-10-02/“批准”；默认10MiB合计，生成器同限；诊断额度TASK-13前冻结 |
| DEC-02 | CREATED TTL、重启终态及不自动续跑 | 已批准；清理预算延期 | 同次批准；15分钟TTL，重启STALE/RESTART_INTERRUPTED不续跑；清理TASK-18/19前冻结 |
| DEC-03 | 数字比较、precision缺省、编码空白及解析上界 | 语义已批准；解析上界延期 | 同次批准；整数精确/小数绝对误差/非有限文本；API0、页面1e-9；上界及错误映射TASK-15前冻结 |
| DEC-04 | 会话环境绑定及可信来源变化矩阵 | 策略已批准；部署细项延期 | 同次批准；管理员STRICT/普通AUDIT可配置；来源及UA矩阵TASK-03/07前冻结 |
| DEC-05 | Worker HTTPS认证与是否首期必须真实mTLS | 已批准首期不要求真实mTLS | 同次批准；拒绝伪cert-only，实际HTTPS Bearer/HMAC验收仍待08/26 |
| DEC-06 | URL凭证/弱码迁移、Secure/CSRF、回环/代理/Origin配置 | 迁移方向已批准；部署值延期 | 同次批准；移除弱默认/URL凭证，Secure/SameSite/CSRF/回环；代理Origin及生产操作另审 |
| DEC-07 | 有界参数、目标容量与平台/provider发布范围 | Linux目标已批准；定量参数延期 | 同次批准；其他未验收provider禁用待实现；按approval-and-baseline.md逐任务冻结 |
| DEC-08 | 重复start/cancel响应、终态响应及兼容字段 | 已批准 | 同次批准；运行start200/终态409；终态cancel accepted=false；无权/不存在404 |

## 5. 逐次执行记录模板

复制本节结构追加到§7，不把空白模板当证据；每个任务可有多次尝试。适用验收逐项展开，不能仅写“全部通过”。

### YYYY-MM-DD HH:mm — TASK-XX / RNN — 状态

- 执行人/角色、复核人、时区：待填写；缺复核须注明，不冒填他人批准。
- 任务目标、预期效果、实际结果：分别填写，说明是否一致。
- 需求追溯：RQ、AC、DEF/N、需求章节；设计追溯：具体章节、协议/状态/资源边界；依赖任务状态。
- 决策及授权：对应DEC已批准值与记录；适用危险操作授权记录。
- 基线：仓库/分支（只记录）、起始SHA、原有修改、OS/JDK/Python/Node、profile/provider、镜像/Worker版本、生效配置摘要。
- 实际工作：逐条写修改内容、原因及未修改内容，不复制计划伪装完成。
- 文件变更：逐文件列路径与目的，区分实现/测试/配置/文档；说明KISS/DRY/SOLID/YAGNI的具体取舍。

| 序号 | 完整命令及工作目录 | 环境/数据/预算 | 时间/退出码 | 通过/失败/错误/跳过 | 脱敏证据 |
| --- | --- | --- | --- | --- | --- |
| 1 | 待实际执行填写 | 待填写 | 未执行 | 未执行 | — |

| 验收项/需求AC/设计条款 | 正例/负例/边界及预期 | 实际结果/量化值 | 证据与结论 |
| --- | --- | --- | --- |
| 逐项展开权限、异常、并发、资源和恢复中的适用项 | 待填写 | 未执行 | 未验收 |

- 测试结论：新增/定向回归/全量回归/真实集成各自结果；必需未执行或跳过项与原因；区分历史失败、新增回归。
- 差异风险：需求/设计偏差、剩余风险、兼容、迁移回滚、不覆盖范围；范围变更批准人和证据。
- 复核：范围、安全、并发/资源、测试证据、文档一致性、无敏感信息，逐项通过或阻断。
- 提交准备：明确暂存路径、差异/敏感信息检查结果、`git diff --check`实际结果。
- 提交定位：执行记录编号RNN、唯一提交主题`[TASK-XX-CNN]`、完整主题、父SHA；自身SHA填“由唯一主题定位，尚未生成”，提交完成后该文字仅表示提交前快照，不代表提交仍待执行，真实结果按Git核验。
- 提交结果：失败追加脱敏错误并恢复VERIFIED_PENDING_COMMIT；成功以Git核验，不为填自身SHA amend。
- 用户汇报：核验提交后报告任务状态、实际工作、测试/验收、风险、真实SHA；后续记录可补发送时间与前次真实SHA。
- 台账更新：完成数、阻断数、下一任务前置；有提交不自动等于缺陷关闭，仍需目标环境证据。

## 6. 阻断与失败模板

### YYYY-MM-DD HH:mm — TASK-XX / BLOCK-NN

- 阶段/原状态/现状态及选择原因：待填写。
- 失败命令/动作、环境、退出码、实际错误和脱敏证据：待填写。
- 影响需求/设计/依赖任务，已实施但未验收内容：待填写。
- 未执行/失败/跳过测试和验收逐项列出；清理状态、残余资源风险：待填写。
- 解除条件、责任人、所需决定/授权/环境、用户汇报：待填写。
- 恢复时追加重测，不删除本记录；未经授权不改全局Git安全配置、不改环境、不删除用户内容。

## 7. 实际记录（只追加）

### 2026-10-02 — PLAN-INITIAL — 规划交付

- 性质：编制后续开发任务及日志制度，不是开发实施，不计入TASK-00。
- 依据：读取需求/设计，保留31个缺陷独立验收、真实环境证据、未批准决策和发布门槛。
- 产出：`docs/task.md`承载开发规划；本文件承载台账、日志模板、决策/授权和提交定位规则。
- 状态：开发0/30，TASK-00～29全部NOT_STARTED，DEC全部待批准；未取得本地提交危险操作确认。
- 业务修复：未实施；业务测试、真实HTTP/WS/浏览器/provider、攻击或故障注入：未运行。
- Git：本次规划未commit、未push、未amend、未建分支，没有本次任务完成SHA。
- 历史测试仅属评审基线，不计此次测试；静态文档检查由实际执行者追加，不替代业务验收。
- 环境观察：日志编写子代理读取仓库HEAD遇dubious ownership拒绝，未修改全局safe.directory；这是该次执行的环境记录，不代表主代理随后也无法读取，后续核验见追加记录。
- 下一步：评审task.md，确认决策、验证环境与逐任务提交授权，再按依赖实施。

### 2026-10-02 — PLAN-VERIFY — 规划静态校验与交叉复核

- 性质：仅验收本次规划文档，不是TASK-00，不推进30个开发任务；当前仍0/30。
- 实际工作：主代理完成30张任务卡，逐项给出RQ/AC、设计小节、角色、提示词、依赖、目标/预期、实现、严格测试/验收及提交主题；覆盖31缺陷独立追溯，增加逐任务实做汇报与本地commit协议。
- 实际修订：统一提交定位CNN与执行尝试RNN；澄清TASK-29自身提交前后的审计顺序；公网发布至少一个真实验收隔离provider；N5同时关联基线及输出边界验收。
- 文件：新增`docs/task.md`与`docs/work.md`；本次未修改需求/设计、原review或业务代码，未处理既有无关未跟踪产物。
- 基线核验：主代理在项目根执行`git rev-parse HEAD`成功，实际为`f31d039183c690f36d7a07e25da911ce1ace55db`；与前条子代理环境失败分别保留，没有改Git安全配置。
- 实际静态检查：PowerShell内联断言校验30张卡每卡9类必需字段、30个索引及NOT_STARTED台账、依赖全部指向前序无环、31行独立缺陷追溯、20个RQ编号引用、UTF-8无替换字符/无尾随空白/代码围栏配对；工具退出码0，全部PASS。完整内联命令及输出见本轮工具执行记录。
- Git检查：`git diff --check`退出码0；另直接读取新增文件做静态校验，因为未跟踪文件不在普通git diff范围内。`git status --short`确认新文档仍未提交。
- 交叉复核：安全任务规划复核代理检查任务字段、授权/DONE/提交协议；runner规划复核代理检查依赖、安全证据和31缺陷矩阵。定位键与N5追溯建议已修正，未把代理复核当产品决策批准。
- 未执行：业务单测/全量Maven、Python、HTTP/WS、浏览器、真实provider、攻击/故障注入及部署均未运行；不能据此关闭任何缺陷。
- Git交付：本次规划未commit、未push、未amend、未建分支，无虚构自身SHA；逐任务提交将在正式开发、验收和必要确认后执行。
- 用户汇报摘要：提供task/work路径、30任务/31缺陷覆盖、每项日志/独立本地提交/即时汇报规则，说明当前只完成规划。
- 下一步：用户评审后启动TASK-00，记录DEC-01～08、目标环境与本地提交授权，之后按依赖执行。

### 2026-10-02 — TASK-00 / R01 — 基线核查及审批阻断

- 主代理执行；baseline_inspection 子代理只读复核。追溯 R §1/2/4/7、DEC-01～08；D §1/2/8/9/10；不代表产品或运维批准，完成仍为0/30。
- HEAD：f31d039183c690f36d7a07e25da911ce1ace55db；git log --all --oneline --grep=TASK- 无匹配。四份输入及task/work仍未跟踪，另有.mimosa/、.playwright-mcp/，全部保留，未暂存或清理；输入归属待确认。未发现实体AGENTS.md，遵循会话指令。
- 当前PATH未发现java/mvn/python/python3/py/docker；不等于整机未安装。系统Windows NT 10.0.26200.0；Git2.55.0；Node24.18.0；npm.cmd11.16.0；MinGW-W64 15.2.0；Clang22.1.8。Chrome154.0.8037.58、Edge154.0.4258.37仅安装文件版本，未启动。
- wsl --list --quiet 返回Wsl/EnumerateDistros/Service/E_ACCESSDENIED；用户Python目录访问拒绝；不能断言无WSL或Python。npm.ps1被策略拒绝，未改策略。
- 实际执行 .\mvnw.cmd test：沙箱第一次退出1，.m2/wrapper/dists创建权限拒绝；按权限机制提权重试退出1，JAVA_HOME配置不正确。两次均未启动测试，通过/失败/错误/跳过计数不可用，不引用历史193项数字。命令已终止，无后台测试可等待。
- pom目标Java17；Wrapper配置Maven3.9.10（非成功运行版本）；33个Test类；Python入口 python -m unittest discover -s runner/tests；TaskRunnerArtifactContractTest仍硬编码python，未修复。
- 本轮PowerShell内联结构校验退出0：tasks=30、required-fields=270、defect-matrix=31、backward-dependency-check通过；此前中断检查不计通过。子代理依赖图解析退出0：29个后续任务全部依赖00，无可独立开工任务。git diff --check退出0，但不覆盖未跟踪文件。
- 未执行业务用例、Python/HTTP/WS/TLS/浏览器/provider或攻击/资源/故障测试；没有关闭缺陷、没有发布许可；未启动服务或修改系统环境。
- 实际修改仅task状态和本日志，复用台账遵循KISS/DRY；不提前实施未批准语义，遵循YAGNI。

#### 待批准建议及影响

| 决策 | 建议（全部未批准） |
| --- | --- |
| DEC-01 | 保留10MiB，stdout+stderr按单次执行合计，生成器同限；编译诊断另设有限预算，影响OLE边界 |
| DEC-02 | CREATED 15分钟TTL，重启非终态STALE/RESTART_INTERRUPTED，不续跑，用户需重新提交 |
| DEC-03 | 整数精确、小数十进制绝对误差、非有限文本精确；API缺省0、页面1e-9；严格UTF-8/ASCII空白；解析上界和错误映射须实现前冻结 |
| DEC-04 | 管理员STRICT，可信来源/UA变化再认证；普通用户默认AUDIT且可配置，需确认NAT/移动网络影响 |
| DEC-05 | 首期拒绝伪cert-only，仅实际验收HTTPS Bearer/HMAC；需确认是否首期必须真实mTLS |
| DEC-06 | 移除URL token/弱码，回环默认、Secure/SameSite/CSRF；需确认迁移及代理CIDR/Origin，不擅自处理生产日志/会话 |
| DEC-07 | 建议首期Linux隔离provider；direct仅可信本地；Windows/native/remote无证据保持未验收且生产禁用；需确认目标容量和资源预算 |
| DEC-08 | 已排队/运行start200，终态start409；终态cancel accepted=false及原状态，无权/不存在404 |

- 设计候选轮询100ms、flush1s、清理2s、事件批次256KiB或256条、单行128KiB均未批准。事件累计量、限流/WS/进度容量、撤销时限、数字解析上界及内存/磁盘/测量容差尚需冻结；建议明确机器容量后逐任务审批，不测后放宽。
- 环境解除条件：提供Java17+/Python3路径或授权检查已有WSL；明确隔离执行域、TLS/Worker、浏览器和CPU/内存/磁盘/watchdog预算。工具版本查询不构成攻击测试授权。
- 授权解除条件：确认四份输入归属；按危险操作格式批准TASK-00～29验收后精确范围的本地commit，不含push/amend/reset/建分支/无关文件/删除/系统配置变更。DEC批准人和时间仍空缺，不把自动续轮或工具前缀许可视为业务批准。
- 预留提交定位 [TASK-00-C01]，主题 docs(plan): TASK-00 freeze execution baseline [TASK-00-C01]；父SHA如上，自身SHA未生成；未commit、未push。两次apply_patch调用因参数传递失败，未产生文件变更，随后改用同一apply_patch入口的直接参数调用。
- 下一步：用户批准或明确延期DEC及定量阈值，确认环境/平台/归属/提交授权；完成剩余验收并提交后才进入TASK-01。当前TASK-00为BLOCKED而非DONE，Goal未完成。

### 2026-10-02 — TASK-00 / R02 — 恢复后的环境证据补充

- 恢复后重新读取任务附件、HEAD、工作区及R01；HEAD未变，无新增决策批准、授权或提交。先前目标已BLOCKED，本次恢复按新的阻断审计计数，不将自动续轮解释为业务批准。
- 只读检查常规JDK安装目录未定位可用JDK；Python安装目录的Test-Path再次访问拒绝。随后按权限机制提权执行 wsl --list --quiet 和Python目录枚举，总退出码0，WSL退出码0。
- 新证据：现有发行版Ubuntu；目录 C:/Users/29901/AppData/Local/Programs/Python/Python314 和 Launcher 存在。这里只证明发行版登记和安装目录存在，尚未运行解释器验证版本/完整性，不证明Java或业务测试可用。R01中的访问拒绝历史保留，不再以该拒绝推断环境不存在。
- 本轮未启动WSL发行版、未安装软件、未更改系统配置、未运行测试、未提交或推送。没有实际业务用例计数。仅追加本日志，不改变任何DEC或完成数。
- 下一步仍需用户确认DEC、目标环境/资源边界、输入文档归属和本地提交授权；可使用新发现的Ubuntu/Python路径安排后续验证，但不能因此绕过TASK-00准入。

### 2026-10-02 — TASK-00 / R03、R04 — 批准后执行基线与验收

- 用户在明确审批请求后回复“批准”；记录DEC语义及未定参数延期、现有WSL开发验证、首期Linux目标、四份输入归属和逐任务本地提交授权。原R01/R02未批准历史保留，不倒填过去。详细边界见docs/acceptance/TASK-00/approval-and-baseline.md。
- 对应R §1/2/4/7、DEC-01～08和D §1/2/8/9/10。阶段从BLOCKED恢复，完成TASK-00的决策/环境/红基线记录；不宣称业务修复、真实provider或发布验收通过。
- 起始父SHA f31d039183c690f36d7a07e25da911ce1ace55db；.mimosa/和.playwright-mcp/仍为既有任务外目录，不纳入提交。四份输入归档，仅对动态附录两处凭据脱敏，不改变评审事实/代码位置。
- 实际新建run-baseline.sh、approval-and-baseline.md、verify-plan.ps1和生成的R03/R04脱敏证据。共享task/work由主代理维护；baseline_inspection仅负责附录脱敏/静态复核，baseline_review只读复核脚本与结果。
- 使用现有WSL Ubuntu24.04.1/OpenJDK21.0.12.1/Maven3.8.7/Python3.12.3/g++13.3.0；Docker服务版本29.1.3仅能力查询，没有真实provider任务。未安装软件、未改全局环境或策略。
- 外层临时服务限制4GiB内存、swap0、2核、512任务、2GiB tmpfs、10分钟截止，进程独立网络空间无IP路由、系统/家目录/源码只读、NoNewPrivileges。仓库仅证据目录可写，/tmp可写，宿主Docker socket未隔绝；不能保证经宿主服务的网络/写入隔离。只运行受信仓库默认测试，不作为生产恶意代码隔离保证；实际配置保存在R04/unit-properties.txt。

| 实际执行（项目根） | 结果/退出码 | 证据与边界 |
| --- | --- | --- |
| WSL工具查询及临时隔离preflight | 退出0 | Java/Maven/Python/g++可用；python命令缺失；preflight的PrivateTmp组合未证明tmpfs，正式运行改用独立TemporaryFileSystem并断言 |
| systemd-run ... run-baseline.sh ... TASK-00（R03） | Maven退出1；193项，182通过/2失败/7错误/2跳过 | TASK-00根目录baseline-summary.json和build-summary.log；原始原因被过度裁剪，独立复核后补强而不覆盖 |
| systemd-run ... run-baseline.sh ... TASK-00/R04 | Maven退出1；193项，182通过/2失败/7错误/2跳过 | R04下JSON含脱敏断言/异常/跳过原因、单元属性；实际命令完整记录在approval-and-baseline.md |
| verify-plan.ps1直接执行 | PowerShell执行策略拒绝；未启动校验 | 未更改/绕过系统执行策略；组合命令末尾成功不能覆盖此失败，补以独立Node静态检查 |

- R04确证两项断言为1048576与10485760不符、生成器预期OLE实际AC；7个错误明确为无法启动python（error2不存在）。两项真实provider探针跳过保留，原因见JSON的detail，不包装为通过。
- 独立静态复核：20RQ、29AC、31缺陷映射一致，30卡均9类字段，依赖指向更早任务且设计章节存在。R04脚本绑定单个不可变SHA、拒绝复用证据目录、保留有限脱敏诊断与原退出码、校验非零报告，不放宽任何业务断言。
- 主代理Node内联校验实际通过：tasks=30、fields=270、preceding-dependencies=all、defect-mappings=31、RQ=20、AC=29、references=defined、strict-UTF8、whitespace、baseline-counts；工具成功返回。没有绕过PowerShell脚本执行策略，未将被拒绝的ps1声明为执行通过。
- baseline_review最终只读复核确认R04证据与计数一致、脚本问题实质修复；按其意见限定Docker socket可达的隔离措辞。Linux跳过因runner镜像缺失，Windows跳过因runtime为Linux，均非平台安全通过。给验收目录sh设置局部LF属性，避免Windows检出后复跑失败，不改全局Git配置。
- 首次git add因沙箱不能创建.git/index.lock失败，随后按权限机制提权精确暂存成功；未使用git add .，未将失败尝试算作成功提交。
- 暂存后git diff --cached --check首次退出2，发现原主评审末尾额外空行；仅移除该空行，不改评审内容，再次检查后才允许提交。
- 验收逐项：八项DEC均有用户批准或明确延期/受阻任务；输入归属及提交授权明确；原改动清单保留；版本、真实测试和环境错误可追溯。TASK-00允许红基线，TASK-01须修复；后续发布仍要求真实隔离provider与全量验收。
- KISS/DRY：沿用Maven与既有任务/日志；脚本只负责受测快照和证据，不创造第二套业务实现。YAGNI：未提前改业务或引入新技术栈。残余风险：未定资源/部署值按对应任务审批、Windows/Worker未验收、普通回归两项真实探针尚跳过。
- 提交候选主题 docs(plan): TASK-00 freeze execution baseline [TASK-00-C01]；父SHA如上；自身SHA由唯一主题定位，尚未生成。候选DONE仅在真实提交存在且范围核验后生效；提交失败必须撤回并追加记录。未push、未amend、未建分支。
- 下一项TASK-01：00提交核验后实施10MiB动态边界夹具和解释器发现，先用本次失败证据定位，定向与全量复测，不禁用失败或跳过关键用例。

### 2026-10-02 — TASK-00 / R05 — 本地提交身份阻断

- git diff --cached --check在移除末尾空行后退出0；精确暂存19个文档/验收文件，任务外.mimosa/和.playwright-mcp/未暂存；没有业务代码变更。
- 实际命令 git commit -m "docs(plan): TASK-00 freeze execution baseline [TASK-00-C01]" -m "Record approved decisions and deferred capacity gates." -m "Preserve red Maven baseline evidence and redact review credentials."，退出128。
- 实际错误 Author identity unknown；unable to auto-detect email address。未生成提交，未声称存在真实SHA；未绕过钩子、未push、未amend、未建分支。
- 按提交失败协议撤回候选DONE，task/work恢复VERIFIED_PENDING_COMMIT，完成计数0/30；R03/R04真实验收和用户批准仍有效，不重置成未开始，也不删除失败历史。
- 解除条件：用户提供本仓库本地提交所用Git作者姓名和邮箱。优先按单次命令git -c user.name=... -c user.email=... commit使用，不修改全局或持久配置，不冒用历史作者或编造身份。原TASK-00～29本地提交批准仍有效。
- TASK-01只读定位已完成：默认额度测试仍为1MiB、生成器固定2MiB且详情预览断言过期；两处runner启动硬编码python。未实施后续代码，等待TASK-00真实提交满足依赖。

### 2026-10-02 — TASK-00 / R06 — 用户提供提交身份后重试

- 用户明确提供Git身份yanwenyu及2990114500@qq.com；仅对本次commit使用git -c user.name和-c user.email，不修改全局或仓库持久配置。
- R05失败历史保留；HEAD及受测业务代码未改变，R03/R04和独立静态验收继续有效。提交前重新检查范围及git diff --cached --check。
- 使用原定位[TASK-00-C01]，不是伪造新任务或空提交；父SHA仍f31d039183c690f36d7a07e25da911ce1ace55db。候选DONE仅在提交成功及真实SHA/父SHA/19文件范围核验后生效；不推送。

### 2026-10-02 — TASK-01 / R01 — 动态输出边界与解释器发现开工

- 前置已核验：TASK-00真实SHA 4c86d830edc9a927e32377291bac20641ac1e557，父f31d039183c690f36d7a07e25da911ce1ace55db；唯一主题匹配1条，19文件精确范围含task/work，提交身份与用户提供一致，未推送。提交后仅既有.mimosa/、.playwright-mcp/未跟踪，已向用户即时汇报。
- 需求RQ-V01/AC-V01/T-V01、N5、DEC-01/07；设计D §8.2/9.1。批准默认10MiB，不改变生产额度/runner语义；编译诊断及后续资源参数仍按批准延期边界处理。
- 真实前测为TASK-00 R04：额度断言1MiB对10MiB失败、2MiB生成器不足以超限、python硬编码导致7错误；不伪造新的前测成功。
- 主代理负责ApplicationYamlExecutionProfileTest和JudgeBaselineTest：期望与批准额度一致，生成器按生效配置输出limit-1/limit/limit+1，预览断言独立按preview上限；python_discovery负责测试作用域Python发现/矩阵、两个runner调用点及README，彼此不覆盖文件。
- 计划本项执行定向、全量Maven和解释器矩阵；实际结果后续追加。业务配置与生产代码保持不变，不禁用失败/不把OLE期望改AC，task/work由主代理统一更新及提交。

### 2026-10-02 — TASK-01 / R02～R04 — 回归、显式路径和失败负例验收

- 角色/范围：主代理集成与验收，python_discovery实现测试作用域解释器发现，baseline_inspection/baseline_review分别独立复核。需求RQ-V01/AC-V01/T-V01、N5、D §8.2/9.1；父SHA 4c86d830edc9a927e32377291bac20641ac1e557。
- 实做：默认YAML断言对齐批准的10MiB；注入生效ExecutionProperties，生成器以7和换行加固定块空白产生恰好limit-1/limit/limit+1字节。下界/等界须AC，上界保留OLE而非系统错误，详情预览按独立配置上限断言。未改任何生产额度或判题代码。
- 实做：PythonInterpreter显式judge.test.python优先且无效不回退，否则python3再python；真实版本检查只接受Python3，5秒等待/1秒清理，两个真实runner启动点统一调用。新增12测试：11个注入/Mockito选择与错误/超时/中断单测，1个真实环境探测；不把mock矩阵称为三个真实安装环境。
- README记录显式配置；复用隔离脚本新增worktree模式及独立Maven参数，保留committed默认。各次包含131个源码/runner/scripts/POM文件hash、150个完整输入hash、harness hash、父SHA、模式、真实计数和有限脱敏原因。测试后四组hash全部与当时工作树匹配；之后仅验收文档/task/work更新，代码及文档契约实际依赖输入未改。
- 失败历史：首次定向命令未给-Dtest逗号列表加引号，被PowerShell解析拒绝，退出1、测试未启动；纠正独立argv后执行R01。审查另发现快照模式错误反斜杠，运行前修正并补完整输入hash；不伪称这两次工具/脚本问题为通过测试。

| 运行与Maven附加参数 | 实际结果 | 证据 |
| --- | --- | --- |
| R01：-Dtest=ApplicationYamlExecutionProfileTest,JudgeBaselineTest,TaskRunnerArtifactContractTest,PythonInterpreterTest | 退出0；28项/28通过/0失败/0错误/0跳过 | docs/acceptance/TASK-01/R01 |
| R02：无-Dtest，全量默认test | 退出0；207项/205通过/0失败/0错误/2跳过 | docs/acceptance/TASK-01/R02 |
| R03：-Dtest=TaskRunnerArtifactContractTest,PythonInterpreterTest -Djudge.test.python=/usr/bin/python3 | 退出0；19项/19通过/0失败/0错误/0跳过 | docs/acceptance/TASK-01/R03 |
| R04：-Dtest=PythonInterpreterTest#actualEnvironmentHasAWorkingPython3Interpreter -Djudge.test.python=/nonexistent/task01-python | 预期退出1；1项/0通过/0失败/1错误/0跳过；错误明确为显式路径不存在且不回退 | docs/acceptance/TASK-01/R04 |

- 每次完整Maven argv存于对应baseline-summary.json；外层unit-properties/环境及可复跑命令见TASK-01 README。R04为有意错误配置负例，独立验证其退出码/错误数/缺失路径与不回退消息，不伪装BUILD SUCCESS或混入正常全量通过数。
- 全量2项跳过保持原有真实provider探针：Linux缺cpp-judge-runner:latest，Windows因Linux runtime不适用。TASK-01四类核心测试均实际执行且无skip；未新增/扩大任何跳过，不将这两项计通过。真实平台验收仍由TASK-25/26负责，当前不可发布，N5不提前关闭。
- 未执行Python runner独立unittest套件（本项真实artifact由Java7项覆盖）、真实浏览器/WS/TLS/provider/生产资源攻击；不把mock发现矩阵推广到Windows实际安装。后续输出合计、在线资源限制及进程树治理仍由TASK-13/14完成。
- 原则：仅测试及测试工具/文档变更；DRY统一两个解释器入口并复用隔离采集，KISS/YAGNI不引入包管理或生产依赖，不改业务默认/放宽断言。独立复核确认边界字节数、保留OLE、显式不回退及有限等待；已修快照转义/证据范围问题。
- 本地提交候选 test(baseline): TASK-01 restore reproducible regression [TASK-01-C01]；父SHA如上，自身SHA由唯一主题定位尚未生成，身份沿用用户提供的单次git -c参数。精确暂存本任务测试、工具、README、证据及task/work，不纳入既有临时目录；提交前git diff --check及提交后SHA/父/范围唯一性核验，未推送。
- 下一任务TASK-02：前置00/01真实提交及DEC-06方向批准；统一凭证、安全默认和脱敏日志，不借此越过TASK-03的代理/容量审批。
- 提交前首次git diff --cached --check退出2：R04脱敏构建摘要的Maven错误标题带尾随空格；仅去除该行末空白，错误文本、退出码、JSON及所有测试源码/hash不变，重新检查后再提交。

### 2026-10-02 — TASK-02 / R01 — HTTP凭证与安全默认开工

- 角色：后端安全工程师；范围RQ-A04/AC-A04a、AC-A04c/T-A06、T-A08，RQ-A05/AC-A05b/T-A10，D §3.1/3.5/8.1/8.3。DEC-06方向及本地逐任务提交已批准。
- 恢复核验：TASK-00实际提交4c86d830edc9a927e32377291bac20641ac1e557，TASK-01实际提交bcf1d5ea6f40829dac9ef02ce6d526f2eef81d57（本项父SHA）；工作树只有既有.mimosa/、.playwright-mcp/，不纳入本项。
- 已定位：四处HTTP凭证提取不一致，query回退与Cookie/Header冲突；YAML/Java默认码；两个启动校验器把账号模式的闲置访问码当风险；访问码会话userId等于token，审计也会泄漏，不能仅修显式sessionId日志。
- 执行顺序：先补可在原实现编译运行的失败回归，再统一解析/材料校验和日志指纹，定向与全量验证。保留接口字段、账户模式规则与所有权/配额身份；来源限流、Cookie/CSRF、WS及环境绑定分别留TASK-03/04/06/07。
- Git身份仅使用用户提供的单次-c参数，不改持久配置、不推送。本项仍IN_PROGRESS，未产生新验收结论。
- R01真实执行退出1：testCompile失败，0测试/0报告，不能作为缺陷失败证据。原采集器只保留编译失败标题，故补充脱敏编译诊断行及统一去尾空白后重新执行；保留R01记录，不覆盖证据。
- R02同样退出1、0测试：诊断明确为新增断言误用了hasMessageNotContaining多参数重载；改为两个单参数断言，未改变断言语义。R01/R02均为测试代码问题，不记作产品缺陷复现。
- R03原生产实现前测：39项，7通过/31失败/1错误/0跳过，退出1；复现URL认证、冲突选择Cookie、旧默认码登录、空配置NPE、账号模式误受闲置访问码阻塞、会话/审计明文。R04修复后十类定向：80项，77通过/3失败/0错误/0跳过，退出1。
- R04三项失败来自本次测试误判HTTP契约：WebMvcConfig本来排除/auth/**，/auth/session以200+valid=false表示未认证，logout在无可解析凭证时仅清客户端Cookie并成功返回。保留该契约，改为断言valid=false/无sessionId及冲突两端会话均未失效，普通/管理/下载仍严格401。不把这三项状态假设当产品缺陷证据，不为改状态码扩展任务范围。
- 实做：共享无状态凭证解析器对空白、不同值及重复冲突失败关闭；同值允许，query不参与。共享认证模式规则，默认材料清空/回环绑定；访问码模式无材料或历史默认值拒绝，账户模式独立验证可用账户。SHA-256会话日志关联及审计userId脱敏保留原所有权/配额身份，补审计session/cookie/authorization键脱敏。
- 测试迁移：八个原SpringBootTest显式注入仅测试作用域材料；静态YAML安全断言仍直接读取真实资源。原trusted-local弱码放行断言按DEC-06改为显式材料与独立弱码拒绝，账户生产测试保留全部provider/Origin/隔离检查，只移除闲置访问码阻塞。未删除/跳过测试。
- R05十类定向80/80通过，R06全量260项/258通过/0失败/0错误/2原provider跳过，均退出0。审查发现仅删除spring.security.user配置仍可能触发框架生成闲置用户；新增实际Spring上下文断言先验证该回退，不依靠隐藏日志或恢复硬编码账号。
- R07默认账号回退前测2项/1通过/1失败/0错误/0跳过，退出1；实际存在inMemoryUserDetailsManager。仅排除未使用的UserDetailsServiceAutoConfiguration，继续使用现有应用会话认证和SecurityFilterChain；新增配置绑定断言核验默认回环。未借此开启/关闭其他安全过滤器，CSRF留TASK-04。

### 2026-10-02 — TASK-02 / R08～R09 — 验收与本地提交候选

- 最终定向R08：11套件83项，83通过/0失败/0错误/0跳过，退出0。最终全量R09：38套件262项，260通过/0失败/0错误/2原provider跳过，退出0。准确argv、环境、unit约束、失败历史及套件结果见docs/acceptance/TASK-02；本次未运行独立Python unittest或真实浏览器/WS/TLS/provider攻防验收。
- R08/R09各137个源码/runner/scripts/POM哈希、157个完整输入哈希及harness哈希逐一与工作树匹配。核验后仅追加验收/task/work/change-log及脱敏归档；未改被测代码/配置/测试。R03断言信息中的已退役Java默认值替换为<redacted-legacy-default>，仅脱敏message/detail，不改计数/类型/退出码。
- 两项原skip：Linux镜像cpp-judge-runner:latest缺失；Windows Hyper-V因当前runtime是Linux不适用。核心认证/默认材料/日志用例均实际执行无skip，不将skip计为通过。外层断网/限额仅用于受信测试，未隔离宿主Docker socket，不证明生产沙箱安全。
- 逐AC：Cookie/Header/同值成功，不同/空白/重复冲突拒绝，query-only在普通/管理/状态/下载不可认证；直接Controller无MVC拦截器仍拒绝。认证查询保留200业务失败契约；冲突logout不选择任一服务端身份。DEBUG/INFO/WARN真实日志逐条扫描两模式密码、访问码和会话均无原值，SHA-256关联仍存在；访问码审计不再通过userId泄漏。
- AC-A05b：Java与YAML均无可用fallback；旧码、null/空白、退役哨兵拒绝；访问码/账户模式启动矩阵通过，账户模式的闲置访问码不阻塞；有效BCrypt与启用账户校验未削弱生产provider门槛。实际Spring上下文无默认UserDetailsService，配置绑定默认127.0.0.1；不把配置绑定声称为真实部署网络验收。
- 文件：新增security/SessionCredentialResolver、SessionAuditId；四HTTP入口统一，两个启动校验器/AuthConfiguration/UserAccountService复用模式规则，AccessCodeService/SessionManagementService/AuditService脱敏；Demo18Application排除闲置Spring账号，application.yml安全默认。新增4类回归，8个SpringBootTest明确测试材料，既有3类安全基线按批准语义迁移；更新README、change-log与验收采集/台账。
- 主代理复核：检查实际调用链、白名单、所有权/配额稳定性、原生产沙箱断言及失败归因；修正/auth/**排除造成的测试状态假设，发现并补测Spring默认账号回退。没有宣称独立外部审核；独立关闭审计留TASK-29。
- 原则：DRY共享解析/模式判定/指纹；KISS无新认证框架或构造器侵入；YAGNI不越界实现CSRF/WS/代理或迁移生产数据；小类各司解析和脱敏职责。保留原响应、所有权及配额语义，不改变业务额度。
- 状态：开发验收已通过，先VERIFIED_PENDING_COMMIT，再按task §2.4写DONE提交候选，仅匹配真实提交存在后生效。DEF-18/N4为IMPLEMENTED_PENDING_VALIDATION，N3还缺WS；后续24/28/29闭环之前不关闭全部缺陷或宣称可发布。
- 提交候选：fix(auth): TASK-02 unify credentials and safe defaults [TASK-02-C01]，父SHA bcf1d5ea6f40829dac9ef02ce6d526f2eef81d57；自身SHA由唯一主题定位，未伪填。仅使用用户提供身份的单次git -c参数，按批准范围精确暂存并核验，不推送、不amend、不新建分支、不修改持久配置。既有.mimosa/、.playwright-mcp/仍排除。
- 下一顺序TASK-03受可信代理/限流容量等定量参数延期门槛影响，不能擅自填业务默认；TASK-08依赖01/02且DEC-05方向已批准，可先核对其真实TLS环境/授权后推进独立工作。整体Goal尚未完成。

### 2026-10-02 — TASK-08 / R01 — Worker TLS与认证契约开工

- 恢复核验TASK-02真实提交6f3b479b8cd37bc29cb862495515318066e88618，父bcf1d5ea6f40829dac9ef02ce6d526f2eef81d57，作者yanwenyu及用户提供邮箱、唯一TASK-02-C01主题、116文件范围及task/work均核验。仅本地未推送；工作树只有既有两个临时目录。
- TASK-03依赖02已满足，但可信代理CIDR、来源/账户阈值、容量/TTL和头上限按已批准延期仍未冻结，标BLOCKED，不借安全修复自造业务默认；不阻断独立TASK-08。
- 角色：后端集成/TLS工程师。RQ-A07/AC-A07/T-A13、DEF-23、DEC-05，D §3.6/8.1/8.3。当前三处Worker配置对cert-only/签名材料的判断不一致，Runner静默优先配置且发固定伪mTLS头；Options允许HTTP，生产校验只读嵌套配置。
- 本项先加配置/认证失败回归，再统一解析与HTTPS约束；保留现有Bearer/HMAC签名输入，明确拒绝未实现mTLS。使用现有受限WSL中的临时本地TLS服务、临时证书/信任库进行真实握手正反例；不访问外部Worker、不更改系统信任库、不安装依赖。实际外部服务端验签/重放保护与端点审批留TASK-26。
- 设计要求使用Java标准HttpClient/keytool/TLS API，实际可用性通过本地编译和握手验证，不以文档或mock替代真实TLS用例。开工时未写生产变更/未执行TASK-08测试，状态IN_PROGRESS。
- R01配置/认证前测：29项/15通过/14失败/0错误/0跳过，退出1。新增15项仅同值/单来源正例通过；实际复现HTTP、伪mTLS、配置冲突、原始端点错误回显及生产校验忽略主Worker配置，原6项客户端/8项启动用例通过。R02迁移后testCompile失败，0报告/0测试：Auth安全默认测试构造器遗漏新WorkerProperties参数；根据诊断补齐，不能当作TLS失败或通过。
- R03定向42/42通过、0失败/错误/跳过，退出0；其中13项为真实本地TLS握手/认证/重定向，原6项Worker接口测试已迁移HTTPS。临时CA签发有效/过期/错主机证书，独立内存信任验证，不使用trust-all、不更改全局SSLContext或系统truststore；全部keytool进程有30秒上限及清理检查。
- 主代理复核补充：仅HTTPS跳转HTTP不能充分证明禁止所有跨端点跳转，因此补齐301/302/303/307/308×HTTP/HTTPS目标矩阵；源端须实际收到正确Bearer/HMAC，目标端请求数须0。另补嵌套HMAC及真实Spring装配、签名冲突用例。显式NEVER是约束和回归保护，不把R01说成已证明原客户端存在重定向泄密。
- R04四类定向49/49通过，0失败/错误/跳过，退出0；包含18个真实TLS场景和17个配置场景。继续加强证书负例：必须出现实际PKIX证书链/有效期或subject alternative主机名拒绝原因，不能仅以通用请求失败/零请求数替代TLS失败证明；该断言在当前受测JDK21环境运行，不据此声称其它JDK/平台已经验收。
- R05加强后的定向49/49通过，退出0。R06全量297项/294通过/1失败/0错误/2原provider跳过，退出1：JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe期望RE却得到SYSTEM_ERROR；本项TLS/配置用例全部通过。R07保持生产代码及断言不变，单独复测该用例1/1通过，退出0。首次全量失败真实保留，现有证据尚未确认根因，不能宣称已修复或用重跑抹去不稳定风险；未改判题逻辑、未放宽RE断言、未禁用用例。随后执行一次同实现全量核验，结果单独记录。

### 2026-10-02 — TASK-08 / R05～R08 — 客户端验收与本地提交候选

- 最终定向R05：49项/49通过/0失败/0错误/0跳过，退出0。完整复验R08：40套件297项/295通过/0失败/0错误/2原provider跳过，退出0。R06判题RE→SYSTEM_ERROR失败、R07原码单项通过均独立保存；未确定该偶发失败根因，仍属N5/最终回归的未消除风险，不声称整个系统回归已稳定。
- 源码核验：R05/R06/R07/R08各141个源码/runner/scripts/POM哈希与同一工作树全部一致，未改判题实现/测试/额度以使复验变绿。R08完整162个输入及harness哈希均匹配；后续仅验收/task/work/change-log记录变更。两项skip仍是缺Linux镜像、非Windows runtime，核心Worker用例无skip。
- 实际范围：WorkerProperties集中归并与校验，SandboxProperties.Worker复用认证能力规则并支持signing-secret，Options复用同一逻辑；ProductionSecurityStartupValidator读取真实主/嵌套配置，HTTP/无认证/伪mTLS/配置冲突失败关闭。保留Bearer/HMAC签名输入，移除固定伪mTLS头，显式NEVER重定向，默认TLS验证不绕过。
- 真实TLS：18项包含3种认证模式GET/JSON POST正例、2种错误认证、过期/错SAN/不可信CA各一负例、5种跳转状态×HTTP/HTTPS目标10项。正例实际到达本地HttpsServer并验证认证；证书反例须实际PKIX/hostname拒绝且无HTTP handler请求；跳转源端认证成功但目标请求为0。原6项协议测试迁移为HTTPS而未删除。
- 证书/信任：只使用临时目录内keytool生成CA和叶证书、标准内存KeyManager/TrustManager，不改系统truststore或全局SSLContext，不使用trust-all/hostname关闭。没有外部Worker、真实生产信任部署、服务端重放防护或执行沙箱验证，不能提前关闭DEF-23或开启未验收provider。
- 文档与兼容：production-sandbox-runbook去掉mTLS已可用的旧表述，说明两处配置冲突、最终HTTPS端点及迁移；application.yml补空嵌套signing-secret，无新增业务默认或放宽安全门槛。三处原启动测试补新依赖，保留原沙箱/账户断言；提交独立配置/TLS测试及临时证书工具，不含测试私钥/keystore。
- 主代理复核：核对构造器实际Spring装配、配置真相、凭证不回显、标准TLS与重定向矩阵；发现仅HTTP跳转负例不充分并补HTTPS目标，强化具体TLS原因而非通用网络错误。未宣称独立第三方审核，最终审计留29。DRY复用配置解析，KISS不引入第二套客户端/认证协议，YAGNI不实现未批准mTLS或外部Worker服务端。
- 状态：本卡客户端开发验收通过，先VERIFIED_PENDING_COMMIT，再按task §2.4写DONE提交候选，真实提交匹配后生效。DEF-23为IMPLEMENTED_PENDING_VALIDATION；R06全量不稳定、TASK-03参数以及未验收provider仍使整体Goal不能完成/发布。
- 提交候选：fix(worker): TASK-08 reject nominal auth and enforce tls [TASK-08-C01]；父SHA 6f3b479b8cd37bc29cb862495515318066e88618，自身SHA按唯一主题核验。沿用已批准的单次Git身份参数，仅本地、不push/amend/新建分支，精确暂存排除既有.mimosa/、.playwright-mcp/。
- 后续：先核对TASK-09的v2清单字段/目标平台及延期参数边界；TASK-03继续等待可信代理与限流定量参数批准，不擅自采用候选默认。

### 2026-10-02 — TASK-08 / C02 — 已完成任务总览一致性补正

- TASK-08 C01真实提交d40192bca8aa63011b60e6fa7d68d057b37be14f已核验：父6f3b479b8cd37bc29cb862495515318066e88618、用户指定作者、唯一主题、90个文件、task/work均在提交中；仅本地未推送，工作树仅既有.mimosa/、.playwright-mcp/。
- TASK-09预检时发现task.md任务卡已完成但总览仍未勾选TASK-02/08，属于本次台账同步遗漏。仅同步两条总览为已核验，不改需求、代码、测试或缺陷关闭状态；总览与真实已完成00/01/02/08一致，共4/30。
- 本补正为纯文档，验证30张任务卡、30条总览以及4条勾选与真实唯一C01提交对应，执行git diff --check/暂存检查；未重复执行业务测试，沿用TASK-08 R05/R08代码验收且保留R06未定位失败风险。
- 补充提交候选docs(task): TASK-08 reconcile completed-task index [TASK-08-C02]，父SHA d40192bca8aa63011b60e6fa7d68d057b37be14f；只包含task/work，不amend旧提交、不推送、不为回填自身SHA改写历史。

### 2026-10-03 — TASK-09 / R00 — 恢复核验与开工

- 本地时间以 Get-Date 的 Asia/Shanghai 输出为准。HEAD ea51d2ab8a936ccd392d9665ed78c086a554e369、唯一 TASK-08-C02、task/work 范围与既有工作区已核验；00/01/02/08 共4/30完成，其余未完成。
- 主任务 TASK-09，契约/跨平台后端工程师；对应 RQ-R01/AC-R01/T-R01、RQ-R03、RQ-C01 参数贯通，D §2/4.1/4.2/5.2/8.1/8.3。TASK-03 的代理/限流参数继续等待批准，不阻断本项。
- 事实：Linux/Windows 直接写含宿主绝对路径的 spec 且先建目录；Python 从 spec.workDir 建目录并截断宿主 events.jsonl 后才校验；Worker 没有版本/字段能力协商。
- 计划先用现有可编译 API 添加真实 Python 失败回归，再建立独立 v2 wire manifest、相对路径校验、attempt 文件与显式能力拒绝；串行修改 DTO、adapter、JudgeService、Python 和测试。
- 比较算法及 API 无损接收留15/16：保留当前请求 double 限制并如实记录，禁止将未实现十进制能力伪报可用；09不擅自冻结事件/轮询/数字资源阈值。
- 验证执行定向五套件、真实 Python suite、不同根映射和危险路径前后快照、全量回归；保留 TASK-08 R06 的未定位 RE/SYSTEM_ERROR 风险。未验收真实容器/Windows/native/外部Worker，不关闭完整安全链路。

### 2026-10-03 — TASK-09 / R01～R06 — 实施、验收与本地提交候选

- R01先测后修：2项真实Python回归均失败，宿主四行audit被替换、危险workDir在拒绝前已创建。R02为测试辅助方法未声明InterruptedException导致testCompile失败，0测试；修声明，保留证据。R03定向63项/59通过/2失败/2跳过：旧Linux命令替身匹配全部run --rm，误给协议探针返回PROBE_UID文本；仅收紧旧探针到/bin/sh，未放松安全断言。R04扩展后118项/116通过/0失败/0错误/2原环境跳过。
- 实做文件：新增SandboxComparison、SandboxExecutionManifest、RunnerProtocolCapabilities与共享SandboxManifestFactory；扩展宿主spec/capabilities/handle、ResolvedTaskPolicy/TaskPolicyResolver与JudgeService；三个adapter分别生成wire并实际协商，配置固定attempt内文件名。Python拆出无副作用runner_manifest校验，执行根由provider提供，全部校验后才写新attempt事件/摘要/workspace。
- 路径/协议：相对源码拒绝POSIX/盘符/UNC/父级/设备名/链接/非本任务输入；源与控制目录先验，独立UUID attempt和CREATE_NEW/独占事件文件禁止截断；时间固定毫秒、资源字节、retention显式；版本不得由字符串/小数隐式转成2，缺版本/比较/限额能力拒绝。Worker先协商再发送manifest+实际源码Base64，确认回执版本和judgeId/attemptId，保持原HTTPS、Bearer/HMAC签名输入及禁止重定向。
- 参数边界：precision从现有double请求进入策略快照后按字符串贯通；高精度内部字符串不再二次舍入，但API初始double损失未解决，留15。Python仅声明真实LEGACY_TEXT/SPJ，不假报DECIMAL_ABSOLUTE；普通host请求在当前artifact上失败关闭，算法留16。没有采用延期的事件/轮询/数字容量候选参数。
- 测试：R05最终10套件124项、122通过/0失败/0错误/2跳过；R06全量42套件360项、358通过/0失败/0错误/2跳过，均退出0。R04/R05/R06内实际Python unittest均11/11，另存python-summary.json；真实根目录复制映射、前后文件树字节快照、重放/新attempt、SPJ及路径/字段/版本负例通过。TLS 18项仍真实执行，三认证方式GET+新v2 POST成功；新增Worker协议负例不冒充外部Worker执行。
- 精确argv/环境/退出码/结果/失败历史见docs/acceptance/TASK-09各轮；环境为已批准WSL Ubuntu24.04.1/JDK21.0.12.1/Maven3.8.7离线/Python3.12.3/g++13.3.0，保持TASK-00外层约束。2跳过仍为缺Linux镜像与非Windows runtime，核心安全负例不skip；未安装依赖、不修改全局环境，不把受信测试约束当生产隔离。
- 源码核验：R05/R06各150个源码/runner/scripts/POM哈希、172个完整输入哈希及harness哈希全部匹配工作树，两轮列表相同；final-hash-verification.json留证。核验后只更新验收/task/work/change-log。公共harness仅新增可选Python结果采集，不改Maven测试选择、成功条件或禁用测试。
- 文档/兼容：更新runner规范、README、生产runbook；新旧host/artifact/Worker必须成套维护升级，旧任务只读，拒绝旧文件名和混合事件流，回滚不恢复弱认证/共享流。未修改历史任务、生产配置或真实平台，新增client源码bundle只定义并实现发送侧，外部Worker须自行严格验证并实现接收侧。
- 逐项开发验收通过；DEF-02为IMPLEMENTED_PENDING_VALIDATION，实际Linux/Windows/native/外部Worker闭环留25/26；N2文件分面不等于权限/增量验真，完整关闭留10/11。DEF-11/12留15/16，TASK-08 R06 RE/SYSTEM_ERROR偶发失败仍未定位，不能因本轮全绿关闭N5/发布风险。
- 主代理自审（非独立第三方）：审查路径与能力边界、host事件不变、字段类型、TLS兼容、迁移、证据一致性；DRY复用路径工厂和策略，SRP分开domain/wire/校验，KISS/YAGNI不引入框架或伪比较算法。最终独立安全放行留29。
- 状态从IN_PROGRESS经VERIFIED_PENDING_COMMIT进入DONE提交候选；提交主题feat(runner): TASK-09 version execution manifests and paths [TASK-09-C01]，父SHA ea51d2ab8a936ccd392d9665ed78c086a554e369，真实SHA按唯一键核验。沿用已批准本地提交授权及单次Git身份，不push/amend/建分支，排除既有.mimosa/、.playwright-mcp/。
- 用户明确要求“完成这个任务后先停一停”；本项真实提交核验后将Goal置paused，不开始TASK-10/15或其他任务。总计将为5/30完成，整体Goal未完成、不可发布。
- 提交前一致性复核另发现本文件§3总台账仍停留TASK-01时的2/30，与后续追加日志及真实Git不一致；本次同步已核验的01/02/08、03既有阻断及09候选为5/30，保留所有历史执行条目，不把同步台账计作新增开发任务。


### 2026-10-03 — TASK-10-P01 — 恢复与实施前门槛核查

- 用户明确“继续后续任务”，恢复推进；真实HEAD为5f672c2faa9d02e2e3cd95c832ef4623bc222301，唯一TASK-09-C01与父提交匹配。将总账09候选同步为已核验DONE，仍为5/30，不重复实施09。
- 开工前只有既有.mimosa/、.playwright-mcp/未跟踪；保留且排除。检查任务依赖后，首个新runner任务10满足代码依赖但DEC-07执行域/监督器尚未批准。
- 读取现有Linux adapter与Python Popen：整任务根可写挂载、同身份执行未建立可信控制面隔离。只读WSL/Docker预检确认server29.1.3、cgroup v2/systemd，cpp-judge-runner:latest不存在；当前daemon有21个运行容器，不推定为独占攻防环境。首次WSL沙箱E_ACCESSDENIED按规则提权重试；组合模板参数不完整后逐项补查。
- 预检命令、结果、候选方案及解除条件记录于docs/acceptance/TASK-10/preflight.md；建议可信监督器管理独立受限执行单元，runtime管理端点不进入不可信域，待用户确认，不擅自改变安全边界。
- 10标BLOCKED；同时核对独立分支03及15，03既有限流/代理参数仍待批准，15解析上界/错误映射仍待批准并标BLOCKED。其他未完成任务无已满足全部门槛的独立分支，不绕过依赖。Goal维持未完成，不自行暂停，不因首次阻断就标整个Goal blocked。
- 未改源码、未跑回归或攻击、未拉取/构建镜像、未操作已有容器、未修改系统配置；本轮仅文档/预检，不新增DONE或关闭缺陷。主代理自审，非第三方安全验收；KISS/YAGNI避免未批默认和无关架构实现。
- 修改范围仅本预检文档、task/work；暂不提交未验收任务，无新增提交SHA或提交候选，不push/amend/建分支。下一步需先批准10方案与实际环境边界，或冻结03/15参数后推进相应独立任务。


### 2026-10-03 — TASK-10 / R01～R03 — 已批架构下的前置失败关闭阶段

- 用户回复“批准”，明确采纳独立可信监督器/不可信执行单元架构方向；不继续把架构方向列为未批准。该回复未指定独占环境或具体外层/生产资源数值，其他03/15延期参数也未自动获批。
- 代码探索发现真实缺口：Linux probe仅靠外围限制返回productionSafe，生产启动器仅看人工capabilityProbePassed标志，start也不验证控制面。先改两个既有回归，R01实际2项/2失败/0错误/0跳过、退出1，证明原实现误放行，不是编译或缺环境失败。
- 实做：SandboxCapabilities新增controlPlaneIsolated默认false；Linux共享workdir adapter明确不隔离/不生产安全，artifact自称feature不放行；非精确trusted-local start在prepare/写manifest/任何命令之前拒绝。ProductionSecurityStartupValidator通过ObjectProvider取得实际runner并probe，核验provider/isolation/productionSafe/控制面/网络/身份/资源/安全配置；缺bean、null、异常、矛盾结果拒绝，人工passed/required不再替代实际Linux能力。异常不回显原message/cause。
- 测试：保留原协议/路径/manifest/事件/取消合同，旧成功路径明确迁至trusted-local，新增5个profile无副作用拒绝、伪artifact能力、DTO旧JSON/默认false/往返、配置标志四组合、能力负例与真实Spring装配/生命周期；mock能力只证明启动合同，认证fixture继续独立检验认证，不冒充真实平台。
- R02定向7套件116项/115通过/0失败/0错误/1跳过、退出0；R03全量42套件389项/387通过/0失败/0错误/2跳过、退出0，另实际Python unittest11/11且0跳过。R02未执行Python suite。准确argv/环境/计数/失败与skip原因见docs/acceptance/TASK-10对应轮；沿用TASK-00受信基线的WSL/JDK21/Maven3.8.7离线及外层约束，不把它当生产攻防环境。
- 实际skip仍为Linux镜像缺失和Windows runtime不匹配；Linux即使有镜像当前也会因控制面未实现拒绝，不更改assumption制造通过。TASK-08历史RE/SYSTEM_ERROR偶发问题未再现但根因仍未确认。
- 一致性：R02/R03各150个源码哈希、174个完整输入哈希及harness全部匹配当时工作树、两轮清单相同；phase-one-hash-verification.json留证。此后仅更新文档。runner README及Linux/生产runbook明确旧路径当前不可生产，不能以切trusted-local或人工探针通过绕过。
- 协作：只读explorer定位/复核adapter、DTO与startup入口；worker仅负责startup及3个关联测试，主代理负责其他实现、集成/实际测试及日志。所有代理未提交；未发现阶段范围阻断问题不等于独立安全验收，最终放行仍留TASK-29。
- 环境补查：WSL仅Ubuntu，默认root；dockerd/systemd-run/unshare可用，rootlesskit/newuidmap/newgidmap/qemu-system-x86_64/virsh未在PATH发现；/dev/kvm存在但没有据此假定VM已就绪。未安装、建镜像、启停daemon或操作既有容器。README提出任务专用临时运行时及4GiB/2CPU/512进程/600秒/2GiB临时空间的实验外层候选，待用户明确授权后先验证外层再攻击，不填生产资源默认。
- 本阶段修复只封住旧路径，不以禁用代替完整独立执行域实现。任务回到BLOCKED（已完成部分开发回归；实际隔离、镜像、权限/角色实验与相应预算/环境未完成），DEF-05/N2不关闭，整体仍5/30、不可发布。未暂存或commit，无新增SHA；继续保留.mimosa/与.playwright-mcp/。KISS/DRY复用probe/装配，YAGNI不引入未经批准的默认或临时伪隔离。

### 2026-10-03 — TASK-10 / ENV-R04～R15 — 已批专用环境良性预检

- 用户“后续的都批准”覆盖此前明确提出的专用临时运行时、实验外层上限及仅本任务新资源清理；不再以同一授权缺失阻断。生产资源默认、03/15未列定业务参数及push不由此推定批准。
- 新增runtime_preflight.py/test_runtime_preflight.py及逐轮证据；共享daemon仅显式socket读取info/image inspect/save，测试容器只在临时独立daemon运行。固定argv、独立PID/mount/cgroup/net域、私有根、外层实际2GiB/2CPU/512进程/600秒/2GiB临时空间，未安装全局依赖或操作既有容器。
- R04～R14失败全部保留于environment.md及对应目录。特别R11证实仅chroot时初始容器与exec文件系统视图不一致；改pivot_root+私有proc+卸载旧根并新增exec/init命名空间一致性验证，未放松原断言、未执行攻击。R14另纠正BusyBox readlink单路径兼容。R15退出0、辅助单测11/11零跳过，真实UID/能力/seccomp/cgroup限额/scratch/控制目录不可见检查通过；仅良性环境预检，非完整TASK-10安全验收。
- 外侧R15后ps仅共享containerd231/dockerd317，两服务active/NRestarts0；R10～15六unit均inactive。专用容器计数0、daemon正常退出。此前辅助探索意外启动containerd的偏差完整保留于environment.md；不能宣称整个探索全程纯只读或保证未触碰默认元数据。
- 本轮仅修改验收脚本/文档，Java与runner产品源码仍为R02/R03测试版本，未声称新增全量回归。KISS以固定argv与单个良性容器验证真实边界，DRY共用断言/镜像一致性校验，拒绝用mock或放宽条件代替实测。未暂存/提交，无新增SHA。
- TASK-10改IN_PROGRESS，整体仍5/30、DEF-05/N2未关闭、不可发布。下一步是可复现编译镜像和可信监督器/阶段独立执行域，必须保留v2/attempt/审计及取消/恢复句柄契约；不能用本次预检完成替代产品实现。

### 2026-10-03 — TASK-10 / BUILD-R01～R05、ENV-R16 — 执行镜像构建与正例

- 实际新增runner/linux-executor/Dockerfile、.dockerignore及README；固定Alpine基底、本地APK全量哈希锁、官方签名校验、空仓库离线安装、默认非root/失败命令，不复制监督器或控制文件。可信监督器产品实现及阶段后端仍未完成。
- 验收脚本增加明确受信取包/断网构建及固定归档重放分支；共享daemon仍仅info/inspect/save。BUILD-R01官方取17包后切断网构建失败，R02/R03签名安装成功但vfs复制空间不足，R04构建成功但scratch所有权导致链接失败，R05显式uid/gid65532、0700后真实C++17编译/运行成功且导出镜像。所有失败保留，未取消签名校验或放宽负例。
- R04/R05重分配获批总额内子预算：内存1504MiB包含tmpfs，tmpfs1408MiB；持久新归档包128MiB+镜像384MiB，合计2016MiB<2GiB；CPU2、512pids、570+30秒不变。R05实测cgroup memory.peak=1376096256字节，不能以systemd显示10.5M替代。
- 测试：BUILD-R01辅助11/11；R02～R05、ENV-R16各18/18零失败/错误/跳过。新增归档正常/穿越/软硬链接/重复/缺包/哈希负例。R05 unit退出0，固定编译运行输出TASK10_CXX17_OK；ENV-R16退出0，确认扩展模式未削弱原良性隔离检查。未新增Java全量回归或角色攻击验收。
- 产物：target/task10-runtime/BUILD-R05/executor-image.tar，332817920字节，SHA256=9d666ec70e1dd5b115a89ea1035857a66cdacec55cd6120ed52f1e7d7bc6b832，Windows侧独立流式哈希匹配；完整镜像ID、包清单、实际命令、环境、退出码及快照见docs/acceptance/TASK-10/build.md和BUILD-R05。R05脚本/测试/Dockerfile哈希全部核验，ENV-R15历史快照也核对一致。大归档留在Git忽略target，不提交二进制。
- R05后ps仅共享containerd231/dockerd317，两服务active/NRestarts0；五个build unit均inactive。未导入共享daemon，未安装全局依赖、未操作已有容器、未推送。取包后的runtime后代检查不冒充全部进程安全验收。
- 协作：worker仅负责runner/linux-executor三文件，主代理负责harness/真实运行/集成与证据；explorer只读审核构建分支与持久runId集成方向，未独立重跑，不代替TASK-29。KISS复用已验证专用runtime，DRY共用模式/守卫，锁定真实输入、不写伪镜像或mock通过。
- TASK-10仍IN_PROGRESS，未暂存/提交，无新增SHA，DEF-05/N2不关闭，整体仍5/30且不可发布。下一阶段是可信监督器和阶段独立执行、持久归属及取消/恢复、安全产物交接、所有角色真实负例和全量回归；不得只给旧adapter换镜像绕过失败关闭。

### 2026-10-03 — TASK-10 / CONTRACT-R04～R05 — 阶段派发合同

- 新增linux_stage_executor.py及16项CLI替身合同，TaskRunner编译和四角色可注入阶段后端，新增6项派发合同；默认trusted-local路径保持不变。编译产物在容器移除后用descriptor-relative普通文件检查交接，stdout不作为控制消息。遵循KISS复用现有ProcessOutcome与角色逻辑；没有以替身替代真实隔离。
- R04收集33项，50个错误（含子测试）、0断言失败/跳过，unit退出1：受限服务未提供可写临时目录。R05增加512MiB专用tmpfs后33/33、零失败/错误/跳过，unit退出0。证据见docs/acceptance/TASK-10/stages.md及CONTRACT-R04/R05。当前新增源码已不再是旧R02/R03全量回归对应版本。
- 审查发现卷限额/归属与恢复消费者尚未实现，取消预检查及运行中退出码处理有缺口，daemon日志需显式关闭；这些结论与真实Docker兼容性分开记录，不以合同绿色判定完成。既有无界communicate输出缺陷仍待TASK-12，未隐瞒。
- ENV-R16后只读核验共享containerd231/dockerd317均active/NRestarts0，未见额外同名daemon；未修改共享服务。
- TASK-10仍IN_PROGRESS、未提交、未关闭DEF-05/N2。接下来修复阶段取消/退出合同，并在专用环境运行真实独立编译和执行；完整四角色负例、Java监督器接入与全量回归仍必需。

### 2026-10-03 — TASK-10 / CONTRACT-R06、STAGE-R01 — 实际阶段后端正例

- 新增linux_stage_volume.py及18项合同，执行前强制核对卷归属、真实tmpfs挂载/容量与运行中holder；新增config显式卷预算/holder ID，无生产默认。修复预取消、复制中取消、start前取消、停止后真实退出码；显式禁用阶段daemon日志。阶段合同从16增至30项，复用ProcessOutcome/归属校验，未旁路安全异常。
- CONTRACT-R06实际65/65、0失败/错误/跳过、退出0；保存完整日志、9个源码/测试快照及哈希。R04环境错误和R05旧版33项通过均保留。此次不是Java新全量回归。
- STAGE-R01在断网专用pivot_root/namespace/daemon执行实际产品compile→rm→安全copy→execute，两个不同容器退出0；C++17产物18840字节，最终输出TASK10_SEPARATE_STAGE_OK。实际卷为32MiB tmpfs、root:root0755、nosuid/nodev，readonly input与scratch权限正反例通过；没有用替身取代产品后端。环境辅助18/18、观察器合同9/9另计。
- 预算保持2GiB内存/swap0、2CPU/512pids、570+30秒、tmpfs1408MiB；整树memory.peak=1514184704字节，禁止用systemd10.7M或attach CLI RSS冒充。只读导入已有锁定归档，无新大二进制和共享daemon写入。
- 所有阶段/holder/volume按本轮归属回收，清单为空，daemon退出0；外侧共享231/317均active/NRestarts0。七份实际运行快照哈希独立复核一致。详细命令、ID、产物哈希、inspect、journal及边界见stages.md和STAGE-R01。
- 仅真实USER编译/执行正例及有限边界断言完成；四角色控制事件/摘要/取消/工具/私有数据、FD/信号负例，可信监督器镜像与Java接入、持久恢复和完整全量回归仍未完成。TASK-10保持IN_PROGRESS、不提交、不关闭DEF-05/N2、不宣布可发布；后续工作继续按已批准范围执行。

### 2026-10-03 — TASK-10 / CONTRACT-R07、STAGE-R02 — 四角色实际控制面负例

- 新增强制阶段配置的linux_supervisor.py及23项合同，配置在可信attempt内经有界FD读取和精确字段/身份验证，使用真实executor、无local fallback；复用dataclass字段/manifest校验遵循DRY。另修复TaskRunner一般阶段异常的私有路径脱敏并新增1项合同。R07实际89/89零失败/错误/跳过、unit退出0，保留11份源/测试快照与日志；本轮监督器入口尚未真实运行。
- R02实际10个独立阶段容器完成正常分阶段正例、真实TaskRunner三角色LEGACY_TEXT单用例，以及独立SPJ编译/执行；unit退出0，耗时28.316秒。四角色的控制文件、原始源码、实际监督工具/私有数据、进程/FD与固定SIGCONT负例均通过；编译器__has_include控制路径守卫通过。辅助18+9+7项另计，不替代真实证据。
- 四条宿主audit使用约定root/events.jsonl，metadata/final使用约定根路径；先后哈希和runner首事件/六条事件序列核验。有限伪ac=999的SUMMARY/COMPLETED仅作为stdout数据，真实摘要始终AC1/completed1。SPJ只取得当前case.in/out，既有事件/摘要不变；未宣称TaskRunner SPJ模式全链路。
- 可信哨兵PID689持有实际私有FD；每角色不可见该进程/FD/root，固定SIGCONT返回ESRCH，哨兵保持存活至可信侧精确清理（-15）。未运行无限输出/fork/资源耗尽或对生产攻击。外层预算不变，整树峰值1530814464字节；全部阶段/holder/volume清理，专用daemon退出0。九份运行快照哈希复核一致。
- 详细事实与限制见docs/acceptance/TASK-10/stages.md、CONTRACT-R07和STAGE-R02。仍需真实监督器CLI/镜像、Java适配器、持久恢复/取消竞态与完整回归，TASK-10保持IN_PROGRESS，无新提交/推送；DEF-05/N2与发布门禁不提前关闭。

### 2026-10-03 — TASK-10 / SUPERVISOR-R01/R02、CONTRACT-R08 — 真实监督器CLI与配置完整性

- R01实际专用域CLI错误配置返回30、有效配置返回0并AC1，6个实际阶段journal均CLEANED；加基线2阶段，服务21.102秒、memory.peak1528872960字节。不是监督器镜像或Java部署闭环，也没有给CLI阶段注入替身/逐阶段观察器。
- 只读复核提出配置owner/mode缺口后，增加显式必填--config-owner-uid及FD逐层目录/文件完整性验证，禁止group/other写，工作负载UID不能等于可信写入者/监督器。配置字段仍复用LinuxStageConfig，无生产UID默认，保留0644/0755合法输入，未虚称配置保密。
- CONTRACT-R08实际100/100、零失败/错误/跳过、退出0，保存11份源码/测试快照、哈希与完整日志；新增11项监督器属主/权限/身份/CLI反例。旧R07与此前失败记录保留。
- R02真实缺字段、错误owner、配置0666、父目录0775四负例均在执行副作用前返回30；只改本轮新路径并恢复。有效配置继续实际AC1、六阶段CLEANED、四条audit和所有可信文件未变，服务23.800秒、memory.peak1528909824字节，预算未扩大。所有阶段/holder/volume回收、daemon退出0，11份运行快照哈希复核一致。详见stages.md及SUPERVISOR-R01/R02。
- 产品仍缺可信监督器镜像、Java启动/探针与取消/持久恢复、创建回执丢失/晚到创建竞态、完整新回归。Windows native provider的生产能力/启动还需按TASK-10明确禁用未验收控制面路径（当前仅Linux门禁已明确失败关闭），不得把Linux实验结论推广给其他provider。TASK-12输出无界问题仍存在。
- 保持TASK-10 IN_PROGRESS，无暂存/提交，无新SHA、未推送，整体5/30且不可发布。下阶段需同时遵守明确身份/存储权限映射与既有handle恢复契约，不能直接给旧adapter换镜像放行。

### 2026-10-03 — TASK-10 / R04 — Windows未验收路径失败关闭

- 恢复核验HEAD仍为5f672c2faa9d02e2e3cd95c832ef4623bc222301，TASK-10全部实现/证据未提交；补齐task.md落后的R08/SUPERVISOR-R02状态。用户再次授权后续已提操作，仍不扩大为生产操作、push或未冻结业务默认。
- 并行worker仅修改WindowsHyperVContainerRunner及其测试、ProductionSecurityStartupValidator及其测试；主代理集成/受限运行/文档。旧Windows后端明确controlPlaneIsolated=false、productionSafe=false；非精确trusted-local在目录/manifest/命令副作用前拒绝。共同容器startup复用实际选定runner probe，缺失/异常/矛盾均失败关闭，不接受人工passed/required组合覆盖。
- R04命令由既有TASK-00/run-baseline.sh以worktree只读快照执行，Maven定向WindowsHyperVContainerRunnerTest,ProductionSecurityStartupValidatorTest,LinuxContainerRunnerTest；实际87项、85通过、0失败、0错误、2跳过、退出0，service35.962秒。两skip仍为Linux缺cpp-judge-runner:latest及WSL运行时非Windows，startup51项全部通过，不以替身合同/skip声称Windows真实隔离。
- 外层4GiB/swap0/2CPU/512pids、断网、只读源码、tmpfs2GiB，复用受信回归环境；没有执行攻击样例。证据docs/acceptance/TASK-10/R04，133项Java源码/测试/POM哈希另行独立核对一致（java-hash-verification.json）；Python正在后续独立修改，未把R04称作新增Python回归。
- R02后补查共享containerd231/dockerd317均active/running、NRestarts0，专用supervisor-r02单元inactive/dead。首次bash/awk引号解析失败退出1，随后独立WSL只读argv成功；失败历史见stages.md，不宣称初次检查成功。
- 只读复核确认未知create超时后当前不存在不等于永不落地，旧finally可能错误写CLEANED。已另派持久UNKNOWN/阻止同attempt继续工作的小范围修复及真实延迟请求验收准备；没有把有限轮询或停止CLI视为创建屏障，完整恢复尚未解决。独立监督器镜像构建准备也未计为通过。
- 原则：DRY复用两平台startup能力检查，KISS以失败关闭消除未验收启用路径，不引入可手工放行配置；保持trusted-local旧合同，未扩展Windows支持。已更新两生产runbook、runner README和change-log。
- 期间发现非本任务的.idea/jarRepositories.xml变动，保留、不修改、不纳入后续暂存；既有.mimosa/.playwright-mcp仍未读取或清理。TASK-10仍IN_PROGRESS、无本地提交、整体5/30、DEF-05/N2未关闭、不可发布。

### 2026-10-03 — TASK-10 / CONTRACT-R09、SUPERVISOR-BUILD-R01/R02、R05

- 修复未知create被误计为CLEANED：调用前持久journalVersion1/creationState UNKNOWN，完整ID及归属inspect后才CONFIRMED；未知结果保留目录和CREATE_UNKNOWN，拒绝产物导出及该attempt继续。安全有界journal读取与目录FD非阻塞flock覆盖全阶段，新实例也不能绕过。仅修改linux_stage_executor.py及对应合同，未实现权威创建屏障或恢复消费者；不以有限空列表/停止CLI代替终结证据。
- CONTRACT-R09真实118/118、零失败/错误/跳过，Python22.273秒、unit23.940秒、测试和unit退出0。PS stderr/Tee包装报NativeCommandError并退出1，已区分记录而非修改通过结果。11份源码/测试快照和hash全部匹配，新增阶段合同从30至48。
- 新增runner/linux-supervisor三文件与harness独立监督器build/replay模式；五源码hash锁、26个官方签名APK锁定，先停止取包daemon再断网构建，R02全程断网重放。无pip/GCC/dockerd，未向共享daemon构建/加载/运行。两个实际unit退出0、29.701/15.038秒，辅助合同各32/32，整树memory.peak725282816/725053440字节，预算未增加。源码/目录只读、五模块导入、静态capabilities、缺参返回30全部真实镜像内执行；不等于控制面隔离验收。
- 两归档均120208384字节，完整ID与独立Get-FileHash验证见supervisor-build.md；每轮10份snapshot hash匹配。R02源码/包锁相同但镜像ID和归档hash不同，明确不是逐位确定性重建。大归档仅留Git忽略target，不提交。USER root仅实验可信域身份，尚未解决生产writer UID映射。
- R05全量真实失败：42套件418项，415通过/1失败/0错误/2跳过、Maven及unit退出1；service78.655秒。JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe期望RE实际SYSTEM_ERROR，是TASK-08已记录的间歇项再现，本轮尚未定位根因，不能由旧R03绿结果覆盖。未放松断言、未重跑后隐藏失败。两环境skip原因与R04一致。Python全套独立实际118/118零跳过且退出0。163份受测源码/脚本/POM哈希全部匹配（R05/source-hash-verification.json）。
- 正在准备镜像内监督器六阶段与真实延迟请求故障注入；这些准备未计为通过。TASK-10仍IN_PROGRESS、未暂存/提交、无新SHA、未推送，DEF-05/N2未关闭且不可发布。下一步先完成当前真实边界验收与失败诊断，再推进Java/持久生命周期，不能给旧adapter换镜像放行。

### 2026-10-03 — TASK-10 / R06 — 有界诊断与一次全量复跑

- 只读定位确认DirectProcessRunner通过文件redirectInput，不存在向用户stdin写管道的路径；不能猜测为broken pipe。RE正常映射明确，SYSTEM_ERROR可来自生成器、编译/调度或用例异常；R05现存摘要缺少terminal message/results，尚不能区分根因。
- 仅给JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe的原RE等值断言添加有限长度status/message/progress/results诊断描述；不改变fixture、期望结果、等待/重试或生产代码。按同一受限快照流程执行一次完整R06。
- R06真实42套件418项、416通过/0失败/0错误/2原环境跳过，Maven/unit退出0，service78.686秒；同轮Python118/118零跳过。163份源码/脚本/POM快照与当前文件hash匹配，证据R06/source-hash-verification.json。未复现不是根因修复，保留R05失败及后续定位要求，不反复运行到绿来关闭间歇问题。
- 监督器两轮build及R05结束后只读检查仅containerd231/dockerd317、active/NRestarts0，专用unit均not-found/inactive/dead；not-found时systemctl的默认Result字段不作为历史退出码，R05真实退出1仍以原运行/summary为准。
- 新恢复故障代理helper在512MiB/swap0/1CPU/64pids/断网/源码只读/64MiB tmpfs服务内实跑16/16、零失败/错误/跳过、退出0；没有连接Docker，不能计为真实晚到创建验收。当前继续准备真实镜像内入口及故障注入，TASK-10与发布门禁均未关闭。

### 2026-10-03 — TASK-10 / 容器化R04、恢复R02、全量R07及证据补核

- 修复Docker卷copy-up带来的scratch身份漂移：两个阶段mount均显式volume-nocopy，不添加DAC能力、不放宽监督器权限。真实故障证据先见root:root/0777经create变为65532:65532/0700；修复后RECOVERY-R02仍保持原权限，实际inspect确认NoCopy。辅助合同stage_probe增至13项、recovery增至17项。
- SUPERVISOR-BUILD-R03断网构建通过，16.911秒、整树memory.peak724869120；监督器归档120208384字节，SHA256 5a3780ceb7579738facfdf0730f560add2c6584e289b424959084ea42a292929，独立Windows核验匹配。未宣称逐位确定性构建，未在共享daemon加载/运行。
- SUPERVISOR-CONTAINER-R01因ENOSPC失败，R02因copy-up导致CLEANUP_FAILED，R03因harness计数守卫未同步失败；全部保留。R04真实unit0、27.674秒、整树memory.peak2035732480，48+13+7辅助合同通过；四权限/配置负例均30、合法任务0且AC1，六个独立阶段journal均CLEANED。监督器cap-drop ALL/NNP/只读根/断网，仅任务根、单卷原Mountpoint及专用socket三挂载。它不是Java入口验收，内部阶段未逐个留inspect/stdout。
- RECOVERY-R01/R02真实延迟create均通过；R02 unit0、27.958秒、整树memory.peak1534025728，48+13+7+17辅助合同通过。CLI先超时、daemon当时无容器，新executor拒绝；随后唯一create真实201且created/Pid0，新executor仍拒绝，UNKNOWN保留。关闭/join代理并证明已持请求终结后由实验harness归属清理，不是产品恢复消费者。两种最新实验最终容器/卷为空、daemon0，快照hash已核验。
- R07 Java418项415通过/1失败/0错误/2原环境skip，Maven/unit1、78.768秒；Python118/118零skip且退出0。失败确切消息为terminal SYSTEM_ERROR / Judge executor rejected the task / progress100 / results=null，定位JudgeScheduler.execute捕获RejectedExecutionException分支，不再猜测用户stdin。生产调度器尚未改动，不能用R06绿结果覆盖R05/R07失败。
- 补核R07全部163份受测输入与当前工作树hash一致，见R07/source-hash-verification.json；随后仅更新验收文档。首次保存长补丁超过Windowsargv限制，未写入；改分段apply_patch并验证JSON163条。
- 18:44只读复核共享docker PID317、containerd PID231均active/running且NRestarts0，TASK-10无loaded unit，进程列表未见实验daemon/probe，仅共享daemon及既有shim；不清理共享资源。首个跨shell命令因引号解析失败，后用独立WSL argv重试成功；原失败不计为核验。
- 保留IDE的.idea/jarRepositories.xml及.idea/misc.xml，不纳入本任务。TASK-10仍IN_PROGRESS，未暂存/提交、未推送，5/30、DEF-05/N2未关闭；下一步目录删除保护与产品恢复接线，调度拒绝另做只读根因分析，不擅自扩展TASK-18。

### 2026-10-03 — TASK-10 / 删除保护红绿回归与调度拒绝确定性复现

- 先补测试，CLEANUP-R01在原生产代码真实28项12通过/16失败/零错误跳过，Maven/unit1、11.356秒；证明无handle时受管证据会被删除。再仅修改FileTaskStore：原完整路径预检后、任何首个删除前检查control/attempts所有直接子项；三类受管标记存在即拒绝，父路径异常失败关闭，不解析CLEANED放行。TaskCleanupService复用原失败列表，不改生产流程。
- 补充旧UNKNOWN与新manifest共存、SYSTEM_ERROR/STALE及null/空runId后，CLEANUP-R02真实32/32、零失败/错误/skip、Maven/unit0、15.385秒；Linux链接负例实际执行，legacy任务仍正常清理。两轮163份输入hash核验匹配。
- 该门禁有意保留全部受管任务，即使stage已CLEANED；未实现任务级恢复凭据，可能持续占存储。它仅保护检查时已落盘的标记，不解决跨进程创建/删除窗口、不实现权威create屏障或自动恢复。KISS：避免另造不完备JSON放行器；DRY：保留现有路径预检和服务失败处理；无业务额度或新生产默认。
- 独立只读代理复核生产及测试diff，未发现本次范围阻断；未独立重跑测试，不等于TASK-29安全放行。详见cleanup-protection.md。
- SCHEDULER-PROBE-R01用真实AsyncConfig与JudgeScheduler、有限latch确定排队，准确复现后继SYSTEM_ERROR / Judge executor rejected the task / 执行0次；诊断1/1、零skip、Maven/unit0、9.220秒。夹具仅注入私有快照，未改正常测试发现规则或生产调度器。160份源码hash及两诊断文件hash匹配。成功表示缺陷存在，不是修复验收；无R07完整轨迹，r07CausalityProven=false。代码级机制是零队列执行器工作线程在finally中尚未退出即重提交后继。详见scheduler-rejection.md，后续归属TASK-18调度交接修复。
- R08完成本次生产修改后全量：42套件439项437通过/0失败/0错误/2原环境skip，Maven/unit0、80.825秒；Python118/118零skip、退出0。163份受测输入hash匹配。R08未触发间歇失败不代表调度缺陷修复，保留R05/R07失败及上述确定性复现；不重跑到绿放行。
- TASK-10仍IN_PROGRESS，缺Java监督器适配、完整持久恢复/取消和平台验收；未暂存/提交、未推送，HEAD仍5f672c2，整体5/30、DEF-05/N2未关闭、不可发布。下一步核对Java部署显式配置与持久生命周期接入点。
- 后续Java接入只读核对已完成并存java-supervisor-integration.md：暴露旧probeProtocol未journal化docker run、旧CommandExecutor无界读取、普通writer与root无DAC目录权限不兼容等真实接入限制。未新增Java监督器模式、未自动采用生产root身份或任何部署默认。最终只读检查git diff --check退出0、无暂存、新HEAD未产生，docker317/containerd231均active/NRestarts0、TASK-10无loaded unit。


### 2026-10-03 — TASK-10 / JAVA-CONTRACT-R01～R04、全量R09/R10

- 定位键仍为TASK-10-C01，未完成、未暂存/提交。本段补录前轮Java实现与本轮inspect红绿回归，不替换历史失败。
- 新增LinuxSupervisorOptions、LinuxRuntimeJournal、LinuxSupervisorCommandExecutor、LinuxSupervisorLauncher及测试，SandboxProperties/Runner接入显式可选模式，FileTaskStore保护runtime-state.lock和task级锁。无生产默认，只允许实验trusted-local；纯校验及manifest/目录链force完成后才允许create，UNKNOWN先持久、完整ID/严格inspect后CONFIRMED，start前START_REQUESTED。
- Journal采用有界读取、严格JSON/身份/权限/单链接校验及独占FileChannel锁，原子替换无fallback；不是Python flock互通或断电恢复证明。有界CLI共享双流预算、统一截止时间，子孙终止仅尽力。stop仅STOPPED/CLEANUP_PENDING，cancel明确报恢复未完成，生产门禁保持关闭。
- JAVA-CONTRACT-R01真实325项322过/2错误/1skip，错误来自LongNode落盘IntNode后配置比较；修复同mapper往返比较，浮点仍拒绝。该轮后核验2份FileTaskStore输入已变动，原样保留。R02为347项346过/0失败错误/1skip、16.175秒；R09全量737项735过/0失败错误/2skip、84.276秒，Python118/118；两轮171份源码hash匹配。
- 独立复核发现inspect对UTSMode/UsernsMode/OomKillDisable/Mounts.ReadOnly/State.Error隐式转换过宽。先新增76项畸形负例和4组合合法null/省略正例；R03真实157项97过/60失败/0错误/skip，Maven/unit1、13.738秒，失败均为应拒绝却放行。只改精确类型谓词后R04七套件427项426过/0失败错误/1skip，Maven/unit0、16.184秒。
- 最新R10真实46套件817项815过/0失败错误/2原环境skip，Python118/118零skip，Maven/unit0、85.219秒。R04/R10的171份源码hash全部匹配且清单相同；R03与当前仅launcher修复差异，证据各自保存。skip是旧Linux镜像缺失及Windows运行时不适用，不是新Java真实provider验收。
- 全部测试沿用已批准私有快照：4GiB/swap0/2CPU/512pids/600秒、断网、源码只读、2GiB私有tmpfs。systemd显示约7MiB不是整棵测试树内存峰值，不作性能证明。独立只读复核确认此次5字段修复无范围内剩余阻断；未冒充独立实跑。
- KISS/DRY：复用现有manifest/RunHandle/CommandExecutor，只抽取精确类型小谓词；不改协议或业务额度，不扩大到TASK-12空poll和TASK-18调度缺陷。未完成真实Java→Docker、产品持久恢复/归属回收、跨进程删除冻结及真实取消；harness准备不计验收。
- 已同步task、README、java-supervisor-integration及change-log；保留两份IDE改动，不读取清理.mimosa/.playwright-mcp。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无新SHA、未推送；整体5/30，DEF-05/N2未关闭、不可发布。


### 2026-10-03 — TASK-10 / JVM-BUILD-R01～R05、JAVA-SUPERVISOR-R01～R03

- 定位键TASK-10-C01，仍IN_PROGRESS。新增冻结构建脚本、Classpath测试、Java真实probe及独立Java模式harness，复用现有隔离域；产品源码保持R10受测版本，不新增框架/依赖。
- 构建R01/R02的Maven1/1通过但打包unit1（24.406/27.802秒）；有界诊断实证唯一尾空classpath项，严格省略该项且实际classpath不含cwd。R03/R04/R05构建unit0（39.496/36.735/35.552秒），每包276文件/96解析jar；168份产品输入hash匹配。R05归档68321280字节，SHA256=92f18489eea97d4830e262813932cae35e81e7e60ead59b9dd9c8ca7a88ab555；二进制只留忽略target。
- JAVA-HELPER-R01在512MiB/1CPU/64pids/120秒/断网/源码只读/64MiB tmpfs实跑64/64零skip、unit0。真实R01 unit1/58.494秒，start成功且磁盘AC1，但probe未留poll错误详情。JAVA-MAPPER-R01因JVM参数引用错误未执行；R02有界真实事件重放证实只注册JavaTimeModule缺失TestCaseResult构造映射，而findAndAddModules成功。仅修harness mapper，并补有界逐批诊断。
- 真实R02 unit1/62.081秒，六poll事件已与磁盘一致/AC1；harness把产品runtime-client目录误计为journal，六份真实journal合法。最小修复明确校验该唯一空目录root:root0700/非链接，不忽略未知项，不改六journal断言；失败保留。
- 真实R03 unit0/74.739秒：真实LinuxContainerRunner(...,null,mapper)启动及poll成功，AC1、六事件、六CONFIRMED/CLEANED阶段、控制文件哈希不变、supervisor/holder/volume独立inspect通过。辅助64+13+7全过零skip，12份输入快照hash匹配；R05的276资产/168源码和归档独立核验全部匹配。
- 实验预算未扩大：4GiB/swap0/2CPU/512pids/570+30秒，根tmpfs1792MiB，含shm和卷总预算2016MiB。私有mount/cgroup/net/ipc/PID、pivot_root/私有proc/卸载旧根；JDK只读明确配置与证书，JNA私有0700临时目录，不挂宿主home/.m2。systemd约13MiB不是整树峰值，不作性能结论。
- 成功验收明确productionAcceptance=false、productRecoveryComplete=false；本轮没有Java UNKNOWN注入/真实cancel/HTTP全链路/生产普通UID验收。保留资源供inspect后销毁专用域不是产品恢复。事后只读核验共享docker317/containerd231 active/running、NRestarts0、无TASK-10 loaded unit，无共享资源清理。
- 恢复设计只读研究保存recovery-consumer-design.md：UNKNOWN需要权威服务端终结屏障，普通锁/空list不够；回收先停止监督器，移除stage→supervisor→holder→volume；目录删除须外部稳定锁。生产运行时代际范围/UID授权仍需确认，不把研究当实现。
- KISS/DRY复用真实runner、解析classpath和现有专用域，按事实修harness，不修改产品来迎合夹具。已同步task/work/change-log/README/集成说明；HEAD仍5f672c2，无暂存提交/推送，保护IDE改动，整体5/30，DEF-05/N2未关闭、不可发布。

### 2026-10-03 — TASK-10 / LIFECYCLE-R01～R05、R11、JVM-BUILD-R06及JAVA-SUPERVISOR-R04

- 定位键TASK-10-C01，仍IN_PROGRESS。新增TaskLifecycleGuard与真实子JVM测试；Store创建/状态/handle/摘要/删除纳入稳定storage-root准入，禁止晚到更新隐式重建。Launcher短准入中取得永久task marker/lock，Docker命令前释放外锁；JudgeService删除目录回退创建，统一两路径源码准备及SPJ参数写入。未改业务额度、空poll或调度逻辑。
- guard→task monitor/lock统一顺序避免源码准备读取metadata时死锁；固定JVM锁避免新增无界注册表。跨进程锁冲突失败关闭，目录链/fileKey/owner、POSIX私有锁、单链接复验；只有显式create可创建根。所有受管marker仍保留，没有恢复凭据放行。
- 红绿实跑：LIFECYCLE-R01为201项185过/16失败、14.856秒；R02为235/235、18.667秒；R03为9项5过/4失败、13.190秒；R04为245项244过/1夹具String/Path比较失败、20.132秒；仅修精确期望类型后R05为245/245、19.663秒，均零错误/skip。R05的174份源码hash匹配；R01～R04差异如实保存。子JVM证明更新临界区阻止另一进程删除，删除后旧Store不复活；线程测试实际WAITING后验证读方法不被反向锁阻塞。
- R11全量48套件868项865过/1失败/0错误/2原环境skip，Python118/118零skip；Maven/unit1、102.427秒，174份源码hash匹配。唯一失败JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe收到SYSTEM_ERROR/执行器拒绝，而非RE。与独立SCHEDULER-PROBE-R01症状一致，未捕获完整R11调度轨迹，不超额宣称因果。未修改断言或重跑掩盖全量红；TASK-18缺陷仍未修。
- JVM-BUILD-R06离线构建1/1、unit0、35.242秒，279资产/96解析jar/171源码hash全部匹配；manifest SHA256=d3e64d35d14c0fb52124c11d090e6ed522077881b075f990f01fb14d9f103492，归档SHA256=89b13f602c74ebce0a8a5ad287a08e9742011aec33f21a9b8f8518530d4ac4ca。归档仅留忽略target，不提交二进制。
- JAVA-SUPERVISOR-R04使用R06新包，unit0、76.091秒：真实Java默认executor→专用Docker→六阶段→poll、AC1/六事件、可信控制文件不变与独立inspect均通过；辅助64+13+7零skip，12份输入快照hash匹配。直接覆盖Launcher准入，不冒称实跑FileTaskStore/JudgeService HTTP全链路。保留supervisor/holder/volume后销毁专用域，不是产品恢复。
- 未扩大已批准预算：构建4GiB/2CPU/512pids/570+30秒/断网/只读源码/2GiB tmpfs；真实实验相同内存CPU进程时限，1792MiB根tmpfs、合计2016MiB临时存储、私有namespace/pivot_root。systemd约4/13MiB值不是整树峰值。事后共享docker317/containerd231 active/running、NRestarts0，无TASK-10 loaded unit，未清理共享资源。
- 独立只读审查确认稳定锁已实现、设计历史说明需校正；下一可做严格状态分类与持久发现，但UNKNOWN屏障/完整归属回收/可信删除凭据/真实cancel仍未实现。JudgeService叶文件普通writeString、旧SPJ参数删除未持guard，legacy不声明全面冻结；Windows ACL、恶意同可信UID替换及断电恢复未验收。
- KISS/DRY/SOLID：稳定短锁与运行时生命周期分责，复用源文件准备与现有身份校验，不引入新业务默认。已同步task/work/change-log及相关验收文档，历史失败保留。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无新提交/推送，保护IDE及工具目录；整体5/30，DEF-05/N2未关闭、不可发布。

### 2026-10-03 — TASK-10 / RECOVERY-STATE-R01～R04、R12/R13、JVM-BUILD-R07及JAVA-SUPERVISOR-R05

- 定位键TASK-10-C01，仍IN_PROGRESS。新增包内LinuxRuntimeState严格分类13种v1持久快照；stop在journal锁内、任何运行时命令/修改前调用。字段集合/类型、身份、名称、完整互异ID、资源组合与配置均校验；资源名复用同一函数。没有RECOVERED/删除许可/伪generation，不把磁盘状态当服务端终结证明，低层journal职责保持独立。
- RECOVERY-STATE-R01实际43项43失败、零错误/skip，unit1、13.075秒，均为预期IOException未抛。R02为767/767、unit0、17.095秒；其中State536测试包含一项枚举1715组合/拒绝1702非法组合，不虚增JUnit数量。五种合法confirmed supervisor阶段两次stop均要求inspect→stop→inspect，零删除。
- 独立复核发现旧Launcher路径规则允许NBSP/DEL但新State拒绝。R03真实5项1过/4失败、unit1、11.420秒：workDir拒绝前已有副作用，非法volume mountpoint已被标CONFIRMED；实际writer的13个落盘快照分类正例通过。仅将Options既有严格路径函数包内复用，Launcher/State委托，未放宽断言。R04四套件956/956、零skip、unit0、18.374秒；176份源码hash匹配。历史红基线差异保留。
- R12全量1452项1449过/1失败/0错误/2原环境skip，Maven/unit1、107.175秒；最终R13为1457项1454过/1失败/0错误/2skip，Maven/unit1、109.851秒。两轮Python均118/118零skip，受测176份源码各自在核验时匹配；R13与R04清单相同。唯一失败均为RE基线收到SYSTEM_ERROR/Judge executor rejected the task；未修改调度或用重跑掩盖失败，TASK-18仍待修。
- 新真实probe先有界inspect确认正常退出，再两次实调runner.cancel并要求精确pending异常、无cause；journal仅允许CLEANUP_PENDING/STOPPED变化，task marker fileKey、事件/summary、源码与控制文件均不变。独立复核指出的自然退出竞态与停止后hash漏检在实跑前已修，不放宽ExitCode=0。
- JVM-BUILD-R07离线1/1、unit0、85.450秒，173份构建输入/281资产/96jar独立hash核验一致；manifest SHA256=69e3f77785f64bdb28c69778ee9682021ee8b74a156f87ff469cc123e1130fca，归档SHA256=4efa1f83cda79316407fe4c6effd1ec980b41703c2f7622a9cb11e8027b75a7e。二进制只留忽略target。
- JAVA-SUPERVISOR-R05使用R07新包，unit0、71.605秒：真实六阶段/六事件/AC1、两次完成后停止、pending持久结果与独立inspect通过；辅助64+13+7全过零skip，12快照hash匹配。supervisor/holder/volume仍保留，由专用域销毁，不是产品恢复；不宣称活跃负载取消。共享docker317/containerd231仍active/running、NRestarts0，无TASK-10 loaded unit。
- 全部沿用原冻结资源/隔离预算，未并行Maven与真实域、未扩大授权；systemd约8/13MiB非整树峰值。独立只读最终复核未发现本轮新增阻断，不冒充额外实跑。KISS/DRY复用既有Options规则和运行时合同，分类/安全读写/运行时操作各自分责，不改业务终态、事件协议、调度或自动重启政策。
- 缺handle/metadata的有界持久发现尚未实现；扫描预算、不完整报告和有界锁准入需先落地，不直接塞进现有STALE/过期删除链。UNKNOWN权威屏障、完整归属回收、可信删除凭据、真实活跃取消及生产generation/权限/UID仍未解决。task/验收文档/变更日志已同步，HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无新提交/推送；保留IDE改动及工具目录，整体5/30、不可发布。


### 2026-10-03 — TASK-10 / DISCOVERY-R01～R03、R14、JVM-BUILD-R08～R11及JAVA-SUPERVISOR-R06

- 定位键TASK-10-C01，仍IN_PROGRESS。新增显式LinuxAttemptDiscovery并接入runner.inspectRetainedAttempts；不依赖handle/runs/metadata，只按可信storage标准固定层级观察。显式目录/报告/读取/路径预算，JSON报告保守上界；根guard→task锁→journal锁，复用严格v1分类和配置。stage只标记需冻结，不消费Python记录；完整枚举也只报告RECOVERY_REQUIRED，绝非删除许可。
- TaskLifecycleGuard新增tryAcquireExisting：JVM tryLock+OS tryLock，无等待冲突、不创建root或稳定锁、保留中断。Guard共30项，含确定性竞争和独立子JVM退出/强制退出恢复；原acquire语义不变。普通文件系统调用不宣称硬实时期限。
- DISCOVERY-R01为329项328过/1失败、unit1/25.505秒，暴露正式runner-summary.json遗漏；R02三条均红、unit1/10.818秒，另确认无control的合法未启动任务被误报。仅补正式summary白名单、NOFOLLOW_LINKS属性检查且仅NoSuchFileException表示缺control；权限/链接/临时summary/存在control缺锁负例保持拒绝。R03八套件1082/1082零skip、unit0/20.013秒，Discovery52、Guard30、Launcher214、State536、Journal22、Options184、Store42、StoreProcess2。
- 原session61145已轮询终态，R14全量1516项1513过/1失败/0错误/2原环境skip，Maven/unit1、102.646秒；Python118/118零skip。唯一runtimeErrorIsRecordedAsSingleCaseRe仍为SYSTEM_ERROR/Judge executor rejected the task，而非期望RE。未改调度或重跑掩盖；独立调度probe不等于本轮完整因果证明。R03/R14各178份源码hash全匹配且清单相同，R01/R02两份后续修复差异保留。
- 新真实辅助器标准化私有storage/judge目录，六阶段完成和两次stop后启动独立JVM；不传handle/attempt、不调用start。固定JDK/classpath及子进程20秒、Xmx96m等资源上限，不增加外层预算。Python独立校验PID、实际attempt/pending分类与具体预算，新增两项共19负例，辅助测试数66。
- 独立审查找到库存Files.walk深度6漏掉runtime-client内第7层文件；提取可测函数并改为完整walk、129项探测/128项上限。先保留R08编译错误（hash受检Exception未声明，unit1/16.195秒），R09为2项1失败（unit1/14.118秒），增强脱敏报告后R10明确深层库存测试失败（unit1/20.959秒）。修复后R11为2/2、unit0/40.353秒，175输入、288资产/96jar独立hash匹配；manifest=27aa11f2ef7386cd6ba6fc5ebe18eeea0cbb93d7ae74d524fa2fb48ad3da17a8，archive=60774f53d4bf605cf577cd524769bf32bfbaab7c22977c625e66a5f47f5ef1a9。红轮源码和辅助器差异另行保留。
- JAVA-SUPERVISOR-R06只用R11新包，unit0/69.228秒、Java0：真实六阶段/六事件/AC1、两次完成后停止及新JVM无handle发现通过。父PID213、子1539，访问12目录项、预留131074读取字节，唯一真实attempt为RECOVERY_REQUIRED/SUPERVISOR_STOPPED且stage需冻结；36项前后库存相等。证据是路径/身份/权限/内容hash的前后相等，不是系统调用审计或无瞬时写入证明。辅助66+13+7全过、12快照hash匹配。
- 独立inspect确认supervisor已退出、holder仍活、两容器和卷仍保留；最终专用daemon退出0，域销毁不计产品回收。事后共享服务只读检查首次沙箱WSL E_ACCESSDENIED，按权限流程重试成功：docker317/containerd231 active/running、NRestarts0，无TASK-10 loaded unit。没有停止共享daemon，systemd12.9MiB不是整树内存峰值。
- KISS/DRY/SOLID：只加显式观察入口，复用锁/目录/配置/状态校验；预算报告与实际恢复分责，不接入STALE/过期删除，不改变业务终态/事件/调度。独立只读复核本轮新JVM辅助器无剩余阻断，未冒充额外实跑。已新增persistent-discovery.md并同步task、work及change-log。
- 尚需持久关闭创建入口、UNKNOWN权威终结、Python冻结后stage归属回收、supervisor移除后再移除holder/volume、可信删除凭据及真实活跃取消；生产generation/权限/UID未冻结。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无本地提交/推送；保留两项IDE改动，不读写工具目录。整体5/30、DEF-05/N2未关闭、不可发布。


### 2026-10-03（UTC） — TASK-10 / STAGE-ADMISSION-R01～R04、R15、SUPERVISOR-BUILD-R04、JVM-BUILD-R12及JAVA-SUPERVISOR-R07/R08

- 定位键TASK-10-C01，仍IN_PROGRESS。Python新增close_existing_stage_admission，复用open_directory与_stage同目录inode flock；明确可信调用方绑定task/attempt及祖先目录，缺目录/忙锁不创建、不等待。stage在runtime/INTENT/scratch前拒绝closed/pending，新旧executor及四角色compile/execute一致；不改业务终态、不解除UNKNOWN、不调用Docker回收资源。
- 写入顺序为排他pending→fsync目录→写JSON/fsync文件→原子closed→fsync目录→复读；正确重复保留inode/hash，坏marker或pending不覆盖/删除。目录锁前后、发布期间与返回前复核同inode，修复了旧stage路径替换后仍执行的窗口。marker严格三字段/版本/attempt、0600/单硬链/有界普通文件，复用重复字段拒绝，错误信息固定。
- STAGE-ADMISSION-R01为140测试方法、42 failure记录（含subTest）、0errors/skip，unit1/8.577秒；缺API与目录替换拒绝未抛的红基线保留，不误记为98项通过。R02为142/142零skip、unit0/11.460秒，较原118新增24方法，含真实独立Python进程双向flock、进程退出、UNKNOWN字节/inode不变、pending/同步/发布/权限/链接负例。21份快照hash匹配，红轮产品及测试差异保留。只证明合作协议与进程崩溃路径，未声称断电或恶意同root防护。
- R15全量Java1516项1513过/1失败/0错误/2原环境skip，Maven/unit1、102.761秒；Python142/142零skip，178份源码hash匹配。唯一runtimeErrorIsRecordedAsSingleCaseRe仍为SYSTEM_ERROR/Judge executor rejected the task，未通过重跑/关断言掩盖；TASK-18未修。运行日志本地时间为2026-10-04T00:23:03+08:00，对应本节UTC日期。
- SUPERVISOR-BUILD-R04复用已锁APK断网重建，unit0/15.551秒，66辅助合同及真实镜像源码/工具/capabilities/拒绝烟测通过；整树memory.peak725458944字节。新image sha256:6f7e70984cfff475fdd0fecc315cc7620c16b8f0e0faa23a22414b7271bbd901；120221696字节archive SHA256=5b8eb56ef08c5f1c1db854b68cd97d408e5dd906d107c7d4eac827006e4c3288；11快照和归档独立核验。JVM-BUILD-R12为2/2、unit0/67.032秒，175输入、288资产/96jar全匹配；manifest=6ca6c55ffbb82ddbae6aeaaf88fd22d8bc93b9b2ca12ae9d412b06b5823eb33d，archive=5e2b0f42ec93bdcd0761f733628da753aa0e585afafe61c7ae1981ba8373d8aa。
- 真实JAVA-SUPERVISOR-R07虽unit0/89.861秒、辅助73+13+7通过，但独立审查发现marker指纹未绑定实际CLOSED内容；明确记录stageAdmissionAcceptance=false，不以退出0冒充严格验收。STAGE-ADMISSION-R03验证器为74方法/3subTest failure、0errors/skip、unit1/2.676秒，证明API均报CLOSED、指纹均相同时错误字节仍可能被接受。仅增加canonical字节的直接比较和长度/hash绑定，R04为74/74、unit0/7.044秒；24份快照匹配，红轮辅助器差异保留。
- 最终JAVA-SUPERVISOR-R08用新R04镜像/R12Java包和修复辅助器，unit0/76.516秒、Java0：真实六阶段/六事件/AC1、两次完成后stop、新JVM无handle发现及独立inspect通过，再由harness对实际保留stage目录调用真实关闭API两次。专用PID命名空间内关闭PID1、独立Python拒绝子进程PID1611，8个角色/phase入口精确闭门拒绝；runtime/process哨兵0调用，不是发出8次Docker请求。90字节标记严格绑定canonical CLOSED、三次inode/hash一致，六条真实stage及Java控制文件不变；辅助74+13+7全过零skip、12快照hash匹配。
- supervisor已退出、holder仍活、两容器和卷保留，专用daemon最终退出0；域销毁不计产品回收。事后共享docker317/containerd231仍active/running、NRestarts0，无TASK-10 loaded unit。所有实验沿用既定预算，未并行Maven/构建/真实域；systemd14.6MiB不是整树峰值。
- 独立审查确认协议内幂等分支只需补目录fsync：产品closed必已文件fsync，不扩展为收养任意手写未同步marker。marker字节绑定缺口也经独立复核确认修正，不冒充额外实跑。KISS/DRY复用既有目录打开、共同stage入口和运行器；准入关闭与权威终结/回收分责，不选生产默认、不清证据。
- 新增stage-admission.md、受限合同/validator运行器，更新task/work/change-log及恢复设计。Java尚未接入真实目录flock和关闭API，早期缺journal窗口、UNKNOWN权威终结、严格stage消费、归属回收、删除凭据、活跃取消仍待完成。只读探索确认可复用已存在JNA，但原生常数/ABI/procfs和真实跨语言互通尚需核验，不能把FileChannel锁冒称flock。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无提交/推送；保留IDE与工具目录，整体5/30、不可发布。

### 2026-10-03（UTC）／2026-10-04（Asia/Shanghai） — TASK-10原生目录flock与目标/任务交接

- 定位键TASK-10-C01，仍IN_PROGRESS。新增LinuxStageJournalGuard及对应测试，复用JNA5.13.0，不新增部署默认/解释器/生产权限；原生open/openat逐级拒绝符号链、目录flock非阻塞获取，procfs实际FD身份与重新打开路径比对，显式owner/default-FS/Linux x86-64门禁。
- Lease的verify/close同步串行；失败poison、关闭前使保存FD失效、close不重试；fcntl验证CLOEXEC。组件不创建目录/锁文件，不写marker，不调用Docker，不解除UNKNOWN，不授权删除。调用者仍负责task/attempt及受控祖先；持FD不自动保证后续普通路径写入安全。
- STAGE-FLOCK-R01红基线3项/0通过/0failures/3errors/0skip，unit1/22.585秒，统一unavailable。ABI-R01以目标头文件及最小C探针核实open/flock标志、int4/指针8、F_GETFD实际flags1，unit0/170ms；保存源码、宏、头文件hash、环境、单元属性。先前sandbox/WSL引号/CR探路失败有记录，未冒充正式通过。
- STAGE-FLOCK-R02为21个展开测试项全部通过、零skip，unit0/24.488秒，覆盖真实Python/JVM双向争锁、子JVM正常/强制退出、目录替换、链接/owner/mode、中断、并发verify/close、FD泄漏与fdinfo/CLOEXEC及不继承；180份源码hash独立匹配，红轮只差产品和测试两文件。
- 最新全量R16为Java1537项/1534通过/1失败/0错误/2原环境skip，Python142/142零skip，unit1/105.915秒；180份源码hash匹配。唯一runtimeErrorIsRecordedAsSingleCaseRe仍期望RE却收到SYSTEM_ERROR / Judge executor rejected the task，归TASK-18未修；两skip仍为旧Linux镜像缺失与WSL非Windows。日志真实结束2026-10-04T01:26:32+08:00，不改写成UTC本地时间。
- 独立只读审查在两文件SHA冻结后进行，限定可信procfs/受控祖先/协作写入者未发现阻断项；无额外实跑。native close错误/EINTR注入、procfs不可用环境负例及确定性交错FD复用仍缺，不扩大成完整恢复验收。native错误统一拒绝，未区分BUSY/EINTR/不支持。
- 事后共享docker317/containerd231均active/running、NRestarts0，无已加载TASK-10单元；全部实验会话已终态，全部子代理完成停写。遵循原预算，不并行重型域，不停止共享daemon；摘要Memory peak未宣称整树峰值。
- 按用户新请求生成docs/project-goal-2026-10-04.md与docs/task-handoff-2026-10-04.md，包含当前任务/真实SHA/红绿及全量结果/冻结资产/运行预算/未决边界/明确下一步；同步task、组件验收、恢复设计及change-log。当前Goal保持active，没有伪标完成或暂停。
- KISS/SRP：新增独立目录守卫，不把锁与回收决策耦合；复用既有JNA和受限测试器。Java仍未接入持久闭门和恢复消费者；旧JVM-BUILD-R12不含新guard，后续集成须重建。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无新提交/推送，保留用户IDE与工具目录，整体5/30、不可发布。

### 2026-10-04 — TASK-10 / STAGE-RECOVERY-R01～R02、全量R17 — Java恢复准备消费者与Python互通闭门

- 定位键TASK-10-C01，仍IN_PROGRESS。新增LinuxStageRecord（journalVersion=1全部14种creation/state组合、20种结构形态、身份绑定，不返回safeToDelete/recovered/generationBarrier）、LinuxStageEntryIo（持锁目录FD的openat/read/write/fsync/fchmod/renameat原语，procfs身份，与close_existing_stage_admission字节级一致的pending→closed发布协议）及LinuxStageRecoveryPreparation（root短准入→task锁→释放root→目录flock→runtime journal严格分类与configuration比对→有界枚举逐条消费→闭门→复核；运行时调用零次）。guard新增5个synchronized包私有Lease方法，FD不越包，R02既有21项guard语义不变。
- 真实Python对拍：探针资源固定argv执行、路径经probe-config传递；Java发布closed marker被python _read_closed_admission接受且RECORD canonical字节一致，Python先闭门后Java幂等复核inode/字节不变，canonical 90字节实测相等。Java读closed采用canonical字节精确比较，严于Python语义校验，为有意差异并已记录。
- 探路失败如实保留于STAGE-RECOVERY-R01-probes：编译失败一轮（5处throws+1处类型）、awaitReady的readNBytes管道EOF死锁（600s预算终止）、close/verify探针模块级import先于sys.path.insert。红轮R01为84项51过/22失败/11错误：统一unavailable下正例真红，部分负例因assertThrows假绿，不以51过宣称负例验证。绿轮R02为82/82零skip（RecordTest 59+PreparationTest 23），unit0/8.742秒；期间修正测试自身4处缺陷（数组assertEquals引用比较、inventory相对路径前缀、探针输出前缀、两个合法形态误列禁止），实现侧修正字段集校验逻辑、JNA byte[]三参切片映射陷阱（read/write签名改数组+单int）与runtime-client坏条目改报unresolved。
- 全量R17：Java 1619项1616过/1失败/0错误/2环境skip，unit1/93.1秒；Python 142/142零skip。唯一失败仍为runtimeErrorIsRecordedAsSingleCaseRe（期望RE收到SYSTEM_ERROR/Judge executor rejected the task），TASK-18未修未屏蔽；两skip仍为旧镜像缺失与WSL非Windows。R17快照191份源码hash，与R02共享源码独立比对完全一致。
- 协议边界如实记录于stage-recovery.md：stage-journal事后替换不可检测（当前目录闭门、移走证据不触碰，专门测试）；枚举用路径+前后身份复核，marker/记录读写全部dirfd绑定；unresolved证据保留不修复；UNKNOWN仅置标志，权威服务端终结屏障仍未实现；极早期无stage-journal的attempt属早期创建冻结缺口。
- 仍待：早期创建入口冻结、权威UNKNOWN屏障、归属回收、删除凭据、跨进程删除冻结、活跃取消、JVM资产重建与真实supervisor集成。生产generation/权限/UID未自选默认，门禁关闭。全部实验单元终态collect；共享docker/containerd事后核验active/running、NRestarts0。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无提交/推送；Mimosa写入hook多次误报测试探针命令注入，最终以全字面量argv+资源文件探针形态通过，未绕过扫描。整体5/30、不可发布。

### 2026-10-04 — TASK-10 / STAGE-RECOVERY-R03～R04、全量R18 — host侧早期创建入口冻结

- 定位键TASK-10-C01，仍IN_PROGRESS。核对受管start落盘顺序（attempt目录→task-spec.json→runtime-state.lock→runtime-state.json→首次运行时调用）后确认：旧rejectManagedAttempts的5-marker列表覆盖不了早期崩溃残留（空attempt目录、仅task-spec.json、含runner-events.jsonl/runner-summary.json的部分文件），且仅task-spec.json残留与legacy manifest-only布局不可区分；该缺口使早期残留不被冻结、新start继续创建新attempt、孤儿目录让持久发现永远JOURNAL_MISSING不完整。
- 收紧为attempts目录下任何canonical受管attempt目录存在即拒绝新start（保留uuid与信任目录校验、任何运行时调用前失败、残留原样保留）。这有意反转既有legacy manifest-only放行契约（legacyManifestOnlyAttemptAllowsANewManagedAttempt改为同样要求先恢复），理由为形态不可区分下安全冻结优先；行为变更在验收文档与change-log明确记录，不含糊为兼容性保持。
- 红轮R03为Launcher 218项5失败（四残留形态+legacy反转，红轮中残留形态下start曾完整走到模拟运行时，放行证据真实）；实现后绿轮R04为Launcher+ContainerRunner+Discovery+RecoveryPreparation共310项全过/0错误/1既有环境skip。全量R18：Java 1623项1620过/1失败/0错误/2环境skip、Python 142/142零skip，唯一失败仍为TASK-18既有执行器拒绝，未修未屏蔽。
- 本冻结只关闭host侧start入口，与task锁+恢复消费者stage闭门组合阻断恢复期间新创建；不实现权威UNKNOWN服务端终结、不冻结跨进程删除、不覆盖createDirectories后无attempt目录的正常空窗口。生产generation/权限/UID仍待用户具体确认后才能进入权威屏障与归属回收。
- HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无提交/推送（TASK-10未全部验收）；全部实验单元终态collect。

### 2026-10-04 — TASK-10 / JVM-BUILD-R13～R15、JAVA-SUPERVISOR-R09、全量R19 — 真实域Java恢复准备集成

- 定位键TASK-10-C01，仍IN_PROGRESS。JVM-BUILD-R13首次重建含guard与全部新组件的Java资产；R14/R15轮重建因探针--prepare-only参数个数判断（5≠6）失败后修复（R15 manifest ee4fb7d5…、probe源3e175fb9…）；R14B命名不符合构建脚本两位数字约束，如实保留探行记录。
- JavaSupervisorProbe新增--prepare-only模式与主流程verifyFreshJvmPreparation：真实六阶段+两次完成后stop+新JVM发现之后，由独立新JVM（冻结R15包）执行runner.prepareRetainedStageRecovery两次；断言6条真实stage全CONFIRMED/CLEANED、unresolved空、closed marker为90字节canonical且inode不变、storage inventory精确差异仅closed marker。LinuxStageRecoveryPreparation按discovery先例public化并经LinuxContainerRunner公开入口暴露，主源码无行为变化。
- runtime_preflight.py的run_java_stage_admission_probe适配：初始open断言改为Java已发布canonical marker断言（90字节精确比对）+Python两次close走幂等复核路径且marker身份四处全等（javaPreparationMarker==AfterFirst==AfterSecond==AfterChild），独立Python 8角色/phase拒绝、stage根空、inventory不变全部保留；域内validator自测74+13+7通过。探路失败保留于JAVA-SUPERVISOR-R09-failed1（参数个数）与R09b（unit名含字母）。
- JAVA-SUPERVISOR-R09在既有专用真实域（SUPERVISOR-BUILD-R04镜像、专用daemon、Delegate、1792MiB tmpfs、9min30s）全程exit 0：真实start/poll/stop/discovery保持R08语义，新JVM恢复准备与Python↔Java双向互通（Java发布→Python幂等复核不重写）在真实supervisor产物上完成验收。
- 全量R19：Java 1623项1620过/1失败/0错误/2环境skip（唯一失败仍为TASK-18既有执行器拒绝，未修未屏蔽）、Python 142/142零skip。事后共享docker/containerd均active/running、无已加载wjc单元。仍缺权威UNKNOWN屏障、跨进程删除冻结、归属回收、删除凭据、活跃取消；屏障与回收需生产generation/权限/UID具体确认。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无提交/推送。

### 2026-10-06 — TASK-10 / CLEANUP-R03～R05、全量R20 — 无marker残留删除保护

- 定位键TASK-10-C01，仍IN_PROGRESS。先重新读取原始目标/技能/任务和当前代码；R19全量1623项及191份源码hash与开工工作树一致，上一目标轮判为实际进展，不重复实现已验收的Java恢复准备与R09真实域集成。HEAD保持5f672c2faa9d02e2e3cd95c832ef4623bc222301。
- 发现Launcher已拒绝任意attempt残留新start，但FileTaskStore仍只查runtime-marker且允许无startup锁的空attempt/manifest-only被删除；按失败关闭合同统一为control/attempts任何条目存在即拒绝删除。复用已有root生命周期锁，不新增锁体系、生产默认或删除凭据；普通无attempt/无startup锁任务继续可删。
- 明确兼容性收紧：legacy manifest-only由可删改为保留，可能增加存储占用；不能在缺少可信回收证明时以手工删attempt/lock恢复。不是完成资源回收，不用“无marker”推导无资源。
- CLEANUP-R03红53项45过/8失败/0错误/0skip，unit1/15.149秒；5本进程残留与3真实子JVM残留确实被旧实现误删。最小产品修复后R04关联386项381过/5失败/0错误/0skip，unit1/22.421秒；Store及跨进程新测试已绿，5失败来自CleanupService旧legacy删除契约，完整保留历史。
- R05更新明确合同并加强普通任务成功删除、legacy证据/状态保留及cleanupResidual成功不授权删除负例，七套件389/389零skip、unit0/20.493秒。全量R20为1635项1632过/1失败/0错误/2既有环境skip，Python142/142零skip，unit1/100.561秒；唯一TASK-18 RE→SYSTEM_ERROR/Judge executor rejected the task未修未屏蔽。R05/R20各191份源码hash独立匹配，红轮差异文件精确保存。
- 独立审查限定本轮4文件未发现阻断；仅读不运行测试。真实子JVM证明Store进程边界，mock runner仅证明服务编排，未外推真实容器回收。safeDeletePaths仍先全树遍历，不能宣称root锁硬时间上界或凭据约束的完整删除事务。原则：KISS移除不完备marker名单；DRY复用稳定准入；保留与恢复分责。
- 本轮命令开始曾因PowerShell逗号参数未加引号在解析阶段失败，没有启动实验；修正后均沿用4GiB/swap0/2CPU/512pids/600秒断网边界，串行运行。全部会话终态collect；事后docker PID325、containerd PID247均active/running、NRestarts0，无loaded wjc-task10单元，不复用10-04旧PID。
- 更新cleanup-protection、task/work/change-log、恢复设计和stage-recovery当前未完成项；新增10-06交接，旧10-04交接标历史，Goal文档接续指令切至最新状态，避免后续重复原生锁、Java闭门与R15/R09集成。原始Goal仍active，整体5/30、不可发布，未提交/未推送，IDE和工具目录未动。
- 下一步仍为授权专用域内的归属回收与UNKNOWN权威屏障实现，以及可信任务级删除凭据/集合重验、活跃取消。生产运行时代际范围、停止权限、数据根/控制配置属主和UID/GID必须具体确认，不能由“后续批准”自行选值；不因组件通过把TASK-10标DONE。

### 2026-10-06 — Goal与任务交接文档收尾

- 按用户“写一个goal以及任务的交接文档”请求，仅更新文档。定位键沿用TASK-10-C01，任务仍IN_PROGRESS；核对原始附件、Goal工具active状态、HEAD与task/work，HEAD仍为5f672c2faa9d02e2e3cd95c832ef4623bc222301，整体5/30、不具备发布条件。
- 更新docs/project-goal-2026-10-04.md的实际更新日期与可复制接续指令；补全docs/task-handoff-2026-10-06.md中的证据入口、既有代理/文件所有权、冻结点、CONFIRMED容器与卷连续性边界、未实施状态草案及下一步。修正无上下文的“第7节授权”引用，显式指向recovery-consumer-design.md。
- 既有stage_record_update代理完成最小安全编辑后冻结：LinuxStageJournalGuard新增仅抛IOException的replaceStageRecord占位API，新LinuxStageJournalUpdateTest落盘6例；LinuxStageEntryIo未被其修改。未编译或实跑红/绿，无活跃命令/后台任务，不把3例预期失败当实测结果。confirmed_recovery_seam只读完成，未新增实现/测试；本次没有新建代理。
- 读取CLEANUP-R05与R20原始汇总，仍分别为389/389零skip及1635项1632过/1失败/0错误/2skip；这些是历史测试，不是本次运行。R20的191源码hash匹配只适用于此前已验收快照，不覆盖随后stage占位API及新测试；同步task.md明确这一边界。
- KISS/DRY：沿用现有Goal文件与交接入口，不复制新的目标或重做已验收组件；准备、资源回收与删除授权分责，未将分析草案记为已实现。本次未改产品源码、未运行测试/容器/构建、未提交或推送，IDE与工具目录保持不动，原始Goal仍active。

### 2026-10-06 — TASK-10 / STAGE-UPDATE-R01～R03、R21/R22 — 原子stage写回

- 定位键TASK-10-C01，仍IN_PROGRESS。上一Goal轮分类为progress：交接文档/冻结API改变了权威状态。本轮重新读取原目标、技能、task/work与真实代码，HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，整体5/30；未把文档交付或组件成功缩成Goal完成标准。
- 预检发现继承的LinuxStageJournalUpdateTest字符串双引号实际丢失，先用直接参数apply_patch修正，再受限实跑R01，实际6项3过/0失败/3错误/0skip；3正例抛占位IOException，unit1/11.455秒。不是编译失败或预期假红，192源码清单/红绿精确差异完整保留。
- 复用既有代理和明确文件所有权：worker只改LinuxStageEntryIo/Guard及普通、故障测试；主代理另增互通测试，统一串行验收/证据/文档；confirmed_recovery_seam仅审5文件，不运行。未新增代理，不回退别人改动。
- 实现真实Lease原子替换：规范UUID.json及1..65536字节、clone、可信UID/0600普通单链接、原bytes/全身份重验、UUID tmp排他创建/fsync/write/重验/rename/final fsync/发布FD与bytes重读；允许合法rename ctime变化，单次close保留cause/suppressed，失败留tmp/主记录、不清理/回滚。
- R02普通33+故障64+实际Python互通18共115/115零skip，unit0/16.868秒；R21全量1750项1747过/1失败/0错误/2skip，Python142/142，unit1/105.514秒，194源码均匹配。独立只读审查无阻断，但提示post-rename确认丢失尚无直接用例。
- 仅FaultTest追加2例：先真正rename成功，再返回-1或抛EIO，确认API报失败而完整已发布记录/tmp消失/单次close/锁释放/异常原因均保持。R03现117/117零skip，unit0/19.060秒；R22全量1752项1749过/1失败/0错误/2skip，Python142/142零skip，unit1/114.450秒；同轮12关联套件586/586，非额外运行。
- R03/R22各194源码与最终工作区独立匹配；R02/R21在追加前已独立核验，R21→最终仅FaultTest不同，精确SHA差异另存。唯一全量RE→SYSTEM_ERROR / Judge executor rejected the task仍归TASK-18，未修/未屏蔽；两skip为旧Linux镜像缺失及WSL非Windows，非平台验收。
- 实验均4GiB/swap0/2CPU/512pids/600秒、断网、只读repo、2GiB tmpfs、仅证据可写，5个测试会话全部终态collect。开工WSL为Stopped，启动后服务曾314/230；11:25:28只读观测docker317/containerd231均active/running、NRestarts0，无loaded wjc-task10单元。PID/计数不证明跨WSL生命周期未重启，不沿用旧PID授权；没有执行共享daemon stop/restart。
- KISS/DRY复用同目录guard、native Calls与procfs身份，不新增锁/依赖；存储I/O与业务转换/归属回收分责。此原语不要求闭门或证明JSON转换；非合作root TOCTOU、内核真实EIO/阻塞、SIGKILL/断电及资源回收尚未证明。R15旧包不含本轮新代码，真实集成前须重建，未启生产provider、未提交/推送，IDE/工具目录未动。
- 新增stage-update及独立review，更新task/work/change-log、恢复设计/stage-recovery和Goal/交接到实际进展。下一步实际CONFIRMED消费者/完整ID归属与typed absence/managed枚举/持久写序，卷未证明连续性时保留；继续可信域/UNKNOWN/删除凭据/集合重验/活跃取消，生产具体generation/权限/UID仍待明确。原Goal保持active，不DONE、不关DEF-05/N2。
- 文档合并补丁首次因Windows参数长度限制ENAMETOOLONG在spawn前失败，未执行、未改文件；随后改用逐文件小型apply_patch补丁全部成功，不绕过权限或覆写证据。

### 2026-10-06 — Goal与任务交接文档核验（R22后）

- 按用户“写一个goal以及任务的交接文档”请求，仅整理文档。定位键沿用TASK-10-C01，任务仍IN_PROGRESS；原始Goal仍active，未重复创建、暂停或标记完成。HEAD核验为5f672c2faa9d02e2e3cd95c832ef4623bc222301，任务表仍5/30，DEF-05/N2未关闭，不具备发布条件。
- 更新docs/project-goal-2026-10-04.md：将完整TASK-00～29、31缺陷目标明确为新线程可复用的Goal内容，保留原始范围和完成门槛；接续指令引用当前交接，避免复制多份状态。更新docs/task-handoff-2026-10-06.md：区分最新开发轮与本次文档交付，新增HEAD/status、证据/源码、在途状态、CONFIRMED回收及新证据目录的开工检查清单。
- 只读核对此前原始汇总：STAGE-UPDATE-R03为117/117零skip；R22 Java1752项1749过/1失败/0错误/2skip，Python142/142零skip。独立重新计算R03/R22各194份源码SHA256，均无差异；唯一全量失败仍为TASK-18的RE→SYSTEM_ERROR执行器拒绝，未修或屏蔽。这些不是本次新运行的业务测试。
- 两份目标/交接文档静态校验通过：严格UTF-8，无NUL/替换字符或尾随空白，代码围栏闭合，14个直接本地引用存在；git diff --check通过。既有两子代理均completed，本次未创建或启动代理。
- KISS/DRY：沿用既有Goal文件和唯一当前交接入口，不新增平行目标或重复开发已验收组件；目标、事实交接和追加日志分责。仅上述两文档及本条work记录属于本次修改；未改产品源码、未执行Maven/Python业务测试、构建、WSL/Docker命令、Git暂存/提交或推送，保留IDE用户改动，不读取或清理禁止访问的工具目录。

### 2026-10-06 — TASK-10 / CONFIRMED回收消费者开工

- 定位键TASK-10-C01，仍IN_PROGRESS；上一Goal轮完成Goal/交接与事实核验，分类为progress，但完整30任务、31缺陷目标未达到。本轮重新读取原始附件、工程/后端技能、任务卡和当前代码，HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，整体5/30。
- 实施范围：严格CONFIRMED、无UNKNOWN/未决create的完整ID容器回收；原子stage写回、runtime持久意图、stage/supervisor/holder归属及有界观察，卷保持CREATED/CLEANUP_PENDING，不授予文件删除凭据，不开启生产provider。不从未明确的生产generation/UID/GID/拓扑推导默认值，不停止共享daemon。
- 按原目标允许的独立并行工作复用两既有代理：stage_record_update仅负责runtime分类器及其测试，confirmed_recovery_seam仅负责新stage归属检查器/测试及真实形状资源；主代理负责消费者、Launcher/Runner接入、关联夹具、串行验收与文档。所有权分开，不回退他人修改，不并行构建/Maven/真实域。
- 先落盘两项实际回收红测及fail-closed占位API，修正夹具方法注解后再准备实跑，未把编译错误或预期失败当红测结果。classifier追加六个保留卷的容器回收快照，尚未实跑；stage verifier正在独立实现，不把源码完成当验收。
- WSL只读列表为Stopped，获准启动后2026-10-06T12:00:56+08:00观测docker PID315/containerd228、active/running、NRestarts0，无loaded wjc-task10单元；未执行共享服务stop/restart，观测不证明跨WSL生命周期身份连续。后续测试沿用4GiB/swap0/2CPU/512pids/600秒、断网、只读repo和2GiB tmpfs预算，选新证据目录。

### 2026-10-06 — Goal与任务交接文档核验（R24后）

- 按用户“写一个goal以及任务的交接文档”请求，沿用既有Goal文件及当前10-06交接入口，不重复创建Goal。完整TASK-00～29/31缺陷目标保持active；HEAD为5f672c2faa9d02e2e3cd95c832ef4623bc222301，整体5/30，TASK-10-C01仍IN_PROGRESS、未提交，DEF-05/N2未关闭、不可发布。
- Goal接续指令改为消费者/归属/默认FS门禁已经实现、下一步文档收尾及新冻结包/新JVM真实Docker验收；交接记录实际合同、19快照、持久意图写序、卷与控制证据保留、同JVM有状态模拟CLI的证明限度，不重复实现已验收组件。
- 只读核对此前开发轮JSON：CONFIRMED-RECOVERY-R01为2项0过/1失败/1错误，R02为1446项1444过/1失败/1错误，R03为1550/1550；真实ZIP FS三入口旧代码R04为3/3失败，修复R05为1553/1553零skip。R24 Java2359项2356过/1失败/0错误/2skip，Python142/142；唯一失败仍是TASK-18的RE→SYSTEM_ERROR执行器拒绝，两个skip为旧Linux镜像不存在及WSL非Windows，未修或屏蔽。这些不是本次新运行的业务测试。
- 独立重算R05/R24各198份源码SHA256均匹配当前工作区，包含FS修复；在各轮新增source-hash-verification.json，不覆盖旧证据。旧R03/R23等冻结快照不能覆盖最终FS新代码。独立审查MAJOR及修复/复核已列入交接，审查人未实跑。
- 原只读事后观测session34125沿同handle收取终态，退出0，输出2026-10-06T12:38:53+08:00：docker MainPID348、NRestarts0、activating/start；containerd246、NRestarts0、active/running。未启动新WSL/Docker命令，不宣称Docker已就绪或跨WSL生命周期连续，不停止/重启共享daemon；已有两代理completed，本次未创建或触发代理。
- 本次只改Goal/交接、追加本记录及两份verification；产品源码、IDE用户改动与Git index保持不动，未运行测试/构建/真实容器回收，未暂存/提交/推送。task/change-log、消费者验收草稿/设计/stage-recovery及新独立review仍须收尾，详见交接第10节。KISS/DRY沿用唯一入口，目标、事实交接与追加日志分责；文档交付不构成TASK-10闭环，当前请求结束后不在本轮继续产品开发。
- 本次静态校验通过：5份交付文件严格UTF-8、无NUL/替换字符/尾随空白、代码围栏闭合，14个直接本地引用存在；git diff --check退出0。198份源码及两份IDE文件/index哈希保持不变，work历史前缀完整保留；Git的既有LF→CRLF提示未触发配置或批量换行修改。

### 2026-10-06 — TASK-10 / CONFIRMED消费者开发轮收尾与真实域开工

- 定位键TASK-10-C01，保持IN_PROGRESS/未提交/5/30。上一Goal轮分类progress：Goal/交接及R05/R24最终198源码核验证据改变了接续动作；本轮重新读取原目标、HEAD/status、任务卡及实验授权，HEAD/index未变，保留IDE与工具目录。
- 同步此前开发事实到task/change-log、confirmed-container-recovery及新独立review，追加恢复设计§18/stage-recovery§8。消费者、stage归属、19快照、路径FS保护已实际实现；R01/R02/R04失败史和R03/R05/R23/R24保留，最新R05为1553/1553，R24 Java2359项2356过/1失败/2skip，Python142/142。这不是本轮新实跑。
- 开始扩展真实实验探针、重建冻结资产及新JVM/无handle验收，仍只操作本轮私有域新资源；卷/控制证据保留，不给删除凭据。原始TASK-10全验收、UNKNOWN/卷/删除/取消仍未完成，生产门禁关闭，不停止共享daemon。
- WSL首次只读列表被sandbox拒绝后按规则申请escalation；实际Ubuntu为Stopped，经批准启动后12:53:38+08:00只读观测docker314/containerd229均active/running、NRestarts0，无loaded wjc-task10单元。session16332已终态收取，未重启旧handle，不推断跨WSL身份连续。

### 2026-10-06 — TASK-10 / 语义与Docker29参数红绿、冻结资产及真实失败

- 主代理对照Python实际协议发现旧Java仅移除容器却发布CLEANED并改写NOT_REQUESTED，不能证明阶段目录已清理。R06两真实stage记录红测2/2失败；修复为CONFIRMED只到REMOVED、原CLEANED保持、NOT_REQUESTED原bytes/状态不变，不清理或授权卷/任务删除。R07八套件1555/1555；R25 Java2361项2358过/1失败/0错误/2skip，Python142/142，最终各198源码独立匹配，失败未屏蔽。
- JVM-BUILD-R16编译2/2、unit0，195产品输入/306资产及manifest/归档独立核验。JAVA-SUPERVISOR-R10是真实私有域/新JVM：正常六stage/AC1与准备通过后，回收子进程1745在stop纯ID校验上失败。最后命令实际exit0/stderr0/stdout120字节，STOP_REQUESTED证据保留；原R10没有保存stdout正文，不编造正文。私有daemon最终exit0、该unit退出1；12冻结源副本独立匹配，不外推当前脚本。
- 只读CLI help实际Docker29.1.3（session92666终态0）输出--time弃用警告，与120字节现象匹配；采用canonical -t 0修复新消费者两处与既有stop，不剥离警告或放宽ID/观察门禁。R08两入口红2/2失败；R09八套件1557/1557，R26 Java2363项2360过/1失败/0错误/2skip，Python142/142，各198源码匹配。唯一失败仍TASK-18 RE→SYSTEM_ERROR执行器拒绝，两个skip仍旧Linux镜像缺失/WSL非Windows。
- 新探针以真实LinuxSupervisorCommandExecutor记录完整argv及mutation原始stdout/stderr，独立验证交错stop/rm顺序；新增五个验证器方法，计数由74变79。完成后用全新JVM再重试，不能以同JVMRecordingRuntime代替真实资源回收。Python实际stage持flock覆盖整个执行，故专用故障fixture将SIGKILL新监督器，证明活的CONFIRMED stage成为真实孤儿后再回收，不宣称活跃取消已验收，不人工改JSON或晋升UNKNOWN。
- JVM-BUILD-R17编译2/2、unit0，195源/306资产独立匹配。manifest=2085d90611b042d99dc084319b69433425ce7b27fc10a4f6fe6805e84fdf10a9，archive=96d913ca1ac36a15374b9d071710eb3a686a183ea01e30d98ac1d7eaf0f9f42a、68587520字节；Probe=8af0d5072816b4bd884bdffa25112adb96fa945586903e410863dcd23b848f4c。R16/旧R15不能覆盖-t/原始回显新代码，不覆盖旧资产或日志。
- 本轮按获批预算串行Maven/构建/真实域：4GiB/swap0/2CPU/512pids、570+30秒或600秒；真实域根tmpfs1792MiB、最多3容器各64MiB shm与两个32MiB卷，总2GiB不提高预算，断网/NNP/只读repo。R06=94990、R07=57289、R25=98922、R16=5039、R10=9886、R08=27511、R09=97205、R26=9934、R17=47570均终态；新JAVA-SUPERVISOR-R11在同获批私有域执行，session51926仍在途，待同handle收取，不因超时重启。
- 不创建代理、不改IDE、Git index/HEAD、全局配置或工具目录，不停止共享Docker/containerd，未暂存/提交/推送。完整Task10/卷连续性/UNKNOWN/部分创建/删除凭据/活跃取消仍未验收，5/30、DEF-05/N2未关闭、生产门禁关闭，原Goal保持active。


### 2026-10-06 — Goal/任务交接整理与R11终态补录

- 定位键TASK-10-C01；本次为用户要求的Goal/交接文档交付，不是新开发任务验收。重新读取原目标、现有active Goal、HEAD/status/index、任务卡、日志及历史证据；HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，index无暂存路径，5/30、TASK-10 IN_PROGRESS/未提交、DEF-05/N2未关闭、生产门禁关闭。没有重复创建或改变Goal状态。
- 补录上一条记录中的JAVA-SUPERVISOR-R11“在途”终态，保留原记录不覆写：既有session51926已结束，unit退出0；post-observations.json中同单元monotonic端点269908641→378857654微秒，重新计算108.949013秒，不用Realtime差或Maven耗时替代。最后steps保留私有daemon退出0，不能据此推导共享Docker状态或卷权威回收。
- 重新读取四场景observation/commands：正常六条CONFIRMED由新JVM1744回收，4次stop/rm；JVM2542重试零mutation。监督器SIGKILL后的五条CONFIRMED孤儿现场由JVM4220回收，6次stop/rm；JVM5072重试零mutation。十次mutation均核对exit0、stderr空、stdout为完整64hex ID加换行65字节；四个JVM均无handle/无本进程start，且不宣称productRecoveryComplete、volumeContinuity、删除凭据或生产验收。来源为既有JAVA-SUPERVISOR-R11/steps.jsonl、java下四场景及post-observations.json，未重新运行实验。
- 重新核对CONFIRMED-RECOVERY-R09为1557/1557零skip；R26 Java2363项2360过/1失败/0错误/2skip、Python142/142。独立重算两轮各198项source-hashes.sha256均与当前源码匹配。唯一TASK-18执行器拒绝及两个环境skip保留，不称全量通过；195构建输入/198测试清单/12实验副本仍为不同范围。
- 更新docs/project-goal-2026-10-04.md与docs/task-handoff-2026-10-06.md，复用原入口，补充当前检查点与明确接续顺序。发现旧交接“全部开发记录已同步”不符合磁盘现状，已修正：change-log仍停留R05/R24，恢复设计第18节及stage-recovery第8节仍需追加R17/R11限定结果；新语义/CLI独立复核仍缺。先收尾这些记录，再接续同一失败现场的新JVM恢复，不回到旧“消费者未实现/R15”起点。
- 本次仅改上述Goal/交接及追加work；不启动Maven/Python回归、构建、WSL/Docker或代理，不操作共享daemon，不改产品源码、IDE、Git index/全局配置或禁止工具目录。无暂存/提交/推送，不填新SHA；文档交付后结束本轮工作，不自动启动下一切片。
- KISS/DRY复用唯一Goal与交接入口；目标、接续快照与追加事实日志分责，保留失败和“在途”历史。本次审查来源仅为主代理证据核对，不伪称独立新代码复核；文档完整不满足TASK-10关闭或项目发布门槛。
- 本次最终静态检查：3份交付文档严格UTF-8，无NUL/替换字符/尾随空白，代码围栏闭合，12个直接本地引用存在；git diff --check退出0。R09/R26各198项源码、两份IDE文件和Git index保持原值，work历史字节前缀完整保留。Git既有LF→CRLF提示未导致配置或批量换行修改；这些检查不构成新产品回归或完整开发轮验收。


### 2026-10-06 — TASK-10 / 同现场失败恢复开工与历史收尾

- 定位键TASK-10-C01，原Goal保持active。上一Goal轮分类progress：交接修正了磁盘记录缺口，源码核验和R11原始回显复核改变了接续动作。本轮重读原目标、HEAD/index/status、需求/设计/任务/评审及后端技能；HEAD仍5f672c2，index空，保留IDE与工具目录，无新代理。
- 先追加change-log、恢复设计第19节与stage-recovery第9节，纠正R05/R24/R15时期的旧接续点并保留历史；R11限定成功与剩余证据范围已同步。新语义/CLI独立复核仍缺。
- 接下来实际开发新私有域故障验收：真实Docker CLI访问私有不存在socket产生连接失败，真实rm成功后由受控实验executor丢失响应，新JVM在同attempt/同现场恢复；分别验证supervisor与stage状态。只扩展验收Probe/验证器，不放宽产品断言，不把响应丢失注入写成真实daemon故障。此为开工范围，不预填测试成功。
- 沿用4GiB/swap0/2CPU/512pids/600秒、2GiB私有存储及570+30秒构建/真实域预算，串行执行；只操作新私有资源，不停止共享daemon、不臆造生产参数。整体5/30、TASK-10 IN_PROGRESS、未提交，生产门禁关闭。


### 2026-10-06 — TASK-10 / 同现场失败与响应丢失真实恢复终态

- 定位键TASK-10-C01，原Goal保持active、5/30、IN_PROGRESS/未提交，DEF-05/N2未关闭、生产门禁关闭。只扩展JavaSupervisorProbe、runtime_preflight/九项验证器和有界验证脚本；产品源未改，无HTTP/schema/数据库迁移。未创建/触发代理，所有新变化由主代理明确自审，新独立复核仍缺。
- VALIDATOR-FAULT-R01：88方法、0fail/25error/0skip、unit1/0.550186秒；九个新方法子例缺验证函数，不按88-25伪算方法通过数。R02 loader88/88、unit0/0.403440秒，但未覆盖脚本main计数。R12真实预检88项全过，却被旧严格==79 main门禁拒绝，unit1/13.122432秒、未启动私有daemon；12冻结副本完整保留，与当前计数修复后一文件不同。修为严格==88并增强验证脚本执行真实入口，不削弱断言；R03 loader/入口88/88、零skip、unit0/0.569187秒。
- JVM-BUILD-R18离线编译2/2、unit0/42.127757秒；195输入/306资产/manifest/archive/Probe独立匹配，保存source-hash-verification。manifest=c96576a7ffbbab8c2bc3ce94ff7d5ef963101f810a9c82ffa04804bdcb6ac87a，archive=176eb83277a76f0b0eed5e09d03923d24dd6a20d6010e0c5528a00d06413cb54、68597760字节；Probe=4fc63b3d425740c183446a8777302dc89da1e055a26b337453d544ec0d98c67c。不是产品全验收，旧R17不能覆盖新Probe。
- JAVA-SUPERVISOR-R13实际unit0/103.318973秒：PID1742真实CLI连接私有不存在socket，exit1/stdout空/stderr222字节，独立容器前后相同、STOP_REQUESTED保留；2161真实supervisor rm后由实验executor丢响应，REMOVAL_REQUESTED及目标实际缺失；2697同现场完成、3407零mutation。producer3893正常退出后只对已核验新私有supervisor注入SIGKILL，live stage仍在；5101真实stage stop/rm后丢回应，REMOVING/null outcome保留，5669同现场完成、6426零mutation。
- 主代理独立重核三条失败after→下一before完整runtime/stages JSON相等、七恢复PID互异、11次成功mutation纯65字节完整ID+换行/exit0/stderr空，另一次实际CLI连接错误；88+13+7辅助过，R13的12冻结副本/当前源全匹配。正常原CLEANED和所有creationState/ID/outcome不改，stage最终只到REMOVED；两卷/控制证据保留CLEANUP_PENDING、无删除凭据。不是实际daemon故障、所有步骤失败或活跃取消/卷/UNKNOWN/完整删除。
- R18 session22387、R12 session91614、R13 session24535均沿原handle收取真实终态，没有观察超时后重启。透明R13/collect-observations.py和post-observations保存原始双时钟端点，时长用同单元monotonic差，不替换为Realtime/Maven时间。14:58:53+08:00只读docker314/containerd233均active/running、NRestarts0、无loaded TASK-10单元，不推断跨WSL连续性；私有域退出0不是产品卷清理，未停止/重启共享服务。
- 沿用4GiB/swap0/2CPU/512pids、570+30秒预算，真实域1792MiB根tmpfs+最多三容器64MiB shm+两32MiB卷仍为2GiB，串行执行。同步task/work/change-log、消费者第9节/review、恢复设计第20节/stage第10节、Java集成/build/index与Goal/交接第12节，历史不覆写。KISS/DRY复用真实executor、持久消费者/锁与既有验证器，不加产品默认或业务转换。
- 下一步其他CLI步骤失败/成功回显无效果、无记录/归属不符/tmp、卷连续性/可信域终结、UNKNOWN/部分创建、可信删除事务/集合重验、活跃取消和独立复核。R26既有TASK-18失败/两skip未修未屏蔽。未改IDE/index/全局配置或禁止目录，未暂存/提交/推送，完整Goal不缩减为三例或文档成功。


- 本轮最终静态核验：12份Markdown严格UTF-8，无NUL/替换字符/尾随空白，代码围栏闭合；13个最新直接证据入口存在。R09/R26各198产品/测试源码仍匹配，两份IDE文件与Git index哈希不变，旧work字节前缀完整保留。git diff --check退出0、HEAD仍5f672c2、暂存路径0；既有LF→CRLF提示不导致配置或批量换行修改。这里只是本轮文档/保护检查，非新全量产品回归或完整TASK-10验收。


### 2026-10-06 — TASK-10 / 活跃取消控制路径开工

- 定位键TASK-10-C01，原Goal仍active/5/30/未提交；重读原目标与当前HEAD/index/实际代码，上一轮分类progress，R13失败恢复不重复。优先处理代码中更直接的取消缺口，其他CLI/tmp负例仍保留待办：Runner.cancel目前只调用即时stop，Python执行期持stage flock，可信cancel.requested从未由此入口发布；R13证明杀owner后活stage仍可存活。
- 本轮先在新私有域由同一真实JVM start/cancel无限执行的USER stage，核验实际flock/容器状态、保留红测；再复用现有runtime journal私有文件/锁发布空取消标记，用已有commandTimeout作合作退出观察预算，不在Python仍持锁时获取stage flock。若超时/未知则保留恢复边界，不伪称资源全清。
- 不预填成功；仅实验配置UID0/65532，既有4GiB/swap0/2CPU/512pids/570+30秒/2GiB临时存储预算，仍两卷、不增加第三fixture、不停止共享daemon。不创建代理、不猜生产参数、不提前放行TASK-10或DEF-05/N2。


### 2026-10-06 — TASK-10 / Goal与任务交接文档更新（文档轮）

- 定位键仍TASK-10-C01，本条只是当前任务的交接记录，不新增任务完成或提交定位键。按用户“写一个goal以及任务的交接文档”请求，仅更新文档，不续跑开发、构建或真实域。原Goal已有active状态，未重复创建、暂停或complete；当前5/30、TASK-10 IN_PROGRESS/未提交、DEF-05/N2未关闭、生产门禁关闭。
- 只读重核原始目标、适用指令/技能、需求设计和评审输入、任务/现有交接、Git HEAD/log/status/index及当前取消代码入口。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无暂存路径；保护两IDE文件及旧work历史，不读取或清理禁止目录，不创建/触发代理，不修改全局配置。
- 更新docs/project-goal-2026-10-04.md第6/9节为可复制完整Goal与当前检查点，docs/task-handoff-2026-10-06.md第13节为权威接续断点；同步docs/task.md的当前接续条目，本条追加docs/work.md。复用已有入口而非创建重复日期旁支，旧R18/R13切片标作历史，保留原始失败与skip；不修改产品、Probe或Python harness。
- 实际核对VALIDATOR-FAULT-R04/summary.json与两入口日志：各93方法、2失败/8错误/0skip、退出1；不计算虚假的通过方法数。红测后当前runtime_preflight.py摘要d24838c577605b28b19abe0590cc81eaaad0e03e258f33038d84faf6834717aa已不同于R04冻结603c81a66ba6f0d8dbff95250036f41434049e2b7f6e116d58b262629af59e12，尚未跑绿；test摘要68443762fc9f4ff90ccc9b1cf664581ecca60a94fed373e84607a254c84f70b5仍匹配R04，Probe摘要4fc63b3d425740c183446a8777302dc89da1e055a26b337453d544ec0d98c67c仍为R18版本且没有--cancel-active-only。未声明新93/93、新构建、真实活跃取消或新全量通过。
- 交接写明同JVM真实Runner.start/cancel、取消前实际flock/运行态证明、旧产品真实红测→可信marker/合作退出最小修复→新冻结包真实绿测的顺序；Journal/Launcher新方法均标作未实施。保持原pending异常、不同JVM后续容器回收/卷保留边界，不将SIGKILL孤儿恢复或手写marker算活跃取消。
- R26历史唯一TASK-18失败与两skip保留，其他CLI/归属/tmp、卷连续性/可信域终结、UNKNOWN/部分创建、可信删除事务/集合重验及新独立复核仍缺。预算仍4GiB/swap0/2CPU/512pids、测试600秒、构建/真实域570+30秒；真实域1792MiB根tmpfs+最多三容器64MiB shm+两32MiB卷，不增第三fixture。本次未新查WSL服务，旧14:58:53观察不当当前连续性证明。
- KISS/DRY：沿用原Goal与交接单一入口，指向真实证据和既有有界脚本，不复制新的实现或预算。职责清晰：区分已验收历史、当前红测、未实施方案和完整任务余项。全部编辑通过apply_patch；文档静态核验另据实际结果追加。没有暂存、提交或推送，也无新SHA或任务DONE。
- 本次最终静态核验：4份文档严格UTF-8、无NUL/替换字符/尾随空白、代码围栏闭合；15个直接输入/证据入口存在。两IDE文件及Git index、3份当前harness/Probe摘要不变，旧work的148193字节历史前缀完整保留，task完成索引仍恰好5项。git diff --check退出0，HEAD仍5f672c2，无暂存路径；仅有既有LF→CRLF提示，未调整配置或批量换行。没有运行新产品测试、构建或真实域；不是93项绿测、全量通过、TASK-10验收或新提交。

### 2026-10-06 — TASK-10 / 接续真实活跃取消红绿链

- 前一Goal轮分类progress：实际更新Goal/交接/task/work并核对当前红测与源码摘要，不是取消验收。本轮重读原始附件、技能与当前任务/交接及实际代码，继续完整active Goal，不缩减为文档或单一切片；仍5/30、TASK-10-C01 IN_PROGRESS/未提交、生产门禁关闭。
- 新只读WSL查询16:09:49+08:00：docker314/containerd233 active/running、NRestarts0，无列出的loaded TASK-10单元；只证明当次状态，不证明跨WSL连续性。首次bash -lc库存命令因Windows引号解析返回1且未执行服务操作，改用直接只读systemctl查询返回0；不停止共享服务。
- 先复用真实无限USER fixture补Java --cancel-active-only：同JVM保留runner/handle，实际锁竞争、marker/目录/容器/合作退出证据；随后有界预检双入口、旧产品新冻结包和真实红测。此时产品代码尚未修复，不预填绿测或真实取消成功。串行、原预算、不创建代理、不暂存/提交/推送。
- VALIDATOR-FAULT-R05已实跑且终态退出1：loader/真实main各93方法、1失败/0错误/0skip；唯一失败为outer-java-supervisor-cancel-extra未被configure_mode立即拒绝，完整summary/两入口log/source-hashes保留。不猜基础设施问题，不从服务内存峰值推断测试未执行。改为凡包含取消模式token均先严格匹配四phase白名单，再按原模式转换；不改测试或严格93门禁。
- Java取消Probe已写入，复用原无限USER fixture和同JVMrunner/handle，真实Python非阻塞flock子进程、独立容器查询、私有卷目录前后、marker属性及产品stop前owner inspect；旧产品未修。一次apply_patch边界重叠失败且未改Probe，修正完整方法边界后成功写入；尚未编译/真实验收。
- R06 loader与真实main均93/93、零skip、unit0；R19离线编译2/2、unit0，195源/306资产、manifest/archive/Probe独立核验，产品sourceSnapshot仍与R18相同。manifest=e53e528130b39b727eec71721e472c2e08ee5550bf6176777a021a8298750509，archive=b20085b24f71e91d81baa0e55bdab97a737e21a50ed9b1a92b0498d629ce7cf6、68608000字节，Probe=4cfbe6105f7a118ca396faddcdb15850e39bf209dc65ad44c6e801fec98a5762。
- JAVA-SUPERVISOR-R14已终态unit1，正常入口exit0，但在旧completed-stop-unreachable严格完整容器快照前后相等断言失败；未到达active fixture，不能冒称旧产品活跃取消红测已证明。真实不可达CLIexit1/stdout空/stderr222字节，STOP_REQUESTED保留；12冻结源独立核验。原harness在断言后才写独立前后，故此轮缺差异原始快照，不能猜是哪一字段或容器变化。
- 采集R04/R05/R06/R19/R14原始systemd双时钟与boot IDs：monotonic分别3.083494/0.586971/0.668602/40.479636/82.542951秒，端点保存R14/post-observations.json，不用Realtime或工具Service runtime替换。16:27:51+08:00当次共享314/233 active/running、NRestarts0，无loaded TASK-10单元，仍不证明跨WSL连续性。R19 session46996与R14 session54755均沿真实handle收取终态，未超时重启。
- 为保留严格失败证据，把四份独立容器/卷原始快照写入移至断言之前，未放宽或禁用旧断言。新增一项快照保留回归，main严格93→94；当前尚未跑新94项，也尚未取得活跃取消产品红测，不以R14失败替代其目标。
- R07已终态unit0，loader/main各94/94零skip；R15已终态unit1，monotonic108.029503秒，辅助94+13+7通过。此次已真实达到旧产品活跃取消红测，不是R14失败替代：JVM3901同JVM实际Runner.start/cancel，Python4946的LOCK_EX|LOCK_NB确实受拒；完整stage d3cb51fa063a8cf6733672fd7e829249660cda5f8b1562b6bf478b0ae0d83d26前后Running=true/Pid4910。
- 主代理另外核验R15所有证据：唯一mutation为supervisor 3c9f924c35b351d08b86ca60451b7d2062d8b254d35da67b80b0156eb0b02088的真实stop -t 0，exit0/完整ID+换行65字节/stderr空；产品stop前owner仍Running。runtime CLEANUP_PENDING/supervisor STOPPED，live stage仍STARTING，无可信cancel.requested/CANCELLED，目录未删，原pending消息正确。12冻结源匹配，保存source-hash-verification；证明当前取消缺口而非取消成功，不称新独立review。
- R15旧不可达CLI和supervisor rm丢回应的同现场恢复/零mutation重试已严格通过，原始四份前后快照均保存。R14漂移具体原因仍未定位，原失败保留，不把本轮通过写成其根因修复。R15 session83405沿真实handle收取终态；透明collector保存R07/R15原始端点与boot IDs，monotonic0.720233/108.029503秒。16:40:33+08:00共享314/233正常、NRestarts0、无loaded TASK-10单元，只是当次观察，不证明跨WSL连续性。
- 产品源码本轮未改。新增查得LinuxAttemptDiscovery.ATTEMPT_FILES当前不接受cancel.requested；下一产品修复需合法空/0600/单链接/writer marker的发现及非法拒绝，不仅放宽白名单。按原Journal/Launcher/Runner方案补安全/幂等/锁/超时测试，随后新定向/全量、新冻结包和新私有域绿测；当前旧产品R19不可作为新产品证明。
- 同步active-cancellation.md、task/work/change-log、Goal第10节与交接第14节；上一第9/13节保留历史。原Goal active、5/30、TASK-10未提交、DEF-05/N2未关闭、生产门禁关闭。未运行新全量，R26 TASK-18失败/两skip、其他卷/UNKNOWN/删除/完整预算/取消与独立复核余项不变；无暂存/提交/推送，不缩小完整30任务目标。
- 本轮最终静态核验：8份Markdown严格UTF-8、无NUL/替换字符/尾随空白、围栏闭合；当前94项预检源与R07摘要完全匹配，195构建输入仍与R19旧产品匹配。两IDE及Git index摘要不变，151700字节旧work前缀完整保留，完成索引仍5项。git diff --check退出0，HEAD仍5f672c2、无暂存路径；既有LF→CRLF警告未引发配置/批量换行修改。此为静态保护核验，不是新全量、产品绿测或完整TASK-10验收。

### 2026-10-06 — TASK-10 / 可信取消标记与合作退出产品修复开工

- 前一Goal轮分类progress：新Probe/严格94项预检/旧产品R19冻结包和R15实际同JVM取消红测已落地并核验，不能重复造红。重读原目标、当前Git/任务/交接和实际产品/测试契约；原Goal active、5/30、TASK-10-C01未提交，生产门禁关闭。
- 本轮先补Journal marker安全/幂等、Launcher合作退出/锁/超时、Runner pending、Discovery严格合法marker回归；以明示旧行为/未实现入口取得新契约红测，然后最小修复。复用原身份核验、锁序、文件强制与commandTimeout，不在活stage持锁期间取得stage锁，不自动回收卷或发删除凭据。
- 采用既有4GiB/swap0/2CPU/512pids/有界离线测试与新证据目录；串行、不创建代理、不停止共享服务、不猜生产UID/generation，不提交未验收TASK-10。R14快照漂移、R26 TASK-18失败/两skip和完整Goal余项不变。

### 2026-10-06 — TASK-10 / 契约红测准备断点的Goal与任务交接（文档轮）

- 按用户“写一个goal以及任务的交接文档”请求，仅更新持久文档，不继续修产品、测试、构建或WSL实验。已有Goal保持active，未重复创建/暂停/complete；完整30任务/31缺陷范围不变，仍5/30、TASK-10-C01 IN_PROGRESS/未提交、DEF-05/N2未关闭、生产门禁关闭。
- 只读核对原附件、适用技能、Goal/交接、任务表与最新work、需求设计和评审输入、HEAD/log/status/index及当前取消源码。HEAD仍5f672c2faa9d02e2e3cd95c832ef4623bc222301，无暂存路径；记录7份产品/测试源码和两IDE/index摘要作保护基线，核查17个直接输入/证据入口存在。未读取或清理禁止目录，未创建/触发代理或修改全局配置。
- 实际断点：Journal.requestCancellation直接抛未实现IOException，Launcher.cancelAndStop仅转调旧stop；三套件新取消契约测试已写入但未编译/运行，Runner与Discovery尚未接入。Launcher mock在marker存在时无条件断言STOP_REQUESTED，第二次cancel可能是STOPPED；上次修mock/建run-cancellation-contracts.sh的整段调用因模板插值解析失败而均未执行，本次核查脚本仍不存在。未替用户继续修mock或创建实验脚本。
- 更新docs/project-goal-2026-10-04.md第6/11节、docs/task-handoff-2026-10-06.md第15节为最新接续点，原第10/14节改为历史入口；同步docs/task.md当前接续文字，本条追加docs/work.md。写明第一动作是修mock/真正创建有界离线脚本取得契约红测，再最小产品修复、新定向/全量、新冻结包及新真实域绿测；候选轮次不是执行结果。
- R07/R19/R15仅为各自冻结的历史结果，当前产品新增占位后不能再称与R19源码一致。保留R14漂移未定位、R26 Java2360过/1失败/2环境skip与Python142/142，TASK-18失败未修未屏蔽；完整卷/UNKNOWN/删除/预算/取消/独立复核余项不缩减。无新测试或新WSL服务/连续性结果。
- KISS/DRY：沿用原Goal及交接单一入口，不创建重复目标或日期旁支；明确区分历史验收、当前占位、未实跑测试和待执行方案。预算不变，不猜生产参数、不停共享daemon，不赋予删除权限。所有文档编辑通过真实apply_patch；没有暂存、提交、推送或新任务DONE，静态核验按实际结果追加。
- 本次最终静态核验：4份Markdown严格UTF-8、无NUL/替换字符/尾随空白、代码围栏闭合；19个直接输入/证据入口存在。7份取消相关产品/测试源码、两IDE文件及Git index摘要不变；158773字节旧work历史前缀完整保留，task完成数仍5。限定四份文档的git diff --check退出0，HEAD仍5f672c2、无暂存路径；既有LF→CRLF提示未触发配置或批量换行修改。本条是文档/保护检查，不是新测试、新全量、产品修复或完整TASK-10验收。

### 2026-10-06 — TASK-10 / 取消契约红测与产品修复续接

- 上一Goal轮分类progress：Goal/交接/task/work已真实更新，权威断点移至契约红测准备；完整Goal保持active、5/30、TASK-10-C01 IN_PROGRESS/未提交，不因文档完成缩减目标。重读原附件、适用技能与代码目录指令，核对当前HEAD/status/index，仍5f672c2、无暂存路径。
- 已通过apply_patch修复Launcher RecordingRuntime在第二次cancel遇到既有marker与STOPPED时的错误模拟分支：只在STOP_REQUESTED阶段模拟合作退出/中断，保留重试/pending断言和原stop检查。新增run-cancellation-contracts.sh，复用只读Git源码快照、离线Maven、4GiB/swap0/2CPU/512pids/600秒/2GiB临时存储预算与严格Surefire统计；支持定向四套件及后续全量Java/Python，未预填任何通过结果。
- 下一步新鲜只读核查WSL双时钟/boot及共享服务、相关单元占用，取得真实契约红测后才实施可信marker与合作退出。不会重复R15旧产品红测，不停止共享Docker/containerd、不猜生产参数、不提高预算、不创建代理。
- 本轮为内部沙箱实现，不新增HTTP/数据库/OpenAPI契约；沿用独立stop与Runner原pending异常，现有Journal/身份/配置/锁/验证器为单一复用路径。R14漂移、R26 TASK-18失败/两skip与完整余项保留；未提交或推送。


### 2026-10-06 — TASK-10 / 可信取消闭环与精简执行

- 沿用原active Goal及TASK-10-C01，父HEAD仍5f672c2；无暂存、提交或push，保留IDE改动。当前无TASK-10 loaded unit/Probe进程，共享Docker/containerd均active/running、NRestarts0（PID314/233）；仅证明本次观察点。
- 实际产品修复已完成：LinuxRuntimeJournal可信空marker与幂等/权限/链接校验，LinuxSupervisorLauncher持久停止意图、剩余预算内合作观察和严格完整ID停止；LinuxContainerRunner.cancel与LinuxAttemptDiscovery已接入。复用现有锁、校验与commandTimeout，不新增管理器或测试框架。
- 复用已执行报告：CANCEL-CONTROL-R01真实红测退出1；R02四套件468项/467过/0失败错误/1旧镜像环境skip，取消核心零skip，退出0。R27遗漏文档/脚本输入的额外失败已最小修复并保留历史；R28 Java2403项/2400过/1失败/2环境skip，Python142/142，Java退出1。唯一JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe仍是已登记TASK-18执行器拒绝问题，不重复跑全量、不宣称发布门禁通过。
- 复用JVM-BUILD-R20与JAVA-SUPERVISOR-R16：当前产品编译2/2；JVM3927同进程start/cancel、Python4972真实flock拒绝，marker空/0600/UID-GID0/nlink1，监督器产品stop前自然ExitCode20/Pid0，USER实际CANCELLED/CLEANED、exit137非OOM，容器及stage目录均已消失；JVM5290后续恢复、6036零mutation重试通过。限定取消阶段唯一CLI mutation为监督器完整ID stop；两卷与控制证据保留，无删除授权，productRecoveryComplete/productionAcceptance/activeCancellationComplete均false。
- 采用用户最新策略：本次记录更新不触发构建/全量/重新冻结，不再重做旧Probe或红测。下一步仅核对原RQ-R03/RQ-R04必要验收与后续任务归属；完整资源回收、未知请求权威终结及生产参数缺口须准确保留，未达到交付条件不标DONE。审核为主代理自审，不冒充独立安全放行。


### 2026-10-06 — TASK-10 / 锁内取消标记发现修复

- 最小修复LinuxAttemptDiscovery与LinuxRuntimeJournal：取消文件目录枚举在journal锁之前，原cancellationSeen快照会漏验此间新增marker；现在持有原journal锁后无条件检查可选marker，存在时复用严格空文件/权限/UID-GID/链接/身份校验，缺失仍合法。没有新增锁、管理器或消息机制。
- 在既有LinuxAttemptDiscoveryTest内用参数化时序回归覆盖扫描后发布的空/非空marker；合法项可发现，坏项失败关闭且证据原样保留。既有600秒/4GiB/swap0/2CPU/512pids/2GiB tmpfs离线命令运行CANCEL-CONTROL-R03，session30513实际退出0：四套件470项、469过/0失败/0错误/1旧镜像skip，Discovery67、Journal33、Launcher353均零skip；新两例已执行。证据summary.json、build-summary.log及source-hashes.json均在该既有证据目录。
- 取消发布/合作停止主路径未改，沿用R16真实验收；R28保持改前全量历史，本次只重验受影响套件，不新建Probe/验证框架，不重做全量或冻结。将R28既有执行器拒绝明确登记到TASK-18；本轮不修无关调度代码。UNKNOWN权威终结、完整资源回收及具体生产身份/代际/终结权限仍待闭环，TASK-10未提交/未DONE，Goal仍active；主代理自审，不冒充独立复核。


### 2026-10-06 — TASK-10 / 原卡隔离验收与本地交付准备 [TASK-10-C01]

- 上一Goal轮为progress：实际修复锁前扫描遗漏marker并完成R03绿测；本轮核对最新源码与原TASK-10/14/19卡及D §4.1，没有新增恢复架构。原10要求身份/挂载/可信控制隔离和实际权限负例，不把14整域回收、19全非终态收敛或25/28生产参数当作10无限前置；完整Goal与安全底线不缩减。
- 原10证据已核对：STAGE-R02四角色各编译/执行成功，实际伪SUMMARY/COMPLETED不覆盖真实AC1；9份可信控制文件内容前后相等、真实FD/进程/信号权限负例保留，正常scratch/输入边界可用。task_runner、volume、compile/execute/inspect隔离路径未变；后续stage journal/闭门加固由R28 Python142/142覆盖。R16当前取消控制主路径已有真实绿测；R03重验Journal/Discovery及Runner/Launcher，470项469过、0失败错误、1旧镜像skip，核心零skip。
- R28已做本任务必要全量：Java2403项2400过/1既有TASK-18失败/2环境skip、Python142/142；后续两模块改动由R03覆盖，其余有效证据复用。唯一执行器拒绝登记18，R14不可定位的故障快照差异登记27；不能由后续绿测抹去失败，也不宣称发布门禁通过。
- 保留两卷/控制证据及UNKNOWN/cleanup pending，不发删除凭据、不提前释放占用、生产provider仍关闭。后续14/19/25/28任务记录已注明这些强制收尾，不降级为可忽略待办。IDE文件不提交，历史大产物只保留本地及校验索引，提交仅本任务源码/测试、必要记录和关键证据。审核为现有切片复核加主代理自审，不冒充最终独立安全放行。
- 当前VERIFIED_PENDING_COMMIT；父HEAD5f672c2faa9d02e2e3cd95c832ef4623bc222301，定位TASK-10-C01。按00～29既有本地提交授权准备交付；真实提交成功/范围核验后才DONE并推进11，无push。本次关键里程碑压缩更新既有Goal/交接末节，不新建文档，也不因文档重跑产品。
- 首次C01本地提交退出128：Git未配置author身份，未生成提交，索引保留。下一次沿用用户提供的yanwenyu/2990114500@qq.com，仅使用git -c的本次命令参数，不修改仓库或全局配置，不绕过钩子。


### 2026-10-06 — TASK-10 / 实际交付状态闭环 [TASK-10-C02]

- C01真实本地提交a9d2e11768972977fec03e512a9dc02bd91f3ae7，主题fix(sandbox): TASK-10 isolate trusted control plane [TASK-10-C01]；唯一定位匹配1项，父5f672c2，200份列定源码/测试/记录/关键证据含task/work，未夹IDE/二进制/归档，无剩余暂存。默认钩子未绕过，使用用户身份的单次git -c，无配置修改或push；此前身份缺失的失败保留。
- 实际交付及范围核验后才将10标DONE，完成6/30；整链DEF-05/N2、资源/调度占用保留、UNKNOWN收敛及生产配置仍须11/12/14/19/21/25/28/29验收，不把目录防删除或限定取消成功当完整资源许可保留的证明。两份原始/proc status天然尾空白保持不改，源码/文档格式检查排除该原始观测后退出0；仅去工具尾空行，不改测试逻辑。
- C02仅同步既有task/work/Goal/交接和验收README状态，父C01；不构建、不重跑有效测试。Goal保持active、无发布许可。下一TASK-11前置09/10现已有真实提交，可按原预算配置推进有界事件读取与验真。

### 2026-10-06 — TASK-11 / 事件归属与完整类型校验

- TASK-10已实际交付，C01 a9d2e117、C02 799fc1cb均唯一核验，09/10前置满足。TASK-00事件阈值仍是待确认候选，不把Task卡的前置条件当批准记录；先修不依赖该数值的事件身份/类型校验。
- 最小修改SandboxEventIngestor.Session.accept：在任何聚合/发布前拒绝null、缺类型、缺judgeId或跨任务事件，避免污染当前任务统计/终态；既有测试内参数化4形态，断言已有任务/摘要字节保持、零额外WS发布。待定reader限额不选默认，不新增管理器或测试框架；下一步只运行受影响套件。
- 定向验证已实际完成：R01因PowerShell未引用-Dstyle.color参数退出1、未执行测试（不是缺陷红测）；最小引号修正后R02/session70783退出0，SandboxEventIngestorTest 7/7、0失败错误跳过，其中新4例实际执行；R03/session22379只验调用方JudgeSandboxOrchestrationTest 7/7、0失败错误跳过，未重复第一套件/全量。证据直接使用target/surefire-reports两份标准XML/txt，命令为既有离线mvn -o -B -Dtest=<单套件> test；请求4GiB/swap0/2CPU/512pids/600秒、只读源码、仅target可写、2GiB tmpfs，无clean或旧产物删除。
- 两轮--collect单元结束后已卸载，事后systemctl show的not-found/default属性不是实际配置证据，不据此声称资源上界验收通过，也不为补观测重跑测试。原R16取消/隔离主路径未改、证据复用；TASK-11仍未交付，有界reader及具体限额仍待冻结，Goal active/6-30，无push。

### 2026-10-06 — TASK-11 / 协议验真实际修复与必要真实绿测 [TASK-11-R06]

- 修改既有SandboxTaskEvent/Ingestor、JudgeService、fake/Remote启动失败记录及Python emitter：真实handle绑定v2/attempt、连续sequence与幂等去重；有限BitSet拒绝case回放/越界，结果字段一致性校验；矛盾SUMMARY不能覆盖宿主统计或提前终结，COMPLETED须有完整结果或原请求授权的首错停止。未改聚合器/锁/权限边界，无通用管理器或第二套测试框架。
- R04/session19226：43/43、退出0；首次沙箱WSL E_ACCESS_DENIED未执行测试，依审批机制重跑后成功。v2接线使相关源码失效后R05/session70413实际90/90（53/8/9/20）、退出0，零失败/错误/skip；Artifact现有真实Python/C++ AC及首错停止用例接入Java验真/持久化，不用mock替代这段执行，但消息模板仍为单元替身，不声称真实WS/生产provider整链。
- 最后统一case范围检查只重验受影响套件：R06/session43582，Ingestor55/55及Python emitter单例1/1，均退出0、零失败/错误/skip。Maven实际参数为-o -B -Dstyle.color=never -Dtest=<受影响套件> test；Python为-B -m unittest runner.tests.test_task_runner_contract.TaskRunnerContractTest.test_runner_writes_ac_summary_and_events -v；标准证据直接使用target/surefire-reports四套XML/txt及unittest结果，不复制归档、不加固定数量门禁，无全量/clean/产品再冻结。
- 三轮均请求4GiB/swap0/2CPU/512pids/600秒、仅target可写及2GiB隔离/tmp。R05运行中systemctl show实际active/running，InvocationID 2d92e1ab49ed40e080a4e90d4f489167与启动一致，限制属性核对通过；仅证明测试外层配置，不当作事件RSS/磁盘资源上界验收。
- 真正剩余阻断是未批准的事件预算；Linux/Windows仍readAllLines/行游标，严格JSON/半行/UTF8/文件身份/总量边界未闭环。宿主传输错误及固定unavailable runId并发归12，权威停止统计仍归17；不扩展本任务绕过决策。源码自审/定向检查不冒称独立安全放行；总账旧5/30及10/11状态已纠正为实际6/30、10 DONE、11 BLOCKED。保留IDE/旧证据，未暂存/提交/push，Goal active；当前Java/Python事件契约不可冒称由旧R16/R20完整覆盖。

### 2026-10-06 — TASK-11 / JSON整数与类型验真闭环 [TASK-11-R08]

- 上一Goal轮为progress（实际协议修复及R04～06验证），未收到事件预算确认；本轮继续独立的JSON类型缺口。既有DTO内复用Jackson ObjectReader/内置coercion配置，三个provider各缓存一次；拒绝小数/字符串整数、文本强转、枚举序号、primitive null、重复键及尾随JSON，不改应用Mapper、依赖或限额，无新管理器/测试框架。
- R07/session26367因测试lambda泛型推断编译失败退出1、未执行测试，不算缺陷红测；一行类型参数修正后R07b/session81024实际18项1过/17失败/0错误skip、退出1，已证明非法输入会被接受。按现有缓存Jackson2.19.1 API作最小修复，不再搭第二套复现。
- R08/session1599实际五套92项90过/0失败错误/2环境skip、退出0（Contract27、Linux17含1skip、Windows19含1skip、Remote20、Artifact9）；协议和真实Python/C++→严格解码→Java入库均零skip，AC/首错停止仍正确，应用Mapper未被修改。仅跑受影响套件，未重跑Ingestor/编排、全量或冻结构建；标准证据仍为target/surefire-reports，外层请求沿用R06资源/只读约束，不将systemd Memory peak当JVM上界。
- 本次整套provider选择带入两项旧liveProbe：Linux缺cpp-judge-runner:latest镜像，Windows运行时实际linux。XML已解释skip，不能当平台放行或忽略发布阻断；后续局部只选受影响方法，不再带入旧Probe，不安装镜像/重跑期待偶然通过。生产真实域仍归25/26；预算相关reader继续BLOCKED，完整Goal active/6-30，无新提交/push，IDE及旧证据保留。

### 2026-10-06 — TASK-03 / 默认来源防伪 [TASK-03-R01]

- 两处现有认证路径只取request.getRemoteAddr()，application.yml明确forward-headers-strategy: none；不新增代理解析框架、不猜可信CIDR或限流阈值。ProductionAuthenticationTest复用真实回环HTTP，账号/访问码两模式同时携带伪XFF、X-Real-IP、Forwarded，实际创建会话仍绑定127.0.0.1，测试后失效会话。
- R01/session79607、unit wjc-task03-source-r01-20261006退出0；ProductionAuthenticationTest6、AuthenticationSafeDefaultsTest19、SessionCredentialHttpTest18、SessionCredentialLoggingTest2，共45/45、零失败/错误/skip，新两个真实HTTP参数实际执行；标准证据target/surefire-reports，四个修改文件diff --check通过。保留Java17编译目标。
- 仅默认直连切片闭环；可信代理链解析、容量/TTL/独立限流/头预算仍缺具体批准，03仍BLOCKED，无全量、Probe、冻结构建或提交/push。此次仅同步文档，不重跑有效测试；沿用原Goal active/6-30，保留03/11及IDE改动。

### 2026-10-06 — TASK-11 / 必填字段解码闭环 [TASK-11-R10]

- 现有SandboxTaskEvent.jsonReader内复用Jackson局部mix-in构造字段约束，拒绝缺失摘要计数/样本字段和结果字段；不新建验证框架、不改应用Mapper或全局DTO。参数化用例证明缺失计数此前静默补零；原资源字段遗漏已拒绝，未将其伪称新发现。显式零值与可选首错/停止原因保留兼容。
- R09因PowerShell拆分未引用的-Dstyle.color=never而退出1，未执行测试，不计红测；只修正参数引号后R09b/session97011实际13项3过/10失败/0错误skip、退出1，缺陷已充分证明。R10/session38593退出0：契约41、Linux/Windows/Remote轮询各1、真实Python/C++→严格解码→Java入库2，共46/46、零失败/错误/skip；真实AC和授权首错停止均正确。Maven沿用-o -B "-Dstyle.color=never"，-Dtest限定上述方法/契约套件，不带旧liveProbe；标准证据target/surefire-reports五套XML/txt，运行报告未复制归档。
- 两轮测试均沿用4GiB/swap0/2CPU/512pids/600秒、源码只读仅target可写及2GiB隔离/tmp；R10 unit wjc-task11-missing-fields-r10-20261006、InvocationID 6ef6fa3ecac24f8fb6cd9e309b583fe7终态success。外层限制不冒称事件/JVM资源上界或生产provider验收。未全量、clean、冻结构建或提交/push；仍缺具体事件预算与reader闭环，11保持BLOCKED，Goal active/6-30，保留03/11及IDE改动。

### 2026-10-06 — TASK-15 / 整数与非有限字面量最小闭环 [TASK-15-R02]

- 上一Goal轮为progress（11实际字段修复及46/46），本轮未收到预算批准；01/09依赖已交付，继续DEC-03已批准且不需要新增资源阈值的整数/非有限文本切片。仅在JudgeService原outputsMatch内补两个词法分支；整数用符号/前导零规范化和regionMatches精确判等，不进行大数运算或构建巨额指数，不新增比较框架、不改SPJ/认证/Runner。复用JudgeBaselineTest及单份语言无关JSON向量。
- R01/session55674、unit wjc-task15-semantic-r01-20261006实际23项15过/8失败/0错误skip、退出1，确认2^53相邻整数、NaN/5、非有限符号文本和整数不可套容差等误判；充分红测后直接修复。R02/session82910、unit wjc-task15-semantic-r02-20261006退出0，受影响基线32/32、零失败/错误/skip；XML确认23向量及两例真实C++编译执行均实际运行，NaN/5与相邻大整数经现有MockMvc创建/启动/查询得到WA、completed=1/wa=1/ac=0，正常AC与原输出限额回归仍绿。不冒称真实TCP/浏览器或隔离provider验收。
- 两轮沿用4GiB/swap0/2CPU/512pids/600秒及源码只读/仅target可写、2GiB隔离/tmp；R01默认日志目录写入被只读约束拒绝但测试实际执行，R02仅用-Dlogging.file.name=/tmp/wjc-task15-r02.log修正测试日志去向，未改产品配置。标准证据target/surefire-reports/TEST-com.example.demo.JudgeBaselineTest.xml及txt；无证据复制、全量、Probe、冻结构建或提交/push，源码/fixture定向diff检查通过。为源码自审，非独立放行。
- 仍缺解析上界与非法输入错误映射批准；有限小数/指数的double误差及溢出、API/页面精度损失、严格UTF8/完整词法等保留在15，跨语言留16。23向量不等于完整比较验收；15保持BLOCKED，Goal active/6-30，03/11/15与IDE改动全部保留。

### 2026-10-06 — TASK-04 / 站内重定向最小闭环 [TASK-04-R02]

- 上一Goal轮为progress（15实际误判修复及32/32），本轮未收到数值/环境新批准；仅推进DEC-06既定的重定向切片，不视03整体依赖已经满足。AuthenticationController原三分支及页面模型复用一个私有safeRedirect；使用标准URI/UTF8严格解码、一次规范化与残余编码检查，拒绝scheme/authority、双斜杠/反斜杠、控制字符、畸形编码及已覆盖登录循环，非法回退/；保留合法查询/片段、Unicode与字面百分号。未新增URI管理器、代理框架或生产阈值，未改变Cookie/CSRF/身份提取和03默认socket来源。
- 现有ProductionAuthenticationTest内参数化真实回环HTTP，客户端NEVER跟随跳转、只访问隔离网络中的127.0.0.1，成功会话finally失效。R01/session39330、unit wjc-task04-redirect-r01-20261006实际22项5过/17失败/0错误skip、退出1；充分红测后直接修复，未另搭复现。R02/session74365退出0，ProductionAuthenticationTest30（含24重定向向量、2默认socket来源）及SessionCredentialHttpTest18，共48/48、零失败/错误/skip；每向量实际覆盖已认证GET、账户POST、访问码POST，非法目标均回到/，正常站内目标保持可用。测试保留Java17编译目标。
- R02 unit wjc-task04-redirect-r02-20261006、InvocationID 75c3132e6d3f482bbc052e817b433d7c终态success；沿用4GiB/swap0/2CPU/512pids/600秒、源码只读仅target可写和2GiB隔离/tmp，测试日志仅/tmp。标准证据target/surefire-reports两套XML/txt，定向diff检查通过；无全量、Probe、clean、冻结构建或证据复制。为源码自审，不冒称TLS/浏览器/独立安全放行。
- 04仍因03整体前置未交付及Cookie/CSRF/完整真实验收未闭环而BLOCKED，DEF-20最终关闭留24/29；11事件预算、15解析预算仍待具体批准。不新增Goal/交接/阶段文档，无暂存/提交/push；Goal active/6-30，保留03/04/11/15和IDE全部改动。

### 2026-10-07 — TASK-04 / Cookie及真实HTTPS客户端闭环 [TASK-04-R04]

- AuthenticationController复用sessionCookie创建/失效属性：HttpOnly、SameSite=Lax、Path=/，无Domain；只有trusted-local原始socket回环的实际HTTP例外，HTTPS/生产/非回环强制Secure，不信转发协议头，rememberMe仍30天。沿用IpAddressMatcher标准回环定义，非生产代理CIDR批准。直接构造Controller的日志测试同步参数；不改身份/会话业务。
- R03/session77939实际13项全部失败、零错误/skip、退出1；四例已真实TLS握手登录，失败在安全属性，充分红测后直接修复。unit后缀20261006保留，实际时间已跨2026-10-07。R04/session28445退出0，ProductionAuthenticationTest39、AuthenticationCookieHttpsTest4、SessionCredentialHttpTest18、SessionCredentialLoggingTest2，共63/63、零失败/错误/skip，Cookie9和TLS4逐套XML确认实际执行；24重定向向量回归仍绿。标准证据target/surefire-reports四套XML/txt，无重复归档。
- HTTPS测试复用WorkerTlsCertificates既有签发材料，断言实际peer certificate一致，不关闭证书/主机名检查、不装全局CA；CookieManager经HTTPS实际认证、同主机HTTP捕获端口不携带JUDGE_SESSION，真实HTTPS登出后CookieJar删除且会话失效。测试应用初始trusted-local启动，再切换账户/生产Cookie路径，不冒称生产TLS启动/隔离provider验收。Tomcat默认keystore字样不作为证书来源证据。
- Maven：-o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task04-cookie-r04.log" "-Dtest=ProductionAuthenticationTest,AuthenticationCookieHttpsTest,SessionCredentialHttpTest,SessionCredentialLoggingTest" test。unit wjc-task04-cookie-r04-20261007、InvocationID 6028283979c24c27a8c05d1577da0343终态success，完成2026-10-07T00:16:57+08:00。沿用4GiB/swap0/2CPU/512pids/600秒、源码只读仅target可写、2GiB隔离/tmp和PrivateNetwork；不以外层峰值冒称业务资源上界。定向diff检查通过。
- 仅同步现有task/work，不因文档重跑测试/构建/冻结；未全量、暂存、提交或push。03前置、CSRF、浏览器及生产启动/代理配置仍阻断完整04，自审非独立放行；04保持BLOCKED，Goal active/6-30，保留03/04/11/15及IDE全部改动。

### 2026-10-06 UTC — TASK-04 / HTTP CSRF最小闭环 [TASK-04-R08]

- SecurityConfig移除全局csrf.disable；仅增加薄SessionBoundCsrfTokenRepository适配既有Spring HttpSession仓库，将token绑定当前统一解析的JUDGE会话，换凭证拒绝旧token。AuthenticationController成功登录/登出清token并轮换既有HTTP session id；auth/index所有现有POST发送模板token。无任何Cookie且X-Session-ID实际有效才豁免，无效/冲突Header不能回退或绕过。不新增管理器、消息/测试调度框架或生产预算；SockJS POST无宽泛例外，CONNECT授权及必要窄适配留06；当前无管理员状态变更URL。
- R05/session41889（unit wjc-task04-csrf-r05-20261007，InvocationID 2660b561e5bd4467955c82029cf482a4）实际12项1过/11失败、零错误/skip、退出1，充分红测后直接修复。R06/session81381退出0、145/145、零失败/错误/skip；四认证63、CSRF12、启动安全6、页面18、配额7、策略5、审计2、判题基线32，逐套XML确认实际执行。Maven选择器：-o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task04-csrf-r06.log" "-Dtest=CsrfProtectionHttpTest,ProductionAuthenticationTest,AuthenticationCookieHttpsTest,SessionCredentialHttpTest,SessionCredentialLoggingTest,SecurityModeStartupValidatorTest,FrontendTemplateContractTest,QuotaAndAuthorizationTest,JudgeControllerPolicyTest,AuditAndAdminTest,JudgeBaselineTest" test；unit wjc-task04-csrf-r06-20261007终态success，InvocationID未取得，不猜填。
- R07/session45311（InvocationID 06501c145c8e498fa04e4808b878fce6）15项14过/1失败、零错误/skip、退出1：真实Cookie→创建→启动→C++ AC通过，唯一失败为未启动CREATED取消accepted=false，精确归18/RQ-L01。成功及CANCELLED断言移入普通CreatedTaskCancellationHttpTest，未禁用、未放宽、整理后的独立文件仅编译未执行；18修复/最终验收须执行，不能称全量绿。04套件仅保留自身CSRF/实际AC路径，不扩修生命周期，也不重跑期待偶然通过。清理HTTP200仅为既有接口接收确认，不冒称删除或资源回收成功；实际清理收尾留14/18/22。
- R08/session35608仅重验当前CsrfProtectionHttpTest，退出0、14/14、零失败/错误/skip，XML逐方法核对：六变更路径缺失/错误token、无效/冲突/带Cookie Header、旧/跨浏览器及跨JUDGE会话token拒绝、真实登录/登出/AC及跨站CORS预检拒绝。-o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task04-csrf-r08.log" "-Dtest=CsrfProtectionHttpTest" test；unit wjc-task04-csrf-r08-20261007终态success，InvocationID未取得。观测完成2026-10-07T01:01:52+08:00（对应2026-10-06 UTC）；全部沿用R04外层限制/只读源码/私有网络，TLS与其他未受影响证据直接复用。标准证据target/surefire-reports各套XML/txt，无重复归档、全量、Probe、clean或冻结构建。
- 只读环境核对：隔离Ubuntu PATH无node/npm/npx/chromium/chromium-browser/google-chrome，Python playwright/selenium均不存在；未安装、未开放私有网络给Windows浏览器，真实浏览器仍缺兼容既定隔离边界的执行环境。03具体部署/限流批准、生产TLS启动/代理及完整验收仍缺，04/18保持BLOCKED；源码自审非独立安全放行。仅同步现有task/work，文档不触发产品测试；未暂存/提交/push，Goal active、仍6/30，保留03/04/11/15与IDE改动。

### 2026-10-06 UTC — TASK-05 / 双模式页面及错误最小修复 [TASK-05-R01]

- 上一Goal轮为progress（04实际CSRF修复、145/145及真实HTTP14/14）；本轮HEAD仍799fc1cbb85ceae91327d6e2710eabb47afd602b，未获新生产数值/环境批准。复用附录DEF-01既有页面DOM证据和最新源码确认，不重跑旧红测。AuthenticationController仅补后端模式模型；auth模板仅渲染该模式凭证、按原DTO提交、保留CSRF/rememberMe/站内redirect，密码不trim，复用按钮禁用防重发并删重复Enter派发。401/429/403/5xx、解析异常、断网与200+success=false分别提示，消息用text node不拼HTML；保持原视觉，不建组件/请求/消息框架。
- R01/session94502退出0：ProductionAuthenticationTest42、FrontendTemplateContractTest19、CsrfProtectionHttpTest14，共75/75、零失败/错误/skip，逐套XML与新增方法核对实际执行；三真实HTTP渲染向量覆盖本地访问码、本地账户、生产模式强制账户，假mode query不改变服务端选择。内联JS语法解析通过，不冒称UI行为/浏览器验收；真实Cookie→创建→启动→C++ AC及登录/登出回归仍绿，动态模式测试不冒称生产整体启动/隔离provider验收。
- 命令：-o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task05-ui-r01.log" "-Dtest=ProductionAuthenticationTest,FrontendTemplateContractTest,CsrfProtectionHttpTest" test。unit wjc-task05-ui-r01-20261007、InvocationID 7860bdcc7c6b4f91813d764cf9d2699d终态success；观测完成2026-10-07T01:25:51+08:00（2026-10-06 UTC）。沿用既有4GiB/swap0/2CPU/512pids/600秒、源码只读仅target可写、2GiB隔离/tmp及PrivateNetwork。标准Surefire XML/txt，不归档复制/全量/Probe/clean/冻结；未变化的TLS/其他阶段证据复用，源码定向diff检查通过。
- 真实浏览器入口做必要核对后停止工具探索：Windows Edge/Chrome文件存在，当前CUA库存有Edge扩展；IAB创建返回Browser is not available，Edge任务标签创建返回unsupported Codex auth method: apikey，未取得可用任务tab handle。未输入凭证、未改Codex鉴权/浏览器权限、未安装驱动或开放私有网络，未另启动测试应用；不能说整机无浏览器，更不能以HTTP/模板绿代替两模式成功/401/429/断网/登出UI证据。需可用且符合既定隔离边界的浏览器执行入口；04前置/完整验收仍缺，05保持BLOCKED。仅同步现有task/work，文档不触发产品验证；源码自审非独立放行，未暂存/提交/push，Goal active/6-30，全部已有源码及IDE改动保留。

### 2026-10-06 UTC — TASK-12 / 失败归属与宿主轮询错误最小修复 [TASK-12-R03]

- RemoteWorkerRunner复用完整不可变句柄作localEvents键，四参数向量覆盖同/不同judge及正/逆消费顺序，Jackson还原句柄仍归属一致，取消只释放本attempt。R01/session83918五项全部失败、零错误/skip、退出1；修复后R02/session57111三套51/51、零失败/错误/skip、退出0，完成2026-10-07T01:52:58+08:00；unit wjc-task12-failure-owner-r02-20261007、InvocationID ba91bd22f96849879c6e87bbfe0f7dd8。旧红测不重跑。
- 本轮再补最小错误分离：失败句柄事件消费/清理后返回空批、不请求虚构run；HTTP错误、非法JSON、null批次/元素由既有异常路径交给宿主，不再生成伪SANDBOX_UNAVAILABLE或runner序号；恢复轮询中断信号。参数化复用真实HTTPS FakeWorkerServer，宿主编排测试确认已启动句柄只调用一次现有cleanupResidual；这是调用证据，不是实际容器/Worker清理成功。未新增管理器、消息或测试调度框架。
- R03/session91748：RemoteWorkerRunnerTest31、RemoteWorkerTlsTest18、JudgeSandboxOrchestrationTest9，共58/58、零失败/错误/skip、退出0，关键方法逐套XML确认。命令mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task12-poll-error-r03.log" "-Dtest=RemoteWorkerRunnerTest,RemoteWorkerTlsTest,JudgeSandboxOrchestrationTest" test；unit wjc-task12-poll-error-r03-20261007、InvocationID b5525bea35584447b277edcc62e8772a，完成2026-10-07T02:02:40+08:00（2026-10-06 UTC）。运行中实际核对4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及2GiB隔离/tmp，标准Surefire XML/txt，不复制归档/全量/Probe/冻结。普通WSL E_ACCESS_DENIED后按审批重试，非测试失败。
- 11预算/reader及本任务显式状态、drain/flush、总deadline、有限重试仍缺；当前传输错误仍立即宿主失败关闭，不冒称完整UNKNOWN语义。12保持BLOCKED，Goal active/6-30，无暂存/提交/push，IDE及全部既有改动保留；只同步task/work，文档不触发构建。

### 2026-10-06 UTC — TASK-18 / CREATED取消实际功能闭环 [TASK-18-R01]

- 不重跑TASK-04 R07既有取消红测，直接沿DEC-08获批语义最小修复。JudgeScheduler复用与enqueue同一monitor，CREATED落盘CANCELLED及零已执行摘要后才返回accepted=true，不创建ScheduledTask/执行槽；摘要仅调整既有方法参数以复用TaskContext，未扩改运行中统计。JudgeService成功取消释放pending，CREATED状态从持久摘要读，不再覆盖成无摘要缓存；重复取消false/CANCELLED，非本任务的其他槽不再误报running/queued。未建生命周期管理器或锁Map。
- R01/session41001退出0：JudgeSchedulerTest9、CreatedTaskCancellationHttpTest1、JudgeSandboxOrchestrationTest9、FileTaskStoreTest48、JudgeControllerPolicyTest5、QuotaAndAuthorizationTest7，共79/79、零失败/错误/skip，新增及取消/预算/权限关键方法XML确认。真实Cookie+有效CSRF创建→首次取消true/CANCELLED→状态摘要total=1/completed=0→重复取消false/CANCELLED；取消后调度拒绝入队且执行/槽位为零。命令mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task18-created-cancel-r01.log" "-Dtest=JudgeSchedulerTest,CreatedTaskCancellationHttpTest,JudgeSandboxOrchestrationTest,FileTaskStoreTest,JudgeControllerPolicyTest,QuotaAndAuthorizationTest" test；unit wjc-task18-created-cancel-r01-20261007、InvocationID 2289705a391447a2907c2596b22da4ab，完成2026-10-07T02:10:38+08:00（2026-10-06 UTC）。沿用R03外层隔离/资源限制，标准Surefire证据，不全量/归档/Probe/冻结。
- 18仍BLOCKED：14/17前置、TTL扫描/清理预算、完整start幂等/终态409与三方竞态未完成；R28执行器交接拒绝未修，不重跑期待偶然通过。CREATED没有执行结果，零摘要并非伪造运行终态，也不冒称运行域已回收；跨文件原子final仍留17。12显式状态/预算仍未闭环，Goal active/6-30，无暂存/提交/push；IDE及所有既有改动保留。只更新task/work，文档不触发产品重测。

### 2026-10-06 UTC — TASK-18 / 有界交接及启动幂等最小修复 [TASK-18-R03]

- 上一Goal轮为progress（12/18实际修复、58/58和79/79）；本轮HEAD仍799fc1cb，沿最新源码继续18，不重跑旧Probe或红测。AsyncConfig只增加与现有许可数相等的有界内部交接缓冲，线程并发、业务排队额度和AbortPolicy不变；Scheduler复用monitor，释放本任务与预留FIFO后继在一个锁区内，避免退栈窗口拒绝和新请求抢队。四参数向量复用真实AsyncConfig，涵盖并发1/2、队满重试、取消、FIFO、实际工作线程及上界。
- R02/session80028六套67/67、零失败/错误/skip、退出0：Scheduler13、Baseline32、Created HTTP1、Orchestration9、Controller5、Quota7；原runtimeErrorIsRecordedAsSingleCaseRe真实C++/HTTP正常RE断言转绿，未改fixture/等待/业务断言。命令mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task18-handoff-r02.log" "-Dtest=JudgeSchedulerTest,JudgeBaselineTest,CreatedTaskCancellationHttpTest,JudgeSandboxOrchestrationTest,JudgeControllerPolicyTest,QuotaAndAuthorizationTest" test；unit wjc-task18-handoff-r02-20261007、InvocationID ad0b47339ddf484b8282c732f5945a15，完成2026-10-07T02:23:16+08:00。
- 再补DEC-08既定启动语义：pending缺失仅验证Scheduler真实已调度，不靠存量非终态伪称运行；同锁检查终态，专用异常映射409，原200/429及权限隐藏保留，pending仅移除本次对象。R03/session45085六套37/37、零失败/错误/skip、退出0：Scheduler13、Created HTTP1、Orchestration10、Controller5、Quota7、真实RE1；XML关键方法及runLog实际核对。Cookie+CSRF取消后start返回409/CANCELLED，重复取消仍false；Running重复start仅一次Runner，终态不得再启动。命令同R02通用选项，日志/tmp/wjc-task18-start-r03.log，选择器JudgeSchedulerTest,CreatedTaskCancellationHttpTest,JudgeSandboxOrchestrationTest,JudgeControllerPolicyTest,QuotaAndAuthorizationTest,JudgeBaselineTest#runtimeErrorIsRecordedAsSingleCaseRe；unit wjc-task18-start-r03-20261007、InvocationID cc12031863984e5991d21ed5cbc53f88，完成2026-10-07T02:28:54+08:00（两轮均2026-10-06 UTC）。
- 沿用既有4GiB/swap0/2CPU/512pids/600秒、私有网络、源码只读仅target可写、2GiB隔离/tmp；标准Surefire/实际命令输出，不复制归档或以外层峰值冒称业务资源验收。只重验受影响部分，未变比较向量复用R02，不全量/Probe/冻结。18仍BLOCKED于14/17前置、TTL扫描/清理预算及完整三方竞态；不冒称原子final、真实运行域回收或最终生产放行。仅更新task/work、自审非独立安全审核，未暂存/提交/push，IDE与已有修改保留，Goal active/6-30。

### 2026-10-06 UTC — TASK-23 / direct运行摘要实际闭环 [TASK-23-R03]

- 上一Goal轮为progress（18交接/启动实际修复与67/67、37/37）；本轮HEAD仍799fc1cb，按23边界推进N8，不扩大10/18。复用附录40例无运行摘要的真实红证据；JudgeService只改原RUNNING构造，附带同一ResultAggregator快照、results=null，复用现有回调/缓存/发布入口，不新增框架。普通会话UA AUDIT告警保留，未修改认证策略，不把本次当STRICT绑定/真实WS/浏览器验收。
- R01/session46910六套56项54通过/0失败/2错误/0skip、退出1：CaseBatchRunner9、HighVolume4、Aggregator4、Publisher5、Baseline32共54绿，报告runLog均/tmp/wjc-task23-running-r01.log。真实HTTP两项未发Accept，按原双格式接口得到纯文本ID而解析失败；R02/session47972两项均失败/零错误skip、退出1，夹具filesystem不兼容产品既有-std=c++14。两次受阻后核对推进方式，最小修正Accept与C++14路径处理，不改产品、编译配置、阈值或强断言，不搭第二测试流程。完成分别2026-10-07T02:43:56、02:47:09+08:00；R01 InvocationID未取得，结束--collect后的not-found/default参数不作为真实资源证据，未为此重跑。
- R03/session11543 RunningProgressHttpTest两参数项2/2、零失败/错误/skip、退出0，XML/runLog /tmp/wjc-task23-running-r03.log实际核验。真实Cookie+CSRF创建/启动、C++生成器/候选/答案执行，分别40例AC/WA；HTTP在终态前观察首例completed=1，运行计数单调、分类和等于completed、测试样本上界3/2、无全量results，终态completed=40，早期快照仍为1。命令mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task23-running-r03.log" "-Dtest=RunningProgressHttpTest" test；unit wjc-task23-running-r03-20261007、InvocationID 679b07cbd7044972b38d2ca38f75dfd4，完成2026-10-07T02:51:29+08:00（2026-10-06 UTC）。R01选择器RunningProgressHttpTest,HighVolumeJudgeIntegrationTest,ResultAggregatorTest,ProgressPublisherTest,JudgeBaselineTest,CaseBatchRunnerTest；R02仅真实HTTP两项，日志/tmp/wjc-task23-running-r02.log、unit wjc-task23-running-r02-20261007、InvocationID e79a92276c9b41a3ac4bba2366cdae95。
- 运行中实际核对R03的4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写和2GiB隔离/tmp；不以外层峰值冒称业务资源上界或把测试参数当生产批准。产品源码在R01后未改，54项有效回归直接复用，无全量/Probe/证据复制/冻结。23仍BLOCKED：17/21/22前置、revision、实际HTTP/WS一致性、缓存回收/容量/TTL及晚到消息未闭环；源码自审非独立安全放行，未暂存/提交/push，IDE与其他既有改动保留，Goal active/6-30；仅更新task/work，文档不触发产品重测。

### 2026-10-07 +08:00（2026-10-06 UTC）｜TASK-17 停止仲裁与统计保留 [TASK-17-STOPPING-R02]

- 沿用原Goal，HEAD799fc1cb及已有改动保留；TASK-10已真实交付，不重复Probe/取消红测。按RQ-L03/AC-L03、DEF-21、D§6.2直接修复已实锤覆盖：JudgeScheduler执行中取消/预算定时器只发令牌，退出前保持实际状态与运行占用；已提交终态优先，不再重复写同态摘要。兜底复制持久摘要的分类/样本/firstFailedCase，仅无结果时全零；已有完成数但无聚合时记错误并不伪造分类。JudgeService取消不再写100%无摘要缓存，未启动任务终结后读取磁盘。复用原令牌、聚合器、存储入口；只改两处产品文件和原JudgeSchedulerTest/RunningProgressHttpTest，不新建框架。
- R01/session69554：JudgeSchedulerTest17、JudgeBaselineTest32、JudgeSandboxOrchestrationTest10、CreatedTaskCancellationHttpTest1，共60/60、零失败/错误/skip、退出0；runLog /tmp/wjc-task17-stopping-r01.log，unit wjc-task17-stopping-r01-20261007，InvocationID ac25d80d31cb4be6bf3f0db11e01e11f，完成03:17:00+08:00。六参数覆盖令牌先接受但未退出、已有/兜底摘要保留及重复取消；随后仅将单位夹具终态改为真实WA、对齐控制器RUNNING响应断言并删除失用等待助手，产品源码未改，未重复其余43项。
- R02/session83671：JudgeSchedulerTest17/17、RunningProgressHttpTest4/4，共21/21、零失败/错误/skip、退出0；XML逐方法及runLog /tmp/wjc-task17-stopping-r02.log已核对。保留原AC/WA两项40例强断言，新增两项真实Cookie/CSRF、TCP HTTP和C++混合结果：取消completed=3/ac=2/wa=1、failureSamples=1/slowSamples=2；预算超限17/9/8、3/2，均firstFailedCase=2，分类和等于completed，终态/summary.json/移除本任务缓存后HTTP逐字段相等，重复取消未改finishedAt。8秒预算仅测试配置并finally恢复，不是生产批准；普通会话UA AUDIT告警保留，未改变认证策略。证据直接使用target/surefire-reports，不复制归档。
- 两轮均使用现有systemd-run隔离：4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写、2GiB隔离/tmp；运行中实际核对，未操作共享Docker/containerd。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task17-stopping-r0N.log" "-Dtest=上述当轮套件" test；R02 unit wjc-task17-stopping-r02-20261007、InvocationID ce8ea99ea1be41199f45115c276b315f，完成03:22:04+08:00（两轮均2026-10-06 UTC）。源码自审非独立放行；17仍BLOCKED：11/12、final字段/旧数据兼容、单文件原子提交及写失败/投影重建/晚到事件/重启验收未闭环。无全量/冻结/真实WS或生产provider验收，未暂存/提交/push；Goal保持active/6-30，仅同步task/work，文档不触发产品重测。

### 2026-10-07 +08:00（2026-10-06 UTC）｜沙箱停止统计与角色预算 [TASK-17-SANDBOX-R02] [TASK-12-ROLE-BUDGET-R01]

- 上轮为progress（停止仲裁修复与真实HTTP绿测）；核对HEAD799fc1cb、空暂存及现有记录后，继续同一DEF-21根因。SandboxEventIngestor复用toSummary保留全部分类/样本/首错，取消和预算采用既有规范停止原因；同一Session的accept/terminal互斥，首个终态后重复、晚到结果/状态不再聚合或发布。只改既有入口，不另建生命周期框架；61项协议/统计回归含五类停止与并发回调均绿，统计以宿主已接收RUN_FINISHED为准。
- R01/session86640：79项77通过/2失败/0错误/0skip、退出1，完成03:45:27+08:00；其中Ingestor61、Orchestration10、Publisher5共76绿，产物runLog /tmp/wjc-task17-sandbox-r01.log。真实取消夹具误要求Python已有slowSamples为2，但其报告为空且协议允许采样子集；改为逐字段核对全部标量/失败样本，严格验证宿主慢样本两项来自真实结果及冷读全字段一致，不增生产采样限额、不放宽计数。另一真实红测为30秒角色突破8秒任务预算，15秒仍未退出；登记TASK-12，不扩TASK-17。
- TASK-12最小修复：task_runner.py在编译及各角色执行前后复用check_control_files/monotonic与已存在maxTaskRuntimeMillis；传入原执行器的timeout取角色限制与剩余任务预算较小值，预算导致的超时不误写TLE/编译失败，保留取消优先与原参数/权限。仅改原脚本与test_task_runner_stage_dispatch.py；Python定向10/10、零失败错误skip、退出0，unit wjc-task12-budget-r01-20261007、InvocationID e00f57b857f54058b3fd00118ba8ca4a，命令python3 -B -m unittest discover -s runner/tests -p test_task_runner_stage_dispatch.py -v。未引入新预算配置或等待框架。
- 脚本变更后R02/session38979只补验受影响TaskRunnerArtifactContractTest：11/11、零失败/错误/skip、退出0，完成03:58:18+08:00；真实取消exit20/0.982秒、预算exit21/8.172秒，均completed=2/ac=1/wa=1/firstFailedCase=2，宿主样本1/2及新FileTaskStore冷读一致，普通WA/TLE/OLE、编译失败与首错停止仍通过。XML方法/runLog /tmp/wjc-task17-sandbox-r02.log已核对，复用未失效76项，不重跑旧容器Probe/全量/冻结。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task17-sandbox-r0N.log" "-Dtest=当轮选择器" test；R01选择器SandboxEventIngestorTest,JudgeSandboxOrchestrationTest,ProgressPublisherTest,TaskRunnerArtifactContractTest#realRunnerMixedResultsSurviveStopAndHostIngestion+taskRunnerStopsAfterFirstNonAcceptedCaseWhenRequestedAndKeepsArtifacts，R02选择器TaskRunnerArtifactContractTest；两轮unit wjc-task17-sandbox-r01/r02-20261007、InvocationID分别7d77fb6e3538436a88a2abad7853d0fa/456f056121f04a3ba7119c17a0e6dbcc，实际运行中核对既有4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及2GiB隔离/tmp。仅trusted-local Python/C++→宿主磁盘证据，不冒称WS、重启、容器/Worker或实际全域回收；旧冻结产物不冒称对应当前脚本。17原子final/旧数据兼容及11/12前置，12显式状态/宿主deadline/drain/重试，均仍BLOCKED；未提交/push，IDE改动保留，Goal active/6-30，只同步task/work。

### 2026-10-07 +08:00（2026-10-06 UTC）｜本地Runner读流错误 [TASK-12-LOCAL-READ-R01]

- RQ-R02/AC-R02、D §4.3/4.4：LinuxContainerRunner和WindowsHyperVContainerRunner的pollEvents读取失败改抛既有宿主异常，不再制造无v2归属/序号的SYSTEM_ERROR事件。两原provider测试各加malformed-json/directory参数，保留具体IOException cause但公开消息不泄漏路径/数据；真实文件恢复后sequence=1正常消费，错误不推进游标或自行删除容器。只改两适配器及原测试，不新增管理器/阈值/状态框架，自审非独立放行。
- 接续既有session39957，无重启实验：Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task12-local-read-r01.log" "-Dtest=LinuxContainerRunnerTest,WindowsHyperVContainerRunnerTest,JudgeSandboxOrchestrationTest,SandboxRunnerContractTest" test；unit wjc-task12-local-read-r01-20261007，04:26:31+08:00完成、退出0。Surefire四套19/21/10/41，共91项、89通过、0失败错误、2skip；四个新增参数用例及宿主pollFailureUsesHostErrorPathAndCleansTheStartedHandle均实际执行，报告runLog一致。skip明确为Linux cpp-judge-runner:latest镜像缺失及Windows运行时实际为linux；不是关键用例跳过。
- 沿用既有systemd-run隔离，命令请求4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、只读源码仅target可写及2GiB隔离/tmp；本轮未在active期间读取实际属性，不用collect后的默认值补证，也不为补观测重跑。真实的是文件/JSON路径，容器命令为替身；未做真实provider/WS、全量、冻结或全域回收。TASK-10已交付证据复用，TASK-12仍BLOCKED（11预算及显式状态/宿主deadline/drain/有限重试未闭环），不标DONE、不提交/push；保留IDE改动，Goal active/6-30。

### 2026-10-07 +08:00（2026-10-06 UTC）｜创建配额原子区 [TASK-20-CREATE-R02]

- 上轮为progress（TASK-12读流错误绿测），沿用HEAD799fc1cb、空暂存及全部既有改动。11/12生产阈值审批阻断未变化，转进20可独立修复的既有竞态，不扩10范围。RQ-L02/AC-L02、D §6.3：原QuotaService/稳定owner检查保留，仅新增固定64条带锁（锁分片实现、非用户容量或业务配额），JudgeService检查→CREATED落盘→pending注册在同一原子区；无预留管理器/无界用户锁Map，不持锁编译/调用网络，匿名兼容、管理员无豁免、业务数值/时区/每日请求预算不变。只改上述两产品文件及原QuotaAndAuthorizationTest，自审非独立放行。
- R01/session98578：四参数均真实红，8个不同会话归同owner争最后1额度，queued普通/管理员、daily-case、daily-runtime分别误成功8/8/6/6；4失败、0错误skip、退出1，04:43:54+08:00完成。选择器QuotaAndAuthorizationTest#concurrentSessionsOfOneOwnerCannotConsumeTheLastCreationQuotaTwice；原测试报告失败字段已核对，不另搭复现流程。unit wjc-task20-create-r01-20261007、InvocationID eee9ec44b82c42a8a5933438775ae3c2；属性查询时单元已collect，不将默认infinity等值当作运行观测，不为补证重跑。
- 最小修复后R02/session31245：QuotaAndAuthorizationTest11、JudgeControllerPolicyTest5、JudgeSandboxOrchestrationTest10、CreatedTaskCancellationHttpTest1，共27/27、零失败错误skip、退出0，04:45:48+08:00完成；每个新参数实测1成功/7个JUDGE_QUOTA_EXCEEDED，磁盘仅1个CREATED且pending增量精确对应成功ID，另用户创建成功。并发路径是实际Controller/Quota/FileTaskStore的MockMvc集成；必要真实验收复用原TCP HTTP+Cookie/CSRF创建→取消→状态→晚启动409→重复取消用例，未冒称浏览器或真实HTTP并发压力。四套Surefire方法及runLog标识/tmp/wjc-task20-create-r02.log核对通过，无新增固定测试总数门禁。
- 两轮Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task20-create-r0N.log" "-Dtest=当轮选择器" test；R02选择器为上述四套，unit wjc-task20-create-r02-20261007、InvocationID ed303da62ba640258275ff45439e24a3，active时实际核对4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及2GiB隔离/tmp，最终内存峰值423.9MiB/swap0。仅此受影响回归，无全量/冻结/旧Probe；20失败回滚、当日计费防删除退款/重启重建、日期边界与18/19依赖仍未完成，21派发许可留原边界。未暂存/提交/push，不标DONE，IDE改动保留，Goal active/6-30；只更新task/work，文档不触发产品重测。

### 2026-10-07 +08:00（2026-10-06 UTC）｜创建落盘失败回滚 [TASK-20-ROLLBACK-R02]

- 上轮为progress（原子创建配额修复），HEAD799fc1cb/空暂存及既有改动核对后继续20。RQ-L02/AC-L02、D §6.3：FileTaskStore先原子新建目录，拒绝接管/覆盖已有目录；落盘move或append失败在原lease/任务锁内回滚本次创建，复用原删除实现（同类内私有重载，避免嵌套lease），全部路径/managed证据防护保留。原异常重抛，回滚失败加入suppressed；不伪造删除授权/回收成功、不增管理器/策略数值。只改FileTaskStore及原FileTaskStoreTest，自审非独立放行。
- R01/session91882：原moveAtomically故障接缝、真实events目录引发append IOException；两失败残留、两已有目录被接管/覆盖、managed失败未报告回滚拒绝，共5失败/0错误skip、退出1，04:57:10+08:00完成。选择器FileTaskStoreTest#failedCreationRemovesOnlyItsNewDirectoryAndCanBeRetried+creationNeverAdoptsOrOverwritesAnExistingDirectory+failedCreationRetainsManagedAttemptEvidenceAndReportsRollbackFailure，报告5项失败已核对。unit wjc-task20-rollback-r01-20261007；查询时已collect，无实际InvocationID/属性补证，不使用默认值或重跑旧红。
- 修复后R02/session29256：FileTaskStore53、Quota11、ControllerPolicy5、Orchestration10、TaskCleanup13、ProductionCleanup5、JudgeFile17、JudgeFileProduction4、CreatedCancellationHttp1、Artifact11，共130/130、零失败错误skip、退出0，05:01:05+08:00完成；五个新增方法/参数及十套runLog标识/tmp/wjc-task20-rollback-r02.log实际核对。新测试真实磁盘证明失败目录/计数归零、外部任务逐字节与storage锁身份不变、同ID重试仅1条事件；managed证据及元数据完整保留、suppressed明确失败，原删除负例保持。既有真实Cookie/CSRF/TCP创建取消及Python/C++取消exit20/预算exit21均绿，后者completed=2/ac=1/wa=1、样本1/2、冷读一致；只因全局创建/删除路径变化重验相关消费者，非容器/Worker/浏览器、非共享Docker操作或完整域回收验收。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task20-rollback-r0N.log" "-Dtest=当轮选择器" test；R02选择器FileTaskStoreTest,QuotaAndAuthorizationTest,JudgeControllerPolicyTest,JudgeSandboxOrchestrationTest,TaskCleanupServiceTest,ProductionCleanupTest,JudgeFileServiceTest,JudgeFileServiceProductionTest,CreatedTaskCancellationHttpTest,TaskRunnerArtifactContractTest。unit wjc-task20-rollback-r02-20261007、InvocationID 9c7aef4b87c84edea8fed1d36a019dd9，active时实际核对既有4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、只读源码仅target可写及2GiB隔离/tmp，最终内存峰值587.0M（systemd输出）/swap0。20仍BLOCKED：pending注册异常、计费防删除退款/重启重建、日期边界及18/19未完成；拒绝回滚的残留不当作释放容量。无全量/冻结/旧Probe，未暂存/提交/push，不标DONE，IDE改动保留，Goal active/6-30；仅同步task/work，文档不重测产品。
- 同阶段自审补齐 [TASK-20-ROLLBACK-R03]：回滚读取可变JudgeTask的ID可能改变目标，改为捕获本次创建的原始judgeId并贯穿路径/锁/回滚；新增实际move接缝变ID→events目录导致IOException的单项，原失败目录删除、另一RUNNING任务metadata/events逐字节保留。无需再搭红测框架或改业务数值。R03/session60123仅重验FileTaskStoreTest54和CreatedTaskCancellationHttpTest1：55/55、零失败错误skip、退出0，05:07:29+08:00完成；Maven同上，选择器FileTaskStoreTest,CreatedTaskCancellationHttpTest、runLog标识/tmp/wjc-task20-rollback-r03.log。unit wjc-task20-rollback-r03-20261007、InvocationID d0803dcf8eb4461c8aca45a3a7dd5cd6，active时核对同一实际隔离属性，最终内存峰值484.4M（systemd输出）/swap0。复用R02稳定输入消费者其余76项，不重复Runner真实实验/全量/冻结；未提交、不标DONE，原20阻断与Goal active/6-30不变。

### 2026-10-06 UTC（日志2026-10-07 +08:00）｜pending注册失败回滚 [TASK-20-PENDING-R02]

- 上轮为progress（落盘回滚/身份绑定），核对HEAD799fc1cb、空暂存及源码后继续同一20。RQ-L02/AC-L02、D §6.3：只在TaskStore/FileTaskStore原create添加注册重载，单参仍兼容；同步pending动作在原任务锁/lease及回滚try内，不另建事务/预留管理器。JudgeService用putIfAbsent避免覆盖，失败按实例身份清本次条目（不用record值相等误删旧注册）；原权限、稳定owner、身份绑定、managed拒删、异常原因和业务参数保留。仅改三产品文件及原Orchestration测试，自审非独立放行。
- R01/session4373：注册写前/写后异常留下真实CREATED占额，已有注册被覆盖，共3失败/0错误skip、退出1，21:18:49 UTC完成（Maven05:18:49+08:00）。选择器JudgeSandboxOrchestrationTest#failedPendingRegistrationDoesNotChargeCreationOrOverwriteAnExistingRegistration；原报告三参数失败已核对，unit wjc-task20-pending-r01-20261007，属性查询时已collect，未取得实际InvocationID/属性，不把默认值补作运行证据，不重跑旧红。
- 最小修复后R02/session22694：FileTaskStore54、Orchestration13、Quota11、ControllerPolicy5、CreatedCancellationHttp1，共84/84、零失败错误skip、退出0，21:21:59 UTC完成（Maven05:21:59+08:00）；五套XML/runLog标识/tmp/wjc-task20-pending-r02.log及三新参数实际执行核对。故障由原Mockito注入map操作、磁盘/配额为真实实现：写前/后失败无本次目录/CREATED/pending，最后1 queued额度同ID可重试且1事件；等值旧注册按同一实例保留，其他任务不受影响。真实Cookie/CSRF/TCP创建→取消→状态→409晚启动→重复取消仍通过；不是实际OOM/崩溃、容器/Worker/浏览器验收。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task20-pending-r0N.log" "-Dtest=当轮选择器" test；R02选择器FileTaskStoreTest,JudgeSandboxOrchestrationTest,QuotaAndAuthorizationTest,JudgeControllerPolicyTest,CreatedTaskCancellationHttpTest，unit wjc-task20-pending-r02-20261007、InvocationID ceb490cbb8934dc3873b6f5009fa167a；active时实际核对既有4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、只读源码仅target可写及2GiB隔离/tmp，终态内存峰值675.2M（systemd输出）/swap0。复用不受注册分支影响的旧证据，不重跑全量/冻结/Runner/Probe，不操作共享Docker。20仍BLOCKED：计费防删除退款/日期边界/重启重建及18/19；进程崩溃或拒绝/失败回滚残留仍保守占额。未暂存/提交/push、不标DONE，IDE改动保留，Goal active/6-30；仅同步task/work，文档不重测产品。

### 2026-10-06 UTC（日志2026-10-07 +08:00）｜删除不退每日额度 [TASK-20-DAILY-R03]

- 续原Goal/20，核对HEAD799fc1cb及空暂存；上轮pending修复有效直接复用。最小修改DailyQuotaCharge/JudgeTask、TaskStore/FileTaskStore、QuotaService、TaskCleanupService及四个既有测试：每次创建生成计费提交ID，正常删除在原安全校验/锁/lease内先写当日凭据，失败创建回滚跳过；写失败、损坏或符链拒删。owner仅SHA指纹，live/retained按提交去重，同judgeId重建仍另计；保留系统时区、旧case/runtime及缺policy默认口径，累计饱和。过期复用既有清理周期和quota查询，不新增管理器/调度/TTL参数或权限。
- R01/session55015：QuotaAndAuthorizationTest#cancellationAndPhysicalDeletionDoNotRefundDailyQuotaAfterReload，两参数均预期429却200，2失败/0错误skip、退出1，21:47:59 UTC；原XML与runLog /tmp/wjc-task20-daily-r01.log已核对后才修复。R02/session63597选择器FileTaskStoreTest,QuotaAndAuthorizationTest,JudgeSandboxOrchestrationTest,JudgeControllerPolicyTest,TaskCleanupServiceTest,ProductionCleanupTest,CreatedTaskCancellationHttpTest；64+13+13+5+13+5+1=114/114，零失败错误skip、退出0，21:53:53 UTC。
- 补接既有定时清理后R03/session68034仅重验TaskCleanupServiceTest,ProductionCleanupTest,CreatedTaskCancellationHttpTest：14+5+1=20/20，零失败错误skip、退出0，21:56:30 UTC；复用R02未变化的95项。标准target/surefire-reports七套报告保留各自R02/R03 runLog及实际方法执行，新增rollback三参数、charge失败三参数、同ID重建/去重、legacy两参数日期/默认runtime、累计溢出、TTL方法均通过。真实Cookie/CSRF/TCP创建→取消→重复取消→物理删除404→再创建429，非mock网络；磁盘冷读、其他owner与session回退、凭据无原始凭证、删前写失败不动metadata/events/canary及TTL删后仍计费已验证。日期由既有now参数推进，不冒称真实跨午夜或整进程重启。
- 命令沿用Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task20-daily-r0N.log" "-Dtest=对应选择器" test；unit wjc-task20-daily-r0N-20261007，R01/R02/R03实际active InvocationID依次eff69f96745d4fe2aea522833f586bfd、841203cf2e6e48718d8281d775191ae1、3fc822f114bc481d9838acb54d8b184b，现场核对同一4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及2GiB隔离/tmp；R02/R03终态内存峰值416.7M/371.1M（systemd输出），swap0。无全量/冻结/旧Probe/Runner重复实验，无共享Docker操作。
- 20仍BLOCKED：18/19完整恢复/残余执行/占用重建、启动429/重试交付验收及本地C01未完成，21派发许可不扩入20；本阶段为功能progress，不关闭完整DEF-15。未暂存/提交/push，不标DONE，保留IDE改动，Goal active/6-30；只更新task/work，文档不触发产品重测。

### 2026-10-06 UTC（日志2026-10-07 +08:00）｜ASCII分词与词法误判 [TASK-15-LEXICAL-R02]

- 前轮为功能progress（20每日计费/TTL）；原Goal、HEAD799fc1cb和空暂存已核对，11/12/15未批准阈值不代设。依RQ-C01、DEC-03、D §5.1只改JudgeService原普通比较：六种ASCII空白提取token，保留NUL/其他控制字符，十进制词法之外按原文本精确比较；整数/非有限切片、precision、SPJ及provider不改。复用原JSON fixture及Baseline，新增11向量和一个真实C++用例，不建通用比较器或另一套工具。
- R01/session74126，选择器JudgeBaselineTest#ordinaryComparisonFollowsApprovedIntegerAndLiteralRules+aRealNulOutputCannotBeIgnoredAsWhitespaceAndReportedAccepted：35项，27过/8失败/0错误skip、退出1，22:10:13 UTC（Maven06:10:13+08:00）。7个控制字符/Java专属数值词法向量及真实NUL stdout本应WA却AC；原XML/runLog /tmp/wjc-task15-lexical-r01.log实际核对。查询时unit已collect，未取得实际InvocationID/隔离属性，不用默认值补证、不重跑旧红。
- 修复后R02/session66658，仅JudgeBaselineTest：44/44、零失败错误skip、退出0，22:13:13 UTC（Maven06:13:13+08:00）；标准target/surefire-reports实际34参数及NUL方法/runLog /tmp/wjc-task15-lexical-r02.log已核对。真实C++生成NUL+7换行，结果WA/completed=1/wa=1/ac=0，文件详情仍为NUL+7换行、oracle为7换行；既有ASCII空白、整数、NaN及真实执行/下载回归均通过。控制字符不是模拟输出；HTTP夹具为原MockMvc，不冒称浏览器、Python/Worker或端到端精度验收。
- 命令Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task15-lexical-r0N.log" "-Dtest=对应选择器" test；R02 unit wjc-task15-lexical-r02-20261007、实际active InvocationID e7392a7770f24c40a75618ac09d2fdd9，现场确认原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及2GiB隔离/tmp，终态内存峰值499.3M（systemd输出）/swap0。git diff --check通过，未全量/冻结/旧Probe，不操作共享Docker；20等未受影响证据复用。15仍BLOCKED于精确小数/API precision、严格解码及待批token/指数/错误映射，跨语言留16；未暂存/提交/push，不标DONE，IDE改动保留，Goal active/6-30。仅更新task/work，文档不重测产品。

### 2026-10-06 UTC（日志2026-10-07 +08:00）｜双流合计与OLE终态 [TASK-13-COMBINED-R03]

- 核对原Goal active、HEAD799fc1cb和TASK-10实际C01 a9d2e117后继续13，不重复旧Probe或取消红测。依RQ-R06/AC-R06a、D §4.5，只改task_runner.py合计检查/前缀截断和JudgeStatus既有枚举/别名；复用原StageDispatch、Artifact及Ingestor夹具，无新抽象、阈值或验证框架。
- R01/session70898：真实C++ stdout2049+stderr2048、限额4096，原实现误AC，1失败/0错误skip、退出1，22:50:40 UTC；修合计后R02/session96899为11过/1失败/0错误skip、退出1，22:52:16 UTC，OLE计数已正确但现有终态判定不识别OUTPUT_LIMIT_EXCEEDED。直接补齐映射，未放宽断言或重复复现。
- R03/session43178：Artifact12、Ingestor63、FileTaskStore64、Cleanup14，共153/153、零失败错误skip、退出0，22:56:49 UTC；四套标准target/surefire-reports XML均带/tmp/wjc-task13-combined-r03.log标识，真实双流方法及两个OLE名称冷读参数实际执行。Python命令直接返回，无持续session：两套unittest共22/22、零失败错误skip、退出0，含stdout/stderr/混合limit-1、limit、limit+1及两流各超限十组subTest。真实Python/C++、事件解析和文件存储执行，只有消息传输mock；不冒称容器/Worker全链或整进程恢复。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task13-combined-r0N.log" "-Dtest=对应选择器" test；R01为新Artifact方法、R02仅Artifact、R03上述四套。Python命令python3 -B -m unittest -v test_task_runner_contract test_task_runner_stage_dispatch。unit wjc-task13-combined-r03-20261007，active InvocationID 2f34b64ff3d24e17872b80a8b28a79ac，实际核对原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、只读源码仅target可写和2GiB隔离/tmp；Python unit wjc-task13-combined-python-r03-20261007，同限制命令、InvocationID e6c08730aa0047ceb8a92dfd058f5fc0，未取得active属性不补默认值。R01查询时已collect，同样不补证、不重跑。未操作共享Docker。
- 13仍BLOCKED：全量communicate、无界退出drain、所有阶段错误映射及真实内存/磁盘上界未闭环，12/14和诊断/收尾审批留原卡；合计返回前缀有界不等于运行内存有界。git diff --check通过，未暂存/提交/push、不标DONE，IDE改动保留；Goal active/6-30。只同步task/work，文档不触发重建或重测。

### 2026-10-06 UTC（日志2026-10-07 +08:00）｜在线捕获与双语言预算 [TASK-13-STREAM-R02]

- 前轮为功能progress，续原Goal/13、HEAD799fc1cb及空暂存。只改task_runner.py原捕获、DirectProcessRunner原额度和三个既有测试：双流并行固定块读取、合计/保留前缀共享预算，Python锁内累计饱和及后台IO错误传播，Java原捕获器用AtomicLong共享额度并保留独立stderr上限；沿用身份、控制文件、终止器、超时和原额度，无新管理器/调度/依赖。
- Python两套R01/session49806为24/24（含十组额度边界、三组超额后持管道及双向大输入/早闭stdin）；后来仅测新增IO故障1/1，真实子进程管道注入异常正确抛出，未重复24项。Artifact R01/session96548为12/12，23:15:43 UTC；强化原C++用例为双流4097字节后持管道10秒，原case预算2秒内在线OLE、不等EOF，实际v2事件验真和文件摘要通过。Java R01/session75918六参数4过/2失败、退出1，23:20:44 UTC，两个独立流均未超限却合计超限被SUCCESS；最小修复后R02/session95187为ProcessRunner15+Baseline44=59/59、23:23:19 UTC。所有绿测零失败/错误/skip、退出0，三个标准Surefire XML及关键方法/runLog已核对；Java进程夹具只mock内存采样，不当作真实RSS验收，Artifact仅消息传输mock。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task13-stream-r01.log或/tmp/wjc-task13-java-r0N.log" "-Dtest=对应选择器" test；Artifact仅原套件，Java红仅新方法、绿为ProcessRunnerTest,JudgeBaselineTest。Python为python3 -B -m unittest -v原两模块，后仅新增IO方法。stream-r01/java-r02 unit分别wjc-task13-stream-r01-20261007/wjc-task13-java-r02-20261007，实际active InvocationID ee8da4f101f949989ae5c78e8806812f/c0b25c0e4e9f406ea1571778fc245ee6；核对同一4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写、2GiB隔离/tmp。复用未受影响R03持久化/清理证据；不重跑全量/冻结/旧Probe，不操作共享Docker。
- 仍BLOCKED：杀后wait/管道join未有界，完整阶段映射、产品RSS/磁盘上界及适用平台验证仍缺；12/14与诊断/收尾批准留原卡，单元峰值不替代产品资源证明。本阶段为实际功能progress，未暂存/提交/push、不标DONE，保留IDE改动，Goal active/6-30；仅更新task/work，文档不触发产品重测。

### 实际日志2026-10-07 +08:00 / 2026-10-07 UTC｜阶段OLE闭环 [TASK-13-STAGE-R02]

- 沿用原Goal/HEAD799fc1cb，TASK-10实际C01 a9d2e117已核验，不返工；依RQ-R06/AC-R06a、D §4.5，仅修task_runner.py生成器/答案/SPJ的OLE分类和编译诊断原因，JudgeService答案/SPJ复用原generatorFailureStatus。生成器有界前缀落盘、不派发后续角色；编译仍退出10且不补造用例，身份/控制/预算和原数值保留。回归复用StageDispatch、Artifact、Baseline，无新抽象/工具。
- R01/session61462、unit wjc-task13-stage-r01-20261007：6项1过/5失败、零错误/skip、退出1，完成2026-10-07 07:55:04+08:00＝2026-10-06 23:55:04 UTC；生成器退出40、答案System Error、Python SPJ WA、编译无超限原因、Java SPJ System Error，USER原正确。查询时已collect，不以默认属性补隔离证据，不为补证重跑。
- Python R02/session89354、unit wjc-task13-stage-python-r02-20261007，命令python3 -B -m unittest -v test_task_runner_contract test_task_runner_stage_dispatch：27/27、零失败/错误/skip、退出0；含四角色/首错策略参数化、编译原因及既有捕获/IO/取消/预算回归。Maven R02/session1110、unit wjc-task13-stage-r02-20261007，命令mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task13-stage-r02.log" "-Dtest=TaskRunnerArtifactContractTest,JudgeBaselineTest" test：Artifact16+Baseline46=62/62、零失败/错误/skip、退出0；标准target/surefire-reports两份XML确认四角色、编译诊断、两Java参数实际执行及当轮标识，完成2026-10-07 08:02:45+08:00＝2026-10-07 00:02:45 UTC，不沿用上一轮UTC日期。
- Maven实际active InvocationID64655eadeafb4eec8dbb5e22d7c006d8；现场核对原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、ProtectSystem=strict/ProtectHome=read-only/NoNewPrivileges、源码只读仅target可写及隔离/tmp 2GiB。534.1M是整个测试单元峰值，非产品RSS上界。真实Python/C++、g++、v2解析和存储；传输mock、Java API为MockMvc，不冒称真实TCP/浏览器/容器Worker整链。
- 本阶段为功能progress；非OLE Python SPJ基础设施状态仍误记WA，登记16按原异常映射处理，不扩10/13。13仍BLOCKED于有限退出/drain、14整域回收、产品RSS/磁盘上界和适用平台；诊断/收尾生产值未冻结，不声明资源已回收。git diff --check通过，未暂存/提交/push、不标DONE，保留IDE改动，Goal active/6-30。记录更新不触发重建/重测，未变化证据复用。

### 实际日志2026-10-07 +08:00 / 2026-10-07 UTC｜SPJ基础设施分类 [TASK-16-SPJ-R02]

- 依16原异常映射范围及runner/task-runner-spec.md §Verdict Mapping，核对Java后只在task_runner.py原run_special_judge加非SUCCESS→System Error条件；取消/预算优先、OLE和正常非零退出WA保留。不改普通比较、接口/权限/数值，不并入10/13。原Artifact增加四个真C++参数，覆盖成功AC、拒绝WA、超时/内存失败System Error，核对事件、真实summary与v2入库计数；32MiB/64MiB为既有隔离内的受控夹具，不是生产批准值。自审非独立放行。
- R01/session11875、unit wjc-task16-spj-r01、InvocationID69f19e318bae4bf3b45a5c6acd51c1ad：四项两过/两失败、零错误/skip、退出1；超时和内存两项实际WA，完成08:15:11+08:00＝00:15:11 UTC。事后属性已collect，不把默认值当隔离证据，不重跑补证。Maven选择器TaskRunnerArtifactContractTest#specialJudgeInfrastructureFailuresDoNotBecomeWrongAnswers；修复后R02/session44606选择器TaskRunnerArtifactContractTest：20/20、零失败/错误/skip、退出0，完成08:16:54+08:00＝00:16:54 UTC。
- Maven两轮命令mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task16-spj-r0N.log" "-Dtest=上述选择器" test。R02 unit wjc-task16-spj-r02、实际active InvocationID8c38538ab5324acaa003d4e1c0527451，现场确认4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、严格只读源码仅target可写和/tmp 2GiB，整单元峰值602.1M不代表产品RSS上界。Python unit wjc-task16-spj-python-r02、InvocationIDa9461f72ed0045338b353f9a22ee14c4，命令python3 -B -m unittest -v test_task_runner_stage_dispatch：16/16、零失败/错误/skip、退出0，仅请求同一原隔离配置、未另取active属性。
- 标准target/surefire-reports/TEST-com.example.demo.TaskRunnerArtifactContractTest.xml带R02标识，四SPJ参数、四角色OLE及编译诊断均实际执行；它是当前Python源码对应的最新Artifact报告，前阶段CLI失败/通过历史保留，未变化的Java Baseline46报告仍有效。真实Python/C++/文件解析和存储，消息传输mock，不冒称浏览器/容器Worker/完整回收。本阶段为功能progress；16仍BLOCKED于12/14/15、跨语言精度及适用Worker验收，13剩余预算/域/资源阻断不变。HEAD799fc1cb、暂存为空，未C01提交/push、不标DONE，保留IDE改动；Goal active/6-30。仅更新task/work并检查文档，不重复全量/冻结/旧Probe。

### 2026-10-07 +08:00｜两级派发准入与公平出队 [TASK-21-ADMISSION-R03]

- 上轮为功能progress；续原Goal/HEAD799fc1cb，20整体前置仍阻断，推进21不依赖新数值的准入切片。仅改JudgeScheduler：复用monitor/runningTasks，可信owner快照共享原maxRunningTasksPerUser预算，立即执行和出队同一准入，有限队列跳过满额owner，原实例移除保证释放幂等。匿名/缺ownership保守同组，不改权限/创建规则/默认值，不新增管理器、用户Map或锁。原Scheduler测试补四参数，旧全局测试明确独立owner而不放宽断言；异常退出finally用既有awaitIdle等待写盘结束，避免JUnit临时目录先删除。自审非独立安全放行。
- R01/session82559、unit wjc-task21-admission-r01，选择器JudgeSchedulerTest#userRunningLimitIsSharedAcrossSessionsAndDoesNotBlockOtherOwners：四项全失败、零错误/skip、退出1，限额1实际预留2、限额2实际预留3，08:25:54+08:00完成。失败时原临时目录与后台写盘交错日志保留，仅补测试收尾，不重跑同一红测。R02/session95893、unit wjc-task21-admission-r02：Scheduler21、Quota13、Controller5、Orchestration13、ProductionCleanup5、CreatedCancellationHttp1、RunningProgressHttp4，共62项60过/零失败/两错误/零skip、退出1，08:29:31+08:00完成；错误均为两旧负例反射私有TaskContext三参构造，准入四参数及六套其他消费者已绿。
- 仅将JudgeSandboxOrchestrationTest原反射夹具适配四参/匿名owner，业务和产品源码未再改。R03/session57925、unit wjc-task21-admission-r03、InvocationID2a52d0440e64424a89afcbdd47c1a6be，仅重验该套13/13、零失败/错误/skip、退出0，08:31:42+08:00完成；R02其余49项直接复用，不重跑HTTP/C++/全量/冻结。Maven三轮均mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task21-admission-r0N.log" "-Dtest=当轮上述选择器" test；七份标准target/surefire-reports XML保留对应R02/R03标识，确认四准入参数、四原生产交接参数及两反射负例实际执行。三轮日志时间对应当日UTC00:25:54、00:29:31、00:31:42。
- R02实际active InvocationID522d9e4787ee456094ea52b6806004e9，现场核对4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写与/tmp 2GiB；467.4M为整测试单元峰值，非产品上界。真实生产执行器/磁盘证明的是提交作业并发准入，既有真实Cookie/CSRF/TCP/C++链路作回归，不冒称多用户C++峰值、容器Worker、完整进程域回收或重启容量重建。21仍BLOCKED于20及14/19的域确认、清理失败保留占用和恢复，当前finally不能当作整域已停；不关闭DEF-15、不标DONE、未暂存/提交/push，IDE改动保留，Goal active/6-30。本阶段为实际功能progress；仅同步task/work，文档不触发产品重测。

### 2026-10-07 +08:00｜真实多用户C++与共享用例池最小修复 [TASK-21-CPP-R05]

- 上轮为功能progress；续原Goal/HEAD799fc1cb，复用RunningProgressHttpTest、Cookie/CSRF/TCP设施补必要真实验收，不搭另一套Probe。同账号两个会话、另一账号两任务先真实创建再启动；受控C++写自身PID后等待测试输入放行，阶段观测实际存活/退出与队列，四任务需实际AC，finally真实取消剩余并等待占用归零。全局2/每用户1/每任务Case1及Case15秒是隔离夹具，不是新生产批准；不写可信取消标记或伪造终态。自审非独立安全放行。
- R04/session99803、unit wjc-task21-http-r04、InvocationIDaf20d2a1339145118a06cb08d7f02e27：HTTP5项4过/1失败、零错误/skip、退出1，08:47:32+08:00＝00:47:32 UTC；另一账号other-first.pid十秒未出现，原四项回归仍绿。核实AsyncConfig共享Case池仅按单任务并发1配置，阻塞其他已获全局许可任务，归本卡实际公平派发，不扩10。最小产品修改仅两行：Case线程数取原全局×每任务上限，Math.multiplyExact拒绝溢出，原队列/CallerRunsPolicy和CaseBatchRunner局部在途预算不变，默认全局1容量不变；不新增管理器、配置或权限。
- R05/session76567、unit wjc-task21-http-r05，选择器RunningProgressHttpTest,CaseBatchRunnerTest：5+9=14/14、零失败/错误/skip、退出0，08:58:39+08:00＝00:58:39 UTC。标准target/surefire-reports两份XML带/tmp/wjc-task21-http-r05.log标识，关键真实方法及四原HTTP参数实际执行；USER PID owner66292→66392、other66300→66346，三次交接均两账号各一个实际程序存活，旧同owner程序退出后才新程序开始，满额owner队首未阻塞other-next，四个实际completed1/ac1、末尾running/queued归零。Batch9保留十万用例在途上界、停止/乱序/边界回归；未改业务或资源断言换绿。
- Maven两轮均mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task21-http-r0N.log" "-Dtest=当轮选择器" test；R04仅HTTP、R05上述两套。R05实际active InvocationID911d5f9eb6054ad199afc28240c54951，R04/R05现场均核对原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及/tmp 2GiB；R05整单元峰值1.0G非产品RSS上界。产品实际HTTP/C++路径绿，不以本次良性单进程退出代替恶意进程域/容器Worker/Windows/重启证据，不操作共享Docker。21仍BLOCKED于20及14/19域确认、清理失败占用与恢复；不标DONE，未暂存/提交/push，IDE改动保留，Goal active/6-30。本阶段为产品修复及关键真实证据progress；未受影响R02/R03证据复用，仅同步task/work，不再全量/冻结或因文档重测。

### 2026-10-07 +08:00｜已知句柄清理失败保留运行许可 [TASK-21-RETENTION-R02]

- 复用上一阶段已有红测，不再搭Probe：R01/session21439、unit wjc-task21-retention-r01、InvocationID9c88cf4f6daa47f5a5e5b4d92d674bff，原Orchestration方法两参数1过/1失败、零错误/skip、退出1，09:18:52+08:00＝01:18:52 UTC；实际cleanup抛异常后expected running1/actual0。
- 最小产品修复仅JudgeService/JudgeScheduler：已知handle清理异常标记原TaskContext，原monitor/runningTasks释放入口保留同实例的全局及owner占用、不派发后继；正常成功仍原幂等释放。原Orchestration参数覆盖成功/失败，真实单线程barrier保证finally已结束，检查完整落盘handle、失败running1及新任务QUEUED；无新管理器、Map、锁、恢复消息或配置，自审非独立安全放行。
- R02/session56559、unit wjc-task21-retention-r02，Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task21-retention-r02.log" "-Dtest=JudgeSandboxOrchestrationTest,JudgeSchedulerTest,QuotaAndAuthorizationTest,ProductionCleanupTest,RunningProgressHttpTest" test：14/21/13/5/5，共58/58、零失败/错误/skip、退出0，09:26:52+08:00＝01:26:52 UTC。五份标准target/surefire-reports XML带同轮runLog，两清理参数及真实多用户方法实际执行；真实USER PID owner68459→68559、other68467→68513，两级峰值/队首跳过及四AC仍绿。
- active InvocationIDe15d8c1e54164a3bab00f6341b77ae4e，现场核对原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、只读源码仅target可写与/tmp 2GiB；整单元峰值459.7M非产品资源上界。清理失败是原Runner替身注入，真实C++是trusted-local正常/取消/预算回归，不冒称恶意域或生产provider清理。仍BLOCKED：正常Runner终态/未知start的停止确认、cleanupResidual默认void不足、真实回收后占用释放及持久恢复归14/19/20；未C01、不标DONE、不关闭DEF-15，Goal active/6-30，未暂存/提交/push。只同步task/work、不全量/冻结，已有未变化证据复用；本阶段为实际功能progress。

### 2026-10-07 +08:00｜Toast DOM顺序与启动HTTP检查 [TASK-22-START-R02]

- 推进22不依赖新数值的独立切片，不扩10/21。DEF-17附录已有真实null守卫失败依据，不重复旧浏览器红测：index.html仅将原Toast节点移至脚本前，保留样式/ARIA/textContent；FrontendTemplateContractTest复用原套件参数化检查成功/错误节点先于捕获。R01/session3129、unit wjc-task22-toast-r01，21/21、零失败/错误/skip、退出0，09:31:18+08:00＝01:31:18 UTC。
- 核对现有Controller的start成功为HTTP200纯文本，不编造响应字段。原connectAndSubscribeAsync不再发送start，submit中单一POST检查startResponse.ok后才启用降级轮询；HTTP拒绝走原按钮/进度收尾，提交前显示准备中/正在提交，不误标运行。只改index.html和既有FrontendTemplateContractTest，无HTTP管理器、另一套状态机或测试框架。R02/session34120、unit wjc-task22-start-r02、InvocationID569150c3acac455487c079e0d200e2af，22/22、零失败/错误/skip、退出0，09:34:54+08:00＝01:34:54 UTC；标准XML同轮runLog，两Toast参数及启动检查实际执行。
- 两轮Maven均mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task22-当轮.log" "-Dtest=FrontendTemplateContractTest" test；实际runLog分别wjc-task22-toast-r01.log/wjc-task22-start-r02.log，沿用既有systemd隔离参数。R01属性读取发生collect后，不将默认值当证据、不为补观测重跑；整单元峰值不宣称产品上界。浏览器环境既有IAB/Edge认证阻断未变化，不绕过权限或发展工具；源码契约不证明Toast实际可见或真实HTTP/WS交互，具体错误/原ID重试/未知网络/通道生命周期及兜底仍未闭环。22仍BLOCKED于05/06/18及上述项，不C01、不DONE、不关闭DEF-16/17；原Goal active/6-30、无暂存/提交/push。本阶段实际功能progress，只同步task/work，不新增文档、全量/冻结，未受影响21真实绿测复用。

### 2026-10-07 +08:00｜原ID重试与未知结果对账 [TASK-22-RETRY-R04]

- 上一Goal轮为progress；续原Goal/HEAD799fc1cb，仅改index.html与原FrontendTemplateContractTest。原currentJudgeId加待启动标记，重试先status、仅CREATED才start，不再创建另一任务；刷新CREATED可恢复，输入验证失败不清历史。补JSON Accept和具体HTTP提示，已知ID网络/5xx先对账，无ID创建未知只明确提示；接受响应不覆盖已收到终态。自审补区分start/status/create，查询429不虚报未启动，查询失败不立即重复同次对账；无新API、管理器或状态机。
- R03/session27089、unit wjc-task22-retry-r03，29/29、退出0，09:47:01+08:00；自审相关源码变化后R04/session31357、unit wjc-task22-retry-r04，29/29、退出0，09:49:55+08:00，两轮零失败/错误/skip。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task22-retry-r0N.log" "-Dtest=FrontendTemplateContractTest" test。Node vm只检查实际主脚本语法及直接调用429三请求阶段消息函数，均通过；不是浏览器/真实请求替代。
- 沿用既有隔离命令参数，本轮未观察active属性，不补采collect默认值、不重跑旧浏览器阻断。22仍BLOCKED于05/06/18及必要浏览器/交付验收，未关闭DEF-16/17、未C01；Goal active/6-30，无暂存/提交/push、不全量/冻结。本阶段实际功能progress，未受影响后端证据复用。

### 2026-10-07 +08:00｜原任务取消与旧通道退出 [TASK-22-LIFECYCLE-R06]

- 继续最小需求实现：原ID取消调用既有Cookie/CSRF接口，校验业务响应后再status，accepted不当作停止/清理成功；加入实例/ID检查隔离旧恢复、启动、取消、WS及轮询响应。复用原连接和轮询函数，切换/失败/终态/退出清旧计时器、abort在途轮询、关闭自身socket；串行轮询沿用原1秒间隔及既有重连退避/次数，不加生产参数。Toast缺组件复用原DOM/textContent可见且可关闭兜底，QUEUED按真实状态显示排队。无新通用管理器/调度框架/依赖。
- R05/session36938、unit wjc-task22-lifecycle-r05，共32项31过/一失败、零错误/skip、退出1，10:00:20+08:00；唯一失败是旧完整active代码块未包含新QUEUED文案。保留原业务断言并扩入完整新分支，仅改夹具；R06/session19921、unit wjc-task22-lifecycle-r06、InvocationID22abc8f412474d599692192fe1183a0b，单项1/1、零失败/错误/skip、退出0，10:03:48+08:00，其他31项有效绿测直接复用。Maven命令同R04，runLog分别/tmp/wjc-task22-lifecycle-r05.log及r06.log；R05选择器整套FrontendTemplateContractTest，R06仅#refreshPreservesRunningUiAndFlushesCodeBeforeLeavingPage。标准XML已核对关键新增项与单项实际执行，最新XML为R06单项，不伪称一轮32全绿。
- 实际主脚本语法通过；当前HTML源码与target/classes模板SHA256均F723B29E1DE894DC03AA7EECEE64CD79184F6D9F99CD99C7BE45A9F84A589A6D，只核对本次变化一次、不冻结产品。测试命令请求原隔离资源配置，未观察active属性、不将整单元峰值当产品上界。浏览器环境阻断未变，契约/语法不证明真实可见性、网络取消或通道资源回收；22及DEF-16/17不DONE/不关闭，原依赖及必要真实/交付验收仍阻断。仅同步task/work，Goal active/6-30，保留IDE改动，无暂存/提交/push、不新增文档或重复后端/全量/冻结。本阶段实际功能progress。

### 2026-10-07 +08:00｜清理失败保留占用计入创建配额 [TASK-20-HELD-QUOTA-R02]

- 最小修复JudgeScheduler/QuotaService/JudgeService：原owner创建原子区内取可信owner调度运行ID快照，释放原monitor后扫描磁盘，用局部Set合并去重；终态但已知cleanup失败仍占配额，不改变每日口径、匿名内部契约、生产数值或权限，无新管理器/锁/持久许可表。仅扩原JudgeSandboxOrchestrationTest成功/失败参数与重复start接缝：同账号双会话共享限额、失败拒绝且不落盘、成功及另一owner可创建、相同运行ID不双计。
- R01/session78084、unit wjc-task20-held-quota-r01，选择器JudgeSandboxOrchestrationTest#pollFailureUsesHostErrorPathAndCleansTheStartedHandle+repeatedStartOfRunningTaskDoesNotStartAnotherSandboxRun；3项2过/1失败、零错误/skip、退出1，10:28:16+08:00。唯一红项为cleanup失败后创建未抛配额异常；已有证据不另搭复现。R02/session89519、unit wjc-task20-held-quota-r02，修复后128/128、零失败/错误/skip、退出0，10:35:05+08:00。
- R02标准XML八套已核对：JudgeSandboxOrchestrationTest14、JudgeSchedulerTest21、QuotaAndAuthorizationTest13、FileTaskStoreTest64、JudgeControllerPolicyTest5、CreatedTaskCancellationHttpTest1、RunningProgressHttpTest5、ProductionCleanupTest5；关键三个配额回归、真实创建取消和真实多用户C++实际执行。均logging.file.name=/tmp/wjc-task20-held-quota-r02.log；报告target/surefire-reports/TEST-com.example.demo.<套件>.xml。真实CPP输出ownerPids=71260/71360、otherPids=71268/71314、ownerLimit=1、globalPeak=2、blockedHeadSkipped=true、ac=4；非清理失败或恶意域证明。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task20-held-quota-r02.log" "-Dtest=上述八套" test；R01仅上述方法选择器、独立同名runLog。
- R02 active InvocationID ba943734cc634f359602f5e6726136cc；实际观察原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及/tmp 2GiB。整单元峰值976.2M不是产品RSS上界。清理失败仍由原Runner替身注入；14/19/21真实停止确认、回收后释放和进程重建未闭环，20仍BLOCKED于18/19及完整交付，不C01、不DONE、不关闭DEF-15。Goal active/6-30，保留IDE改动，无暂存/提交/push、全量或冻结；本阶段为实际功能progress，文档不触发重测。

### 2026-10-07 +08:00｜预算执行器拒绝纳入原异常收尾 [TASK-21-BUDGET-R02]

- 原JudgeSchedulerTest新增单一接缝：本对象预算执行器关闭后拒绝提交，job不得调用；先运行/后排队的同owner双会话任务均SYSTEM_ERROR，全局/owner预留依次释放并交接，无实际运行域，不伪造清理成功。R01/session10620、unit wjc-task21-budget-r01，1项失败、零错误/skip、退出1，10:45:46+08:00；RejectedExecutionException从原try外scheduleBudget直接逃逸。只改JudgeScheduler两行，将预算提交移入原try/finally，复用CompletedScheduledFuture及原终态/释放入口，无新抽象、数值或框架。
- R02/session54794、unit wjc-task21-budget-r02，选择器JudgeSchedulerTest,JudgeSandboxOrchestrationTest,RunningProgressHttpTest#realCppTasksShareOwnerPermitsAcrossCookiesAndSkipBlockedQueueHeads；22+14+1=37/37、零失败/错误/skip、退出0，10:47:25+08:00。三份标准XML已核对logging.file.name=/tmp/wjc-task21-budget-r02.log及关键预算拒绝、清理成功/失败、重复start、真实CPP方法；当前HTTP报告仅1项，不宣称原5项重跑。真实ownerPids=72962/73066、otherPids=72970/73018、ownerLimit=1/globalPeak=2/blockedHeadSkipped=true/ac=4，原Runner清理失败占用仍保留。命令沿用mvn -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task21-budget-r02.log" "-Dtest=上述选择器" test；R01仅新增方法和独立同名runLog。
- R02 active InvocationID074592891c5a4916845dbbd63e292a50，实测原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、ProtectSystem=strict、源码只读仅target可写及/tmp 2GiB；整单元峰值295.3M非产品上界。R01属性读取时已collect，不把返回默认值当证据或为此重跑。21仍BLOCKED于20及14/19真实全域停止/回收后占用恢复；未C01、不DONE、不关闭DEF-15，Goal active/6-30，无暂存/提交/push、全量/冻结或新文档。本阶段为实际功能progress。

### 2026-10-07 +08:00｜执行器拒绝后落盘失败仍释放预留 [TASK-21-REJECT-WRITE-R02]

- 属21原异常/许可无泄漏范围，不扩10或持久化架构。原Scheduler套件两参数覆盖终态写盘成功/IOException：执行器未接纳job，同owner双会话两个任务的拒绝仍向调用方传播、running/queue归零、job调用0；写失败保持磁盘QUEUED，不假报终态持久成功。R01/session94035、unit wjc-task21-reject-write-r01，2项1过/1失败、零错误/skip、退出1，10:50:25+08:00；写失败时running=1证明漏释放。JudgeScheduler原execute拒绝分支仅为persistStatus加try/finally调用原释放入口，不吞异常，不另建清理或调度机制。
- R02/session17178、unit wjc-task21-reject-write-r02，JudgeSchedulerTest24/24、零失败/错误/skip、退出0，10:51:32+08:00；标准XML及两个拒绝写盘参数、预算拒绝项已核对，logging.file.name=/tmp/wjc-task21-reject-write-r02.log。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task21-reject-write-r02.log" "-Dtest=JudgeSchedulerTest" test；R01只选#rejectedExecutionReturnsPermitsEvenWhenTerminalPersistenceFails及独立同名runLog。改动仅拒绝分支，未受影响BUDGET-R02编排14和真实HTTP/C++1直接复用，不重跑正常域验收/全量/冻结。
- R02 active InvocationID75a8bb621e1e442ebcfe0a224cb3b63c，实测原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、ProtectSystem=strict、源码只读仅target可写及/tmp 2GiB；整单元峰值291.1M非产品上界。已启动域清理失败保留不变，14/19全域停止与恢复、20完整前置/交付仍阻断21；本切片不替代17原子终态故障验收。21不C01/不DONE、不关闭DEF-15，Goal active/6-30，保留用户/IDE改动，未暂存/提交/push。本阶段实际功能progress，仅同步现有task/work，文档不触发重测。

### 2026-10-07 +08:00｜正常终态统一清理且取消应答不冒充停止 [TASK-14-TERMINAL-CLEANUP-R03]

- 上一Goal轮为实际progress；沿用原Goal/HEAD799fc1cb，转14原停止确认边界。JudgeService原cleanupStartedHandle移入finally，已返回句柄的正常/停止/异常均清理；失败只设原cleanupPending，不覆盖真实AC/统计或释放占用。SandboxRunner默认cancel之后显式报stop未确认；LocalFake及原高容量纯事件替身显式清理本地状态，它们不启动OS域，不扩权限/数值/框架。Remote不可用缓存回归保留原所有隔离断言并增加未确认停止错误，不假报远端回收。
- R01/session12366、unit wjc-task14-terminal-cleanup-r01，原AC成功/失败清理两参数及回环HTTPS取消200/202/204/404/409五参数共7项全红、零错误/skip、退出1，11:00:34+08:00；前者未调用清理，后者把取消应答当成功。修复后R02/session34593、unit wjc-task14-terminal-cleanup-r02，编排15、调度24、Remote36、ProductionCleanup5、TaskCleanup14、ProductionHighVolume2，共96项95过/1失败、零错误/skip、退出1，11:03:57+08:00。关键七项及原poll清理保留已绿，唯一失败为高容量旧v1替身没有v2句柄/attempt/序号，既有Expected WA断言不放宽；只补原替身身份/序号，不扩测试工具。R03/session90675、unit wjc-task14-terminal-cleanup-r03，仅#productionRunnerChainHandles100To100000CasesWithoutLinearFuturesPayloadsOrSamples，1/1、零失败/错误/skip、退出0，11:06:14+08:00，余95项复用；当前高容量XML仅1项，不伪称一轮96全绿。
- 标准XML六套及关键方法已核对；R02 logging.file.name=/tmp/wjc-task14-terminal-cleanup-r02.log，R03对应r03.log。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task14-terminal-cleanup-r0N.log" "-Dtest=当轮上述选择器" test。R02 active InvocationIDd2d3eeb9d2834171b1d37776aa651bc6，实测原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、ProtectSystem=strict、源码只读仅target可写及/tmp 2GiB；整单元峰值325.8M非产品上界。R03属性读取时已collect，不把默认值当证据或补重跑；100/10000/100000例为原事件替身链路，不是实际Runner吞吐。真实HTTPS证明取消应答非确认，不证明外部Worker域已停。
- 14仍BLOCKED：12/13及目标域/清理预算、真实父退出/setsid/持管道全域回收、stage/holder/volume及回收后占用释放；无句柄/unknown启动和不可用fallback身份区分归12，持久隔离/进程就绪归19。本次不C01、不DONE、不关闭DEF-08，Goal active/6-30；保留用户/IDE改动、无暂存/提交/push，不重跑旧10 Probe、Direct真实CPP、全量或冻结。本阶段为实际功能progress，只同步task/work。

### 2026-10-07 +08:00｜无句柄未知启动保留已有运行占用 [TASK-12-UNKNOWN-START-R02]

- JudgeService原finally增加startRequested与handle空值检查，复用cleanupPending隔离已调用start但throw/null的未知结果，不假造runId或停止确认；启动前实际源码写入失败不保留。仅改原编排及其三参数回归，无新接口/锁/参数/框架。R01/session36063、unit wjc-task12-unknown-start-r01，3项全失败、零错误/skip、退出1，11:09:52+08:00；throw/null为真实running=0红测，before-start的null源码仍会写空文件，夹具未真正阻止启动。只将该夹具改为generator.cpp目录导致真实文件写入失败，原断言不放宽，不重跑红测。
- R02/session46827、unit wjc-task12-unknown-start-r02，编排18/18、零失败/错误/skip、退出0，11:11:49+08:00；标准XML核对三个未知/未启动参数及原AC/清理成功失败项，logging.file.name=/tmp/wjc-task12-unknown-start-r02.log。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task12-unknown-start-r02.log" "-Dtest=JudgeSandboxOrchestrationTest" test；R01仅#unknownStartOutcomeRetainsPermitsButPreStartFailureDoesNot及独立同名runLog。未受影响14-R02的HTTPS/存储/调度及R03高容量证据复用，不全量/冻结。
- active InvocationID23739340c485412d957fda8589ad14f0，实际原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、ProtectSystem=strict、源码只读仅target可写及/tmp 2GiB；整单元峰值485.4M非产品上界。启动throw/null是原Runner替身，不冒称真实未知域已回收；保留仅进程内，12显式对账/总deadline、14真实整域停止及19持久重建仍阻断，不C01/不DONE、不关闭DEF-03。Goal active/6-30，保留用户/IDE改动，无暂存/提交/push；本阶段实际功能progress，仅简短同步task/work。

### 2026-10-07 +08:00｜残余回收失败不迁移状态且中止初始化 [TASK-19-READINESS-R02]

- 上一Goal轮为功能progress；沿用原Goal/HEAD799fc1cb，仅改TaskCleanupService与原ProductionCleanupTest。原所有已存句柄统一清理，有失败先抛异常、STALE写入后置；PostConstruct不再吞失败。七状态参数验证真实Spring初始化失败/上下文不活跃，metadata及case数据逐字节不变，原状态/句柄保留。Runner失败仍为替身注入，未假报实际域停止；无新锁/管理器/参数/接口。
- R01/session12907、unit wjc-task19-readiness-r01，7项全失败、零错误/skip、退出1，11:27:38+08:00；旧代码未抛错继续初始化，QUEUED/RUNNING已先迁移STALE。最小修复后R02/session8464、unit wjc-task19-readiness-r02，ProductionCleanup12+TaskCleanup14+CreatedTaskCancellationHttp1=27/27、零失败/错误/skip、退出0，11:31:13+08:00。标准XML及七参数/真实Cookie-CSRF-HTTP方法已核对，logging.file.name=/tmp/wjc-task19-readiness-r02.log；Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task19-readiness-r02.log" "-Dtest=上述三套" test；R01仅#failedResidualCleanupPreventsSpringReadinessAndPreservesTaskState及同名独立runLog。
- R02 active InvocationID829410c257ec4fce87cb4263cdcbabe7，原隔离资源配置已实测核对（4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、只读源码仅target可写及/tmp 2GiB）；整单元峰值620.5M非产品上界。19仍BLOCKED于14/17/18及五非终态安全收敛、无句柄UNKNOWN/孤立受管证据隔离、持久占用/final与真实进程恢复，不C01/不DONE、不关闭DEF-14。Goal active/6-30，用户/IDE改动保留，无暂存/提交/push、全量/冻结或新文档；本阶段为实际功能progress。

### 2026-10-07 +08:00｜取消先校验句柄后端归属 [TASK-14-PROVIDER-GUARD-R02]

- 仅在LinuxContainerRunner、WindowsHyperVContainerRunner、RemoteWorkerRunner原cancel入口增加provider匹配检查；对应原测试用空/缺失/外来provider三参数，断言外部命令及真实回环HTTPS取消请求为空。不新增接口、管理器或Probe，保留合法句柄原取消路径；同provider的完整身份/代际回收仍待14。
- R01/session44966、unit wjc-task14-provider-guard-r01，九项全失败、零错误/skip、退出1，11:42:21+08:00。接续既有R02/session63489（未重启测试），unit wjc-task14-provider-guard-r02，Linux22/Windows24/Remote39共85项83过、零失败/错误、2环境skip、退出0，11:45:20+08:00；三份标准XML及九关键参数已核对。skip仅原Linux缺cpp-judge-runner:latest及Hyper-V实际运行域Linux，不计为平台验收；容器执行器仍是记录替身，Remote为原真实HTTPS测试。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task14-provider-guard-r02.log" "-Dtest=LinuxContainerRunnerTest,WindowsHyperVContainerRunnerTest,RemoteWorkerRunnerTest" test；R01仅三套同名参数方法及独立runLog。R02结束已collect，不把默认属性当实测，不为补观测重跑；沿用原隔离测试命令。14仍BLOCKED、不C01/不DONE；Goal active/6-30，未暂存/提交/push，用户/IDE改动保留。本阶段有实际修复与定向绿测，后续恢复归19，不扩10。

### 2026-10-07 +08:00｜覆盖全非终态并阻止缺句柄未知执行就绪 [TASK-19-NONTERMINAL-R02]

- FileTaskStore原恢复入口在已有生命周期锁内预检全部缺句柄任务，非终态/startedAt/受管证据不允许当作停止；缺句柄CREATED/QUEUED也保守隔离。随后用既有isTerminal处理五非终态并记录RESTART_INTERRUPTED。TaskCleanupService拒绝已存空runId，不跳过后续删除防护。只改原两入口和三个原套件；旧纯元数据正例补LocalFake身份/无OS域回收，保留原状态、事件及安全断言，无新接口/管理器/参数。
- R01/session26035、unit wjc-task19-nonterminal-r01，两个原套件方法共15参数全失败、零错误/skip、退出1，11:55:26+08:00；五成功态漏迁移或缺原因，十未知/空句柄/受管证据错误继续初始化。R02/session86077、unit wjc-task19-nonterminal-r02，FileTaskStore64+TaskCleanup14+ProductionCleanup26+CreatedTaskCancellationHttp1=105/105、零失败/错误/skip、退出0，11:57:37+08:00；四份XML及关键22参数已核对。原真实Cookie/CSRF/TCP取消正常；五状态及再次Spring初始化保留metadata/events/summary/case字节，Runner.start不调用。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task19-nonterminal-r02.log" "-Dtest=上述四套" test；R01仅ProductionCleanupTest#startupReconciliationCleansResidualHandlesBeforeMarkingUnfinishedTasksStale+unconfirmedExecutionPreventsStartupAndPreservesTaskEvidence及独立runLog。R02 active InvocationID282ad083d5b5411788c5411c28598f59，原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork/ProtectSystem及源码只读仅target可写、tmpfs2GiB已实测；整单元峰值759.2M非产品上界。上下文再初始化不冒称操作系统进程重启，Recording/LocalFake不冒称实际域停止。
- 19仍BLOCKED：无metadata孤立受管目录、UNKNOWN权威对账/持久占用、17的final及统计一致、14真实域回收和产品进程重启。新增失败关闭不伪造终态或释放占用；未C01/未DONE，Goal active/6-30，用户/IDE改动保留，未暂存/提交/push，不重跑10 Probe、全量或冻结；本阶段有实际功能修复。

### 2026-10-07 +08:00｜无metadata受管残留不允许启动漏扫 [TASK-19-ORPHAN-EVIDENCE-R02]

- 已按本次用户指令即时核读project-goal第3/6节及task第1.1节，替代旧频次/恢复步骤；接续既有session89506，不重开10。FileTaskStore原loadAllTasks只补无metadata目录的受管证据校验，复用原路径/锁防护；原ProductionCleanupTest三参数验证启动锁/attempt阻止Spring初始化，普通未完成创建不阻止，数据/证据保留且不伪造metadata、停止或删除授权。
- R01/session89506、unit wjc-task19-orphan-evidence-r01，3项1过/2失败、零错误/skip、退出1，12:01:16+08:00。R02/session50092、unit wjc-task19-orphan-evidence-r02，FileTaskStore64+TaskCleanup14+ProductionCleanup29+QuotaAndAuthorization13=120/120、零失败/错误/skip、退出0，12:03:09+08:00；四份标准XML及三关键参数已核对。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task19-orphan-evidence-r02.log" "-Dtest=上述四套" test；R01仅#orphanManagedEvidencePreventsStartupButOrdinaryIncompleteCreationDoesNot及独立runLog。原HTTP正常路径证据复用，不重复全量、冻结或10 Probe。
- R02 active InvocationIDde1e63641ca542a29c532ad88da96158，原隔离限额及源码只读/target可写属性已实测核对；整单元峰值698.5M非产品上界。真实Spring/磁盘门槛不是实际容器回收或产品进程重启证明；未出现文件不是完整域无残留证明。同步总台账12/14/18/19/20/21与现有任务卡/实测记录，旧18红测保留于历史而不充作当前状态。19仍BLOCKED于14/17/18、UNKNOWN权威对账/持久占用、final/统计及真实进程恢复，未C01/未DONE；Goal active/6-30，未暂存/提交/push，用户/IDE改动保留，无新文档/框架。本阶段为实际修复和定向绿测，接下来文档仅diff检查。

### 2026-10-07 +08:00｜现有终态快照不可覆盖且持久失败不发布成功 [TASK-17-TERMINAL-SNAPSHOT-R04]

- 前一Goal轮为实际progress；沿用HEAD799fc1cb及最新记录，仅推进17可独立切片，不重开10。FileTaskStore复用原任务锁/路径检查，重复终态不改metadata、message、finishedAt或events，已有终态summary不被晚到值覆盖；ProgressPublisher回读真实快照供发布/缓存，写盘失败传播、不发成功。JudgeService原错误清理放finally；两个无任务用例新增明确失败断言，原不得建目录/启动代码断言保留。自审；未新增final格式/字段/接口/管理器，仍不具备单文件权威提交/崩溃原子性。
- R01/session38217、unit wjc-task17-terminal-snapshot-r01，新增verify matcher重载编译歧义、退出1，12:19:11+08:00，未执行测试不计红测；仅改any(Object.class)。R02/session29978、unit同前缀r02，FileTaskStore3+ProgressPublisher5共8参数全失败、零错误/skip、退出1，12:21:37+08:00。R03/session13583、unit同前缀r03，存储67+发布10+调度24+编排18+高容量2+真实HTTP2+事件63=186项184过/2错误、零失败/skip、退出1，12:26:16+08:00；两错误为不存在任务现在显式拒绝。最小补错误清理并强化原测试后，R04/session96391、unit同前缀r04，编排18/18、零失败/错误/skip、退出0，12:32:25+08:00，余168项复用；不冒称单轮186全绿。七份标准XML、八关键参数及两边界方法均核对。
- R03真实trusted-local C++/HTTP：CANCELLED completed3/ac2/wa1、failureSamples1/slowSamples2；BUDGET_EXCEEDED 17/9/8、failureSamples3/slowSamples2，均coldHttpEqual=true。通知防伪用原SimpMessagingTemplate mock，不能替代真实WS；高容量仍原事件替身，不是实际Runner资源验收。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task17-terminal-snapshot-r0N.log" "-Dtest=当轮选择器" test；R03七套含RunningProgressHttpTest#realMixedResultsSurviveStoppingAndColdHttpRead，R04仅JudgeSandboxOrchestrationTest。R03属性观察时已collect、不作实测或补重跑；R04 active InvocationID6538d6150fb543d097a63a73a9f2ae96，原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及/tmp2GiB已实测，峰值335.1M非产品上界。
- 17仍BLOCKED于11/12、final字段/旧数据兼容未冻结及完整原子提交/恢复；19的final/持久占用/真实进程恢复依赖不因此消失，不C01、不DONE、不关闭DEF-21。Goal active/6-30，无暂存/提交/push、全量/冻结/新文档；用户/IDE改动保留。本阶段有实际修复、定向回归及必要真实绿测，文档后仅diff检查。

### 2026-10-07 +08:00｜捕获故障不伪造超输出 [TASK-13-CAPTURE-IO-R03]

- 沿用原Goal/HEAD799fc1cb，未重开10。DirectProcessRunner仅在原私有StreamCapture携带捕获失败：真实文件IO错误调用原终止器并沿原IOException传播；capture等待错误不再伪造OLE，中断标记与InterruptedException保留。实际TLE/MLE/OLE仍优先，不改额度、预算、接口或全域回收机制；只改运行器及ProcessRunnerTest/JudgeBaselineTest原夹具。
- R01/session83352：两参数原码均未抛出宿主IO异常，2失败/0错误skip，退出1，12:54:58。最小修复后R02/session49096：ProcessRunner17/17、Baseline47项46通过/1失败，合计64项63通过/1失败/0错误skip，退出1，12:59:31；唯一失败是新增验收错误预期公开状态为SYSTEM_ERROR，实际沿原ResultAggregator返回System Error。按原契约修正精确拼写且新增磁盘JudgeStatus.SYSTEM_ERROR断言，未放宽业务或安全要求。
- R03/session76466仅重跑新增真实C++/g++生成器IO项，1/1、0失败错误skip、退出0，13:01:46；其余63项源码/配置未变，复用R02，不冒称单轮64全绿。真实Java子进程正常/睡眠两参数的IO故障均及时抛出且根进程已停止；共享额度六参数、无限输出、TLE/MLE关键项已实际执行。真实判题的completed/systemError=1、OLE/ac=0，结果System Error且磁盘终态SYSTEM_ERROR；HTTP入口为原MockMvc，不冒称真实TCP/WS或整进程域回收。标准证据target/surefire-reports/TEST-com.example.demo.ProcessRunnerTest.xml（17项R02）和TEST-com.example.demo.JudgeBaselineTest.xml（1项R03），R02其余46项及失败保留在本轮命令输出/本记录，无重复归档。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task13-capture-io-r0N.log" "-Dtest=R01新增进程方法/R02两套/R03新增基线方法" test；沿用systemd-run外层4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写、/tmp2GiB及内层540秒timeout。R01/R02 active InvocationID分别e0e995103cb44d7aa286356201fe861b/b9d462029f3044e7ac1b1e736098563d，限制已实测；R03请求同等限制、未补测或重跑。不操作共享Docker；产品RSS/磁盘及12/14有限drain/整域回收阻断不变，13仍BLOCKED、不C01/关闭DEF-07。只同步task卡及work总表13/16现有状态，不更新Goal/交接，不全量/冻结/暂存/提交/push，保留用户/IDE改动；文档后仅diff检查。

### 2026-10-07 +08:00｜终态节流回收与晚到防重建 [TASK-23-THROTTLE-RELEASE-R01]

- 上轮为功能progress，续原Goal/HEAD799fc1cb。复核DEF-22现有源码证据后直接修复，未重复旧红测；ProgressPublisher沿用原ConcurrentMap.compute，将持久化快照与节流决策置于同一judge临界区，终态返回null删除本键，避免在途RUNNING在终态回收后重建条目。只改发布器及ProgressPublisherTest/RunningProgressHttpTest；无新共享字段、接口、管理器、TTL或生产数值，终态仍绕节流，原失败传播/快照冻结不变。
- R01/session20347：ProgressPublisher17+真实HTTP4，共21/21、0失败错误skip、退出0，完成13:17:25。六终态/别名各32个短任务及重复/晚到回调后只剩原活跃键，活跃RUNNING仍节流；同judge八RUNNING与一终态并发后无残留键。原发送故障替身、三晚到快照、两落盘失败回归均执行通过；替身通知不冒称真实WS或发布端发送排序验收。
- 真C++/g++及Cookie+CSRF HTTP：AC/WA各40例，首例摘要、累计分类/样本原断言保持，终态本任务throttle键已释放；取消completed/ac/wa=3/2/1，预算17/9/8，冷HTTP与磁盘逐字段相等，二者throttle键同样不存在。标准证据target/surefire-reports/TEST-com.example.demo.ProgressPublisherTest.xml与TEST-com.example.demo.RunningProgressHttpTest.xml，关键参数及真实观测已核对。Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task23-throttle-release-r01.log" "-Dtest=ProgressPublisherTest,RunningProgressHttpTest#fortyRealCasesPublishBoundedRunningSummaryFromFirstResult+realMixedResultsSurviveStoppingAndColdHttpRead" test；原systemd隔离4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写及/tmp2GiB，内层540秒timeout；active InvocationID1b7a61ac80c64fe4982f8208a4939861限制已实测，测试单元峰值不是产品RSS证明。
- 23仍BLOCKED：17/21/22完整前置、物理清理兜底/非终态TTL容量、有限重试及实际HTTP/WS revision/发送排序未闭环，不关闭DEF-22、不C01。原会话环境AUDIT告警不据此改07未批准策略；06仍依赖03/04，不把探查当实现。仅同步task及work总表22/23既有状态，未改Goal/交接，未全量/冻结/暂存/提交/push，IDE改动保留；Goal active/6-30，文档后仅diff检查。

### 2026-10-07 +08:00｜客户端SEND失败关闭与真实协议回归 [TASK-06-SEND-R02]

- 上轮为功能progress，续原Goal/HEAD799fc1cb，未重开10。最新WebSocketConfig仍无客户端生产进度业务及入站拒绝；仅在原configureClientInboundChannel注册标准ChannelInterceptor，拒绝入站MESSAGE/SEND，覆盖topic/queue/app，不增加身份/连接管理器、共享状态、生产阈值或依赖。未改服务器发布通道、REST接口、Origin配置或既有用户/IDE改动。
- 复用现有Spring6.2.8/Tomcat客户端及CsrfTestSupport，新增原JUnit体系的WebSocketSendHttpTest参数化协议回归。R01/session89330为6项5通过/1失败/0错误skip、退出1，13:50:29；最后一项在创建时被原queued quota=5正确返回429，未进入协议验证，原因是夹具六参数共用同一owner。仅改每个场景独立的服务端夹具身份，不提高配额、不放宽断言；同一夹具补原生入口及XHR轮询后，R02/session81192为12/12、0失败错误skip、退出0，14:02:45。
- 真实原生/ws/websocket、SockJS WebSocket、XHR streaming与polling四入口各对topic/queue/app SEND：真实ERROR返回而非接受普通传输异常冒充拒绝，伪造AC/100未进入观察者消息，拒绝后服务器JudgeProgress JSON DTO仍正常推送。真实TCP、HTTP Cookie/CSRF、broker与协议客户端，无消息mock；会话由产品服务在隔离夹具中创建，未执行C++或证明真实账户登录/浏览器、匿名握手、owner订阅或已订阅撤销。标准证据target/surefire-reports/TEST-com.example.demo.WebSocketSendHttpTest.xml，12关键参数及观测已核对，R01失败保留本轮输出/记录，无新验证框架/报告副本。
- Maven -o -B "-Dstyle.color=never" "-Dlogging.file.name=/tmp/wjc-task06-send-r0N.log" "-Dtest=WebSocketSendHttpTest" test；沿用systemd-run外层4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写、/tmp2GiB及内层540秒timeout。R01 active InvocationID451dac3444c4462ab0803f347e5ab8f7限制已实测；R02请求同等限制，属性查询时已collect返回默认值，不能当作实际无限运行或补重跑。仅核对本地已测试依赖的API/原生路径，不安装/改依赖、访问外网或操作共享daemon。06仍BLOCKED：03/04完整前置、握手/CONNECT Principal与CSRF、精确owner/admin订阅、注销/到期/撤权主动关闭、有界连接/订阅索引及全部启用transport验收未完成；不关闭DEF-04/N1/N3整体、不C01。仅更新task/work，Goal active/6-30，未全量/冻结/暂存/提交/push；文档后仅diff检查。


### 2026-10-07 +08:00｜WS身份绑定与精确订阅 [TASK-06-IDENTITY-R06]

- 已即时核读project-goal第3/6节及task第1.1节，续原Goal/HEAD799fc1cb，不重开10。仅新增WebSocketSessionGuard并接原SecurityConfig/WebSocketConfig：标准HTTP过滤器在SecurityContextHolderAwareRequestFilter之后覆盖/ws及transports，禁自动重复注册，复用resolver/getSession产生稳定Principal；CONNECT/订阅复核活会话，精确主题复用canAccessTask和原文件ID校验，客户端角色声明无效、公开身份只用审计指纹。无新连接管理器、索引、依赖或生产参数，Origin、REST、用户/IDE改动保留。
- 复用同一WebSocketSendHttpTest/Spring客户端真实端口夹具。原生WS、SockJS WS、XHR streaming/polling各验owner/同owner新会话/admin的服务器进度DTO和CONNECTED指纹；各验他人/不存在/通配/多层/queue/app拒绝且伪ADMIN不能越权。四HTTP路径逐验匿名/query-only/未知/过期/冲突为401、Header /info为200；XHR后续xhr_send/xhr/xhr_streaming跨Principal均404，原身份仍204并收到CONNECTED。会话为产品服务隔离夹具、任务为真实HTTP/文件metadata，无消息mock、未运行C++或证明实际账户登录、浏览器及既有订阅撤销。
- 失败保留：R01/session95642总118项，认证42/授权13/启动6通过，WS57因别的夹具残留触发原TASK-19未确认停止门禁而未执行；只用原TestConfiguration/Primary隔离真实FileTaskStore，不删残留/放宽恢复。R02/35157协议57项54通过、3项已ERROR拒绝后客户端DISCONNECT清理竞态；移除拒绝连接的多余DISCONNECT。R03/55940仅订阅28项27通过、1轮询ConnectionLost；只加原客户端关闭观测，普通异常仍失败。R04/76629为夹具WebSocketClient接口编译失败、未执行；补齐两个SDK方法。R05/22484为27通过/1失败，真实轮询3000不等于1002；核对缓存SDK的closeFrameGoAway及D §3.3原ERROR/断开契约，只对轮询同时要求3000与Go away!，其余关闭须1002，SEND原ERROR断言不变。未把普通TransportError当拒绝，未修改产品关闭行为或放宽业务授权。
- R06/session60389定向订阅28/28、零失败错误skip、退出0，14:59:59完成；标准XML逐项核对26 ERROR、轮询wildcard/queue两项真实3000/Go away!。复用R02未受影响29项与R01 61项，不重复全量/冻结。命令Maven -o -B -Dtest=WebSocketSendHttpTest#foreignMissingAndNonExactSubscriptionsAreRejected test，标准证据target/surefire-reports/TEST-com.example.demo.WebSocketSendHttpTest.xml及三个既有报告；沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写、隔离/tmp2GiB、内层540秒timeout。R01 active InvocationID7c5aa80405b24426aca4f901bdcecb81预算已实测；R06 InvocationID9c201ac1a1544cc6be071715fff6b93b，后置查询collect后的默认值不当作运行证据、不补重跑。
- 06仍BLOCKED：03/04完整前置、CONNECT CSRF与前端token、账户禁用/撤权、无下一帧主动关闭、连接/订阅/帧速率预算及全部实际登录/transport矩阵未闭环；相应阈值/关闭时限仍须批准，不猜值。本阶段实际功能progress，只更新task/work，Goal active/6-30，不关闭DEF-04/N1/N3整体、不C01、不DONE；未全量/冻结/暂存/提交/push，文档后只做差异检查。


### 2026-10-07 +08:00｜CONNECT CSRF与窄SockJS适配 [TASK-06-CSRF-R02]

- 上轮为功能progress，续原Goal/HEAD799fc1cb，不重开10。仅POM补受Boot管理的spring-security-messaging 6.5.1，WS原配置接CsrfTokenHandshakeInterceptor/XorCsrfChannelInterceptor复用原HTTP仓库；只豁免/ws/*/*的xhr/xhr_streaming/xhr_send POST之HTTP层，CONNECT仍必验token，REST、Principal授权和Origin保留。前端携带原meta token并禁帧debug，原协议夹具/CSRF helper最小接入，无手写token解码、连接管理器或新验证框架。批准后官方SDK下载校验完成，首次WSL引用转交和握手类包名查询失败已纠正；真实classpath为security/core/web/messaging 6.5.1及Spring6.2.8，未升级核心或安装插件。
- R01/session84603：152项148通过/4错误/0失败skip，15:25:46退出1；四个轮询负例为关闭状态与ConnectionLost均已完成时anyOf优先错误future，不是放行CONNECT。只调真实关闭证据优先顺序，另独立断言无越权数据，普通断网/超时及非协议关闭仍失败，原SEND ERROR断言不变。R02/session79416仅CONNECT拒绝12＋订阅28＝40/40、零失败错误skip、15:34:19退出0；复用R01协议38＋模板32＋认证42＝112项，不重复全量/冻结。
- 真实四入口缺失/错误/跨会话CONNECT token即使HTTP token合法仍不能CONNECTED：R02原生/WS/XHR各3项1002、轮询3项3000/Go away!；R01四入口Cookie/Header共8正例无HTTP CSRF Header仍接收服务器DTO。Header以X-Session-ID认证但仍用JSESSIONID持有CSRF，不宣称完全无Cookie；非transport POST及未批准Origin403。标准证据为既有三份Surefire XML，关键参数/跳过/依赖已核对；未执行C++、浏览器或真实账户->WS登出/到期/撤权联动，模板检查不冒充浏览器。
- Maven -o -B -Dtest=WebSocketSendHttpTest,FrontendTemplateContractTest,ProductionAuthenticationTest test；R02只#connectRequiresItsOwnSessionBoundCsrfToken+foreignMissingAndNonExactSubscriptionsAreRejected。沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target可写、隔离/tmp2GiB和内层540秒timeout；R01 active InvocationID4cf1c325b729432fa101cbe985d91339限制已实测，789.1M为整单元峰值非产品上界。06仍因03/04完整前置、主动撤销、账户禁用/撤权、连接/订阅/帧速率批准及浏览器/完整transport验收BLOCKED；本阶段功能progress，不C01/不DONE、不关闭整缺陷。Goal active/6-30，只更新task/work，保留用户/IDE改动，未全量/冻结/暂存/提交/push，文档后只查差异。

### 2026-10-07 +08:00｜账户变更撤销现有会话 [TASK-06-ACCOUNT-R02]

- 沿用active Goal/HEAD799fc1cb及第3/6节、task§1.1，不重开10。真实账户登录改用带账户来源的原会话创建入口，写入activeSessions前绑定；getSession/isSessionValid复用UserAccountService当前查询，禁用/移除/稳定ID替换/管理员撤权删除旧会话，恢复账户不复活，旧普通权限不自动提升。访问码/可信内部会话沿用原路径，无新管理器、调度或依赖。
- R01/session22129：安全默认19、日志2、账户认证48、配额授权13、真实WS86，共168项零失败错误skip，16:24:54+08:00退出0。真实/auth/verify Cookie登录后，/auth/session、/auth/check及保护页面失效；合法账户和访问码仍可用。四入口×禁用/撤权8项：原生/SockJS WS真实ERROR且无新订阅数据，XHR streaming/polling对观测到的原会话xhr_send提交真实SUBSCRIBE返回401；全部新ws/info握手401。登录轮换后重新取CSRF，不以手工账户会话替代关键登录验收。
- R01退出阶段出现两条XHR读取超时及Surefire 30秒强杀fork。仅补新增夹具的显式客户端disconnect，并严格核对被拒DISCONNECT的401根因；R02/session18266只复验8项，零失败错误skip、16:29:37退出0，但同一退出残留仍在。停止沿夹具继续扩建；这些断言证明当前请求/帧拒绝，不证明无后续帧的产品主动断连或清理成功，06仍BLOCKED。
- 标准证据仍用target/surefire-reports：ProductionAuthenticationTest、SessionCredentialLoggingTest、AuthenticationSafeDefaultsTest、QuotaAndAuthorizationTest及WebSocketSendHttpTest的XML；WS现为R02八项，其余有效R01结果不重跑/复制。两轮选择器分别为上述五套和WebSocketSendHttpTest#realAccountRevocationRejectsFurtherSubscriptionAndNewHandshake；沿用离线Maven与4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target及隔离/tmp可写，R01 active InvocationID6757ee4e97df4df7aeb7991b00a8ed44限制已实测。未全量、冻结、暂存、提交或push，只同步task/work；未批准的关闭/连接/订阅/帧速率参数、03/04及浏览器验收仍缺，不猜值、不标DONE或complete。

### 2026-10-07 +08:00｜同任务发送顺序与真实WS摘要 [TASK-23-SEND-ORDER-R02]

- 上轮账户修复为progress；续原Goal/HEAD799fc1cb，不重复06已知退出残留或10实验。ProgressPublisher仅将原broker发送放入已有compute串行点，修复旧RUNNING发送被终态抢越；持久化先于对应通知、异常告警与终态键回收保留，无新队列/锁/框架/阈值。原ProgressPublisherTest增加三个受控并发参数；原WS夹具增加四入口40例真实C++创建/启动/摘要/终态验收。
- R01/session9167：20项发布器及5项真实HTTP全过；四个新WS用例误将非运行态兼容results也禁掉，共29项25过/4失败、零错误skip，16:48:32退出1。只把无全量results断言限定到需求规定的RUNNING，保留统计单调、分类和、真实AC及HTTP/WS整快照相等断言，不修改产品payload。R02/session33192仅四项，4/4、零失败错误skip、16:55:14退出0；其余25项有效结果复用，两轮均无前轮06的Surefire退出强杀提示。
- 原生WS、SockJS WS、XHR streaming/polling各实际40 AC、观察到1个非空中间统计；运行态无全量results，完成数不倒退、分类和等于completed，最终40/40且HTTP/STOMP整个JSON相等。正常客户端断连，不冒充失效会话主动关闭；本轮服务端测试身份携Cookie/CSRF，不宣称浏览器登录或生产provider验收。broker调用串行不代表所有出站执行器/并发场景均已验证，有限发送预算仍未批准。
- 标准证据为target/surefire-reports的三份XML（ProgressPublisherTest20项、RunningProgressHttpTest5项、WebSocketSendHttpTest4项），关键并发参数和四入口实际执行已核对。Maven离线选择器R01为三套（WS仅新增方法），R02仅WebSocketSendHttpTest#fortyRealCasesDeliverRunningSummaryAndSameTerminalOverHttpAndStomp；沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork及源码只读/target与隔离tmp可写，R01 active InvocationID0a2ef747f4e14109a9377466f0e36924预算已实测。23仍因17/21/22、TTL/容量/清理、发送预算及revision/出站有序BLOCKED；只同步task/work，无全量/冻结/暂存/提交/push，Goal active/6-30，不DONE/complete。

### 2026-10-07 +08:00｜确认目录删除后回收节流键 [TASK-23-CACHE-CLEANUP-R01]

- 上轮发送顺序及真实WS为progress；本轮CUA只读当前surface为空（apps=[]/browsers=[]），不重试旧IAB/Edge认证故障、不安装/扩工具，浏览器验收仍缺。改推23已明确的物理清理缺口：TaskCleanupService只在原FileTaskStore确认目录删除后调用ProgressPublisher.clearThrottleState；目录校验、锁、残余句柄和受管证据删除防护不变，无新管理器/扫描/参数，原构造入口兼容。
- R01/session52923：发布器20、清理18、生产清理29、真实HTTP创建取消1＝68/68，零失败错误skip，17:32:20+08:00退出0。四个新参数实际执行：临时普通目录确认删除仅清本键，false/异常/受管证据拒删均保留；活任务目录/数据及缓存键不动，迟到发布失败不重建、重复清理幂等。此为真实文件系统与现有服务集成，元数据夹具不冒充真实代码执行/进程停止；原真实HTTP证明新生产构造接入未破坏启动/创建取消。
- 标准证据为target/surefire-reports中上述四份XML；Maven -o -B -Dtest=ProgressPublisherTest,TaskCleanupServiceTest,ProductionCleanupTest,CreatedTaskCancellationHttpTest test，沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读仅target及隔离/tmp可写，unit wjc-task23-cache-cleanup-r01/InvocationID2fca621c800c4f8595342bdcd9845d55。23仍因17/21/22、TTL/容量、有限发送预算及revision/出站有序BLOCKED，受管资源删除仍须14/19/28证据/批准；Goal active/6-30。只更新task/work，未全量/冻结/暂存/提交/push、不DONE/complete，保留用户/IDE改动。


### 2026-10-07 +08:00｜未验收provider生产失败关闭 [TASK-26-DISABLE-R02]

- 已读Goal第3/6节和task第1.1节，续HEAD799fc1cb/active Goal；TASK-10已交付，不重开或重做旧Probe。按TASK-00批准的DEC-07，在原ProductionSecurityStartupValidator禁用Windows/Remote生产入口；非trusted-local按provider及执行profile双向判定，未知/空/交叉profile、probe开关和自报安全能力不能启用。在能力探针前拒绝；保留原认证、资源及Worker配置检查，trusted-local开发入口与Linux真实安全探针不变，无新增框架/部署参数。
- R01/session5481五套141项，140过/1失败、零错误skip，18:09:37+08:00退出1：唯一失败为真实Windows启动的构造选择NoSuchMethodException，不把此异常当禁用成功。仅给原WindowsHyperVContainerRunner配置构造加Autowired，未改执行/取消/探针逻辑或放宽启动断言。R02/session30248仅复验actualApplicationRejectsUnverifiedProviderAtStartup两个参数，2/2、零失败错误skip，18:12:01退出0；windows-prod/worker-prod真实完整应用均在启动验证器以DISABLED_UNVERIFIED拒绝并关闭启动上下文。使用真实provider bean，无mock替代启动；不宣称Windows artifact或外部Worker部署已验证，R01其余有效回归直接复用。
- 标准证据沿用target/surefire-reports；R01选择器ProductionSecurityStartupValidatorTest,WorkerAuthenticationConfigurationTest,ProductionAuthenticationTest,AuthenticationSafeDefaultsTest,ApplicationYamlExecutionProfileTest；R02仅ProductionSecurityStartupValidatorTest#actualApplicationRejectsUnverifiedProviderAtStartup（当前该XML为两个真实启动负例，R01历史保留在工具输出及本记录，其余四份XML未覆盖）。离线Maven、4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork与源码只读/target及隔离tmp可写；unit wjc-task26-provider-disable-r01/r02，InvocationID分别93b690b02f8d421bbbdc06ce83fc386f/e2861fb9f70f42808e896258d3883300；内存峰值不当产品资源上界。
- 当前Windows/Remote矩阵均DISABLED_UNVERIFIED；完整08～21/23依赖、适用平台与发布/部署验收仍缺，26为BLOCKED，不删原平台要求、不称SUPPORTED_VERIFIED。仅更新task/work，未全量/冻结/暂存/提交/push，Goal active/6-30，不DONE/complete，保留用户/IDE改动。


### 2026-10-07 +08:00｜排队取消与重试串行闭环 [TASK-18-QUEUED-CANCEL-R03]

- 上轮26为progress；续原Goal/HEAD799fc1cb，不重开10。发现原queued cancel先锁内移除、再锁外落盘，窗口中同ID可重新enqueue，重复cancel会提前返回QUEUED。只把原取消令牌、CANCELLED/摘要持久化和出队纳入已有monitor；落盘失败不丢队列、不伪报accepted，重试仍可完成，无新锁/状态机/参数。原Scheduler套件加start/cancel两参数及一项元数据写失败回归，保留已有运行/清理失败占用逻辑。
- R01/session47787仅两新参数，2失败/零错误skip，18:22:43+08:00退出1：start未拒绝、重复cancel仍报QUEUED，已足够证明窗口，不重跑。最小修复后R02/session77068五套56项，51过/5启动错误、零失败skip，18:27:03退出1；Scheduler27（含三新项）、编排18、Policy5及真实Created取消1全过。五错误根因为Policy在共享默认根留下CREATED（其中100000例记录），后续HTTP上下文按19安全门槛拒绝未确认停止，非新取消断言失败。只给Policy加Import复用既有WebSocket IsolatedTaskStore，不删记录、不放宽恢复断言、不扩测试设施。
- R03/session86523仅复验Policy5+RunningProgressHttp5，10/10、零失败错误skip，18:33:36退出0；真实Cookie/CSRF创建取消和HTTP/C++原断言有效，混合结果取消/预算冷读、两用户运行许可及40例摘要通过。当前五份标准XML合计56项有效绿色，R02其余46项直接复用；文件系统/线程竞态不冒称恶意容器全域停止、原子final、TTL三方竞争或浏览器验收。
- 证据沿用target/surefire-reports，R01选择器JudgeSchedulerTest#queuedCancellationSerializesRetriesUntilTerminalPersistence，R02为上述五套，R03仅JudgeControllerPolicyTest,RunningProgressHttpTest；离线Maven及既有4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读/target与隔离tmp可写，unit wjc-task18-queued-cancel-r01/r02/r03；不将单元峰值当产品上界。14/17前置、TTL/扫描清理预算和完整持久化验收仍缺，18保持BLOCKED，Goal active/6-30；仅同步task/work，无全量/冻结/暂存/提交/push，不DONE/complete。


### 2026-10-07 +08:00｜批准的会话环境策略实际拒绝 [TASK-07-POLICY-R04]

- 上轮18为progress；续原Goal/HEAD799fc1cb，不重开10。再次核实TASK-00批准文件：2026-10-02 DEC-04已批准管理员STRICT、普通默认AUDIT且可配置STRICT，UA规范化/代理与移动矩阵仍延期。原AuthConfiguration新增仅已批准的AUDIT/STRICT配置（judge.auth.session-environment-policy，默认AUDIT），管理员固定STRICT；SessionManagementService用现有来源/UA原值比较，STRICT真实invalidate并false，AUDIT只记指纹与变化布尔量、不记录原/新IP或UA。原HTTP拦截器不再忽略false，auth页面/check/session及WS握手共用此校验，无新管理器、索引、事件机制、阈值或权限；OFF未启用/未宣称完成，原要求保留待明确批准。
- R01/session11602三套77项58过/19失败、零错误skip，19:07:12+08:00退出1：15个新例尚未登录就在错误的受保护首页取CSRF遇302，四管理员WS正例的旧CSRF请求缺稳定UA而被正确STRICT拒绝。初步JSON协商猜测后来证实不是15例失败根因；只补显式Accept和原Builder接口UA、MockMvc辅助转发实际UA。R02/session2356五套111项30过/81启动错误，19:18:39退出1：真实TLS4、CSRF14、WS角色12通过；剩余两类是CSRF夹具把已启动任务留共享根，生产19安全拒绝未确认停止。只复用既有IsolatedTaskStore隔离CSRF写入，不删目录、不放宽恢复门槛。
- R03/session99297仅CSRF/认证/凭据三套95项80过/15同一入口失败、零错误skip，19:26:06退出1；CSRF14、旧认证48、凭据18有效。停止猜响应，读取失败行与实际分支后只把登录前CSRF取值改到公开/auth。R04/session79443仅新方法15参数，15/15、零失败错误skip，19:29:02退出0；真实账号密码→Cookie，各例先证明同环境有效；127.0.0.2真实绑定且伪XFF为原地址仍触发STRICT，UA变化覆盖保护页、auth/check/session/page及原生WS Upgrade（STRICT401，普通AUDIT可连接），回原环境也不复活已撤销会话。既有四transport owner/同owner/admin及真实TLS/CSRF断言有效，不用mock代替关键路径。
- 分轮有效113项＝R04新15＋R03旧认证48/凭据18/CSRF14＋R02 TLS4/WS12＋R01日志2，直接复用未失效结果；当前认证XML仅15新例，48项历史保留工具输出及本记录，不重复归档。标准证据target/surefire-reports；离线Maven、原4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读/target及隔离tmp可写，unit wjc-task07-environment-r01～r04；R01/R02/R03 InvocationID 9d8e84e32fa2497aa6297846b05daa18/9910b70013b249baab54aeeda47ede7b/d337fed958e04899b7bae90d89f9d653（R04未取得不补作证据）。03/06整体、可信代理/UA规范化/OFF矩阵及无帧主动WS关闭和资源预算仍缺；7为BLOCKED，不关闭N6、不C01，Goal active/6-30。只同步task/work，无全量/冻结/暂存/提交/push，不DONE/complete。


### 2026-10-07 +08:00｜事件文件身份与缩短拒绝 [TASK-11-FILE-IDENTITY-R02]

- 已核对Goal第3/6节、task第1.1节、HEAD799fc1cb及空暂存；上轮07差异检查通过，沿用active Goal/6-30，不重开10。11设计明确同attempt缩短/替换不能当正常空轮询；仅在原Linux/Windows RunState及poll中记已见身份/长度，状态内串行读取和游标提交，NOFOLLOW打开常规可识别文件，读后再核对。已见文件消失、身份更换、缩短/丢行走原宿主异常，无伪造事件、回放或外部停止动作；错误消息不带私有内容。未建通用reader/管理器、未添预算或权限。
- R01/session15495新五参数×两provider共10项，2过/8失败、零错误skip，19:44:08+08:00退出1：截断/替换/移走/符链原来均未抛错误，足够证明缺陷不重跑。最小修复后R02/session4266，Linux27、Windows29、协议41、Ingestor63，共160项158过、零失败错误/2既有环境skip，19:47:56退出0；十个新参数实际执行且全过，启动前无文件与正常追加只消费新行。两个skip分别为cpp-judge-runner:latest不存在和当前runtime为Linux而非Windows；未绕过或冒称平台通过。真实临时文件系统、容器命令替身，不替代真实provider验收。
- 标准证据沿用target/surefire-reports四份XML；两轮离线Maven选择器分别为两套#eventFileMustRemainAppendOnlyWithTheSameIdentity和LinuxContainerRunnerTest,WindowsHyperVContainerRunnerTest,SandboxRunnerContractTest,SandboxEventIngestorTest。unit wjc-task11-file-identity-r01/r02沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读/target及隔离tmp可写；不拿单元峰值当产品上界。
- 11仍BLOCKED：正式批次/行/累计/字段深度预算未批准，仍全文件逐行读取；半行/UTF8跨块、并发替换/同inode覆盖与资源上界未证明。本轮静态身份闭环不等于完整11/N2/DEF-06关闭。只更新task/work、检查相关差异，无全量/冻结/暂存/提交/push，不DONE或complete，保留用户/IDE改动。


### 2026-10-07 +08:00｜认证检查与失败计数原子化 [TASK-03-ATTEMPT-ATOMIC-R02]

- 上轮11为progress，续原Goal/HEAD799fc1cb；核查12仍空批break，正式poll/drain/cleanup预算未批准，未重跑旧红测或猜数值。推进03既有计数并发缺陷：AccessCodeService/UserAccountService将次数和时间合为原Map的私有不可变记录，compute内完成原认证流程；同键不再越过在途失败，成功清本键，保留原key/阈值/重置时间/HTTP契约，无新管理器、权限或配置默认。
- R01/session71669只跑AuthenticationAttemptConcurrencyTest#aPendingFailureSerializesOnlyItsOwnKeyBeforeTheNextLockoutCheck，2失败/零错误skip，20:04:22+08:00退出1：两入口后继都提前完成而非等待首个失败。修复后R02/session19087六套110/110、零失败错误skip，20:09:33退出0；并发/清零4、ProductionAuthentication63、SessionCredentialHttp18、SafeDefaults19、Logging2、CookieHttps4。真实HTTP/TLS登录及既有来源/环境/权限断言通过，四新参数实跑；spy仅控制凭据读取窗口，不替代服务认证或真实入口。
- 标准证据target/surefire-reports六份XML；R02选择器AuthenticationAttemptConcurrencyTest,ProductionAuthenticationTest,SessionCredentialHttpTest,AuthenticationSafeDefaultsTest,SessionCredentialLoggingTest,AuthenticationCookieHttpsTest。沿用离线Maven、4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork及源码只读/target和隔离tmp可写；03仍BLOCKED，组合key尚非独立账户/来源限制，代理CIDR、容量/主动TTL/阈值待批。当前CUA只读核对apps=[]/browsers=[]，22/24真实浏览器环境仍缺，未重试旧IAB/Edge错误路径。Goal active/6-30；只同步task/work，无全量/冻结/暂存/提交/push，不DONE/complete。


### 2026-10-07 +08:00｜原生broker进度出站保序 [TASK-23-OUTBOUND-ORDER-R02]

- 上轮03为progress，续原Goal/HEAD799fc1cb；16正式解析预算/完整前置仍缺，不启用未声明的十进制模式。23现有发布器调用已串行，但clientOutboundChannel默认并发仍乱序；只给WebSocketConfig加setPreservePublishOrder(true)，复用Spring既有设施，无新消息框架、队列实现、阈值或权限。
- 原40例真实C++/四transport夹具仅增加出站执行器首RUNNING门闩：保持真实生产者继续至HTTP40 AC，后帧不能越过未释放首帧，随后仍验证计数单调/分类和/运行态无全results及HTTP-STOMP整终态相等。R01/session20294四项全失败、零错误skip，20:46:51+08:00退出1，四传输都确实抢越；修复后R02/session41454四项全过、零失败错误skip，20:51:44退出0。native、SockJS WS、XHR streaming/polling各40真AC；标准XML四个参数与noOutboundOvertake=true观测已核对，未改原业务断言、伪造终态或替代真实传输。
- 标准证据target/surefire-reports/TEST-com.example.demo.WebSocketSendHttpTest.xml；两轮均只-Dtest=WebSocketSendHttpTest#fortyRealCasesDeliverRunningSummaryAndSameTerminalOverHttpAndStomp，离线Maven及既有4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读/target与隔离tmp可写，unit wjc-task23-outbound-order-r01/r02。23仍BLOCKED：revision、TTL/容量、发送/框架保序积压上界及17/21/22整体前置未闭环，单元峰值不算产品上界。真实browser/provider与最终跨模块/回滚仍缺；只同步task/work，无全量/冻结/暂存/提交/push，不DONE/complete，Goal active/6-30。


### 2026-10-07 +08:00｜进度节流尾帧退出与终态直达 [TASK-22-THROTTLE-R07]

- 按Goal第3/6节及task第1.1节接续，HEAD799fc1cb、TASK-10已交付不重开。仅index.html原节流helper清旧延期、增加cancel并接入通道退出；终态复用现有判断立即执行，保留300ms、参数和this，无新队列/管理器。FrontendTemplateContractTest补一个最小接入契约。
- 复用前轮充分红测：实际原函数三项全失败（A尾帧落到B、AC非立即、序列1/3/2），本轮原生计时器执行最新实际函数三项全过（仅A首帧、RUNNING/AC立即、1/3）；页面唯一内联脚本语法通过。R07/session96494离线Maven仅FrontendTemplateContractTest，33/33、零失败/错误/skip，21:41:42+08:00退出0，新增契约实际执行；标准证据target/surefire-reports/TEST-com.example.demo.FrontendTemplateContractTest.xml，unit task22-throttle-r07-20261007沿用既有4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork及源码只读/target和隔离tmp可写。
- 纯JS与源码契约不是浏览器/DOM验收；复用未失效后端/真实WS证据，不重跑全量或冻结。22仍BLOCKED于05/06/18整体前置和真实浏览器规定矩阵，未C01/DONE；仅更新task/work并检查相关差异，不暂存/提交/push，不complete，Goal active/6-30。


### 2026-10-07 +08:00｜作业交接窗口取消复核 [TASK-18-HANDOFF-CANCEL-R02]

- 上轮22为实际修复/绿测progress，沿用Goal及第3/6节、task第1.1节；HEAD799fc1cb和原暂存状态未变。原Scheduler收到预留/晋升任务取消后仍调用作业；仅在runTask开始及作业调用前检查原令牌，复用原停止摘要、finally与FIFO/owner释放，无新锁、状态框架、参数或权限。JudgeSchedulerTest复用既有手动执行器接缝，四参数覆盖直接预留/队列晋升和执行前/启动落盘后的取消，断言零作业调用、真实零已执行摘要、重复取消不改快照、同owner后继仅启动一次与许可归零。
- R01/session57765为新测试record访问器写错导致编译失败（3错误、未执行测试），只修成既有accepted()，不算产品红测。R01b/session69483四项全失败、零错误skip，22:01:57+08:00退出1，四例实际调用计数均1而非0；充分红测后直接最小修复。R02/session66307仅五套60/60、零失败错误skip，22:04:16退出0；Scheduler31、Orchestration18、CreatedHTTP1、RunningHTTP5、Policy5，四个新参数实际执行且五份报告均为当前轮次。真实Cookie/CSRF取消及晚到start409保持通过；真实C++取消3例/ac2/wa1、预算17例/ac9/wa8与冷HTTP摘要相等，owner限1/global峰2/后继四AC及40例AC/WA摘要不退化。
- 标准证据target/surefire-reports对应五份XML，不复制归档；离线Maven选择器JudgeSchedulerTest,JudgeSandboxOrchestrationTest,CreatedTaskCancellationHttpTest,RunningProgressHttpTest,JudgeControllerPolicyTest。unit task18-handoff-cancel-r01/r01b/r02-20261007沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork和源码只读/target与隔离tmp可写，R02 InvocationID2b123645926746b88ad5fcf19d71d483，不把unit峰值当产品资源上界。18仍BLOCKED于14/17整体前置、未批准TTL/扫描/清理预算及完整三方/持久化验收；未C01/DONE，不全量/冻结/暂存/提交/push，不complete，Goal active/6-30。只简记task/work并检查差异，复用未失效证据。


### 2026-10-07 +08:00｜宿主失败终态摘要补齐 [TASK-17-HOST-ERROR-R02]

- 上轮18为实际修复/绿测progress，续原Goal/HEAD799fc1cb；现行第3/6节及task第1.1节未变。核对当前07仅AUDIT/STRICT，OFF未批准且未启用，不新增模式；原审批已批准CREATED TTL15分钟，修正18依赖表述，扫描/清理预算仍缺。Scheduler原作业异常及执行器拒绝只写metadata，冷读会缺摘要或保留RUNNING；两入口仅复用既有停止摘要，保留已落盘AC/WA、样本/进度及首个已存消息，其他已存终态不覆盖，无结果才零兜底，无新字段、接口、锁或框架。
- R01/session98541仅两个方法共五参数，2过/1失败/2错误、零skip，22:23:41+08:00退出1：部分结果summary仍RUNNING，无结果/拒绝缺summary。充分红测后直接修复，补一个已存SYSTEM_ERROR消息参数；R02/session62031六套150/150、零失败错误skip，22:30:23退出0，Scheduler35/Store67/Publisher20/Orchestration18/RunningHTTP5/Policy5，四个异常参数实跑，六份报告均属本轮。异常注入为测试case记录＋真实文件落盘/冷读，不冒称生产provider异常验收；既有真实C++取消3/ac2/wa1、预算17/ac9/wa8的冷HTTP逐字段相等，owner限1/global峰2/后继四AC及40例AC/WA摘要回归通过。
- 标准证据target/surefire-reports对应六份XML，不复制/冻结；离线Maven选择器JudgeSchedulerTest,FileTaskStoreTest,ProgressPublisherTest,JudgeSandboxOrchestrationTest,RunningProgressHttpTest,JudgeControllerPolicyTest。unit task17-host-error-r01/r02-20261007沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork及源码只读/target和隔离tmp可写，InvocationID189aaeccd940466bac7e3754440297fc/0e8eb9d774e14988ae4984279cb1c185，不把unit峰值当产品上界。17仍BLOCKED于11/12、终态/旧数据兼容批准及原子final/缓存WS/崩溃一致性验收，未C01/DONE；只简记task/work并检查差异，无全量/暂存/提交/push，不complete，Goal active/6-30。


### 2026-10-07 +08:00｜请求侧CREATED到期与配额释放 [TASK-18-TTL-REQUEST-R03]

- 续active Goal/HEAD799fc1cb及现行第3/6节、task§1.1，未重开10。Scheduler在原monitor内用已批准15分钟TTL复核start/cancel，仅未调度且无启动时间/句柄的CREATED转STALE，复用零已执行摘要；Service两入口释放pending与通道缓存，晚到start409、cancel false/STALE，不运行或清理Runner、不新增扫描器/配置/时钟框架。Scheduler六参数覆盖14/15/16分钟及两入口；真实HTTP两个参数验证STALE/total1/completed0、无启动、配额释放与同owner重新创建。
- R01/session1705仅六新参数2过/4失败、零错误skip，22:49:57+08:00退出1，已充分证明到期仍可执行/普通取消。R02/session51137七套152项147过/5启动错误、零失败skip，23:01:18退出1；真实TTL两参数及Scheduler41/Store67/Orchestration18/CreatedHTTP3/Policy5/Quota13通过。RunningHTTP启动按19安全门禁拒绝Quota夹具共享根遗留未确认停止任务1c9fa078-ad08-45e2-9c54-b15edfdd611a；仅在Quota测试用两行Import复用既有IsolatedTaskStore，不删除遗留或改变生产恢复。R03/session21148仅Quota/RunningHTTP18/18、零失败错误skip，23:09:37退出0，复用R02未失效134项，当前152项有效绿色。真实C++取消completed3/ac2/wa1、预算completed17/ac9/wa8与冷HTTP一致，owner限1/global峰2/后继四AC及40例摘要回归通过。
- 标准证据为target/surefire-reports七份对应XML，五份R02、Quota/Running为R03，八个新参数实跑；离线Maven选择器JudgeSchedulerTest,FileTaskStoreTest,JudgeSandboxOrchestrationTest,CreatedTaskCancellationHttpTest,RunningProgressHttpTest,JudgeControllerPolicyTest,QuotaAndAuthorizationTest，R03仅最后受影响两套。unit task18-ttl-request-r01/r02/r03-20261007沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork及源码只读/target和隔离tmp可写；R02/R03 InvocationID d8eb4e1209be4a02b0e40d79e0bf54f5/c82638f078804e5791b9c180d99afcb8，不把单元预算或峰值冒充产品上界。这里只闭合请求侧TTL；独立扫描/无请求空闲回收、扫描频率/清理预算、14/17前置及完整三方/持久化验收仍阻断，DEF-13不关闭、18不DONE。自审，无全量/冻结/暂存/提交/push，不complete，Goal active/6-30；文档变化不触发重测。


### 2026-10-07 +08:00｜编译事件状态拒绝与真实贯通 [TASK-11-COMPILE-STATUS-R02]

- 沿用Goal及现行策略，未重开10。新问题归11协议验真：COMPILE_FINISHED此前把AC/WA/RUNNING/CANCELLED/SYSTEM_ERROR/未知/空状态均作编译成功。SandboxEventIngestor只加三行拒绝校验，保留真实Runner的OK、无状态兼容及既有编译失败别名；复用原Session/序号/错误收尾，无新状态、预算或抽象。Ingestor测试参数化覆盖七拒绝/五别名/两兼容，拒绝不发通知/写摘要/推进序号，同序号修正后可继续完成；Artifact原编译失败真实测试补Java入库与零已执行断言。
- R01/session46110七新参数全部预期失败、零错误skip，23:37:21+08:00退出1；直接最小修复。R02/session64500 Ingestor76/Orchestration18/ProductionHighVolume2/Artifact指定两项，共98/98、零失败错误skip，23:39:27退出0；四份标准Surefire XML均runLog=/tmp/task11-compile-status-r02.log，14个编译参数及两真实Runner方法实跑。真实Python/C++正常三AC与编译失败→COMPILATION_ERROR/完成0/AC0→持久摘要通过；100/10000/100000例为既有Runner替身编排回归，不冒称真实provider资源上界。
- 两轮离线Maven仅上述受影响选择器，Artifact只选择taskRunnerCompilesProgramsWritesJsonlEventsAndAtomicSummaryForAcceptedCases+taskRunnerReturnsStableCompileErrorAndBoundsCompilerOutput；unit task11-compile-status-r01/r02-20261007沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork及源码只读/target与隔离tmp2GiB可写。R02 active InvocationID6ae31e1b4eac440abe948abe036c37d7及实际限制已核对；R01查询时已collect，默认值不当运行证据，不为补观测重跑。自审；11仍因字节/字段/深度预算、增量reader与真实资源验收BLOCKED，未C01/DONE。TTL未受此改动影响的已有证据复用；仅简记task/work及diff检查，不全量/冻结/暂存/提交/push，Goal active/6-30、不complete。


### 2026-10-07 +08:00｜既有订阅出站撤销 [TASK-06-OUTGOING-REVOKE-R03]

- 续active Goal/HEAD799fc1cb及现行第3/6节、task§1.1，不重开10。WebSocketConfig复用Spring发送钩子及WebSocketSessionGuard原会话复核，失效则不发送并1008关闭，无新连接表/锁/调度框架或预算。原账户/HTTP/CSRF夹具覆盖四transport×禁用/管理员撤权/真实登出/测试截止1秒后实际到期，合法订阅先实收；无需新客户端STOMP帧，受保护消息零送达、实际关闭且SimpUserRegistry本身份回落。轮询1006只在捕获实际HTTP401且索引归零时认可，普通断链仍失败；原下一次订阅与新握手拒绝断言保留。
- R01/session79345新16项全失败、零错误skip，日志2026-10-07 23:53:44+08:00退出1（13泄露/3轮询1006无权威关闭）；直接最小修复。R02/session54901协议106项82过/24失败、零错误skip，日志2026-10-08 00:01:22退出1；新16项全过（13个1008/3个1006+401，均索引回落），原账户8项、SEND/CSRF/授权及四入口真C++40AC/HTTP-STOMP终态一致通过。24失败为原管理员负例取CSRF缺稳定UA，被07 STRICT正确拒绝302；仅两行修该请求UA，不改生产策略。R03/session39163只订阅28/28、零失败错误skip，日志2026-10-08 00:04:47+08:00退出0；复用R02其余78项，106项断言分轮有效通过，不冒称进程退出正常。
- 标准证据target/surefire-reports/TEST-com.example.demo.WebSocketSendHttpTest.xml（R02）及同名-task06-outgoing-revocation-r03.xml（R03，Surefire内置后缀，无复制/冻结）；R01只#revokedAccountCannotReceiveExistingSubscriptionWithoutAnotherClientFrame、R02全该类、R03只#foreignMissingAndNonExactSubscriptionsAreRejected。unit task06-outgoing-revocation-r01/r02-20261007、r03-20261008沿用4GiB/swap0/2CPU/512pids/600秒、PrivateNetwork、源码只读/target及隔离tmp2GiB可写；R01/R02 active InvocationID f47765c0f3b84f4e83b8ea0bc47f3a90/510ff5dfb1e948e7b15c61ff11e5f274和限制实测，R03已collect的默认值不当证据、不补重跑。R01/R02标准dumpstream仍证实30秒退出强杀/旧XHR读取超时，归06阻断，不改退出门槛或扩工具。只闭合发送前撤销；无发送的空闲主动关闭、批准时限/连接订阅预算、03/04及浏览器未闭环。自审，06仍BLOCKED、不C01/DONE/关闭DEF-04；只简记task/work与diff检查，不全量/暂存/提交/push，Goal active/6-30、不complete。


### 2026-10-07 +08:00｜终态提交审批阻断核查 [TASK-17-APPROVAL-CHECK-R01]

- 核对原审批TASK-00 approval-and-baseline§2、当前TaskStore/FileTaskStore及ProgressPublisher、task/work，HEAD799fc1cb/暂存空不变；原批准未冻结17终态字段/旧数据兼容，Task卡仍明确要求批准。源码当前updateStatus→saveSummary分文件写，未实现final.json权威提交；已有红测/绿测不重复。16当前仅声明LEGACY_TEXT/SPECIAL_JUDGE并拒绝DECIMAL_ABSOLUTE/非零精度，15数值预算/错误映射未批准，不能以未接入解析器或无界Decimal绕过。请求明确批准按D§6.2实施单文件final权威、现有DTO内容兼容及attemptId/revision/finalizedAt，保留旧有效summary、不把未验证历史事件升级为final、不自动重跑。此次为明确审批阻断核查，非功能性进展；产品/测试未改、未构建/重测/冻结，仅工作记录及差异检查。17仍BLOCKED、不DONE，Goal active/6-30、不complete，无暂存/提交/push。


### 浏览器环境解除与真实前端闭环 [TASK-22-BROWSER-R03]

- 沿用active Goal/HEAD799fc1cb及现行第3/6节、task§1.1，未重开10。已有Chromium1228/Playwright1.61.1以nobody UID/GID65534运行；runtime R01隔离tmp默认不可写、R02缺CDP观测flag，最小修正临时tmpfs mode=1777与enable-automation后R03退出0，实测Chrome149.0.7827.55且无no-sandbox/disable-setuid-sandbox。仅缓存模板既有8个固定版本CSS/JS到target供离线加载，不安装/升级浏览器或依赖，不改宿主权限、内核或共享Docker；业务HTTP/WS均走真实服务，只有创建响应丢失用实际route.fetch成功落盘后abort注入。
- 最新classes来自现有Surefire classpath，排除test-classes，无Spring替身；auth/index/application资源与源码一致，存储/日志位于隔离临时根。R01/session31196误把访问码失败当401而退出1，按RQ-A01/D§3.1改为200+success=false并确认无会话，不放宽安全要求；R02/session49647已到刷新后显式提交，但结果页隐藏表单导致孤立waitForResponse超时、Node异常退出1，无JSON报告，保留截图与失败。仅补真实代码页导航/固定测试设置及Promise.all，R03/session38916六检查全PASS、pageErrors0、退出0：登录负例不假成功，空代码错误Toast可见且create0；真实create丢响应create1/start0、无ID、刷新不自动重发，查询CREATED并由owner实际取消为CANCELLED/完成0；显式再提交总create2/start1，真实三C++程序三例AC，4条SockJS/WS消息整终态与HTTP相等，成功摘要可见；实际logout后受保护HTTP401、页面回登录。浏览器关闭后应用SIGTERM退出143，无SIGKILL；这不替代06旧XHR/Surefire退出阻断或生产资源上界验收。
- 证据仅target/task22-browser-r01.json、R02/R03原截图及target/task22-browser-r03.json，入口target/task22-browser-check.cjs；WSL Ubuntu systemd-run→timeout550s node，R01～03单元task22-browser-r0N-799fc1cb，NoNewPrivileges/PrivateNetwork、4GiB/swap0/2CPU/512pids/600秒、临时tmp2GiB及源码/缓存只读。R03 active InvocationID2025ffad2fa44bd99af67f9fdcff3e76和实际限制已读，不以collect后的默认值补证。产品源码未改，既有受影响套件结果复用，无全量/冻结；只简记task/work，22仍BLOCKED于前置及完整错误/重试/网络矩阵，24仅解除浏览器基础环境、最终矩阵未执行，17格式批准仍缺。自审；无暂存/提交/push、不DONE/complete，Goal active/6-30。


### 双模式登录与表单淡化根因修复 [TASK-05-BROWSER-VISIBILITY-R04]

- 续原active Goal/HEAD799fc1cb及现行策略，未重开10。复用缓存Chrome149/Playwright1.61.1、现有classes与固定静态资产，账户材料仅用现有JDK/Spring BCrypt在隔离临时根生成，保留密码前后空格；应用无test-classes/替身。R01/session84465账号401已返回但未消费非2xx响应体的CDP读取卡住，550秒上限后浏览器关闭、整体退出124，不能称验证通过；最小改夹具为HTTP状态+真实可见错误+auth/check未认证，原后端字段回归复用。R02/session34176两模式各7项业务/DOM检查通过、整体退出0：服务端模式不受query干预、401/历史200失败、真实旧token403、成功登录/rememberMe30天/HttpOnly/Lax/站内redirect、登出后401及原5次尝试规则真实拒绝有效凭据（账户429/访问码200失败）。503为明确前端故障注入、断网为abort，未作为后端503证明；应用日志未含实际测试材料/已发会话token，两应用SIGTERM143。
- R02截图反证仅检查alert自身opacity不足：整张表单淡化，不能以14项PASS宣称可见性完整通过。直接复用这些真实红证据，只改auth.html根容器一处类名，移除重复fade-in、复用原GSAP入口/减少动效/无GSAP兜底，不加动画或状态框架。原FrontendTemplateContractTest认证方法增加根因断言；离线Maven仅该套件33/33、零失败错误skip、session40755退出0，标准target/surefire-reports/TEST-com.example.demo.FrontendTemplateContractTest.xml，资源更新到classes。必要真实验证增加父级合成opacity≥0.95，不放宽断言：R03/session59490账号普通/减少动效/无GSAP三项全绿（0.968332/1/0.955447），随后停在访问码交互提示直到外层上限、KILL退出1，无完整JSON，绝不作正常退出/完整资源验收。确认该handle终态后删除已经观察过布局的暂停，仅补R04/session63102访问码三项全绿（0.968347/1/0.955493），pageErrors0、9.732秒退出0、应用SIGTERM143无SIGKILL。账号R03与访问码R04截图均已实看，六组合分轮有效；不重跑未受影响业务或旧Probe。
- 入口target/task05-browser-check.cjs（visibility access为定向选择，无新测试调度框架）；证据保持R01/R02原JSON、R02反例图、R03账号三图/实际输出及target/task05-browser-visibility-r04.json/三图，不伪造缺失R03报告。所有WSL单元沿用私网/NoNewPrivileges、4GiB/swap0/2CPU/512pids/600秒、源码/缓存只读与临时tmp2GiB；浏览器nobody+沙箱，未改宿主权限/依赖/共享Docker。R01 active InvocationID5733c1bc00bd4ba2bd54a5aca69b9f70及预算实测；Maven/R03/R04 InvocationID14f42861db154f63af4a930705e8ebd2/edab37b7418840f78279f3a898d0a3cf/0f9dbe5b92cd4d02979ee1a8a8a15467按实际输出，不以collect默认值补证。自审；05仍BLOCKED于04及交付回归，04生产TLS/HTTPS浏览器与03批准仍缺，17格式批准未回答，06旧退出问题不被本轮替代。只简记task/work并查diff，无全量/冻结/暂存/提交/push、不DONE/complete，Goal active/6-30。


### 2026-10-08 +08:00｜真实队满后的原ID重试闭环 [TASK-22-START-RETRY-R04]

- 沿用原Goal/HEAD799fc1cb及现行第3/6节、task第1.1节，不重开10。复用现有应用产物；R01的429保留CREATED/原ID正证据有效，但未证明占位任务已完成。R02/session97641、R03/session79523均退出1；R03实际二次响应仍JUDGE_QUEUE_FULL，查询占位任务为COMPILING/QUEUED，证明原页面异步等待提前返回，不能据此认定产品重试失败。只把同一验收入口的等待改为有30秒截止的真实HTTP查询，不改产品、不建测试框架。
- R04/session70596两项全PASS、pageErrors0、11.943秒退出0：两个占位任务真实AC后，原ID重试完成固定良性C++一例AC；仅1次create、2次同ID start（429→200），真实SockJS/WS整终态与HTTP相等、成功Toast可见且截图已实看，连接峰值1/最终0。证据target/task22-start-retry-r04.json及rejected/completed图；入口target/task22-start-retry-check.cjs，R01～03原失败报告保留。三轮应用均SIGTERM143、无强杀；不把此切片当生产provider或完整资源验收。
- 沿用私网、nobody/沙箱及既有4GiB/2CPU/512pids/600秒预算；R03 active InvocationID fcefc2694cbb4be78ffeb6524b822129与限制已实读，R02/R04已collect的默认值不作证据。产品未变，原受影响套件结果复用、不全量/构建/冻结。仅同步task/work并自审；22仍缺网络未知及完整错误/重连矩阵和05/06/18前置，17格式审批仍缺，不DONE/complete，无暂存/提交/push。


### 2026-10-08 +08:00｜启动响应未知后的原任务对账修复 [TASK-22-NETWORK-RECOVERY-R03]

- 上轮R04为真实闭环progress，续原Goal/HEAD799fc1cb、现行策略，不重开10。复用原验收入口network选择；R01/session57571夹具30000超出HTML max10000，浏览器未提交、等待超时退出1，只改到现有上限并检查原表单validity。R02/session95922真实start200后丢响应、首次查询断网，未知提示/原ID保留有效；实际对账恢复运行中后旧待启动取消按钮仍显示，断言失败退出1。已有真实红证据足够，仅index.html的handleProgress已接受状态分支增加隐藏旧按钮一行，原FrontendTemplateContractTest取消方法补定向断言，无新状态机/接口/管理器。
- 离线受影响FrontendTemplateContractTest 33/33、零失败错误skip、session76628退出0（15.306秒）；标准XML及关键取消方法实际执行已查，classes模板与源码一致。R03/session14796两项全PASS、pageErrors0、11.325秒退出0：原ID显式重试只对账并恢复HTTP轮询，总create/start=1/1、3次页面status请求且并发峰值1；一例真实C++ AC，HTTP与localStorage整终态相等，1.3秒观察窗内终态后不再轮询、在途0，连接峰值1/最终0，按钮恢复且旧取消隐藏。错误/成功提示opacity≥0.95，unknown/completed图已实看；不把网络abort注入当服务端5xx或完整断网矩阵。
- 证据target/task22-network-r01～r03.json及对应截图、target/surefire-reports/TEST-com.example.demo.FrontendTemplateContractTest.xml；入口target/task22-start-retry-check.cjs network。沿用既有私网/低权限浏览器和资源预算，应用三轮均SIGTERM143、无强杀；R02 InvocationID a608f85df7e14dcaac58e8988bfbc0f8来自实际启动输出，不用collect后的默认值。既有队满/创建未知证据不受该按钮改动影响，直接复用，不全量/冻结；仅简记task/work、自审。22仍缺其余错误/WS恢复矩阵及05/06/18整体前置，17审批仍缺，不DONE/complete，无暂存/提交/push。


### 2026-10-08 +08:00｜WS不可用降级与真实CSRF拒绝恢复 [TASK-22-WS-CSRF-R01]

- 上轮网络修复为progress；沿用原Goal/HEAD799fc1cb与现行策略，不重开10。产物未变，classes模板与当前源码一致；仅复用现有验收入口的websocket/csrf选择，原有限重连、原ID重试、真实HTTP/WS断言直接复用，无产品改动/新框架。已有33项定向及队满/创建未知/网络恢复证据未失效，不重跑、不全量/冻结。
- WS/session53924两项全PASS、pageErrors0、56.902秒退出0：私有页面WS路径abort为明确网络注入，不冒称服务端WS故障。首次拒绝后真实CREATED、准备中、create1/start0/status轮询0；沿用原退避的6次尝试后，仅一次真实start200、HTTP单轮询取得一例C++ AC，浏览器整终态与HTTP相等，终态后1.3秒观察窗无再轮询/在途0、无WS打开，成功提示可见。CSRF/session6381两项全PASS、pageErrors0、11.263秒退出0：Cookie/身份保持不变，仅首次start无效CSRF头由服务端真实拒绝403，CREATED/原ID保留、错误opacity≥0.95、旧WS关闭；刷新不自动create/start，显式同ID403→200、一例真实AC，HTTP/WS整终态相等，create1/start2、连接峰值1/最终0。四张关键截图已实看。
- 证据target/task22-ws-fallback-r01.json、target/task22-csrf-recovery-r01.json及对应截图；入口target/task22-start-retry-check.cjs websocket/csrf。两应用均SIGTERM143无强杀，沿用既有私网/低权限/预算；实际启动InvocationID 82d17546c64b4c6780ce630167b00f77、1f1f924c7da2405faad6582946e4d183，不用collect默认值补证。仅简记task/work并自审；22仍缺其余HTTP错误、已连接后的断线/重连与完整矩阵及05/06/18前置，17审批仍缺，不DONE/complete，无暂存/提交/push。

### 2026-10-08 +08:00｜真实已连接通道关闭与终态冲突对账 [TASK-22-DISCONNECT-CONFLICT-R02]

- 复用原产物/入口，未改产品。disconnect/session81227两项全PASS、pageErrors0、13.139秒退出0：真实start200及WS连接后由客户端关闭通道，同ID HTTP降级，create/start=1/1、查询2次且并发峰值1；真实C++一例AC、浏览器与HTTP整终态相等，1.3秒窗无再轮询/在途0，连接峰值1/最终0。该注入不证明服务端故障或会话撤销。证据target/task22-ws-disconnect-r01.json及polling/completed图；InvocationID 0b59b925ef774bbf9edfea374d48bdc1。
- conflict R01/session90182退出1，仅夹具把真实Cancellation requested硬编码为Task cancelled；保留失败报告。最小改为等待实际start响应并匹配实际取消响应文案，不放宽409/终态/权限断言。R02/session80286两项全PASS、pageErrors0、10.742秒退出0：owner真实取消CREATED后start409（JUDGE_TASK_FINISHED/CANCELLED），页面同ID对账终态、不自动重启、不假成功；显式新提交不同真实ID，一例C++ AC、HTTP/WS整终态一致，create/start=2/2、409→200、连接峰值1/最终0。证据target/task22-conflict-r01.json、r02.json及reconciled/completed图；InvocationID 9ebe20cad0bc46978bf533061c66df0c。
- 四张关键截图已实看，三应用均SIGTERM143无强杀；沿用私网/低权限/原资源预算。本轮只同步task/work并检查文档，既有33项定向与有效真实证据复用，不重跑/构建/冻结。继续22其余错误/恢复及完整矩阵；05/06/18交付和17格式批准仍缺，不DONE/complete，无暂存/提交/push。

### 2026-10-07 UTC｜HTTP错误的真实拒绝与显式恢复 [TASK-22-HTTP-ERRORS-R02]

- 已核对Goal第3/6节及task第1.1节，沿原active Goal/HEAD799fc1cb继续22，不重开10。仅原临时入口参数化增加create401/503、start401/404/503、status401/404/503，无产品改动或新框架。R01/session52010在创建两场景4项通过后因夹具未切回代码页、取消按钮上层隐藏而失败，22.845秒退出1；报告保留。最小修为切回现有代码页再断言，R02只跑剩余六场景，已通过创建证据不重跑。
- R02/session61407共12项全PASS、pageErrors0、47.994秒退出0。401/404是转发实际匿名/不同owner Cookie及CSRF请求的真实拒绝，原浏览器会话仍有效，不冒称登出/会话撤销；503明确为HTTP响应注入，不冒称服务端故障。八场景各真实C++一例AC、浏览器/HTTP整终态相等：创建失败无ID/无start，显式新建；start401/503保留原ID并先查询后重试；404停止旧通道、忘旧ID、显式新建；status401只对账不再start，status503单次退避后恢复。查询峰值≤1、终态1.3秒窗不再轮询/在途0，连接峰值1/最终0，输入保留、Toast及按钮真实可见。
- 证据target/task22-http-errors-r01.json、r02.json及401/404/503-rejected与completed图，四图已实看。两应用均SIGTERM143无强杀；R02运行中实读私网/nobody/4GiB/swap0/2CPU/512pids/600秒及只读源码，InvocationID 3ffad24ef7504df28984141feeef3dcc；R01已collect的默认值不作证据。复用未失效33项定向，不构建/全量/冻结；仅简记task/work。22完整矩阵及05/06/18交付、17格式批准仍缺，不DONE/complete，无暂存/提交/push。

### 2026-10-07 UTC｜真实会话恢复与过期错误提示最小修复 [TASK-22-SESSION-RECOVERY-R05]

- 上轮HTTP边界为progress，续原Goal/HEAD799fc1cb及第3/6节、task第1.1节，不重开10。原入口session模式复用既有BCrypt账户夹具与通道闭环。R01/session40999、R02/session36708均因非必需精确进度帧等待超时、未触发登出，退出1；按两阶段无功能进展检查方式，改用已有真实HTTP活跃状态前置，不继续扩工具。R03/session34854真实撤销项已过，但显式重登录查询未等响应就检查次数，退出1；只补响应等待，所有失败报告留存。
- R04/session48949核心两项全PASS、pageErrors0、16.214秒退出0，但实看completed图发现真实AC恢复后旧“登录已失效”仍在。已有视觉红证据，直接index.html的showSuccess增加一行点击原错误Toast关闭控件（兼容原fallback），原FrontendTemplateContractTest补契约断言，无新状态机/管理器。nobody定向构建因root拥有的target产物元数据操作受限而退出1、测试未执行；专项批准可信离线Maven以原拥有者root、清空Linux能力、源码只读/仅target可写，未改ACL或运行C++。session2325受影响33/33、零失败错误skip、15.529秒退出0；标准XML关键fallback方法实际执行、classes模板与源码相等已核对。
- R05/session52870两项全PASS、pageErrors0、17.578秒退出0：实际账户登出200/会话失效后旧WS下一次出站被关闭、未收到受保护帧；原页面查询401、可见错误、代码/原ID保留且停止跟踪。同账户真实重登录更换令牌，显式原ID查询200取得真实C++一例AC，HTTP/浏览器整终态相等，create/start=1/1、原页面查询2次/并发峰值1、终态1.3秒窗无轮询/在途0，连接峰值1/最终0；新断言确认成功后旧错误隐藏，两张R05关键图已实看，口令/新旧token未入应用日志。五应用均SIGTERM143无强杀，真实执行/浏览器仍nobody/沙箱与既有私网预算，未扩权限；R05 InvocationID f4aa57def81840ec9ea4b828d5d42f0a，collect后的默认值不作证据。
- 证据target/task22-session-recovery-r01～r05.json及对应截图、target/surefire-reports/TEST-com.example.demo.FrontendTemplateContractTest.xml；入口target/task22-start-retry-check.cjs session，定向mvn --offline -Dtest=FrontendTemplateContractTest test。只更新task/work、检查文档，不重跑其他有效矩阵/全量/冻结；22其余边界及05/06/18交付、17格式批准仍缺，不DONE/complete，无暂存/提交/push。

### 2026-10-07 UTC｜严格证书校验下的真实HTTPS浏览器 [TASK-04-HTTPS-BROWSER-R01]

- 上轮22的一行修复及R05真实绿测为progress；现行策略未变，续原Goal/HEAD799fc1cb，不重开10。最早03仍缺可信代理CIDR、来源/账户阈值、状态容量/TTL及头上限批准，未猜默认。转补04独立HTTPS浏览器缺口：现有certutil缺失，专项批准仅下载匹配现NSS的libnss3-tools=2:3.98-1ubuntu0.2（615kB）至target并解包，不安装/升级系统或运行安装脚本。原WorkerTlsCertificates只用于生成证书，真实应用classpath仍排除test-classes；仅私有临时HOME/NSS导入本次CA，保持证书校验、浏览器沙箱与nobody，无产品源码/配置/核心依赖改动。
- 原入口target/task22-browser-check.cjs https；session75678五项全PASS、pageErrors0、16.212秒退出0：未信任CA浏览器真实ERR_CERT_AUTHORITY_INVALID；受信连接实际TLS1.3，Cookie Secure/HttpOnly/SameSite=Lax且remember30天、JS读不到HttpOnly值；真实HTTPS创建/启动各一次、两请求实际携带Cookie，C++一例AC，1条WSS连接的整终态与HTTP/浏览器缓存相等；真实登出安全属性保留且Cookie删除、保护查询401；不改原Cookie标志，在无HSTS新profile对同host实际明文HTTP探针未发送该Cookie（服务端实收请求，不以混合内容/HSTS阻断代替）。SSL密码与会话token未入应用日志，应用SIGTERM143无强杀。
- 证据target/task04-https-browser-r01.json、completed图（已实看）；沿既有私网/NoNewPrivileges/源码只读、4GiB/swap0/2CPU/512pids/600秒与2GiB私有tmp，InvocationID 7b992c1900fd405fb60cbb322f55e1c0按实际启动输出，collect默认值及256K统计不作产品资源证据。04浏览器环境缺口已闭环；原63/145/14项及最新33项受影响回归未失效，直接复用，不全量/重建/冻结，只简记task/work并检查文档。03参数批准、04生产TLS/代理及完整交付、17格式批准仍缺，不代表生产provider通过，不DONE/complete，无暂存/提交/push。

### 2026-10-07 UTC｜持续查询失败的最小修复与恢复 [TASK-22-STATUS-RECOVERY-R02]

- 上轮04严格HTTPS验收为progress；已读原附件、Goal第3/6节和task第1.1节，核对active Goal、HEAD799fc1cb及源码/记录，不重开10。复用原浏览器入口参数化补创建429、持续查询429/503/断网；HTTP拒绝和网络故障均为明确注入，后续恢复仍用真实接口与C++，不冒称真实服务端限流/故障。
- R01/session19090：前三场景6项PASS，持续查询断网实际误报“启动结果未知”，消息断言超时退出1；原ID/输入保留且5次后停止，红测JSON和截图保留。index.html只增加查询阶段文案分支，原FrontendTemplateContractTest补断言，无新状态机/管理器。可信离线定向沿原产物拥有者root及空CapabilityBoundingSet请求、源码只读/仅target可写；session86982受影响33/33、失败错误skip均0，15.734秒退出0，XML关键方法实执行、classes模板与源码相等、主脚本语法通过；collect后默认属性不作实际限制证据。
- R02/session63584只跑失败的查询断网分支，2项全PASS、pageErrors0、45.069秒退出0；按既有5次退避停止，poll/reconnect timer及controller均归零。显式重试仅第6次查询原ID取得真实C++一例AC，create/start=1/1、查询并发峰值1、连接峰值1/最终0，整终态与浏览器相等、旧错误隐藏；拒绝/完成两图已实看。R01其余6项仍有效，直接复用；两应用均SIGTERM143无强杀、凭证日志检查通过。
- 证据target/task22-status-recovery-r01.json、r02.json及截图、target/surefire-reports/TEST-com.example.demo.FrontendTemplateContractTest.xml；入口target/task22-start-retry-check.cjs status（R01）/status green（R02），定向mvn --offline -Dtest=FrontendTemplateContractTest test。两真实验收仍nobody/浏览器沙箱及原私网、4GiB/swap0/2CPU/512pids/600秒与2GiB私有tmp，active属性已核对；InvocationID分别111a957a241d47d498a4e3e49f935e4a、18ce222407a84c08b109fe71f9b16ed1。只简记task/work并检查文档，不全量/冻结；22其余边界及05/06/18交付、03参数和17格式批准仍缺，不DONE/complete，无暂存/提交/push。

### 2026-10-08 UTC｜连点、晚到响应与运行中刷新 [TASK-22-INTERACTION-R02]

- 上轮查询断网修复及真实绿测为progress；续原Goal/HEAD799fc1cb，未重开10。原浏览器入口补交互场景，延迟的启动200来自真实服务端，两任务均固定良性C++。产品源码未变，模板与上轮通过套件/target/classes一致，33项及其他有效证据直接复用，不构建/全量/冻结。
- R01/session80761连点项已PASS：实际双击仅1次create/start，真实WS先于延迟启动响应取得AC。随后夹具额外await Response.finished无上限未推进；核对原单元仍live、直属Node/Chromium PID、exe及cgroup后，经专项批准仅SIGTERM私有浏览器PID125617；原句柄7分18.605秒退出1，报告TargetClosed和一项PASS保留，应用SIGTERM143。仅删除多余等待，无新调度/消息/管理器。
- R02/session18956用interaction resume只续验剩余三项，连点不重复；全PASS、pageErrors0、12.858秒退出0，应用SIGTERM143无强杀。旧任务启动200晚到未覆盖新ID/缓存/按钮；刷新真实COMPILING任务保留代码和原ID、无新create/start；鉴权HTTP恢复一例真实AC，整终态与浏览器相同。两任务create/start各一次，原页面查询2次/峰值1、连接峰值1/最终0、终态后停止，完成图已实看，凭证日志检查通过。
- 证据target/task22-interaction-r01.json、r02.json及completed图；入口target/task22-start-retry-check.cjs interaction（R01）/interaction resume（R02）。仍nobody/浏览器沙箱与原私网、源码只读/仅target可写、4GiB/swap0/2CPU/512pids/600秒及2GiB私有tmp；R01 active限制/InvocationID10d36e5b38d240a0b2f3ba83729b7514已读，R02查询时已collect，不以默认值或4.1M统计补证。只简记task/work和检查文档；22其余边界及05/06/18、03参数、17格式批准阻断保留，非完整22/24或provider验收，不DONE/complete，无暂存/提交/push。

### 2026-10-08 UTC｜轮询权威源的最小修复 [TASK-23-AUTHORITATIVE-POLL-R02]

- 上轮22交互验收为progress；原Goal/HEAD799fc1cb未变。按任务边界转补23，不重开10。新单项屏障回归复用原发布器/文件存储，R01/session57381实际显示磁盘AC/completed1但HTTP服务方法被旧缓存覆盖为RUNNING/completed0，1失败、零error/skip，18.791秒退出1；target/task23-cache-race-r01.xml仅保留本次新失败，不搭第二套复现。
- JudgeService删除重复judgeStatusMap、所有投影写入及60分钟延迟回收任务；状态查询直接复用现有TaskStore摘要/metadata兜底，沙箱事件仍走原持久化/发布入口。只补现有编排回归，真实冷读测试移除对已删除私有缓存的清空接缝，完整HTTP/文件、计数/样本业务断言不变；无新锁/框架/管理器、生产阈值或终态格式。
- R02/session53781可信纯Java编排19+发布器20，39/39、零失败错误skip，32.239秒退出0；原产物拥有者root、空CapabilityBoundingSet请求及源码只读/仅target可写，无C++/容器。随后session62883以nobody直接现有surefire:test，避免重复复制root元数据、不改ACL；真实HTTP/C++5/5、零失败错误skip，41.994秒退出0：AC/WA各40例；取消3例（AC2/WA1）、预算17例（AC9/WA8）统计与文件及冷HTTP完整相等，样本边界保持；owner1/global峰值2、阻塞队首跳过和四任务真实AC仍通过。标准XML三套及关键竞态方法实际执行已核对，真实JVM user.name=nobody。
- 证据target/task23-cache-race-r01.xml、target/surefire-reports/TEST-com.example.demo.{JudgeSandboxOrchestrationTest,ProgressPublisherTest,RunningProgressHttpTest}-task23-cache-r02.xml。命令mvn --offline -Dsurefire.reportNameSuffix=task23-cache-r02 -Dtest=编排/发布器 test，及-Dtest=RunningProgressHttpTest surefire:test；沿原私网/NoNewPrivileges/4GiB/swap0/2CPU/512pids/600秒、2GiB私有tmp。纯JavaR02 InvocationID773002f59cc049ebb84fa7d78332f92c；属性读取时已collect，不拿默认值或1.5M统计补证。真实测试沿既有trusted-local/AUDIT夹具；只读cwd文件日志未创建、离线未用插件描述符警告已归27，不冒称日志/打包/生产STRICT/TLS或provider验收，业务绿色不为这些警告重跑。
- 仅简记task/work，文档后只diff检查，不全量/冻结/更新Goal或交接。23的17/21/22前置、非终态容量/TTL、发送/出站预算及revision，03具体参数和17终态字段/旧数据兼容批准仍缺，不关闭完整DEF-21/22、不DONE/complete，无暂存/提交/push，保留IDE改动。

### 2026-10-08 +08:00｜取消落盘失败后的原ID重试 [TASK-18-TERMINAL-PENDING-R02]

- 原Goal/HEAD799fc1cb继续，已读Goal §3/6及task §1.1并完成上轮diff检查；10不重开。发现18取消摘要IO失败后metadata已CANCELLED，原ID重试false却不清pending。JudgeService只用现有终态枚举替代STALE单值判断；不触碰调度占用、权限或终态提交。仅补原编排与真实HTTP套件，无新框架/能力模式。
- R01/session38656单项1失败、零错误skip，18.312秒退出1，保留标准XML；R02/session64320编排20+调度41均通过，32.385秒退出0。随后nobody直接既有surefire:test，session95807真实HTTP4/4、12.774秒退出0；三套65项均零失败错误skip，关键新方法及user.name=nobody已核对。单位回归还证明其他CREATED注册及其状态不变、没有Runner启动或许可占用。
- 真实Cookie/CSRF/TCP与文件故障：仅私有tmp测试任务的空summary.json目录令首次取消500；移除该空夹具后原ID重试200/accepted=false/CANCELLED，pending释放、metadata字节不变、startedAt/handle仍空、晚到start409。摘要确实未提交且保持缺失，不伪造成功或回收证明；补齐及崩溃一致性仍归17。
- 证据为target/surefire-reports原标准XML（编排task18-terminal-pending-r01，编排/调度/CreatedTaskCancellationHttpTest的task18-terminal-pending-r02）。离线Maven只选择上述两套test和HTTP套件surefire:test，日志指定单元私有tmp；沿原源码只读/target可写、NoNewPrivileges/空能力集/私网、4GiB/swap0/2CPU/512pids/600秒及2GiB私有tmp，R02启动InvocationID9c58789383f94f39b6d0c1bd8e0bcf5c。不拿单元峰值当产品资源上界，离线插件提示沿27既有记录，不重跑绿色业务。18的14/17前置、独立扫描预算及完整三方/持久化验收仍缺；仅更新task/work并检查文档，无全量/冻结/暂存/提交/push，不DONE/complete，Goal保持active/6-30。

### 2026-10-08 +08:00｜请求侧启动、取消和到期竞争 [TASK-18-REQUEST-RACE-R01]

- 上轮为实际修复progress；续原Goal/HEAD799fc1cb，空暂存，未重开10。本轮只补原编排套件的两项屏障竞争；产品源码未改，上轮65项不重跑、不冻结。14分钟实测CANCELLED、16分钟STALE，均completed=0/Runner启动0；pending及调度占用归零，其他CREATED注册不变，重复取消/启动后metadata与summary字节稳定。仅现有假Runner与真实文件存储，不冒称全部竞争顺序、独立TTL扫描或真实OS域回收。
- session91857/单元wjc-task18-request-race-r01-20261008，2/2、零失败错误skip，17.423秒退出0；标准XML target/surefire-reports/TEST-com.example.demo.JudgeSandboxOrchestrationTest-task18-request-race-r01.xml已核对两参数实执行。离线Maven仅-Dtest=JudgeSandboxOrchestrationTest#concurrentStartCancelAndRequestExpiryConvergeWithoutResurrectionOrPendingLeak test，沿原只读源码/target及私有tmp可写、空能力集与私网/资源预算；InvocationIDf651b17e5f2c46858c3612a7b7ee2936。
- 核对未批准项及实际源码：run_process角色timeout只覆盖首次wait，杀后wait/pump.join仍无界；不能用外层600秒替代产品收尾，需冻结flush/清理预算，不代设grace。原审批也未冻结17 final字段/兼容规则，不能从“后续都批准”推定schema批准。12/13/14、17及18独立扫描/完整竞争验收阻断保留；只简记task/work及diff检查，不全量/冻结/暂存/提交/push，不DONE/complete，Goal保持active/6-30。

### 2026-10-08 +08:00｜获批原子终态闭环 [TASK-17-ATOMIC-FINAL-R07]

- 续原active Goal/HEAD799fc1cb，已读Goal §3/6、task §1.1及原目标附件；10不重开。用户已明确批准单文件final、保留JudgeProgress字段并增attemptId/revision/finalizedAt、保留无final的旧有效summary且不从事件补造。改动仅DTO、TaskStore/FileTaskStore、Publisher、Scheduler及既有测试：复用原lease/任务锁和移动接缝，无嵌套lease或新管理器；final内容force/原子替换/POSIX目录force，不支持原子移动即失败。final优先冷读/findAll，缺身份或损坏final失败关闭；启动/重试修复metadata/summary投影但不把final当停止或删除授权。冻结首次快照、拒绝统计回退，时间用ISO字符串保留精度，复制/脱敏保留三个新字段；CREATED/direct的attempt保持真实null，终态revision固定1，不宣称非终态版本完成。
- 原有取消IO红测足够，直接修复分文件提交；R01存储/发布99项9失败（提交时间经数值树解析丢精度），修根因未放宽断言。R02/R04因夹具导入/泛型编译失败，未执行测试；R03七套247项246过/1旧调度故障接缝失败，改接finalizeTask后R05/session61782调度41+提交恢复6均绿，21.926秒退出0，未受影响200项复用。覆盖写前/不支持原子替换/替换后/目录force/两投影故障、冷store及启动恢复、冲突终态、晚到帧、坏final、旧summary和跨JVM锁；标准XML均零error/skip。首次命令两次PowerShell解析失败亦保留，不把未执行算红测。
- R06/session51338真实HTTP/C++9/9绿，四种STOMP在新增冷读断言处4失败：valueToTree的LongNode与传输解析IntNode不同；只改为实际序列化JSON比较，仍逐字段整快照相等。R07仅编译夹具后nobody复验该原方法四参数，4/4、零失败错误skip，20.058秒退出0；R06已绿九项不重跑。实测取消completed=3/ac2/wa1、预算17/ac9/wa8，首错2及失败/慢样本保留；final/HTTP/冷读一致。native、SockJS WebSocket、xhr、xhr-polling各40个真实C++ AC，final/冷store/HTTP/STOMP相等，原出站不超车断言仍绿。真实文件障碍使首次取消500但metadata仍CREATED且无final，移除夹具后原ID200/true提交，重复200/false/字节稳定、pending释放、无执行、晚到start409；旧18的部分终态故障语义被此原子提交修复，不保留摘要缺失作为新需求。
- 证据仅现有target/surefire-reports标准XML后缀task17-final-r01/r03/r05/r06/r07。离线Maven：R03选择存储/跨JVM/发布/调度/编排/聚合/摄取七套test；R05仅调度器及提交恢复方法；R06原CreatedTaskCancellationHttpTest、RunningProgressHttpTest及WebSocketSendHttpTest的fortyRealCasesDeliverRunningSummaryAndSameTerminalOverHttpAndStomp方法surefire:test；R07仅该WS方法。沿原NoNewPrivileges/空能力集/私网、源码只读target可写、2GiB私有tmp、4GiB/swap0/2CPU/512pids/600秒及内层540秒，真实运行XML user.name=nobody。InvocationID：R05 def1a37effda46bc940926c60195e1b1，R06 45bd4fc5d58448f589fd9e523ee4cd79，R07 c1936fa7fc804a2182ecc02a006de8cb；collect后的256KiB统计不当资源上界。离线clean/jar描述符警告仍归27，不安装依赖或重跑绿测。
- 自审，非独立放行；仅trusted-local/AUDIT开发环境，未验收Windows目标文件系统、生产provider/TLS或整产品重启/资源上界。11事件预算和12 poll/flush/清理预算等前置仍未批准/交付，完整17及DEF-21不关闭，不DONE/complete。仅简记task/work，文档后只diff检查，不全量/冻结/更新Goal或交接；空暂存、无提交/push，保留IDE/用户改动，Goal保持active/6-30。

### 2026-10-08 +08:00｜旧终态读取与TASK-17定向闭环 [TASK-17-LEGACY-READ-R09]

- 最新策略生效：日常只更新work，停止task/Goal/交接更新。FileTaskStore的find/findAll也优先旧有效终态summary，阻止旧metadata仍CREATED时重新启动；不回填final或改写旧文件，不扩TASK-19恢复范围。旧兼容回归加强状态、拒绝RUNNING及原文件字节不变断言。
- R08：编译及存储/跨JVM/发布/调度/编排五套167/167，零失败错误skip，48.430秒退出0；R09：nobody真实Created取消/写盘失败重试四项、混合AC/WA停止/冷HTTP两项6/6，零失败错误skip，24.765秒退出0。取消3/2/1、预算17/9/8，首错/样本及final字段完整；沿原隔离预算。标准XML后缀task17-final-r08/r09，Maven分别选择上述五套test和CreatedTaskCancellationHttpTest、RunningProgressHttpTest#realMixedResultsSurviveStoppingAndColdHttpRead的surefire:test；未变序列化及四种STOMP的R07证据复用。
- 本切片已闭环；完整17仍缺11/12前置及最终交付验收，不DONE/complete、未提交/push。继续11不依赖待定数值的完整行/UTF8分帧修复；批次、行、累计字节及深度预算仍待具体批准，不代设生产值。文档不触发构建或复验。

### 2026-10-08 +08:00｜TASK-11完整行与UTF8分帧 [TASK-11-FRAMING-R03]

- 两provider沿用原FileChannel/NOFOLLOW、身份与长度检查及行游标，只在LF后严格解码完整行，兼容CRLF；EOF半行/半个UTF8字符不解码、不消费，下次补齐只返回一次。完整非法UTF8/JSON仍走宿主异常，保留原合法事件恢复断言；无新reader框架或生产预算。
- R01六项红测2失败/4错误、零skip，17.886秒退出1，直接复用未重跑。修复后R02编译及Linux/Windows两套62项60过、零失败错误/2既有环境skip，48.894秒退出0；六个半行参数全部实际通过。skip为Linux镜像缺失和当前runtime非Windows，不冒称真实容器/Windows平台通过。
- 在原读取错误参数化测试加完整非法UTF8，R03仅该方法×两provider，6/6、零失败错误skip，30.429秒退出0；R02未受影响结果复用。真实临时文件追加/CRLF/解码及游标路径已验，容器命令为替身。标准报告target/surefire-reports/*-task11-framing-r01/r02/r03.xml；离线Maven test选择两provider及该方法，沿用既有只读源码/target可写、私网和外层资源隔离，不以collect后峰值当产品上界。
- 完整11仍阻断于批次/行/累计字节/字段深度具体预算及有界增量读取、并发替换/同inode覆盖、资源与平台验收。17既有真实HTTP/STOMP证据不受本改动影响、不重复；只更新work，未全量/冻结/暂存/提交/push，不DONE或complete，原Goal保持active。

### 2026-10-08 +08:00｜真实创建写盘失败的浏览器恢复 [TASK-22-CREATE-FS-R01]

- 上轮11分帧为实际progress。本轮核对既有UI发现新提交已经清除旧进度缓存，未因疑似问题修改产品；复用原task22-start-retry-check.cjs，仅加create-fs参数。nobody私有临时根校验后用普通文件阻挡TaskStore创建，转发原始真实HTTP500，finally恢复夹具；无响应码mock、生产参数或新框架。
- R01/session38515两项PASS、pageErrors0，13.988秒退出0：失败时create1/start0、无ID/无轮询/无连接，错误Toast和显式重交按钮可见、输入保留；恢复后用户显式重交才create2/start1，真实C++一例AC，HTTP与浏览器整终态相等，连接峰值1/最终0、终态停止、旧错误隐藏。Chrome149.0.7827.55/Playwright1.61.1、UID65534及既有外层隔离；应用SIGTERM退出143，无强杀。缓存定位首次rg在WSL缺失及版本shell命令参数失败不算测试失败，未安装工具。
- 标准现场证据target/task22-create-fs-r01.json及rejected/create-500-completed两张原图已实看；未复制归档或重验原HTTP503注入矩阵。产品未改，不重编译/全量/冻结。仅更新work；22仍缺完整前置与剩余矩阵，11/12等定量预算阻断保留，不DONE/complete、无暂存/提交/push，Goal保持active。

### 2026-10-08 +08:00｜TASK-11字节增量检查点 [TASK-11-BYTE-CURSOR-R02]

- 两adapter原RunState行游标改为long字节游标，原FileChannel从最后成功提交的LF后seek；只解码/解析新增完整行，不再扫描已消费前缀或收集全文件字符串。保持NOFOLLOW/文件身份与长度、严格UTF8/JSON及原锁；完整批次解析与读后校验全部成功才提交字节位置，EOF半行不提交。无新reader/管理器、参数或限额默认值。
- R01编译及两provider四个受影响读取方法24/24、零失败错误skip，31.802秒退出0；既有半行用例加强真实字节位置断言，六项半行/UTF8/无换行仍通过。R02只验新增非法JSON/UTF8尾行×两provider，4/4、零失败错误skip，17.389秒退出0：已解析本批合法新事件后遇坏行仍不推进游标，无伪事件/清理；仅修复夹具未提交尾行后合法事件返回一次。真实临时文件与读写，外部容器命令替身；原24项未受测试追加影响直接复用。现有Surefire报告后缀task11-byte-cursor-r01/r02，沿既有离线/私网/只读源码和资源隔离，不跑旧Probe或全量。
- 已消费前缀重扫切片闭环；未完成尾行仍需重读，单行/批次/累计/字段深度预算未批准，批次事件列表与尾行内存仍无批准上界，不冒称完整有界reader。并发替换/同inode覆盖、真实目标provider/资源验收及11整体交付仍缺；17终态代码/HTTP证据未受影响，不重验。仅简记work，不更新task/Goal/交接、不冻结/暂存/提交/push，不DONE/complete，原Goal保持active。

### 2026-10-08 +08:00｜必填预算审批阻断 [GOAL-BLOCK-REQUIRED-BUDGETS]

- 上轮字节游标修复及24+4项定向验证为progress，本轮没有新增功能或重测。当前源码仍无事件限额，Python杀后wait/pump.join仍无界；核对TASK-00审批§2，100ms轮询/1s flush/2s清理、256KiB/256条批次/128KiB行上限明确仅为候选，累计字节/字段深度亦未冻结。TASK-26禁用代码及原实际启动拒绝证据已完成，复用而不重做。
- 同一必填参数缺失已连续多轮出现，独立代码/真实验收切片已推进后，剩余关键实现不能安全代设阈值、错误映射或生产身份/停止删除授权。Goal工具实际返回blocked，原目标和全部适用任务范围不变、未complete；等待明确预算审批，不以更多等价绿测/文档替代主线。仅简记work，未改产品/task/Goal/交接，未构建/全量/冻结/暂存/提交/push；无新启动的验收进程。

### 2026-10-08 +08:00｜TASK-11已批准读取预算 [TASK-11-BUDGETS-R01]

- Goal已恢复active；本次批准256KiB/256条批次、128KiB单行、100ms轮询、1s退出flush、2s清理。先在原SandboxProperties/两个Options落实读取三项；原FileChannel分块最多读取批预算，字节计数含LF，行计数为LF前原始UTF8字节（含CR）。超行立即宿主失败、不提交本批；批满保留完整行检查点，后续续读不丢失、不重复。未新增reader/管理器，累计/字段/深度限额没有代设。
- 编译及两provider现有套件106项：104通过、零失败错误、2原环境skip，33.099秒退出0；新增参数化真实临时文件边界、未换行超限、中文/CRLF、批次续读及配置拒绝/绑定均通过。套件内liveProbe分别因缺默认Linux镜像和非Windows运行域跳过，不是目标平台验收；不再次执行。现有Surefire后缀task11-budgets-r01。整体11仍缺累计/字段/深度批准、尾缓冲持久化/并发身份及目标平台资源验收，未DONE、未提交/push；只更新work，17既有有效结果复用。

### 2026-10-08 +08:00｜TASK-11有界尾缓冲 [TASK-11-TAIL-R02]

- 两adapter复用RunState保存最多128KiB尾行，后续从完整行检查点加已缓存字节的位置续读，不重读未完成前缀；本轮256KiB预算计入原尾缓冲。完整批次解析及文件身份校验成功才共同提交游标和尾缓冲，坏JSON/UTF8不破坏原缓存。原回归加强半行→合法补全→坏尾行失败→修复后只返回一次的业务断言。
- 定向两provider读取54/54、零失败错误skip，52.861秒退出0，Surefire后缀task11-tail-r02；已验证配置及不受影响用例复用。首次UID65534执行test在资源复制阶段因既有产物mtime权限失败，退出1、未执行测试；改用原构建身份编译纯Java/文件套件、UID65534仅执行已编译真实验收，未修改权限/配置或忽略失败。整体11仍缺累计/字段单位/深度具体值、生产者大摘要与行限额一致性、并发文件替换及目标平台资源验收，不DONE。

### 2026-10-08 +08:00｜TASK-14杀后等待有界 [TASK-14-CLEANUP-R02]

- 原Python run_process以同一单调case deadline覆盖wait及三路pump，杀后wait/join/monitor共享已批准2s清理截止；Windows taskkill接收有限timeout，Linux允许父退出后终止原进程组。任一进程/线程未结束即明确报停止未确认，不对仍持锁的pipe同步close、不伪报SUCCESS或全域已回收；无新管理器/调度框架。
- UID65534真实Python受影响套件18/18、零失败错误skip，2.985秒退出0；父退出、同组持管道得到TLE，setsid逃逸在预算内报OSError且不假称停止，实验finally只回收自身记录的子进程。真实Python/g++/C++→v2验真/摘要七项7/7，25.512秒退出0，涵盖AC、编译错误、WA/TLE/OLE及SPJ正常/拒绝/超时/内存基础设施错误，Surefire后缀task14-cleanup-cpp-r02。Windows停止命令仅单位验证，不冒称Hyper-V或全进程域回收；14完整身份/停止确认、占用释放及目标平台验收仍未完成。

### 2026-10-08 +08:00｜TASK-12空批轮询与退出drain [TASK-12-POLL-R02]

- 原Runner接口增加兼容的三态查询及带剩余预算poll重载；Linux/Windows对已启动句柄只读检查Running/Pid，supervisor沿用原可信journal、配置/代际身份和锁校验，不改控制文件。原JudgeService空ACTIVE批按100ms可中断等待，EXITED最多1s继续drain、零退出不代替协议终态；未知/null状态仍失败关闭，不代设重试次数。循环复用原任务预算/取消令牌，Worker沿原HTTP request timeout取配置与剩余预算较小值，保留认证/中断/宿主失败传播，不新增Worker端点。
- R01编译及编排/契约/状态定向52/52，39.663秒；HTTP接入后仅受影响18/18，46.273秒；均退出0、零失败错误skip。真实回环HTTPS、临时可信证书和Bearer请求验证服务端确已接收后100ms截止、不等待原2s、不伪造事件；EXITED后传入poll的预算不超过剩余flush。原取消/预算、首终态统计及清理失败保留占用断言保持，未受影响的状态/读取结果复用。Surefire后缀task12-poll-r01、task12-http-r02；编排及runtime CLI为现有替身，不是部署Worker/容器真实验收。
- 剩余：11累计/字段长度及计数单位/JSON深度未给具体值；12启动和整体清理调用的统一总deadline、UNKNOWN有限重试及真实Worker状态/游标仍未完整实现；目标生产身份/运行时代际、停止与卷删除授权及拟启用provider环境未具备。记录对应任务边界，不扩17/19，不猜参数。仅更新work、不冻结/全量/提交/push、不标DONE/complete，原Goal保持active；本轮所有验收unit已退出。

### 2026-10-08 +08:00｜TASK-12启动及清理总deadline [TASK-12-DEADLINE-R03]

- 两adapter原CLI执行器的wait与stdout/stderr捕获共用单调截止，捕获失败传播宿主异常，不再无界join或把异常消息当成功输出；UID65534真实Python CLI正常、睡眠超时及父退出后子持管道六参数6/6，4.194秒、退出0、零失败错误skip，报告后缀task12-cli-r02。仅终止自身CLI，不声称整个执行域已停止。
- Scheduler复用原任务预算起点，源码准备、Runner启动及后续poll消费同一剩余预算；两adapter协议检查/启动、supervisor镜像检查/资源创建及Worker GET→POST不重置截止。实际POST后传输/ACK不明抛unknown-start、无虚构unavailable句柄，沿原cleanupPending保留占用；POST前明确不可用行为及原startedAt含义保留。首轮编译27.340秒退出1，定位并补齐测试Map导入；随后启动切片113/113、49.535秒、退出0、零失败错误skip，报告后缀task12-startup-r01。
- 取消与残余清理沿现有Runner接口传已批准2s共享剩余预算；supervisor可信身份检查、合作退出、停止ACK及最终Running/Pid确认同用一个截止。耗尽即保留STOP_REQUESTED/CLEANUP_PENDING和运行占用，不在截止后再请求外部停止、不把ACK当全域回收或扩大stage/volume删除授权。原超时用例改为更强的未确认/不新增停止命令断言，未新增管理器或调度框架。受影响编排32项及契约/停止/HTTP切片合计55/55、51.965秒、退出0、零失败错误skip，报告后缀task12-cleanup-r01。
- 真实回环TLS/Bearer已接收GET及POST后验证400ms总启动截止，晚到202 ACK不能生成句柄；取消/清理POST两参数确已到服务端，100ms调用截止不等待原2s。可信journal/marker使用真实临时文件系统，supervisor/container CLI仍是原替身，不冒称部署Worker、Hyper-V或完整Linux进程域停止验收；未受影响结果复用，未重跑旧Probe/取消红测或TASK-17验收。
- 剩余：12 UNKNOWN/传输错误有限重试及真实Worker状态/游标；11累计字节、字段长度/计数单位和JSON深度待具体批准；14全域停止确认/释放及目标平台资源验收仍待生产身份、运行时代际、停止/卷删除范围和拟启用provider环境。原Goal保持active，TASK-12未DONE；仅更新work，未全量/冻结/暂存/提交/push，本轮全部验收unit已退出。

### 2026-10-08 +08:00｜TASK-11/12事件检查点与停止仲裁 [TASK-11-12-READ-STOP-R02]

- 两adapter的null JSON记录原先在批次检查点提交后触发NPE，修正文件后无法续读；终态读取还会自行调用旧30s清理而绕过编排预算。原设施四参数R01为2失败/2错误、18.769秒，报告task11-checkpoint-r01保留；临时shell包装错误返回0，不计通过，已改回直接WSL --exec/systemd-run/Maven，无副作用false校验确认失败退出1，不新增验证框架。
- 最小修复在提交检查点/尾缓冲前拒绝null，修正后两事件各读一次；poll不再执行rm，清理只沿原显式取消/受限finally，删除两处已无用途的terminal helper。真实临时文件、UTF8/身份/批次/游标及显式预算定向60/60、30.462秒、退出0、零失败错误skip，报告task11-checkpoint-r02；runtime CLI仍为原替身，不冒称Docker/Hyper-V实机停止。
- JudgeService在每条事件接收前复核原取消令牌与单调剩余预算，阻止阻塞poll晚到的结果/SUMMARY/COMPLETED先落成功终态，保留此前已验收统计。受影响编排34/34、47.360秒、退出0、零失败错误skip，报告task12-late-events-r01；新增用户取消/实际500ms夹具截止两参数均保持total=2/completed=1/ac=1、文件状态/摘要一致和原取消/清理入口，未放宽首终态断言，未新增生产参数。
- 剩余具体限额、UNKNOWN/传输重试、真实Worker状态/游标及生产身份/平台/停止和卷删除授权仍按11/12/14任务边界保留，不扩17/19。仅追加work，未重跑旧Probe/取消红测、未全量/冻结/提交/push；本轮unit均已退出，TASK-11/12未DONE，原Goal保持active。

### 2026-10-08 +08:00｜TASK-11类型与TASK-12未创建域 [TASK-11-12-TYPE-NOCREATE-R01]

- 三adapter沿原读取分支拒绝缺失/null事件type；文件批次失败不提交游标/尾缓冲，修正后完整续读、不重复或自行清理；HTTP错误仍为宿主失败，不伪造事件。仅扩充原参数化向量，文件/真实回环HTTPS定向71/71、35.272秒、退出0、零失败错误skip，报告task11-type-r01。
- Remote原本为POST前能力失败生成本地unavailable句柄，但其清理仍报停止未确认并永久占用运行许可。沿现有保留ID及已禁止的服务端同名ACK区分本地未创建域：只释放本句柄缓存，兼容入口统一委托带预算入口，重复清理幂等；真实/未知run仍不能凭202/404/409取消ACK确认停止，无新缓存/管理器/权限。
- 定向23/23、38.934秒、退出0、零失败错误skip，报告task12-no-create-r01。真实TLS/Bearer能力GET第一请求受控等待，使第二任务确实QUEUED；返回503后两任务均SANDBOX_UNAVAILABLE、completed/ac=0、运行及排队许可归零，远程runs下请求数为0。原本地清理用例强化为重复调用、另一attempt缓存保留且无POST/cancel；POST后错误/保留ID ACK仍抛未知且无本地失败缓存，throw/null未知启动仍保留占用，不以虚构未启动或删除授权替代停止证明。
- 仅本地未请求运行路径闭环，不冒称真实Worker执行域回收或平台验收；累计/字段/深度具体限额、UNKNOWN重试、Worker状态/游标及生产身份/运行代际/停止和卷删除范围仍有阻断。仅更新work、未旧Probe/取消红测/全量/冻结/提交/push，验收unit均已退出；原Goal active，TASK-11/12未DONE。

### 2026-10-08 +08:00｜TASK-14 Java等待共享截止 [TASK-14-JAVA-DEADLINE-R03]

- DirectProcessRunner从原启动起点扣除monitor/pump准备耗时；原强停回调、已发现后代等待及stdout/stderr捕获共享一次killGrace起点，不逐流重置预算。等待中断先终止本次进程再传播；截止后捕获不完整仍报IOException，TLE诊断不冒称停止确认。复用原捕获器及原异常/状态，超限标记在强停前保存，零等待预算仍保留OLE，未新增管理器或生产参数。
- 新增回归沿原ProcessRunnerTest运行真实JVM/双流管道及临时身份文件。编译R01因误用Java21 List.getFirst不兼容release17退出1，已改get(0)。红测task14-java-deadline-r01为2项、1失败/1错误、7.731秒退出1：准备耗时1438ms超过1100ms；隔离UID下子进程arguments不可读，夹具改用原PID+startInstant精确身份后清理，不扩大停止范围。R02两套67项66通过/1失败、21.115秒退出1，双流断言误计JVM启动总耗时1324ms；改为实际父退出后测700ms共享预算，仍要求1s内抛捕获IOException，未放宽业务/安全语义。
- 最终Java17编译成功；UID65534、原只写target/私有tmp/隔离网络及4GiB/2CPU/512pids/600s外层约束下，mvn离线surefire:test -Dtest=ProcessRunnerTest -Dsurefire.reportNameSuffix=task14-java-deadline-r03为22/22、7.375秒、退出0、零失败/错误/skip。涵盖启动准备截止、父正常退出子持双流、等待中断、stdout/stderr零与非零grace超限及原IO/MLE/输出共享预算。标准XML已核对；R02业务基线47/47结果保留，不计为最终全量，Python/C++未受影响证据复用。
- 接入收尾R04：JudgeService编译与程序执行请求去掉旧5s覆盖，复用ProcessRunner原已批准2s默认值，覆盖生成器/候选/答案/SPJ及g++诊断。仅因此源码变更编译，再以UID65534运行原JudgeBaselineTest的AC三例、编译错误、生成器IO/输出超限及普通/SPJ答案超限切片，6/6、22.844秒、退出0、零失败/错误/skip，标准报告task14-java-budget-r04；真实g++/C++和临时文件执行，HTTP为原MockMvc控制器验收，不冒称真实TCP/部署provider，R03进程测试未受此次调用方默认接入影响而复用。
- 本切片只证明有界等待及已发现进程处理，不是父退出/setsid后的完整域回收：持管道实验的残留子进程由夹具finally核对自有PID+startInstant后停止，不能算产品清理成功。14完整身份/代际、停止与卷删除范围、真实provider及适用平台验收仍阻断；原Goal active、14未DONE，仅追加work，未重开10/17、全量/冻结/暂存/提交/push，验收unit均已终态退出。

### 2026-10-08 +08:00｜TASK-19恢复及过期清理预算 [TASK-19-CLEANUP-BUDGET-R03]

- TaskCleanupService原残余句柄清理仍调用无预算入口，绕过已批准2s；只改为现有cleanupResidual(handle, Duration.ofSeconds(2))，启动恢复和过期清理共用，未新增管理器、配置或整轮扫描预算。原Runner停止未确认仍报失败，不能先写STALE或据取消ACK删除目录。
- 沿原ProductionCleanup/TaskCleanup加强预算及禁止无预算入口断言。R01六项5预算失败/1旧夹具错误、3.935秒退出1；按已批准17语义将终态句柄在初始历史数据中保存，不再终态后saveRunHandle，不改产品终态保护。R02三套49项44通过/5旧摘要字节断言失败、18.032秒退出1：原断言要求首次STALE后仍保留非终态summary全文，已改为保留完整统计/结果/样本并验证权威STALE及再次初始化字节稳定，未放宽安全或统计断言。
- 仅重验受影响五非终态参数R03为5/5、4.396秒、退出0、零失败/错误/skip；实测total=12/completed=3/ac=2/wa=1、firstFailedCase=2、失败/慢样本及三结果保留，停止原因关联RESTART_INTERRUPTED，final与summary一致、revision=1，再初始化metadata/events/summary及case数据字节不变。R02其余24恢复、18清理、2真实TLS参数共44项有效结果复用；标准报告后缀task19-cleanup-budget-r01/r02/r03，均核对实际报告，不重复全量/归档。
- UID65534及原只写target/私有tmp/隔离网络、4GiB/2CPU/512pids/600s约束；真实回环TLS/Bearer两入口确已收到POST，在Worker配置5s时遵守调用方2s，超时后启动报错或过期清理列失败，metadata/events/句柄及canary保留、不伪报终态/删除。恢复停止仍为原替身或未确认HTTPS应答，不是部署Worker全域回收/完整产品进程重启；UNKNOWN权威对账、持久占用恢复/释放及真实域身份/代际/停止与卷删除范围、provider环境仍阻断。仅更新work，未重开10/17、冻结/全量/暂存/提交/push，unit均已退出；19未DONE，原Goal active。

### 2026-10-08 +08:00｜TASK-11缓存尾段身份一致性 [TASK-11-TAIL-IDENTITY-R02]

- 两adapter原先直接跳过缓存尾段；同inode文件变长时改写已缓存judgeId，旧缓存与新后缀仍拼成合法旧身份事件。最小修复复用原FileChannel及8KiB缓冲，从原完整行检查点只校验当前缓存尾段，字节不符或缺失即宿主失败、不提交游标/尾缓存；不重读已提交历史，不新增reader/管理器或生产参数。校验尾段已计入原previousLine.length批次预算，校验磁盘读取加新字节读取仍不超过原批次上限。
- 沿原半行JSON/UTF8跨块/完整JSON待LF三参数加同inode改写、缓存及游标不变、恢复原前缀后完整续读/不重复/无自动rm断言。R01两provider六参数全红、3.564秒退出1、零错误/skip，确因缓存掩盖改写而未抛错；修复后仅受影响读取/检查点/UTF8/行/字节/条数预算七方法共54/54、5.731秒退出0、零失败/错误/skip。标准报告task11-tail-identity-r01/r02，原红证据保留，无固定测试数量门禁或第二套复现。
- Java17编译成功，UID65534及原只写target/私有tmp/隔离网络、4GiB/2CPU/512pids/600s约束；实测临时文件inode保持不变、身份前缀确实被改写，三种尾段边界均拒绝后可恢复。runtime仍为原CLI替身，Windows adapter文件逻辑运行于Linux，不冒称Docker/Hyper-V或Windows文件系统验收。当前校验只覆盖观察到的缓存尾段，不证明已提交历史或读取期间全文件原子性；累计字节、字段单位/长度、JSON深度具体值及完整并发替换/平台/资源上界仍待原任务收敛。
- 只追加work，复用未受影响的停止/启动/HTTP/17结果，未重开10、旧Probe/取消红测、全量/冻结/暂存/提交/push；本轮unit均退出，11未DONE，原Goal active，全部适用任务及31缺陷目标不缩减。

### 2026-10-08 +08:00｜TASK-11事件读取provider边界 [TASK-11-PROVIDER-R01]

- Linux/Windows adapter的pollEvents原先直接按runId查缓存，外来provider若同ID可消费合法运行的事件游标。两入口最小复用executionState/cancel已有provider校验，null句柄或缺失/外来provider在读取前拒绝；无新身份框架、配置或清理动作。
- 原provider测试中参数化覆盖null句柄、null/空provider、remote-worker及另一container provider；使用真实临时事件文件和相同runId，拒绝后文件字节不变，合法句柄仍完整消费一次且续读为空，未新增runtime命令。编译R01因新夹具误用事件构造参数退出1，修正为原11字段契约后Java17编译通过；仅受影响poll/cancel/state入口四方法共24/24、3.840秒、退出0、零失败/错误/skip，标准报告task11-provider-r01确认两套各12项实际执行。
- 沿用UID65534、隔离网络/私有tmp及4GiB/2CPU/512pids/600s外层预算；CLI仍为原替身，Windows文件逻辑在Linux执行，不冒称实机provider验收。累计事件字节、字段单位/长度、JSON深度具体值及完整并发替换/平台/资源上界仍待11闭环；未改task/Goal/交接，未全量/冻结/提交/push，两unit均退出，TASK-11未DONE、原Goal active。

### 2026-10-08 +08:00｜TASK-18启动审计追加失败回滚 [TASK-18-AUDIT-R03]

- 原FileTaskStore.updateStatus先写metadata再追加events；追加失败时启动返回500但留下未实际入队的QUEUED，前端原ID对账只查询而不能重新启动。归18的准入写盘一致性及22实际恢复切片，不重开17。最小修复在原任务锁内恢复本次改变的status/message/startedAt并重新落盘，仍传播原IOException；回滚也失败则保留其suppressed异常，不伪报CREATED、成功或终态。额度关联、原锁、首终态及审计失败语义不变，无新框架/参数。
- 原文件套件新增QUEUED/PENDING/COMPILING/RUNNING四参数，真实events路径目录障碍证明metadata曾部分提交。R01四项全红、3.353秒退出1；修复后受影响存储82+调度41共123/123、12.246秒退出0，零失败/错误/skip，要求原metadata字节、冷读全部字段及每日额度关联恢复、显式重试仅一条新事件。新增回滚move边界故障单例R03仅验该项1/1、3.040秒退出0：原追加异常及回滚异常均保留，实际QUEUED和计费关联不伪造回退，无final/summary成功记录；原123项直接复用。Java17编译及标准Surefire报告task18-audit-r01/r02/r03均已核对。
- 仅在原task22-start-retry-check.cjs补start-audit-fs参数；校验私有tmp根/任务realpath后暂存events并建立目录障碍，转发真实服务端响应、finally恢复，无响应码mock或权限扩大。Chromium149.0.7827.55/Playwright1.61.1沙箱真实两项PASS、pageErrors0、13.397秒退出0：启动500后CREATED/输入/原ID保留、提示可见且WS归零；显式同ID重试500→200、create/start=1/2，真实C++一例AC、HTTP与浏览器整终态相等，查询及连接峰值1/最终停止、旧错误隐藏。证据target/task18-start-audit-fs-r01.json及start-500-completed图已实看，应用SIGTERM143、无强杀。
- 沿用UID65534、私网/私有tmp、只读源码及仅target可写、4GiB/2CPU/512pids/600s预算；仅证明进程内写入失败回滚及trusted-local/Linux真实恢复，不声称跨文件断电原子性、磁盘持续故障自动恢复或容器整域停止。回滚持续失败保持真实存储阻断，恢复仍归19；18独立TTL扫描具体间隔、14/19实际运行域与其余任务待定参数/验收仍待闭环。只更新work，未全量/冻结/改task/Goal/交接/提交/push，本轮unit均退出，18/22未DONE、原Goal active。

### 2026-10-08 +08:00｜TASK-18/23准备与运行阶段保序 [TASK-18-23-PHASE-R02]

- 最小修改三处现有入口：Scheduler交接job时写PENDING而非提前RUNNING，保留原启动时间/许可/预算；JudgeService编译完成后复用原聚合器发布RUNNING及零完成摘要，首例执行不再停留COMPILING；Ingestor复用latestProgress，使后续角色compile/run通知不能回退全局RUNNING或进度，编译错误仍走原终态入口。无新状态、管理器、锁或配置。旧夹具改为在PENDING交接点注入取消，模拟已执行结果的job显式写RUNNING，取消/首终态/统计断言未放宽。
- 新调度单例与三种后续角色事件R01四项全红、5.119秒退出1（提前RUNNING、两次回退COMPILING、57→15）。修复后Java17编译成功；仅调度42+验真79+编排35共156/156、27.082秒退出0，零失败/错误/skip，冷读、已有一例AC及后续二例AC、身份/序号、未确认清理保留均通过。标准报告task18-phase-r01/r02关键方法实际执行。
- 原RunningProgressHttpTest仅加强真实首例及审计轨迹，5/5、43.583秒退出0、零失败/错误/skip，报告task18-phase-http-r02：两个真实USER程序仍存活时HTTP及metadata均RUNNING，total=1/completed=0、七分类全零、无全量results；四任务审计顺序CREATED→QUEUED→PENDING→COMPILING→RUNNING→AC，无正常阶段倒退，原owner/global许可和四例AC保持。原40例AC/WA及取消/预算统计、冷读final和节流释放也通过。只读工作区导致FILE日志落盘警告，console/Surefire证据保留，不冒称日志落盘验收、不扩大权限或为清除警告重复绿测。
- 沿原nobody隔离预算，仅trusted-local/Linux及协议/磁盘切片，不冒称真实容器/Worker域验收；18独立TTL扫描具体间隔、23容量/TTL/积压预算及14/19真实恢复仍待闭环。仅更新work和检查差异，未全量/冻结/改task/Goal/交接/提交/push，unit均退出，18/23未DONE、原Goal active。

### 2026-10-08 +08:00｜TASK-06/24真实浏览器跨owner订阅隔离 [TASK-24-WS-OWNER-R01]

- 仅在原task22-start-retry-check.cjs加入ws-owner验收模式，无产品源码或新测试框架；node语法检查通过。隔离WSL unit codex-task24-ws-owner-r01-20261008/session43661实际2/2 PASS、pageErrors0、12.159秒退出0，Chromium149.0.7827.55沙箱开启，应用SIGTERM143无强杀；沿原UID65534、私网、只写target/私有tmp及资源预算，未构建或重复已有登录/取消回归。
- 两个独立浏览器会话真实登录且Cookie不同：已认证B查询A任务返回404；B携真实CSRF建立SockJS/STOMP连接，确实CONNECTED后订阅A任务收到ERROR并关闭，消息0。A原UI订阅收到4条真实进度/终态，释放私有gate后真实C++一例AC，HTTP、WS整终态及UI缓存一致，B仍消息0。证据target/task24-ws-owner-r01.json及completed.png，完成图已看；凭证脱敏/日志检查通过，不冒称空闲撤销、容量/TTL或全部24验收完成。
- 需求外待办：详情模态框异步请求缺少晚到响应的任务/用例隔离，可能旧详情覆盖新选择；不属当前创建/启动/查询及进度跟踪验收范围，本阶段不扩修。仅追加work；06/24及原Goal仍未完成，不暂存/提交/push。

### 2026-10-08 +08:00｜TASK-19有效final下坏摘要投影恢复 [TASK-19-SUMMARY-R02]

- FileTaskStore原修复入口遇到summary.json坏JSON只警告，后续启动也不重写。仅在原文件校验后捕获JSON解析异常，按已验证final用原原子写入重建摘要；真实文件IO错误及非法final仍沿原失败路径，不新增字段/锁/管理器或放宽执行停止判定。参数化覆盖空摘要、坏JSON、过期RUNNING投影，要求冷读整终态一致、final/metadata/events原字节不变、再次恢复无额外写入。
- R01三参数2过/1解析错误、零skip、16.927秒退出1，充分证明坏JSON未恢复；修复后Java17编译成功，存储86+清理18+恢复29共133/133、33.344秒退出0，零失败/错误/skip，关键原子提交故障、非法final及三新参数在标准报告task19-summary-r02确实执行。首次nobody离线Maven未解析父POM、未执行测试；按专项批准复用原产物拥有者root，仅可信文件套件，能力清空/离线缓存只读/只写target与私有tmp，不改权限、不运行C++。
- 原真实应用入口只补restart-final模式，不建第二套框架。nobody真实账户HTTP创建并取消未启动任务，确认startedAt/句柄为空、原final为CANCELLED；正常停止产品后只破坏metadata投影及summary JSON，不改final，随后同目录两个全新产品PID重新登录查询均PASS。第一次启动恢复原metadata/summary且HTTP整终态一致，第二次metadata/summary/events字节稳定，final两次均原字节；三产品均SIGTERM143无强杀、pageErrors0，unit/session9928退出0、27.374秒，证据target/task19-final-restart-r01.json。实际日志落盘及凭证检查通过，沿原私网/沙箱浏览器/资源预算，不为文档重构建。
- 本切片只证明无执行域的真实产品重启及final投影恢复，不代表五种活跃执行域、UNKNOWN权威对账、持久占用重建/回收释放或容器/Worker部署通过；这些仍按14/19及原待定身份、代际、停止/卷删除授权处理。复用无关有效结果，仅追加work；全部本轮unit已退出，19未DONE、原Goal active，无暂存/提交/push或task/Goal/交接更新。

### 2026-10-08 +08:00｜TASK-19孤立final不能绕过启动检查 [TASK-19-ORPHAN-FINAL-R02]

- 原loadAllTasks在metadata缺失时只检查受管执行证据，随后过滤目录，导致存在final的任务也被静默漏掉。仅在原扫描/NOFOLLOW检查内增加final存在时失败关闭，不猜归属、补造metadata或删除目录；无final/受管证据的普通未完成创建仍可保留原行为。复用原恢复参数化设施，真实存储提交CANCELLED后暂存metadata，覆盖合法及坏JSON final，要求启动失败、无清理调用且final/summary/events/case数据及暂存metadata字节保持。
- 两参数R01全红、零错误/skip、20.221秒退出1（启动未抛错）；最小修复Java17编译成功，存储86+恢复31+清理18+配额13共148/148、40.266秒退出0，零失败/错误/skip。标准报告task19-orphan-final-r02已确认新两参数及普通未完成创建兼容用例实际执行；沿既有离线、能力清空、只写target/私有tmp隔离编译边界，没有全量/冻结或新测试门禁。
- 原restart入口只补restart-orphan-final模式。session41585/unit codex-task19-orphan-final-http-r01-20261008真实账户HTTP创建并取消未启动任务，正常SIGTERM143后仅暂存私有metadata；新产品进程确实启动失败退出1，未发布HTTP就绪，原final/summary/events及暂存metadata保持。脚本1/1 PASS、pageErrors0、15.621秒退出0，证据target/task19-orphan-final-http-r01.json，日志落盘/凭证检查通过；未改final或伪造终态。原nobody/私网/沙箱浏览器与资源预算保持，全部unit终态退出。UNKNOWN对账、持久占用恢复及真实域身份/停止/删除授权仍阻断完整19；仅更新work，不标DONE/complete、提交或push。

### 2026-10-08 +08:00｜TASK-26原生禁用门禁先于恢复 [TASK-26-NATIVE-DISABLE-R09]

- 现有Windows JDK在沙箱内被拒绝访问，专项批准后实际版本Temurin21.0.12.1+1、平台win32/10.0.26200。原生夹具R03～R05均未进入产品，分别保留空classpath项/转义及JShell无法加载BCrypt的失败；只修夹具输入和主入口，97个已有公共依赖仅复制一次到target私有目录，后续复用，不安装/升级依赖或改变原缓存。中断前句柄失效后，实际报告FAIL且专属进程查询无存活，未盲目重启。失败报告均保留，不把初始化错误当禁用成功。
- R06真实完整windows-prod启动先在TaskCleanupService因生命周期路径不可识别失败，尚未到DISABLED_UNVERIFIED。只改原ProductionSecurityStartupValidator提前拒绝已批准禁用provider并复用原判定；TaskCleanupService在原初始化前可选取得验证器，独立恢复上下文仍可运行。完整验证/原Linux探针仍沿afterSingletons入口，trusted-local语义、文件身份/锁检查均未放宽，无新管理器。首版强制命名依赖R07共205项178过/21失败/6错误/零skip，定位全部为独立恢复上下文缺验证器；移除强制依赖改为现有ObjectProvider可选注入，未改业务断言。
- R08 Java17编译及受影响验证器54+真实认证63+恢复31+清理18共166/166、零失败/错误/skip、Maven1分10秒退出0；报告task26-early-disable-r08关键配置绕过/不调用禁用探针/两个完整产品启动及恢复负例确实执行。R07其余Worker配置18、认证默认19、Yaml2的有效绿测直接复用；只读cwd的FILE日志警告仍只认console/XML，不重跑清警告。隔离离线Maven沿原能力清空、私网、只写target/私有tmp及资源预算。
- R09原生Windows两个全新产品PID17856/23920均明确DISABLED_UNVERIFIED，正常退出1、无超时强杀或HTTP就绪；probe-required=false与自报probe-passed=true不能启用。脚本2/2 PASS、6.201秒退出0，应用日志落盘且测试口令/token未泄漏；证据target/task26-native-disable-r09.json及两profile.log，复用当前classes、未重编译/复制依赖。只证明原生主机生产禁用，不证明Windows runner artifact/ACL/Job Object、trusted-local Windows文件身份或部署Worker支持；26及完整Goal仍未DONE/complete。仅追加work，未全量/冻结/改task或Goal/暂存/提交/push，本轮已确认验收句柄均终态。

### 2026-10-08 +08:00｜TASK-03具体参数阻断 [TASK-03-PARAMETERS-BLOCK-R01]

- 按最新执行策略接续；当前AuthConfiguration仍只有既有maxAttempts=5、attemptResetHours=1，两认证服务的attempts仍为ConcurrentHashMap。独立来源/账户失败阈值与时窗、状态容量与主动TTL、可信代理CIDR及代理头字节上限没有具体批准，不能把旧键的5次/1小时自动套用为新的独立限制。需用户明确上述配置，才能继续有界认证实现。
- 既有直连来源、原子尝试记录及TASK-26原生禁用结果直接复用。本阶段没有源码修复、新测试或功能进展，明确记录决策阻断；仅追加work，未重跑、冻结、更新task/Goal/交接、暂存、提交或push，原Goal保持active，未标DONE/complete。
- 后续两个自动接续轮次仍无功能进展：TASK-03具体配置未提供，TASK-15无损精度接入仍受数字解析预算阻断，其他未决预算/执行域环境亦未改变。连续三个Goal轮次达到阻断审计门槛，原Goal现已设为blocked（未complete、不新建目标）；收到具体配置或必要环境后可继续原目标。本轮仅记录这一实际状态变更，未重测、扩工具或改源码。

### 2026-10-08 +08:00｜TASK-03独立限流、容量与TTL闭环 [TASK-03-BOUNDS-R03]

- 用户授权选择合理参数，原Goal已恢复active。保留maxAttempts=5/attemptResetHours=1：账户级及旧访问码来源级仍为5次/小时；账户模式额外来源级30次/10分钟，访问码全局300次/10分钟。每认证服务失败状态硬上限10000（账户/来源合计；访问码另有一个常量全局记录），闲置TTL2小时、原清理调度改为每60秒；活跃会话上限10000，原24小时/30天会话有效期不变。默认可信代理为空，仍只取socket；代理字段工程初值4096字节/16跳，本切片尚未接入解析，不宣称该限额已生效。
- 复用原compute与清理设施：账户/来源分开计数，成功账户登录不清来源累计失败；容量准入后computeIfPresent避免清理后的重新插入越界，不淘汰有效封禁。账户/来源键固定SHA-256长度，访问码增加有界全局在途预留并finally释放，空码也计失败；会话饱和明确HTTP503、无成功体/新Cookie，原有效会话不被驱逐。旧并发夹具将无关请求明确为不同账户，原等待/检查次数/成功清自身断言保留，新增跨来源账户锁定、跨账户来源锁定、并发硬容量及可替换时钟过期断言。
- 初次Maven因PowerShell参数拆分在测试前退出1，不计红测；引用修正后R01a新5项全红、零错误/skip、25.875秒退出1。修复后R02编译及八个受影响套件136/136，56.544秒退出0、零失败/错误/skip；标准XML确认新12项实际执行。R03仅补跑新增真实随机端口HTTP3/3，25.479秒退出0、零失败/错误/skip：伪头/账户轮换后429，双模式饱和503且无新会话、原会话仍可访问。证据为现有Surefire后缀task03-auth-bounds-r01a/r02及task03-auth-bounds-http-r03；源码只读、target/私有tmp可写、离线私网、能力清空及原资源上限保持，只读cwd的FILE日志警告仅认console/XML，不当文件日志证据。
- 本轮相关unit/会话均已正常终态退出。可信代理统一HTTP/WS解析及其真实正反例、任务交付一次必要全量仍待后续切片；不据此标TASK-03 DONE或Goal complete。只追加work，未改task/Goal/交接、冻结、暂存、提交或push；最终提醒用户这些初值可按实际NAT/代理及容量调整，不猜生产CIDR、UID或停止权限。

### 2026-10-08 +08:00｜TASK-03可信来源闭环与回归阻断 [TASK-03-SOURCE-R09]

- ClientAddressResolver已统一接入登录、HTTP拦截器及WS握手。默认可信CIDR为空；仅显式受信socket对端解析指定XFF/X-Real-IP，右向左剥离可信跳，累计4096字节/16跳，拒绝非法链、主机名及框架二次解析。真实127.0.0.2非可信对端忽略伪头；不代设生产CIDR，不把握手验收当完整STOMP撤销验收。
- R04编译及受影响回归148/148；R05真实HTTP/WS为7过/8失败，发现原通用异常处理将400转为500。仅补现有ResponseStatusException映射，未改400断言；R06为39/39，包含真实HTTP/WS17项、三个在途并发边界及实际调度清理。均零错误/skip、正常退出；证据为原Surefire后缀task03-source-proxy-r04/r05/r06。
- 必要全量R07因nobody资源复制权限在测试前退出1，复用已编译产物，以原低权限入口R07a执行一次：3174项、5失败/101错误/455跳过、Maven退出1（5:24），不报全量绿色。99错误来自共享/tmp/online-judge夹具污染，实际启动恢复正确拒绝未确认停止；其余2错误为旧LinuxRuntimeJournal夹具修改文件owner权限不足。455跳过为root-writer/身份及Docker环境前提，未扩大权限或重跑旧Probe。
- 归TASK-01/24的最小夹具修复：AuditAndAdminTest、JudgeBaselineTest、HighVolumeJudgeIntegrationTest及RunningProgressHttpTest仅各增加两行，复用现有IsolatedTaskStore，无新框架、mock或业务断言变更。R08为135过/17启动错误（剩余污染已定位RunningProgress）；最后只复验两套，R09编译成功、22/22、46.703秒退出0，零失败/错误/skip。现有XML确认九套152个不同用例分阶段有效通过；未重跑其余全量。
- 剩余全量阻断按所属任务保留：TASK-14进程身份startInstant相差3秒的安全断言失败；TASK-23/24四transport运行摘要用例RUNNING/AC断言失败；权限夹具及平台验收仍需相应执行条件。保留失败报告和有效阶段证据，不放宽身份/恢复检查，不据此交付TASK-03 DONE或Goal complete。
- 本轮unit均终态退出；只更新work，未改task/Goal/交接、冻结、暂存、提交或push，保留其他任务及IDE改动。源码只读、私网/私有tmp及原资源预算不变；FILE日志只读告警不当落盘证据。最终提醒用户BOUNDS-R03工程初值及当前默认不信任任何代理。

### 2026-10-08 +08:00｜TASK-23首个非空运行摘要绕节流 [TASK-23-FIRST-RUNNING-R03]

- 复用R07a四transport真实红测，定位TASK-18新增空RUNNING占用节流窗口、快任务首个非空摘要被丢弃。ProgressPublisher仅在原SentState增加一个布尔字段，首个含完成数的RUNNING立即发送，后续普通节流及终态/缓存回收不变；新增1/40参数回归，未改原真实WS业务断言或新增框架。
- R01编译及发布器23/23（37.100秒）；R02原四transport/40例真实C++、HTTP/STOMP为4/4（19.501秒），首RUNNING阻住至真实40 AC、后帧不抢越、统计单调及整终态相等均通过；R03仅受影响沙箱事件/高容量三套85/85（20.788秒）。均退出0、零失败/错误/skip，标准XML确认实际执行；合成高容量不能代替真实provider/资源上界验收。

### 2026-10-08 +08:00｜TASK-14进程出生身份夹具最小修复 [TASK-14-IDENTITY-R04]

- R07a已有同PID跨JVM startInstant相差3秒失败，不重跑旧红测。仅改ProcessRunnerTest私有夹具：Linux读取并核对PID及内核出生tick，读失败仍拒绝停止；其他平台保留原startInstant核对。真实持双管道子进程、停止后isAlive=false及原700ms/1秒收尾断言均保留，未改产品回收授权或放宽时限。
- 编译成功后沿原nobody/私网/私有tmp和资源预算，原22项全部通过、6.344秒退出0、零失败/错误/skip，标准报告task14-identity-r04；本轮unit均终态。五个全量失败已分别定向转绿，但旧权限错误/455环境skip、14真实执行域/平台及23容量/TTL/出站积压等阻断仍保留，不报全量绿或DONE/complete。只追加work、检查差异，无冻结、提交/push或共享daemon操作，原Goal保持active。

### 2026-10-08 +08:00｜TASK-12 UNKNOWN沿现有预算等待 [TASK-12-UNKNOWN-WAIT-R03]

- 复用R01红测（3项中UNKNOWN预期AC却SYSTEM_ERROR，退出1），JudgeService仅移除UNKNOWN立即失败条件；继续沿原100ms轮询等待及单调任务总预算，null/非法批次仍失败关闭，EXITED原1秒drain和2秒清理不变，无新限额或框架。
- R02编译因新真实TLS测试缺Instant导入退出1，补单个导入后R03编译退出0；nobody/私网/只读源码及原资源预算下编排全套38/38、29.361秒退出0、零失败/错误/skip，XML确认ACTIVE/EXITED/UNKNOWN延迟终态、四种取消/预算边界及null负例实际执行，报告task12-unknown-green-r03。
- 新真实回环HTTPS/Bearer：前两批空、第三批严格v2身份/序号事件，3次poll且间隔至少90ms、6请求认证、AC及磁盘摘要一致；取消202仅为ACK，未确认执行域停止仍保留运行占用1。只证明真实TLS传输和宿主编排，不冒称部署Worker执行/回收。
- 剩余传输错误有限重试及真实Worker状态/游标按12保留，11具体预算和14/19真实域/平台前提仍未具备。仅追加work、差异检查，未全量/冻结/暂存/提交/push，unit均终态；TASK-12未DONE，原Goal保持active。

### TASK-12明确传输故障有界重试 [TASK-12-RETRY-R03]

- 原延迟终态回归增添timeout/connect参数：R01五项中新增两项均AC预期却SYSTEM_ERROR，退出1、零错误/skip。JudgeService仅捕获直接cause为HttpTimeoutException/ConnectException的轮询失败，沿原空批等待、取消和总任务deadline重试；503、TLS、JSON、null仍失败关闭，无新参数、接口或框架。
- R02编译成功，受影响编排49/49、45.188秒退出0、零失败/错误/skip；六项HTTP/TLS/JSON失败及未确认清理占用、八项取消/预算边界均实际执行，统计和首终态断言保留。报告task12-retry-red-r01/green-r02，复用原nobody/私网/只读源码及资源预算。
- 仅加强真实TLS两参数：首GET保持在途未返回，原2秒HTTP超时后另两GET仍收到合法v2事件并AC，磁盘摘要一致、三次轮询/六次Bearer、202不视为停止且占用保留1；私有两线程及请求均确认收尾。R03编译、真实2/2、14.354秒退出0、零失败/错误/skip，报告task12-retry-tls-r03；复用其余47项有效结果，不重跑全套。
- 只闭环明确超时/连接失败，不将其他传输类型、Worker状态/游标或真实执行域回收报为完成；11具体预算及14/19身份/平台/停止删除前提仍保留。仅追加work和差异检查，所有unit终态，无全量/冻结/暂存/提交/push，TASK-12未DONE、原Goal保持active。

### TASK-23运行态完整数组剥离 [TASK-23-INLINE-R02]

- ProgressPublisher仅增加三行RUNNING判定，统一剥离小任务及空inline results，保留原摘要/样本、首个非空推送、终态与1000条兼容阈值。新0/1/40/1000参数先因夹具样本类型编译失败，复用JudgeProgressEvent.from修正后R01a四项全红（4.443秒退出1）；未把编译失败计为产品红测。
- R02编译成功，发布器27、真实40例C++ HTTP的AC/WA两项及四transport HTTP/STOMP四项共33/33、50.150秒退出0、零失败/错误/skip，报告task23-inline-green-r02。磁盘/返回/通知无运行态完整数组、摘要不变、小终态仍保留原results，首RUNNING不抢越及完整终态一致均通过。
- 23非终态缓存容量/TTL、有限重试及出站积压预算仍未闭环，未DONE。原全量两项LinuxRuntimeJournal owner/group夹具在Files.setAttribute处EPERM，当前空capabilities不能构造异属文件；保留失败而非跳过/放宽校验，不扩大权限或重复全量。

### TASK-12完整HTTP响应截止 [TASK-12-HTTP-BODY-R02]

- 原真实TLS用例增添响应体阻塞：R01两项中body在100ms预算下约2.094秒后仍返回空批，1失败/0错误/skip、10.796秒退出1。RemoteWorkerRunner.send仅复用HttpClient的sendAsync及带剩余单调预算的get，覆盖响应头和完整body，超时/中断取消未完成future；保留原IO/TLS异常链、预先中断拒发及未知创建语义，无新限额/框架。
- R02编译成功，Worker契约42、真实TLS28、宿主编排49共119/119，约65秒退出0、零失败/错误/skip，报告task12-body-green-r02。XML确认poll/启动/取消/cleanup的八项headers/body参数实际执行；原100ms/400ms及1秒/650ms收尾断言保留，晚到POST ACK不生成句柄、不伪造未创建/停止，未确认清理仍保留占用与文件。
- 真实TLS传输不是部署Worker执行域回收证明；12状态/游标、11累计/字段/深度及HTTP字节预算、14/19生产身份/代际/停止删除授权与平台验收仍保留。只追加work和差异检查，全部本轮unit终态，未全量/冻结/暂存/提交/push，未改task/Goal/交接、未标DONE/complete，原Goal保持active。

### TASK-11 HTTP读取预算闭环 [TASK-11-HTTP-BUDGET-R03]

- RemoteWorkerRunner复用已批准256KiB/256条批次、128KiB单事件及原共享配置；响应订阅先检查累计字节再收集，超限立即取消，不等EOF。严格UTF8解码，有界数组逐项验JSON、原始UTF8字节及条数，保留重复字段/尾随拒绝、完整响应deadline和失败归属，不新增reader框架。
- 新真实TLS21项R01为12过/9失败、退出1，证明原超限/坏UTF8接受和未完成body不能及时拒绝。R02编译、编排49/Worker42/TLS49共140/140，约65秒退出0；追加配置/JSON断言后R03仅Worker53/53、9.052秒退出0，复用R02其余98项。标准XML确认限额−1/等于/+1、fixed/chunked、中文按字节、非法UTF8、未完成超限body、重复/尾随及完整截止实际执行，均零错误/skip；报告后缀task11-http-red-r01/green-r02/contract-r03，无固定测试数量门禁。
- 四文件差异检查通过，全部运行已终态。11累计磁盘量/字段单位/JSON深度及真实资源上界、12 Worker状态/游标、14/19实际执行域与平台验收仍未闭环；真实TLS不当部署Worker回收证明。仅追加work，未全量/冻结/归档/暂存/提交/push，未改task/Goal/交接或标DONE/complete，原Goal保持active。

### TASK-12 Worker轮询身份拒绝 [TASK-12-POLL-IDENTITY-R02]

- RemoteWorkerRunner.pollEvents在本地缓存/HTTP之前复用cancel的provider拒绝语义，仅新增三行：空句柄、空/缺失provider及Linux/Windows异属句柄直接IllegalArgumentException，不向Worker发送携带凭据的轮询，不触碰其他attempt缓存。未创建身份管理器或更改正常事件协议。
- 原真实HTTPS夹具新增五项R01全红，零错误/skip、Maven6.085秒退出1；修复后编译通过，Worker58/TLS49/编排49共156/156，约66秒退出0、零失败/错误/skip。标准XML核对五项拒绝、正常启动、四种失败attempt归属及原TLS预算/截止实际执行，user.name=nobody；报告task12-poll-identity-red-r01/green-r02，仍沿原离线私网、只读源码和资源隔离。
- Worker状态/不透明游标的真实阻断为当前仅有数组响应契约且仓库未提供Worker服务实现或state/nextCursor返回约定；不猜状态接口、不以序号推算游标、不据取消ACK声称回收。11剩余累计/字段/深度、14/19执行域与平台前提不变；所有本轮会话终态，未全量/冻结/暂存/提交/push，仅更新work，任务未DONE、Goal保持active。

### TASK-22真实编译错误与新任务恢复 [TASK-22-COMPILE-RECOVERY-R03]

- 复用原task22-start-retry-check.cjs增加compile场景，无产品源码改动、响应mock或新框架。真实C++ #error携带HTML/事件属性，COMPILATION_ERROR经HTTP/WS进入可见错误提示；正文与服务端逐字相等、无注入DOM或脚本执行、缓存整终态一致、未自动再创建。显式修正代码后新ID实际一例AC，旧错误隐藏、HTTP/缓存整终态一致，create/start各2次，WS峰值1/最终0。
- R01/R02均已通过真实错误转义，恢复步骤分别因未切回代码页、错误猜测tab角色而超时退出1，不记为产品红测；仅复用原已成功交互场景的button定位，未放宽断言。R03两项全PASS、pageErrors0、会话6659退出0；Chromium149.0.7827.55/Playwright1.61.1沙箱、UID65534及原只读源码/私网/资源预算保持，应用均SIGTERM143无强杀。R02 active属性与InvocationID90b92d761db04800bd476646e2c47525实读，不拿collect后的默认统计当产品资源上界。
- 证据为原target/task22-compile-recovery-r01/r02/r03.json及截图，已实看R01错误与R03成功；未重复归档、构建、全量或冻结。TASK-14出生身份已有R04绿测直接复用，两项异属UID/GID夹具仍缺权限而保留失败、不扩大能力；22整体前置/剩余矩阵及11/12/14/19既有阻断仍在，仅追加work，未更新task/Goal/交接、暂存/提交/push或标DONE/complete，原Goal保持active。

### TASK-13无输入EOF闭环 [TASK-13-STDIN-EOF-R03]

- DirectProcessRunner原无输入管道不关闭，等待EOF的正常程序误超时，真实C++生成器终态System Error。仅两行修复：在原等待try内关闭父侧stdin，close异常沿原有界清理传播；文件重定向不变，无输入配置、新线程或管理器。现有JVM夹具参数化无输入/空文件/中文内容，并新增真实随机端口HTTP→C++生成器/答案/候选→AC、1.in原始内容及精确持久终态验收。
- R01编译成功，新4项2过/2失败、20.558秒退出1，零错误/skip。修复后R02编译及73项为72过/1失败、24.112秒退出1；失败仅新增断言误用旧COMPLETED，实际HTTP/摘要/文件已AC。按既有枚举改为精确AC并增加finishedAt非空，未改产品终态或放宽业务断言；R03仅复验HTTP1/1、14.155秒退出0，复用执行器25及基线47项，全部有效用例零错误/skip。
- 标准XML后缀task13-stdin-eof-red-r01/green-r02/http-r03已核对关键方法实际执行和失败传播，包括原合计输出、IO故障、内存、执行及共享清理deadline；UID65534/离线私网/只读源码和原资源预算保持，所有会话终态。仅简记work，无全量/冻结/归档/暂存/提交/push或task/Goal/交接更新；13资源上界及14执行域/平台、11/12/19既有缺口仍保留，不据良性trusted-local验收标DONE或Goal complete。

### TASK-13已完成内存异常终态闭环 [TASK-13-MEMORY-TERMINAL-R02]

- DirectProcessRunner在终态分类前非阻塞读取原memoryFuture，将已完成的MemoryLimitExceededException同步到原AtomicReference；普通/OLE路径复用同次采样，原TLE优先序、共享清理deadline及接口不变。MemoryMonitorService先发布超限异常再停止进程，消除先停后报窗口；无新等待、管理器或生产参数。ProcessRunnerTest用真实JVM及延迟回调模型覆盖正常/非零退出，新RunningProgressHttpTest以真实Cookie/CSRF、C++触页128MiB、请求限额64MiB/3秒验证HTTP、结果、摘要及磁盘冷读终态MLE和finishedAt；memoryUsed严格大于64MiB，不拿采样模型冒充真实OOM或平台资源上界。
- 沿原WSL root离线test-compile→nobody离线surefire:test。R01新3项1过/2失败，15.493秒退出1、零错误/skip，确证误SUCCESS/RE；编译通过。最小修复后R02编译通过，ProcessRunner27+Baseline47+真实HTTP1共75/75，26.346秒退出0、零失败/错误/skip。标准XML后缀task13-memory-terminal-red-r01/green-r02确认两参数和真实HTTP实际执行、低权限身份及失败传播，日志target/task13-memory-terminal-red-r01.log/green-r02.log；差异空白检查通过。
- 所有会话终态，维持私网/只读源码、target可写、2GiB tmpfs、4GiB/swap0、2CPU、512pids及既定时限；systemd统计不充当产品资源上界。仅更新work，自审，未重复旧Probe/取消红测、全量/冻结/归档/暂存/提交/push或更新task/Goal/交接；13资源/drain、14/19执行域与平台及11/12/15/16/23既有缺口仍保留，未标DONE或Goal complete，原Goal active。

### TASK-16旧文本比较保留非空白字节 [TASK-16-TEXT-BOUNDARY-R02]

- 最新Python杀后wait/pump已有[TASK-14-CLEANUP-R02]的共享2s截止，复用而未重修。转向实际比较缺口：task_runner.py仅一行，将旧LEGACY_TEXT边界strip限制为获批六种ASCII空白，不再吞掉U+00A0或U+001C并误AC。现有Python套件复用7条共享JSON文本向量；Artifact夹具直接读取同一向量，以真实C++输出两种字节，经Python/v2验真/磁盘冷读确认WA、completed=1/wa=1/ac=0/systemError=0及原始.out/.ans内容。
- R01 Python一个参数化方法的7向量有2个subTest失败，0.004秒退出1；离线编译通过，真实Artifact两参数均误AC，3.937秒退出1、零错误/skip。最小修复后Python两套30/30、11.953秒，Artifact22/22、36.426秒，均退出0、零失败/错误/skip；绿测复用现成Java编译产物，未重建。日志target/task16-text-python-red-r01.log/green-r02.log，标准XML后缀task16-text-artifact-red-r01/green-r02确认两参数和相关SPJ/混合终态实际执行、失败传播及nobody身份。
- 仅闭合旧文本模式边界，不声称已支持DECIMAL_ABSOLUTE；能力拒绝断言保留，15/16有限小数/precision无损、解析上界和非法UTF8映射、Worker贯通及其他任务既有阻断仍在。所有会话终态，沿原私网/只读源码与资源预算；自审，仅简记work，无全量/冻结/归档/暂存/提交/push或task/Goal/交接更新，未标DONE/complete，原Goal保持active。

### TASK-13真实输出资源与下一用例 [TASK-13-OUTPUT-RESOURCES-R01]

- 最新TASK-12启动/清理总deadline及Python有界收尾已有有效切片，直接复用、不重修。仅在原Artifact夹具补Linux真实资源验收：C++首例无限双流输出，4096字节额度内停止并记OLE；同一Python监督器继续第二例AC，v2验真及磁盘冷读保持completed=2/OLE=1/AC=1/systemError=0和首个OLE终态。核对全部用例数据产物共4104字节（额度+8），无首例答案、第二例输出/答案均为4换行。
- R01离线test-compile成功（13.456秒）；仅新增关键用例1/1，5.740秒退出0、零失败/错误/skip，原22项未受影响结果复用。真实监督器PID200408的Linux VmHWM为19177472字节，OLE之后内核峰值读数仍19177472；小于64MiB实验断言，有真实洪泛后观测，不使用systemd单元峰值替代。标准XML后缀task13-output-resources-r01确认关键方法、nobody及原始观测，日志target/task13-output-resources-r01.log。
- 仅测试与work变化，无产品源码/生产配置变更；64MiB为隔离夹具的资源断言，不是生产监督器SLA。不把单进程/输出产物观测扩称整域内存、任意文件写入配额、部署Worker或Windows验收；14/25/27相关域/平台资源和11/12/15/16/23前提仍保留。会话终态，沿原私网/只读源码、2GiB tmpfs、4GiB/swap0/2CPU/512pids/既定时限，自审；仅更新work，未全量/冻结/归档/暂存/提交/push或task/Goal/交接更新，不标DONE/complete，原Goal active。

### TASK-03原生转发启动校验闭环 [TASK-03-NATIVE-SOURCE-R03]

- 锁定Boot3.5.3的实际字节码及真实启动红测证实：即使forward-headers-strategy=none，显式Tomcat remote-ip-header/protocol-header仍会激活原生地址改写。ClientAddressResolver仅复用Boot Binder及ServerProperties.Tomcat.Remoteip，在现有构造校验中拒绝两类头；原手工构造接口、可信CIDR/额度和认证参数不变。现有套件参数化覆盖两类头的连字符/驼峰绑定及实际Servlet启动前拒绝，无新框架。
- R01编译成功，新6项全失败、零错误/skip，11.471秒退出1；修复后R02编译成功，实际ClientAddressResolver45、SessionCredentialResolver14、TrustedProxyHttp17共76/76，19.405秒退出0。R02另两个选择器误写不存在类名、未执行，不能算入通过数；R03仅以现成编译产物补跑正确AuthenticationBounds13及SessionCredentialHttp18，共31/31、13.554秒退出0，未重跑上述76项。两阶段107项均零失败/错误/skip，标准XML后缀task03-native-source-red-r01/green-r02/green-r03已核对实际方法、nobody身份及失败传播；日志target/task03-native-source-{red-r01,green-r02,green-r03}.log。真实HTTP/WS及两参数Servlet启动与MockMvc集成区分记录，不互相冒充。
- 保持既有私网/只读源码、target可写、空capabilities、2GiB tmpfs、4GiB/swap0/2CPU/512pids及有界收尾，R03会话正常终态。仅简记work，不因文档重建/测试，不重复全量/冻结/归档；原全量失败和平台/执行域及其他所属任务阻断仍保留，无暂存/提交/push或task/Goal/交接更新，TASK-03未DONE、原Goal active。认证及代理初值沿用户授权的原实现，最终提醒按实际共享出口与代理拓扑调整，不猜生产CIDR或扩大权限。

### TASK-15显式null精度与参数错误闭环 [TASK-15-PRECISION-NULL-R02]

- 从未闭环卡及最新源码定位到不依赖数字预算的接收缺口：primitive double将precision:null默认为0，错误数组类型被通用异常映射为500。JudgeRequest仅增加JsonSetter(nulls=FAIL)，保留原double接口和缺字段默认0；GlobalExceptionHandler仅补HttpMessageNotReadableException入口，复用现有HTTP_ERROR/400响应及固定提示，不回显解析栈或请求内容，不改比较算法、生产参数或批准语义。
- 现有RunningProgressHttpTest增加缺字段/显式0/null/数组四参数真实Cookie+CSRF请求：拒绝400且无judgeId、任务集合不变；两种合法0均CREATED、未启动，策略及metadata磁盘值为0，最后经真实取消入口收尾。R01编译成功，4项2过/2失败，实际null误200/数组误500，12.844秒退出1、零错误/skip。最小修复后R02编译成功（23.858秒），ControllerPolicy5+RunningHttp11+TaskPolicy9共25/25，47.550秒退出0、零失败/错误/skip，包含原真实C++/统计/取消正例；拒绝断言加强为既有错误模型、固定详情及不泄漏源码/解析异常名，未放宽业务断言。
- 标准XML后缀task15-precision-null-red-r01/green-r02已确认四参数实际执行、nobody及失败传播，日志target/task15-precision-null-{red-r01,green-r02}.log；沿原私网、只读源码/仅target可写、2GiB tmpfs、4GiB/swap0/2CPU/512pids和有界收尾，会话均终态。仅追加work，不因文档重测/构建，不全量/冻结/归档/暂存/提交/push。15/16有限小数及precision无损、token/指数具体预算和非法UTF8比较错误映射仍待闭环，不据此交付完整任务；TASK-15未DONE、原Goal active。

### TASK-19真实RUNNING崩溃与拒绝不安全重启 [TASK-19-RUNNING-RESTART-R01]

- 仅在原task22-start-retry-check.cjs补restart-running场景，复用现成账户登录、真实HTTP/CSRF、固定C++及产品重启入口；无产品源码或配置变更、不新建框架。真实一例进入RUNNING、startedAt非空且持久句柄为null；只对脚本自建产品PID203874注入SIGKILL。候选PID204077、Linux startTicks22261348、UID/GID65534及相同测试cgroup在崩溃前后/新产品拒绝启动后保持，是真实活执行而非改写metadata模拟。
- 新产品同私有storage启动确实退出1、具体原因Execution stop is unconfirmed，未发布HTTP就绪；metadata/events字节不变、final不存在、未伪造STALE/终态或容量释放。JS语法检查通过，隔离unit/session42530单项1/1 PASS、pageErrors0，16.150秒退出0；Chromium149.0.7827.55/Playwright1.61.1/Node22.23.1沿原缓存复用，无下载或产品重建。证据target/task19-running-restart-r01.json/log，SIGKILL为明确注入故障而非常规收尾失败。
- 最后仅释放夹具自己的gate并确认候选退出；随后unit已collect、inactive/dead、MainPID0，PID204077实际查询无进程。报告明确productReclaimedExecution=false，不将夹具或外层cgroup收尾当作产品回收成功。保持nobody、私网/只读源码、2GiB tmpfs及4GiB/swap0/2CPU/512pids/原时限，不停止共享服务。此负例不替代五活跃状态收敛、UNKNOWN对账、占用重建或生产身份/代际/停止删除授权；仅简记work，无全量/冻结/归档/暂存/提交/push或task/Goal/交接更新，19未DONE、原Goal active。

### TASK-15非法精度标量与兼容接收闭环 [TASK-15-PRECISION-SCALAR-R02]

- 沿原真实HTTP参数化夹具补空串、纯空白、文本null和页面数字/字符串1e-9。R01编译成功，九参6过/3失败、14.431秒退出1，三类非法字符串均误200并创建任务，零错误/skip。DTO仅在现有precision入口增加严格字符串接收，保留primitive double getter/setter及缺字段0；复用现有400固定错误模型，不新增反序列化器/框架、数字预算或生产参数。合法数字/原数字字符串输入与JSON数字序列化保留，当前double计算不宣称无损小数实现。
- R02编译成功（22.568秒），仅运行真实HTTP九参、ControllerPolicy5和TaskPolicy9共23/23、16.896秒退出0、零失败/错误/skip。五类无效输入400、无judgeId且任务集合不变；缺字段/0及数字/字符串1e-9的策略与metadata磁盘值一致、未启动，最后走实际取消入口。原策略用例增加JSON字段仍为数字的断言并保留全部精度/快照断言，未放宽业务规则；其他旧C++/Probe/取消验收及全量未重跑。
- 标准XML后缀task15-precision-scalar-red-r01/green-r02已确认九参数及序列化断言实际执行、nobody和失败传播，日志target/task15-precision-scalar-{red-r01,green-r02}.log。维持原离线/私网/只读源码、target可写、2GiB tmpfs、4GiB/swap0/2CPU/512pids及有界收尾，会话均终态。仅简记work，不因文档重测/冻结/归档/暂存/提交/push；15/16无损小数、数字token/precision字节及指数上限和非法UTF8比较错误映射仍须具体批准，未标DONE、原Goal active。

### TASK-27真实异属取消标记负例 [TASK-27-JOURNAL-IDENTITY-R04]

- 仅适配LinuxRuntimeJournalTest原uid/gid参数：可选judge.test.foreign-identity-directory接收真实异属0600、单链接、空文件，原子移动保留inode/owner，每次只让一个身份维度不匹配；原取消/读取拒绝、lease失效及runtime-state字节不变断言保留。现成daemon/nogroup与nobody/daemon只在私有markers、私网/空能力集/64MiB/30秒单元生成文件，未CHOWN、创建用户或修改产品权限。
- R01的65535被拒、R02的65536无账户导致217/USER，均是夹具失败。R03编译成功15.615秒；原套件31过/2跨挂载rename错误、零失败/skip、3.136秒退出1。仅去掉私有目录内重复子挂载，复用产物R04为33/33、2.986秒退出0、零失败/错误/skip；实际异属UID为1/65534、异属GID为65534/1，writer为65534/65534，inode不变。标准XML后缀task27-journal-identity-r04确认八参及nobody，日志target/task27-journal-identity-{compile-r03,r03,r04}.log和task27-journal-foreign-actors-r01～03.log保留失败历史；沿原源码只读/target及私有夹具可写、4GiB/swap0/2CPU/512pids/600秒预算，单元均终态。
- 仅更新work，无全量/冻结/归档/暂存/提交/push；不将此绿色替代旧全量或标TASK-27 DONE。15/16数字解析具体预算及错误映射、11事件预算、6空闲撤销/连接容量、18扫描频率及生产执行域身份/停止删除授权仍阻断相应切片，原Goal active。

### 原Goal具体参数等待 [GOAL-PARAMETERS-WAIT-R01]

- 上轮33项真实文件安全验证是有效推进；本轮自动接续不是对4096字节/指数1000/System Error提议的人工批准，不擅自启用无损任意精度运算或放宽错误映射。核对TASK-22既有CSRF报告发现CREATED刷新、原ID显式重试、真实AC及正常收尾已通过，直接复用，不再补造同类浏览器验收。
- 当前仍缺15/16数字预算及错误映射、11事件预算、6空闲撤销/连接容量、18扫描频率，以及12外部Worker契约和14/19/25/27生产执行域身份/代际/停止删除授权。无可继续的独立必要代码切片或活验证会话；本轮无源码修复/新测试，不算功能进展，只记录真实阻断。未重测、冻结、提交/push或更新task/Goal/交接，原Goal active，未标DONE/complete。
- 同一审批/环境阻断连续三个Goal轮次未解除，最近两阶段没有功能进展；已核对当前精度入口仍为double、暂无独立可执行切片，按阻断审计将原Goal设为blocked（工具已返回），不是complete或重建Goal。具体批准/环境到位后恢复原范围，保留33项绿色及其他有效证据；本轮不再重测或扩建工具。

### TASK-15/16有界无损十进制比较 [TASK-15-16-EXACT-DECIMAL-R01]

- 用户明确批准每token及precision最多4096 UTF-8字节、显式指数绝对值最多1000（含边界），运行比较非法UTF-8或超界为System Error，非法API precision创建前400拒绝；原Goal已恢复active。沿现有DTO/策略/普通比较和Runner接入保存precision原文，Java BigDecimal及Python整数缩放精确绝对误差，页面不再parseFloat；整数精确比较、文本/LEGACY_TEXT及SPJ原契约保留。51条共享向量覆盖有限大数、小数及预算边界，未扩框架或生产权限。
- 离线编译成功。Java五套R01为132过/4失败（136项、零错误/skip）；四个新增测试误用内部枚举拼写，修正公开System Error并加强持久枚举与计数断言后R02四项全过；其余132项直接复用。另受影响Manifest36/36、Python四类28/28全过（零失败/错误/skip）；标准XML后缀task15-exact-r01/r02、task15-manifest-r03及target/task16-exact-r01.log。Python派发mock不作为真实provider证据。
- 真实Chromium/Cookie/CSRF→HTTP→磁盘策略→C++→页面终态：precision原文1e-1000未损失，R01大数差WA通过，第二例定位文字猜错导致整体失败；最小修定位后仅补R02恰等边界AC通过、pageErrors0，两张截图已实看，不称R01全绿。证据target/task15-exact-browser-r01/r02.json及对应日志/截图，应用SIGTERM143正常收尾；nobody与原私网/只读源码及资源预算不变。本轮补丁diff --check通过，未重复全量/冻结/归档/暂存/提交/push，只记work，15/16未DONE；真实Runner/host贯通及其他独立参数/环境阻断仍须继续。

### TASK-16共享向量真实Artifact贯通 [TASK-16-DECIMAL-REAL-R03]

- 仅复用现有Artifact夹具按共享precision分8批执行51向量，真Python/C++→v2事件验真→宿主final→新FileTaskStore冷读；逐例状态、原产物契约、全计数、首失败、attempt/revision/时间戳及冷读相等均断言。另双侧非法UTF-8真文件/事件/入库均为System Error，不误AC/WA；不新增调度/管理器，不复制向量。8192字节为夹具输出预算以区分4096-token拒绝与OLE，不是生产参数。
- R01编译20.054秒成功，10项2过/8失败、16.337秒退出1；新断言误假定产物不归一CRLF和摘要含全results。保留逐例业务断言并按原产物/摘要契约修正、加首失败与冷读断言；R02编译20.689秒成功，16项8过/8失败、18.526秒退出1，Worker精度原文发送及7类能力不足不发源码8项全绿。余失败为夹具误猜聚合System Error优先及未持久绑定handle；沿原首失败规则补真实存储绑定，不改产品语义。R03编译15.462秒成功，仅受影响Artifact10/10、13.041秒退出0，零失败/错误/skip；标准XML确认8批51条观测逐ID/precision/结果一致、双侧坏编码及nobody。R02 Worker8项直接复用，不称R01/R02整体绿，原Java/Python和浏览器绿色不重跑。
- 证据target/task16-decimal-{compile,real}-r01～03.log及标准XML后缀task16-decimal-real-r02/r03。继续请求原私网/只读源码、target可写、2GiB私有tmp、4GiB/swap0/2CPU/512pids/600秒及540秒内timeout/15秒control-group收尾；属性读取时单元已collect，不将其默认值或收尾Memory peak作为实际资源证据，不为补观测重跑。此为trusted-local Artifact与替身HTTP Worker契约，不冒称实际Linux容器/外部Worker/生产整域回收验收。15/16整体仍待12/14及平台验收，11/6/18/23独立预算和生产身份/代际/停止删除权限仍未获具体参数；未DONE/提交/push/complete，仅更新work，不冻结/归档或改task/Goal/交接。

### TASK-15/16一次必要交付回归 [TASK-15-16-DELIVERY-REGRESSION-R01]

- 上轮为真实51向量/双侧坏编码功能进展，本轮沿原Goal复用其浏览器及Artifact证据，仅对当前已编译源码执行一次必要全量，不重建/冻结。Java离线surefire:test标准XML后缀task15-delivery-java-r01：66套3303项，2846过/0失败/2错误/455跳过，05:58退出1；数值/API/模板/Manifest/Artifact/Worker八套270/270、零失败错误skip，但整体明确未绿。两错误是LinuxRuntimeJournalTest uid/gid夹具未设置既有foreign-identity-directory，在nobody下CHOWN被拒；属于TASK-27验证环境，不改身份规则、放宽断言或当作产品绿色，既有TASK-27-JOURNAL-IDENTITY-R04真实异属证据仍保留。
- 455跳过已逐套发现：Discovery67、RecoveryPreparation23、Launcher361及Guard/Update各1均要求实际root writer，当前nobody不足，共453；余2为Linux容器能力与非Windows平台条件。已有root Launcher8项报告不能泛化为451项完整执行，未把跳过计通过、补造root身份或重跑旧Probe/取消红测。Surefire另出现fork退出30秒后自终止提示，保留原日志，不忽略或据此宣称产品回收；测试命令与控制组已终态，未停止共享服务。
- Python一次现有unittest discover为158/158、16.570秒退出0、零失败/错误/skip，日志target/task16-delivery-python-r01.log；Java日志target/task15-delivery-java-r01.log及unit.log。Java运行中实际确认InvocationID8a714d5bfe574601b82628f33e9be7ee、nobody、私网/私有tmp、源码只读仅target可写、2GiB tmpfs、4GiB/swap0/2CPU/512pids/600秒及540秒内timeout/15秒control-group收尾；整单元峰值不作为产品资源上界。只简记work，不重复全量/归档或更新task/Goal/交接，未暂存/提交/push、DONE/complete；root契约/异属夹具的当前全量环境、11/12/6/18/23具体预算及生产执行域/Worker授权仍是后续闭环边界。

### TASK-12/14收尾契约及root专属分项补验 [TASK-12-14-ROOT-CONTRACT-R02]

- 上轮必要全量为实际验证进展，本轮只补其未执行分项，不重复全量或旧真实取消实验。实际root writer、空能力集、私网/私有设备及原只读源码/4GiB/swap0/2CPU/512pids/600秒预算下，Launcher361及RecoveryPreparation23共384项：383过/1失败、零错误/skip、01:15退出1。运行中实际确认InvocationIDc0dd3780fc694cdc8d131b6513de92b6与全部限制；运行时为替身，Python/文件合同真实，不冒称生产运行域或容器验收。
- 失败是原noEffect取消测试仍期待总deadline耗尽后强制stop一次，与已验证TASK-12总预算契约冲突；生产实现不改、超时不加长、断言不放宽。仅将停止无效覆盖转到强制stop入口，前置意图通过真实LinuxRuntimeJournal发布，仍须IOException、stop=1/rm=0、意图及CLEANUP_PENDING保留、supervisor/holder仍运行；合作退出后的两种不可信应答增加ExitCode20/观察已发生/holder保留断言。R02编译13.762秒成功，仅上述三例及原200ms耗尽不发stop、共享递减预算共5/5，4.007秒退出0、零失败错误skip；其余未受影响绿色复用，未称R01整体绿。
- Discovery以原方法选择器补不含CHOWN的61/61，6.914秒退出0、零失败错误skip；原六参数组仍完整保留在源码、未执行且不计通过。root专属分项按范围分轮已有445项有效通过，尚余该六参数组及Guard/Update实际owner变更各1，共8项待明确CAP_CHOWN与读取0600异属文件所需CAP_DAC_READ_SEARCH授权；未自行授予能力、改变业务身份或伪造标记/停止/删除授权，Journal异属R04有效结果继续保留。标准XML后缀delivery-root-contract-r01/deadline-contract-r02/delivery-discovery-r01及同名target日志；仅测试最小修正和work，无task/Goal/交接更新、冻结/归档/暂存/提交/push、DONE/complete，原Goal active。

### TASK-23已持久快照revision与页面旧版本闭环 [TASK-23-PERSISTED-REVISION-R04]

- 未收到新增能力确认，本轮避开8项异属验证，直接补现有23缺口。FileTaskStore复用任务锁为落盘非终态及首个final分配前版+1，深复制、绑定实际attempt、清除非终态finalizedAt，不信任调用方版本；提交前失败不消耗版本，Long耗尽拒写，旧有效终态保持。index只保留当前任务一份版本状态，拒绝旧/晚到无版本及终态后覆盖，新任务复位；未加管理器、接口或生产预算。
- R01因nobody无法处理既有编译产物权限退出1、无测试；沿原root空能力编译/nobody空能力执行分离，R02五项5/5红、4.674秒退出1。最小修复后R03编译25.692秒成功，10套367项348过/19失败、零错误skip、01:49退出1；失败为9项旧版本预期、10项把未落盘内部候选误当已提交快照。仅修夹具并加强精确版本/统计/实际落盘断言，R04相关参数组20/20、14.184秒退出0，包含原19失败及1已绿同组；标准XML逐用例确认19全部补齐，其余348复用，原367项按范围分轮有效通过，不称R03整体绿。
- 实际文件冷读、提交故障、重复/冲突终态、晚到RUNNING及版本上界均通过；R03真实40例HTTP两项、四transport STOMP四项保持计数单调、同版整快照与final一致，Artifact受影响10项R04通过。复用原浏览器夹具，r01终态/新任务1/1、10.056秒，active-r02运行态1/1、10.087秒，均退出0、pageErrors0；真C++/HTTP/WS观察revision3→4→6、completed0→1→2，重放实际旧RUNNING/CREATED不回退，非伪造终态；两张截图已实看。Chromium149.0.7827.55/Playwright1.61.1缓存复用，nobody空能力/私网/只读源码、2GiB临时盘、4GiB/swap0/2CPU/512pids/600秒与control-group收尾在浏览器运行中实际确认；Java单元已collect后的默认属性不作为实证，整单元峰值不冒称产品资源上界。
- 证据复用target/task23-revision-{red-r01,red-r02,green-r03,green-r04}.log、标准XML同名后缀及task23-revision-browser-{r01,active-r02}.json/log；不新建索引或归档。仅更新work，不重跑全量/旧Probe/旧取消实验、不冻结或改task/Goal/交接，无暂存/提交/push。23的TTL/容量/出站预算、其他既有参数及生产执行域验收仍阻断，8项CAP_CHOWN/CAP_DAC_READ_SEARCH仍待明确确认；TASK-23未DONE，原Goal active。

### TASK-23迟到运行聚合防回退 [TASK-23-RUNNING-MONOTONIC-R02]

- 上轮版本及页面闭环有效。本轮确认存储仍会把空/较旧聚合写成更高版本，丢弃已有统计；仅在FileTaskStore既有锁内、版本分配前拒绝这两类覆盖，不改接口、调度或生产参数。复用发布器并发设施和真实文件冷读，三参数保留完整message/progress/revision、AC/WA/首错/样本、磁盘字节及发送内容断言，不伪造终态。
- R01新增三参数3/3红、零错误skip、退出1；最小修复后R02存储90、发布器30、事件79及真实HTTP2，共201/201、零失败错误skip、47.784秒退出0，标准XML确认全部实际执行且user=nobody。真40例C++的AC/WA均在运行中重放真实HTTP旧快照：old=1、retained=2、revision=5，继续正常完成40例，分类和、样本、final/冷读与原版本断言保留；不是天然并发发生率或容器/Worker验收。
- target/task23-counts-{red-r01,green-r02}.log、同名标准XML与green-r02-unit.log为证据；运行中实际确认InvocationID da4e0ac9f38d49a0a98e319ecce22564、nobody空能力、私网/私有设备/只读源码、2GiB临时盘、4GiB/swap0/2CPU/512pids/600秒及15秒control-group收尾。未受影响的WS/浏览器证据复用，不重跑全量或旧实验，不扩工具/索引、冻结或归档。
- 仅追加work，保留用户/IDE及其余WIP，无task/Goal/交接更新或暂存/提交/push；23的缓存/出站预算及其他既有环境/决策阻断仍在，8项异属验证权限尚未明确确认。未DONE，原Goal active。

### TASK-18独立TTL扫描参数阻断核对 [TASK-18-SCAN-DECISION-WAIT-R01]

- 本轮转到18检查最新源码，无代码或测试新进展：JudgeScheduler仅在请求入口执行已批准15分钟CREATED过期；TaskCleanupService现有30分钟周期只处理终态保留清理，且findAll无单轮扫描上限，不能直接充当已批准的独立TTL扫描。扫描间隔、单轮任务量/时长预算仍未具体确认，未借用其他预算、猜值或新建扫描框架；8项异属验证的两能力授权也未收到。不重跑已有绿测、不改task/Goal/交接、不提交或push；原Goal active，等待明确参数后补当前缺失代码。


### TASK-18有界独立CREATED扫描闭环 [TASK-18-CREATED-SCAN-R02]

- 按本轮“设置合理值并最后提示”授权采用扫描1分钟、每轮最多128项、1秒协作预算，保留已批准15分钟TTL。ExecutionProperties/YAML补三项配置及非法值启动拒绝；JudgeService沿现有pending弱一致迭代器分轮推进，JudgeScheduler复用原monitor和权威final提交，确认终态才清注册/节流缓存。未改30分钟终态保留清理、不findAll全盘扫描、不新增管理器；1秒只限制开始下一项，不强行中断在途锁/原子提交，不宣称硬时长上界或无限积压及时清完。
- 编译R01因新测试通配泛型失败退出1、无测试，仅修断言后R02编译17.021秒成功。七个受影响套件R01共154项151过/3失败、零错误skip、01:40退出1；三失败为JSON IntNode/LongNode表示差异，改为HTTP完整DTO与真实冷读逐字段比较，不放宽业务断言。R03仅测试编译13.414秒成功；R02只补原三参数3/3、零失败错误skip、16.444秒退出0，其余151项复用，当前154项分轮有效通过，不称R01整体绿。
- 标准报告确认预算/续扫、真实原子move失败保留pending且其他任务继续、恢复后首个final revision1及冷读一致、14/16分钟三方屏障竞争均实际执行；本轮实测CANCELLED/STALE、completed0/Runner启动0、重试字节稳定，不冒称穷尽全部调度顺序或整OS域验收。真实Cookie/CSRF/HTTP的start/cancel/scan均STALE、无启动句柄、pending释放且同owner配额可复用；scan在老化metadata后不再发请求，由实际Spring后台扫描终结，250ms仅为测试加速配置，生产仍1分钟。
- 证据为target/task18-scan-compile-r01/r02/r03.log、task18-scan-green-r01/r02.log及同后缀标准XML；R01运行中InvocationID75f9c40978b14f08b26a37cc61947edf确认nobody空能力、私网/只读源码、2GiB临时盘、4GiB/swap0/2CPU/512pids/600秒和control-group收尾，见green-r01-unit.log。参数阻断已解除，完整18及原Goal仍未DONE/complete；原执行域/适用平台与最终交付阻断、8项异属验证两能力确认仍保留。本轮仅追加work，未改task/Goal/交接、全量重跑、冻结、归档或暂存/提交/push，保留既有WIP。


### TASK-22持续启动故障与原ID恢复实测 [TASK-22-PERSISTENT-START-R01]

- 上轮18为实际修复/验证进展。本轮复用19已完成的final投影及真实重启证据，其余UNKNOWN/整执行域恢复仍依赖原身份、代际和终结授权；不重复旧验收。转到22，只在既有task22-start-retry-check.cjs增加start-persistent参数，覆盖连续503和启动请求断网各三次显式失败，不建新框架、不改产品源码。
- 语法检查通过；Chromium149.0.7827.55/Playwright1.61.1沙箱真实运行6项PASS、pageErrors0、退出0。每场景仅create1次、同ID失败3次后显式第4次启动成功，失败时真实metadata仍CREATED且startedAt/句柄为空，输入/ID保留、错误可见、无自动重发/残留轮询与连接；恢复后良性C++真实1例AC、HTTP与页面整快照相同、查询/连接峰值1、终态停止且旧错误隐藏。两张断网拒绝/恢复图已实看；503与断网为受控网络故障注入，不冒称服务原生503或真实Worker验收。
- 证据target/task22-persistent-start-r01.json/log及原截图；复用上一轮编译产物及未失效单测，不全量/重构建/冻结/重复归档。nobody(uid65534)与应用SIGTERM143已实际记录；请求沿用空能力、私网、只读缓存/源码、2GiB临时盘、4GiB/swap0/2CPU/512pids/600秒及control-group收尾，属性查询时unit已collect，其默认值不作实际限制证据，不为补观测重跑。仅追加work，无task/Goal/交接更新、暂存/提交/push；完整22/19及原Goal仍未DONE/complete，原参数、执行域与8项能力授权阻断保留。


### TASK-22/07跨会话缓存授权前泄漏修复 [TASK-22-CACHE-AUTH-R03]

- 上轮持续启动验收有效。本轮发现结果缓存先于授权查询渲染，归22恢复/07授权边界；复用原浏览器同一profile，真实C++先产生ownerA的WA，再清Cookie重新登录另一访问码会话，实际跨owner404响应受控暂缓。R01确实先显示旧WA/计数/用例且acceptedSnapshot=true，退出1、pageErrors0，红图已实看，非伪造终态或404。仅移除index.restoreLatestJudgeResult的缓存抢先渲染，改由已有HTTP成功及当前ID校验后显示真实快照；保留输入、缓存记录和授权owner正常刷新，不新增接口、身份缓存或管理器。
- 编译15.509秒成功；R02模板33/33、零失败错误skip、4.399秒退出0，标准XML确认两项刷新契约实际执行且user=nobody。R02浏览器因把可见空占位误当泄漏退出1：idle/acceptedSnapshot=false且实图仅“等待运行/暂无测试点结果”；只把夹具改为必须无私有摘要/用例、idle且未接受快照的业务断言，不改产品或放宽授权。R03仅复验该真实场景，4项PASS/pageErrors0/退出0；实际404清旧缓存和跟踪，新owner真C++ AC，HTTP/页面整快照一致，授权刷新恢复且两任务总create/start均2，无重复执行。红测、空占位及新owner完成图已实看，33项结果复用、不全量重跑。
- 证据target/task22-cached-owner-{r01,r02,r03}.json、相关原log/图及标准XML task22-cached-owner-green-r02。复用Chromium149/Playwright1.61.1与原nobody隔离参数，实际uid65534、应用SIGTERM143；属性采集时unit已collect，其默认值不作限制实证，不为补观测再跑。仅必要源码/测试和work，无task/Goal/交接、冻结、重复归档或暂存/提交/push；完整07/22、原执行域/预算/8项能力确认和最终交付阻断仍保留，原Goal active，不DONE/complete。


### 原Goal真实所有权负例授权等待 [GOAL-OWNER-CAP-REQUEST-R01]

- 上轮缓存授权前泄漏源码修复、33项定向及4项真实浏览器为实际进展。本轮仅核对现有8项待验仍要求真实UID/GID变更与0600异属内容不变，未改断言或替换inode；能力确认仍未到位，需私有临时测试单元CAP_CHOWN、CAP_DAC_READ_SEARCH，保留原只读源码/缓存、私网与资源预算，不涉及生产或共享daemon。优先提出这一精确人工确认；其他事件/通道/缓存预算及真实执行域前置仍按原边界，不借18授权猜值。
- 本轮无源码修复/新测试，不算功能进展；不重跑445项、旧实验或全量，仅简记work，不改task/Goal/交接、不冻结/提交/push。原Goal active、未complete；当前缺权限不能靠扩建工具或旁支文档解除。


### 原Goal连续三轮阻断审计 [GOAL-BLOCKED-AUDIT-R01]

- 连续三轮均只有自动Goal接续，没有对8项CAP_CHOWN/CAP_DAC_READ_SEARCH的人工确认，也未提供剩余事件/通道/缓存预算或真实运行域身份、代际、终结/删除授权及外部Worker前置。前两轮无功能进展，本轮复核同一前置仍缺；当前源码无进度缓存TTL/容量配置，18已批参数不授权其他预算。既有测试会话均已终态，无活作业可等待，不重跑验证、扩工具或用旁支文档规避阻断。
- 按连续三轮门槛将申请update_goal(blocked)，状态以工具实际返回为准；原TASK-00～29、31缺陷和交付标准不变，不complete或新建Goal。只追加这条关键等待记录，不改task/Goal/交接、源码、断言或权限，无新测试/冻结/暂存/提交/push。取得对应批准/真实环境后恢复原Goal，复用有效结果、只补相关缺口；当前不宣称DONE或可发布。

### 原Goal真实所有权负例补验（2026-10-09） [GOAL-OWNER-CAP-GREEN-R01]

- 用户明确“确认”后，仅私有临时测试单元使用CAP_CHOWN、CAP_DAC_READ_SEARCH；复用最新编译产物，未修改源码或断言。Maven离线surefire:test，选择LinuxAttemptDiscoveryTest#cancellationDiscoveryRejectsUnsafeMarkerAndPreservesEvidence六参数、LinuxStageJournalGuardTest#actualOwnerMutationPoisonsLeaseAndCloseReleasesOriginalDescriptor及LinuxStageJournalUpdateTest#wrongOwnerRecordIsRejectedWhenNativeFixtureCanChangeOwnership，实际8/8通过、零失败/错误/skip，3.820秒、退出0；标准XML确认root身份及三个方法实际执行。真实UID/GID变更、0600异属内容不变、拒绝不可信取消标记及锁释放断言均保留；此前445项有效结果不重跑，本次解除这8项能力授权/实测缺口，不代表全量或整Goal通过。
- 证据target/owner-cap-r01.log及三个标准XML后缀owner-cap-r01。运行单元wjc-owner-cap-r01-799fc1cb已成功终态并collect；命令限制能力仅上述两项、NoNewPrivileges、私网/私有设备、源码及Maven缓存只读、仅target可写、临时盘2GiB、4GiB/swap0/2CPU/512pids/600秒、内层timeout540秒/kill-after15秒及control-group收尾，禁止访问共享运行时socket。未采集运行中属性，不把collect后的默认属性当作实际限制证据、不为补观测重跑。未接触生产或停止共享daemon。
- 仅追加work，不改task/Goal/交接、不重建/全量重跑/冻结/重复归档/暂存/提交/push。原Goal仍active；事件/连接/缓存预算、真实执行域身份/代际/终结删除授权、外部Worker及最终适用平台验收仍按原任务边界待补，不DONE/complete。TASK-18已采用的扫描配置仍为1分钟、每轮128项、1秒协作预算（不硬中断在途提交），本轮未改变其他业务阈值。

### 原Goal预算与执行域阻断（2026-10-09） [GOAL-BUDGET-BLOCKED-R02]

- 8项所有权真实补验通过后，连续三轮自动接续均无新的业务预算批准或执行域/Worker前置。TASK-23建议容量1024项、空闲TTL30分钟仍待人工确认，自动接续不视为授权；本轮只读核对源码仍无相应配置，上一测试单元LoadState=not-found、inactive/dead，无活作业可等待。其他事件/连接/发送预算及真实域身份、代际、终结/删除授权仍按原任务边界缺失；不以自行猜值或扩建测试/文档代替修复。
- 按连续三轮真实阻断门槛，update_goal已返回blocked；原TASK-00～29、31缺陷及交付标准不变，未complete或新建Goal。仅追加work并检查文档，未修改源码/断言、重跑测试/构建、冻结、暂存/提交/push；8项补验和其余未失效证据保留，明确确认对应预算或提供真实执行域前置后继续原Goal。

### TASK-23非终态缓存容量/TTL闭环（2026-10-09） [TASK-23-CACHE-LIMITS-R02]

- 用户批准1024项、空闲TTL30分钟并恢复原Goal active。ExecutionProperties/application.yml接入配置与非法/显式空值启动拒绝；ProgressPublisher复用原同judge compute串行点，以非阻塞Semaphore限制常驻条目，容量满仍落盘和发送、不新增无界旁路缓存；终态、清理、TTL均在映射移除后释放额度，节流中的有效发布也刷新空闲时间。TaskCleanupService在原维护入口先清过期缓存，再走原任务删除授权；不改变任务文件、首个终态或revision。TTL到期失效，物理清理复用既有30分钟维护周期，可能滞后一轮并等待在途操作，不承诺硬时长上界。
- 离线test-compile R01因新测试泛型断言退出1，保留精确集合断言修正后R02成功；R03纳入配置修复成功。定向R01共109项，108过/1失败、零错误skip，唯一失败是绑定器忽略显式空TTL；实际补启动拒绝并加强空容量参数，未放宽断言。发布器41、清理18、生产清理31共90项有效结果复用；R02配置20、真实HTTP5、四入口STOMP4共29/29、零失败错误skip、54.525秒退出0。本切片119项按范围有效通过，非全量绿：真实C++同时2个RUNNING但缓存仅1项，4任务AC、HTTP整DTO与冷读一致且缓存回落；原40例AC/WA、取消/预算统计及四transport持有首帧至真实终态后的保序断言均保持。单位级实际文件字节不变、TTL临界/活动续期、容量饱和、锁竞争与额度复用均已执行。
- 证据target/task23-cache-bounds-{compile-r01,compile-r02,compile-r03,r01,r02}.log和标准XML后缀task23-cache-bounds-r01/r02；R02运行中InvocationID862a8caf251c4f76a59d5fad0f17071c与nobody/空能力、私网、只读源码/缓存、仅target可写、临时盘2GiB、4GiB/swap0/2CPU/512pids/600秒及control-group收尾已观测，见r02-unit.log；R01已collect后的默认属性不作实证、不为补观测重跑。仅源码/必要测试与work，diff检查通过；未改task/Goal/交接、全量重跑、冻结、重复归档、暂存/提交/push。原Goal active，23的有限重试/发送/积压预算、其他任务参数及真实执行域/Worker与最终验收仍待补，不DONE/complete。

### 原Goal连接/发送预算待批（2026-10-09） [GOAL-CONNECTION-BUDGET-BLOCKED-R03]

- 缓存切片闭环后，连续三轮均无TASK-06/23参数组的人工批准或其他执行域前置。待批建议为全局128连接、每owner2连接、每连接8订阅、10分钟无有效客户端业务活动撤销（推送不续期）、每连接待发进度32条且合计2MiB（含保序积压）、单次通知软件发送总预算5秒及最多追加2次尝试；均未写入默认配置。已安装Spring 6.2.8保序通道字节码核对不替代实际功能/资源验收；不以传输缓冲限制冒称全部积压受控。本轮源码仍无相应配置，上一单元not-found/inactive/dead，无活作业可等待；其他事件/轮询预算和真实执行域/Worker前置仍缺，不能猜生产身份或停止/删除授权。
- 三轮同一必要批准/环境阻断已满足门槛，update_goal已返回blocked，原TASK-00～29、31缺陷及交付标准不变、未complete。本轮仅简记work并检查文档，无源码改动、重测/构建、冻结、暂存/提交/push；缓存119项及其他未失效证据复用，不将请求批准或状态核对算作功能进展。明确批准上述参数组后继续原Goal的现有实现，不新增框架或重复交接文档。


### 2026-10-10 TASK-06：有界连接、实际退订与无消息撤销 [TASK-06-CONNECTION-BOUNDS-R04]

- 已按最新批准继续原Goal（active），不再沿用上一条预算未批准的阻断。只修改现有WebSocketSessionGuard、WebSocketConfig、AccessCodeService及必要测试：全局128连接、每稳定owner最多2连接、每连接8订阅；owner复用QuotaService.ownershipFor，不按审计Principal或登录Cookie分别计数。注册/容量检查共用原拦截器内单锁，重复关闭幂等；物理关闭或框架关闭回调后释放，关闭失败保留占用且维护可重试。退订先保留额度，SimpleBroker实际处理成功后才释放，避免入站异步处理导致瞬时超8；重复ID/退订失败不释放。
- 登出和getSession检测到的到期/账户撤销，通过既有服务的懒引用直接关闭已注册连接，无事件总线、新管理器或调度器。复用原会话维护扫描关闭无消息的失效/空闲连接；10分钟无有效CONNECT/SUBSCRIBE/UNSUBSCRIBE业务活动即不再允许接收，服务器推送和心跳不续期。主动物理关闭仍受原默认60秒扫描周期及在途关闭影响，不宣称恰好10分钟或硬停止上界。
- 实际验收：native、SockJS WebSocket、XHR streaming、XHR polling均通过；16项真实账户禁用/管理员撤权/HTTP登出/到期在无后续客户端帧、无服务器推送时关闭，用户及连接索引回落；4项同owner不同登录共享2连接、超额拒绝、真实断连释放及重连推送；4项8订阅分别真实送达、实际退订释放、第9订阅ERROR/协议关闭及索引清空。原鉴权、CSRF、SEND拒绝、持久化与HTTP/终态业务断言不放宽；空闲阈值/心跳与推送不续期、128全局容量、并发owner额度、失败关闭保留占用由12项定向状态测试覆盖，不冒称真实等候10分钟的计时验收。
- 失败历史保留：R01四套210项202通过/4失败/4错误，均零skip；新夹具将装饰后的SockJS当native导致404，原SEND清理在已证明ERROR后重复DISCONNECT遇CLOSING并中断清理。最小修正入口及finally后，R02仅20项16过/4失败（JsonNode的Iterable断言重载）；改为完整帧及message直接断言，并补broker真实退订完成回归，R03仅16项15过/1失败（超订阅错用身份撤销1008而非既有ERROR/1002协议关闭契约）。沿用原拒绝契约、不接受普通超时/传输异常后，R04仅四入口4/4通过，零失败/错误/skip，13.653秒、退出0。编译R01～R05均退出0；最终有效结果按未失效范围复用：R01的178项、R02的SEND/连接16项、R03状态12项、R04订阅4项，共210项；不是全仓全量绿。R01仍出现旧XHR退出读取超时/Surefire30秒强杀fork，不当作完整资源退出验收；后续三次定向运行未出现该强杀。
- 证据仅现有target/task06-connections-{compile-r01～r05,r01～r04}.log及标准XML相同后缀，关键套件确实执行、零非预期skip、失败退出1正确传播。沿用WSL离线、源码只读/仅target可写、私有网络/临时目录、4GiB/swap0/2CPU/512pids/600秒、内层540秒及隐藏共享daemon套接字；R01活动测试InvocationID=989d3d7d3e314edc8b5b8e23644bcc08实测nobody/nogroup、空能力等限制，编译活动InvocationID=813ded839fdb44a79e0eec29014d7e60实测root空能力；R02～04查询时已collect，默认属性不作实证、不补跑。仅追加work，无task/Goal/交接/索引更新、冻结、全量、暂存/提交/push；保持原HEAD和用户/IDE改动。
- 剩余：TASK-06完整03/04前置、真实浏览器/自动维护时序及最终跨模块资源验收与C01交付未闭环，不标DONE。TASK-23批准的每连接待发32条/合计2MiB（含保序积压）、通知同一5秒软件预算/最多追加2次仍待源码及真实闭环，不能只限制最终传输缓冲来冒称完成；本次核实SockJS HTTP缓存也属于后续待发计量边界，不另建框架或扩入TASK-10/19。原生产执行域/Worker及其他批准、验收阻断不变，Goal不complete。

### 2026-10-10 TASK-23：保序积压与实际写完成计量 [TASK-23-PENDING-BOUNDS-R02]

- 复用既有SubscriptionRegistry委托及ConnectionState，在进入Spring保序队列前限制每连接待发32条/合计2MiB；按STOMP及SockJS封装实际编码计量，超额关闭订阅连接，不改变落盘状态或鉴权HTTP恢复。channel调度完成不释放，native/SockJS WebSocket及XHR polling/streaming、既有EventSource实际write/flush后才幂等释放；写前检查同一帧期限，推送不续业务空闲。EventSource与XHR streaming仍严格隔离会话类别，没有新增队列或通用框架。
- 定向编译R01～03均退出0。R01 ProgressPublisherTest 41、WebSocketSessionGuardTest 17及四transport相关真实验收44，共102/102通过；R02四transport计数/字节积压8及EventSource真实收发/transport隔离1，共9/9通过；均零失败/错误/skip、退出0。真实C++到AC后持有首帧，证明第33条或超过2MiB实际断连且索引回落；被持有帧不送达，完整final.json、冷FileTaskStore及鉴权HTTP仍与真实终态相同。EventSource连续35次真实终态通知flush后每次计量回零，不误关连接。有效111项仅按范围通过，不冒称全量绿。
- 证据复用target/task23-pending-{compile-r01～03,r01～02}.log和标准XML同名后缀。沿用离线WSL、nobody/nogroup、空能力、NoNewPrivileges、私有网络/临时目录/设备、源码只读/仅target可写、4GiB/swap0/2CPU/512pids/600秒及内层540秒/kill-after15秒，隐藏共享daemon套接字；R01活动InvocationID=cf45ee23026b467b8a40a5540a08af65限制已实测，R02已collect后的默认属性不作实证、不补跑。仅更新work，无task/Goal/交接/索引、冻结、全量、提交/push。
- 剩余：broker到实际写前的5秒帧期限尚不等于ProgressPublisher整个通知的软件总预算；最多追加2次仍需现有发送路径的最小接入及真实闭环。TASK-06/23与原Goal不标DONE/complete，其他生产执行域/Worker及最终验收阻断不变。

### 2026-10-10 TASK-23：同一通知预算与有限追加尝试 [TASK-23-SEND-BUDGET-R03]

- 只对已持久化的同一快照最多尝试3次，共用通知起点的单一5秒单调期限及notice ID，不因重试刷新期限。复用原消息转换器、broker与出站执行器，先按实际订阅预留条目/编码字节，再入既有执行器；broker二次接入和重试不重复占额，取消移除未运行Future。失败/中断共享取消标记，晚到通知不可写，原维护扫描回收失败/过期连接；保留同judge提交保序、首终态/revision与鉴权HTTP，不新增池或通用管理器。该软件期限及有界等待不冒称硬中止转换/系统调用或恰好5秒物理断连。
- 编译R02～05退出0，R05仅测试夹具变化、主源码up-to-date；R01命令引号错误退出1、未进入Java编译，未计通过。R01阶段63/63通过；最终R02的89项87通过/2失败，失败是XHR两入口的测试客户端5秒read timeout抢先断开1006，SocketTimeoutException已实证。仅该故障用例使用10秒客户端读取等待，原夹具仍5秒，产品5秒预算、1008/既有SockJS关闭契约、零晚到帧、完整冷读/HTTP断言不放宽。仅补跑相应参数化方法，R03 13/13、零失败/错误/skip、退出0、48.818秒；复用R02未失效76项，最终89项按范围通过，不是全量绿。
- 真实C++先达到AC：四入口两次失败后第三次送达同一完整终态；三次失败/5秒耗尽后通知零送达、维护实际关闭及连接/字节/条目回落，final.json、冷FileTaskStore与仍获授权HTTP全部一致。独立测试执行器仍被持有时，已预留通知排队后到期，队列确实清空、broker执行0次；仅测试池随后正常退出。原四入口40例/首RUNNING持有至真实终态/无越过、8项总待发边界及EventSource 35次flush恢复仍通过。
- 证据仅target/task23-send-{compile-r01～05,r01～03}.log及标准XML同名后缀；离线隔离、nobody/nogroup、空能力、私有网络/临时目录/设备、源码只读/仅target可写及4GiB/swap0/2CPU/512pids/600秒沿用，R01活动InvocationID=6895d3af86a24602b77f5234cb04354a属性已实测，R02/03 collect后默认属性不作实证、不补跑；日志仅测试命令覆写到私有/tmp，不扩大源码写权限。只更新work，不全量/冻结/改task、Goal、交接或索引，不暂存/提交/push。跨模块、适用平台、资源/回滚、真实生产执行域/Worker前置与C01交付仍未闭环，TASK-23及原Goal不DONE/complete。


### 2026-10-10 Windows 本地启动修复（Goal保持暂停）

- 同一Windows JDK 21.0.12.1实测目录BasicFileAttributes.fileKey为null，原生命周期校验在C盘根目录即拒绝。仅在原TaskLifecycleGuard中复用JNA读取卷号/128位文件ID，拒绝reparse point及多硬链接锁，保留owner、规范路径、进程锁和POSIX私有权限校验；不以路径或时间戳代替身份。
- Windows定向最终目录锁套件30项：24通过、6项原POSIX专属用例跳过；硬链接拒绝、锁文件替换和跨进程互斥实测通过，强制锁阻止写入并保持零长度。复用存储86通过/3跳过、清理17通过/1跳过；存储仍有1个符号链接夹具因Windows创建权限不足报AccessDenied，未改业务断言或冒称全绿。
- 真实Spring启动使用同一JDK、target内隔离任务目录及临时随机访问码：目录/锁创建成功、HTTP页面200、正常关闭、退出0。默认访问码留空仍按安全策略拒绝启动，IDE需配置JUDGE_AUTH_ACCESS_CODE；未修改用户凭据。仅现有Surefire报告和target/windows-lifecycle-startup-smoke.log，不冻结/全量/更新task或Goal、不自动提交/push。
