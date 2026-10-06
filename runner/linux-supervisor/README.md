# TASK-10 可信监督器实验镜像

此镜像与 `linux-executor` 分离，仅包含官方 Alpine 签名 APK 提供的 Python、Docker CLI 及其递归依赖，以及五个产品模块的只读快照。无 pip、编译器或 Docker daemon。APK 与源文件均在安装/使用前校验完整 SHA256 清单；APK 安装仍由 Alpine 原生签名验证，不使用 `--allow-untrusted`。

入口是 `linux_supervisor.py`，无参数返回 30；任务路径、工作根、阶段配置及配置写入者 UID 必须由调用者显式提供。`--capabilities` 仅返回静态协议能力，不是生产安全探针。

`USER root` 仅用于当前专用可信域镜像实验，不代表部署身份已冻结。实际部署必须独立冻结 supervisor 身份、配置写入者身份、工作负载身份、socket/CLI 配置、资源预算与挂载；必须以只读根文件系统运行，文件 mode 不能限制可信 root。不得将镜像构建成功等同于 Java 接入、隔离验收或可发布。

专用验收驱动位于 `docs/acceptance/TASK-10/runtime_preflight.py`：

- `outer-supervisor-image-build <SUPERVISOR-BUILD-Rnn目录>`：仅官方 Alpine v3.22 main/community 获取 `python3 docker-cli` 及递归依赖；停止联网专用 daemon 后切换新断网 namespace 构建。
- `outer-supervisor-image-replay <SUPERVISOR-BUILD-Rnn目录>`：从 `SUPERVISOR-BUILD-R01` 证据与精确包归档离线重放，重新快照当前产品源码。

两种模式仅使用专用 daemon；共享 daemon 仅用于既有 Alpine 基底的 inspect/info/save。镜像标签为 `wjc-task10-supervisor:local`，归档为 `target/task10-runtime/SUPERVISOR-BUILD-Rnn/supervisor-image.tar`。归档和 APK 版本、SHA256 在每轮证据中记录，不预猜版本。2GiB 总临时存储预算由 1408MiB tmpfs、128MiB 包归档、384MiB 镜像归档组成；外层内存 1504MiB、swap 0。
