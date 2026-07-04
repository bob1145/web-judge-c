package com.example.demo;

import org.junit.jupiter.api.Test;

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
                .contains("const response = await fetch(`/judge/status/${currentJudgeId}`)")
                .contains("handleProgress(cachedProgress, { silent: true, switchView: false })")
                .contains("await restoreLatestJudgeResult();");
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
                .contains("if (isActiveProgress(progress)) {\n                restoreRunningUiState({ switchView: options.switchView !== false });\n            }");
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
                .contains("handleProgress(cachedProgress, { silent: true, switchView: false })")
                .contains("handleProgress(progressData, { silent: true, switchView: false })")
                .contains("startPollingProgress(currentJudgeId, { switchView: false })")
                .contains("restoreRunningUiState({ switchView: options.switchView !== false })");
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
    void caseDetailsRenderTruncatedPreviewWarnings() {
        assertThat(html)
                .contains("details.inputTruncated")
                .contains("details.userOutputTruncated")
                .contains("details.correctOutputTruncated")
                .contains("function truncationNotice(truncated)")
                .contains("仅显示前 1 MiB 预览");
    }
}
