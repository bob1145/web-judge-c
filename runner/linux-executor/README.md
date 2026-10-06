# Linux 独立执行镜像构建材料

本目录是 TASK-10 的离线构建入口。BUILD-R05 已通过真实断网重放构建和固定可信 C++17 hello 编译/运行正例；BUILD-R01～R04 失败历史完整保留。该结果仅证明当前材料可构建且工具链能执行固定样例，不等于独立阶段、四角色或控制面安全验收，不能据此启用 `linux-prod` 或关闭 DEF-05/N2。

## 边界

- 镜像只提供 Alpine 和 g++ 工具链，不复制监督器、任务源码、清单、审计文件、取消文件或控制 socket。
- 编译器、GENERATOR、USER、ORACLE、SPECIAL_JUDGE 都是不可信负载。权限、阶段输入、PID/网络隔离及资源限额由外部可信监督器逐次设置，不能由镜像标签证明。
- 默认 UID/GID 为 `65532:65532`，默认命令失败退出。`/scratch` 是唯一预设工作目录；运行时使用只读根文件系统，并单独提供有界可写 scratch。
- Dockerfile 不下载依赖，不允许未签名 APK，不替换 Alpine 官方公钥。哈希锁与输入 APK 全集精确比较，缺包、多包、改包或锁格式差异均终止构建。哈希锁用于完整性/重放，官方签名用于来源验证，两者不能互相替代。
- APK 安装显式使用空仓库文件和本地 `/var/cache/apk`，不读取基底默认远程仓库；先用缓存完成签名安装与 g++ 检查，再仅删除镜像内 `/var/cache/apk/*.apk` 副本，保留缓存目录和已安装文件。不使用 `--force-non-repository` 绕过安装检查；镜像不依赖启动时重装包，不作为磁盘无持久化的 APK 恢复系统使用。来源 APK 已在镜像外连同哈希锁归档，清理缓存不能删除或替代这些重放证据。
- 在大包 COPY/安装之前设置环境变量、工作目录与默认命令，仅最终 USER 留在安装之后，以减少专用 vfs/classic builder 安装后中间步骤的大 rootfs 复制；安装仍以构建身份执行，最终非 root 身份与签名校验不变。缓存副本清理单独未解除 BUILD-R03 的空间失败；R04/R05 在批准总预算内调整临时空间分配后才完成构建，不能把结果归因于单项清理。
- `.dockerignore` 仅允许 Dockerfile、本地 APK 及其哈希锁进入构建上下文。

## 固定基底

ENV-R15 的 `seed-selected` 记录给出以下基底事实；执行镜像的实际构建结果另见 BUILD-R05 记录：

| 项目 | 值 |
| --- | --- |
| 原镜像引用 | `alpine@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412` |
| 来源存储镜像 ID | `sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412` |
| 已验证 vfs 导入镜像 ID | `sha256:706db57fb2063f39f69632c5b5c9c439633fda35110e65587c5d85553fd1cc38` |
| 基底发行版 | Alpine 3.22.2 |

Dockerfile 的默认 `BASE_IMAGE` 固定来源 RepoDigest，不使用浮动标签。为了兼容已导入专用 vfs/classic 存储的种子，受信环境管理器可以通过 `--build-arg BASE_IMAGE=<完整导入镜像ID>` 传入其已验证的本地映像；该入口不是任意更换基底的授权。跨存储格式的 ID 不同不能靠字符串相等判断内容相同，必须沿用预检的 RootFS、Config、Architecture、Os 一致性验证，并记录来源 RepoDigest 到导入 ID 的映射。构建前管理器拒绝标签、截断 ID 和未经内容验证的映像。

**离线构建前置检查**：在专用运行时执行所选不可变引用的 `image inspect`，确认本地可解析且内容符合记录。`image load` 后存在导入 ID，并不自动证明 RepoDigest 仍可解析；此时只允许使用上述已验证映射的完整导入 ID，不能改用浮动标签或隐式联网拉取。无法验证映射时停止，不声称离线构建已可用。

