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

## 2026-07-04

- Scope: Stop-on-first-non-AC workflow and failed-case artifact download.
- Summary: Added a persisted frontend switch for stopping after the first non-AC result, wired the flag through local and sandbox judge execution, exposed a failed-case ZIP download, removed duplicated running case counts in the progress text, and made the sandbox runner keep `.in`, `.out`, and `.ans` artifacts for non-AC cases with consistent LF line endings.
- Data Sources: Existing judge request payload, task policy snapshot, sandbox task spec, persisted judge summaries, and existing `/download/{judgeId}/...` artifact endpoints.
- Integration Status: Backward compatible request extension. The new `stopOnFirstNonAc` field defaults to false for missing backend/sandbox specs, while the frontend switch defaults on for the differential-testing workflow.
- Test Results: `.\mvnw.cmd "-Dtest=CaseBatchRunnerTest,JudgeFileServiceTest,FrontendTemplateContractTest,TaskRunnerArtifactContractTest,JudgeBaselineTest,JudgeSandboxOrchestrationTest" test` passed with 53 tests, 0 failures. `python -m unittest runner.tests.test_task_runner_contract` passed with 2 tests. Inline script parse passed with 1 script.

## 2026-07-04

- Scope: Running-progress workspace navigation behavior.
- Summary: Stopped live RUNNING progress updates from forcing the workspace back to the Test Results page after the user manually navigates elsewhere. Submitting a new judge run still switches to Test Results once at the start.
- Data Sources: Existing frontend workspace navigation state, WebSocket progress handler, and polling progress handler.
- Integration Status: No backend API changes. Existing progress payloads continue to update the running UI and result badge without taking over navigation.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed with 14 tests, 0 failures.

## 2026-07-04

- Scope: Failed/high-volume result download button behavior.
- Summary: Hid the "download all test cases" action for summary-style results where the backend archive may be unavailable, such as high-volume or stopped-early runs, while keeping the non-AC case ZIP download available when a failed case exists.
- Data Sources: Existing frontend result summary payloads and existing `/download/{judgeId}/failed` and `/download/{judgeId}/all` endpoints.
- Integration Status: No backend API changes. The frontend now only exposes the full archive action for ordinary rendered result arrays.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed with 15 tests, 0 failures.

## 2026-07-04

- Scope: Test case detail modal readability.
- Summary: Reworked the case detail modal into a judge-focused layout with a clear status header, input/output cards, side-by-side expected versus actual output, and a bounded line-by-line diff table for WA cases. Removed the old diff2html/jsdiff patch viewer resources.
- Data Sources: Existing `/details/{judgeId}/{caseNumber}` payload fields and existing test case result metadata.
- Integration Status: No backend API changes. The modal still consumes the same detail endpoint and preserves input download behavior.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed with 16 tests, 0 failures. Inline script parse passed with 1 script.

## 2026-07-04

- Scope: Configurable test-case detail preview limit.
- Summary: Added `judge.execution.max-detail-preview-bytes` / `JUDGE_EXECUTION_MAX_DETAIL_PREVIEW_BYTES` so the details modal preview cap is configurable instead of hard-coded to 1 MiB, while still respecting each task's `max-output-bytes-per-case`. Updated the truncation notice to avoid a stale fixed-size label.
- Data Sources: Existing `/details/{judgeId}/{caseNumber}` endpoint, `ExecutionProperties`, production startup validation, and Windows/Linux/WSL deployment runbooks.
- Integration Status: Backward compatible configuration addition. Default preview remains 1 MiB to protect the browser; full large files should still be obtained through failed-case downloads.
- Test Results: `.\mvnw.cmd "-Dtest=JudgeFileServiceTest,JudgeFileServiceProductionTest,ApplicationYamlExecutionProfileTest,ProductionSecurityStartupValidatorTest,FrontendTemplateContractTest" test` passed with 47 tests, 0 failures, and 1 platform-dependent skip. Inline script parse passed with 1 script. `git diff --check` passed with Windows CRLF warnings.

## 2026-07-04

