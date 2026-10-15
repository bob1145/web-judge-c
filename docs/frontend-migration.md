# React 前端迁移记录（2026-10-10）

## 范围与兼容性

- 将两个大型静态模板的业务逻辑迁移到 `frontend/` 中的 React + TypeScript 工程，保留 `/` 和 `/auth` 原路由。
- 继续复用现有登录、判题、启动、取消、查询、测试点详情与下载接口。新增的 `GET /auth/bootstrap` 只提供前端启动需要的 CSRF 与登录方式；不改变权限检查、不返回访问码、不放宽跨域。
- 前端持有 CSRF 仅在内存中；身份依旧使用 HttpOnly Cookie。登录后重新获取已轮换的 CSRF。所有修改请求均携带服务端指定的 CSRF Header。
- 原始毫秒、内存字节、精度字符串等请求语义保持不变；内存 UI 单位为 MB，测试点结果内存为 KB。
- 原 localStorage 草稿首次使用时迁入 `cppJudge.reactDraft.v1`。原任务 ID 可恢复，但本地进度缓存不再用于显示真实状态。
- 用户在任务运行中切换页面不会被推送强制切回结果页。卸载或更换任务会中止轮询和订阅；网络恢复后查询原任务。
- 保留部署配置、现有后端任务安全逻辑和用户未提交修改，没有提交 Git 或创建分支。

## 设计

BUSINESS：让算法开发者完成代码准备、真实验证、失败复盘。
DIRECTION：浅色、安静、精致的开发工作台，而不是营销展示页。
PALETTE：参考 ggsci NPG 期刊图表配色的青绿 `#00A087`、珊瑚 `#E64B35`、天青 `#4DBBD5`。实际主按钮使用较深的 `#287A68`，搭配暖白 `#F7F8F4` 与浅绿表面；辅助文本统一使用对比度经过验证的颜色。
TYPE：系统无衬线与中文系统字体回退；源代码使用本地等宽字体回退，不请求远程字体。
LAYOUT：桌面侧栏、双栏代码/参数；平板收窄导航；手机切换为底部导航。
DENSITY：代码、结果保持紧凑，登录与引导适度留白。
GLASS：不叠加玻璃材质，不用模糊掩盖低对比文字。
SIGNATURE：原创代码校验 Logo、登录页 SVG 插画、克制的入场与真实进度过渡。工作台首屏直接显示编辑器和参数，不设宣传横幅。
NO-GO：深色大面积底色、蓝紫发光渐变、虚构用户/指标/历史记录、滚动劫持、强制循环动效。

配色参考：https://nanx.me/ggsci/articles/ggsci.html

## 本地图片与 Logo

- `frontend/public/images/logo.svg`：原创可缩放代码/校验品牌标识，同时作为 favicon。
- `frontend/public/images/judge-illustration.svg`：项目内可编辑的立体代码窗口插画，无外部图片依赖。
- 示例卡片使用 CSS 与文字构成的示意图，不伪造运行结果。

## 接口对照

| 用途 | 接口 | 关键约束 |
| --- | --- | --- |
| 启动配置 | GET /auth/bootstrap | no-store；CSRF Token/Header + accountAuthRequired |
| 会话 | GET /auth/session | 根据 valid 判断认证，不存 sessionId |
| 登录/退出 | POST /auth/verify、POST /auth/logout | Cookie + CSRF；账号模式不裁剪密码空格 |
| 创建 | POST /judge | Accept: application/json，保留结构化策略及 judgeId |
| 启动/取消 | POST /judge/start/{id}、POST /judge/cancel/{id} | 只操作原 ID；取消后查询最终状态 |
| 进度 | GET /judge/status/{id} | 顺序轮询，有限退避；拒绝旧 revision 与终态回退 |
| 实时消息 | /ws/websocket → /topic/progress/{id} | STOMP CONNECT 携带 CSRF，失败回退 HTTP |
| 详情 | GET /details/{id}/{caseNumber} | 尊重服务端截断标记，按行比对，限量显示 |
| 下载 | GET /download/{id}/{caseNumber|failed|all} | Cookie 鉴权；摘要模式不假设全部数据归档存在 |

## 构建与测试

- 前端：`npm --prefix frontend ci`、`npm --prefix frontend test`、`npm --prefix frontend run build`。
- 浏览器：`npm --prefix frontend run test:e2e`。覆盖 1440、1024、768、390、320px；账号登录、草稿恢复、快捷导航、判题详情、下载路径，以及主要页面 WCAG A/AA 自动检查。
- 后端：`mvn -Dtest=FrontendBootstrapHttpTest,FrontendTemplateContractTest,JudgeControllerPolicyTest,AuthenticationSafeDefaultsTest test`。
- 本机 JDK 21 的 Mockito 动态附加失败，需要给测试 JVM 显式添加本地 `mockito-core-5.17.0.jar` 的 `-javaagent`；不修改项目依赖来绕过该环境问题。
- 原来依赖 HTML 字符串的业务断言迁移为可执行的 TypeScript 状态/接口/异步测试；Java 模板测试只检查入口、打包资产、兼容 meta 和对应回归覆盖。

