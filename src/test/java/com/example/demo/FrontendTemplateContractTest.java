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
}
