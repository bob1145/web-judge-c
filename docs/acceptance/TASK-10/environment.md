# TASK-10 专用运行时预检

## 授权与范围

2026-10-03用户明确“后续的都批准”，本轮按已提出的方案落实本机专用临时运行时/测试镜像、仅本任务新增资源的清理和实验外层预算：总内存4GiB、swap0、CPU2、进程512、临时存储2GiB、总运行及终止上限600秒。实际watchdog设运行570秒、终止30秒，未增加预算。此授权不包含push、破坏既有工作、生产API或擅自决定未列定的业务默认值。

首先只进行良性环境预检，未把通过本脚本等同于TASK-10控制面完整验收。runtime_preflight.py固定argv：只读从共享daemon导出本地已存在alpine:3.22.2的不可变image ID，然后只向专用chroot内的socket操作；不让不可信程序接触共享daemon。所有新daemon/容器均须落在受限systemd unit的cgroup层级内；同UID/改名或只限制docker客户端不合格。

## 必须保留的执行偏差

只读辅助探索阶段，子代理在PowerShell→WSL→bash复杂字符串中发生引号解析错误，意外执行无参数containerd，输出显示等待默认BoltDB锁。已对session43445发Ctrl+C，该session退出1。主代理随后独立只读核验：containerd PID231、dockerd PID317，systemd均active/running且NRestarts=0；运行容器21个；未见额外containerd/dockerd进程。systemd ExecMainStartTimestamp分别为2026-10-02 17:22:55/56 CST，ps lstart分别为同日17:47:24/25，两组原值保留，不据此推断时钟差异原因。

没有停止/删除/重配共享服务，也没有清理共享目录。不能保证意外进程退出前未触碰默认目录元数据；不能把这次探索称为纯只读成功。后续取消嵌套shell组合命令，改用仓库内脚本和subprocess固定argv。本偏差不计入正式隔离通过证据。

## 静态审核与入口

只读explorer复核了专用脚本的共享socket只读边界、独立data/exec/config和cgroup/PID路径。执行前修正了两处问题：不再混用unshare --root与宿主绝对proc挂载路径，改为进入新PID/chroot后明确挂载/proc；不依赖容器shell是否exec优化，改验真实PID namespace/NSpid。该审核不是实际测试结果。

首轮证据ENV-R04，unit wjc-task10-env-r04。每次失败/重试使用新证据目录和unit，不覆盖历史。脚本记录阶段、实际限额、镜像ID、程序哈希及受限日志；完成后另从外侧确认共享服务存活且没有新daemon残余。尚未运行攻击负例或新增全局依赖。

## ENV-R04～R06 失败与纠正

- R04退出1：进入私有PID/chroot后，临时文件系统nodev使新建/dev/null不可打开；尚未启动daemon。改为仅bind标准null/zero/random/urandom四个设备文件，不暴露宿主设备树或增加可写文件系统。
- R05退出1：网络守卫要求/proc/net/route恰好一行，触发拒绝。R06保持原断言，仅补脱敏观测，仍退出1；实际routeLines为空、接口仅lo，证实是空表而非外部路由。新增网络解析函数及6项正反回归，接受空/仅表头无路由且只有lo，仍拒绝任意IPv4路由、外部接口、外部IPv6路由或缺失设备表。没有删除/跳过网络守卫。
- 三轮均在daemon启动前停止；外侧核验三个unit inactive，仅见共享containerd231/dockerd317，无新daemon遗留。
- R07起将实验内存上限主动收紧至2GiB（低于获批4GiB上限），swap仍0，所有daemon/容器及新增tmpfs页均在同一外层cgroup中；避免只限制/tmp大小而遗漏容器/dev、shm、scratch等额外tmpfs。临时存储上限仍2GiB，未提高任何已批准额度。这不是生产容量/性能验收，也不改变受信Maven基线的4GiB设置。

## ENV-R07～R15：失败保留与良性预检通过