- Scope: Test-case detail diff readability and browser render limits.
- Summary: Changed the output diff table to show only mismatched rows with the failing line number, expected answer, and user output. Reduced the default detail preview cap from 1 MiB to 64 KiB and added frontend-side clipping for large detail cards and diff analysis to avoid freezing the browser.
- Data Sources: Existing `/details/{judgeId}/{caseNumber}` payload fields, frontend case detail modal renderer, and `judge.execution.max-detail-preview-bytes` configuration.
- Integration Status: No API shape changes. Full large data points remain available through failed-case downloads while the modal focuses on bounded diagnosis.
- Test Results: `.\mvnw.cmd "-Dtest=JudgeFileServiceTest,JudgeFileServiceProductionTest,ApplicationYamlExecutionProfileTest,ProductionSecurityStartupValidatorTest,FrontendTemplateContractTest" test` passed with 49 tests, 0 failures, and 1 platform-dependent skip.

## 2026-07-05

- Scope: Output diff comparison order.
- Summary: Changed the WA output diff flow to compare the full backend-provided preview first, then truncate only the displayed diff rows/cells. Removed the misleading `差异对比仅分析前 ... 字符` warning and kept the 2000-character per-cell render cap.
- Data Sources: Existing frontend case detail modal renderer and `/details/{judgeId}/{caseNumber}` payload fields.
- Integration Status: No API changes. The modal still uses bounded backend previews and failed-case downloads remain the path for full large artifacts.
- Test Results: `.\mvnw.cmd "-Dtest=FrontendTemplateContractTest" test` passed with 18 tests, 0 failures.

## 2026-10-02 — TASK-02

- Scope: Shared HTTP session credentials, safe authentication defaults, and credential-free application logs.
- Summary: Cookie/Header resolution now rejects ambiguity and never authenticates query sessionId; all four HTTP callers share the resolver. Removed usable YAML/Java fallback credentials and the unused Spring default-user auto-configuration. Local access-code mode fails closed without valid explicit material; account mode validates enabled BCrypt accounts without depending on an unused access code. Default bind address is loopback.
- Integration Status: Existing HTTP paths, response fields, account-mode selection, ownership and quota identities remain intact. Session logs use SHA-256 audit identifiers; access-code audit userId and credential-bearing detail keys are redacted. Program clients must use X-Session-ID rather than URL credentials.
- Test Results: Final targeted run R08 passed 83/83; full Maven run R09 passed 260 of 262 with 0 failures/errors and the 2 existing provider-environment skips. Earlier compile failures, red regressions and corrected HTTP contract assumptions remain documented in docs/acceptance/TASK-02 and docs/work.md.
- Risks/Migration: Explicit external authentication material is now required. Historical log handling/session rotation require separate approval. Proxy/rate limits, secure cookies/CSRF, WebSocket authorization and production-provider acceptance remain follow-up tasks; this change is not a production release approval.

## 2026-10-02 — TASK-08

- Scope: Remote Worker configuration, HTTPS enforcement, and rejection of unsupported nominal mTLS.
- Summary: Primary worker settings, nested sandbox settings and Runner Options now share validation. Conflicting nonblank values fail closed; nested HMAC settings are supported consistently. HTTP/credential-bearing or malformed endpoint URLs and any configured mTLS certificate string are rejected. The nominal X-Judge-Worker-Mtls header is removed and redirects are explicitly disabled.
- Integration Status: Existing Bearer/HMAC signature input and v1 request/response contracts remain unchanged. Startup validates the actual merged Worker configuration, and the runner's intended Spring constructor is selected explicitly. Production uses standard Java TLS trust/hostname checks; custom test CA trust is injected only through a package-private test seam.
- Test Results: R05 targeted 49/49 passed, including 18 real loopback TLS scenarios. R06 full regression had one RE-versus-SYSTEM_ERROR failure outside the modified Worker path; unchanged targeted reproduction passed in R07 and unchanged full rerun R08 passed 295 of 297, with 0 failures/errors and 2 existing provider skips. The earlier failure remains an unresolved stability risk, not a claimed fix.
- Risks/Migration: Configure a final HTTPS endpoint and matching settings rather than relying on precedence or redirects. mTLS remains unsupported. Temporary local protocol fixtures do not establish external Worker verification, replay protection or provider safety; TASK-26/29 evidence is still required. See docs/acceptance/TASK-08 and docs/work.md for commands, hashes and retained failure history.

