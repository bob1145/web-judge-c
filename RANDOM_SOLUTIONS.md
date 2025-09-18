# 多线程环境下随机数生成解决方案

## 🐛 问题描述

在多线程对拍环境中，如果多个测试用例同时运行，使用传统的 `srand(time(0))` 会导致：
- 相同的时间戳产生相同的随机数种子
- 多个进程生成相同的随机数序列
- 测试数据缺乏多样性，影响测试效果

## 🔧 解决方案

### 方案1：高质量随机数生成器（推荐）

```cpp
#include <bits/stdc++.h>
using namespace std;

class RandomGenerator {
private:
    mt19937_64 rng;
    
public:
    RandomGenerator() {
        // 使用多种熵源初始化
        random_device rd;
        auto seed = chrono::high_resolution_clock::now().time_since_epoch().count();
        seed ^= hash<thread::id>{}(this_thread::get_id());
        seed ^= rd();
        rng.seed(seed);
    }
    
    long long randInt(long long min_val, long long max_val) {
        uniform_int_distribution<long long> dist(min_val, max_val);
        return dist(rng);
    }
};
```

**优势：**
- 使用多种熵源：高精度时间、线程ID、随机设备
- 即使在同一毫秒内启动也能产生不同种子
- 使用现代C++的随机数库，质量更高

### 方案2：高精度时间戳（简化版）

```cpp
#include <bits/stdc++.h>
using namespace std;

int main() {
    // 使用高精度时间戳作为种子
    auto seed = chrono::high_resolution_clock::now().time_since_epoch().count();
    mt19937_64 rng(seed);
    
    // 生成随机数
    int n = rng() % 1000 + 1;
    int m = rng() % 1000 + 1;
    
    cout << n << " " << m << endl;
    return 0;
}
```

**优势：**
- 使用纳秒级精度时间戳
- 即使在短时间内也能产生不同种子
- 代码简洁，易于理解

### 方案3：random_device + 容错处理

```cpp
#include <bits/stdc++.h>
using namespace std;

int main() {
    random_device rd;
    mt19937_64 rng;
    
    // 尝试使用random_device，如果失败则使用时间戳
    try {
        rng.seed(rd());
    } catch (...) {
        auto seed = chrono::high_resolution_clock::now().time_since_epoch().count();
        rng.seed(seed);
    }
    
    uniform_int_distribution<int> dist(1, 1000);
    int n = dist(rng);
    int m = dist(rng);
    
    cout << n << " " << m << endl;
    return 0;
}
```

**优势：**
- 优先使用系统随机设备
- 有容错机制，确保在任何环境下都能工作
- 使用标准分布函数，避免模运算的偏差

## 📊 方案对比

| 方案 | 随机质量 | 多线程安全 | 代码复杂度 | 推荐指数 |
|------|----------|------------|------------|----------|
| 传统srand | ⭐⭐ | ❌ | ⭐ | ❌ |
| 高精度时间 | ⭐⭐⭐⭐ | ✅ | ⭐⭐ | ⭐⭐⭐⭐ |
| 多熵源混合 | ⭐⭐⭐⭐⭐ | ✅ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| random_device | ⭐⭐⭐⭐⭐ | ✅ | ⭐⭐⭐ | ⭐⭐⭐⭐ |

## 🎯 实际应用示例

### 生成数组测试数据
```cpp
RandomGenerator rng;

int n = rng.randInt(1, 100000);
cout << n << endl;

for (int i = 0; i < n; i++) {
    cout << rng.randInt(1, 1000000);
    if (i < n - 1) cout << " ";
}
cout << endl;
```

### 生成图论测试数据
```cpp
RandomGenerator rng;

int n = rng.randInt(3, 1000);  // 节点数
int m = rng.randInt(n-1, n*(n-1)/2);  // 边数

cout << n << " " << m << endl;

set<pair<int,int>> edges;
while (edges.size() < m) {
    int u = rng.randInt(1, n);
    int v = rng.randInt(1, n);
    if (u != v) {
        edges.insert({min(u,v), max(u,v)});
    }
}

for (auto [u, v] : edges) {
    cout << u << " " << v << endl;
}
```

### 生成字符串测试数据
```cpp
RandomGenerator rng;

int len = rng.randInt(1, 100000);
string chars = "abcdefghijklmnopqrstuvwxyz";

for (int i = 0; i < len; i++) {
    cout << chars[rng.randInt(0, chars.length()-1)];
}
cout << endl;
```

## 🔍 调试技巧

### 1. 验证随机性
```cpp
// 生成多个随机数，检查是否有重复
set<int> generated;
for (int i = 0; i < 1000; i++) {
    int val = rng.randInt(1, 1000000);
    if (generated.count(val)) {
        cerr << "Duplicate found: " << val << endl;
    }
    generated.insert(val);
}
```

### 2. 种子调试
```cpp
// 输出种子值用于调试
auto seed = chrono::high_resolution_clock::now().time_since_epoch().count();
cerr << "Seed: " << seed << endl;
mt19937_64 rng(seed);
```

## 💡 最佳实践

1. **推荐使用方案1**（多熵源混合），随机质量最高
2. **避免使用传统的srand/rand**，质量差且不线程安全
3. **使用uniform_distribution**而不是模运算，避免偏差
4. **在生成器中添加参数控制**，便于调整测试数据规模
5. **考虑数据的有效性**，确保生成的数据符合题目约束

## 🚀 性能考虑

- `mt19937_64` 比 `mt19937` 在64位系统上更快
- `random_device` 可能较慢，适合用于种子初始化
- 高精度时间戳获取开销很小，可以频繁使用
- 分布函数比直接模运算稍慢，但质量更高

这些解决方案确保了在高并发的对拍环境中，每个测试用例都能生成独特且高质量的随机数据。