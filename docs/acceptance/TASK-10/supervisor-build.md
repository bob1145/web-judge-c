# TASK-10 可信监督器镜像构建记录

日期：2026-10-03。状态：独立监督器工具链镜像已真实构建并断网重放，尚不等于容器化监督器执行、Java集成或生产隔离通过。生产门禁保持关闭。

## 输入与边界

- 镜像定义：runner/linux-supervisor/Dockerfile；与含GCC的executor镜像分离。镜像包含Python、Docker CLI和五份哈希锁定产品源码，不包含gcc/g++/pip/dockerd；运行时只读验证五模块和目录权限。USER root仅为本轮可信域实验身份，不作为生产部署默认。
- 基底仍为已核验Alpine来源，专用vfs导入ID为sha256:706db57fb2063f39f69632c5b5c9c439633fda35110e65587c5d85553fd1cc38；共享daemon仅info/inspect/save，未在其中构建、加载或运行新镜像。
- R01仅官方Alpine v3.22 main/community取python3/docker-cli递归依赖阶段联网，实际26个签名APK、28707313字节，完整文件/版本/SHA256见SUPERVISOR-BUILD-R01/packages.json和SHA256SUMS；归档保存在target/task10-runtime/SUPERVISOR-BUILD-R01/packages.tar。停止联网daemon并核验后代退出后才进入新断网namespace构建。
- R02从上述精确归档还原，整个systemd单元PrivateNetwork=yes；两轮均空仓库/no-network离线安装并保留APK签名验证，没有allow-untrusted。五产品源码复制后完整校验清单哈希，镜像内再次校验。
- 两轮预算：内存1504MiB（包括所有tmpfs/后代）、swap0、CPU2、512pids、570+30秒；tmpfs1408MiB、包归档128MiB、镜像归档384MiB，合计上界2016MiB小于批准2GiB临时存储。保留pivot_root、私有proc与旧根卸载。

## 实际结果

| 轮次 | 退出码 | 服务耗时 | 构建时整树memory.peak | 结果 |
| --- | --- | --- | --- | --- |
| SUPERVISOR-BUILD-R01 | 0 | 29.701秒 | 725282816字节 | 官方取包、断网构建、工具链烟测、镜像导出成功 |
| SUPERVISOR-BUILD-R02 | 0 | 15.038秒 | 725053440字节 | 完全断网重放同一包锁、烟测和导出成功 |

两轮辅助合同各32/32、零失败/错误/跳过；不与Java/Python产品套件混算。实际Python为3.12.15、Docker CLI为28.3.3（build 980b85681696fbd95927fd8ded8f6d91bdca95b0）。这些是镜像内实测版本，不是所有版本兼容承诺。

真实烟测包括五模块导入、源码哈希与只读mode、工具版本、静态capabilities；缺必需参数返回30/统一消息且stdout为空。没有连接运行时socket或实际派发阶段，不把静态能力输出当隔离证明。专用容器计数为0后才导出，两轮daemon均退出0。systemd显示的11.6M/11.4M不是整树峰值，不替代直接cgroup读数。

| 轮次 | 完整镜像ID | 归档SHA256 |
| --- | --- | --- |
| R01 | sha256:f79ced8cc720b33afe9a5c2a9ac575c7f8476754be98a5b328281d6e1f50f1bb | 8fdb0223b01355cf31cd7973ca8cc973c90f262218846a4155286b556d59e7d4 |
| R02 | sha256:5336666f3790e6c72db15c3570a561ac6d4fafd68b119e7be9d8043fba04bffe | 0aca8aa864d9b8fc173ad9219eb7a266ed69300eb0b32262d2e5a2544686a580 |

两份supervisor-image.tar均为120208384字节，镜像inspect Size均118455186；Windows侧Get-FileHash独立核验与记录一致。二进制仅留Git忽略target目录，不纳入提交。每轮10份输入快照另核验全部匹配（hash-verification.json）。同一源码/包锁得到不同镜像ID及归档哈希，证明本轮不是逐位确定性构建；不得称两份镜像完全相同。

## 复跑与剩余门槛

实际完整入口argv、systemd限制和构建/烟测命令保存在各轮steps.jsonl。新一轮必须使用未存在的证据目录和独立unit。R01模式outer-supervisor-image-build，R02模式outer-supervisor-image-replay；unit分别wjc-task10-supervisor-build-r01/r02。

下一步是镜像内监督器挂载单卷的daemon原始Mountpoint、专用socket和明确可信任务目录，验证权限拒绝及真实六阶段；随后Java调用、持久生命周期、真实取消/晚到创建验收和最终回归。root写入者实验不得代替生产宿主UID映射，不能挂整个daemon数据目录或使用hostPID/privileged绕过。TASK-10仍IN_PROGRESS、未提交、DEF-05/N2未关闭、不可发布。

