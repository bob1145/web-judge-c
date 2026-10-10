package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendTemplateContractTest {

    private final String html = Files.readString(
            Path.of("src/main/resources/templates/index.html"),
            StandardCharsets.UTF_8
    );

    FrontendTemplateContractTest() throws Exception {
    }

    @Test
    void progressThrottleCancelsPendingFramesAndBypassesDelayForTerminalProgress() {
        String normalizedHtml = html.replace("\r\n", "\n");
        int stopOffset = normalizedHtml.indexOf("function stopProgressTracking()");
        String stopBlock = normalizedHtml.substring(stopOffset,
                normalizedHtml.indexOf("window.addEventListener('beforeunload'", stopOffset));
        int throttleOffset = normalizedHtml.indexOf("function createProgressThrottle(func, limit)");
        String throttleBlock = normalizedHtml.substring(throttleOffset,
                normalizedHtml.indexOf("async function parseCreateResponse", throttleOffset));

        assertThat(stopBlock).contains("throttledHandleProgress.cancel();");
        assertThat(throttleBlock)
                .contains("clearTimeout(timeoutId);\n                timeoutId = null;")
                .contains("if (isTerminalProgress(args[0]) || now - lastCall >= limit)")
                .contains("throttled.cancel = function ()", "lastCall = 0;", "return throttled;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "error"})
    void toastNodesAreParsedBeforePageScriptCapturesThem(String toastKind) {
        int initializationOffset = html.indexOf("const " + toastKind + "Toast = toastAvailable ? new bootstrap.Toast(");
        int toastOffset = html.indexOf("id=\"" + toastKind + "-toast\"");
        int messageOffset = html.indexOf("id=\"" + toastKind + "-message\"");

        assertThat(initializationOffset).isPositive();
        assertThat(toastOffset).isBetween(0, initializationOffset - 1);
        assertThat(messageOffset).isBetween(0, initializationOffset - 1);
    }

    @Test
    void authenticationPreservesServerModeCsrfAndDistinctErrorsWithoutDuplicateEnterSubmission() throws Exception {
        String auth = Files.readString(Path.of("src/main/resources/templates/auth.html"), StandardCharsets.UTF_8);
        assertThat(auth).contains("th:if=\"${accountAuthRequired}\"", "th:unless=\"${accountAuthRequired}\"")
                .contains("credentials.username = usernameInput.value.trim()", "credentials.password = passwordInput.value")
                .contains("credentials.accessCode = accessCodeInput.value.trim()", "JSON.stringify(credentials)")
                .contains("'X-CSRF-TOKEN': csrfToken", "response.status === 401", "response.status === 429")
                .contains("response.status === 403", "response.status >= 500", "result.success === true")
                .contains("document.createTextNode(message)", "if (submitBtn.disabled)")
                .contains("<div class=\"auth-container\">")
                .doesNotContain("<div class=\"auth-container fade-in\">", "form.dispatchEvent(new Event('submit'))",
                        "credentials.password = passwordInput.value.trim()");
    }

    @Test
    void createResponseUsesStructuredJsonAndStillKeepsJudgeIdForStartAndPolling() {
        assertThat(html).contains("async function parseCreateResponse(response)");
        assertThat(html).contains("const createResponse = await parseCreateResponse(response)");
        assertThat(html).contains("currentJudgeId = createResponse.judgeId");
        assertThat(html).doesNotContain("currentJudgeId = await response.text()");
        assertThat(html).contains("renderCreatePolicy(createResponse)");
        assertThat(html).contains("createResponse.maxOutputBytesPerCase");
        assertThat(html).contains("function formatByteLimit(bytes)");
    }

    @Test
    void startChecksHttpAcceptanceAndWebSocketReadinessDoesNotSendAnotherStart() {
        assertThat(html).contains("const startResponse = await fetch('/judge/start/' + judgeId")
                .contains("if (!startResponse.ok)")
                .contains("if (pollAfterStart)")
                .contains("setResultsBadge('准备中', 'idle')")
                .contains("正在提交...");
        int startOffset = html.indexOf("const startResponse = await fetch(");
        int rejectionOffset = html.indexOf("if (!startResponse.ok)");
        int pollingOffset = html.indexOf("if (pollAfterStart)");
        assertThat(rejectionOffset).isGreaterThan(startOffset);
        assertThat(pollingOffset).isGreaterThan(rejectionOffset);
        int connectionOffset = html.indexOf("function connectAndSubscribeAsync(judgeId)");
        int connectionEndOffset = html.indexOf("function smoothProgressUpdate()", connectionOffset);
        assertThat(html.substring(connectionOffset, connectionEndOffset))
                .contains("resolve();", "client.debug = () => {};", "client.connect({ 'X-CSRF-TOKEN': csrfToken },")
                .doesNotContain("/judge/start/", "client.connect({},");
    }

    @Test
    void retryReconcilesTheOriginalJudgeBeforeStartWithoutCreatingAnotherTask() {
        int retryOffset = html.indexOf("if (pendingStart && currentJudgeId)");
        int retryEndOffset = html.indexOf("saveWorkspaceState();", retryOffset);
        String retryBlock = html.substring(retryOffset, retryEndOffset);
        assertThat(retryBlock).contains("await reconcileKnownJudge()", "await startKnownJudge()", "return;")
                .doesNotContain("fetch('/judge'", "forgetLatestJudgeResult()");
        assertThat(retryBlock.indexOf("await reconcileKnownJudge()"))
                .isLessThan(retryBlock.indexOf("await startKnownJudge()"));
        assertThat(html).contains("pendingStart = true", "重试启动原任务")
                .contains("if (startButton.disabled)", "headers: { 'Accept': 'application/json' }")
                .contains("if (!isActiveProgress(progress) && !isTerminalProgress(progress))");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 409, 429})
    void submissionHttpErrorsAreDistinguishedWithoutReflectingServerText(int status) {
        assertThat(html).contains("error.httpStatus === " + status)
                .contains("failure.httpStatus = response.status")
                .contains("failure.requestPhase = requestPhase", "error.requestPhase === 'start'")
                .contains("error.requestPhase !== 'status'")
                .contains("error.httpStatus >= 500")
                .contains("创建结果未知且未取得任务ID；未自动重新提交")
                .doesNotContain("showError(error.message)", "parseInt(error.retryAfter", "Number(error.retryAfter");
    }

    @Test
    void restoredCreatedTaskIsRetryableAndDoesNotStartRunningOrPolling() {
        assertThat(html).contains("normalizeStatus(progressData.status) === 'CREATED'")
                .contains("restorePendingStartUi('任务已创建但尚未启动，可重试启动原任务。')")
                .contains("else if (!isTerminalProgress(progressData))")
                .contains("['QUEUED', 'PENDING', 'COMPILING', 'RUNNING'].includes(normalizeStatus(progress.status))")
                .contains("if (pendingStart) {", "setResultsBadge('已接受/排队', 'running')");
    }

    @Test
    void cancellationUsesTheOriginalIdAndReconcilesWithoutSynthesizingCompletion() {
        int cancelOffset = html.indexOf("cancelStartButton.addEventListener('click'");
        int cancelEndOffset = html.indexOf("function judgeHttpError(", cancelOffset);
        String cancelBlock = html.substring(cancelOffset, cancelEndOffset);
        assertThat(cancelBlock).contains("fetch('/judge/cancel/' + judgeId", "'X-CSRF-TOKEN': csrfToken")
                .contains("typeof cancelResult.accepted !== 'boolean'", "await reconcileKnownJudge()")
                .contains("最终状态以任务查询为准")
                .doesNotContain("fetch('/judge'", "status: 'CANCELLED'", "status: 'COMPLETED'");
        assertThat(html).contains("id=\"cancel-start-button\"", "cancelStartButton.hidden = true")
                .contains("cancelStartButton.disabled = true");
        int acceptedOffset = html.indexOf("if (isActiveProgress(progress) || isTerminalProgress(progress))");
        int acceptedEndOffset = html.indexOf("rememberJudgeProgress(progress);", acceptedOffset);
        assertThat(html.substring(acceptedOffset, acceptedEndOffset))
                .contains("pendingStart = false;", "cancelStartButton.hidden = true;");
    }

    @Test
    void progressChannelsStopAndRejectLateResponsesWithoutOverlappingPolls() {
        assertThat(html).contains("function stopProgressTracking()", "progressPollController.abort()")
                .contains("clearTimeout(progressPollTimer)", "clearTimeout(reconnectTimer)", "socket.close()")
                .contains("window.addEventListener('beforeunload', stopProgressTracking)")
                .contains("stompClient !== client || currentJudgeId !== judgeId")
                .contains("progressPollController !== controller || currentJudgeId !== judgeId")
                .contains("signal: controller.signal", "failures >= maxReconnectAttempts")
                .contains("setTimeout(poll, failures ? 3000 * failures : 1000)")
                .contains("currentJudgeId && error.requestPhase === 'status'")
                .contains("网络或响应异常，任务状态暂时未知；已保留原任务，重试前会先查询状态。")
                .doesNotContain("setInterval(async () =>");
    }

    @Test
    void missingToastComponentHasEscapedVisibleAndDismissibleFallback() {
        assertThat(html).contains("typeof bootstrap.Toast === 'function'")
                .contains("showFallbackToast('success', message)", "showFallbackToast('error', message)")
                .contains("document.querySelector('#error-toast [data-bs-dismiss=\"toast\"]')?.click()")
                .contains("body.textContent = message", "toast.style.display = 'block'")
                .contains("closeButton.onclick", "toast.style.display = 'none'")
                .doesNotContain("body.innerHTML = message");
    }

    @Test
    void highVolumeProgressRendersSummaryWithoutAssumingResultsArrayExists() {
        assertThat(html).contains("function renderSummaryProgress(progress)");
        assertThat(html).contains("if (progress.summary)");
        assertThat(html).contains("renderSummaryProgress(progress)");
        assertThat(html).contains("renderFailureSamples");
        assertThat(html).contains("summary.totalCases");
        assertThat(html).contains("summary.completedCases");
        assertThat(html).contains("summary.firstFailedCase");
        assertThat(html).doesNotContain("if (progressData.results || progressData.status === 'SYSTEM_ERROR'");
    }

    @Test
    void syntheticHighVolumeSummaryHasBoundedRenderedCaseNodes() {
        assertThat(html).contains("const MAX_RENDERED_RESULT_CARDS");
        assertThat(html).contains("results.slice(0, MAX_RENDERED_RESULT_CARDS)");
        assertThat(html).contains("summary.failureSamples || []");
        assertThat(html).contains(".result-case");
        assertThat(html).doesNotContain("results.forEach(result =>");
    }

    @Test
    void serverSuppliedMessagesUseTextContentInsteadOfRawInnerHtml() {
        assertThat(html).contains("function setElementText");
        assertThat(html).contains("resultSummary.textContent");
        assertThat(html).contains("successMessage.textContent");
        assertThat(html).contains("errorMessage.textContent");
        assertThat(html).doesNotContain("showError(error.message)");
    }

    @Test
    void terminalProgressWithoutResultsStillStopsRunningUi() {
        assertThat(html)
                .contains("'CE'")
                .contains("'COMPILE_ERROR'")
                .contains("'SANDBOX_UNAVAILABLE'")
                .contains("'SECURITY_VIOLATION'")
                .contains("'OUTPUT_LIMIT_EXCEEDED'");
        assertThat(html).contains("function renderTerminalProgress(progress, displayText, options = {})");
        assertThat(html)
                .contains("else if (isTerminalProgress(progress))")
                .contains("renderTerminalProgress(progress, displayText, options);")
                .contains("return;");
        assertThat(html)
                .contains("function isFailedTerminalProgress(progress)")
                .contains("completeProgressUi(failed ? '失败' : '已完成', failed ? 'failed' : 'done');");
    }

    @Test
    void refreshRestoresCodeSettingsAndLatestJudgeResultFromRealStatusEndpoint() {
        assertThat(html)
                .contains("const LAST_JUDGE_ID_KEY")
                .contains("const LAST_PROGRESS_KEY")
                .contains("function saveWorkspaceState()")
                .contains("function loadWorkspaceState()")
                .contains("function rememberJudgeProgress(progress)")
                .contains("async function restoreLatestJudgeResult()");
        assertThat(html)
                .contains("localStorage.setItem(LAST_JUDGE_ID_KEY, currentJudgeId)")
                .contains("localStorage.setItem(LAST_PROGRESS_KEY, JSON.stringify(progress))")
                .contains("const response = await fetch(`/judge/status/${currentJudgeId}`,")
                .contains("handleProgress(progressData, { silent: true, switchView: false })")
                .contains("await restoreLatestJudgeResult();");
        String restoration = html.substring(html.indexOf("async function restoreLatestJudgeResult()"),
                html.indexOf("generatorEditor.on('change', saveWorkspaceState)"));
        assertThat(restoration).doesNotContain("cachedProgress", "JSON.parse(cachedRaw)");
        assertThat(restoration.indexOf("if (!response.ok)"))
                .isNotNegative()
                .isLessThan(restoration.indexOf("handleProgress(progressData"));
        assertThat(restoration).contains("if (currentJudgeId !== judgeId)",
                "throw judgeHttpError(response)", "await showJudgeSubmissionFailure(error)");
        assertThat(html)
                .contains("saveWorkspaceState();")
                .contains("document.getElementById('time-limit').value")
                .contains("document.getElementById('memory-limit').value")
                .contains("document.getElementById('test-cases').value")
                .contains("document.getElementById('precision').value");
    }

    @Test
    void refreshPreservesRunningUiAndFlushesCodeBeforeLeavingPage() {
        String normalizedHtml = html.replace("\r\n", "\n");

        assertThat(normalizedHtml)
                .contains("function isActiveProgress(progress)")
                .contains("function restoreRunningUiState(options = {})")
                .contains("if (isActiveProgress(progress)) {\n"
                        + "                restoreRunningUiState({ switchView: options.switchView === true });\n"
                        + "                if (normalizeStatus(progress.status) === 'QUEUED') {\n"
                        + "                    setResultsBadge('排队中', 'running');\n"
                        + "                    startButton.innerHTML = '<i class=\"fas fa-spinner fa-spin\"></i> 排队中...';\n"
                        + "                }\n            }");
        assertThat(normalizedHtml)
                .contains("window.addEventListener('beforeunload', saveWorkspaceState)")
                .contains("window.addEventListener('pagehide', saveWorkspaceState)")
                .contains("document.addEventListener('visibilitychange'")
                .contains("if (document.visibilityState === 'hidden') {\n                saveWorkspaceState();\n            }");
    }

    @Test
    void refreshRestoresEditorsWithoutOverwritingLaterDraftsDuringLoad() {
        String normalizedHtml = html.replace("\r\n", "\n");

        assertThat(normalizedHtml)
                .contains("let isRestoringWorkspaceState = false")
                .contains("if (isRestoringWorkspaceState) {\n                return;\n            }")
                .contains("try {\n                isRestoringWorkspaceState = true;")
                .contains("finally {\n                isRestoringWorkspaceState = false;\n            }");
        assertThat(normalizedHtml)
                .contains("generatorEditor.on('change', saveWorkspaceState)")
                .contains("bruteForceEditor.on('change', saveWorkspaceState)")
                .contains("specialJudgeEditor.on('change', saveWorkspaceState)")
                .contains("userEditor.on('change', saveWorkspaceState)");
    }

    @Test
    void refreshKeepsThePreviouslySelectedWorkspaceView() {
        String normalizedHtml = html.replace("\r\n", "\n");

        assertThat(normalizedHtml)
                .contains("const ACTIVE_WORKSPACE_VIEW_KEY")
                .contains("function savedWorkspaceView()")
                .contains("localStorage.setItem(ACTIVE_WORKSPACE_VIEW_KEY, viewName)")
                .contains("const initialView = loadWorkspaceState();\n            showWorkspaceView(initialView, { persist: false });");
        assertThat(normalizedHtml)
                .contains("handleProgress(progressData, { silent: true, switchView: false })")
                .contains("startPollingProgress(currentJudgeId, { switchView: false })")
                .contains("restoreRunningUiState({ switchView: options.switchView === true })");
    }

    @Test
    void liveRunningProgressDoesNotForceResultsViewAfterManualNavigation() {
        String normalizedHtml = html.replace("\r\n", "\n");

        assertThat(normalizedHtml)
                .contains("showWorkspaceView('results');")
                .contains("restoreRunningUiState({ switchView: options.switchView === true })")
                .doesNotContain("restoreRunningUiState({ switchView: options.switchView !== false })");
    }

    @Test
    void resultArraysWithSystemErrorsRenderAsFailedAndUseNormalizedStatusStats() {
        assertThat(html)
                .contains("function resultStatusKey(result)")
                .contains("function isAcceptedStatus(status)")
                .contains("function hasFailedResults(results)")
                .contains("function collectResultStats(results)")
                .contains("const statusKey = resultStatusKey(result)")
                .contains("const failed = hasFailedResults(results) || isFailedTerminalProgress(progress)")
                .contains("completeProgressUi(failed ? '失败' : '已完成', failed ? 'failed' : 'done')")
                .contains("showError(`判题未通过：${displayText}`)");
        assertThat(html)
                .contains("SYSTEM_ERROR: 0")
                .contains("OUTPUT_LIMIT_EXCEEDED: 0")
                .contains("status-${statusKey.toLowerCase().replace(/_/g, '-')}")
                .contains("stats.SYSTEM_ERROR > 0")
                .contains("stats.OUTPUT_LIMIT_EXCEEDED > 0");
    }

    @Test
    void caseDetailsAcceptTruncationFlagsWithoutNoisyBackendPreviewWarnings() {
        assertThat(html)
                .contains("details.inputTruncated")
                .contains("details.userOutputTruncated")
                .contains("details.correctOutputTruncated")
                .contains("function truncationNotice(truncated)")
                .doesNotContain("仅显示配置上限内的预览内容");
    }

    @Test
    void caseDetailsUseReadableJudgeFocusedLayoutInsteadOfRawPatchChrome() {
        assertThat(html)
                .contains("case-detail-modal")
                .contains("case-detail-compare")
                .contains("function renderCaseDetailCard")
                .contains("function renderOutputDiffTable")
                .contains("function updateDetailsHeader")
                .doesNotContain("Diff.createTwoFilesPatch")
                .doesNotContain("new Diff2HtmlUI");
    }

    @Test
    void caseDiffShowsOnlyMismatchedRowsWithExpectedAndActualColumns() {
        assertThat(html)
                .contains("const MAX_RENDERED_DIFF_ROWS = 80")
                .contains("const differingRows = []")
                .contains("lineNumber: index + 1")
                .contains("differingRows.slice(0, MAX_RENDERED_DIFF_ROWS)")
                .contains("<span>错误行</span>")
                .contains("<span>标准答案</span>")
                .contains("<span>你的输出</span>")
                .contains("const expectedLines = splitOutputLines(expectedOutput)")
                .contains("const actualLines = splitOutputLines(actualOutput)")
                .doesNotContain("clipDetailText(expectedOutput")
                .doesNotContain("clipDetailText(actualOutput")
                .doesNotContain("MAX_DETAIL_DIFF_CHARS")
                .doesNotContain("差异对比仅分析前")
                .doesNotContain("const rowClass = expectedLine === actualLine ? 'is-same' : 'is-different';");
    }

    @Test
    void caseDetailsClipLargeTextBeforeRenderingInBrowser() {
        assertThat(html)
                .contains("const MAX_DETAIL_CARD_CHARS = 2000")
                .contains("const MAX_DIFF_CELL_CHARS = 2000")
                .contains("function clipDetailText(value, maxChars)")
                .contains("仅渲染前")
                .contains("clipDetailText(content, MAX_DETAIL_CARD_CHARS)")
                .contains("clipDetailText(value, MAX_DIFF_CELL_CHARS)")
                .doesNotContain("MAX_DETAIL_DIFF_CHARS");
    }

    @Test
    void runningProgressDoesNotDuplicateServerSuppliedCaseCounts() {
        assertThat(html)
                .contains("function hasExplicitCaseCount(text)")
                .contains("function runningDisplayText(progress, displayText, totalTestCases)")
                .contains("if (hasExplicitCaseCount(displayText)) {\n                return displayText;\n            }")
                .doesNotContain("displayText = `${message || status} (${completedCases}/${totalTestCases})`");
    }

    @Test
    void frontendDefaultsToStopOnFirstNonAcceptedAndOffersFailedCaseDownload() {
        assertThat(html)
                .contains("id=\"stop-on-first-non-ac\"")
                .contains("stopOnFirstNonAc: document.getElementById('stop-on-first-non-ac').checked")
                .contains("localStorage.setItem('stopOnFirstNonAc'")
                .contains("id=\"download-failed-btn\"")
                .contains("function updateFailedDownloadButton(hasFailures)")
                .contains("downloadFailedBtn.href = `/download/${currentJudgeId}/failed`")
                .contains("updateFailedDownloadButton(failed)")
                .contains("updateFailedDownloadButton(Boolean(summary.firstFailedCase))");
    }

    @Test
    void summaryProgressHidesAllCaseDownloadBecauseArchiveMayBeUnavailable() {
        assertThat(html)
                .contains("function updateAllDownloadButton(canDownload)")
                .contains("updateAllDownloadButton(false)")
                .contains("updateAllDownloadButton(results && results.length > 0)")
                .doesNotContain("if (downloadAllBtn && currentJudgeId) {\n                downloadAllBtn.href = `/download/${currentJudgeId}/all`");
    }
}
