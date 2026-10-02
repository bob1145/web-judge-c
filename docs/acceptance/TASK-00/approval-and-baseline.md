# TASK-00 批准、基线与延期边界

## 1. 批准依据

- 批准人：本任务用户；日期：2026-10-02，Asia/Shanghai；会话原话：“批准”。
- 对应上一条明确请求：DEC建议及延期参数、测试环境/目标平台、四份输入归属、TASK-00～29验收后独立本地提交；不是自动续轮推定批准。
- 本地提交仅限当前仓库各任务实现、测试、必要文档和task/work；不包含push、amend、reset、建分支、无关改动、删除、全局配置修改或生产数据操作。
- 首期目标为单实例Linux隔离provider；direct仅可信本地。Windows/native/remote的生产禁用及负例验证仍待TASK-26，批准范围不等于当前代码已经禁用或平台已验收。
- 允许使用现有Ubuntu WSL进行开发基线；生产攻击、生产API、破坏性迁移不在批准范围。后续真实provider实验必须先落实独立执行域和外层预算。

## 2. 已批准语义及延期事项

| 决策 | 已批准内容 | 未定细项与受阻任务 |
| --- | --- | --- |
| DEC-01 | 默认10MiB；单次执行stdout+stderr合计；生成器同限；等于限额合法，超额必须OLE | 编译诊断独立额度延期至TASK-13；TASK-01只修测试与解释器，不改额度制造通过 |
| DEC-02 | CREATED TTL15分钟；重启非终态STALE并记录RESTART_INTERRUPTED；不续跑 | 清理频率/预算在TASK-18/19实施前冻结 |
| DEC-03 | 整数精确；有限小数十进制绝对误差；NaN/Inf等按大小写敏感文本精确；API缺省0、页面1e-9；UTF-8严格解码、ASCII空白 | token字节/指数上界及非法输入错误映射延期至TASK-15 |
| DEC-04 | 管理员STRICT，可信来源/UA变化重新认证；普通用户默认AUDIT，可配置STRICT | 可信代理列表、UA规范化与移动网络矩阵延期至TASK-03/07 |
| DEC-05 | 首期拒绝伪cert-only；只允许真实验收HTTPS Bearer/HMAC；真实mTLS非首期必交 | Worker端点及服务端协议证据延期至TASK-08/26 |
| DEC-06 | 删除URL凭证/弱默认码；回环默认、Secure/SameSite/CSRF；Header客户端按设计受控兼容 | 部署代理CIDR、Origin和历史日志/会话迁移延期至TASK-03/04/24/28 |
| DEC-07 | 首期Linux隔离目标；其他未验收provider生产禁用；未定容量按对应任务实施前审批 | TASK-03限流；06/24连接/订阅/撤销；09/11/12事件/轮询；10/14执行域/回收；23缓存；25/27容量/测量容差 |
| DEC-08 | 已排队/运行start200；终态start409；终态cancel accepted=false及原状态；无权/不存在404；首次CREATED取消accepted=true | 精确字段按既有DTO兼容落实到TASK-18 |

100ms轮询、1s退出flush、2s清理、256KiB/256条批次、128KiB行上限仍为对应任务的候选。总体批准接受“未定参数延期、实施前冻结”，不是批准任意补造数值。延期不阻断TASK-01的10MiB基线修复。

## 3. 环境与实际全量基线

- 受测提交f31d039183c690f36d7a07e25da911ce1ace55db，git archive独立临时副本，没有引用历史报告作为本次测试。
- Ubuntu24.04.1 LTS、OpenJDK21.0.12.1、Maven3.8.7、Python3.12.3（python3）、g++13.3.0；python命令不存在。Windows JAVA_HOME失败保留于work R01。
- 构建偏差：使用已安装Maven3.8.7离线缓存，非Wrapper配置3.9.10；未安装/升级依赖，后续切换版本应重新验证。
- R03、R04均实际执行 mvn -o -B -Dstyle.color=never test，33个suite、193项、182通过/2失败/7错误/2跳过，退出1，BUILD FAILURE。
- 断言失败来自ApplicationYamlExecutionProfileTest和JudgeBaselineTest；7个IOException来自TaskRunnerArtifactContractTest；Linux/Windows真实探针各跳过1项。基线红可按TASK-00记录，不能作为TASK-01或发布通过证据。
- 根目录四个生成文件为R03首次证据，诊断只保留类型，不足以独立证明错误原因。独立复核后补强脚本，R04新目录保留脱敏断言、错误/跳过原因和单元配置，未覆盖R03。
- 当前证据以R04/baseline-summary.json为准；未运行额外真实容器/Worker/TLS/浏览器验收，不关闭任何缺陷。

## 4. 外层约束与复跑

可信仓库基线使用临时systemd服务：MemoryMax4GiB、swap0、CPUQuota200%、TasksMax512、RuntimeMax600s、内部timeout540s及10s终止宽限；/tmp为2GiB tmpfs。进程独立网络命名空间且无IP路由，系统/家目录/源码只读、NoNewPrivileges；仓库仅证据目录可写，另有临时可写/tmp。宿主Docker socket未隔绝，仅用于受信基线，不能保证经宿主服务的网络/写入隔离。

脚本检查cgroup内存/交换/进程/CPU限制、NoNewPrivs、独立网络空间与无路由、源码只读、tmpfs；systemctl show保存外层约束。不是生产不可信代码隔离证明：只读家目录不隐藏秘密。systemd的Memory peak不作为JVM实际峰值证据，不据此验收内存性能。

R04实际命令如下；复跑必须使用新的unit名及不存在的证据子目录，不覆盖旧证据：

~~~powershell
wsl -d Ubuntu -- systemd-run --wait --pipe --collect --unit=wjc-task00-baseline-r04 -p MemoryMax=4G -p MemorySwapMax=0 -p CPUQuota=200% -p TasksMax=512 -p RuntimeMaxSec=600 -p PrivateNetwork=yes -p ProtectSystem=strict -p ProtectHome=read-only -p NoNewPrivileges=yes -p TemporaryFileSystem=/tmp:rw,size=2G -p ReadOnlyPaths=/mnt/c/Users/29901/Documents/dp/web-judge-c -p ReadWritePaths=/mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-00 /bin/bash /mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-00/run-baseline.sh /mnt/c/Users/29901/Documents/dp/web-judge-c /mnt/c/Users/29901/Documents/dp/web-judge-c/docs/acceptance/TASK-00/R04
~~~

## 5. 审核与未完成范围

- baseline_inspection核对20RQ、29AC、31缺陷映射、30任务无环及设计章节；附录两处凭据已脱敏，保留事实/代码位置。
- baseline_review复核脚本及产物，指出诊断裁剪、隔离证明、HEAD竞争、旧证据混入和零测试判定问题；已补强并复跑。
- R04最终复核确认上述问题已修复，Linux探针跳过因cpp-judge-runner:latest镜像缺失，Windows探针跳过因当前runtime为Linux。Docker服务可达，不把进程网络限制描述为完全隔绝宿主服务。
- TASK-00只交付执行基线。支持矩阵、迁移回滚、真实provider、全绿均待后续任务，当前不可发布。