## SUPERVISOR-CONTAINER-R01 失败记录

双镜像容器化验收首次使用原2GiB内存/1408MiB根tmpfs预算，实际unit退出1、20.374秒。在基线USER编译容器create时，专用daemon明确返回 `no space left on device`，尚未运行任何监督器容器。加载两镜像的vfs层和新容器副本超过原临时盘容量，不能称为权限拒绝或镜像内入口成功。

产品最终报未知创建且journal恢复必需；保留下来的journal仍为journalVersion1、creationState UNKNOWN、state CREATE_REQUESTED（磁盘不足使后续状态落盘失败），没有CLEANED或产物。实验harness随后观察本专用域容器/卷为空并停止daemon（退出0）；这不是产品崩溃恢复成功。完整原日志见该轮daemon-log-prefix.txt、steps.jsonl和stage-journal。

下一轮拟仅在原批准4GiB内存、2GiB临时存储上限内重新分配容器化模式预算，不改生产资源默认、不增加试验授权总额。其他模式和历史失败证据保留。

## 容器化R02～R04与权限漂移修复

- R02改为4GiB内存、1792MiB根tmpfs，其他限制不变；另计同时3个容器各64MiB shm与32MiB卷合计2016MiB，未扩大原批准总额。四拒绝容器均返回30；合法监督器首次GENERATOR编译实际成功，但产物交接/清理失败（journal CONFIRMED、outcome SUCCESS0、CLEANUP_FAILED），unit退出1、22.322秒。未以编译成功冒称整体通过。
- RECOVERY-R01直接提供根因证据：create前scratch为root:root0777，create后、尚未start即变成65532:65532/0700，与executor镜像/scratch元数据相同。Docker卷默认copy-up改变了阶段目录归属；外侧root完整能力掩盖问题，而cap-drop ALL的监督器无法访问该目录。
- 最小产品修复仅给两个阶段挂载增加volume-nocopy，保持原cap-drop ALL/NNP/隔离与资源限制，不增加DAC能力或放宽目录权限。增强真实inspect检查和负例。曾准备的额外诊断脚本因已有直接证据而移除，未执行、不纳入验收结论。
- SUPERVISOR-BUILD-R03使用同一签名APK锁断网重建，unit退出0、16.911秒，48项辅助合同全过、整树memory.peak724869120字节。新镜像sha256:6a9814bbd6db588bb58c9aeba3220873022a761bec27bcd5bf6ef395dad3c545；120208384字节归档SHA256为5a3780ceb7579738facfdf0730f560add2c6584e289b424959084ea42a292929，Windows独立哈希匹配；11份输入快照匹配。
- CONTAINER-R03在daemon启动前失败（unit1、4.941秒）：新增stage helper实际13/13通过，但脚本预期数量仍12而返回1。同步实际数量到13（恢复helper到17），没有删测试或放松安全断言；该失败保留。
- **CONTAINER-R04通过**：unit0、27.674秒、整树memory.peak2035732480字节，48+13+7辅助合同全过。实际五个独立监督器容器分别执行四项配置/权限拒绝和合法任务，保留各自create/inspect/attach/exit/cleanup证据；四拒绝30且无执行副作用，合法返回0、AC1、六个不同实际阶段ID与CLEANED journal。另有两个直接观察的基线阶段。
- 监督器实际UID0、cap-drop ALL、NNP、只读root、断网、256MiB/swap同值、32pids、0.5CPU、log none；仅映射本轮任务根、单个卷到daemon原Mountpoint及专用socket。既有四条audit及metadata/final/spec/source/tool哈希未变。各监督器按完整ID/完整标签核验清理，最终容器/卷为空，daemon退出0。R01～R04每轮12份源码快照均匹配。
- 内部六阶段证据仍是实际镜像内产品journal/CLI流与最终资源结果，不冒称逐个stage inspect/stdout；这是容器化监督器路径首次成功，不是Java入口、生产身份映射或完整恢复通过。


## SUPERVISOR-BUILD-R04：持久stage准入版本

复用既有锁定APK离线构建，unit0/15.551秒、66辅助合同通过，真实镜像内源码hash/导入/capabilities/无参数拒绝烟测通过，整树memory.peak725458944字节。新image为sha256:6f7e70984cfff475fdd0fecc315cc7620c16b8f0e0faa23a22414b7271bbd901；120221696字节归档SHA256=5b8eb56ef08c5f1c1db854b68cd97d408e5dd906d107c7d4eac827006e4c3288。11份快照和归档独立核验，预算未增加，二进制只留忽略target。

该镜像用于JAVA-SUPERVISOR-R07/R08，最终R08严格闭门验证通过；R07的marker内容独立绑定缺口及后续红绿历史保留。详见stage-admission.md。镜像构建本身仍不代表完整恢复/生产支持，Java原生flock桥接、UNKNOWN权威终结及资源回收尚未完成。