## 2026-10-03 — TASK-09

- Scope: Versioned runner manifests, provider path namespaces, attempt-local outputs and explicit capability negotiation.
- Summary: Keep the native host SandboxTaskSpec; send v2 relative-source manifests with millisecond/byte units, comparison strings and retention. Validate before materialization, preserve host audit/summary and old attempts, and bind handles to the negotiated version/attempt. Remote submissions include actual Base64 source bytes after capability checks; HTTP paths and authentication/signing stay unchanged.
- Compatibility: Upgrade host/artifact/Worker together; old protocols and legacy control filenames fail closed. Python currently supports only explicit LEGACY_TEXT/SPJ, not DECIMAL_ABSOLUTE. Ordinary container comparison remains unavailable until TASK-16; lossless API precision remains TASK-15. This is not provider isolation or release acceptance.
- Tests: Final targeted 124 (122 pass, 0 failure/error, 2 original environment skips); full 360 (358 pass, 0 failure/error, 2 original environment skips). Actual Python suite 11/11. Evidence and retained failure history: docs/acceptance/TASK-09. Source/input/harness hashes verified; no production deployment or data migration.


## 2026-10-03 — TASK-10 development stage (not accepted)

- Scope: Fail closed before enabling the current shared-control-plane Linux adapter; full independent execution units and real isolation acceptance remain unfinished.
- Changes: Add controlPlaneIsolated with a false default. The legacy Linux adapter cannot declare production safety or start non-trusted-local tasks. Linux production startup consumes the actual selected runner probe and rejects absent, failed or contradictory capabilities regardless of manually configured probe flags. Probe exception details are not surfaced. Other providers gain no new safety certification.
- Verification: R01 retained two expected failures. R02 targeted 115/116 passed with one environment skip; R03 full 387/389 passed with two environment skips and no failures/errors, plus actual Python 11/11. Source/input/harness hashes match; evidence is in docs/acceptance/TASK-10. Contract fixtures do not prove container isolation.
- Deployment impact: Current Linux production startup is intentionally unavailable until the real isolation implementation and acceptance exist. Do not bypass by selecting trusted-local, restoring a config-only gate, or claiming the pending task complete. No production deployment, environment mutation or task commit was performed.

## 2026-10-03 — TASK-10 dedicated environment and executor image (not accepted)

- Added a dedicated-runtime acceptance harness with explicit resource, namespace and mount-root checks; ENV-R15/R16 benign preflight passes, and failed attempts remain archived.
- Added a digest-pinned offline executor Dockerfile, signed APK hash lock/replay, and bounded image export. BUILD-R05 builds and executes a fixed trusted C++17 program; the new image is not a trusted supervisor or a production-ready full runner.
- Helper tests increased to 18 with archive traversal/link/duplicate/missing/hash negative cases. No new Java full-regression or adversarial role acceptance is claimed.
- No shared containers or global dependencies were changed; the earlier accidental containerd invocation remains documented in environment.md. TASK-10 remains IN_PROGRESS with no task commit or defect closure.

## 2026-10-03 — TASK-10 Windows fail-closed parity (not accepted)

- The legacy Windows adapter now rejects non-trusted-local starts before side effects and cannot report control-plane isolation or production safety. Both container profiles share actual-runner capability validation; manual probe flags cannot override missing or inconsistent evidence.
- R04 targeted Java regression: 87 collected, 85 passed, zero failures/errors, two existing real-provider environment skips; all 51 startup-validator tests passed. This is contract verification, not real Windows isolation acceptance.
- Windows and general production runbooks explicitly retain the disabled production gate. No production enablement, task commit or defect closure.

## 2026-10-03 — TASK-10 persistent unknown-create quarantine and supervisor experiments

