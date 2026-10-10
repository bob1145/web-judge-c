package com.example.demo;

import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.dto.SandboxComparison;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeProgressEvent;
import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.SandboxEventIngestor;
import com.example.demo.service.sandbox.SandboxManifestFactory;
import com.example.demo.support.RunnerProtocolFixtures;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TaskRunnerArtifactContractTest {

    private static final Path RUNNER = Path.of("runner", "scripts", "task_runner.py").toAbsolutePath();
    private final Map<Path, Path> attemptDirectories = new HashMap<>();
    private static final int EXIT_COMPLETED = 0;
    private static final int EXIT_COMPILE_ERROR = 10;
    private static final int EXIT_CANCELLED = 20;
    private static final int EXIT_BUDGET_EXCEEDED = 21;
    private static final int EXIT_SECURITY_VIOLATION = 30;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private final ObjectReader eventReader = SandboxTaskEvent.jsonReader(objectMapper);

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
        assertThat(Files.exists(attemptDirectories.get(workDir).resolve("runner-summary.json.tmp"))).isFalse();
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        assertThat(ingested.getStatus()).isEqualTo("AC");
        assertThat(ingested.getSummary().getCompletedCases()).isEqualTo(3);
        assertThat(ingested.getSummary().getAc()).isEqualTo(3);
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
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        assertThat(ingested.getStatus()).isEqualTo("COMPILATION_ERROR");
        assertThat(ingested.getSummary().getCompletedCases()).isZero();
        assertThat(ingested.getSummary().getAc()).isZero();
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
    @EnabledOnOs(OS.LINUX)
    void realLinuxOutputFloodKeepsSupervisorAndArtifactsBoundedAndAllowsNextCase() throws Exception {
        Path workDir = workDir("bounded-output-resources");
        writeDefaultSources(workDir, """
                #include <chrono>
                #include <iostream>
                #include <string>
                #include <thread>
                int main() {
                    int caseNumber;
                    std::cin >> caseNumber;
                    if (caseNumber == 1) {
                        const std::string output(8192, 'o');
                        const std::string error(8192, 'e');
                        while (true) {
                            std::cout << output << std::flush;
                            std::cerr << error << std::flush;
                        }
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(250));
                    std::cout << caseNumber * 2 << std::endl;
                }
                """, """
                #include <iostream>
                int main(){int caseNumber;std::cin>>caseNumber;std::cout<<caseNumber*2<<std::endl;}
                """);
        SandboxTaskSpec taskSpec = validSpec(workDir, 2).build();
        Path spec = writeSpec(workDir, taskSpec);
        Path eventsPath = attemptDirectories.get(workDir).resolve("runner-events.jsonl");
        Path log = workDir.resolve("runner.log");
        Process process = runnerProcess(spec).redirectOutput(log.toFile()).start();
        long peakResidentBytes = 0;
        long postFloodResidentBytes = 0;
        long deadline = System.nanoTime() + taskSpec.maxTaskRuntime().toNanos();
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                boolean floodReported = Files.exists(eventsPath)
                        && Files.readString(eventsPath).contains("OUTPUT_LIMIT_EXCEEDED");
                long residentBytes = linuxResidentPeakBytes(process);
                peakResidentBytes = Math.max(peakResidentBytes, residentBytes);
                if (floodReported) {
                    postFloodResidentBytes = Math.max(postFloodResidentBytes, residentBytes);
                }
                Thread.sleep(10);
            }
            assertThat(process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isEqualTo(EXIT_COMPLETED);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        assertThat(peakResidentBytes).isPositive().isLessThanOrEqualTo(64L * 1024 * 1024);
        assertThat(postFloodResidentBytes).isPositive();
        List<SandboxTaskEvent> events = readEvents(workDir);
        assertThat(events).filteredOn(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED)
                .extracting(SandboxTaskEvent::status).containsExactly("OUTPUT_LIMIT_EXCEEDED", "AC");
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        for (JudgeSummary summary : List.of(readSummary(workDir), ingested.getSummary())) {
            assertThat(summary.getCompletedCases()).isEqualTo(2);
            assertThat(summary.getOutputLimitExceeded()).isEqualTo(1);
            assertThat(summary.getAc()).isEqualTo(1);
            assertThat(summary.getSystemError()).isZero();
        }
        assertThat(ingested.getStatus()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
        assertThat(Files.size(workDir.resolve("1.out"))).isLessThanOrEqualTo(taskSpec.maxOutputBytesPerCase());
        assertThat(Files.notExists(workDir.resolve("1.ans"))).isTrue();
        long artifactBytes = 0;
        for (String name : List.of("1.in", "1.out", "1.ans", "2.in", "2.out", "2.ans")) {
            Path artifact = workDir.resolve(name);
            if (Files.exists(artifact)) {
                artifactBytes += Files.size(artifact);
            }
        }
        assertThat(artifactBytes).isLessThanOrEqualTo(taskSpec.maxOutputBytesPerCase() + 8);
        assertThat(Files.readString(workDir.resolve("2.out"))).isEqualTo("4\n");
        assertThat(Files.readString(workDir.resolve("2.ans"))).isEqualTo("4\n");
        System.out.printf("TASK-13 real Linux resources: supervisorPid=%d VmHWM=%d postFloodVmHWM=%d artifactBytes=%d outputLimit=%d OLE=1 nextCaseAC=1 coldStoreEqual=true%n",
                process.pid(), peakResidentBytes, postFloodResidentBytes, artifactBytes, taskSpec.maxOutputBytesPerCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unicode-space-is-not-ascii", "trailing-control-is-not-whitespace"})
    void realRunnerPreservesSharedNonWhitespaceVectorsThroughWrongAnswerIngestion(String vectorId) throws Exception {
        JsonNode vectors;
        try (var source = getClass().getResourceAsStream("/comparison/ordinary-output-vectors.json")) {
            assertThat(source).isNotNull();
            vectors = objectMapper.readTree(source);
        }
        JsonNode vector = null;
        for (JsonNode candidate : vectors) {
            if (candidate.path("id").asText().equals(vectorId)) {
                vector = candidate;
                break;
            }
        }
        assertThat(vector).isNotNull();
        assertThat(vector.path("matches").asBoolean()).isFalse();
        assertThat(vector.path("expectedOutput").asText()).isEqualTo("7");
        byte[] expectedBytes = (vector.path("userOutput").asText() + "\n").getBytes(StandardCharsets.UTF_8);
        String outputBytes = IntStream.range(0, expectedBytes.length)
                .mapToObj(index -> Integer.toString(Byte.toUnsignedInt(expectedBytes[index])))
                .collect(Collectors.joining(","));
        Path workDir = workDir("legacy-text-" + vectorId);
        writeDefaultSources(workDir, "#include <cstdio>\nint main(){unsigned char output[]={" + outputBytes
                        + "};std::fwrite(output,1,sizeof(output),stdout);}\n",
                "#include <iostream>\nint main(){std::cout<<7<<std::endl;}\n");
        Path spec = writeSpec(workDir, validSpec(workDir, 1).build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_COMPLETED);
        List<SandboxTaskEvent> events = readEvents(workDir);
        assertThat(events).filteredOn(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED)
                .singleElement().satisfies(event -> assertThat(event.status()).isEqualTo("WA"));
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        for (JudgeSummary summary : List.of(readSummary(workDir), ingested.getSummary())) {
            assertThat(summary.getCompletedCases()).isEqualTo(1);
            assertThat(summary.getWa()).isEqualTo(1);
            assertThat(summary.getAc()).isZero();
            assertThat(summary.getSystemError()).isZero();
        }
        assertThat(ingested.getStatus()).isEqualTo("WA");
        assertThat(Files.readAllBytes(workDir.resolve("1.out"))).isEqualTo(expectedBytes);
        assertThat(Files.readString(workDir.resolve("1.ans"))).isEqualTo("7\n");
    }

    @ParameterizedTest
    @MethodSource("ordinaryComparisonBatches")
    void realRunnerMatchesSharedDecimalVectorsThroughHostFinal(String precision, List<JsonNode> vectors) throws Exception {
        Path workDir = workDir("decimal-vectors");
        writeDefaultSources(workDir, vectorOutputSource(vectors, "userOutput"),
                vectorOutputSource(vectors, "expectedOutput"));
        SandboxComparison comparison = SandboxComparison.decimal(precision);
        Path spec = writeSpec(workDir, validSpec(workDir, vectors.size())
                .comparison(comparison).maxOutputBytesPerCase(8192).build());
        SandboxExecutionManifest manifest = objectMapper.readValue(spec.toFile(), SandboxExecutionManifest.class);
        assertThat(manifest.comparison()).isEqualTo(comparison);

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_COMPLETED);
        List<SandboxTaskEvent> events = readEvents(workDir);
        List<SandboxTaskEvent> finished = events.stream()
                .filter(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED).toList();
        assertThat(finished).hasSize(vectors.size());
        int expectedAc = 0;
        int expectedWa = 0;
        int expectedSystemError = 0;
        for (int index = 0; index < vectors.size(); index++) {
            JsonNode vector = vectors.get(index);
            String expectedStatus = vector.has("error") ? vector.path("error").asText()
                    : vector.path("matches").asBoolean() ? "AC" : "WA";
            assertThat(finished.get(index).caseNumber()).isEqualTo(index + 1);
            assertThat(finished.get(index).status()).as(vector.path("id").asText()).isEqualTo(expectedStatus);
            assertThat(Files.readAllBytes(workDir.resolve((index + 1) + ".out")))
                    .isEqualTo(vector.path("userOutput").asText().replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            assertThat(Files.readAllBytes(workDir.resolve((index + 1) + ".ans")))
                    .isEqualTo(vector.path("expectedOutput").asText().replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            switch (expectedStatus) {
                case "AC" -> expectedAc++;
                case "WA" -> expectedWa++;
                case "System Error" -> expectedSystemError++;
                default -> throw new IllegalArgumentException("unexpected shared vector status " + expectedStatus);
            }
            System.out.printf("TASK-16 vector=%s path=python-artifact-v2 precision=%s result=%s%n",
                    vector.path("id").asText(), manifest.comparison().precision(), finished.get(index).status());
        }
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        Integer firstFailedCase = finished.stream().filter(event -> !"AC".equals(event.status()))
                .map(SandboxTaskEvent::caseNumber).findFirst().orElse(null);
        for (JudgeSummary summary : List.of(readSummary(workDir), ingested.getSummary())) {
            assertThat(summary.getTotalCases()).isEqualTo(vectors.size());
            assertThat(summary.getCompletedCases()).isEqualTo(vectors.size());
            assertThat(summary.getAc()).isEqualTo(expectedAc);
            assertThat(summary.getWa()).isEqualTo(expectedWa);
            assertThat(summary.getSystemError()).isEqualTo(expectedSystemError);
            assertThat(summary.getTle()).isZero();
            assertThat(summary.getMle()).isZero();
            assertThat(summary.getRe()).isZero();
            assertThat(summary.getOutputLimitExceeded()).isZero();
            assertThat(summary.getFirstFailedCase()).isEqualTo(firstFailedCase);
            assertThat(summary.getStoppedReason()).isNull();
            for (var sample : summary.getFailureSamples()) {
                assertThat(sample.getStatus()).isEqualTo(finished.get(sample.getCaseNumber() - 1).status());
                assertThat(sample.getStatus()).isNotEqualTo("AC");
            }
        }
        assertThat(ingested.getStatus()).isEqualTo(firstFailedCase == null ? "AC"
                : finished.get(firstFailedCase - 1).status());
        assertThat(ingested.getResults()).isNull();
        assertThat(ingested.getAttemptId()).isEqualTo(manifest.attemptId());
        assertThat(ingested.getRevision()).isGreaterThan(1L);
        assertThat(ingested.getFinalizedAt()).isNotNull();
        FileTaskStore coldStore = new FileTaskStore(objectMapper, tempDir.resolve("host-storage"));
        assertThat(coldStore.findSummary(manifest.judgeId()).orElseThrow()).isEqualTo(ingested);
    }

    static Stream<Arguments> ordinaryComparisonBatches() throws IOException {
        Map<String, List<JsonNode>> batches = new LinkedHashMap<>();
        try (var source = TaskRunnerArtifactContractTest.class
                .getResourceAsStream("/comparison/ordinary-output-vectors.json")) {
            assertThat(source).isNotNull();
            for (JsonNode vector : new ObjectMapper().readTree(source)) {
                batches.computeIfAbsent(vector.path("precision").asText(), key -> new ArrayList<>()).add(vector);
            }
        }
        return batches.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private String vectorOutputSource(List<JsonNode> vectors, String field) {
        StringBuilder source = new StringBuilder("#include <cstdio>\nint main(){int number;if(std::scanf(\"%d\",&number)!=1)return 1;switch(number){");
        for (int index = 0; index < vectors.size(); index++) {
            byte[] output = vectors.get(index).path(field).asText().getBytes(StandardCharsets.UTF_8);
            String bytes = output.length == 0 ? "0" : IntStream.range(0, output.length)
                    .mapToObj(offset -> Integer.toString(Byte.toUnsignedInt(output[offset])))
                    .collect(Collectors.joining(","));
            source.append("case ").append(index + 1).append(":{unsigned char output[]={")
                    .append(bytes).append("};std::fwrite(output,1,").append(output.length)
                    .append(",stdout);return 0;}");
        }
        return source.append("}return 1;}\n").toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ORACLE"})
    void realRunnerMapsInvalidUtf8OnEitherSideToSystemError(String role) throws Exception {
        Path workDir = workDir("invalid-utf8-" + role);
        String invalid = "#include <cstdio>\nint main(){std::putchar(255);}";
        String valid = "#include <cstdio>\nint main(){std::putchar(55);}";
        writeDefaultSources(workDir, role.equals("USER") ? invalid : valid,
                role.equals("ORACLE") ? invalid : valid);
        Path spec = writeSpec(workDir, validSpec(workDir, 1)
                .comparison(SandboxComparison.decimal("0")).build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_COMPLETED);
        List<SandboxTaskEvent> events = readEvents(workDir);
        assertThat(events).filteredOn(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED)
                .singleElement().satisfies(event -> assertThat(event.status()).isEqualTo("System Error"));
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        assertThat(ingested.getStatus()).isEqualTo("System Error");
        for (JudgeSummary summary : List.of(readSummary(workDir), ingested.getSummary())) {
            assertThat(summary.getCompletedCases()).isEqualTo(1);
            assertThat(summary.getSystemError()).isEqualTo(1);
            assertThat(summary.getAc()).isZero();
            assertThat(summary.getWa()).isZero();
        }
        assertThat(Files.readAllBytes(workDir.resolve(role.equals("USER") ? "1.out" : "1.ans")))
                .isEqualTo(new byte[]{(byte) 255});
    }

    @Test
    void compilerDiagnosticLimitHasAnExplicitCauseWithoutCreatingFakeCases() throws Exception {
        Path workDir = workDir("compiler-output-limit");
        writeDefaultSources(workDir, "#error diagnostic output overflow\n".repeat(1000), "int main(){return 0;}");
        Path spec = writeSpec(workDir, validSpec(workDir, 1).build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_COMPILE_ERROR);
        List<SandboxTaskEvent> events = readEvents(workDir);
        SandboxTaskEvent compileFinished = events.stream()
                .filter(event -> event.type() == SandboxTaskEvent.Type.COMPILE_FINISHED)
                .findFirst().orElseThrow();
        assertThat(compileFinished.status()).isEqualTo("COMPILE_ERROR");
        assertThat(compileFinished.message()).contains("OUTPUT_LIMIT_EXCEEDED", "USER");
        assertThat(compileFinished.message().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(events).noneMatch(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED);
        JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
        assertThat(ingested.getSummary().getCompletedCases()).isZero();
        assertThat(ingested.getSummary().getAc()).isZero();
        assertThat(ingested.getSummary().getOutputLimitExceeded()).isZero();
        assertThat(ingested.getSummary().getStoppedReason()).contains("OUTPUT_LIMIT_EXCEEDED", "USER");
    }

    @ParameterizedTest
    @EnumSource(SandboxTaskSpec.SourceRole.class)
    void realRunnerCannotReportAcceptedWhenTheCombinedOutputExceedsItsLimit(SandboxTaskSpec.SourceRole role) throws Exception {
        Path workDir = workDir("combined-output-limit");
        String overflowingSource = """
                #include <iostream>
                #include <string>
                #include <thread>
                #include <chrono>
                int main(){
                    std::cout << std::string(2049, '7') << std::flush;
                    std::cerr << std::string(2048, 'e') << std::flush;
                    std::this_thread::sleep_for(std::chrono::seconds(10));
                }
                """;
        String ordinarySource = """
                #include <iostream>
                #include <string>
                int main(){ std::cout << std::string(2049, '7'); }
                """;
        writeDefaultSources(workDir, ordinarySource, ordinarySource);
        Path limitedSource = workDir.resolve(switch (role) {
            case GENERATOR -> "generator.cpp";
            case USER -> "user.cpp";
            case ORACLE -> "oracle.cpp";
            case SPECIAL_JUDGE -> "spj.cpp";
        });
        writeSource(limitedSource, overflowingSource);
        SandboxTaskSpec.Builder builder = validSpec(workDir, 1).sourcePath(role, limitedSource);
        if (role == SandboxTaskSpec.SourceRole.SPECIAL_JUDGE) {
            builder.sourcePath(SandboxTaskSpec.SourceRole.ORACLE, null)
                    .comparison(new SandboxComparison(SandboxComparison.Mode.SPECIAL_JUDGE, "0"));
        }
        Path spec = writeSpec(workDir, builder.build());
        RunnerResult result = runRunner(spec);
        assertThat(result.exitCode()).isEqualTo(EXIT_COMPLETED);
        SandboxTaskEvent finished = firstRunFinished(workDir);
        assertThat(finished.status()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
        JudgeSummary summary = readSummary(workDir);
        assertThat(summary.getCompletedCases()).isEqualTo(1);
        assertThat(summary.getOutputLimitExceeded()).isEqualTo(1);
        assertThat(summary.getAc()).isZero();
        assertThat(summary.getSystemError()).isZero();
        JudgeProgress ingested = ingestActualRunnerEvents(spec, readEvents(workDir));
        assertThat(ingested.getSummary().getCompletedCases()).isEqualTo(1);
        assertThat(ingested.getSummary().getOutputLimitExceeded()).isEqualTo(1);
        assertThat(ingested.getSummary().getAc()).isZero();
        assertThat(ingested.getSummary().getSystemError()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"accepted,AC", "rejected,WA", "timeout,System Error", "memory,System Error"})
    void specialJudgeInfrastructureFailuresDoNotBecomeWrongAnswers(String scenario, String expectedStatus) throws Exception {
        Path workDir = workDir("spj-" + scenario);
        writeDefaultSources(workDir, "int main(){return 0;}", "int main(){return 0;}");
        String spjSource = switch (scenario) {
            case "accepted" -> "int main(){return 0;}";
            case "rejected" -> "int main(){return 1;}";
            case "timeout" -> """
                    #include <thread>
                    #include <chrono>
                    int main(){ std::this_thread::sleep_for(std::chrono::seconds(10)); }
                    """;
            case "memory" -> """
                    #include <cstdlib>
                    #include <thread>
                    #include <chrono>
                    int main(){
                        const size_t bytes = 64 * 1024 * 1024;
                        volatile unsigned char* memory = static_cast<unsigned char*>(std::malloc(bytes));
                        if(!memory) return 1;
                        for(size_t index = 0; index < bytes; ++index) memory[index] = 1;
                        std::this_thread::sleep_for(std::chrono::seconds(1));
                        return 0;
                    }
                    """;
            default -> throw new IllegalArgumentException(scenario);
        };
        Path spjPath = workDir.resolve("spj.cpp");
        writeSource(spjPath, spjSource);
        Path spec = writeSpec(workDir, validSpec(workDir, 1)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, null)
                .sourcePath(SandboxTaskSpec.SourceRole.SPECIAL_JUDGE, spjPath)
                .comparison(new SandboxComparison(SandboxComparison.Mode.SPECIAL_JUDGE, "0"))
                .memoryLimitBytes(32L * 1024 * 1024)
                .build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_COMPLETED);
        assertThat(firstRunFinished(workDir).status()).isEqualTo(expectedStatus);
        JudgeSummary summary = readSummary(workDir);
        JudgeSummary ingested = ingestActualRunnerEvents(spec, readEvents(workDir)).getSummary();
        for (JudgeSummary observed : List.of(summary, ingested)) {
            assertThat(observed.getCompletedCases()).isEqualTo(1);
            assertThat(observed.getAc()).isEqualTo(expectedStatus.equals("AC") ? 1 : 0);
            assertThat(observed.getWa()).isEqualTo(expectedStatus.equals("WA") ? 1 : 0);
            assertThat(observed.getSystemError()).isEqualTo(expectedStatus.equals("System Error") ? 1 : 0);
            assertThat(observed.getTle()).isZero();
            assertThat(observed.getMle()).isZero();
            assertThat(observed.getRe()).isZero();
            assertThat(observed.getOutputLimitExceeded()).isZero();
        }
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
        JudgeProgress ingested = ingestActualRunnerEvents(spec, readEvents(workDir));
        assertThat(ingested.getStatus()).isEqualTo("WA");
        assertThat(ingested.getSummary().getCompletedCases()).isEqualTo(1);
        assertThat(ingested.getSummary().getStoppedReason()).isEqualTo(summary.getStoppedReason());
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
        writeSource(workDir.resolve("user.cpp"), "int main(){return 0;}");
        Path specPath = writeSpec(workDir, validSpec(workDir, 1).build());
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(specPath.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("sourcePaths"))
                .put("USER", "../outside-user.cpp");
        objectMapper.writeValue(specPath.toFile(), node);
        String outsideBefore = Files.readString(outsideUser);

        RunnerResult result = runRunner(specPath);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_SECURITY_VIOLATION);
        assertThat(result.output()).contains("manifest rejected before execution");
        assertThat(Files.readString(outsideUser)).isEqualTo(outsideBefore);
        assertThat(attemptDirectories.get(workDir).resolve("runner-events.jsonl")).doesNotExist();
        assertThat(workDir.resolve("workspace")).doesNotExist();
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

        Process process = runnerProcess(spec)
                .redirectErrorStream(true)
                .start();
        Thread.sleep(1_000);
        Files.writeString(attemptDirectories.get(workDir).resolve("cancel.requested"), "cancel", StandardCharsets.UTF_8);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(exited).as(output).isTrue();
        assertThat(process.exitValue()).isEqualTo(EXIT_CANCELLED);
        assertThat(readEvents(workDir)).last().satisfies(event -> assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.CANCELLED));
        assertThat(readSummary(workDir).getStoppedReason()).contains("Cancellation requested");
    }

    @ParameterizedTest
    @EnumSource(value = SandboxTaskEvent.Type.class, names = {"CANCELLED", "BUDGET_EXCEEDED"})
    void realRunnerMixedResultsSurviveStopAndHostIngestion(SandboxTaskEvent.Type stoppedType) throws Exception {
        Path workDir = workDir("mixed-" + stoppedType.name().toLowerCase(java.util.Locale.ROOT));
        Path caseThreeStarted = workDir.resolve("case-three-started.txt");
        writeDefaultSources(workDir, """
                #include <chrono>
                #include <fstream>
                #include <iostream>
                #include <thread>
                int main(){
                  int value = 0;
                  std::cin >> value;
                  if (value == 3) {
                    std::ofstream(R"wjc(%s)wjc") << "started";
                    std::this_thread::sleep_for(std::chrono::seconds(30));
                  }
                  std::cout << value + (value %% 2 == 0 ? 1 : 0) << "\\n";
                }
                """.formatted(caseThreeStarted), """
                #include <iostream>
                int main(){ int value=0; std::cin>>value; std::cout<<value<<"\\n"; }
                """);
        Path spec = writeSpec(workDir, validSpec(workDir, 3)
                .caseTimeLimit(Duration.ofSeconds(30))
                .maxTaskRuntime(stoppedType == SandboxTaskEvent.Type.BUDGET_EXCEEDED
                        ? Duration.ofSeconds(8) : Duration.ofSeconds(30))
                .build());
        Path log = workDir.resolve("runner-test.log");
        Process process = runnerProcess(spec).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(caseThreeStarted) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(caseThreeStarted).as(Files.readString(log)).exists();
            List<SandboxTaskEvent> completed = readEvents(workDir).stream()
                    .filter(event -> event.type() == SandboxTaskEvent.Type.RUN_FINISHED).toList();
            assertThat(completed).extracting(SandboxTaskEvent::status).containsExactly("AC", "WA");
            if (stoppedType == SandboxTaskEvent.Type.CANCELLED) {
                Files.writeString(attemptDirectories.get(workDir).resolve("cancel.requested"), "", StandardCharsets.UTF_8);
            }
            assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).as(Files.readString(log)).isTrue();
            assertThat(process.exitValue()).isEqualTo(stoppedType == SandboxTaskEvent.Type.CANCELLED
                    ? EXIT_CANCELLED : EXIT_BUDGET_EXCEEDED);
            List<SandboxTaskEvent> events = readEvents(workDir);
            assertThat(events).last().satisfies(event -> assertThat(event.type()).isEqualTo(stoppedType));
            JudgeSummary runnerSummary = readSummary(workDir);
            assertThat(runnerSummary.getCompletedCases()).isEqualTo(2);
            assertThat(runnerSummary.getAc()).isEqualTo(1);
            assertThat(runnerSummary.getWa()).isEqualTo(1);
            assertThat(runnerSummary.getFirstFailedCase()).isEqualTo(2);
            assertThat(runnerSummary.getFailureSamples()).hasSize(1);
            JudgeProgress ingested = ingestActualRunnerEvents(spec, events);
            assertThat(ingested.getStatus()).isEqualTo(stoppedType.name());
            JsonNode reported = objectMapper.valueToTree(runnerSummary);
            JsonNode trusted = objectMapper.valueToTree(ingested.getSummary());
            for (String field : List.of("totalCases", "completedCases", "ac", "wa", "tle", "mle", "re",
                    "systemError", "outputLimitExceeded", "firstFailedCase", "stoppedReason")) {
                assertThat(trusted.path(field)).as(field).isEqualTo(reported.path(field));
            }
            assertThat(ingested.getSummary().getFailureSamples()).usingRecursiveComparison()
                    .isEqualTo(runnerSummary.getFailureSamples());
            assertThat(ingested.getSummary().getSlowSamples()).hasSize(2)
                    .containsExactlyInAnyOrderElementsOf(completed.stream()
                            .map(event -> JudgeProgressEvent.from(event.result())).toList());
            SandboxExecutionManifest manifest = objectMapper.readValue(spec.toFile(), SandboxExecutionManifest.class);
            FileTaskStore coldStore = new FileTaskStore(objectMapper, tempDir.resolve("host-storage"));
            assertThat(coldStore.findSummary(manifest.judgeId()).orElseThrow())
                    .usingRecursiveComparison().isEqualTo(ingested);
            System.out.printf("TASK-17 real runner %s: exit=%d completed=%d ac=%d wa=%d samples=%d/%d coldStoreEqual=true%n",
                    stoppedType, process.exitValue(), runnerSummary.getCompletedCases(), runnerSummary.getAc(),
                    runnerSummary.getWa(), ingested.getSummary().getFailureSamples().size(), ingested.getSummary().getSlowSamples().size());
        } finally {
            if (process.isAlive()) {
                Files.writeString(attemptDirectories.get(workDir).resolve("cancel.requested"), "", StandardCharsets.UTF_8);
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        }
    }

    @Test
    void taskRunnerPreservesHostAuditHistoryBeforeStartingItsEventStream() throws Exception {
        Path workDir = workDir("host-audit");
        writeDefaultSources(workDir, "int main(){return 0;}", "int main(){return 0;}");
        String audit = """
                {"event":"CREATED"}
                {"event":"QUEUED"}
                {"event":"STARTED"}
                {"event":"POLICY"}
                """;
        Files.writeString(workDir.resolve("events.jsonl"), audit, StandardCharsets.UTF_8);
        Path spec = writeSpec(workDir, validSpec(workDir, 1).build());

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_COMPLETED);
        assertThat(Files.readString(workDir.resolve("events.jsonl"))).isEqualTo(audit);
    }

    @Test
    void taskRunnerRejectsHostRootBeforeCreatingDirectoriesOrControlFiles() throws Exception {
        Path workDir = workDir("invalid-host-root");
        Path outside = tempDir.resolve("must-not-create");
        writeDefaultSources(workDir, "int main(){return 0;}", "int main(){return 0;}");
        var node = objectMapper.valueToTree(validSpec(workDir, 1).build());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("workDir", outside.toString());
        Path spec = workDir.resolve("sandbox-task.json");
        objectMapper.writeValue(spec.toFile(), node);

        RunnerResult result = runRunner(spec);

        assertThat(result.exitCode()).as(result.output()).isEqualTo(EXIT_SECURITY_VIOLATION);
        assertThat(outside).doesNotExist();
        assertThat(workDir.resolve("bin")).doesNotExist();
        assertThat(workDir.resolve("events.jsonl")).doesNotExist();
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
                .comparison(RunnerProtocolFixtures.legacyText())
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
        SandboxManifestFactory.Prepared prepared = SandboxManifestFactory.prepare(spec);
        prepared.write(objectMapper);
        Path attempt = workDir.resolve(prepared.manifest().attemptDirectory());
        attemptDirectories.put(workDir, attempt);
        return workDir.resolve(prepared.manifest().specPath());
    }

    private ProcessBuilder runnerProcess(Path specPath) throws IOException, InterruptedException {
        Path workRoot = specPath.getParent();
        if (specPath.getFileName().toString().equals("task-spec.json")) {
            workRoot = specPath.getParent().getParent().getParent().getParent();
        }
        return new ProcessBuilder(PythonInterpreter.resolve(), "-B", RUNNER.toString(),
                "--work-root", workRoot.toString(), specPath.toString()).redirectErrorStream(true);
    }

    private long linuxResidentPeakBytes(Process process) throws IOException {
        try {
            for (String line : Files.readAllLines(Path.of("/proc", Long.toString(process.pid()), "status"))) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
                }
            }
        } catch (NoSuchFileException failure) {
            if (process.isAlive()) {
                throw failure;
            }
        }
        return 0;
    }

    private RunnerResult runRunner(Path specPath) throws Exception {
        Process process = runnerProcess(specPath)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exited).as(output).isTrue();
        return new RunnerResult(process.exitValue(), output);
    }

    private List<SandboxTaskEvent> readEvents(Path workDir) throws IOException {
        return Files.readAllLines(attemptDirectories.get(workDir).resolve("runner-events.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return eventReader.<SandboxTaskEvent>readValue(line);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private JudgeProgress ingestActualRunnerEvents(Path spec, List<SandboxTaskEvent> events) throws Exception {
        SandboxExecutionManifest manifest = objectMapper.readValue(spec.toFile(), SandboxExecutionManifest.class);
        ExecutionProperties properties = new ExecutionProperties();
        FileTaskStore store = new FileTaskStore(objectMapper, tempDir.resolve("host-storage"));
        ResolvedTaskPolicy policy = new ResolvedTaskPolicy("trusted-local", true, manifest.testCases(),
                manifest.testCases(), 1, 1, Duration.ofMillis(manifest.caseTimeLimitMillis()),
                Duration.ofMillis(manifest.maxTaskRuntimeMillis()), manifest.memoryLimitBytes(),
                manifest.maxOutputBytesPerCase(), false, manifest.comparison());
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId(manifest.judgeId()).runId("runner-artifact")
                .provider("trusted-local").protocolVersion(manifest.protocolVersion())
                .attemptId(manifest.attemptId()).build();
        store.create(JudgeTask.builder().judgeId(manifest.judgeId()).status(JudgeStatus.CREATED)
                .requestedCases(manifest.testCases()).policy(policy).mode(policy.profile())
                .sandboxRunHandle(handle)
                .workDir(store.taskDirectory(manifest.judgeId()).toString()).createdAt(Instant.now()).build());
        ProgressPublisher publisher = new ProgressPublisher(mock(SimpMessagingTemplate.class), store,
                objectMapper, Clock.systemUTC(), properties.getProgressPublishInterval());
        SandboxEventIngestor.Session session = new SandboxEventIngestor(publisher, properties)
                .start(manifest.judgeId(), policy, manifest.stopOnFirstNonAc());
        session.bindRunHandle(handle);
        JudgeProgress progress = null;
        long expectedSequence = 1;
        for (SandboxTaskEvent event : events) {
            assertThat(event.sequence()).isEqualTo(expectedSequence++);
            JudgeProgress previous = store.findSummary(manifest.judgeId()).orElse(null);
            progress = session.accept(event);
            JudgeProgress persisted = store.findSummary(manifest.judgeId()).orElse(null);
            if (persisted != null) {
                assertThat(persisted.getRevision()).isPositive();
                if (previous != null) {
                    assertThat(persisted.getRevision()).isGreaterThanOrEqualTo(previous.getRevision());
                    if (session.isTerminal(persisted) && !session.isTerminal(previous)) {
                        assertThat(persisted.getRevision()).isEqualTo(previous.getRevision() + 1);
                    }
                }
            }
            if (event.type() == SandboxTaskEvent.Type.SUMMARY) {
                assertThat(session.isTerminal(progress)).isFalse();
            }
        }
        assertThat(session.isTerminal(progress)).isTrue();
        assertThat(store.findSummary(manifest.judgeId()).orElseThrow()).isEqualTo(progress);
        return progress;
    }

    private JudgeSummary readSummary(Path workDir) throws IOException {
        return objectMapper.readValue(attemptDirectories.get(workDir).resolve("runner-summary.json").toFile(), JudgeSummary.class);
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
