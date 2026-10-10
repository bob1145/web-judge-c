package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.UserSession;
import com.example.demo.service.CaseBatchRunner;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.QuotaService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.SandboxProcessRunner;
import com.example.demo.service.SandboxEventIngestor;
import com.example.demo.service.TaskPolicyResolver;
import com.example.demo.service.sandbox.LocalFakeSandboxRunner;
import com.example.demo.service.sandbox.SandboxRunner;
import com.example.demo.service.sandbox.TestTlsRemoteWorkerRunner;
import com.example.demo.support.WorkerTlsCertificates;
import com.example.demo.support.RunnerProtocolFixtures;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class JudgeSandboxOrchestrationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private ExecutorService schedulerExecutor;
    private ThreadPoolTaskExecutor caseExecutor;
    private JudgeScheduler scheduler;
    private FileTaskStore store;

    @TempDir
    Path tempDir;

    @AfterEach
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
        }
        if (caseExecutor != null) {
            caseExecutor.shutdown();
        }
    }

    @Test
    void cancellationFinalWriteFailureKeepsCreatedTaskAndRetryReleasesOnlyItsPendingEntry() throws Exception {
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of());
        JudgeService service = service(Optional.of(runner));
        String judgeId = "cancel-summary-write-failure";
        service.createJudgeTask(request(1), judgeId);
        service.createJudgeTask(request(1), "unrelated-created-task");
        service.markSessionActive(judgeId);
        Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingJudgeTasks");
        Object unrelated = pending.get("unrelated-created-task");
        FileTaskStore failingStore = spy(store);
        doAnswer(invocation -> {
            throw new IOException("injected summary write failure");
        }).when(failingStore).finalizeTask(eq(judgeId), any(JudgeProgress.class));
        ReflectionTestUtils.setField(scheduler, "taskStore", failingStore);

        assertThatThrownBy(() -> service.cancelJudgeTask(judgeId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to save stopped judge summary");
        assertThat(store.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
        assertThat(pending.containsKey(judgeId)).isTrue();
        byte[] metadata = Files.readAllBytes(store.taskDirectory(judgeId).resolve("metadata.json"));
        assertThat(store.taskDirectory(judgeId).resolve("final.json")).doesNotExist();
        ReflectionTestUtils.setField(scheduler, "taskStore", store);

        var response = service.cancelJudgeTask(judgeId);

        assertThat(response.accepted()).isTrue();
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.running()).isFalse();
        assertThat(response.queued()).isFalse();
        assertThat(pending.containsKey(judgeId)).isFalse();
        assertThat(pending.get("unrelated-created-task")).isSameAs(unrelated);
        assertThat(store.find("unrelated-created-task").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
        assertThat(Files.readAllBytes(store.taskDirectory(judgeId).resolve("metadata.json"))).isNotEqualTo(metadata);
        JudgeProgress committed = store.findSummary(judgeId).orElseThrow();
        assertThat(committed.getStatus()).isEqualTo("CANCELLED");
        assertThat(committed.getSummary().getCompletedCases()).isZero();
        assertThat(committed.getRevision()).isEqualTo(1L);
        assertThat(committed.getFinalizedAt()).isNotNull();
        assertThat(store.find(judgeId).orElseThrow().getStartedAt()).isNull();
        assertThat(store.find(judgeId).orElseThrow().getSandboxRunHandle()).isNull();
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
        assertThat(runner.startedSpecs()).isEmpty();
        assertThatThrownBy(() -> service.startJudgeTask(judgeId))
                .isInstanceOf(JudgeScheduler.TaskAlreadyFinishedException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {14, 16})
    void concurrentStartCancelAndScanExpiryConvergeWithoutResurrectionOrPendingLeak(int ageMinutes) throws Exception {
        String judgeId = "created-request-race";
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED, Clock.systemUTC().instant(),
                        1, "AC", "case accepted", new TestCaseResult(1, "AC", 1, 128), null),
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "completed")));
        JudgeService service = service(Optional.of(runner));
        service.createJudgeTask(request(1), judgeId);
        service.createJudgeTask(request(1), "unrelated-created-task");
        var aged = store.find(judgeId).orElseThrow();
        aged.setCreatedAt(Clock.systemUTC().instant().minus(Duration.ofMinutes(ageMinutes)));
        objectMapper.writeValue(store.taskDirectory(judgeId).resolve("metadata.json").toFile(), aged);
        Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingJudgeTasks");
        Object unrelated = pending.get("unrelated-created-task");
        ExecutorService requests = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> start = requests.submit(() -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    service.startJudgeTask(judgeId);
                } catch (JudgeScheduler.TaskAlreadyFinishedException expected) {
                    assertThat(store.find(judgeId).orElseThrow().getStatus().isTerminal()).isTrue();
                }
                return null;
            });
            Future<?> cancel = requests.submit(() -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return service.cancelJudgeTask(judgeId);
            });
            Future<?> expiry = requests.submit(() -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                service.expireCreatedTasks(Instant.now());
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            start.get(5, TimeUnit.SECONDS);
            cancel.get(5, TimeUnit.SECONDS);
            expiry.get(5, TimeUnit.SECONDS);
            schedulerExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            var finished = store.find(judgeId).orElseThrow();
            assertThat(finished.getStatus()).isIn(JudgeStatus.AC, JudgeStatus.CANCELLED, JudgeStatus.STALE);
            JudgeProgress stored = store.findSummary(judgeId).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(finished.getStatus().name());
            assertThat(service.getJudgeStatus(judgeId)).usingRecursiveComparison().isEqualTo(stored);
            assertThat(stored.getSummary().getCompletedCases()).isBetween(0, 1);
            assertThat(stored.getSummary().getAc()).isEqualTo(stored.getSummary().getCompletedCases());
            assertThat(runner.startedSpecs()).hasSizeLessThanOrEqualTo(1);
            if (ageMinutes == 16) {
                assertThat(finished.getStatus()).isEqualTo(JudgeStatus.STALE);
                assertThat(stored.getSummary().getCompletedCases()).isZero();
                assertThat(finished.getStartedAt()).isNull();
                assertThat(finished.getSandboxRunHandle()).isNull();
                assertThat(runner.startedSpecs()).isEmpty();
            } else {
                assertThat(finished.getStatus()).isNotEqualTo(JudgeStatus.STALE);
            }
            assertThat(pending.containsKey(judgeId)).isFalse();
            assertThat(pending.get("unrelated-created-task")).isSameAs(unrelated);
            assertThat(store.find("unrelated-created-task").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
            assertThat(scheduler.snapshot().runningCount()).isZero();
            assertThat(scheduler.snapshot().queuedCount()).isZero();
            byte[] metadata = Files.readAllBytes(store.taskDirectory(judgeId).resolve("metadata.json"));
            byte[] summary = Files.readAllBytes(store.taskDirectory(judgeId).resolve("summary.json"));
            assertThat(service.cancelJudgeTask(judgeId).accepted()).isFalse();
            assertThatThrownBy(() -> service.startJudgeTask(judgeId))
                    .isInstanceOf(JudgeScheduler.TaskAlreadyFinishedException.class);
            assertThat(Files.readAllBytes(store.taskDirectory(judgeId).resolve("metadata.json"))).isEqualTo(metadata);
            assertThat(Files.readAllBytes(store.taskDirectory(judgeId).resolve("summary.json"))).isEqualTo(summary);
            System.out.printf("TASK-18 scan race: ageMinutes=%d terminal=%s completed=%d runnerStarts=%d "
                            + "pendingReleased=true permitsReleased=true retryStable=true%n",
                    ageMinutes, finished.getStatus(), stored.getSummary().getCompletedCases(), runner.startedSpecs().size());
        } finally {
            release.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void createdScanHonorsTaskBudgetAndResumesWithoutStarvingLaterEntries() throws Exception {
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of());
        JudgeService service = service(Optional.of(runner));
        ExecutionProperties execution = (ExecutionProperties) ReflectionTestUtils.getField(service, "executionProperties");
        execution.setCreatedTaskScanMaxTasks(2);
        Instant now = Instant.now();
        for (int taskIndex = 0; taskIndex < 4; taskIndex++) {
            String judgeId = "scan-expired-" + taskIndex;
            service.createJudgeTask(request(1), judgeId);
            var task = store.find(judgeId).orElseThrow();
            task.setCreatedAt(now.minus(Duration.ofMinutes(16)));
            objectMapper.writeValue(store.taskDirectory(judgeId).resolve("metadata.json").toFile(), task);
        }
        service.createJudgeTask(request(1), "scan-young");
        Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingJudgeTasks");
        for (int round = 0; round < 3; round++) {
            int before = pending.size();
            service.expireCreatedTasks(now);
            assertThat(before - pending.size()).isBetween(0, 2);
        }
        assertThat(pending).hasSize(1);
        assertThat(pending.containsKey("scan-young")).isTrue();
        FileTaskStore cold = new FileTaskStore(objectMapper, tempDir);
        for (int taskIndex = 0; taskIndex < 4; taskIndex++) {
            String judgeId = "scan-expired-" + taskIndex;
            var task = cold.find(judgeId).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(JudgeStatus.STALE);
            assertThat(task.getStartedAt()).isNull();
            assertThat(task.getSandboxRunHandle()).isNull();
            JudgeProgress terminal = cold.findSummary(judgeId).orElseThrow();
            assertThat(terminal.getStatus()).isEqualTo("STALE");
            assertThat(terminal.getSummary().getTotalCases()).isEqualTo(1);
            assertThat(terminal.getSummary().getCompletedCases()).isZero();
            assertThat(terminal.getRevision()).isEqualTo(1L);
            assertThat(terminal.getFinalizedAt()).isNotNull();
        }
        Path finalFile = store.taskDirectory("scan-expired-0").resolve("final.json");
        byte[] finalBytes = Files.readAllBytes(finalFile);
        service.expireCreatedTasks(now);
        assertThat(Files.readAllBytes(finalFile)).isEqualTo(finalBytes);
        assertThat(cold.find("scan-young").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
        assertThat(runner.startedSpecs()).isEmpty();
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @Test
    void createdScanLetsStartedCommitFinishButDoesNotStartAnotherAfterTimeBudget() throws Exception {
        JudgeService service = service(Optional.of(new LocalFakeSandboxRunner(List.of())));
        ExecutionProperties execution = (ExecutionProperties) ReflectionTestUtils.getField(service, "executionProperties");
        execution.setCreatedTaskScanBudget(Duration.ofMillis(50));
        Instant now = Instant.now();
        for (int taskIndex = 0; taskIndex < 3; taskIndex++) {
            String judgeId = "scan-budget-" + taskIndex;
            service.createJudgeTask(request(1), judgeId);
            var task = store.find(judgeId).orElseThrow();
            task.setCreatedAt(now.minus(Duration.ofMinutes(16)));
            objectMapper.writeValue(store.taskDirectory(judgeId).resolve("metadata.json").toFile(), task);
        }
        var inspected = new java.util.concurrent.atomic.AtomicInteger();
        JudgeScheduler slowScheduler = spy(scheduler);
        doAnswer(invocation -> {
            inspected.incrementAndGet();
            TimeUnit.MILLISECONDS.sleep(75);
            return invocation.callRealMethod();
        }).when(slowScheduler).expireCreatedTask(any(String.class), eq(now));
        ReflectionTestUtils.setField(service, "judgeScheduler", slowScheduler);
        service.expireCreatedTasks(now);
        assertThat(inspected).hasValue(1);
        Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingJudgeTasks");
        assertThat(pending).hasSize(2);
        long committed = store.findAll().stream().filter(task -> task.getStatus() == JudgeStatus.STALE).count();
        assertThat(committed).isEqualTo(1);
        ReflectionTestUtils.setField(service, "judgeScheduler", scheduler);
        execution.setCreatedTaskScanBudget(Duration.ofSeconds(1));
        service.expireCreatedTasks(now);
        assertThat(pending).isEmpty();
    }

    @Test
    void createdScanAtomicWriteFailureKeepsPendingAndOtherTasksContinueThenRetryCommits() throws Exception {
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of());
        JudgeService service = service(Optional.of(runner));
        Instant now = Instant.now();
        String failedId = "scan-write-failure";
        String otherId = "scan-independent";
        for (String judgeId : List.of(failedId, otherId)) {
            service.createJudgeTask(request(1), judgeId);
            var task = store.find(judgeId).orElseThrow();
            task.setCreatedAt(now.minus(Duration.ofMinutes(16)));
            objectMapper.writeValue(store.taskDirectory(judgeId).resolve("metadata.json").toFile(), task);
        }
        Path failedDirectory = store.taskDirectory(failedId);
        byte[] metadata = Files.readAllBytes(failedDirectory.resolve("metadata.json"));
        AtomicBoolean failOnce = new AtomicBoolean(true);
        FileTaskStore failingStore = new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path temporary, Path target) throws IOException {
                if (target.equals(failedDirectory.resolve("final.json")) && failOnce.getAndSet(false)) {
                    Files.createDirectory(target);
                }
                super.moveAtomically(temporary, target);
            }
        };
        ReflectionTestUtils.setField(scheduler, "taskStore", failingStore);
        service.expireCreatedTasks(now);
        Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(service, "pendingJudgeTasks");
        assertThat(pending.containsKey(failedId)).isTrue();
        assertThat(pending.containsKey(otherId)).isFalse();
        assertThat(Files.readAllBytes(failedDirectory.resolve("metadata.json"))).isEqualTo(metadata);
        assertThat(failedDirectory.resolve("final.json")).isDirectory();
        assertThat(store.find(otherId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.STALE);
        Files.delete(failedDirectory.resolve("final.json"));
        assertThat(store.find(failedId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
        assertThat(store.findSummary(failedId)).isEmpty();
        service.expireCreatedTasks(now);
        FileTaskStore cold = new FileTaskStore(objectMapper, tempDir);
        JudgeProgress terminal = cold.findSummary(failedId).orElseThrow();
        assertThat(terminal.getStatus()).isEqualTo("STALE");
        assertThat(terminal.getSummary().getCompletedCases()).isZero();
        assertThat(terminal.getRevision()).isEqualTo(1L);
        assertThat(cold.find(failedId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.STALE);
        assertThat(pending).isEmpty();
        assertThat(runner.startedSpecs()).isEmpty();
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @Test
    void delayedRunningNotificationCannotReplaceDurableTerminalPollingSnapshot() throws Exception {
        JudgeService service = service(Optional.of(new LocalFakeSandboxRunner(List.of())));
        String judgeId = "delayed-running-cache";
        service.createJudgeTask(request(1), judgeId);
        ProgressPublisher publisher = spy((ProgressPublisher) ReflectionTestUtils.getField(service, "progressPublisher"));
        ReflectionTestUtils.setField(service, "progressPublisher", publisher);
        CountDownLatch runningPersisted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        doAnswer(invocation -> {
            JudgeProgress published = (JudgeProgress) invocation.callRealMethod();
            JudgeProgress offered = invocation.getArgument(1);
            if ("RUNNING".equals(offered.getStatus())) {
                runningPersisted.countDown();
                assertThat(releaseRunning.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return published;
        }).when(publisher).publish(eq(judgeId), any(JudgeProgress.class));
        JudgeProgress running = new JudgeProgress("RUNNING", "case executing", 0, null,
                new JudgeSummary(1, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of(), List.of(), null));
        JudgeProgress terminal = new JudgeProgress("AC", "case accepted", 100,
                List.of(new TestCaseResult(1, "AC", 1, 128)),
                new JudgeSummary(1, 1, 1, 0, 0, 0, 0, 0, 0, null, List.of(), List.of(), null));
        ExecutorService publishers = Executors.newSingleThreadExecutor();
        try {
            Future<?> lateRunning = publishers.submit(() -> {
                ReflectionTestUtils.invokeMethod(service, "safeSendMessage", "/topic/progress/" + judgeId, running);
            });
            assertThat(runningPersisted.await(5, TimeUnit.SECONDS)).isTrue();
            ReflectionTestUtils.invokeMethod(service, "safeSendMessage", "/topic/progress/" + judgeId, terminal);
            releaseRunning.countDown();
            lateRunning.get(5, TimeUnit.SECONDS);
            JudgeProgress persisted = store.findSummary(judgeId).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo("AC");
            assertThat(service.getJudgeStatus(judgeId)).usingRecursiveComparison().isEqualTo(persisted);
        } finally {
            releaseRunning.countDown();
            publishers.shutdownNow();
            assertThat(publishers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-insert", "after-insert", "existing"})
    @SuppressWarnings("unchecked")
    void failedPendingRegistrationDoesNotChargeCreationOrOverwriteAnExistingRegistration(String stage) throws Exception {
        JudgeService service = service(Optional.of(new LocalFakeSandboxRunner(List.of())));
        ExecutionProperties properties = (ExecutionProperties) ReflectionTestUtils.getField(service, "executionProperties");
        properties.setMaxQueuedTasksPerUser(1);
        JudgeRequest request = request(1);
        service.createJudgeTask(request, "unrelated-registration");
        Map<String, Object> original = (Map<String, Object>) ReflectionTestUtils.getField(service, "pendingJudgeTasks");
        Object existing = original.get("unrelated-registration");
        String judgeId = "pending-registration-failure";
        Map<String, Object> pending = spy(original);
        AtomicBoolean armed = new AtomicBoolean(true);
        if (stage.equals("existing")) {
            pending.put(judgeId, existing);
        } else {
            Answer<Object> registrationFailure = invocation -> {
                if (armed.getAndSet(false)) {
                    if (stage.equals("after-insert")) {
                        invocation.callRealMethod();
                    }
                    throw new IllegalStateException("simulated pending registration failure");
                }
                return invocation.callRealMethod();
            };
            doAnswer(registrationFailure).when(pending).put(eq(judgeId), any());
            doAnswer(registrationFailure).when(pending).putIfAbsent(eq(judgeId), any());
        }
        ReflectionTestUtils.setField(service, "pendingJudgeTasks", pending);
        UserSession owner = UserSession.builder().sessionId("registration-session").userId("registration-owner").build();
        String expectedMessage = stage.equals("existing")
                ? "Judge task is already registered" : "simulated pending registration failure";

        assertThatThrownBy(() -> service.createJudgeTask(request, judgeId, owner))
                .isInstanceOf(IllegalStateException.class).hasMessage(expectedMessage);

        assertThat(store.find(judgeId)).isEmpty();
        assertThat(store.taskDirectory(judgeId)).doesNotExist();
        assertThat(store.findAll().stream()
                .filter(task -> task.getOwnership() != null && task.getOwnership().isOwnedBy("registration-owner"))
                .toList()).isEmpty();
        assertThat(pending.get("unrelated-registration")).isSameAs(existing);
        assertThat(store.find("unrelated-registration")).isPresent();
        if (stage.equals("existing")) {
            assertThat(pending.get(judgeId)).isSameAs(existing);
        } else {
            assertThat(pending).doesNotContainKey(judgeId);
            service.createJudgeTask(request, judgeId, owner);
            assertThat(store.find(judgeId)).isPresent();
            assertThat(pending).containsKey(judgeId);
            assertThat(Files.readAllLines(store.taskDirectory(judgeId).resolve("events.jsonl"))).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-start", "throw", "null"})
    void unknownStartOutcomeRetainsPermitsButPreStartFailureDoesNot(String stage) throws Exception {
        java.util.concurrent.atomic.AtomicInteger startCalls = new java.util.concurrent.atomic.AtomicInteger();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                SandboxTaskEvent.of("unknown-next", SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE, "test unavailable"))) {
            @Override
            public com.example.demo.dto.SandboxRunHandle start(SandboxTaskSpec spec) {
                startCalls.incrementAndGet();
                if (stage.equals("before-start")) {
                    return super.start(spec);
                }
                if (stage.equals("throw")) {
                    throw new IllegalStateException("start acknowledgement lost");
                }
                return null;
            }
        };
        JudgeService service = service(Optional.of(runner));
        JudgeRequest firstRequest = request(1);
        UserSession owner = UserSession.builder().userId("unknown-owner").sessionId("unknown-session-a").build();
        UserSession sameOwner = UserSession.builder().userId("unknown-owner").sessionId("unknown-session-b").build();
        service.createJudgeTask(firstRequest, "unknown-start", owner);
        if (stage.equals("before-start")) {
            Files.createDirectory(store.taskDirectory("unknown-start").resolve("generator.cpp"));
        }
        service.createJudgeTask(request(1), "unknown-next", sameOwner);
        service.startJudgeTask("unknown-start");
        awaitProgress(service, "unknown-start", "SYSTEM_ERROR");
        schedulerExecutor.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);

        boolean unknown = !stage.equals("before-start");
        assertThat(startCalls).hasValue(unknown ? 1 : 0);
        assertThat(store.find("unknown-start").orElseThrow().getSandboxRunHandle()).isNull();
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(unknown ? 1 : 0);
        service.startJudgeTask("unknown-next");
        if (unknown) {
            assertThat(startCalls).hasValue(1);
            assertThat(store.find("unknown-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
        } else {
            awaitProgress(service, "unknown-next", "SANDBOX_UNAVAILABLE");
            schedulerExecutor.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(startCalls).hasValue(1);
            assertThat(scheduler.snapshot().runningCount()).isZero();
            assertThat(scheduler.snapshot().queuedCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "timeout"})
    void realHttpsEmptyWorkerPollsWaitForValidatedEventsWithoutClaimingDomainStop(
            String firstBatch, @TempDir Path certificateDirectory)
            throws Exception {
        WorkerTlsCertificates.Material tls = WorkerTlsCertificates.create(certificateDirectory)
                .issue("unknown-worker", "dns:localhost,ip:127.0.0.1", false);
        HttpsServer server = HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.serverContext()));
        ExecutorService workerRequests = Executors.newFixedThreadPool(2);
        server.setExecutor(workerRequests);
        CountDownLatch releaseFirstPoll = new CountDownLatch(1);
        AtomicBoolean firstPollReturned = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> attempt = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cancels = new java.util.concurrent.atomic.AtomicInteger();
        List<String> authentication = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Long> pollTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        String judgeId = "unknown-worker-wait";
        server.createContext("/", exchange -> {
            authentication.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String response;
            if (!"Bearer worker-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                response = "unauthorized";
            } else if (path.equals("/api/v1/capabilities")) {
                response = objectMapper.writeValueAsString(SandboxCapabilities.builder().provider("remote-worker")
                        .protocol(RunnerProtocolFixtures.workerProtocol()).build());
            } else if (path.equals("/api/v1/runs") && exchange.getRequestMethod().equals("POST")) {
                var manifest = objectMapper.readTree(exchange.getRequestBody()).path("manifest");
                attempt.set(manifest.path("attemptId").asText());
                response = objectMapper.writeValueAsString(Map.of("judgeId", judgeId, "provider", "remote-worker",
                        "protocolVersion", 2, "attemptId", attempt.get(), "runId", "unknown-wait-run", "eventCursor", "0"));
            } else if (path.equals("/api/v1/runs/unknown-wait-run/events")) {
                pollTimes.add(System.nanoTime());
                int poll = polls.incrementAndGet();
                if (poll == 1 && firstBatch.equals("timeout")) {
                    try {
                        releaseFirstPoll.await(7, TimeUnit.SECONDS);
                        firstPollReturned.set(true);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                response = poll <= 2 ? "[]" : objectMapper.writeValueAsString(List.of(
                        new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_STARTED, Instant.now(), null, null,
                                "running", null, null, 2, attempt.get(), 1L),
                        new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED, Instant.now(), 1, "AC",
                                "accepted", new TestCaseResult(1, "AC", 1, 64), null, 2, attempt.get(), 2L),
                        new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.COMPLETED, Instant.now(), null, null,
                                "completed", null, null, 2, attempt.get(), 3L)));
            } else if (path.equals("/api/v1/runs/unknown-wait-run/cancel")) {
                cancels.incrementAndGet();
                status = 202;
                response = "";
            } else {
                status = 404;
                response = "not found";
            }
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            WorkerProperties worker = new WorkerProperties();
            worker.setEndpoint("https://127.0.0.1:" + server.getAddress().getPort());
            worker.setAuthToken("worker-secret");
            worker.setRequestTimeout(Duration.ofSeconds(2));
            JudgeService service = service(Optional.of(new TestTlsRemoteWorkerRunner(worker, objectMapper, tls.clientContext())));
            service.createJudgeTask(request(1), judgeId);
            service.startJudgeTask(judgeId);
            schedulerExecutor.submit(() -> {}).get(7, TimeUnit.SECONDS);
            JudgeProgress progress = service.getJudgeStatus(judgeId);
            assertThat(progress.getStatus()).isEqualTo("AC");
            assertThat(polls).hasValue(3);
            if (firstBatch.equals("timeout")) {
                assertThat(firstPollReturned).isFalse();
            }
            assertThat(pollTimes.get(1) - pollTimes.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(90).toNanos());
            assertThat(pollTimes.get(2) - pollTimes.get(1)).isGreaterThanOrEqualTo(Duration.ofMillis(90).toNanos());
            assertThat(authentication).hasSize(6).containsOnly("Bearer worker-secret");
            assertThat(progress.getSummary().getCompletedCases()).isEqualTo(1);
            assertThat(progress.getSummary().getAc()).isEqualTo(1);
            assertThat(store.findSummary(judgeId).orElseThrow().getSummary()).isEqualTo(progress.getSummary());
            assertThat(cancels).hasValue(1);
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        } finally {
            releaseFirstPoll.countDown();
            server.stop(0);
            workerRequests.shutdownNow();
            assertThat(workerRequests.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unavailableWorkerWithoutCreateRequestReleasesTheQueuedTask(@TempDir Path certificateDirectory) throws Exception {
        WorkerTlsCertificates.Material tls = WorkerTlsCertificates.create(certificateDirectory)
                .issue("unavailable-worker", "dns:localhost,ip:127.0.0.1", false);
        HttpsServer server = HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.serverContext()));
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger runtimeRequests = new java.util.concurrent.atomic.AtomicInteger();
        List<String> authentication = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/api/v1/capabilities", exchange -> {
            authentication.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (probes.incrementAndGet() == 1) {
                firstRequest.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "down".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/api/v1/runs", exchange -> {
            runtimeRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            WorkerProperties worker = new WorkerProperties();
            worker.setEndpoint("https://127.0.0.1:" + server.getAddress().getPort());
            worker.setAuthToken("worker-secret");
            worker.setRequestTimeout(Duration.ofSeconds(2));
            JudgeService service = service(Optional.of(new TestTlsRemoteWorkerRunner(worker, objectMapper, tls.clientContext())));
            service.createJudgeTask(request(1), "unavailable-first");
            service.createJudgeTask(request(1), "unavailable-next");
            service.startJudgeTask("unavailable-first");
            assertThat(firstRequest.await(2, TimeUnit.SECONDS)).isTrue();
            service.startJudgeTask("unavailable-next");
            assertThat(store.find("unavailable-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
            release.countDown();
            for (String judgeId : List.of("unavailable-first", "unavailable-next")) {
                JudgeProgress progress = awaitProgress(service, judgeId, "SANDBOX_UNAVAILABLE");
                assertThat(progress.getSummary().getCompletedCases()).isZero();
                assertThat(progress.getSummary().getAc()).isZero();
            }
            schedulerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(probes).hasValue(2);
            assertThat(authentication).containsExactly("Bearer worker-secret", "Bearer worker-secret");
            assertThat(runtimeRequests).hasValue(0);
            assertThat(scheduler.snapshot().runningCount()).isZero();
            assertThat(scheduler.snapshot().queuedCount()).isZero();
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void startupTimeIsDeductedBeforePollingInsteadOfRenewingTheTaskBudget() throws Exception {
        String judgeId = "shared-startup-budget";
        java.util.concurrent.atomic.AtomicReference<Duration> startupBudget = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Duration> pollingBudget = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicBoolean cleaned = new AtomicBoolean();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE, "test unavailable"))) {
            @Override
            public com.example.demo.dto.SandboxRunHandle start(SandboxTaskSpec spec, Duration timeout) {
                startupBudget.set(timeout);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Startup interrupted", interrupted);
                }
                return super.start(spec);
            }

            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                pollingBudget.compareAndSet(null, timeout);
                return super.pollEvents(handle);
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleaned.set(true);
                super.cleanupResidual(handle);
            }
        };
        JudgeService service = service(Optional.of(runner));
        service.createJudgeTask(request(1), judgeId);
        service.startJudgeTask(judgeId);
        awaitProgress(service, judgeId, "SANDBOX_UNAVAILABLE");
        schedulerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);

        assertThat(startupBudget.get()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(pollingBudget.get()).isPositive().isLessThan(startupBudget.get().minusMillis(150));
        assertThat(cleaned).isTrue();
        assertThat(store.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.SANDBOX_UNAVAILABLE);
        assertThat(scheduler.snapshot().runningCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productionProfileSubmitsOneSandboxTaskSpecAndPersistsRunnerSummaryWithoutLegacyCompile(boolean cleanupFails) throws Exception {
        JudgeSummary summary = new JudgeSummary(2, 2, 2, 0, 0, 0, 0, 0, 0, null, List.of(), List.of(), null);
        List<com.example.demo.dto.SandboxRunHandle> cleanedHandles = new java.util.ArrayList<>();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                SandboxTaskEvent.of("judge-runner", SandboxTaskEvent.Type.COMPILE_STARTED, "compile started"),
                SandboxTaskEvent.of("judge-runner", SandboxTaskEvent.Type.RUN_STARTED, "running"),
                new SandboxTaskEvent(
                        "judge-runner",
                        SandboxTaskEvent.Type.RUN_FINISHED,
                        Clock.systemUTC().instant(),
                        1,
                        "AC",
                        "case 1 accepted",
                        new TestCaseResult(1, "AC", 7, 128),
                        null
                ),
                new SandboxTaskEvent(
                        "judge-runner",
                        SandboxTaskEvent.Type.RUN_FINISHED,
                        Clock.systemUTC().instant(),
                        2,
                        "AC",
                        "case 2 accepted",
                        new TestCaseResult(2, "AC", 8, 128),
                        null
                ),
                new SandboxTaskEvent(
                        "judge-runner",
                        SandboxTaskEvent.Type.SUMMARY,
                        Clock.systemUTC().instant(),
                        null,
                        "AC",
                        "All cases accepted",
                        null,
                        summary
                ),
                SandboxTaskEvent.of("judge-runner", SandboxTaskEvent.Type.COMPLETED, "completed")
        )) {
            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanedHandles.add(handle);
                if (cleanupFails) {
                    throw new IllegalStateException("simulated cleanup failure");
                }
            }
        };
        JudgeService service = service(Optional.of(runner));

        JudgeRequest request = request(2);
        request.setPrecision(1e-9);
        service.createJudgeTask(request, "judge-runner");
        service.startJudgeTask("judge-runner");

        JudgeProgress progress = awaitProgress(service, "judge-runner", "AC");
        schedulerExecutor.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
        SandboxTaskSpec spec = runner.startedSpecs().get(0);

        assertThat(runner.startedSpecs()).hasSize(1);
        assertThat(spec.judgeId()).isEqualTo("judge-runner");
        assertThat(spec.userId()).isEqualTo("anonymous");
        assertThat(spec.profile()).isEqualTo("linux-prod");
        assertThat(spec.testCases()).isEqualTo(2);
        assertThat(spec.comparison().mode()).isEqualTo(com.example.demo.dto.SandboxComparison.Mode.DECIMAL_ABSOLUTE);
        assertThat(spec.comparison().precision()).isEqualTo("1.0E-9");
        assertThat(spec.caseTimeLimit()).isEqualTo(Duration.ofSeconds(2));
        assertThat(spec.memoryLimitBytes()).isEqualTo(268_435_456L);
        assertThat(spec.maxOutputBytesPerCase()).isEqualTo(1_048_576L);
        assertThat(Path.of(spec.workDir())).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(spec.sourcePaths())
                .containsKeys(SandboxTaskSpec.SourceRole.GENERATOR, SandboxTaskSpec.SourceRole.USER, SandboxTaskSpec.SourceRole.ORACLE);
        assertThat(Files.readString(Path.of(spec.sourcePaths().get(SandboxTaskSpec.SourceRole.USER)))).contains("int main()");
        assertThat(progress.getStatus()).isEqualTo("AC");
        assertThat(progress.getSummary().getTotalCases()).isEqualTo(2);
        assertThat(progress.getSummary().getAc()).isEqualTo(2);
        assertThat(cleanedHandles).hasSize(1);
        assertThat(store.find("judge-runner").orElseThrow().getSandboxRunHandle()).isEqualTo(cleanedHandles.get(0));
        assertThat(store.find("judge-runner").orElseThrow().getStatus()).isEqualTo(JudgeStatus.AC);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(cleanupFails ? 1 : 0);
    }

    @Test
    void sandboxRequiredTaskWithoutRunnerEndsAsSandboxUnavailableWithoutLegacyCompile() throws Exception {
        JudgeService service = service(Optional.empty());

        service.createJudgeTask(request(1), "missing-runner");
        service.startJudgeTask("missing-runner");

        JudgeProgress progress = awaitProgress(service, "missing-runner", "SANDBOX_UNAVAILABLE");

        assertThat(progress.getStatus()).isEqualTo("SANDBOX_UNAVAILABLE");
        assertThat(progress.getMessage()).contains("SandboxRunner is not configured");
    }

    @Test
    void fabricatedSummaryFailsClosedInsteadOfClaimingAcceptedCases() throws Exception {
        String judgeId = "fabricated-summary";
        JudgeSummary fabricated = new JudgeSummary(2, 2, 2, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null);
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED, Clock.systemUTC().instant(),
                        1, "AC", "case 1 accepted", new TestCaseResult(1, "AC", 7, 128), null),
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.SUMMARY, Clock.systemUTC().instant(),
                        null, "AC", "fabricated", null, fabricated),
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "completed")
        ));
        JudgeService service = service(Optional.of(runner));
        service.createJudgeTask(request(2), judgeId);
        service.startJudgeTask(judgeId);

        JudgeProgress progress = awaitProgress(service, judgeId, "SYSTEM_ERROR");

        assertThat(progress.getMessage()).contains("missing case results");
        assertThat(store.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.SYSTEM_ERROR);
        assertThat(progress.getSummary().getAc()).isNotEqualTo(2);
    }

    @Test
    void runnerFailureEndsAsSystemErrorWithoutLegacyCompile() throws Exception {
        SandboxRunner failingRunner = new LocalFakeSandboxRunner(List.of()) {
            @Override
            public com.example.demo.dto.SandboxRunHandle start(SandboxTaskSpec spec) {
                throw new IllegalStateException("runner exploded");
            }
        };
        JudgeService service = service(Optional.of(failingRunner));

        service.createJudgeTask(request(1), "runner-fails");
        service.startJudgeTask("runner-fails");

        JudgeProgress progress = awaitProgress(service, "runner-fails", "SYSTEM_ERROR");

        assertThat(progress.getStatus()).isEqualTo("SYSTEM_ERROR");
        assertThat(progress.getMessage()).contains("runner exploded");
    }

    @ParameterizedTest
    @CsvSource({"false,http", "true,http", "false,tls", "true,tls", "false,json", "true,json"})
    void pollFailureUsesHostErrorPathAndCleansTheStartedHandle(boolean cleanupFails, String failure) throws Exception {
        var cleanedHandles = new java.util.concurrent.CopyOnWriteArrayList<com.example.demo.dto.SandboxRunHandle>();
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        String message = failure.equals("http") ? "worker event poll failed: status 503" : "worker event poll failed";
        SandboxRunner failingRunner = new LocalFakeSandboxRunner(List.of()) {
            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle) {
                polls.incrementAndGet();
                if (failure.equals("tls")) {
                    throw new IllegalStateException(message, new javax.net.ssl.SSLHandshakeException("untrusted worker"));
                }
                if (failure.equals("json")) {
                    throw new IllegalStateException(message, new com.fasterxml.jackson.core.JsonParseException(
                            (com.fasterxml.jackson.core.JsonParser) null, "invalid event payload"));
                }
                throw new IllegalStateException(message);
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanedHandles.add(handle);
                if (cleanupFails) {
                    throw new IllegalStateException("trusted recovery remains pending");
                }
            }
        };
        JudgeService service = service(Optional.of(failingRunner));
        ExecutionProperties properties = (ExecutionProperties) ReflectionTestUtils.getField(service, "executionProperties");
        properties.setMaxRunningTasksPerUser(1);
        UserSession owner = UserSession.builder().sessionId("poll-session-a").userId("poll-owner").build();
        UserSession sameOwner = UserSession.builder().sessionId("poll-session-b").userId("poll-owner").build();
        UserSession otherOwner = UserSession.builder().sessionId("poll-session-other").userId("poll-other").build();
        service.createJudgeTask(request(1), "poll-fails", owner);
        service.createJudgeTask(request(1), "poll-next", owner);
        service.startJudgeTask("poll-fails");

        JudgeProgress progress = awaitProgress(service, "poll-fails", "SYSTEM_ERROR");
        schedulerExecutor.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(progress.getMessage()).contains(message);
        assertThat(progress.getSummary().getAc()).isZero();
        assertThat(polls).hasValue(1);
        assertThat(cleanedHandles).singleElement().satisfies(handle -> {
            assertThat(handle.judgeId()).isEqualTo("poll-fails");
            assertThat(handle.attemptId()).isNotBlank();
            assertThat(handle.runId()).isNotBlank();
        });
        assertThat(store.find("poll-fails").orElseThrow().getSandboxRunHandle()).isEqualTo(cleanedHandles.get(0));
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(cleanupFails ? 1 : 0);
        service.createJudgeTask(request(1), "poll-other-created", otherOwner);
        if (cleanupFails) {
            service.startJudgeTask("poll-next");
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
            assertThat(store.find("poll-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
            assertThat(cleanedHandles).hasSize(1);
            assertThatThrownBy(() -> service.createJudgeTask(request(1), "poll-owner-created", sameOwner))
                    .isInstanceOf(QuotaService.QuotaExceededException.class)
                    .hasMessageContaining("running task quota");
            assertThat(store.taskDirectory("poll-owner-created")).doesNotExist();
        } else {
            service.createJudgeTask(request(1), "poll-owner-created", sameOwner);
            assertThat(store.find("poll-owner-created").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
        }
    }

    @Test
    void repeatedStartOfRunningTaskDoesNotStartAnotherSandboxRun() throws Exception {
        var runStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseRun = new java.util.concurrent.CountDownLatch(1);
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                SandboxTaskEvent.of("repeat-start", SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE, "test unavailable"))) {
            @Override
            public com.example.demo.dto.SandboxRunHandle start(SandboxTaskSpec spec) {
                var handle = super.start(spec);
                runStarted.countDown();
                try {
                    if (!releaseRun.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test run was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test run interrupted", e);
                }
                return handle;
            }
        };
        JudgeService service = service(Optional.of(runner));
        ExecutionProperties properties = (ExecutionProperties) ReflectionTestUtils.getField(service, "executionProperties");
        properties.setMaxRunningTasksPerUser(2);
        UserSession owner = UserSession.builder().sessionId("repeat-session-a").userId("repeat-owner").build();
        UserSession sameOwner = UserSession.builder().sessionId("repeat-session-b").userId("repeat-owner").build();
        service.createJudgeTask(request(1), "repeat-start", owner);
        try {
            service.startJudgeTask("repeat-start");
            assertThat(runStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            service.createJudgeTask(request(1), "repeat-owner-next", sameOwner);
            assertThat(store.find("repeat-owner-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
            service.startJudgeTask("repeat-start");
            assertThat(runner.startedSpecs()).hasSize(1);
            releaseRun.countDown();
            awaitProgress(service, "repeat-start", "SANDBOX_UNAVAILABLE");
            assertThatThrownBy(() -> service.startJudgeTask("repeat-start"))
                    .isInstanceOf(JudgeScheduler.TaskAlreadyFinishedException.class);
            assertThat(runner.startedSpecs()).hasSize(1);
        } finally {
            releaseRun.countDown();
        }
    }

    @Test
    void deletedTaskCannotBeRecreatedByDirectJudgeExecution() throws Exception {
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of());
        JudgeService service = service(Optional.of(runner));
        JudgeRequest request = request(1);
        String judgeId = "deleted-before-execution";
        service.createJudgeTask(request, judgeId);
        Path workDir = store.taskDirectory(judgeId);
        assertThat(store.deleteTaskDirectory(judgeId)).isTrue();

        assertThatThrownBy(() -> service.judge(request, judgeId, policy(request), taskContext(judgeId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to persist judge progress")
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(workDir).doesNotExist();
        assertThat(workDir.resolve("control")).doesNotExist();
        assertThat(runner.startedSpecs()).isEmpty();
    }

    @Test
    void neverCreatedTaskCannotEnterDirectJudgeExecution() throws Exception {
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of());
        JudgeService service = service(Optional.of(runner));
        JudgeRequest request = request(1);
        String judgeId = "never-created";

        assertThatThrownBy(() -> service.judge(request, judgeId, policy(request), taskContext(judgeId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to persist judge progress")
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(store.taskDirectory(judgeId)).doesNotExist();
        assertThat(runner.startedSpecs()).isEmpty();
    }

    @Test
    void sandboxSourceWritesRejectHeldExternalLifecycleLockBeforeChangingFiles() throws Exception {
        JudgeService service = service(Optional.of(new LocalFakeSandboxRunner(List.of())));
        JudgeRequest request = request(1);
        String judgeId = "source-admission";
        service.createJudgeTask(request, judgeId);
        Path workDir = store.taskDirectory(judgeId);
        Path generatorSource = workDir.resolve("generator.cpp");
        Path userSource = workDir.resolve("user.cpp");
        Files.writeString(generatorSource, "original generator");
        Files.writeString(userSource, "original user");
        Method buildSpec = JudgeService.class.getDeclaredMethod("buildSandboxTaskSpec",
                JudgeRequest.class, String.class, ResolvedTaskPolicy.class, Path.class);
        buildSpec.setAccessible(true);

        try (FileChannel channel = FileChannel.open(tempDir.resolve(".task-lifecycle.lock"),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock held = channel.lock()) {
            assertThat(held.isValid()).isTrue();
            assertThatThrownBy(() -> buildSpec.invoke(service, request, judgeId, policy(request), workDir))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(Files.readString(generatorSource)).isEqualTo("original generator");
            assertThat(Files.readString(userSource)).isEqualTo("original user");
            assertThat(workDir.resolve("bruteforce.cpp")).doesNotExist();
        }

        SandboxTaskSpec spec = (SandboxTaskSpec) buildSpec.invoke(service, request, judgeId, policy(request), workDir);
        assertThat(spec.workDir()).isEqualTo(workDir.toString());
        assertThat(Files.readString(generatorSource)).isEqualTo(request.getGeneratorCode());
        assertThat(Files.readString(userSource)).isEqualTo(request.getUserCode());
    }

    @Test
    void sandboxSourceWritesRejectAnotherTasksDirectoryBeforeChangingFiles() throws Exception {
        JudgeService service = service(Optional.of(new LocalFakeSandboxRunner(List.of())));
        JudgeRequest request = request(1);
        service.createJudgeTask(request, "source-owner");
        service.createJudgeTask(request, "other-owner");
        Path otherDirectory = store.taskDirectory("other-owner");
        Path otherSource = otherDirectory.resolve("generator.cpp");
        Files.writeString(otherSource, "other task source");
        Method buildSpec = JudgeService.class.getDeclaredMethod("buildSandboxTaskSpec",
                JudgeRequest.class, String.class, ResolvedTaskPolicy.class, Path.class);
        buildSpec.setAccessible(true);

        assertThatThrownBy(() -> buildSpec.invoke(service, request, "source-owner", policy(request), otherDirectory))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IOException.class);

        assertThat(Files.readString(otherSource)).isEqualTo("other task source");
        assertThat(otherDirectory.resolve("user.cpp")).doesNotExist();
        assertThat(otherDirectory.resolve("bruteforce.cpp")).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,none", "EXITED,none", "UNKNOWN,none", "UNKNOWN,timeout", "UNKNOWN,connect"})
    void emptyBatchesWaitAndDrainTheDelayedTerminalWithoutLosingResults(String executionState, String failure) throws Exception {
        String judgeId = "delayed-" + executionState;
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger observations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        List<Long> pollTimes = new java.util.ArrayList<>();
        List<Duration> eventBudgets = new java.util.ArrayList<>();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.RUN_STARTED, "running"),
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED, Clock.systemUTC().instant(),
                        1, "AC", "accepted", new TestCaseResult(1, "AC", 1, 64), null),
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "completed"))) {
            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle) {
                pollTimes.add(System.nanoTime());
                int poll = polls.incrementAndGet();
                if (poll == 1 && failure.equals("timeout")) {
                    throw new IllegalStateException("worker event poll failed", new java.net.http.HttpTimeoutException("timed out"));
                }
                if (poll == 1 && failure.equals("connect")) {
                    throw new IllegalStateException("worker event poll failed", new java.net.ConnectException("worker unavailable"));
                }
                return poll <= 2 ? List.of() : super.pollEvents(handle);
            }

            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                eventBudgets.add(timeout);
                return pollEvents(handle);
            }

            @Override
            public ExecutionState executionState(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                assertThat(timeout).isPositive();
                observations.incrementAndGet();
                return ExecutionState.valueOf(executionState);
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanups.incrementAndGet();
            }
        };
        JudgeService service = service(Optional.of(runner));
        service.createJudgeTask(request(1), judgeId);
        service.startJudgeTask(judgeId);
        JudgeProgress progress = awaitProgress(service, judgeId, "AC");
        schedulerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertThat(progress.getSummary().getCompletedCases()).isEqualTo(1);
        assertThat(progress.getSummary().getAc()).isEqualTo(1);
        assertThat(polls).hasValue(3);
        assertThat(observations).hasValue(executionState.equals("EXITED") ? 1 : 2);
        assertThat(cleanups).hasValue(1);
        assertThat(pollTimes.get(1) - pollTimes.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(90).toNanos());
        assertThat(eventBudgets).hasSize(3).allMatch(budget -> !budget.isNegative() && !budget.isZero());
        assertThat(eventBudgets.get(2)).isLessThanOrEqualTo(Duration.ofSeconds(executionState.equals("EXITED") ? 1 : 5));
        assertThat(store.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.AC);
        assertThat(store.taskDirectory(judgeId).resolve("final.json")).exists();
    }

    @Test
    void exitedWithoutTerminalUsesOneFlushGraceThenFailsWithoutBusyPolling() throws Exception {
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger observations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong exitedAt = new java.util.concurrent.atomic.AtomicLong();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of()) {
            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle) {
                polls.incrementAndGet();
                return List.of();
            }

            @Override
            public ExecutionState executionState(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                observations.incrementAndGet();
                exitedAt.set(System.nanoTime());
                return ExecutionState.EXITED;
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanups.incrementAndGet();
            }
        };
        JudgeService service = service(Optional.of(runner));
        service.createJudgeTask(request(1), "exit-without-terminal");
        service.startJudgeTask("exit-without-terminal");
        JudgeProgress progress = awaitProgress(service, "exit-without-terminal", "SYSTEM_ERROR");
        schedulerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertThat(progress.getMessage()).contains("without a terminal event");
        assertThat(progress.getSummary().getCompletedCases()).isZero();
        assertThat(progress.getSummary().getAc()).isZero();
        assertThat(System.nanoTime() - exitedAt.get()).isBetween(Duration.ofMillis(900).toNanos(), Duration.ofSeconds(2).toNanos());
        assertThat(polls.get()).isBetween(2, 12);
        assertThat(observations).hasValue(1);
        assertThat(cleanups).hasValue(1);
    }

    @Test
    void nullExecutionStateFailsClosedWithoutRetryOrFakeSuccess() throws Exception {
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of()) {
            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle) {
                polls.incrementAndGet();
                return List.of();
            }

            @Override
            public ExecutionState executionState(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                return null;
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanups.incrementAndGet();
            }
        };
        JudgeService service = service(Optional.of(runner));
        service.createJudgeTask(request(1), "unknown-state");
        service.startJudgeTask("unknown-state");
        JudgeProgress progress = awaitProgress(service, "unknown-state", "SYSTEM_ERROR");
        schedulerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertThat(progress.getMessage()).contains("execution state is not confirmed");
        assertThat(progress.getSummary().getAc()).isZero();
        assertThat(polls).hasValue(1);
        assertThat(cleanups).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,cancel,none", "ACTIVE,budget,none", "UNKNOWN,cancel,none", "UNKNOWN,budget,none",
            "UNKNOWN,cancel,timeout", "UNKNOWN,budget,timeout", "UNKNOWN,cancel,connect", "UNKNOWN,budget,connect"})
    void emptyPollingStillHonorsCancellationAndTheExistingTaskBudget(String state, String stop, String failure) throws Exception {
        java.util.concurrent.CountDownLatch stateObserved = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger cancels = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of()) {
            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle) {
                polls.incrementAndGet();
                if (failure.equals("timeout")) {
                    throw new IllegalStateException("worker event poll failed", new java.net.http.HttpTimeoutException("timed out"));
                }
                if (failure.equals("connect")) {
                    throw new IllegalStateException("worker event poll failed", new java.net.ConnectException("worker unavailable"));
                }
                return List.of();
            }

            @Override
            public ExecutionState executionState(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                stateObserved.countDown();
                return ExecutionState.valueOf(state);
            }

            @Override
            public void cancel(com.example.demo.dto.SandboxRunHandle handle) {
                cancels.incrementAndGet();
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanups.incrementAndGet();
                if (state.equals("UNKNOWN")) {
                    throw new IllegalStateException("Execution domain stop remains unconfirmed");
                }
            }
        };
        JudgeService service = service(Optional.of(runner));
        String judgeId = "empty-" + state + "-" + stop;
        service.createJudgeTask(request(1), judgeId);
        service.startJudgeTask(judgeId);
        assertThat(stateObserved.await(1, TimeUnit.SECONDS)).isTrue();
        if (stop.equals("cancel")) {
            service.cancelJudgeTask(judgeId);
        }
        schedulerExecutor.submit(() -> {}).get(7, TimeUnit.SECONDS);
        JudgeProgress progress = service.getJudgeStatus(judgeId);
        assertThat(progress.getStatus()).isEqualTo(stop.equals("cancel") ? "CANCELLED" : "BUDGET_EXCEEDED");
        assertThat(progress.getSummary().getCompletedCases()).isZero();
        assertThat(progress.getSummary().getAc()).isZero();
        assertThat(cancels).hasValue(1);
        assertThat(cleanups).hasValue(1);
        assertThat(polls.get()).isBetween(1, 55);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(state.equals("UNKNOWN") ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAndResidualCleanupShareTheGraceBudgetAndRetainUnconfirmedPermits(boolean expired) throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Duration> cancellationBudget = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Duration> cleanupBudget = new java.util.concurrent.atomic.AtomicReference<>();
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of()) {
            @Override
            public ExecutionState executionState(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                active.countDown();
                return ExecutionState.ACTIVE;
            }

            @Override
            public void cancel(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                cancellationBudget.set(timeout);
                try {
                    Thread.sleep(expired ? 2100 : 200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Cancellation interrupted", interrupted);
                }
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                cleanupBudget.set(timeout);
                super.cleanupResidual(handle);
            }
        };
        JudgeService service = service(Optional.of(runner));
        String judgeId = "cleanup-grace-" + expired;
        service.createJudgeTask(request(1), judgeId);
        service.startJudgeTask(judgeId);
        assertThat(active.await(1, TimeUnit.SECONDS)).isTrue();
        service.cancelJudgeTask(judgeId);
        schedulerExecutor.submit(() -> {}).get(4, TimeUnit.SECONDS);
        assertThat(cancellationBudget.get()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(service.getJudgeStatus(judgeId).getStatus()).isEqualTo("CANCELLED");
        if (expired) {
            assertThat(cleanupBudget.get()).isNull();
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        } else {
            assertThat(cleanupBudget.get()).isPositive().isLessThan(cancellationBudget.get().minusMillis(150));
            assertThat(scheduler.snapshot().runningCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "budget"})
    void eventsReturnedAfterStoppingCannotReplaceTheStopStatusOrAcceptedStatistics(String stop) throws Exception {
        String judgeId = "late-events-" + stop;
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Duration> pollingBudget = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger cancels = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        JudgeSummary completed = new JudgeSummary(2, 2, 2, 0, 0, 0, 0, 0, 0, null, List.of(), List.of(), null);
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of(
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.RUN_STARTED, "running"),
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED, Clock.systemUTC().instant(),
                        1, "AC", "accepted before stopping", new TestCaseResult(1, "AC", 1, 64), null),
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED, Clock.systemUTC().instant(),
                        2, "AC", "late result", new TestCaseResult(2, "AC", 1, 64), null),
                new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.SUMMARY, Clock.systemUTC().instant(),
                        null, "AC", "late summary", null, completed),
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "late completion"))) {
            private List<SandboxTaskEvent> pending;

            @Override
            public List<SandboxTaskEvent> pollEvents(com.example.demo.dto.SandboxRunHandle handle, Duration timeout) {
                if (pending == null) {
                    pending = super.pollEvents(handle);
                    return pending.subList(0, 2);
                }
                pollingBudget.set(timeout);
                blocked.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Late event fixture was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Late event fixture interrupted", interrupted);
                }
                return pending.subList(2, pending.size());
            }

            @Override
            public void cancel(com.example.demo.dto.SandboxRunHandle handle) {
                cancels.incrementAndGet();
                super.cancel(handle);
            }

            @Override
            public void cleanupResidual(com.example.demo.dto.SandboxRunHandle handle) {
                cleanups.incrementAndGet();
            }
        };
        JudgeService service = service(Optional.of(runner));
        if (stop.equals("budget")) {
            ((ExecutionProperties) ReflectionTestUtils.getField(service, "executionProperties"))
                    .setMaxTaskRuntime(Duration.ofMillis(500));
        }
        service.createJudgeTask(request(2), judgeId);
        service.startJudgeTask(judgeId);
        try {
            assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.getJudgeStatus(judgeId).getSummary().getCompletedCases()).isEqualTo(1);
            if (stop.equals("cancel")) {
                service.cancelJudgeTask(judgeId);
            } else {
                TimeUnit.NANOSECONDS.sleep(pollingBudget.get().plusMillis(50).toNanos());
            }
        } finally {
            release.countDown();
        }
        schedulerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        JudgeProgress progress = service.getJudgeStatus(judgeId);
        assertThat(progress.getStatus()).isEqualTo(stop.equals("cancel") ? "CANCELLED" : "BUDGET_EXCEEDED");
        assertThat(progress.getSummary().getTotalCases()).isEqualTo(2);
        assertThat(progress.getSummary().getCompletedCases()).isEqualTo(1);
        assertThat(progress.getSummary().getAc()).isEqualTo(1);
        JudgeProgress persisted = store.findSummary(judgeId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(progress.getStatus());
        assertThat(persisted.getSummary()).isEqualTo(progress.getSummary());
        assertThat(cancels).hasValue(1);
        assertThat(cleanups).hasValue(1);
        assertThat(scheduler.snapshot().runningCount()).isZero();
    }

    private ResolvedTaskPolicy policy(JudgeRequest request) {
        return new TaskPolicyResolver(executionProperties(), new MemoryConfiguration()).resolve(request);
    }

    private JudgeScheduler.TaskContext taskContext(String judgeId) throws Exception {
        Constructor<JudgeScheduler.TaskContext> constructor = JudgeScheduler.TaskContext.class
                .getDeclaredConstructor(String.class, int.class, Duration.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(judgeId, 1, Duration.ofSeconds(5), "anonymous");
    }

    private JudgeService service(Optional<SandboxRunner> runner) {
        ExecutionProperties execution = executionProperties();
        MemoryConfiguration memory = new MemoryConfiguration();
        store = new FileTaskStore(objectMapper, tempDir);
        TaskPolicyResolver policyResolver = new TaskPolicyResolver(execution, memory);
        schedulerExecutor = Executors.newSingleThreadExecutor();
        scheduler = new JudgeScheduler(execution, store, schedulerExecutor);
        caseExecutor = new ThreadPoolTaskExecutor();
        caseExecutor.setCorePoolSize(1);
        caseExecutor.setMaxPoolSize(1);
        caseExecutor.setQueueCapacity(1);
        caseExecutor.initialize();
        ProgressPublisher publisher = new ProgressPublisher(
                mock(SimpMessagingTemplate.class),
                store,
                objectMapper,
                Clock.systemUTC(),
                Duration.ZERO
        );
        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(false);
        SandboxProcessRunner legacyRunner = new SandboxProcessRunner(sandbox, null, request -> {
            throw new AssertionError("legacy compile/run path must not be called for production sandbox orchestration");
        });

        return new JudgeService(
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
                runner
        );
    }

    private ExecutionProperties executionProperties() {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setProfile("linux-prod");
        properties.setMaxCasesPerTask(100);
        properties.setLargeModeThreshold(50);
        properties.setTaskQueueCapacity(5);
        properties.setMaxConcurrentTasks(1);
        properties.setMaxConcurrentCasesPerTask(1);
        properties.setBatchSize(10);
        properties.setMaxTaskRuntime(Duration.ofSeconds(5));
        properties.setDefaultTimeLimit(Duration.ofSeconds(2));
        properties.setMinTimeLimit(Duration.ofMillis(100));
        properties.setMaxTimeLimit(Duration.ofSeconds(30));
        properties.setMaxOutputBytesPerCase(1_048_576L);
        properties.setRequireSandbox(true);
        return properties;
    }

    private SecurityModeStartupValidator securityValidator(ExecutionProperties execution, SandboxConfiguration sandbox) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("changed-secret");
        auth.setDefaultAccessCode("123");
        return new SecurityModeStartupValidator(auth, execution, sandbox, new WebSocketConfig("https://safe.example.com"));
    }

    private JudgeRequest request(int testCases) {
        JudgeRequest request = new JudgeRequest();
        request.setGeneratorCode("int main(){return 0;}");
        request.setUserCode("int main(){return 0;}");
        request.setBruteForceCode("int main(){return 0;}");
        request.setTestCases(testCases);
        request.setTimeLimit(2_000);
        request.setMemoryLimit(268_435_456L);
        return request;
    }

    private JudgeProgress awaitProgress(JudgeService service, String judgeId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                JudgeProgress progress = service.getJudgeStatus(judgeId);
                if (expectedStatus.equals(progress.getStatus())) {
                    return progress;
                }
                lastFailure = new AssertionError("Expected " + expectedStatus + " but got " + progress.getStatus());
            } catch (AssertionError error) {
                lastFailure = error;
            }
            Thread.sleep(20);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Timed out waiting for " + expectedStatus);
    }
}
