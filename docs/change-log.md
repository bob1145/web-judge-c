# Change Log

## 2026-07-03

- Scope: Frontend redesign for the C++ online differential testing workspace.
- Summary: Reworked the main judge page and access-code page into a light production tool layout, preserved existing form ids, API calls, WebSocket progress handling, result summary rendering, detail downloads, and local code storage. Added lightweight GSAP entrance motion with reduced-motion support.
- Data Sources: Existing Spring Boot templates, existing `/judge`, `/judge/start/{judgeId}`, `/judge/status/{judgeId}`, `/details/{judgeId}/{caseNumber}`, `/download/{judgeId}/...`, and `/auth/...` endpoints.
- Integration Status: No mocked API fields or fake backend data added. The frontend still consumes existing backend responses.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed. Browser render check passed for login, desktop workspace, and mobile workspace with no horizontal overflow.

## 2026-07-04

- Scope: Frontend workspace layout refinement for the C++ differential testing page.
- Summary: Removed the intro information cards, split the workspace into navigation-driven Code Editing and Test Results views, moved judge progress into the results view, auto-switches to results while running, and applied the GitHub Light CodeMirror theme with a light GitHub-style editor chrome.
- Data Sources: Existing Spring Boot template and existing CodeMirror editor instances. The editor theme is loaded from the public `codemirror-github-light` package.
- Integration Status: No backend API changes, no mocked fields, and existing form/result ids remain in place.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed. `git diff --check` passed with only CRLF conversion warnings from Git on Windows. Browser render check passed on the updated `http://127.0.0.1:1235` service for desktop code view, desktop results view, and mobile code view with no horizontal overflow.

## 2026-07-04

- Scope: Compile-error progress handling and user-facing diagnostics.
- Summary: Mapped compile-error aliases such as `CE` and `COMPILE_ERROR` to terminal `COMPILATION_ERROR`, made sandbox `COMPILE_FINISHED` compile failures stop immediately, restored the frontend from no-result terminal events, and hid Windows/Linux/WSL host paths from compiler diagnostics.
- Data Sources: Existing judge progress events, task store state, sandbox runner event contract, and frontend WebSocket/polling progress handlers.
- Integration Status: No new API fields added. Existing progress payloads remain compatible while additional terminal status aliases are recognized.
- Test Results: `.\mvnw.cmd "-Dtest=ProgressPublisherTest,SandboxEventIngestorTest,FrontendTemplateContractTest" test` passed. `.\mvnw.cmd "-Dtest=JudgeBaselineTest" test` passed. `.\mvnw.cmd test` passed with 170 tests, 0 failures, and 4 environment-dependent skips.

## 2026-07-04

- Scope: Frontend refresh recovery for code, settings, and latest judge results.
- Summary: Persisted editor code, run settings, latest judge id, and latest progress in browser storage. On page refresh, the workspace restores cached state immediately and then refreshes the displayed result from `/judge/status/{judgeId}`; unfinished tasks continue polling.
- Data Sources: Existing frontend CodeMirror editors, existing run setting inputs, existing `/judge/status/{judgeId}` endpoint, and existing `JudgeProgress` payloads.
- Integration Status: No new backend API fields added. Restored results are still reconciled against the server status endpoint.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed. Inline script parse check passed. `.\mvnw.cmd test` passed with 171 tests, 0 failures, and 4 environment-dependent skips.

## 2026-07-04

- Scope: Frontend refresh recovery hardening for active judge runs and editor drafts.
- Summary: Restored the running UI state from cached or server progress after refresh, including the results tab, running badge, disabled start button, and progress animation. Added page lifecycle flushes so CodeMirror editor values and run settings are saved on `beforeunload`, `pagehide`, and hidden `visibilitychange`.
- Data Sources: Existing frontend CodeMirror editors, existing browser `localStorage` keys, existing `/judge/status/{judgeId}` endpoint, and existing `JudgeProgress` payloads.
- Integration Status: No backend API changes and no mocked frontend data added. The frontend still reconciles restored state against the real judge status endpoint.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed with 7 tests, 0 failures. Inline script parse check passed with 1 script. `.\mvnw.cmd test` passed with 172 tests, 0 failures, and 4 environment-dependent skips.

