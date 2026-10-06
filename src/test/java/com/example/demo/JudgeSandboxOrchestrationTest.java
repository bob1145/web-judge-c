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
import com.example.demo.service.SandboxProcessRunner;
import com.example.demo.service.SandboxEventIngestor;
import com.example.demo.service.TaskPolicyResolver;
import com.example.demo.service.sandbox.LocalFakeSandboxRunner;
import com.example.demo.service.sandbox.SandboxRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    void productionProfileSubmitsOneSandboxTaskSpecAndPersistsRunnerSummaryWithoutLegacyCompile() throws Exception {
        JudgeSummary summary = new JudgeSummary(2, 2, 2, 0, 0, 0, 0, 0, 0, null, List.of(), List.of(), null);
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
                        SandboxTaskEvent.Type.SUMMARY,
                        Clock.systemUTC().instant(),
                        null,
                        "AC",
                        "All cases accepted",
                        null,
                        summary
                ),
                SandboxTaskEvent.of("judge-runner", SandboxTaskEvent.Type.COMPLETED, "completed")
        ));
        JudgeService service = service(Optional.of(runner));

        JudgeRequest request = request(2);
        request.setPrecision(1e-9);
        service.createJudgeTask(request, "judge-runner");
        service.startJudgeTask("judge-runner");

        JudgeProgress progress = awaitProgress(service, "judge-runner", "AC");
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

    @Test
    void deletedTaskCannotBeRecreatedByDirectJudgeExecution() throws Exception {
        LocalFakeSandboxRunner runner = new LocalFakeSandboxRunner(List.of());
        JudgeService service = service(Optional.of(runner));
        JudgeRequest request = request(1);
        String judgeId = "deleted-before-execution";
        service.createJudgeTask(request, judgeId);
        Path workDir = store.taskDirectory(judgeId);
        assertThat(store.deleteTaskDirectory(judgeId)).isTrue();

        service.judge(request, judgeId, policy(request), taskContext(judgeId));

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

        service.judge(request, judgeId, policy(request), taskContext(judgeId));

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

    private ResolvedTaskPolicy policy(JudgeRequest request) {
        return new TaskPolicyResolver(executionProperties(), new MemoryConfiguration()).resolve(request);
    }

    private JudgeScheduler.TaskContext taskContext(String judgeId) throws Exception {
        Constructor<JudgeScheduler.TaskContext> constructor = JudgeScheduler.TaskContext.class
                .getDeclaredConstructor(String.class, int.class, Duration.class);
        constructor.setAccessible(true);
        return constructor.newInstance(judgeId, 1, Duration.ofSeconds(5));
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
