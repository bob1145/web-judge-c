import type { WorkspaceDraft } from './types';

export interface CodeExample {
  id: string;
  title: string;
  category: string;
  description: string;
  difficulty: string;
  code: Pick<WorkspaceDraft, 'generatorCode' | 'bruteForceCode' | 'userCode'>;
}

const sum = '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    long long first, second;\n    cin >> first >> second;\n    cout << first + second << "\\n";\n    return 0;\n}\n';
const generator = '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    random_device entropy;\n    mt19937_64 random(entropy());\n    uniform_int_distribution<long long> value(1, 1000000);\n    cout << value(random) << " " << value(random) << "\\n";\n    return 0;\n}\n';

export const examples: CodeExample[] = [
  {
    id: 'sum', title: '从 A + B 开始', category: '快速入门', difficulty: '基础',
    description: '用最简单的加法，走通生成、运行与比对的完整流程。',
    code: { generatorCode: generator, bruteForceCode: sum, userCode: sum },
  },
  {
    id: 'overflow', title: '发现整数溢出', category: '边界检查', difficulty: '进阶',
    description: '看似正确的加法，也可能藏着边界问题。试着找到它。',
    code: {
      generatorCode: generator.replace('1, 1000000', '1000000000, 2000000000'),
      bruteForceCode: sum,
      userCode: sum.replace('long long first, second', 'int first, second'),
    },
  },
  {
    id: 'sort', title: '验证你的排序', category: '随机测试', difficulty: '基础',
    description: '让随机数组与标准排序交叉验证，覆盖重复值和负数。',
    code: {
      generatorCode: '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    mt19937 random(random_device{}());\n    int count = uniform_int_distribution<int>(1, 100)(random);\n    cout << count << "\\n";\n    for (int index = 0; index < count; ++index) {\n        cout << uniform_int_distribution<int>(-100, 100)(random) << " ";\n    }\n}\n',
      bruteForceCode: '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    int count;\n    cin >> count;\n    vector<int> values(count);\n    for (auto &value : values) cin >> value;\n    sort(values.begin(), values.end());\n    for (auto value : values) cout << value << " ";\n}\n',
      userCode: '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    int count;\n    cin >> count;\n    vector<int> values(count);\n    for (auto &value : values) cin >> value;\n    for (int end = count - 1; end > 0; --end) {\n        for (int index = 0; index < end; ++index) {\n            if (values[index] > values[index + 1]) swap(values[index], values[index + 1]);\n        }\n    }\n    for (auto value : values) cout << value << " ";\n}\n',
    },
  },
];
