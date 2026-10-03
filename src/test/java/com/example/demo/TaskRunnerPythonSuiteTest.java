package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunnerPythonSuiteTest {
    @Test
    void actualPythonUnittestSuitePassesWithoutSkippedContracts() throws Exception {
        Path output = Path.of("target", "runner-python.log");
        Path report = Path.of("target", "runner-python-summary.json");
        Files.createDirectories(output.getParent());
        Process process = new ProcessBuilder(PythonInterpreter.resolve(), "-B", "runner/tests/run_contract_suite.py", report.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        assertThat(exited).as("Python suite watchdog; %s", Files.readString(output)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        var summary = new ObjectMapper().readTree(report.toFile());
        assertThat(summary.path("tests").asInt()).isGreaterThan(0);
        assertThat(summary.path("failures").asInt()).isZero();
        assertThat(summary.path("errors").asInt()).isZero();
        assertThat(summary.path("skipped").asInt()).isZero();
    }
}
