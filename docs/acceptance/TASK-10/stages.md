# TASK-10 阶段后端实现与验收记录

当前为开发中证据，不是 TASK-10 完成证明，不放开生产门禁。

## 首轮合同回归

- CONTRACT-R04：受限 WSL systemd 单元，33 项测试，0 assertion failures、50 errors（包含子测试错误）、0 skips，unit 退出1。ProtectSystem=strict 下未配置可写临时目录，TemporaryDirectory 失败；保留 summary.json，不归因为阶段实现全部失败。
- CONTRACT-R05：增加专用 /tmp tmpfs（512MiB）后，33/33、0失败/错误/跳过，unit退出0。Python报告耗时21.468秒、service runtime22.039秒。组成：原Runner合同11项、阶段CLI替身16项、TaskRunner阶段派发6项。
- 两轮共同边界：MemoryMax=4G、swap0、CPUQuota=200%、TasksMax=512、RuntimeMaxSec=300、TimeoutStopSec=20、KillMode=control-group、PrivateNetwork=yes、ProtectSystem=strict、ProtectHome=tmpfs、NoNewPrivileges=yes；仓库只读，仅TASK-10证据目录允许写。测试为可信合同样例，不包含容器攻击。
- 入口为 python3 -B runner/tests/run_contract_suite.py docs/acceptance/TASK-10/CONTRACT-R0{4,5}/summary.json。各轮summary包含实际收集数及非通过详情；没有将CLI替身通过当成Docker实际执行证据。

## 当前实现边界

linux_stage_executor.py 增加独立编译/执行CLI调用、最小子目录挂载、容器ID/归属检查、阶段意图日志及移除后安全交接普通产物。task_runner.py 可注入此后端，编译和四角色统一派发，SPJ只取得当前输入与候选输出，安全异常脱敏。默认本地行为不变；尚未接入生产CLI/Java监督器路径。

R05之后只读审查提出：卷实际归属及容量验证、崩溃恢复消费者、取消与创建串行化、真实停止后的退出状态、显式关闭daemon日志。后续修复与实测见下节，未一概视为关闭。现有run_process无界communicate输出问题也仍存在（后续TASK-12范围），不能将当前内存参数宣称为整个可信控制面的输出预算。

ENV-R16之后补查：共享containerd PID231、dockerd PID317，均active、NRestarts=0；ps未见额外dockerd/containerd。此只读外侧核验不替代全局资源残留或生产安全验收。

## CONTRACT-R06与STAGE-R01

- 产品增加逐阶段强制卷验证：完整holder ID、attempt归属、local tmpfs配置、实际mountinfo精确挂载、32MiB明确实验上限、root身份/目录权限、statvfs容量及运行中holder唯一挂载；不接受普通目录/无限额卷/外国任务。当前验证要求监督器可见的卷根路径等于daemon实际Mountpoint，不支持任意映射，也不代表恢复消费者已完成。
- 取消在入口、复制完成及start前检查；取消/TLE/OLE时若容器仍运行，向已确认归属的完整ID发送KILL，再inspect真实停止/ExitCode/OOM状态后移除，停止失败不返回占位退出码。阶段显式--log-driver none。
- CONTRACT-R06：65/65、零失败/错误/跳过、unit退出0，Python23.178秒、服务23.994秒。包括原11、阶段后端30、卷验证18、角色派发6；替身验证和真实容器证据严格区分。完整日志在CONTRACT-R06-unit.log，源码/测试快照及SHA256在CONTRACT-R06。PowerShell将systemd stderr的正常启动信息包装为NativeCommandError，但实际unit/测试均退出0。
- STAGE-R01：服务退出0、耗时16.729秒；环境辅助18/18与观察器合同9/9。只读绑定已核验BUILD-R05归档并仅导入专用daemon；共享daemon仍只info/inspect/save。
- 实际使用产品LinuxStageExecutor编译固定C++17源码（680ms）到18840字节普通产物；编译容器确认移除后导出，SHA256为fe2b842eb29662c5843c554d8b9be94a722347890d328c0c66bc0a73faa11c70。另一独立容器执行（127ms）并输出精确TASK10_SEPARATE_STAGE_OK。完整两个容器ID、inspect、命令和journal见STAGE-R01。
- 实际卷为33554432字节tmpfs、rw/nosuid/nodev，holder保持挂载，volume-subpath兼容性通过。固定程序断言UID/GID65532、scratch可写、input程序不可写、编译源码及/lab/运行时socket不可见。仅执行USER编译/执行正例，不冒称全部角色或所有控制面负例通过。
- 外层2GiB内存、swap0、2CPU、512pids、570+30秒、tmpfs1408MiB，实测整树memory.peak=1514184704字节。systemd显示10.7M不是整树峰值；attach CLI的RSS也不是负载峰值。无联网取包/镜像构建/新大归档。
- 正常结束记录专用容器与卷均为空、daemon退出0；外侧ps仍仅共享containerd231/dockerd317，服务active/NRestarts0。七个脚本/产品源码证据快照SHA256独立复核全部匹配（hash-verification.json）。

