// 示例代码
const examples = {
    // 方案1：使用高精度时间 + 进程ID + 随机设备
    generator1: `#include <bits/stdc++.h>
using namespace std;

// 高质量随机数生成器
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
    
    double randDouble(double min_val, double max_val) {
        uniform_real_distribution<double> dist(min_val, max_val);
        return dist(rng);
    }
};

int main() {
    RandomGenerator rng;
    
    // 生成测试数据
    int n = rng.randInt(1, 1000);
    int m = rng.randInt(1, 1000);
    
    cout << n << " " << m << endl;
    return 0;
}`,

    // 方案2：简化版本，使用chrono高精度时间
    generator2: `#include <bits/stdc++.h>
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
}`,

    // 方案3：使用random_device（如果系统支持）
    generator3: `#include <bits/stdc++.h>
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
    
    // 生成随机数
    uniform_int_distribution<int> dist(1, 1000);
    int n = dist(rng);
    int m = dist(rng);
    
    cout << n << " " << m << endl;
    return 0;
}`,

    // 默认使用方案1（最推荐）
    generator: `#include <bits/stdc++.h>
using namespace std;

// 高质量随机数生成器
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

int main() {
    RandomGenerator rng;
    
    // 生成测试数据
    int n = rng.randInt(1, 1000);
    int m = rng.randInt(1, 1000);
    
    cout << n << " " << m << endl;
    return 0;
}`,

    bruteForce: `#include <bits/stdc++.h>
using namespace std;

int main() {
    int n, m;
    cin >> n >> m;
    
    // 暴力解法：简单相加
    cout << n + m << endl;
    
    return 0;
}`,

    userSolution: `#include <bits/stdc++.h>
using namespace std;

int main() {
    int n, m;
    cin >> n >> m;
    
    // 待测试的解法
    cout << n + m << endl;
    
    return 0;
}`
};

// 加载示例代码的函数
function loadExample() {
    if (confirm('是否加载示例代码？这将覆盖当前的代码内容。')) {
        generatorEditor.setValue(examples.generator);
        bruteForceEditor.setValue(examples.bruteForce);
        userEditor.setValue(examples.userSolution);
    }
}

// 显示随机数方案选择对话框
function showRandomSolutionDialog() {
    const modal = document.createElement('div');
    modal.className = 'modal fade';
    modal.innerHTML = `
        <div class="modal-dialog modal-lg">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">
                        <i class="fas fa-dice"></i> 选择随机数生成方案
                    </h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                </div>
                <div class="modal-body">
                    <p class="text-muted mb-3">在多线程环境下，选择合适的随机数生成方案可以避免生成相同的测试数据：</p>
                    
                    <div class="row">
                        <div class="col-md-6 mb-3">
                            <div class="card h-100 solution-card" data-solution="generator1">
                                <div class="card-body">
                                    <h6 class="card-title text-success">
                                        <i class="fas fa-star"></i> 方案1：多熵源混合 (推荐)
                                    </h6>
                                    <p class="card-text small">使用高精度时间、线程ID、随机设备等多种熵源，随机质量最高</p>
                                    <div class="badge bg-success">推荐</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="col-md-6 mb-3">
                            <div class="card h-100 solution-card" data-solution="generator2">
                                <div class="card-body">
                                    <h6 class="card-title text-primary">
                                        <i class="fas fa-clock"></i> 方案2：高精度时间戳
                                    </h6>
                                    <p class="card-text small">使用纳秒级时间戳，代码简洁，适合大多数场景</p>
                                    <div class="badge bg-primary">简洁</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="col-md-6 mb-3">
                            <div class="card h-100 solution-card" data-solution="generator3">
                                <div class="card-body">
                                    <h6 class="card-title text-info">
                                        <i class="fas fa-shield-alt"></i> 方案3：随机设备 + 容错
                                    </h6>
                                    <p class="card-text small">优先使用系统随机设备，有完善的容错机制</p>
                                    <div class="badge bg-info">稳定</div>
                                </div>
                            </div>
                        </div>
                        
                        <div class="col-md-6 mb-3">
                            <div class="card h-100 solution-card" data-solution="generator">
                                <div class="card-body">
                                    <h6 class="card-title text-warning">
                                        <i class="fas fa-code"></i> 默认方案
                                    </h6>
                                    <p class="card-text small">当前使用的方案，与方案1相同</p>
                                    <div class="badge bg-warning">当前</div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">取消</button>
                </div>
            </div>
        </div>
    `;
    
    document.body.appendChild(modal);
    const bootstrapModal = new bootstrap.Modal(modal);
    
    // 添加点击事件
    modal.querySelectorAll('.solution-card').forEach(card => {
        card.style.cursor = 'pointer';
        card.addEventListener('click', function() {
            const solution = this.dataset.solution;
            if (examples[solution]) {
                generatorEditor.setValue(examples[solution]);
                bootstrapModal.hide();
                showSuccess('已加载 ' + this.querySelector('.card-title').textContent.trim() + ' 的代码');
            }
        });
        
        // 添加悬停效果
        card.addEventListener('mouseenter', function() {
            this.style.transform = 'translateY(-2px)';
            this.style.boxShadow = '0 4px 8px rgba(0,0,0,0.1)';
        });
        
        card.addEventListener('mouseleave', function() {
            this.style.transform = 'translateY(0)';
            this.style.boxShadow = '';
        });
    });
    
    // 模态框关闭时移除DOM元素
    modal.addEventListener('hidden.bs.modal', function() {
        document.body.removeChild(modal);
    });
    
    bootstrapModal.show();
}