package com.example.demo.support;

import com.example.demo.dto.RunnerProtocolCapabilities;
import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxExecutionManifest;

import java.util.HashSet;
import java.util.Set;

public final class RunnerProtocolFixtures {
    private RunnerProtocolFixtures() {
    }

    public static final String PYTHON_CAPABILITIES = """
            {"versions":[2],"comparisonModes":["LEGACY_TEXT","SPECIAL_JUDGE"],
            "features":["relative-sources","attempt-files","explicit-limits","retention"]}
            """;

    public static SandboxComparison legacyText() {
        return new SandboxComparison(SandboxComparison.Mode.LEGACY_TEXT, "0");
    }

    public static RunnerProtocolCapabilities workerProtocol() {
        Set<String> features = new HashSet<>(RunnerProtocolCapabilities.REQUIRED_FEATURES);
        features.add(RunnerProtocolCapabilities.INLINE_SOURCES);
        return new RunnerProtocolCapabilities(Set.of(SandboxExecutionManifest.VERSION),
                Set.of(SandboxComparison.Mode.values()), features);
    }
}
