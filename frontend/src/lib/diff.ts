export interface DiffLine {
  number: number;
  expected: string | undefined;
  actual: string | undefined;
  different: boolean;
}

export function compareOutputs(expectedOutput: string, actualOutput: string, differencesOnly: boolean, maxRows = 200, maxCellChars = 2000) {
  const expected = expectedOutput.replace(/\r\n?/g, '\n').split('\n');
  const actual = actualOutput.replace(/\r\n?/g, '\n').split('\n');
  const rows: DiffLine[] = [];
  let truncated = false;
  let hasDifferences = false;
  for (let index = 0; index < Math.max(expected.length, actual.length); index++) {
    const different = expected[index] !== actual[index];
    if (different) hasDifferences = true;
    if (differencesOnly && !different) continue;
    if (rows.length >= maxRows) { truncated = true; continue; }
    if ((expected[index]?.length || 0) > maxCellChars || (actual[index]?.length || 0) > maxCellChars) truncated = true;
    rows.push({ number: index + 1, expected: expected[index]?.slice(0, maxCellChars), actual: actual[index]?.slice(0, maxCellChars), different });
  }
  return { rows, hasDifferences, truncated };
}
