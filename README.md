# C++ 在线对拍工具 (C++ Online Code Checker)

一个基于Web的在线C++对拍工具，旨在帮助算法竞赛爱好者和开发者高效地测试其代码的正确性。它能自动地生成测试数据、运行您的代码和“暴力”代码，并对比它们的输出，从而找出潜在的错误。本工具提供实时的评测反馈和详细的失败样例分析。

A web-based tool for competitive programmers to test their C++ solutions against a brute-force solution. It automates the process of generating test cases, running both solutions, and comparing their outputs to find discrepancies.

---

## ✨ 主要功能 (Features)

*   **网页化界面**: 使用 Bootstrap 5 构建的现代化、简洁直观的用户界面，方便提交代码和查看结果。
*   **异步判题**: 判题任务在后台异步执行，不会阻塞UI，可同时处理多个判题请求。
*   **实时进度反馈**: 通过 WebSocket 技术，将实时的评测状态（编译中、运行中、测试点进度）推送到前端，并以进度条和状态文本的形式生动展示。
*   **详尽的评测状态**:
    *   `AC` (Accepted): 全部通过
    *   `WA` (Wrong Answer): 答案错误
    *   `TLE` (Time Limit Exceeded): 时间超限
    *   `RE` (Runtime Error): 运行时错误
    *   `CE` (Compilation Error): 编译失败
    *   `SE` (System Error): 系统或辅助代码错误
*   **灵活的输出比较**:
    *   支持**宽松匹配** (忽略行末空格、文末换行等)。
    *   支持用户自定义的**浮点数精度**比较。
*   **强大的失败分析**:
    *   以**网格视图**清晰展示所有测试点的最终结果。
    *   可**点击**失败的测试点，在弹窗中查看详情。
    *   对 `WA` 的样例，使用 `jsdiff` 库进行**高亮差异对比**，一目了然。
    *   提供导致失败的**完整输入数据**。
*   **测试数据下载**: 支持一键下载导致失败的测试点的输入数据（`.in` 文件），方便本地调试。
*   **持久化测试数据**: 每次判题的测试数据都会在服务器上临时保存，支持判题结束后的复盘分析。
  
![image](https://github.com/user-attachments/assets/f25354ca-f6de-4684-bd9d-317827fec9bb)

![image](https://github.com/user-attachments/assets/7d3d6a5f-d642-4adc-882d-a15e49aa0a79)



---

## 🛠️ 技术栈 (Technology Stack)

*   **后端 (Backend)**:
    *   Java 17
    *   Spring Boot 3
    *   Spring Web
    *   Spring WebSocket (STOMP)
    *   Maven
*   **前端 (Frontend)**:
    *   HTML5 / CSS3
    *   Bootstrap 5
    *   JavaScript (ES6)
    *   SockJS & Stomp.js
    *   jsdiff

---

## 🚀 如何运行 (Getting Started)

### 环境要求 (Prerequisites)

1.  **Java JDK**: 版本 17 或更高。
2.  **Maven**: 用于项目构建和依赖管理。
3.  **C++ 编译器**: 必须安装 C++ 编译器（如 **g++**），并将其添加到系统的 `PATH` 环境变量中，以便程序可以从任何位置调用它。

### 启动步骤 (Installation & Running)

1.  **克隆仓库**
    ```bash
    git clone https://github.com/bob1145/web-judge-c.git
    cd demo18
    ```

2.  **使用 Maven 运行**
    在项目根目录下，执行以下命令：
    ```bash
    mvn spring-boot:run
    ```
    或者，你也可以直接在 IDE (如 IntelliJ IDEA) 中打开项目，找到 `Demo18Application.java` 文件并运行它的 `main` 方法。

3.  **访问应用**
    应用启动后，打开你的浏览器并访问:
    > [http://localhost:8080](http://localhost:8080)

---

## 📖 使用指南 (How to Use)

1.  **填写代码**:
    *   **生成器代码 (Generator)**: 粘贴用于生成随机测试数据的C++代码。
    *   **暴力/正确代码 (Brute Force)**: 粘贴已知完全正确的、用于产生标准答案的C++代码。
    *   **待测试代码 (My Solution)**: 粘贴你想要测试其正确性的C++代码。
2.  **配置参数**:
    *   **测试点数量**: 设置需要进行多少轮测试。
    *   **时间限制 (ms)**: 设置你的代码在每个测试点上的最大运行时间。
    *   **浮点数精度**: 设置浮点数比较允许的最大误差。
3.  **开始对拍**: 点击 **"开始对拍"** 按钮。
4.  **查看结果**:
    *   实时观察下方的进度条和状态信息。
    *   判题结束后，页面下方会展示所有测试点的结果网格。
    *   点击任何一个 **WA**, **TLE** 或 **RE** 的测试点，即可在弹出的窗口中查看详细的对比信息和输入数据。
    *   在弹窗中，你可以点击 "下载输入数据" 按钮来获取该测试点的 `.in` 文件。
