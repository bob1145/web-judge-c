# 对拍 · React 前端

基于 React 19、TypeScript、Vite、GSAP 与 CodeMirror 6。页面包括对拍工作台、运行结果、示例模板、使用指南以及账号/访问码登录。不使用生产 mock，不依赖外部 CDN 字体、图标或图片。

## 开发

需要 Node.js 22.12+（本次使用 Node.js 24）和 npm。先按根目录文档配置并启动 Spring Boot，再运行：

```powershell
cd "frontend"
npm ci
npm run dev
```

默认开发地址为 http://127.0.0.1:5173，API 代理目标为 http://127.0.0.1:1234。使用同一个主机名完成登录与访问；访问码/账号沿用后端配置。

需要其他后端地址时，在本目录未跟踪的 ".env.local" 中配置 `JUDGE_BACKEND_URL=http://127.0.0.1:1235`；这只是 Vite 开发代理配置，不是浏览器可见密钥。不要写入登录密码或会话令牌。

## 生产构建

```powershell
npm ci
npm run build
cd ".."
mvn package
```

构建先进行 TypeScript 严格检查，再生成静态文件，并同步到：

- `src/main/resources/static/js/app/`：带哈希的样式与懒加载编辑器、主应用、图像。
- `src/main/resources/templates/index.html` 和 `auth.html`：两个相同的 React 挂载壳，保留 Thymeleaf CSRF meta 以兼容现有客户端测试。

同步不删除历史哈希资源，避免已打开页面的动态导入中断。两个页面壳和静态发布文件应作为同一批发布。修改前端后需要重新构建；修改控制器后需要重新启动后端。普通 Maven 构建不自动调用 npm，可直接使用仓库中已同步的前端资源。

不要直接修改构建出的页面壳和压缩文件。Java 后端继续托管页面，原判题接口、权限判断及 Cookie/CSRF 安全边界不变。

## 验证

```powershell
npm test
npm run test:e2e
npm run build
```

浏览器测试默认使用本机 Chrome，可通过进程环境变量 `PLAYWRIGHT_CHANNEL` 切换为 `msedge`。测试会启动/复用 5173 开发服务。所有接口替身只位于 `e2e/` 和 `*.test.*`；正式应用没有测试数据开关。真实后端冒烟验证另见 `../docs/frontend-migration.md`。

## 源码职责

- `src/lib/api.ts`：同源请求、CSRF、超时、错误与真实契约。
- `src/lib/tracker.ts`：STOMP 订阅、单飞轮询、退避和清理。
- `src/hooks/useJudge.ts`：原任务创建/启动/恢复/取消的生命周期。
- `src/lib/storage.ts`：旧页面草稿迁移，不保存登录凭据或缓存结果。
- `src/components/`：编辑器、弹窗、图标、插画、GSAP 动效。
- `src/pages/`：真实业务页面，不重复实现请求逻辑。

## 交互与可访问性

结果表头支持按测试点、耗时、内存切换升降序。默认按编号升序；首次选择耗时或内存时降序，切换排序返回第一页。排序作用于筛选后的全部已返回结果，再进行分页；大批量摘要模式仅排序已返回样本。

代码标签支持方向键；Ctrl/⌘+Enter 运行；Ctrl/⌘+K 打开功能导航；Escape 关闭弹窗并恢复焦点。原生 dialog 提供焦点约束。动效可手动关闭，且默认遵从系统减少动态效果设置。长结果分页、长输出限量渲染，错误、过期、断网和大批量摘要都有独立反馈。

所有源代码草稿仅在此浏览器自动保存。点击开始对拍才上传代码。加载示例覆盖已有草稿前会确认。刷新任务只查询服务端，不用本地缓存冒充真实结果。
