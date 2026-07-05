package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeFileService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeFileServiceProductionTest {

    @TempDir
    Path tempDir;

    private FileTaskStore taskStore;
    private JudgeFileService service;
    private Path outsideDir;
    private Path outsideCanary;

    @BeforeEach
    void setUp() throws IOException {
        taskStore = new FileTaskStore(new ObjectMapper(), tempDir.resolve("storage"));
        service = new JudgeFileService(taskStore, new ExecutionProperties());
        outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);
        outsideCanary = outsideDir.resolve("canary.txt");
        Files.writeString(outsideCanary, "SECRET_CANARY");
    }

    @Test
    void rejectsWorkDirJunctionOrSymlinkEscapeWithoutReadingOutsideCanary() throws Exception {
        Path workDir = createTask("escaped-workdir", 1);
        Path metadata = workDir.resolve("metadata.json");
        Files.writeString(outsideDir.resolve("metadata.json"), Files.readString(metadata));
        Files.writeString(outsideDir.resolve("1.in"), "SECRET_CANARY");
        Files.writeString(outsideDir.resolve("1.out"), "SECRET_CANARY");
        Files.writeString(outsideDir.resolve("1.ans"), "SECRET_CANARY");
        deleteRecursively(workDir);
        Assumptions.assumeTrue(createDirectoryLink(workDir, outsideDir), "directory link/junction unavailable");

        assertThatThrownBy(() -> service.getTestCaseDetails("escaped-workdir", 1))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("SECRET_CANARY")
                .hasMessageNotContaining(outsideCanary.toString());
    }

    @Test
    void rejectsCaseFileSymlinkEscapeWhenPlatformAllowsFileSymlinks() throws Exception {
        Path workDir = createTask("escaped-file", 1);
        Files.writeString(workDir.resolve("1.out"), "user-output");
        Files.writeString(workDir.resolve("1.ans"), "answer");
        Assumptions.assumeTrue(createFileLink(workDir.resolve("1.in"), outsideCanary), "file symlink unavailable");

        assertThatThrownBy(() -> service.getTestCaseDetails("escaped-file", 1))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("SECRET_CANARY")
                .hasMessageNotContaining(outsideCanary.toString());
    }

    @Test
    void downloadArchiveRejectsEscapedWorkDirBeforeStreamingAnyCanaryBytes() throws Exception {
        Path workDir = createTask("escaped-download", 1);
        Path metadata = workDir.resolve("metadata.json");
        Files.writeString(outsideDir.resolve("metadata.json"), Files.readString(metadata));
        Files.writeString(outsideDir.resolve("1.in"), "SECRET_CANARY");
        deleteRecursively(workDir);
        Assumptions.assumeTrue(createDirectoryLink(workDir, outsideDir), "directory link/junction unavailable");

        assertThatThrownBy(() -> service.streamAllTestCasesArchive("escaped-download"))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("SECRET_CANARY")
                .hasMessageNotContaining(outsideCanary.toString());
    }

    @Test
    void productionDownloadImplementationDoesNotBufferWholeZipInMemory() throws Exception {
        Path workDir = createTask("streaming-only", 2);
        Files.writeString(workDir.resolve("1.in"), "input-1");
        Files.writeString(workDir.resolve("2.in"), "input-2");

        StreamingResponseBody body = service.streamAllTestCasesArchive("streaming-only");
        ByteArrayOutputStream testBuffer = new ByteArrayOutputStream();
        body.writeTo(testBuffer);

        assertThat(testBuffer.size()).isGreaterThan(0);
        assertThat(Files.readString(Path.of("src/main/java/com/example/demo/service/JudgeFileService.java")))
                .doesNotContain("ByteArrayOutputStream");
    }

    private Path createTask(String judgeId, int requestedCases) throws IOException {
        Path workDir = taskStore.taskDirectory(judgeId);
        JudgeTask task = JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.COMPLETED)
                .requestedCases(requestedCases)
                .mode("trusted-local")
                .policy(policy(requestedCases))
                .workDir(workDir.toString())
                .createdAt(Instant.now())
                .build();
        taskStore.create(task);
        return workDir;
    }

    private ResolvedTaskPolicy policy(int requestedCases) {
        return new ResolvedTaskPolicy(
                "trusted-local",
                false,
                Math.max(requestedCases, 1),
                requestedCases,
                100,
                4,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                64L * 1024 * 1024,
                1024,
                false
        );
    }

    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                return false;
            }
            try {
                Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start();
                return process.waitFor() == 0 && Files.exists(link);
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private boolean createFileLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