## 下一验收门槛

继续可信监督器CLI/镜像和Java接入、取消/崩溃恢复、完整全量回归。TASK-10保持IN_PROGRESS；没有新提交，DEF-05/N2未关闭。

## CONTRACT-R07与STAGE-R02

- 新增linux_supervisor.py与23项合同：专用入口必需显式spec/work-root/config；配置固定在当前attempt目录、精确字段/版本/attempt校验、64KiB上限、FD相对打开、拒绝符号/硬链接及非普通文件。按LinuxStageConfig字段派生配置契约，复用既有manifest解析，不引入本地回退。运行前实际验证卷，错误输出通用脱敏；静态--capabilities不宣称安全能力。
- TaskRunner对阶段一般运行时异常也只输出通用失败消息，避免超时命令和私有路径进入用户事件；新增1项回归。CONTRACT-R07实际89/89、零失败/错误/跳过、unit退出0，Python19.776秒，service20.523秒。源码/测试快照及哈希见CONTRACT-R07，完整输出见CONTRACT-R07-unit.log。监督器成功路径此时仅合同替身，尚未真实CLI/镜像验收。
- STAGE-R02：真实unit退出0、28.316秒，辅助18+9+7项全通过，未合并冒充新增全量。相同专用断网环境、预算与固定镜像，10个不同阶段容器：原正例2个、实际TaskRunner的GEN/USER/ORACLE各编译/执行6个、SPJ独立编译/执行2个。不是mock执行，也不是Java调用闭环。
- 每个角色实测UID/GID65532、scratch可写、input只读；对实际事件/spec/summary/cancel、约定root/events.jsonl四条宿主audit、metadata/final、真实监督器工具源码及合成私有文件的读取/写入失败。四份C++源码的__has_include控制路径拒绝守卫全部编译通过；不冒称穷举编译器攻击。
- 四角色对可信哨兵PID689的/proc/cmdline/fd/root路径不可见，遍历自己可见进程/FD未发现控制路径；固定SIGCONT返回ESRCH。可信哨兵真实继承私有文件FD，完成后仍存活、身份及FD未变，最后由可信侧终止（退出-15）。这不是kill(1)测试，也不等于穷举所有进程攻击。
- 每个角色输出有限伪SUMMARY/COMPLETED（伪ac=999），真实TaskRunner仍只有1个AC/1个completed，6条可信事件顺序准确，没有接受伪造完成事件。宿主audit、metadata/final、spec、工具/私有文件、原始源码哈希均未变，cancel未出现。SPJ仅得到当前case.in/out+program，其执行不改已有真实摘要/事件；SPJ不是TaskRunner端到端模式验收。
- 角色执行内存128MiB、编译256MiB，仍由外层2GiB/swap0/2CPU/512pids/570+30秒限制；整树memory.peak=1530814464字节。资源清单容器/卷均空，daemon退出0；九份实际脚本/产品快照哈希独立复核匹配。
- 证据位于STAGE-R02/roles（4份实际源码、before/after哈希、真实spec/events/summary/audit、哨兵状态/namespace/FD、阶段结果）与本轮steps.jsonl、10组before/after inspect及journal。完整支持/发布结论仍未成立；监督器镜像、Java入口与持久生命周期不得由这些实验结果替代。

## SUPERVISOR-R01/R02与CONTRACT-R08

- SUPERVISOR-R01首次在专用运行时执行未替换的linux_supervisor.py CLI：缺runtime_endpoint返回30且无workspace/events/journal/阶段容器，完整显式配置返回0、真实AC1，三角色共6份产品journal均CLEANED。加原基线2阶段，实验共8阶段；服务21.102秒、整树memory.peak=1528872960字节，daemon退出0、容器/卷回收。
- 随后独立只读复核发现配置属主与group/other写权限尚未验证。没有用第一次正例关闭该缺口；新增必填--config-owner-uid，不从待验证JSON自证身份，也不猜Java进程UID。文件属主必须精确匹配；work_root/control/attempts/attempt逐层FD核验属主属于指定写入者或当前监督器，均禁止group/other写。工作负载UID必须不同于两个可信身份。0644/0755可接受，保证完整性而非机密性；实验UID0不作为生产默认。
- CONTRACT-R08：100/100、零失败/错误/跳过、unit退出0；Python22.245秒、服务22.795秒。监督器合同从23增至34项，加入属主/可写目录/可写配置/同UID/参数缺失反例。完整日志、11份源码/测试快照及SHA256见CONTRACT-R08。
- SUPERVISOR-R02：四项真实CLI拒绝（缺字段、错误期望UID、配置0666、control父目录0775）均返回30/统一消息，无任何执行文件或阶段容器；拒绝前后目录清单、内容哈希、元数据与资源清单一致。只改变本轮新建路径，finally恢复原mode，合法执行前再确认基线恢复。
- 恢复可信配置后真实CLI返回0、单用例AC1；6个完整且不同的阶段ID、可信journal/outcome/角色phase顺序及最终空卷均核验，宿主4条audit、metadata/final/spec/源码/工具未改变。服务23.800秒、整树memory.peak=1528909824字节，预算与前轮相同，专用daemon退出0、所有容器/卷回收。11份实际运行快照哈希另行核对全部一致。
- CLI内部6阶段没有注入逐阶段观察器，因此证据是实际CLI输出、可信产品journal与最终资源检查；不伪称本轮有这6阶段的逐个inspect或程序stdout记录。原基线2阶段仍有直接观察，四角色负例完整记录见STAGE-R02，不能混写为本轮重跑。
- 本实验监督器是专用Linux域内的实际Python进程，不是最终部署镜像，也不是Java发起。生产集成必须显式解决配置写入者/监督器UID和宿主存储权限映射，不能照抄实验root UID或放宽完整性检查；不得批量chmod/chown既有存储来绕过门槛。

