package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.service.CaseBatchRunner;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.QuotaService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.SandboxEventIngestor;
import com.example.demo.service.SandboxProcessRunner;
import com.example.demo.service.TaskPolicyResolver;
import com.example.demo.service.sandbox.SandboxRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductionHighVolumeIntegrationTest {

    private static final int MAX_PAYLOAD_BYTES = 65_536;
    private static final int MAX_WEBSOCKET_MESSAGES = 8;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private final List<JudgeScheduler> schedulers = new ArrayList<>();
    private final List<CountingExecutor> schedulerExecutors = new ArrayList<>();
    private final List<ThreadPoolTaskExecutor> caseExecutors = new ArrayList<>();

    @TempDir
    Path tempDir;

    @AfterEach
    void shutdown() {
        for (JudgeScheduler scheduler : schedulers) {
            scheduler.shutdown();
        }
        for (CountingExecutor executor : schedulerExecutors) {
            executor.shutdownNow();
        }
        for (ThreadPoolTaskExecutor executor : caseExecutors) {
            executor.shutdown();
        }
    }

    @Test
    void productionRunnerChainHandles100To100000CasesWithoutLinearFuturesPayloadsOrSamples() throws Exception {
        List<Integer> caseCounts = List.of(100, 10_000, smokeCases());
        for (int totalCases : caseCounts) {
            HighVolumeRun run = runProductionRunnerFlow(totalCases);

            assertThat(run.progress().getStatus()).isEqualTo("WA");
            assertThat(run.progress().getProgress()).isEqualTo(100);
            assertThat(run.progress().getResults()).isNull();
            assertThat(run.payloadBytes()).isLessThan(MAX_PAYLOAD_BYTES);
            assertThat(run.websocketMessages()).isLessThanOrEqualTo(MAX_WEBSOCKET_MESSAGES);
            assertThat(run.runner().peakEventsPerPoll()).isLessThanOrEqualTo(run.runner().batchSize() + 4);
            assertThat(run.runner().emittedCaseEvents()).isEqualTo(totalCases);
            assertThat(run.schedulerExecuteCount()).isEqualTo(1);
            assertThat(run.schedulerExecuteCount()).isNotEqualTo(totalCases);
            assertThat(run.schedulerPeakInFlight()).isEqualTo(1);

            JudgeSummary summary = run.progress().getSummary();
            assertThat(summary.getTotalCases()).isEqualTo(totalCases);
            assertThat(summary.getCompletedCases()).isEqualTo(totalCases);
            assertThat(summary.getFailureSamples()).hasSizeLessThanOrEqualTo(run.maxFailureSamples());
            assertThat(summary.getSlowSamples()).hasSizeLessThanOrEqualTo(run.maxSlowSamples());
            assertThat(summary.getFirstFailedCase()).isEqualTo(3);
            assertThat(run.persistedSummary().getResults()).isNull();
            assertThat(run.persistedTaskStatus()).isEqualTo(JudgeStatus.WA);

            double throughput = totalCases / Math.max(0.001, run.elapsed().toMillis() / 1000.0);
            System.out.printf(
                    "HIGH_VOLUME_SMOKE cases=%d elapsedMs=%d throughputCasesPerSec=%.1f schedulerTasks=%d peakInFlightSchedulerTasks=%d pollCount=%d peakEventsPerPoll=%d websocketMessages=%d payloadBytes=%d failureSamples=%d slowSamples=%d%n",
                    totalCases,
                    run.elapsed().toMillis(),
                    throughput,
                    run.schedulerExecuteCount(),
                    run.schedulerPeakInFlight(),
                    run.runner().pollCount(),
                    run.runner().peakEventsPerPoll(),
                    run.websocketMessages(),
                    run.payloadBytes(),
                    summary.getFailureSamples().size(),
                    summary.getSlowSamples().size()
            );
        }
    }

    @Test
    void highVolumeSmokeScriptsAndRunbooksExposeWindowsAndLinuxEntryPoints() throws Exception {
        Path windowsSmoke = Path.of("scripts/smoke/high-volume-smoke.ps1");
        Path linuxSmoke = Path.of("scripts/smoke/high-volume-smoke.sh");

        assertThat(windowsSmoke).exists();
        assertThat(linuxSmoke).exists();
        assertThat(Files.readString(windowsSmoke))
                .contains("-Cases")
                .contains("ProductionHighVolumeIntegrationTest")
                .contains("highVolumeSmokeCases");
        assertThat(Files.readString(linuxSmoke))
                .contains("--cases")
                .contains("ProductionHighVolumeIntegrationTest")
                .contains("highVolumeSmokeCases")
                .contains("mvnw.cmd");
        assertThat(Files.readString(Path.of("docs/windows-sandbox-runbook.md")))
                .contains("scripts/smoke/high-volume-smoke.ps1")
                .contains("wsl.exe")
                .contains("payloadBytes")
                .contains("schedulerTasks");
        assertThat(Files.readString(Path.of("docs/linux-sandbox-runbook.md")))
                .contains("scripts/smoke/high-volume-smoke.sh")
                .contains("payloadBytes")
                .contains("schedulerTasks");
    }

    private HighVolumeRun runProductionRunnerFlow(int totalCases) throws Exception {
        ExecutionProperties execution = executionProperties(totalCases);
        MemoryConfiguration memory = new MemoryConfiguration();
        memory.setDefaultLimit(64L * 1024 * 1024);
        memory.setMaxLimit(512L * 1024 * 1024);
        FileTaskStore store = new FileTaskStore(objectMapper, tempDir.resolve("storage-" + totalCases));
        TaskPolicyResolver policyResolver = new TaskPolicyResolver(execution, memory);
        StreamingHighVolumeSandboxRunner runner = new StreamingHighVolumeSandboxRunner(totalCases, 512, failures(totalCases));
        CountingExecutor schedulerExecutor = new CountingExecutor();
        schedulerExecutors.add(schedulerExecutor);
        JudgeScheduler scheduler = new JudgeScheduler(execution, store, schedulerExecutor);
        schedulers.add(scheduler);
        ThreadPoolTaskExecutor caseExecutor = caseExecutor();
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ProgressPublisher publisher = new ProgressPublisher(
                messagingTemplate,
                store,
                objectMapper,
                Clock.systemUTC(),
                execution.getProgressPublishInterval()
        );
        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(true);
        SandboxProcessRunner legacyRunner = new SandboxProcessRunner(sandbox, null, request -> {
            throw new AssertionError("production high-volume runner path must not call the legacy process runner");
        });
        JudgeService service = new JudgeService(
                memory,
                execution,
                legacyRunner,
                publisher,
                new SandboxEventIngestor(publisher, execution),
                policyResolver,
                new QuotaService(execution, store),
                store,
                new CaseBatchRunner(caseExecutor),
                scheduler,
                securityValidator(execution, sandbox),
                caseExecutor,
                Optional.of(runner)
        );
        String judgeId = "prod-high-" + totalCases + "-" + UUID.randomUUID();
        Instant started = Instant.now();

        service.createJudgeTask(request(totalCases), judgeId);
        service.startJudgeTask(judgeId);
        JudgeProgress progress = awaitProgress(service, judgeId, "WA");
        Duration elapsed = Duration.between(started, Instant.now());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(anyString(), payloadCaptor.capture());
        int websocketMessages = payloadCaptor.getAllValues().size();
        int payloadBytes = objectMapper.writeValueAsBytes(progress).length;
        JudgeProgress persistedSummary = store.findSummary(judgeId).orElseThrow();
        JudgeStatus persistedTaskStatus = store.find(judgeId).orElseThrow().getStatus();

        return new HighVolumeRun(
                progress,
                persistedSummary,
                persistedTaskStatus,
                runner,
                schedulerExecutor.executeCount(),
                schedulerExecutor.peakInFlight(),
                websocketMessages,
                payloadBytes,
                elapsed,
                execution.getMaxFailureSamples(),
                execution.getMaxSlowSamples()
        );
    }

    private ExecutionProperties executionProperties(int totalCases) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setProfile("linux-prod");
        properties.setRequireSandbox(true);
        properties.setMaxCasesPerTask(Math.max(totalCases, 100_000));
        properties.setLargeModeThreshold(50);
        properties.setTaskQueueCapacity(2);
        properties.setMaxConcurrentTasks(1);
        properties.setMaxConcurrentCasesPerTask(4);
        properties.setBatchSize(128);
        properties.setMaxFailureSamples(5);
        properties.setMaxSlowSamples(7);
        properties.setProgressPublishInterval(Duration.ofSeconds(1));
        properties.setDefaultTimeLimit(Duration.ofSeconds(1));
        properties.setMinTimeLimit(Duration.ofMillis(100));
        properties.setMaxTimeLimit(Duration.ofSeconds(5));
        properties.setMaxTaskRuntime(Duration.ofSeconds(30));
        properties.setMinMemoryLimitBytes(16L * 1024 * 1024);
        properties.setMaxOutputBytesPerCase(64L * 1024);
        return properties;
    }

    private ThreadPoolTaskExecutor caseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        caseExecutors.add(executor);
        return executor;
    }

    private SecurityModeStartupValidator securityValidator(ExecutionProperties execution, SandboxConfiguration sandbox) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("changed-secret");
        auth.setDefaultAccessCode("123");
        return new SecurityModeStartupValidator(auth, execution, sandbox, new WebSocketConfig("https://safe.example.com"));
    }

    private JudgeRequest request(int totalCases) {
        JudgeRequest request = new JudgeRequest();
        request.setGeneratorCode("int main(){return 0;}");
        request.setUserCode("int main(){return 0;}");
        request.setBruteForceCode("int main(){return 0;}");
        request.setTestCases(totalCases);
        request.setTimeLimit(1_000);
        request.setMemoryLimit(64L * 1024 * 1024);
        return request;
    }

    private JudgeProgress awaitProgress(JudgeService service, String judgeId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                JudgeProgress progress = service.getJudgeStatus(judgeId);
                if (expectedStatus.equals(progress.getStatus()) && progress.getProgress() == 100) {
                    return progress;
                }
                lastFailure = new AssertionError("Expected " + expectedStatus + " but got " + progress.getStatus());
            } catch (IllegalArgumentException | AssertionError error) {
                lastFailure = error instanceof AssertionError assertionError
                        ? assertionError
                        : new AssertionError(error.getMessage(), error);
            }
            Thread.sleep(20);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Timed out waiting for " + expectedStatus);
    }

    private Map<Integer, String> failures(int totalCases) {
        Map<Integer, String> failures = new LinkedHashMap<>();
        failures.put(3, "WA");
        failures.put(10, "TLE");
        failures.put(20, "MLE");
        failures.put(30, "RE");
        failures.put(40, "System Error");
        failures.put(50, "OUTPUT_LIMIT_EXCEEDED");
        failures.put(Math.max(51, totalCases - 1), "WA");
        return failures;
    }

    private int smokeCases() {
        return Integer.getInteger("highVolumeSmokeCases", 100_000);
    }

    private record HighVolumeRun(
            JudgeProgress progress,
            JudgeProgress persistedSummary,
            JudgeStatus persistedTaskStatus,
            StreamingHighVolumeSandboxRunner runner,
            int schedulerExecuteCount,
            int schedulerPeakInFlight,
            int websocketMessages,
            int payloadBytes,
            Duration elapsed,
            int maxFailureSamples,
            int maxSlowSamples
    ) {
    }

    private static final class CountingExecutor implements Executor {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private int executeCount;
        private int active;
        private int peakInFlight;

        @Override
        public synchronized void execute(Runnable command) {
            executeCount++;
            delegate.execute(() -> {
                synchronized (this) {
                    active++;
                    peakInFlight = Math.max(peakInFlight, active);
                }
                try {
                    command.run();
                } finally {
                    synchronized (this) {
                        active--;
                    }
                }
            });
        }

        synchronized int executeCount() {
            return executeCount;
        }

        synchronized int peakInFlight() {
            return peakInFlight;
        }

        void shutdownNow() {
            delegate.shutdownNow();
        }
    }

    private static final class StreamingHighVolumeSandboxRunner implements SandboxRunner {
        private final int totalCases;
        private final int batchSize;
        private final Map<Integer, String> failures;
        private int emittedCaseEvents;
        private int pollCount;
        private int peakEventsPerPoll;
        private boolean sentHeaders;
        private boolean sentCompleted;

        private StreamingHighVolumeSandboxRunner(int totalCases, int batchSize, Map<Integer, String> failures) {
            this.totalCases = totalCases;
            this.batchSize = batchSize;
            this.failures = failures;
        }

        @Override
        public SandboxCapabilities probe() {
            return SandboxCapabilities.builder()
                    .provider("streaming-high-volume-fake")
                    .isolation("test-runner")
                    .productionSafe(true)
                    .networkDisabled(true)
                    .nonRoot(true)
                    .resourceLimits(true)
                    .securityProfile("test-seccomp")
                    .build();
        }

        @Override
        public SandboxRunHandle start(SandboxTaskSpec spec) {
            assertThat(spec.testCases()).isEqualTo(totalCases);
            assertThat(spec.profile()).isEqualTo("linux-prod");
            return SandboxRunHandle.builder()
                    .judgeId(spec.judgeId())
                    .runId("streaming-" + spec.judgeId())
                    .provider("streaming-high-volume-fake")
                    .startedAt(Instant.now())
                    .eventCursor("0")
                    .build();
        }

        @Override
        public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle) {
            pollCount++;
            if (sentCompleted) {
                return List.of();
            }
            List<SandboxTaskEvent> events = new ArrayList<>(batchSize + 4);
            if (!sentHeaders) {
                sentHeaders = true;
                events.add(SandboxTaskEvent.of(handle.judgeId(), SandboxTaskEvent.Type.COMPILE_STARTED, "compile started"));
                events.add(SandboxTaskEvent.of(handle.judgeId(), SandboxTaskEvent.Type.COMPILE_FINISHED, "compile finished"));
                events.add(SandboxTaskEvent.of(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED, "running"));
            }
            int limit = Math.min(totalCases, emittedCaseEvents + batchSize);
            while (emittedCaseEvents < limit) {
                int caseNumber = emittedCaseEvents + 1;
                String status = failures.getOrDefault(caseNumber, "AC");
                events.add(new SandboxTaskEvent(
                        handle.judgeId(),
                        SandboxTaskEvent.Type.RUN_FINISHED,
                        Instant.EPOCH,
                        caseNumber,
                        status,
                        status + " case " + caseNumber,
                        new TestCaseResult(caseNumber, status, caseNumber % 23L, 1024),
                        null
                ));
                emittedCaseEvents++;
            }
            if (emittedCaseEvents == totalCases) {
                sentCompleted = true;
                events.add(SandboxTaskEvent.of(handle.judgeId(), SandboxTaskEvent.Type.COMPLETED, "completed"));
            }
            peakEventsPerPoll = Math.max(peakEventsPerPoll, events.size());
            return events;
        }

        @Override
        public void cancel(SandboxRunHandle handle) {
            sentCompleted = true;
        }

        int batchSize() {
            return batchSize;
        }

        int emittedCaseEvents() {
            return emittedCaseEvents;
        }

        int pollCount() {
            return pollCount;
        }

        int peakEventsPerPoll() {
            return peakEventsPerPoll;
        }
    }
}