## 准备签名包与锁

以下是需按实际执行结果保留证据的流程，不是执行日志；已有 BUILD-R01 包输入应优先归档重放，不重复联网下载。仅在获授权的专用临时环境中进行；共享 daemon 只能读取既有种子，禁止在其上启动准备/构建/测试容器。网络准备与之后的断网构建分开进行，不能为下载包撤销运行负载的网络隔离。

1. 使用经内容校验的固定 Alpine 基底，检查实际 `/etc/alpine-release`、`apk --version`、`apk --print-arch`、`/etc/apk/repositories` 及 `/etc/apk/keys`。保留所有版本、仓库配置、公钥 SHA256 与基底导出文件 SHA256；架构必须与准备构建及运行环境一致。
2. 仅使用 Alpine 官方 HTTPS 的 `v3.22/main` 仓库索引。归档实际取得的签名索引、其 SHA256、下载时刻及来源；不使用 edge、镜像不明的仓库、额外密钥、`--allow-untrusted` 或 `--force-broken-world`。
3. 从该索引确定一个实际存在的 g++ 完整版本，人工记录为 `GXX_VERSION`；本材料不虚构版本号。用 Alpine 自带依赖解析器获取它的全部递归依赖，不自行实现 APK/依赖解析。仅将输出目录绑定为可写，不挂宿主控制目录或 socket。受信准备容器内命令形态如下，变量必须由本次准备记录提供：

   ```sh
   apk --repositories-file /preparation/repositories --no-cache fetch --recursive --output /packages "g++=${GXX_VERSION:?record an actual pinned version}"
   apk --keys-dir /etc/apk/keys verify /packages/*.apk
   cd /packages
   LC_ALL=C sha256sum ./*.apk > SHA256SUMS
   ```

   `/preparation/repositories` 只能包含审核后的官方固定分支地址，且只读；`/packages` 必须是本轮新建空目录。每条命令单独检查退出码；签名失败立即停止，不生成可用锁。保存 fetch/verify 的完整输出和所选包文件名/版本，不能仅记录 g++ 版本。
4. 将经过官方密钥验证的全部 APK 和 `SHA256SUMS` 放入本目录的 `packages/`。哈希锁使用上述命令的原始 UTF-8/ASCII 与 LF 输出，不经 PowerShell 默认编码或换行转换。保留包字节，不以未来重新 fetch 的结果替代本轮锁。首次仓库解析不是可重放依赖集；已归档 APK 加锁才是后续离线重建输入。
5. 保存构建材料、完整包集合及官方公钥/索引证据的清单；证据保存到独立验收目录，不放进 `packages/`，也不要自动把二进制包加入 Git。

`apk verify` 和离线 `apk add` 必须在真实固定基底中成功，不能以文件名或自制签名解析器代替官方工具。Dockerfile 的离线安装还会再次执行 APK 的默认签名校验；任何不兼容参数或缺失公钥均按失败处理。

## 专用运行时离线构建

在已经通过良性预检且具有外层内存、CPU、进程数、时间、磁盘上限的临时 runtime 中执行。Docker 客户端每条命令都显式指定本轮专用 socket 和专用配置目录，不能依赖默认 Docker context。以下变量仅由该环境管理器传入，路径不能指向共享 daemon：

```sh
docker --config "${PRIVATE_DOCKER_CONFIG:?}" --host "${PRIVATE_DOCKER_HOST:?}" image inspect "${VERIFIED_BASE_IMAGE:?}"
DOCKER_BUILDKIT=0 docker --config "${PRIVATE_DOCKER_CONFIG:?}" --host "${PRIVATE_DOCKER_HOST:?}" build --pull=false --network=none --no-cache --build-arg "BASE_IMAGE=${VERIFIED_BASE_IMAGE:?}" --tag wjc-task10-executor:local runner/linux-executor
```

