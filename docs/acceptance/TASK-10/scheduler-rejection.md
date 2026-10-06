# TASK-10 全量回归中的调度拒绝诊断

## 事实与范围

R05与R07中JudgeBaselineTest.runtimeErrorIsRecordedAsSingleCaseRe期望RE而得到SYSTEM_ERROR；R06未复现不构成修复。R07有界诊断明确message为`Judge executor rejected the task`，progress=100，results=null。当前没有修改生产调度器，也没有修改基线fixture、期望或等待条件。

## 只读代码定位

- AsyncConfig.judgeRequestExecutor使用core=max=maxConcurrentTasks、queueCapacity=0及AbortPolicy；默认并发1。
- JudgeScheduler.runTask的finally仍在工作线程内，先移除runningTasks槽位，再同步startNextQueuedTask；后者再次executor.execute。逻辑槽释放时旧工作线程尚未退出，零容量线程池没有可用线程，后继任务提交会被拒绝。execute捕获后持久SYSTEM_ERROR，消息与R07相同，并继续尝试队列后继。
- JudgeService先发布100%终态再返回scheduler；基线测试只等待100%，可能使下一测试恰好排队，由此间歇触发确定性交接缺陷。没有发现基线测试主动shutdown、DirtiesContext或并行配置证据。
- 现有JudgeSchedulerTest逻辑并发1却使用executor(2)，辅助工厂为带内部队列的Executors.newFixedThreadPool，未覆盖生产零容量执行器。

这些代码事实强烈支持交接缺陷，但不能把代码推导当作已完成的运行复现，也不能仅据消息排除每次拒绝的所有其他原因。独立只读代理复核，主代理核对生产执行器及交接代码；不是独立安全放行。

## 确定性诊断计划与修复边界

诊断使用真实AsyncConfig与JudgeScheduler，有限latch阻塞第一任务，明确第二任务已入队后才释放第一任务。仅在独立私有快照注入诊断fixture，不改正式测试发现规则。诊断若观察准确SYSTEM_ERROR及第二任务体未执行，表示缺陷复现成功，不是产品验收通过。运行结果另行记录，未执行前不填成功。

这是既有调度生命周期缺陷，关联TASK-18后续统一调度槽和工作线程交接；不属于TASK-10身份隔离修复。修复需要同时覆盖交接、新请求竞态、并发上限、队列公平性和取消，不能通过扩大线程池、CallerRunsPolicy或让基线等待空闲遮掩。TASK-10继续保留回归红状态，不宣称全绿或可发布。

## SCHEDULER-PROBE-R01 实际运行

2026-10-03已在批准的4GiB/swap0/2CPU/512pids/600秒/断网/源码只读/私有2GiB tmpfs中运行独立诊断。真实生产AsyncConfig与JudgeScheduler、有限latch确保排队，观察到第二任务SYSTEM_ERROR、准确消息Judge executor rejected the task且任务体执行0次。诊断1/1、零失败/错误/跳过、Maven/unit退出0、服务9.220秒，**成功含义是缺陷复现，不是产品验收通过**。

夹具仅复制进私有快照，未加入正常回归发现目录、未改变现有测试。160份源码/runner/.mvn/POM哈希全部匹配，另独立核对诊断Java及shell哈希；证据SCHEDULER-PROBE-R01/diagnostic-summary.json明确productAcceptance=false及r07CausalityProven=false。此次确定性运行确认交接缺陷确实存在，但没有R07完整执行轨迹，不能声称已排除R07的全部其他因果。生产调度器未修改，仍需修复及正式正向回归。
