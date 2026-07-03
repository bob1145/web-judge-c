package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.CaseBatchRunner;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.ResultAggregator;
import com.example.demo.service.TaskPolicyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HighVolumeJudgeIntegrationTest {

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(30);
    private static final String TEST_USER_AGENT = "HighVolumeJudgeIntegrationTest";

    private static final String FIXED_GENERATOR = """
            #include <iostream>

            int main() {
                std::cout << 7 << std::endl;
                return 0;
            }
            """;

    private static final String ECHO_SOLUTION = """
            #include <iostream>

            int main() {
                int value = 0;
                std::cin >> value;
                std::cout << value << std::endl;
                return 0;
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper springObjectMapper;

    @Autowired
    private AccessCodeService accessCodeService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<JudgeScheduler> schedulers = new ArrayList<>();

    @AfterEach
    void stopExecutors() {
        for (JudgeScheduler scheduler : schedulers) {
            scheduler.shutdown();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void tenCaseHttpFlowKeepsFullSmallTaskResults() throws Exception {
        String sessionId = authenticatedSession();
        String judgeId = createJudgeTask(sessionId, request(10, ECHO_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION));

        mockMvc.perform(post("/judge/start/{judgeId}", judgeId)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(content().string("Judge task started"));

        JsonNode statusJson = awaitTerminalStatus(sessionId, judgeId);

        assertThat(statusJson.path("status").asText()).isEqualTo("AC");
        assertThat(statusJson.path("progress").asInt()).isEqualTo(100);
        assertThat(statusJson.path("results")).hasSize(10);
        for (int i = 0; i < 10; i++) {
            JsonNode result = statusJson.path("results").get(i);
            assertThat(result.path("caseNumber").asInt()).isEqualTo(i + 1);
            assertThat(result.path("status").asText()).isEqualTo("AC");
        }
        assertThat(statusJson.path("summary").path("totalCases").asInt()).isEqualTo(10);
        assertThat(statusJson.path("summary").path("completedCases").asInt()).isEqualTo(10);
        assertThat(statusJson.path("summary").path("ac").asInt()).isEqualTo(10);
    }

    @Test
    void thousandCaseSyntheticFlowBatchesThrottlesAndKeepsFailureSamples() throws Exception {
        Map<Integer, String> failures = new LinkedHashMap<>();
        failures.put(7, "WA");
        failures.put(111, "TLE");
        failures.put(222, "MLE");
        failures.put(333, "RE");
        failures.put(444, "System Error");
        failures.put(555, "OUTPUT_LIMIT_EXCEEDED");

        SyntheticFlowResult result = runSyntheticFlow(1_000, 1_000, failures, 4, 1);

        assertThat(result.policy().highVolume()).isTrue();
        assertThat(result.outcome().getSubmittedCases()).isEqualTo(1_000);
        assertThat(result.outcome().getCompletedCases()).isEqualTo(1_000);
        assertThat(result.outcome().getPeakScheduledFutures())
                .isLessThanOrEqualTo(result.policy().maxConcurrentCasesPerTask());
        assertThat(result.websocketMessages()).isLessThanOrEqualTo(3);
        assertThat(result.finalPayloadBytes()).isLessThan(65_536);
        assertThat(result.persistedProgress().getResults()).isNull();
        assertSummaryCounters(result.summary(), 1_000, failures, 4);
    }

    @Test
    void hundredThousandSyntheticFlowTraversesCoreChainWithoutLinearPayloads() throws Exception {
        Map<Integer, String> failures = new LinkedHashMap<>();
        failures.put(3, "WA");
        failures.put(10, "TLE");
        failures.put(20, "MLE");
        failures.put(30, "RE");
        failures.put(40, "System Error");
        failures.put(50, "OUTPUT_LIMIT_EXCEEDED");
        failures.put(99_999, "WA");
        Instant started = Instant.now();

        SyntheticFlowResult result = runSyntheticFlow(100_000, 5_000, failures, 5, 1_000);
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
        assertThat(result.policy().highVolume()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(JudgeStatus.WA);
        assertThat(result.outcome().getSubmittedCases()).isEqualTo(100_000);
        assertThat(result.outcome().getCompletedCases()).isEqualTo(100_000);
        assertThat(result.outcome().getPeakScheduledFutures())
                .isLessThanOrEqualTo(result.policy().maxConcurrentCasesPerTask());
        assertThat(result.outcome().getPeakScheduledFutures())
                .isLessThanOrEqualTo(result.policy().batchSize() + result.policy().maxConcurrentCasesPerTask());
        assertThat(result.finalPayloadBytes()).isLessThan(65_536);
        assertThat(result.persistedProgress().getResults()).isNull();
        assertSummaryCounters(result.summary(), 100_000, failures, 5);
    }

    @Test
    void docsDescribeProfilesValidationRiskAndRollback() throws Exception {
        Path runbook = Path.of("docs/judge-hardening-runbook.md");
        assertThat(runbook).exists();
        String runbookText = Files.readString(runbook);
        assertThat(runbookText)
                .contains("trusted-local")
                .contains("local-large")
                .contains("intranet-large")
                .contains("Expected logs")
                .contains("curl")
                .contains("browser")
                .contains("Rollback");

        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme)
                .contains("Public exposure is unsupported")
                .contains("strong authentication")
                .contains("sandbox")
                .contains("audit")
                .contains("isolation");
    }

    private SyntheticFlowResult runSyntheticFlow(
            int totalCases,
            int largeModeThreshold,
            Map<Integer, String> failures,
            int maxFailureSamples,
            int publishEvery
    ) throws Exception {
        ExecutionProperties properties = highVolumeProperties(largeModeThreshold, maxFailureSamples);
        MemoryConfiguration memory = new MemoryConfiguration();
        memory.setDefaultLimit(64L * 1024 * 1024);
        memory.setMaxLimit(512L * 1024 * 1024);
        JudgeRequest request = syntheticRequest(totalCases);
        ResolvedTaskPolicy policy = new TaskPolicyResolver(properties, memory).resolve(request);
        FileTaskStore store = new FileTaskStore(objectMapper, tempDir.resolve("storage-" + totalCases));
        String judgeId = "synthetic-" + totalCases;
        createStoredTask(store, judgeId, policy);

        ExecutorService schedulerExecutor = executor(1);
        ExecutorService caseExecutor = executor(8);
        JudgeScheduler scheduler = new JudgeScheduler(properties, store, schedulerExecutor);
        schedulers.add(scheduler);
        CaseBatchRunner caseBatchRunner = new CaseBatchRunner(caseExecutor);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ProgressPublisher publisher = new ProgressPublisher(
                messagingTemplate,
                store,
                objectMapper,
                new MutableClock(Instant.parse("2026-07-02T00:00:00Z")),
                properties.getProgressPublishInterval()
        );
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<CaseBatchRunner.RunOutcome> outcome = new AtomicReference<>();
        AtomicReference<JudgeProgress> finalProgress = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger completed = new AtomicInteger();

        scheduler.enqueue(judgeId, context -> {
            try {
                ResultAggregator aggregator = new ResultAggregator(
                        policy.highVolume(),
                        policy.requestedCases(),
                        properties.getMaxFailureSamples(),
                        properties.getMaxSlowSamples()
                );
                publisher.publish(judgeId, new JudgeProgress("RUNNING", "synthetic start", 0, null, aggregator.toSummary()));
                CaseBatchRunner.RunOutcome runOutcome = caseBatchRunner.run(
                        policy.requestedCases(),
                        policy,
                        context.cancellationToken(),
                        caseNumber -> syntheticResult(caseNumber, failures),
                        result -> {
                            aggregator.accept(result);
                            context.recordCompletedCase();
                            int doneCases = completed.incrementAndGet();
                            if (doneCases % publishEvery == 0 || doneCases == policy.requestedCases()) {
                                int progress = Math.min(99, (int) ((double) doneCases / policy.requestedCases() * 100));
                                publisher.publish(
                                        judgeId,
                                        new JudgeProgress("RUNNING", doneCases + " cases complete", progress, null, aggregator.toSummary())
                                );
                            }
                        }
                );
                outcome.set(runOutcome);
                finalProgress.set(publisher.publish(judgeId, aggregator.toFinalProgress()));
            } catch (Throwable ex) {
                failure.set(ex);
                if (ex instanceof Exception exception) {
                    throw exception;
                }
                if (ex instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(ex);
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("Synthetic judge flow failed", failure.get());
        }
        awaitIdle(scheduler);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(anyString(), payloadCaptor.capture());
        List<JudgeProgress> sentProgress = payloadCaptor.getAllValues().stream()
                .map(JudgeProgress.class::cast)
                .toList();
        JudgeProgress finalSent = sentProgress.get(sentProgress.size() - 1);
        JudgeProgress persistedProgress = store.findSummary(judgeId).orElseThrow();
        JudgeTask task = store.find(judgeId).orElseThrow();
        int finalPayloadBytes = objectMapper.writeValueAsBytes(finalSent).length;

        return new SyntheticFlowResult(
                policy,
                outcome.get(),
                finalProgress.get().getSummary(),
                persistedProgress,
                task,
                sentProgress.size(),
                finalPayloadBytes
        );
    }

    private void assertSummaryCounters(
            JudgeSummary summary,
            int totalCases,
            Map<Integer, String> failures,
            int maxFailureSamples
    ) {
        assertThat(summary.getTotalCases()).isEqualTo(totalCases);
        assertThat(summary.getCompletedCases()).isEqualTo(totalCases);
        assertThat(summary.getAc()).isEqualTo(totalCases - failures.size());
        assertThat(summary.getWa()).isEqualTo(countFailures(failures, "WA"));
        assertThat(summary.getTle()).isEqualTo(countFailures(failures, "TLE"));
        assertThat(summary.getMle()).isEqualTo(countFailures(failures, "MLE"));
        assertThat(summary.getRe()).isEqualTo(countFailures(failures, "RE"));
        assertThat(summary.getSystemError()).isEqualTo(countFailures(failures, "System Error"));
        assertThat(summary.getOutputLimitExceeded()).isEqualTo(countFailures(failures, "OUTPUT_LIMIT_EXCEEDED"));
        assertThat(summary.getFirstFailedCase()).isEqualTo(failures.keySet().iterator().next());
        assertThat(summary.getFailureSamples()).hasSize(maxFailureSamples);
        assertThat(summary.getFailureSamples())
                .extracting(event -> event.getCaseNumber())
                .containsExactlyElementsOf(failures.keySet().stream().limit(maxFailureSamples).toList());
    }

    private int countFailures(Map<Integer, String> failures, String status) {
        return (int) failures.values().stream()
                .filter(value -> value.equals(status))
                .count();
    }

    private TestCaseResult syntheticResult(int caseNumber, Map<Integer, String> failures) {
        String status = failures.getOrDefault(caseNumber, "AC");
        return new TestCaseResult(caseNumber, status, caseNumber % 17 + 1L, 1024);
    }

    private ExecutionProperties highVolumeProperties(int largeModeThreshold, int maxFailureSamples) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setProfile("local-large");
        properties.setMaxCasesPerTask(100_000);
        properties.setLargeModeThreshold(largeModeThreshold);
        properties.setTaskQueueCapacity(3);
        properties.setMaxConcurrentTasks(1);
        properties.setBatchSize(128);
        properties.setMaxConcurrentCasesPerTask(4);
        properties.setMaxFailureSamples(maxFailureSamples);
        properties.setMaxSlowSamples(10);
        properties.setProgressPublishInterval(Duration.ofSeconds(1));
        properties.setDefaultTimeLimit(Duration.ofSeconds(1));
        properties.setMinTimeLimit(Duration.ofMillis(100));
        properties.setMaxTimeLimit(Duration.ofSeconds(5));
        properties.setMaxTaskRuntime(Duration.ofSeconds(30));
        properties.setMinMemoryLimitBytes(16L * 1024 * 1024);
        properties.setMaxOutputBytesPerCase(64L * 1024);
        return properties;
    }

    private JudgeRequest syntheticRequest(int totalCases) {
        JudgeRequest request = new JudgeRequest();
        request.setTestCases(totalCases);
        request.setTimeLimit(1_000);
        request.setMemoryLimit(64L * 1024 * 1024);
        return request;
    }

    private void createStoredTask(FileTaskStore store, String judgeId, ResolvedTaskPolicy policy) throws IOException {
        Path workDir = store.taskDirectory(judgeId);
        store.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.CREATED)
                .requestedCases(policy.requestedCases())
                .mode(policy.profile())
                .policy(policy)
                .workDir(workDir.toString())
                .createdAt(Instant.now())
                .build());
    }

    private String authenticatedSession() {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", TEST_USER_AGENT);
        return session.getSessionId();
    }

    private String createJudgeTask(String sessionId, Map<String, Object> request) throws Exception {
        MvcResult result = mockMvc.perform(post("/judge")
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(springObjectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode responseJson = springObjectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(responseJson.path("status").asText()).isEqualTo("CREATED");
        assertThat(responseJson.path("requestedCases").asInt()).isEqualTo(request.get("testCases"));
        assertThat(responseJson.path("highVolume").asBoolean()).isFalse();
        String judgeId = responseJson.path("judgeId").asText();
        UUID.fromString(judgeId);
        return judgeId;
    }

    private JsonNode awaitTerminalStatus(String sessionId, String judgeId) throws Exception {
        long deadline = System.nanoTime() + STATUS_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mockMvc.perform(get("/judge/status/{judgeId}", judgeId)
                            .header("X-Session-ID", sessionId)
                            .header("User-Agent", TEST_USER_AGENT)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode statusJson = springObjectMapper.readTree(result.getResponse().getContentAsString());
            if (statusJson.path("progress").asInt() == 100) {
                return statusJson;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for terminal judge status for " + judgeId);
    }

    private Map<String, Object> request(int testCases, String userCode, String generatorCode, String bruteForceCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userCode", userCode);
        request.put("generatorCode", generatorCode);
        request.put("bruteForceCode", bruteForceCode);
        request.put("timeLimit", 2_000);
        request.put("memoryLimit", 268_435_456L);
        request.put("precision", 0.0);
        request.put("testCases", testCases);
        request.put("useSpecialJudge", false);
        request.put("specialJudgeCode", "");
        return request;
    }

    private ExecutorService executor(int threads) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        executors.add(executor);
        return executor;
    }

    private void awaitIdle(JudgeScheduler scheduler) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JudgeScheduler.QueueSnapshot snapshot = scheduler.snapshot();
            if (snapshot.runningCount() == 0 && snapshot.queuedCount() == 0) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    private record SyntheticFlowResult(
            ResolvedTaskPolicy policy,
            CaseBatchRunner.RunOutcome outcome,
            JudgeSummary summary,
            JudgeProgress persistedProgress,
            JudgeTask task,
            int websocketMessages,
            int finalPayloadBytes
    ) {
    }

    private static final class MutableClock extends Clock {
        private final Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
