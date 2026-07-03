package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LocalFakeSandboxRunner implements SandboxRunner {

    private final List<SandboxTaskEvent> scriptedEvents;
    private final List<SandboxTaskSpec> startedSpecs = new ArrayList<>();
    private final Map<String, Boolean> eventsPolledByRunId = new ConcurrentHashMap<>();

    public LocalFakeSandboxRunner(List<SandboxTaskEvent> scriptedEvents) {
        this.scriptedEvents = scriptedEvents == null ? List.of() : List.copyOf(scriptedEvents);
    }

    @Override
    public SandboxCapabilities probe() {
        return SandboxCapabilities.builder()
                .provider("local-fake")
                .isolation("fake")
                .productionSafe(false)
                .details("Deterministic test runner; not a production sandbox")
                .build();
    }

    @Override
    public SandboxRunHandle start(SandboxTaskSpec spec) {
        startedSpecs.add(spec);
        return SandboxRunHandle.builder()
                .judgeId(spec.judgeId())
                .runId(UUID.randomUUID().toString())
                .provider("local-fake")
                .startedAt(Instant.now())
                .eventCursor("0")
                .build();
    }

    @Override
    public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle) {
        if (eventsPolledByRunId.putIfAbsent(handle.runId(), true) != null) {
            return List.of();
        }
        return scriptedEvents;
    }

    @Override
    public void cancel(SandboxRunHandle handle) {
        eventsPolledByRunId.put(handle.runId(), true);
    }

    public List<SandboxTaskSpec> startedSpecs() {
        return List.copyOf(startedSpecs);
    }
}