| 轮次 | 实际退出码 | 事实与后续修正 |
| --- | --- | --- |
| R07 | 1 | daemon启动后导入镜像失败：remount / invalid argument；为临时根增加self-bind挂载点。 |
| R08 | 1 | 导入成功但来源OCI index ID不能用于classic/vfs镜像启动；新增RootFS/Config/Architecture/Os完整对照，仅用内容一致的导入ID。 |
| R09 | 1 | 容器启动及真实限额/UID/seccomp/PID检查通过，文件系统组合断言失败；未放宽断言。 |
| R10 | 1 | 增补观测发现exec的/scratch不存在；未运行攻击。 |
| R11 | 1 | 初始进程/proc/<pid>/root是Alpine且scratch/allowed存在，但exec看到Ubuntu根及无scratch；原chroot-only结构不满足环境边界。 |
| R12/R13 | 各1 | 改用独立挂载根pivot_root后，在尚未挂载私有proc时umount旧根返回ENOENT；尚未启动daemon。 |
| R14 | 1 | 在pivot/chroot之后先挂载新PID域proc，再卸载旧根；exec恢复Alpine/scratch视图。诊断readlink使用多路径，BusyBox拒绝；按实际单路径接口修正。 |
| R15 | 0 | 原文件系统断言及新增exec/init挂载和PID命名空间一致性全部通过；专用容器删除后计数0，daemon退出0。 |

R11只确认了实际视图不一致，未将底层原因归因于某个Docker/runc漏洞或CVE。修正使用独立mount namespace内pivot_root，当前进程chroot到新根，挂载私有proc，分离旧根，随后exec启动监督脚本；不以chroot或仅更改UID作为完整隔离。R15正常编译和角色攻击仍未执行。外部检索未获得可用资料；修正依据本机工具帮助及逐轮实测，不声称获得外部漏洞确认。

R15运行环境：Docker29.1.3/vfs/cgroupfs，containerd2.2.1，runc1.3.4-0ubuntu1~24.04.1，WSL kernel6.18.33.2。共享源Alpine3.22.2 ID为sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412；导入ID为sha256:706db57fb2063f39f69632c5b5c9c439633fda35110e65587c5d85553fd1cc38；对应配置/层/平台逐字段验证一致。该映射不是换tag或换镜像。

每轮R09起预检辅助单测实际11项、0失败/错误/跳过（与此前runner的Python11项是不同套件，不能混算）。R15容器实际UID65532、CapEff0、NNP1、seccomp2、独立PID/mount namespace，真实cgroup为/workloads/<本轮ID>，64MiB/swap0/32pids/0.5CPU；根只读、scratch可写且8MiB/noexec/nosuid/nodev，不可见专用socket、证据目录或/mnt/c。外层2GiB/swap0/512pids/2CPU、tmpfs2GiB、watchdog570+30秒；服务耗时3.627秒，systemd所示Memory peak 9.8M不冒充所有进程实测RSS。

R15后外侧实际核验：ps仅共享containerd PID231、dockerd PID317；docker.service/containerd.service均active且NRestarts=0。ENV-R10～R15六个unit均inactive（systemctl is-active因此预期返回非零，并非服务仍在运行）。没有清理/重配共享运行时。R12因切根中断未写preflight-failure事件，失败退出码及原异常在本表保留，不把缺事件当成功。

完整TASK-10尚缺可信监督器产品实现、可复现编译镜像、所有角色真实负例、正常编译正例及全量回归；预检不关闭DEF-05/N2、不增加DONE计数，未提交。

## 后续：编译镜像与公共预检回归

BUILD-R01～R04失败及R05真实离线构建/固定C++17正例通过详见build.md。新增执行镜像不含可信监督器；原Linux生产路径仍失败关闭。ENV-R16使用最新公共脚本及18项辅助测试重跑原良性预检，退出0、零跳过，原断言继续通过。该阶段仍不等于TASK-10角色隔离验收。

## 2026-10-03 18:44 外侧只读复核

CONTAINER-R04、RECOVERY-R02和Java R07结束后，独立WSL argv读取systemctl/ps：docker.service MainPID317、containerd.service MainPID231，均active/running、NRestarts0；wjc-task10*无loaded unit。进程列表仅共享daemon及已有containerd-shim，没有runtime_preflight/stage_recovery_probe实验进程。未操作共享容器；无loaded unit不能覆盖每轮保存的真实退出码。首条bash复合命令因跨shell引号失败退出1，后分开命令退出0，此核验仅以后者为据。