## 初版验收记录

- 2026-10-10：55 项前端单元测试、11 项 Playwright 浏览器测试、31 项后端回归全部通过；TypeScript 检查、Vite 生产构建及 Maven 打包成功。
- 最终代码高亮使用与页面协调的低饱和配色；加载 C++ 示例后的代码工作区额外通过 WCAG A/AA 自动检查。
- 实测 GSAP 标题点击切换、插画指针跟随及系统减少动态效果切换，未捕获浏览器 JavaScript 异常。
- CodeMirror 作为懒加载模块，压缩后约 527 kB、gzip 约 172 kB。Vite 的 500 kB 提示仍存在，不影响构建；未为隐藏提示调大阈值。
- 桌面预览：[工作台](frontend-preview/workspace-desktop.png)、[示例模板](frontend-preview/examples-desktop.png)；移动预览：[390px 工作台](frontend-preview/workspace-mobile.png)。截图使用明确的验收会话夹具与本地示例代码，不代表真实判题成功。
- 最终构建已同步到 Spring Boot 资源并打包。部署时必须整体更新并重启后端，使新入口、静态资源与 `/auth/bootstrap` 同时生效。

## 2026-10-10 · 文案与首屏精简

- 根据用户反馈移除工作台宣传横幅、侧栏推广卡片、流程口号、页脚标语及无任务时的占位卡片。编辑器和对拍设置直接进入首屏。
- 登录、运行结果、示例模板与使用指南统一使用功能名称；保留参数说明、操作反馈、错误与安全提示。代码标签的必要说明改为悬停提示。
- 删除无用文字动画及关联样式，保留浅色配色、Logo、登录插画、页面入场和真实进度动效。判题与认证逻辑不变。
- 55 项前端单元测试与 11 项浏览器测试通过，生产构建成功；新增首屏编辑器位置、宣传模块移除的断言，并更新桌面和手机预览截图。

## 2026-10-10 · 结果排序

- 测试点、耗时、内存表头支持升降序，箭头表示当前方向；原生按钮支持键盘操作，活动列通过 `aria-sort` 提供排序状态。
- 耗时与内存按接口原始数值排序，值相同时按编号升序；不修改原结果数组。先筛选并排序完整返回集，再分页，切换排序回到第一页；实时结果更新保留当前排序。
- 大批量摘要只排序后端已返回的失败与慢速样本，不宣称是全部测试点的全局排行，不新增或修改后端接口。
- 修复移动端表格隐藏表头的定位范围，横向滚动仅限表格内部。
- 本次 63 项前端单元测试、13 项浏览器测试及生产构建通过，覆盖双向排序、同值次序、分页、筛选、搜索、实时更新、样本边界与键盘操作。

## 验证边界

不将接口夹具结果表述为真实判题。生产页面没有 mock。自动化可访问性检测不能替代屏幕阅读器与真实设备测试。C++ 执行沿用已有后端模式；trusted-local 仍只适用于可信代码，本次前端改造不构成生产沙箱验收。


## 本次真实联调结果

- 在本机 1235 端口启动独立 Spring Boot 进程，临时数据位于 `target/frontend-runtime/`；未停止既有 1234 进程。
- 使用真实身份验证、Cookie/CSRF 轮换、创建/启动任务、C++ 编译、状态查询与 STOMP 推送，浏览器无 JavaScript 异常。
- 三组 A+B 示例在当前 Windows 执行环境进入 `SYSTEM_ERROR`。日志显示启动生成器时报 `CreateProcess error=5`（拒绝访问）。目录中有 `generator.exe`，现有后端传入的启动路径为无扩展名的 `generator`；本次未擅自修改后端执行器或系统权限。
- 前端如实显示系统错误、已完成/失败数量及服务端停止原因，没有将该任务标记为 AC。已验证真实 ZIP 归档下载以及刷新后恢复代码、参数和原任务查询。
- 成功/失败测试点比较、高并发摘要、队列拒绝等其他分支使用符合后端契约的浏览器测试夹具；不将其混同为本机真实 C++ 成功运行。
- 单点/沙箱执行成功验收仍需先解决上述 Windows 执行问题，或在后端已支持的运行环境验证。这不是前端对安全沙箱或判题正确性的额外保证。