- Persist stage creation UNKNOWN before runtime calls and retain incomplete or invalid journals across executor instances; bounded validation and directory locking reject unsafe reuse. This is not automatic crash recovery.
- Disable Docker volume copy-up for both stage mounts. Real late-create inspection confirms NoCopy and stable scratch ownership without granting DAC capabilities.
- Dedicated offline supervisor build R03 and container run R04 pass bounded experimental acceptance; delayed-create RECOVERY-R02 passes quarantine checks, with cleanup performed by the harness only. Earlier failed rounds remain recorded.
- Full Java R07 remains red: 418 tests, 415 passed, one scheduler rejection failure, two environment skips; Python 118 passed. Production gates remain closed, no task commit or defect closure.

## 2026-10-03 — TASK-10 managed-attempt evidence retention

- FileTaskStore refuses automatic task deletion when managed stage journals, supervisor configuration or runtime-state markers exist. Unsafe managed parent paths fail closed; legacy manifest-only cleanup remains supported. This conservatively retains completed managed attempts too, pending trusted task-level recovery.
- Test-first evidence: 16 expected failures before the fix; 32 targeted tests pass afterward. Full R08: 439 Java tests, 437 passed, two existing environment skips; Python 118 passed.
- A separate private-snapshot diagnostic deterministically reproduces the existing zero-capacity executor handoff rejection. Its successful result means the defect exists, not that it is fixed; production scheduler remains unchanged and release remains blocked.


## 2026-10-03 — TASK-10 explicit Java supervisor launch contracts (not accepted)

- Added opt-in supervisor configuration, bounded CLI execution, durable runtime journal and launch integration; create is UNKNOWN before side effects and CONFIRMED only after strict identity/configuration inspection. No production defaults or capability enablement.
- Retain task-level locks and all managed evidence. Stop records pending cleanup; cancellation still reports incomplete recovery rather than claiming resource reclamation.
- Preserve R01 configuration-comparison errors and R03 inspect-type red baseline (60 failures). Exact JSON type checks retain historical Docker null/omitted-field compatibility; R04 passes 426/427 with one existing environment skip.
- Latest R10: Java 817 collected, 815 passed, zero failures/errors, two existing environment skips; Python 118 passed with no skips. All 171 tested source hashes match. Earlier scheduler failure remains independently reproduced and unresolved.
- Real Java runtime acceptance, persistent recovery, cross-process directory freeze and real cancellation remain incomplete. No task commit, defect closure or release approval.


## 2026-10-03 — TASK-10 real Java supervisor entry (partial acceptance)

- Freeze an offline runtime bundle from actual resolved dependencies; reject unsafe entries and omit only the evidenced single terminal empty classpath token, never implicitly including cwd. No Maven cache or host home binding inside the private runtime.
- JAVA-SUPERVISOR-R03 passes real Java start/poll, six stages and events, AC1, unchanged trusted controls and independent container/volume inspection; unit exits zero in 74.739 seconds. Helper suites pass 64+13+7 with zero skips.
- Preserve failed bundle and runtime rounds. Fixes after full R10 affect harness only: module-aware Jackson mapping and exact recognition of the product runtime-client directory.
- Product recovery, authoritative UNKNOWN barriers, cross-process deletion freeze and real cancellation remain pending; dedicated domain teardown is not product recovery. No production enablement, task commit or defect closure.

## 2026-10-03 — TASK-10 stable lifecycle admission and refreshed real Java evidence

- Add a stable storage-root file lock shared by FileTaskStore writes/deletion, launcher task-lock acquisition and JudgeService source preparation. Use admission-before-task-lock ordering, revalidate task identity, and reject implicit resurrection after deletion. All managed markers still block deletion.
- Preserve lifecycle red baselines; LIFECYCLE-R05 passes 245 tests without skips, including real child-JVM contention. Full R11 remains red: 868 Java tests, 865 passed, one executor-rejection failure, two existing environment skips; Python 118 passed. All 174 lifecycle-version source hashes match. Do not claim the scheduler defect is fixed.
- Rebuild JVM-BUILD-R06 from 171 input files; independently verify 279 runtime assets and archive/manifest hashes. JAVA-SUPERVISOR-R04 passes real start/poll, six stages/events, AC1 and independent inspection in 76.091 seconds using that new bundle. Helper suites pass 64+13+7 without skips.
- Real runtime coverage concerns launcher admission, not the full HTTP/Store/JudgeService chain. Dedicated-domain teardown is not product recovery; generation barriers, recovery credentials and real cancellation remain pending. Legacy source-leaf/link behavior and SPJ parameter removal are not fully frozen. No production enablement, task commit or defect closure.

