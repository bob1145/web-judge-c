package com.example.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class PythonInterpreter {

    private static final String PROPERTY = "judge.test.python";

    private PythonInterpreter() {
    }

    static String resolve() throws InterruptedException {
        return resolve(System.getProperty(PROPERTY), command -> new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start());
    }

    static String resolve(String configured, ProcessStarter starter) throws InterruptedException {
        List<String> candidates = configured == null ? List.of("python3", "python") : List.of(configured);
        List<String> failures = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.isBlank()) {
                throw new IllegalStateException(PROPERTY + " must name a Python 3 executable, not a blank value");
            }
            try {
                probe(candidate, starter);
                return candidate;
            } catch (IOException | IllegalStateException failure) {
                failures.add(candidate + ": " + failure.getMessage());
            }
        }
        throw new IllegalStateException("Python 3 is required for runner contract tests. Set -D" + PROPERTY
                + "=<executable>; explicit configuration never falls back. " + String.join("; ", failures));
    }

    private static void probe(String executable, ProcessStarter starter) throws IOException, InterruptedException {
        Process process = starter.start(List.of(executable, "-c",
                "import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)"));
        try {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Python version probe timed out after 5 seconds");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Python 3 version probe failed with exit code " + process.exitValue());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Python version probe could not be terminated");
                }
            }
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }
}
