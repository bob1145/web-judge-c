package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;

import java.util.List;

public interface SandboxRunner {

    SandboxCapabilities probe();

    SandboxRunHandle start(SandboxTaskSpec spec);

    List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle);

    void cancel(SandboxRunHandle handle);

    default void cleanupResidual(SandboxRunHandle handle) {
        cancel(handle);
    }
}
