# TASK-01 验证证据

- 前置提交4c86d830edc9a927e32377291bac20641ac1e557；原红基线见TASK-00/R04。
- 复用TASK-00/run-baseline.sh，增加显式worktree快照模式和Maven参数；默认仍为固定提交快照。每次使用全新的输出目录，记录受测父SHA、快照模式、源码SHA-256清单和准确命令，不把HEAD错误标作未提交代码的树身份。
- 临时systemd限制与TASK-00一致；证据可写目录改为TASK-01。只运行受信仓库测试，不声称Docker socket隔绝或生产沙箱验收。
- 定向参数为 -Dtest=ApplicationYamlExecutionProfileTest,JudgeBaselineTest,TaskRunnerArtifactContractTest,PythonInterpreterTest；完整回归不带-Dtest。实际类名、次数和结果以每次生成JSON为准，不预填通过。
- 显式路径验证另传 -Djudge.test.python=/usr/bin/python3；默认真实环境只有python3而无python，发现失败应明确测试失败而不是skip。
- PowerShell执行策略不作修改；Maven/Python在已有Ubuntu内运行。源码实现与测试的未提交修改纳入只读副本，最终提交前核验源码hash未变化；任务日志/验收记录的后续更新单独审核。

## 实际结果

| 运行 | 范围 | 通过/失败/错误/跳过 | Maven退出 |
| --- | --- | --- | --- |
| R01 | 四类定向，共28项 | 28/0/0/0 | 0 |
| R02 | 全量，共207项 | 205/0/0/2 | 0 |
| R03 | 显式/usr/bin/python3的runner和发现，共19项 | 19/0/0/0 | 0 |
| R04 | 显式不存在路径的真实发现负例，共1项 | 0/0/1/0 | 1（预期失败） |

R04错误消息明确是/nonexistent/task01-python不存在且explicit configuration never falls back，证明确认配置错误时不会悄悄使用可用python3，也不会skip。11个注入单测验证选择/异常矩阵，1个真实探测及7个真实runner用例验证实际Python；不声称已实测Windows或“仅python”操作系统安装。

全量2项skip是既有Linux/Windows真实provider探针（镜像缺失/runtime非Windows），未改禁用条件，不能据BUILD SUCCESS宣称平台通过或关闭N5。R01～R04的131源码hash、150完整输入hash和harness hash均在日志更新前核对一致；提交前再核对源码，仅允许验收元数据变化。

## 复跑命令

以下为R02的完整实际命令；重复执行须更换unit及不存在的证据子目录。定向/显式/负例在末尾追加baseline-summary.json中记录的-D参数，逗号或#参数在PowerShell中必须加双引号。

~~~powershell
wsl -d Ubuntu -- systemd-run --wait --pipe --collect --unit=wjc-task01-full-r02 -p MemoryMax=4G -p MemorySwapMax=0 -p CPUQuota=200% -p TasksMax=512 -p RuntimeMaxSec=600 -p PrivateNetwork=yes -p ProtectSystem=strict -p ProtectHome=read-only -p NoNewPrivileges=yes -p TemporaryFileSystem=/tmp:rw,size=2G -p ReadOnlyPaths=/mnt/c/Users/29901/Documents/dp/web-judge-c -p ReadWritePaths=/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-01 /bin/bash /mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-00/run-baseline.sh /mnt/c/Users/29901/Documents/dp/web-judge-c /mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-01/R02 worktree
~~~
