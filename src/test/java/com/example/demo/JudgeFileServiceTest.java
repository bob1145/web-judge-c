package com.example.demo;

import com.example.demo.dto.TestCaseDetail;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeFileService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeFileServiceTest {

    @TempDir
    Path tempDir;

    private FileTaskStore taskStore;
    private JudgeFileService service;
    private Path canaryFile;

    @BeforeEach
    void setUp() throws IOException {
        taskStore = new FileTaskStore(new ObjectMapper(), tempDir.resolve("storage"));
        service = new JudgeFileService(taskStore);
        canaryFile = tempDir.resolve("outside-canary.txt");
        Files.writeString(canaryFile, "SECRET_CANARY");
    }

    @Test
    void readsOnlyRequestedCaseDetailsWithBoundedFields() throws Exception {
        Path workDir = createTask("valid-detail", 2, false, 64);
        Files.writeString(workDir.resolve("1.in"), "input-1");
        Files.writeString(workDir.resolve("1.out"), "user-1");
        Files.writeString(workDir.resolve("1.ans"), "answer-1");
        Files.writeString(workDir.resolve("2.in"), "input-2");
        Files.writeString(workDir.resolve("2.out"), "user-2");
        Files.writeString(workDir.resolve("2.ans"), "answer-2");

        TestCaseDetail detail = service.getTestCaseDetails("valid-detail", 1);

        assertThat(detail.getInput()).isEqualTo("input-1");
        assertThat(detail.getUserOutput()).isEqualTo("user-1");
        assertThat(detail.getCorrectOutput()).isEqualTo("answer-1");
        assertThat(detail.getInput()).doesNotContain("input-2");
    }

    @Test
    void rejectsMissingCaseFilesInsideDeclaredRange() throws Exception {
        createTask("missing-case", 2, false, 64);

        assertThatThrownBy(() -> service.getTestCaseDetails("missing-case", 2))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found")
                .hasMessageNotContaining(tempDir.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3, Integer.MAX_VALUE})
    void rejectsCaseNumbersOutsideTaskRange(int caseNumber) throws Exception {
        createTask("range-task", 2, false, 64);

        assertThatThrownBy(() -> service.getTestCaseDetails("range-task", caseNumber))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("caseNumber")
                .hasMessageNotContaining(tempDir.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside-canary", "..\\outside-canary", "C:\\outside-canary", "%2e%2e%2foutside-canary", "missing-task"})
    void rejectsTraversalAndUnknownJudgeIdsWithoutLeakingFiles(String judgeId) {
        assertThatThrownBy(() -> service.getTestCaseDetails(judgeId, 1))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("SECRET_CANARY")
                .hasMessageNotContaining(canaryFile.toString());
    }

    @Test
    void previewsDetailFilesLargerThanPolicyLimitInsteadOfFailing() throws Exception {
        Path workDir = createTask("too-large-detail", 1, false, 4);
        Files.writeString(workDir.resolve("1.in"), "12345");
        Files.writeString(workDir.resolve("1.out"), "ok");
        Files.writeString(workDir.resolve("1.ans"), "ok");

        TestCaseDetail detail = service.getTestCaseDetails("too-large-detail", 1);

        assertThat(detail.getInput()).isEqualTo("1234");
        assertThat(detail.isInputTruncated()).isTrue();
        assertThat(detail.getUserOutput()).isEqualTo("ok");
        assertThat(detail.isUserOutputTruncated()).isFalse();
        assertThat(detail.getCorrectOutput()).isEqualTo("ok");
        assertThat(detail.isCorrectOutputTruncated()).isFalse();
    }

    @Test
    void resolvesInputDownloadOnlyInsideTaskWorkDir() throws Exception {
        Path workDir = createTask("download-one", 1, false, 64);
        Files.writeString(workDir.resolve("1.in"), "input-file");

        Path input = service.getTestCaseInputFile("download-one", 1);

        assertThat(input.toAbsolutePath().normalize()).isEqualTo(workDir.resolve("1.in").toAbsolutePath().normalize());
        assertThat(Files.readString(input)).isEqualTo("input-file");
    }

    @Test
    void streamsAllCaseDownloadAsSafeZipEntries() throws Exception {
        Path workDir = createTask("download-all", 2, false, 64);
        Files.writeString(workDir.resolve("1.in"), "input-1");
        Files.writeString(workDir.resolve("1.ans"), "answer-1");
        Files.writeString(workDir.resolve("2.in"), "input-2");
        Files.writeString(workDir.resolve("2.out"), "answer-2");

        StreamingResponseBody body = service.streamAllTestCasesArchive("download-all");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        body.writeTo(buffer);

        List<String> names = zipEntryNames(buffer.toByteArray());
        assertThat(names).containsExactly("1.in", "1.out", "2.in", "2.out");
        assertThat(names).allSatisfy(name -> assertThat(name).doesNotContain("..", "/", "\\"));
        assertThat(Files.readString(Path.of("src/main/java/com/example/demo/service/JudgeFileService.java")))
                .doesNotContain("ByteArrayOutputStream");
    }

    @Test
    void rejectsHighVolumeAllCaseDownloadWithClearError() throws Exception {
        createTask("large-download", 100_000, true, 64);

        assertThatThrownBy(() -> service.streamAllTestCasesArchive("large-download"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("high-volume");
    }

    private List<String> zipEntryNames(byte[] archiveBytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                zip.closeEntry();
            }
        }
        return names;
    }

    private Path createTask(String judgeId, int requestedCases, boolean highVolume, long maxBytes) throws IOException {
        Path workDir = taskStore.taskDirectory(judgeId);
        JudgeTask task = JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.COMPLETED)
                .requestedCases(requestedCases)
                .mode("trusted-local")
                .policy(policy(requestedCases, highVolume, maxBytes))
                .workDir(workDir.toString())
                .createdAt(Instant.now())
                .build();
        taskStore.create(task);
        return workDir;
    }

    private ResolvedTaskPolicy policy(int requestedCases, boolean highVolume, long maxBytes) {
        return new ResolvedTaskPolicy(
                "trusted-local",
                highVolume,
                Math.max(requestedCases, 1),
                requestedCases,
                100,
                4,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                64L * 1024 * 1024,
                maxBytes,
                false
        );
    }
}