## 2026-10-03 — TASK-10 strict retained-state classification and repeated stop evidence

- Validate Java v1 runtime snapshots against the 13 states actually written by the launcher before stop performs any runtime call or journal mutation. Preserve exact configuration and independent ownership checks; no recovered/delete-permission state is introduced.
- Preserve 43 malformed-state red cases and four path-contract red cases. Reuse one strict Options path validator across writer and reader so NBSP/DEL paths cannot create resources that the stop reader later rejects. Final targeted R04 passes 956 tests without skips, including the actual persisted writer sequence.
- Full R13 remains red: 1457 Java tests, 1454 passed, one existing executor-rejection failure, two environment skips; Python 118 passed. All 176 tested source hashes match. No scheduler change or disabled assertion.
- JVM-BUILD-R07 independently verifies 173 inputs and 281 runtime assets. JAVA-SUPERVISOR-R05 passes real start/poll and two post-completion stop calls in 71.605 seconds; pending recovery remains explicit, journal identities and trusted files remain unchanged, and independent inspection confirms resources are retained.
- This does not verify active-workload cancellation, authoritative UNKNOWN termination or complete resource recovery. Production gates and managed-marker deletion retention remain unchanged; no task commit, push or defect closure.


## 2026-10-03 — TASK-10 bounded persistent discovery and fresh-JVM evidence

- Add opt-in retained-attempt discovery with explicit entry/report/read/path budgets and non-creating, non-waiting lifecycle admission. Reuse strict journal classification; never invoke runtime commands, read Python stage journals, mutate business state or issue deletion credentials.
- Preserve summary-layout and unstarted-task red baselines. DISCOVERY-R03 passes 1082 tests without skips; full R14 remains red with 1516 Java tests, 1513 passed, one executor-rejection failure and two environment skips. Python passes 118; 178 tested sources match for both rounds.
- Fix the acceptance inventory depth-six blind spot with a real depth-seven file-change regression and complete count-bounded traversal. Preserve compile/red rounds R08-R10; JVM-BUILD-R11 passes 2 tests and verifies 175 inputs plus 288 runtime assets.
- JAVA-SUPERVISOR-R06 uses the new bundle, exits zero in 69.228 seconds and verifies real start/poll, two post-completion stops and separate-JVM discovery without handle/attempt. Parent/child PIDs differ; 36 before/after inventory entries match. Helpers pass 66+13+7.
- Discovery reports RECOVERY_REQUIRED/SUPERVISOR_STOPPED and frozen-stage inspection still required. Resources remain retained; complete enumeration is not recovery or delete permission. Active cancellation, authoritative UNKNOWN termination and full reclamation remain pending; no production enablement, task commit, push or defect closure.


## 2026-10-03 (UTC) — TASK-10 durable stage admission closure

- Add explicit closure of an existing trusted stage-journal under the same nonblocking directory flock used by all stage entries. Persist pending before payload publication, retain interrupted evidence, verify directory identity, and never turn closure into an UNKNOWN barrier or deletion credential.
- Preserve the 140-method/42-subtest-failure red baseline; the 142-test Python suite passes without skips. Full R15 remains red: 1516 Java tests, 1513 passed, one executor-rejection failure and two environment skips; Python 142 passes and 178 source hashes match.
- Freeze and verify SUPERVISOR-BUILD-R04 and JVM-BUILD-R12. Keep R07 runtime success explicitly limited because its verifier did not independently bind marker content; preserve the three validator red cases and the 74-test green result.
- Final JAVA-SUPERVISOR-R08 passes in 76.516 seconds: six real stages, repeated stop, fresh-JVM discovery, harness-invoked repeated product closure, and fresh-Python-process denial of eight role/phase entries. Canonical marker bytes, identity and six retained stage journals remain independently checked; helper suites pass 74+13+7. No runtime/process callback is invoked during denial; this is not eight Docker requests.
- Java recovery integration, early missing-journal closure, authoritative UNKNOWN termination, full resource reclamation and active cancellation remain pending. Dedicated-domain teardown is not product recovery; no production enablement, commit, push or defect closure.

