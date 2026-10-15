export interface Bootstrap {
  csrfToken: string;
  csrfHeader: string;
  accountAuthRequired: boolean;
}

export interface SessionInfo {
  valid: boolean;
  expiresAt?: string;
  rememberMe?: boolean;
  remainingMinutes?: number;
}

export interface AuthResponse {
  success: boolean;
  message?: string;
  redirectUrl?: string;
}

export interface JudgeRequest {
  userCode: string;
  generatorCode: string;
  bruteForceCode: string;
  specialJudgeCode: string;
  timeLimit: number;
  memoryLimit: number;
  precision: string;
  testCases: number;
  stopOnFirstNonAc: boolean;
  useSpecialJudge: boolean;
}

export interface WorkspaceDraft extends Omit<JudgeRequest, 'memoryLimit'> {
  memoryLimitMb: number;
}

export type CodeKey = 'generatorCode' | 'bruteForceCode' | 'userCode' | 'specialJudgeCode';
export type View = 'workspace' | 'results' | 'examples' | 'guide';

export interface CreateResponse {
  judgeId: string;
  mode: string;
  requestedCases: number;
  maxCasesPerTask: number;
  maxOutputBytesPerCase: number;
  highVolume: boolean;
  status: string;
}

export interface CaseResult {
  caseNumber: number;
  status: string;
  timeUsed: number;
  memoryUsed: number;
}

export interface JudgeSummary {
  totalCases: number;
  completedCases: number;
  ac: number;
  wa: number;
  tle: number;
  mle: number;
  re: number;
  systemError: number;
  outputLimitExceeded: number;
  firstFailedCase?: number | null;
  failureSamples?: CaseResult[] | null;
  slowSamples?: CaseResult[] | null;
  stoppedReason?: string | null;
}

export interface JudgeProgress {
  status: string;
  message?: string;
  progress: number;
  results?: CaseResult[] | null;
  summary?: JudgeSummary | null;
  attemptId?: string | null;
  revision?: number | null;
  finalizedAt?: string | null;
}

export interface CancelResponse {
  judgeId: string;
  accepted: boolean;
  status: string;
  message: string;
  running: boolean;
  queued: boolean;
  completedCases: number;
}

export interface CaseDetail {
  input: string;
  userOutput: string;
  correctOutput: string;
  inputTruncated: boolean;
  userOutputTruncated: boolean;
  correctOutputTruncated: boolean;
}
