# C++ 在线对拍工具

> 一个专为算法竞赛设计的在线代码测试平台，帮助开发者快速验证C++解题方案的正确性

## 项目简介

这是一个基于Spring Boot开发的Web应用，专门用于C++算法代码的自动化测试。通过对比用户代码与标准答案的输出结果，快速发现代码中的逻辑错误，提高算法竞赛和编程练习的效率。
<img width="2514" height="1303" alt="image" src="https://github.com/user-attachments/assets/ac3bcec2-75b1-4a95-80f1-f0b010942db2" />
<img width="2498" height="1312" alt="image" src="https://github.com/user-attachments/assets/bb63e69a-e896-46b5-ada2-a0b15c936c48" />

## 核心特性

### 🚀 智能对拍系统
- **三段式代码输入**：数据生成器、标准答案、待测代码
- **自动化测试流程**：生成随机数据 → 运行对比 → 结果分析
- **多种判题状态**：AC、WA、TLE、RE、CE、SE

### 💻 现代化界面
- 基于Bootstrap 5的响应式设计
- 实时进度显示和状态更新
- 直观的测试结果网格视图
- 详细的错误分析弹窗

### ⚡ 高性能架构
- **异步处理**：后台执行判题任务，不阻塞用户界面
- 多线程测试数据点
- **WebSocket通信**：实时推送测试进度和状态
- **并发支持**：可同时处理多个判题请求

### 🔍 强大的调试功能
- **差异高亮**：使用jsdiff库精确标记输出差异
- **数据下载**：一键下载失败测试点的输入数据
- **浮点数比较**：支持自定义精度的数值比较
- **宽松匹配**：智能忽略空格和换行符差异

## 技术架构

### 后端技术栈
- **Java 17** - 现代化的Java开发环境
- **Spring Boot 3.5.3** - 企业级应用框架
- **Spring WebSocket** - 实时通信支持
- **Spring Web** - RESTful API服务
- **Thymeleaf** - 服务端模板引擎
- **Apache Commons IO** - 文件操作工具
- **Lombok** - 代码简化工具

### 前端技术栈
- **Bootstrap 5** - 现代化UI框架
- **JavaScript ES6** - 前端交互逻辑
- **WebSocket (STOMP)** - 实时通信协议
- **jsdiff** - 文本差异对比库

### 系统架构
```
┌─────────────────┐    WebSocket    ┌─────────────────┐
│   前端界面      │ ←──────────────→ │   Spring Boot   │
│   (Bootstrap)   │                 │   后端服务      │
└─────────────────┘                 └─────────────────┘
                                            │
                                            ▼
                                    ┌─────────────────┐
                                    │   C++编译器     │
                                    │   (g++/clang)   │
                                    └─────────────────┘
```

## 快速开始

### 环境要求
- **Java JDK 17+** - 运行Spring Boot应用
- **Maven 3.6+** - 项目构建工具
- **C++编译器** - g++或clang，需添加到系统PATH

### 安装步骤

1. **克隆项目**
   ```bash
   git clone https://github.com/bob1145/web-judge-c.git
   cd demo18
   ```

2. **编译运行**
   ```bash
   # 使用Maven运行
   mvn spring-boot:run
   
   # 或者编译后运行
   mvn clean package
   java -jar target/demo18-0.0.1-SNAPSHOT.jar
   ```

3. **访问应用**
   ```
   http://localhost:1234
   ```

## 使用指南

### 基本流程

1. **准备代码**
   - **数据生成器**：编写生成随机测试数据的C++代码
   - **标准答案**：提供已验证正确的解题代码
   - **待测代码**：需要验证的目标代码

2. **配置参数**
   - **测试点数量**：设置测试轮数（建议10-100）
   - **时间限制**：单个测试点的最大执行时间（毫秒）
   - **浮点精度**：数值比较的误差范围

3. **执行测试**
   - 点击"开始对拍"按钮
   - 实时观察进度条和状态信息
   - 等待测试完成

4. **分析结果**
   - 查看测试结果网格
   - 点击失败的测试点查看详情
   - 下载问题数据进行本地调试

### 代码示例

**数据生成器示例**
```cpp
#include <iostream>
#include <random>
using namespace std;

int main() {
    random_device rd;
    mt19937 gen(rd());
    uniform_int_distribution<> dis(1, 1000);
    
    int n = dis(gen);
    cout << n << endl;
    for (int i = 0; i < n; i++) {
        cout << dis(gen) << " ";
    }
    return 0;
}
```

**示例**
```cpp
#include <iostream>
#include <vector>
#include <algorithm>
using namespace std;

int main() {
    int n;
    cin >> n;
    vector<int> arr(n);
    for (int i = 0; i < n; i++) {
        cin >> arr[i];
    }
    sort(arr.begin(), arr.end());
    for (int x : arr) {
        cout << x << " ";
    }
    return 0;
}
```

## 项目结构

```
demo18/
├── src/main/java/com/example/demo/
│   ├── Demo18Application.java          # 应用入口
│   ├── config/                         # 配置类
│   ├── controller/                     # 控制器层
│   ├── dto/                           # 数据传输对象
│   └── service/                       # 业务逻辑层
├── src/main/resources/
│   ├── static/                        # 静态资源
│   ├── templates/                     # 模板文件
│   └── application.properties         # 应用配置
├── pom.xml                           # Maven配置
└── README.md                         # 项目文档
```

## 开发指南

### 本地开发
```bash
# 开发模式运行
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 热重载（需要spring-boot-devtools）
mvn spring-boot:run -Dspring-boot.run.fork=false
```

### 构建部署
```bash
# 生产环境打包
mvn clean package -Pprod

# Docker部署（如果有Dockerfile）
docker build -t cpp-checker .
docker run -p 1234:1234 cpp-checker
```

## 常见问题

### Q: 编译错误怎么办？
A: 确保系统已安装C++编译器并添加到PATH环境变量中。

### Q: 测试超时如何处理？
A: 适当增加时间限制，或优化代码算法复杂度。

### Q: 浮点数比较不准确？
A: 调整浮点精度参数，通常设置为1e-6到1e-9。

### Q: 如何查看详细错误信息？
A: 点击失败的测试点，在弹窗中查看完整的输入输出对比。

## 贡献指南

欢迎提交Issue和Pull Request来改进这个项目！

1. Fork本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启Pull Request

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交Issue

---

⭐ 如果这个项目对你有帮助，请给个Star支持一下！