`VERIFIED_BASE_IMAGE` 必须来自受信环境管理器的上述验证结果，而非用户任务数据。此处使用专用 daemon 的既有 classic builder，避免为准备工作安装构建插件；BUILD-R05 已实测当前版本支持完整本地 ID，变更运行时后须重新验证。外层网络 namespace 也必须禁止外部网络，因为 `--network=none` 只约束构建步骤，不等于限制构建器自身解析或拉取基底。若专用 runtime 不具备可用构建器，记录阻塞，不回退到共享 daemon。归档构建器/runtime 版本、Dockerfile/context 哈希、构建 stdout/stderr、退出码、耗时、最终镜像完整 ID、架构和 RootFS。运行测试必须使用实际完整 ID，而不是可变的 `:local` 标签。

构建后用相同受限运行配置保存 `apk info -v` 的排序结果及 `g++ --version`，与 APK 清单共同记录所有实际包版本。随后用同一归档输入重建，比较包集合、配置和编译行为；未经实测不得声称镜像字节级确定性。

## 构建记录与验收

### 已保留的构建失败

2026-10-03，`docs/acceptance/TASK-10/BUILD-R01/steps.jsonl` 的 `offline-image-build` 记录真实构建退出 99。固定导入 ID 已被 classic builder 接受，包哈希比较通过，但原命令 `apk --no-network --no-cache add ./*.apk` 仍尝试基底默认 main/community 索引并产生网络错误，随后报告本地非仓库包在重启后会丢失，要求启用缓存。该轮没有成功镜像或编译正例。

R01 后修订命令先建立 `/var/cache/apk`，再执行 `apk --repositories-file /dev/null --no-network --cache-dir /var/cache/apk add ./*.apk`；只改变仓库来源和缓存配置，保留官方签名校验、完整包哈希检查及网络隔离。

2026-10-03，`docs/acceptance/TASK-10/BUILD-R02/steps.jsonl` 的 `offline-image-build` 记录退出 1：包哈希锁验证及上述离线安装成功，17 个包完成安装/升级，输出 `OK: 234 MiB in 31 packages` 和 `g++ (Alpine 14.2.0) 14.2.0`；最后 `USER 65532:65532` 步骤报告 `no space left on device`。该轮解除 R01 安装阻塞，但专用 vfs/classic builder 在 1 GiB tmpfs 内仍未生成完整最终镜像，不能算构建通过。

R02 后仅在签名安装和 g++ 检查成功后删除 `/var/cache/apk/*.apk` 缓存副本，保留缓存目录，减少后续 vfs rootfs 复制量。原始签名 APK 已在 `target/task10-runtime/BUILD-R01/packages.tar` 归档并配有哈希锁；后续必须继续用此归档重放，不重新解析仓库、不降低签名要求、不扩大批准总预算。

2026-10-03，BUILD-R03 使用清理缓存后的 Dockerfile 重放，安装成功，但最终 USER 步骤仍报 `no space left on device`，构建退出 1。其 `offline-image-build` 记录临时盘已用 989786112 字节；该轮证明缓存清理并未单独解决 1 GiB tmpfs 的构建峰值问题。

2026-10-03，BUILD-R04 在批准总预算内重新分配内存/临时空间，离线构建成功，但编译正例失败：运行时新挂载的 `/scratch` tmpfs 未显式指定 UID/GID，继承 0700 模式而属于 root，覆盖了镜像层中的非 root 目录所有权，链接器报 `Permission denied`。镜像内 chown 不能证明新 tmpfs 挂载仍可写；必须在挂载参数中明确身份，不放宽为公共可写目录。R01～R04 证据分别保留在对应 `docs/acceptance/TASK-10/BUILD-R*/steps.jsonl`。

### BUILD-R05 实际通过范围