## 2026-10-03 (UTC) / 2026-10-04 (Asia/Shanghai) — TASK-10 native stage directory flock

- Add a Linux x86-64 JNA directory-flock guard with explicit owner/path/ABI checks, trusted procfs identity checks, synchronized lease lifecycle and close-on-exec verification; no marker writes, runtime calls or recovery authorization.
- Preserve the three-error red baseline; verify target headers and a compiled C ABI probe. The 21-case focused suite passes without skips, with real Python/JVM contention and child-JVM termination; 180 source hashes match.
- Full R16 remains red: 1537 Java tests, 1534 passed, one existing executor-rejection failure, two environment skips; Python 142 passes. Keep the failure and independent review limitations rather than claiming product recovery.
- Add a reusable project Goal and task handoff snapshot. Keep TASK-10 in progress and production disabled; the old R12 Java bundle does not contain the new guard. No commit or push.

## 2026-10-04 — TASK-10 stage recovery preparation consumer

- Add a strict single-snapshot consumer for every Python stage-journal writer shape (14 creation/state combinations, 20 structural shapes) with bound identity and no deletion/recovery/barrier semantics, plus descriptor-relative entry IO whose admission publication is byte-identical to close_existing_stage_admission (exclusive pending, fsync file and directory, atomic rename, re-read reverification).
- Wire the trusted recovery preparation consumer: short root admission, task lock, root release, nonblocking stage directory flock, strict runtime-journal classification with configuration matching, bounded entry enumeration, then durable closure. Zero runtime calls; interrupted publications and corrupted markers are retained, never repaired.
- Preserve the unified-unavailable red baseline (84 tests: 51 passed, 22 failures, 11 errors; passing negatives not claimed as verified) and probe dead-ends (pipe EOF deadlock, probe import order). Green R02 passes 82/82 without skips, including byte-for-byte Python interop in both directions and repeated-closure identity retention.
- Full R17 remains red: 1619 Java tests, 1616 passed, one existing executor-rejection failure, two environment skips; Python 142 passes. 191 source hashes match; post-hoc directory replacement and enumeration-by-path boundaries are recorded rather than claimed solved.
- Early creation freeze, authoritative UNKNOWN termination, ownership reclamation, deletion credentials, active cancellation and the rebuilt JVM bundle integration remain pending. No production enablement, commit, push or defect closure.

## 2026-10-04 — TASK-10 host-side early creation entry freeze

- Freeze the supervisor start entry on any retained managed attempt directory, closing the early crash windows (empty attempt directory, manifest-only or partially written residue) that previously escaped the five-marker rejection list and left untracked evidence while allowing new attempts.
- This deliberately reverses the legacy manifest-only allowance: such layouts are indistinguishable from managed early-crash remains and now require recovery first. Red R03 keeps five real failures including a full simulated-runtime pass under residue; green R04 passes all four affected suites; full R18 keeps only the existing executor-rejection failure.
- The freeze blocks host-side creation only; authoritative UNKNOWN server-side termination, cross-process deletion freeze, ownership reclamation, deletion credentials and active cancellation remain pending on explicit production decisions.

## 2026-10-04 — TASK-10 real-domain Java recovery preparation integration

