# 随机数方案测试指南

## 🧪 如何测试随机数质量

### 1. 快速测试方法

在对拍工具中使用以下测试代码来验证随机数是否重复：

**生成器代码（用于测试）：**
```cpp
#include <bits/stdc++.h>
using namespace std;

class RandomGenerator {
private:
    mt19937_64 rng;
    
public:
    RandomGenerator() {
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

int main() {
    RandomGenerator rng;
    
    // 生成一个较大的随机数，便于观察重复
    cout << rng.randInt(1, 1000000) << endl;
    
    return 0;
}
```

**暴力代码（记录所有输入）：**
```cpp
#include <bits/stdc++.h>
using namespace std;

int main() {
    int n;
    cin >> n;
    
    // 输出到stderr用于调试，不影响正常输出
    cerr << "Generated: " << n << endl;
    
    // 正常输出
    cout << n << endl;
    
    return 0;
}
```

**待测试代码（相同逻辑）：**
```cpp
#include <bits/stdc++.h>
using namespace std;

int main() {
    int n;
    cin >> n;
    cout << n << endl;
    return 0;
}
```

### 2. 设置大量测试用例

- 测试点数量：设置为 **1000** 或更多
- 时间限制：**1000ms**（足够快）
- 观察结果网格中是否有WA（如果有WA说明生成了重复数据）

### 3. 观察方法

1. **查看控制台日志**：观察是否有重复的随机数
2. **检查WA数量**：理论上应该全部AC（因为输入输出相同）
3. **多次运行**：连续运行几次，观察随机数是否有变化

## 📊 不同方案的测试结果预期

### 传统方案（srand + rand）
```cpp
srand(time(0));
int n = rand() % 1000 + 1;
```
**预期结果：** 
- ❌ 大量重复数据
- ❌ 可能出现很多WA
- ❌ 连续运行结果相似

### 高精度时间戳方案
```cpp
auto seed = chrono::high_resolution_clock::now().time_since_epoch().count();
mt19937_64 rng(seed);
```
**预期结果：**
- ✅ 很少重复数据
- ✅ 基本全部AC
- ✅ 每次运行结果不同

### 多熵源混合方案（推荐）
```cpp
random_device rd;
auto seed = chrono::high_resolution_clock::now().time_since_epoch().count();
seed ^= hash<thread::id>{}(this_thread::get_id());
seed ^= rd();
```
**预期结果：**
- ✅ 极少重复数据
- ✅ 全部AC
- ✅ 最高的随机质量

## 🔍 调试技巧

### 1. 添加调试输出
```cpp
int main() {
    RandomGenerator rng;
    int n = rng.randInt(1, 1000);
    
    // 调试输出（输出到stderr）
    cerr << "PID: " << getpid() << ", Generated: " << n << endl;
    
    cout << n << endl;
    return 0;
}
```

### 2. 统计重复率
创建一个简单的脚本来统计重复：
```bash
# 运行多次并统计
for i in {1..100}; do
    echo "Run $i"
    # 运行你的生成器并收集结果
done
```

### 3. 可视化分布
```cpp
// 生成大量数据并分析分布
map<int, int> freq;
for (int i = 0; i < 10000; i++) {
    int val = rng.randInt(1, 100);
    freq[val]++;
}

// 输出频率分布
for (auto [val, count] : freq) {
    cerr << val << ": " << count << endl;
}
```

## 🎯 实际测试步骤

1. **选择方案**：在对拍工具中点击"随机数方案"按钮
2. **设置参数**：测试点数量设为1000，时间限制1000ms
3. **运行测试**：点击"开始对拍"
4. **观察结果**：
   - 全部AC = 随机数方案工作正常
   - 有WA = 可能存在重复数据
5. **多次验证**：重复运行几次，确保每次结果都不同

## 💡 优化建议

1. **生产环境**：使用多熵源混合方案
2. **开发测试**：高精度时间戳方案已足够
3. **特殊需求**：可以添加额外的随机化因子
4. **性能考虑**：避免在循环中重复初始化随机数生成器

通过这些测试方法，你可以验证选择的随机数方案是否能在多线程环境下产生高质量的、不重复的测试数据。