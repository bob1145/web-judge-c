package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxTaskSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SandboxManifestFactory {
    private SandboxManifestFactory() {
    }

    public static Prepared prepare(SandboxTaskSpec spec) throws IOException {
        Path workDir = Path.of(spec.workDir());
        if (!workDir.isAbsolute()) {
            throw new IllegalArgumentException("host workDir must be absolute");
        }
        workDir = workDir.normalize();
        if (!Files.isDirectory(workDir, LinkOption.NOFOLLOW_LINKS) || !workDir.toRealPath().equals(workDir)) {
            throw new IllegalArgumentException("host workDir must be an existing non-linked task directory");
        }
        Map<SandboxTaskSpec.SourceRole, String> relativeSources = new LinkedHashMap<>();
        for (var source : spec.sourcePaths().entrySet()) {
            Path sourcePath = Path.of(source.getValue());
            if (!sourcePath.isAbsolute() || !sourcePath.normalize().startsWith(workDir)) {
                throw new IllegalArgumentException("source path must stay inside mounted workDir");
            }
            String relative = workDir.relativize(sourcePath.normalize()).toString().replace('\\', '/');
            SandboxExecutionManifest.requireRelativePath(relative);
            requireSource(workDir, relative);
            relativeSources.put(source.getKey(), relative);
        }
        SandboxTaskSpec.Retention retention = spec.retention();
        SandboxExecutionManifest manifest = new SandboxExecutionManifest(
                SandboxExecutionManifest.VERSION, spec.judgeId(), UUID.randomUUID().toString(), relativeSources,
                spec.testCases(), spec.comparison(), milliseconds(spec.caseTimeLimit()), milliseconds(spec.maxTaskRuntime()),
                spec.memoryLimitBytes(), spec.maxOutputBytesPerCase(), spec.stopOnFirstNonAc(),
                new SandboxExecutionManifest.RetentionMillis(milliseconds(retention.completed()),
                        milliseconds(retention.failed()), milliseconds(retention.cancelled())));
        Prepared prepared = new Prepared(workDir, manifest);
        prepared.validate();
        return prepared;
    }

    public static void requireExecutionRoot(String value, boolean windows) {
        boolean absolute = windows ? value.matches("[A-Za-z]:\\\\.*") : value.startsWith("/");
        if (!absolute || value.contains(",")) {
            throw new IllegalArgumentException("execution root must be an absolute provider path without mount delimiters");
        }
        String relative = windows ? value.substring(3).replace('\\', '/') : value.substring(1);
        SandboxExecutionManifest.requireRelativePath(relative);
    }

    private static long milliseconds(Duration value) {
        long millis = value.toMillis();
        if (millis <= 0 || !Duration.ofMillis(millis).equals(value)) {
            throw new IllegalArgumentException("runner durations must be positive whole milliseconds");
        }
        return millis;
    }

    private static void requireSource(Path root, String relative) throws IOException {
        requireNoLinks(root, relative);
        Path source = root.resolve(relative);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("source path must be an existing task file");
        }
    }

    private static void requireNoLinks(Path root, String relative) throws IOException {
        Path current = root;
        for (String component : relative.split("/")) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))) {
                throw new IllegalArgumentException("runner path must not traverse links");
            }
        }
    }

    private static void requireDirectoryOrAbsent(Path root, String relative) throws IOException {
        requireNoLinks(root, relative);
        Path directory = root.resolve(relative);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("runner parent must be a directory");
        }
    }

    public record Prepared(Path workDir, SandboxExecutionManifest manifest) {
        public void validate() throws IOException {
            for (String source : manifest.sourcePaths().values()) {
                requireSource(workDir, source);
            }
            requireDirectoryOrAbsent(workDir, "control");
            requireDirectoryOrAbsent(workDir, "control/attempts");
            requireDirectoryOrAbsent(workDir, "workspace");
            requireNoLinks(workDir, manifest.attemptDirectory());
            requireNoLinks(workDir, "workspace/" + manifest.attemptId());
            if (Files.exists(workDir.resolve(manifest.attemptDirectory()), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(workDir.resolve("workspace/" + manifest.attemptId()), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("runner attempt must be new");
            }
        }

        public void write(ObjectMapper objectMapper) throws IOException {
            validate();
            Files.createDirectories(workDir.resolve("control/attempts"));
            Files.createDirectory(workDir.resolve(manifest.attemptDirectory()));
            try (var output = Files.newOutputStream(workDir.resolve(manifest.specPath()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                objectMapper.writeValue(output, manifest);
            }
        }

        public Map<String, String> sourceContentsBase64() throws IOException {
            validate();
            Map<String, String> sources = new LinkedHashMap<>();
            for (String relative : manifest.sourcePaths().values()) {
                sources.put(relative, Base64.getEncoder().encodeToString(Files.readAllBytes(workDir.resolve(relative))));
            }
            return Map.copyOf(sources);
        }
    }
}
