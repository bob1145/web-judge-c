package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;

import java.util.List;
import java.time.Duration;

public interface SandboxRunner {

    SandboxCapabilities probe();

    SandboxRunHandle start(SandboxTaskSpec spec);

    default SandboxRunHandle start(SandboxTaskSpec spec, Duration timeout) {
        return start(spec);
    }

    List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle);

    default List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle, Duration timeout) {
        return pollEvents(handle);
    }

    default ExecutionState executionState(SandboxRunHandle handle, Duration timeout) {
        return ExecutionState.UNKNOWN;
    }

    enum ExecutionState {
        ACTIVE, EXITED, UNKNOWN
    }

    void cancel(SandboxRunHandle handle);

    default void cancel(SandboxRunHandle handle, Duration timeout) {
        cancel(handle);
    }

    default void cleanupResidual(SandboxRunHandle handle) {
        cancel(handle);
        throw new IllegalStateException("Sandbox execution domain stop is not confirmed");
    }

    default void cleanupResidual(SandboxRunHandle handle, Duration timeout) {
        cleanupResidual(handle);
    }
}
