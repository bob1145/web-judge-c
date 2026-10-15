import { ArrowRight, ChevronDown, FileCode2, Keyboard, LockKeyhole, Play, Waves } from 'lucide-react';
import { Button } from '../components/ui';

export function GuidePage({ onExamples }: { onExamples: () => void }) {
  return <><div className="page-heading" data-reveal><div><h1>使用指南</h1></div><Button variant="primary" onClick={onExamples}>试用示例 <ArrowRight size={16} /></Button></div>
    <section className="guide-start panel" data-reveal><div className="guide-start-copy"><h2>基本流程</h2><p>数据生成器创建输入；参考程序给出正确输出；待测程序运行你的解法。服务端逐组比对，帮你发现潜在问题。</p></div><ol className="guide-steps"><li><span>01</span><div><h3>准备代码</h3><p>填写三份 C++ 程序，或先加载一组示例。</p></div><FileCode2 size={18} /></li><li><span>02</span><div><h3>设定范围</h3><p>设置测试数量、单点时间与内存限制。</p></div><Waves size={18} /></li><li><span>03</span><div><h3>运行与复盘</h3><p>开始对拍，查看失败点并下载测试数据复现。</p></div><Play size={18} /></li></ol></section>
    <div className="guide-columns"><section className="guide-faq" data-reveal><h2>常见问题</h2>{[
      ['测试通过，是否意味着算法一定正确？', '不一定。AC 表示当前生成的数据通过了比较，不能证明所有输入都正确。建议主动覆盖边界、极端值和不同数据分布。'],
      ['为什么使用随机生成器？', '生成器每次运行输出一组输入数据。请使用可靠的随机种子，避免重复得到同一组输入，也要确保数据符合题目约束。'],
      ['如何比较浮点数？', '在高级设置中调整比较精度。0 表示精确比较，1e-9 等非负数用于浮点容差。最终比较规则由后端决定。'],
      ['Special Judge 如何使用？', '开启后，自定义判题程序代替参考程序。当前本地执行实现从标准输入传入两行路径：输入文件、待测输出文件；返回 0 表示 AC，1 表示 WA，2 表示格式错误。隔离执行环境请按部署文档确认支持情况。'],
      ['关闭或刷新页面，会丢失任务吗？', '代码和参数自动保存在此浏览器中。刷新页面后会查询原任务，不会重复提交。关闭页面不会主动取消任务；请在结果页明确取消。'],
      ['遇错停止后，为什么还有其他测试点？', '后端可能并发执行多组测试。发现错误后不再新增测试，已启动的测试会完成并显示结果。全部已判数据只打包有判定记录的测试点。'],
      ['下载文件分别是什么？', '.in 是输入，.out 是待测程序输出，.ans 是参考程序答案。下载测试点可一次获取三类文件；未生成的文件不会伪造，Special Judge 模式可能没有 .ans。'],
      ['为什么只看到部分测试点？', '大批量任务可能只返回摘要与失败、慢速样本。界面不会补造缺失结果。输入或输出预览被截断时，可下载测试点查看完整文件。'],
      ['登录失效或启动失败怎么办？', '重新验证后查询原任务状态。对于已创建的任务，「查询并重试原任务」只重试原 ID，不会另建任务。队列或配额不足时请稍后再试。'],
    ].map(([question, answer]) => <details key={question}><summary>{question}<ChevronDown size={17} /></summary><p>{answer}</p></details>)}</section>
    <aside className="guide-aside" data-reveal><section className="shortcut-card"><h3><Keyboard size={18} />快捷键</h3><dl><div><dt>开始对拍</dt><dd><kbd>Ctrl</kbd> + <kbd>Enter</kbd></dd></div><div><dt>快捷导航</dt><dd><kbd>Ctrl</kbd> + <kbd>K</kbd></dd></div><div><dt>关闭弹窗</dt><dd><kbd>Esc</kbd></dd></div><div><dt>切换代码标签</dt><dd><kbd>←</kbd> <kbd>→</kbd></dd></div></dl><p>macOS 使用 ⌘ 代替 Ctrl。</p></section><section className="privacy-note"><LockKeyhole size={23} /><h3>本地草稿</h3><p>草稿存储在此浏览器。只有点击开始对拍时，程序才会发送到服务端执行。请勿在共享设备中保存敏感代码。</p></section></aside></div>
  </>;
}