## 2026-07-04

- Scope: Failed judge result rendering and generator output-limit classification.
- Summary: Result arrays now normalize statuses such as `System Error` and `OUTPUT_LIMIT_EXCEEDED`, render any non-AC result as a failed judge run, show failed badges/toasts, and include System Error/output-limit counters in the summary. Generator output exceeding the per-case byte cap is now reported as `OUTPUT_LIMIT_EXCEEDED` instead of a generic `System Error`.
- Data Sources: Existing judge task summary payloads, existing `TestCaseResult` statuses, existing frontend result grid, and existing process runner output-limit status.
- Integration Status: No API shape changes. Existing status strings remain accepted while the frontend normalizes display/summary behavior.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest,JudgeBaselineTest" test` passed with 13 tests, 0 failures. Inline script parse check passed with 1 script.

## 2026-07-04

- Scope: Configurable large-case per-test input/output cap.
- Summary: Kept the default per-case generated input/output cap at 1 MiB for trusted-local, made large and production profiles explicitly use 16 MiB, documented the `JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE` deployment override for Windows, Linux, and WSL, and added positive-value validation before task execution and during production startup checks.
- Data Sources: Existing `judge.execution.max-output-bytes-per-case` configuration path, existing task policy resolver, existing production startup validator, and sandbox runbooks.
- Integration Status: No API shape changes. Existing config key remains compatible; deployments can override it through `application.yml` or the Spring environment variable.
- Test Results: `.\mvnw.cmd "-Dtest=ApplicationYamlExecutionProfileTest,TaskPolicyResolverTest,ProductionSecurityStartupValidatorTest,ProductionRunbookDocumentationTest" test` passed with 20 tests, 0 failures. `.\mvnw.cmd test` passed with 178 tests, 0 failures, and 4 environment-dependent skips.

## 2026-07-04

- Scope: Output-limit diagnostics and failed-case detail previews.
- Summary: Added `maxOutputBytesPerCase` to judge creation JSON so the frontend shows the real active per-case cap, fixed output-limit case details to open even when generator failure means user/oracle output files do not exist, and changed details rendering to return bounded 1 MiB previews with truncation flags instead of loading huge files into the browser.
- Data Sources: Existing task policy snapshot, existing test-case artifact files, existing `/details/{judgeId}/{caseNumber}` endpoint, and existing frontend result modal.
- Integration Status: Backward compatible JSON extension. Existing detail fields remain present; new truncation flags are optional for older clients.
- Test Results: `.\mvnw.cmd "-Dtest=ApplicationYamlExecutionProfileTest,JudgeFileServiceTest,JudgeBaselineTest,JudgeControllerPolicyTest,FrontendTemplateContractTest" test` passed with 35 tests, 0 failures. Inline script parse passed with 1 script. `.\mvnw.cmd test` passed with 179 tests, 0 failures, and 4 environment-dependent skips.

## 2026-07-04

- Scope: Frontend refresh-state recovery for the workspace page.
- Summary: Preserved the selected workspace page across refreshes, stopped restored judge results and follow-up polling from forcing a jump to the Test Results page, and guarded CodeMirror restoration so loading generator code no longer overwrites brute-force, SPJ, or user-solution drafts with empty values.
- Data Sources: Existing browser `localStorage` keys, existing CodeMirror editor instances, existing workspace navigation, and existing `/judge/status/{judgeId}` recovery flow.
- Integration Status: No backend API changes and no mocked data added. Restored judge results still reconcile with the real status endpoint.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed with 11 tests, 0 failures.
