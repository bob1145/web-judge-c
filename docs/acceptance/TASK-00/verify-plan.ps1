$ErrorActionPreference = "Stop"
$root = Resolve-Path -LiteralPath "$PSScriptRoot/../../.."
$task = Get-Content -LiteralPath "$root/docs/task.md" -Raw -Encoding UTF8
$requirements = Get-Content -LiteralPath "$root/docs/defect-remediation-requirements-2026-10-02.md" -Raw -Encoding UTF8
$design = Get-Content -LiteralPath "$root/docs/defect-remediation-design-2026-10-02.md" -Raw -Encoding UTF8
$cards = [regex]::Matches($task, '(?m)^### TASK-(\d{2})')
if ($cards.Count -ne 30) { throw "Expected 30 tasks" }
for ($index = 0; $index -lt $cards.Count; $index++) {
    if ([int]$cards[$index].Groups[1].Value -ne $index) { throw "Task index mismatch" }
    $start = $cards[$index].Index
    $end = if ($index + 1 -lt $cards.Count) { $cards[$index + 1].Index } else { $task.IndexOf('## 6.', $start) }
    $card = $task.Substring($start, $end - $start)
    $fields = [regex]::Matches($card, '(?m)^- \*\*[^*]+\*\*.*$')
    if ($fields.Count -ne 9) { throw "Expected nine fields for task $index" }
    $dependency = $fields[2].Value.Split([char]0xff1a)[1].Split([char]0xff1b)[0]
    foreach ($number in [regex]::Matches($dependency, '\d{2}')) {
        if ([int]$number.Value -ge $index) { throw "Non-preceding dependency in task $index" }
    }
}
$matrixPattern = '(?m)^\| (DEF-\d{2}|N[1-8]) \| ([^|]+) \|'
$expected = [regex]::Matches($requirements, $matrixPattern)
$actual = [regex]::Matches($task, $matrixPattern)
if ($expected.Count -ne 31 -or $actual.Count -ne 31) { throw "Expected 31 defect mappings" }
for ($index = 0; $index -lt 31; $index++) {
    if ($expected[$index].Groups[1].Value -ne $actual[$index].Groups[1].Value -or $expected[$index].Groups[2].Value.Trim() -ne $actual[$index].Groups[2].Value.Trim()) { throw "Defect mapping mismatch at $index" }
}
$known = @([regex]::Matches($requirements, '\b(?:RQ|AC)-[A-Z]\d{2}[a-z]?\b') | ForEach-Object { $_.Value } | Sort-Object -Unique)
foreach ($reference in [regex]::Matches(($task + [Environment]::NewLine + $design), '\b(?:RQ|AC)-[A-Z]\d{2}[a-z]?\b')) {
    if ($reference.Value -notin $known) { throw "Undefined reference $($reference.Value)" }
}
$decoder = New-Object System.Text.UTF8Encoding($false, $true)
foreach ($path in @('docs/task.md', 'docs/work.md', 'docs/defect-remediation-requirements-2026-10-02.md', 'docs/defect-remediation-design-2026-10-02.md', 'docs/defect-review-2026-10-02.md', 'docs/defect-review-2026-10-02-dynamic-annex.md')) {
    $text = $decoder.GetString([System.IO.File]::ReadAllBytes("$root/$path"))
    if ([regex]::IsMatch($text, '(?m)[ \t]+\r?$')) { throw "Trailing whitespace in $path" }
}
Write-Output "PASS tasks=30 fields=270 preceding-dependencies=all defect-mappings=31 RQ-AC-references=defined UTF8=strict"
