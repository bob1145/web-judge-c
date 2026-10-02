package com.example.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonInterpreterTest {

    @Test
    void explicitExecutableTakesPrecedenceAndPreservesSpaces() throws Exception {
        List<String> attempted = new ArrayList<>();
        Process process = completed(0);
        String executable = "C:/Program Files/Python/python.exe";
        assertThat(PythonInterpreter.resolve(executable, command -> {
            attempted.add(command.get(0));
            assertThat(command).containsExactly(executable, "-c",
                    "import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)");
            return process;
        })).isEqualTo(executable);
        assertThat(attempted).containsExactly(executable);
    }

    @Test
    void invalidExplicitExecutableDoesNotFallBack() {
        List<String> attempted = new ArrayList<>();
        assertThatThrownBy(() -> PythonInterpreter.resolve("missing-python", command -> {
            attempted.add(command.get(0));
            throw new IOException("not found");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("judge.test.python").hasMessageContaining("not found");
        assertThat(attempted).containsExactly("missing-python");
    }

    @Test
    void blankExplicitExecutableDoesNotFallBack() {
        assertThatThrownBy(() -> PythonInterpreter.resolve(" ", command -> {
            throw new AssertionError("must not launch a process");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("blank");
    }

    @Test
    void discoversPython3WhenOnlyPython3Exists() throws Exception {
        List<String> attempted = new ArrayList<>();
        Process process = completed(0);
        assertThat(PythonInterpreter.resolve(null, command -> {
            attempted.add(command.get(0));
            if (!command.get(0).equals("python3")) {
                throw new IOException("not found");
            }
            return process;
        })).isEqualTo("python3");
        assertThat(attempted).containsExactly("python3");
    }

    @Test
    void discoversPythonWhenOnlyPythonExists() throws Exception {
        List<String> attempted = new ArrayList<>();
        Process process = completed(0);
        assertThat(PythonInterpreter.resolve(null, command -> {
            attempted.add(command.get(0));
            if (!command.get(0).equals("python")) {
                throw new IOException("not found");
            }
            return process;
        })).isEqualTo("python");
        assertThat(attempted).containsExactly("python3", "python");
    }

    @Test
    void missingInterpretersFailInsteadOfSkipping() {
        List<String> attempted = new ArrayList<>();
        assertThatThrownBy(() -> PythonInterpreter.resolve(null, command -> {
            attempted.add(command.get(0));
            throw new IOException("not found");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Python 3 is required");
        assertThat(attempted).containsExactly("python3", "python");
    }

    @Test
    void rejectsPython2InsteadOfSkipping() throws Exception {
        Process python2 = completed(1);
        assertThatThrownBy(() -> PythonInterpreter.resolve("python2", command -> python2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exit code 1");
    }

    @Test
    void rejectedVersionAllowsDiscoveryOfNextCandidate() throws Exception {
        Process python2 = completed(1);
        Process python3 = completed(0);
        assertThat(PythonInterpreter.resolve(null, command ->
                command.get(0).equals("python3") ? python2 : python3)).isEqualTo("python");
    }

    @Test
    void timedOutProbeIsForciblyDestroyedWithBoundedCleanup() throws Exception {
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.isAlive()).thenReturn(true);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(false);
        when(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(true);
        assertThatThrownBy(() -> PythonInterpreter.resolve("hanging-python", command -> process))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
        verify(process).destroyForcibly();
        verify(process).waitFor(1, TimeUnit.SECONDS);
    }

    @Test
    void cleanupFailureIsReportedWithoutUnboundedWaiting() throws Exception {
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.isAlive()).thenReturn(true);
        assertThatThrownBy(() -> PythonInterpreter.resolve("hanging-python", command -> process))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not be terminated");
        verify(process).destroyForcibly();
        verify(process).waitFor(1, TimeUnit.SECONDS);
    }

    @Test
    void interruptionPropagatesAfterProbeCleanup() throws Exception {
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.isAlive()).thenReturn(true);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));
        when(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(true);
        assertThatThrownBy(() -> PythonInterpreter.resolve("python", command -> process))
                .isInstanceOf(InterruptedException.class);
        verify(process).destroyForcibly();
    }

    @Test
    void actualEnvironmentHasAWorkingPython3Interpreter() throws Exception {
        assertThat(PythonInterpreter.resolve()).isNotBlank();
    }

    private Process completed(int exitCode) throws InterruptedException {
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(exitCode);
        return process;
    }
}
