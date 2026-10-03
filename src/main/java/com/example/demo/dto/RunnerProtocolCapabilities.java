package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

public record RunnerProtocolCapabilities(
        Set<Integer> versions,
        Set<SandboxComparison.Mode> comparisonModes,
        Set<String> features
) {
    public static final Set<String> REQUIRED_FEATURES = Set.of(
            "relative-sources", "attempt-files", "explicit-limits", "retention");
    public static final String INLINE_SOURCES = "inline-sources-base64";

    public RunnerProtocolCapabilities {
        versions = versions == null ? Set.of() : Set.copyOf(versions);
        comparisonModes = comparisonModes == null ? Set.of() : Set.copyOf(comparisonModes);
        features = features == null ? Set.of() : Set.copyOf(features);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RunnerProtocolCapabilities fromJson(JsonNode value) {
        if (!value.isObject()) {
            throw new IllegalArgumentException("runner protocol capabilities must be an object");
        }
        Set<Integer> versions = new LinkedHashSet<>();
        JsonNode declaredVersions = value.get("versions");
        if (declaredVersions != null) {
            if (!declaredVersions.isArray()) {
                throw new IllegalArgumentException("runner versions must be an integer array");
            }
            for (JsonNode version : declaredVersions) {
                if (!version.isIntegralNumber() || !version.canConvertToInt()) {
                    throw new IllegalArgumentException("runner version must be an integer");
                }
                versions.add(version.intValue());
            }
        }
        Set<SandboxComparison.Mode> modes = new LinkedHashSet<>();
        for (String mode : strings(value.get("comparisonModes"))) {
            modes.add(SandboxComparison.Mode.valueOf(mode));
        }
        return new RunnerProtocolCapabilities(versions, modes, strings(value.get("features")));
    }

    private static Set<String> strings(JsonNode value) {
        if (value == null) {
            return Set.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("runner capability values must be string arrays");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("runner capability values must be strings");
            }
            values.add(item.textValue());
        }
        return values;
    }

    public void requireVersion2() {
        if (!versions.contains(SandboxExecutionManifest.VERSION) || comparisonModes.isEmpty()
                || !features.containsAll(REQUIRED_FEATURES)) {
            throw new IllegalArgumentException("runner does not support the v2 execution contract");
        }
    }

    public void require(SandboxExecutionManifest manifest) {
        if (!versions.contains(manifest.protocolVersion())
                || !comparisonModes.contains(manifest.comparison().mode())
                || !features.containsAll(REQUIRED_FEATURES)) {
            throw new IllegalArgumentException("runner does not support the requested protocol, comparison or limits");
        }
    }
}