2026-10-03，BUILD-R05 全程断网重放 BUILD-R01 的 17 个已归档 APK，包归档 SHA256 为 `cff15d603c37d3dbf9fccba9ba35fb816cffc4b6d62a9cfa46a1ffd0b16d47f3`；离线 build 退出 0。固定可信源码以 `g++ /input.cpp -std=c++17 -O2 -o /scratch/hello && /scratch/hello` 编译并运行，输出 `TASK10_CXX17_OK`。scratch 明确设置 `uid=65532,gid=65532,mode=0700`，同时保留只读根、非 root、cap-drop ALL、no-new-privileges、断网及负载资源限额。

| 产物 | BUILD-R05 实际值 |
| --- | --- |
| Dockerfile SHA256 | `1d61b862477eef5c2476a7ba26c5678054dc767b8ee462d184d1575347e10dda` |
| 镜像完整 ID | `sha256:360a2877270d6ce26675f4867588dcbffba5a743947557c169713967b2a2f33f` |
| 导出归档 | `target/task10-runtime/BUILD-R05/executor-image.tar` |
| 导出大小 | 332817920 字节 |
| 导出 SHA256 | `9d666ec70e1dd5b115a89ea1035857a66cdacec55cd6120ed52f1e7d7bc6b832` |

外层 MemoryMax 为 1504 MiB、swap 为 0，tmpfs 上限 1408 MiB 且已包含在该内存预算内；包归档上限 128 MiB、镜像导出上限 384 MiB。以外层内存加两个归档上限保守合计为 2016 MiB，小于已批准的 2 GiB；CPU 2 核、512 PID、570 秒运行加 30 秒停止预算不变。未通过扩张总预算掩盖空间失败。

完整命令、镜像检查、哈希核验及输出见 `docs/acceptance/TASK-10/BUILD-R05/`，环境过程见 `docs/acceptance/TASK-10/environment.md`。镜像未导入共享 daemon，未因此提交 Git。**本轮仅在同一受限容器中执行可信固定 hello 的编译和运行，不证明编译/执行独立阶段、全部不可信角色、控制 FD/信号/进程访问或产物交接安全。** 归档输入重放成功也不等于镜像 ID 或导出字节逐位可复现，未作此声明。

### 剩余门槛

- 正例：真实编译最小 C++ 程序，生成可执行产物并实际运行；同时测试 `/scratch` 临时文件创建。编译和运行分别建独立执行单元，只读绑定当前阶段必要输入，不向任一单元提供 Docker socket。只读/noexec 源码挂载与执行产物挂载应区分；不要以在 noexec scratch 执行失败误判编译器不可用，也不要直接放宽所有阶段的挂载权限。
- 产物交接：仅从已退出的编译阶段导出固定预期文件，由可信侧验证普通文件、边界内路径和大小后交给执行阶段；不得把任意负载生成的目录树直接作为宿主路径或新控制文件使用。完整安全交接由 TASK-10 主实现负责。
- 运行配置：显式非 root、cap-drop ALL、no-new-privileges、默认 seccomp、只读根文件系统、network none、独立 PID namespace，以及批准额度内的 memory/memory-swap/CPU/pids/scratch 上限。外层总预算仍须独立存在，镜像 USER 不替代这些约束。
- 反例：包被改写、锁缺包/多包、未签名包、公钥不匹配、缺依赖、基底不可本地解析均须失败。锁与 APK 同时被替换不能由 SHA256 单独阻止，仍须保留并验证可信归档来源及官方签名。
- 身份与控制面：真实检查 UID/GID、caps、seccomp、PID/FD/信号边界和控制文件不可见；对编译器及全部四角色执行要求的负例。镜像本身不提供生产能力探针，也不签发 `productionSafe`/`controlPlaneIsolated`。
- 每项保留实际命令、资源限制、退出码、数量及清理结果。仅清理本次专用 runtime 的已知新资源，确认没有遗留容器、daemon 或临时挂载。不以本 README 或已有 ENV-R15 良性结果代替这些验收。
