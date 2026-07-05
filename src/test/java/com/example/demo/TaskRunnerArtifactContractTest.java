package com.example.demo;

import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunnerArtifactContractTest {

    private static final Path RUNNER = Path.of("runner", "scripts", "task_runner.py");
    private static final int EXIT_COMPLETED = 0;
    private static final int EXIT_COMPILE_ERROR = 10;
    private static final int EXIT_CANCELLED = 20;
    private static final int EXIT_SECURITY_VIOLATION = 30;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path tempDir;

    @Test
    void taskRunnerCompilesProgramsWritesJsonlEventsAndAtomicSummaryForAcceptedCases() throws Exception {
        Path workDir = workDir("ac");
        writeDefaultSources(workDir, """
                #include <bits/stdc++.h>
                int main(){ long long x; if(std::cin>>x) std::cout << (x * 2) << "\\n"; }
                """, """
                #include <bits/stdc++.h>
                int main(){ long long x; if(std::cin>>x) std::cout << (x * 2) << "\\n"; }
                """);
        Path spec = writeSpec(workDir, validSpec(workDir, 3)
                .maxOutputBytesPerCase(1024)
                .build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).isEqualTo(EXIT_COMPLETED);
        List<SandboxTaskEvent> events = readEvents(workDir);
        assertThat(events).extracting(SandboxTaskEvent::type)
                .contains(
                        SandboxTaskEvent.Type.COMPILE_STARTED,
                        SandboxTaskEvent.Type.COMPILE_FINISHED,
                        SandboxTaskEvent.Type.RUN_STARTED,
                        SandboxTaskEvent.Type.RUN_FINISHED,
                        SandboxTaskEvent.Type.SUMMARY,
                        SandboxTaskEvent.Type.COMPLETED
                );
        JudgeSummary summary = readSummary(workDir);
        assertThat(summary.getTotalCases()).isEqualTo(3);
        assertThat(summary.getCompletedCases()).isEqualTo(3);
        assertThat(summary.getAc()).isEqualTo(3);
        assertThat(summary.getFirstFailedCase()).isNull();
        assertThat(Files.exists(workDir.resolve("summary.json.tmp"))).isFalse();
    }

    @Test
    void taskRunnerReturnsStableCompileErrorAndBoundsCompilerOutput() throws Exception {
        Path workDir = workDir("compile-error");
        writeDefaultSources(workDir, "int main(){ this is invalid c++ }\n", """
                #include <iostream>
                int main(){ int x; if(std::cin>>x) std::cout << x << "\\n"; }
                """);
        Path spec = writeSpec(workDir, validSpec(workDir, 1).build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).isEqualTo(EXIT_COMPILE_ERROR);
        List<SandboxTaskEvent> events = readEvents(workDir);
        SandboxTaskEvent terminal = events.get(events.size() - 1);
        assertThat(events).extracting(SandboxTaskEvent::type)
                .contains(SandboxTaskEvent.Type.COMPILE_STARTED, SandboxTaskEvent.Type.COMPILE_FINISHED);
        assertThat(terminal.type()).isEqualTo(SandboxTaskEvent.Type.SYSTEM_ERROR);
        assertThat(terminal.message()).contains("compile failed");
        assertThat(terminal.message().length()).isLessThanOrEqualTo(4096);
    }

    @Test
    void taskRunnerReportsWrongAnswerTimeoutAndOutputLimitAsStableCaseStatuses() throws Exception {
        Path waWorkDir = workDir("wa");
        writeDefaultSources(waWorkDir, """
                #include <bits/stdc++.h>
                int main(){ long long x; if(std::cin>>x) std::cout << (x + 1) << "\\n"; }
                """, """
                #include <bits/stdc++.h>
                int main(){ long long x; if(std::cin>>x) std::cout << (x + 2) << "\\n"; }
                """);
        RunnerResult wa = runRunner(writeSpec(waWorkDir, validSpec(waWorkDir, 1).build()));
        assertThat(wa.exitCode()).isEqualTo(EXIT_COMPLETED);
        assertThat(readSummary(waWorkDir).getWa()).isEqualTo(1);
        assertThat(firstRunFinished(waWorkDir).status()).isEqualTo("WA");

        Path tleWorkDir = workDir("tle");
        writeDefaultSources(tleWorkDir, """
                int main(){ while(true){} }
                """, """
                #include <iostream>
                int main(){ int x; if(std::cin>>x) std::cout << x << "\\n"; }
                """);
        RunnerResult tle = runRunner(writeSpec(tleWorkDir, validSpec(tleWorkDir, 1)
                .caseTimeLimit(Duration.ofMillis(200))
                .build()));
        assertThat(tle.exitCode()).isEqualTo(EXIT_COMPLETED);
        assertThat(readSummary(tleWorkDir).getTle()).isEqualTo(1);
        assertThat(firstRunFinished(tleWorkDir).status()).isEqualTo("TLE");

        Path outputWorkDir = workDir("output-limit");
        writeDefaultSources(outputWorkDir, """
                #include <iostream>
                int main(){ for(int i=0;i<5000;i++) std::cout << 'x'; }
                """, """
                #include <iostream>
                int main(){ std::cout << "ok\\n"; }
                """);
        RunnerResult output = runRunner(writeSpec(outputWorkDir, validSpec(outputWorkDir, 1)
                .maxOutputBytesPerCase(64)
                .build()));
        assertThat(output.exitCode()).isEqualTo(EXIT_COMPLETED);
        assertThat(readSummary(outputWorkDir).getOutputLimitExceeded()).isEqualTo(1);
        assertThat(firstRunFinished(outputWorkDir).status()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
    }

    @Test
    void taskRunnerStopsAfterFirstNonAcceptedCaseWhenRequestedAndKeepsArtifacts() throws Exception {
        Path workDir = workDir("stop-first-wa");
        writeDefaultSources(workDir, """
                #include <bits/stdc++.h>
                int main(){ long long x; if(std::cin>>x) std::cout << (x + 1) << "\\n"; }
                """, """
                #include <bits/stdc++.h>
                int main(){ long long x; if(std::cin>>x) std::cout << x << "\\n"; }
                """);
        Path spec = writeSpec(workDir, validSpec(workDir, 5)
                .stopOnFirstNonAc(true)
                .build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).isEqualTo(EXIT_COMPLETED);
        JudgeSummary summary = readSummary(workDir);
        assertThat(summary.getCompletedCases()).isEqualTo(1);
        assertThat(summary.getFirstFailedCase()).isEqualTo(1);
        assertThat(summary.getStoppedReason()).contains("first non-AC");
        assertThat(Files.readString(workDir.resolve("1.in"))).isEqualTo("1\n");
        assertThat(Files.readString(workDir.resolve("1.out"))).isEqualTo("2\n");
        assertThat(Files.readString(workDir.resolve("1.ans"))).isEqualTo("1\n");
        assertThat(workDir.resolve("2.in")).doesNotExist();
    }

    @Test
    void taskRunnerReportsMemoryLimitExceededStatus() throws Exception {
        Path workDir = workDir("mle");
        writeDefaultSources(workDir, """
                #include <cstdlib>
                #include <cstring>
                #include <thread>
                #include <chrono>
                int main(){
                  const int blocks = 256;
                  char* ptrs[blocks];
                  for(int i=0;i<blocks;i++){
                    ptrs[i] = (char*)std::malloc(1024 * 1024);
                    if(ptrs[i]) std::memset(ptrs[i], 1, 1024 * 1024);
                  }
                  std::this_thread::sleep_for(std::chrono::seconds(3));
                  return 0;
                }
                """, """
                #include <iostream>
                int main(){ int x; if(std::cin>>x) std::cout << x << "\\n"; }
                """);
        Path spec = writeSpec(workDir, validSpec(workDir, 1)
                .memoryLimitBytes(32L * 1024 * 1024)
                .caseTimeLimit(Duration.ofSeconds(5))
                .build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).isEqualTo(EXIT_COMPLETED);
        assertThat(readSummary(workDir).getMle()).isEqualTo(1);
        assertThat(firstRunFinished(workDir).status()).isEqualTo("MLE");
    }

    @Test
    void taskRunnerRejectsSourcePathsOutsideMountedWorkDirAsSecurityViolation() throws Exception {
        Path storageBase = tempDir.resolve("security-storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-security");
        Files.createDirectories(workDir);
        Path outsideUser = storageBase.resolve("outside-user.cpp");
        Files.writeString(outsideUser, "int main(){return 0;}", StandardCharsets.UTF_8);
        writeSource(workDir.resolve("generator.cpp"), "int main(){return 0;}");
        writeSource(workDir.resolve("oracle.cpp"), "int main(){return 0;}");
        SandboxTaskSpec spec = SandboxTaskSpec.builder()
                .judgeId("judge-security")
                .userId("user-security")
                .profile("worker-prod")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, workDir.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, outsideUser)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, workDir.resolve("oracle.cpp"))
                .testCases(1)
                .caseTimeLimit(Duration.ofSeconds(1))
                .maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(128L * 1024 * 1024)
                .maxOutputBytesPerCase(1024)
                .build();
        Path specPath = writeSpec(workDir, spec);

        RunnerResult result = runRunner(specPath);

        assertThat(result.exitCode()).isEqualTo(EXIT_SECURITY_VIOLATION);
        assertThat(readEvents(workDir)).last().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.SECURITY_VIOLATION);
            assertThat(event.message()).contains("source path");
        });
    }

    @Test
    void taskRunnerStopsOnCancellationFileAndWritesPartialSummary() throws Exception {
        Path workDir = workDir("cancel");
        writeDefaultSources(workDir, """
                #include <thread>
                #include <chrono>
                int main(){
                  std::this_thread::sleep_for(std::chrono::seconds(30));
                  return 0;
                }
                """, """
                #include <iostream>
                int main(){ int x; if(std::cin>>x) std::cout << x << "\\n"; }
                """);
        Path spec = writeSpec(workDir, validSpec(workDir, 1)
                .caseTimeLimit(Duration.ofSeconds(30))
                .maxTaskRuntime(Duration.ofSeconds(60))
                .build());

        Process process = new ProcessBuilder("python", RUNNER.toString(), spec.toString())
                .redirectErrorStream(true)
                .start();
        Thread.sleep(1_000);
        Files.writeString(workDir.resolve("cancel.requested"), "cancel", StandardCharsets.UTF_8);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(exited).as(output).isTrue();
        assertThat(process.exitValue()).isEqualTo(EXIT_CANCELLED);
        assertThat(readEvents(workDir)).last().satisfies(event -> assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.CANCELLED));
        assertThat(readSummary(workDir).getStoppedReason()).contains("Cancellation requested");
    }

    private Path workDir(String name) throws IOException {
        Path workDir = tempDir.resolve(name).toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        return workDir;
    }

    private SandboxTaskSpec.Builder validSpec(Path workDir, int cases) {
        return SandboxTaskSpec.builder()
                .judgeId("judge-" + workDir.getFileName())
                .userId("user-runner")
                .profile("worker-prod")
                .storageBase(tempDir.toAbsolutePath().normalize())
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, workDir.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, workDir.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, workDir.resolve("oracle.cpp"))
                .testCases(cases)
                .caseTimeLimit(Duration.ofSeconds(2))
                .maxTaskRuntime(Duration.ofSeconds(30))
                .memoryLimitBytes(128L * 1024 * 1024)
                .maxOutputBytesPerCase(4096);
    }

    private void writeDefaultSources(Path workDir, String userCode, String oracleCode) throws IOException {
        writeSource(workDir.resolve("generator.cpp"), """
                #include <iostream>
                int main(){ long long caseNumber; if(std::cin>>caseNumber) std::cout << caseNumber << "\\n"; }
                """);
        writeSource(workDir.resolve("user.cpp"), userCode);
        writeSource(workDir.resolve("oracle.cpp"), oracleCode);
    }

    private void writeSource(Path path, String source) throws IOException {
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private Path writeSpec(Path workDir, SandboxTaskSpec spec) throws IOException {
        Path specPath = workDir.resolve("sandbox-task.json");
        objectMapper.writeValue(specPath.toFile(), spec);
        return specPath;
    }

    private RunnerResult runRunner(Path specPath) throws Exception {
        Process process = new ProcessBuilder("python", RUNNER.toString(), specPath.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exited).as(output).isTrue();
        return new RunnerResult(process.exitValue(), output);
    }

    private List<SandboxTaskEvent> readEvents(Path workDir) throws IOException {
        return Files.readAllLines(workDir.resolve("events.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return objectMapper.readValue(line, SandboxTaskEvent.class);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private JudgeSummary readSummary(Path workDir) throws IOException {
        return objectMapper.readValue(workDir.resolve("summary.json").toFile(), JudgeSummary.class);
    }

    private SandboxTaskEvent firstRunFinished(Path workDir) throws IOException {
        return readEvents(workDir).stream()
                .filter(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED)
                .findFirst()
                .orElseThrow();
    }

    private record RunnerResult(int exitCode, String output) {
    }
}