### SUPERVISOR-R02 后外侧只读核验

2026-10-03恢复工作后，用独立WSL argv执行 `ps -C dockerd,containerd -o pid,ppid,comm` 及 `systemctl show`：仅共享containerd PID231、dockerd PID317，均active/running、NRestarts=0；wjc-task10-supervisor-r02为not-found/inactive/dead，Result=success。三条只读命令均成功，未停止或重配共享服务。本次之前一次bash/awk嵌套引号命令解析失败（退出1、awk usage及command not found），未获得检查结论；纠正后独立执行，不把该失败记为通过。本次只能证明观察时未见额外同名daemon，不替代全面资源或生产验收。

## CONTRACT-R09：未知创建与持久拒绝

- 只读审查发现旧超时测试仅覆盖先创建成功再丢回复，未覆盖inspect暂不存在后创建才落地。产品新增journalVersion=1及独立creationState；create调用前持久UNKNOWN/CREATE_REQUESTED，完整ID与归属inspect成功才CONFIRMED。未知回复保留CREATE_UNKNOWN和stage目录、禁止导出；即使清理了已可见容器也不宣称CLEANED。
- journal逐条有界读取（64KiB）、拒绝链接/FIFO/损坏/重复字段/旧版本/不匹配/未完成及遗留tmp；Linux目录FD的非阻塞flock覆盖检查到清理。新实例不能绕过同attempt未知记录；正常成功及发送前取消仍可CLEANED。未实现权威创建屏障或自动恢复消费者。
- 实测118/118、0失败/错误/跳过，Python22.273秒，systemd服务23.940秒、真实退出0；阶段合同48项，其他18+34+11+7项。11份源码/测试快照及hash-verification.json全部匹配。完整命令在summary.json及CONTRACT-R09-unit.log，预算沿用R08。
- 本轮PowerShell合并stderr到Tee-Object时把systemd正常启动信息包装为NativeCommandError，外层包装退出1；测试summary和systemd均明确退出0。这不是失败测试，也不能把包装命令写成退出0。真实延迟请求验收另行准备，当前仅为CLI替身和实际文件锁合同。

## RECOVERY-R01/R02：真实延迟请求与未知态保留

- 两轮都先完成原四角色10个真实阶段，再通过专用Unix代理缓存唯一create请求；不替换产品_control，实际Docker CLI在2秒后超时，此时真实daemon尚无该容器。产品保留UNKNOWN/CREATE_UNKNOWN、stage目录和输入，无导出产物；全新executor立即拒绝且不再次联系runtime。
- 随后代理才把已缓存请求转发给真实专用daemon，得到201及完整ID；实际容器停留created/Pid0，从未start。新executor继续拒绝，journal保持UNKNOWN，不以现在发现容器改成CLEANED。证明当前不存在不能保证未来不存在，而非模拟最终容器对象。
- 代理请求/响应128KiB、header16KiB、请求64、并发4、持有deadline25秒；只允许固定资源inspect/ping/version和固定compile create，禁止start/exec/delete。关闭并join全部线程、证明唯一create已终结后，才由实验harness核验归属清理容器及保留目录；journal不修改。这明确不是产品恢复。任何未完成异常路径跳过逐资源删除，终止整个专用运行时域。
- R01 unit0、26.335秒、memory.peak1533382656；辅助48+12+7+16全过。该轮直接观察copy-up权限漂移，支持后续volume-nocopy修复，原证据未修改。
- R02在修复后复验，unit0、27.958秒、memory.peak1534025728；辅助48+13+7+17全过。晚到create后scratch仍root:root0777、input仍root:root0755，新增NoCopy负例与真实断言通过。未知态/新实例拒绝/无产物保持正确。
- 两轮均为2GiB内存、swap0、2CPU、512pids、570+30秒、1408MiB根tmpfs、断网专用域。代理各9个请求、1次create、线程0残留、faults为空；实验结束容器/卷为空、daemon0。每轮14份快照hash独立核验全匹配；详见stage-recovery内before/after元数据、实际inspect、代理关闭证明和保留journal。
- 完整自动恢复、Java持久资源消费者和权威创建屏障仍缺；不得因实验清理成功关闭这些缺口。镜像内监督器真实正反例见supervisor-build.md的CONTAINER-R04，不混写为本轮Java或容器化监督器运行。