- Rebuild the frozen JVM bundle with the native guard and recovery components (R13, then R15 after a probe argument fix) and extend the real-domain supervisor probe with a fresh-JVM --prepare-only mode that strictly consumes the six real stage journals, publishes the canonical closed admission marker twice idempotently, and proves the storage delta is exactly the marker.
- Adapt the real-domain admission harness: the marker must already be published by the Java preparation (exact 90-byte comparison), python closure runs only the idempotent recheck path, and marker identity stays equal across Java publication, both python rechecks and the rejection child. Eight role/phase denials and inventory invariants are retained; validators pass 74+13+7.
- JAVA-SUPERVISOR-R09 exits zero end to end in the dedicated real domain; full R19 keeps only the existing executor-rejection failure (TASK-18) and the two environment skips, Python 142/142.
- Authoritative UNKNOWN termination, cross-process deletion freeze, ownership reclamation, deletion credentials and active cancellation remain pending on explicit production decisions. No commit or push.

## 2026-10-06 — TASK-10 retain markerless attempt evidence

- Refuse task-directory deletion whenever control/attempts contains any entry, under the existing stable lifecycle guard. Do not infer absence of managed resources from missing runtime markers or a missing startup lock.
- Deliberately change legacy manifest-only cleanup from deletion to retention; this may retain storage until trusted recovery exists. Empty control/attempts roots without attempts remain deletable. Do not use manual marker removal as recovery.
- Preserve CLEANUP-R03 (53 cases, eight red failures) and R04 (386 cases, five obsolete legacy-deletion expectations). R05 passes all 389 related cases, including real child-JVM retention and ordinary-task deletion; a successful mock cleanup callback is not a deletion credential.
- Full R20: 1635 Java tests, 1632 passed, one existing executor-rejection failure and two environment skips; Python 142 passes. Both green rounds independently match 191 source hashes. No UNKNOWN barrier, resource reclamation, release enablement, commit or push is claimed.
- Refresh the Goal continuation pointer and add the October 6 handoff, preserving the October 4 snapshot as historical rather than redoing completed recovery preparation.

## 2026-10-06 — TASK-10 atomic stage journal replacement

- Replace the prepared fail-closed API with real descriptor-relative stage writes under the existing directory flock; bound and clone inputs, require trusted UID/0600/single-link records, revalidate original bytes/identity and temporary/publication objects, and check all new native results.
- Retain a publication FD across rename, permit its legitimate ctime update, and reread the same object and bytes after directory fsync. Close once, preserve cause/suppressed errors, and never unlink or roll back ambiguous evidence.
- Preserve actual R01: six cases, three passed and three placeholder errors. R03 passes all 117 new cases, including 66 real-file/native-wrapper faults, post-rename acknowledgement loss and 18 actual Python writer/reader interoperability cases.
- R22: 1752 Java cases, 1749 passed, one existing TASK-18 executor-rejection failure and two environment skips; Python 142 passes. Twelve related suites pass all 586 cases. Final R03/R22 independently match 194 source hashes; earlier R21 differs only in the two subsequently added fault cases.
- Refresh acceptance/review, task/work and Goal/handoff. This storage primitive is not a JSON transition, runtime ownership, UNKNOWN barrier, resource reclamation or deletion credential. No release enablement, commit, push or shared-daemon stop/restart; rebuild the old R15 runtime package before future real integration.

## 2026-10-06 — TASK-10 retained CONFIRMED container reclamation

- Add a persistent full-ID consumer with no live handle dependency; reuse lifecycle admission, task/runtime/stage locks, strict journal parsing and atomic stage replacement. Stop the supervisor before stages, remove supervisor then holder, and persist intent before every operation.
- Extend runtime v1 from 13 to 19 accepted snapshots for containers only. Retain the CREATED volume and CLEANUP_PENDING global state, all control evidence and real stage outcomes; return no task-deletion credential. Old bundles reject new values and fail closed on rollback.
- Validate exact stage identity, labels, image, UID/GID, limits and read-only input/writable scratch volume subpaths. Use bounded successful full-ID list/inspect for absence; nonzero CLI results, malformed output and empty lists do not resolve UNKNOWN.
- Fix non-default FileSystem Path aliases before string conversion or resolution in Launcher recovery/stop and Runner recovery. Preserve the actual three-case ZIP filesystem red round and root-cause repair; do not alter the String spec contract.
- CONFIRMED-RECOVERY-R05 passes 1553 cases without skips; R24 has 2359 Java cases, 2356 passed, one existing TASK-18 failure and two environment skips; Python passes 142. Both final rounds independently match 198 source hashes. Stateful CLI regression is not real Docker recovery; rebuild the old R15 package for fresh-JVM private-domain acceptance. No provider release, commit, push or shared-daemon stop/restart.


## 2026-10-06 — TASK-10 truthful container recovery and real fresh-JVM acceptance

- Replace false stage CLEANED claims with REMOVED for container-only recovery; retain original CLEANED and NOT_REQUESTED bytes/outcomes. R06 proves the two old failures; R07 passes 1555 cases.
- Replace deprecated Docker stop --time with canonical -t 0 in both consumer stops and the existing stop entry. R10 preserves the real failed stop echo and STOP_REQUESTED; R08 proves both parameter failures, R09 passes 1557 cases without skips. Do not strip warnings or weaken identity assertions.
- R26: 2363 Java cases, 2360 passed, the existing TASK-18 executor-rejection failure and two environment skips; Python 142 passes. R09/R26 each match 198 current product/test sources; these are historical executions, not this record-update run.
- JVM-BUILD-R17 freezes 195 build inputs and 306 assets. JAVA-SUPERVISOR-R11 exits zero: four fresh JVMs reclaim completed and supervisor-SIGKILL orphan containers and retry without mutations; all actual stop/rm responses are complete 64-hex IDs plus newline. Retain volumes/control evidence and CLEANUP_PENDING, no deletion credential.
- This is not active cancellation, same-failure-site retry, volume continuity, UNKNOWN termination or full TASK-10 acceptance. Domain teardown is not product volume cleanup. New semantic/CLI code has only explicit primary-agent review, not new independent review; no release, commit, push or shared-daemon stop/restart.


## 2026-10-06 — TASK-10 same-scene fresh-JVM recovery after real CLI failure and response loss

- Extend only the acceptance Probe and validators: execute a real Docker CLI connection failure at a private missing socket; inject IOException only after actual successful full-ID supervisor/stage rm. Retain STOP_REQUESTED, REMOVAL_REQUESTED or stage REMOVING and original IDs/outcomes.
- R18 offline build passes 2/2 and independently verifies 195 inputs/306 assets. R13 real private-domain run exits zero: seven recovery JVMs, three exact failed-after to next-before snapshot links, eleven successful complete-ID mutations and one actual failed CLI connection; independent later JVMs retry without mutations. Retain volumes/control evidence, no task-deletion credential.
- Preserve R12 preflight failure caused by the old exact 79-test entrypoint count despite 88 passing cases; update to exact 88, without weakening assertions. R03 tests both loader and real entrypoint; R13 auxiliary 88+13+7 pass. Product sources remain unchanged and match historical R09/R26 snapshots.
- This is not a real daemon failure, all-step failure/ineffective-success coverage, active cancellation, UNKNOWN or volume/deletion recovery. New independent review remains pending; no release, commit, push, shared-daemon stop/restart or production-default changes.


## 2026-10-06 — TASK-10-C01：真实活跃取消旧产品红测

- 新Java `--cancel-active-only`真实同JVMstart/cancel，保存实际flock、容器/目录/marker和stop前owner证据。R19离线编译2/2、195源/306资产核验；产品未改、无HTTP/schema/DB迁移。
- 预检非法-extra后缀未拒绝的R05红保留，修后R06双入口93/93；失败前保存四份原始快照并新增一项回归后R07双入口94/94。R14严格容器快照漂移未定位，不放宽断言或以R15重跑覆盖失败。
- R15 unit1/108.029503秒真实旧产品红测：JVM3901取消后USER stage仍Running/Pid4910，marker/CANCELLED/目录清理缺失；只有supervisor stop，pending语义正确。94+13+7辅助通过不是产品取消通过，完整证据见`docs/acceptance/TASK-10/active-cancellation.md`。
- 下一修复为可信marker、合作退出、Runner接入及Discovery严格marker发现，新产品必须新测试/包/真实域。生产参数不臆造，原预算不提高，共享daemon不操作。Goal仍active/5/30、TASK-10未提交，不关闭DEF-05/N2，不发删除凭据。
