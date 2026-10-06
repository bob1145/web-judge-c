package com.example.demo.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class JavaSupervisorBundleClasspathTest {
    @Test
    void inventoryDetectsFilesBelowSixDirectoryLevels(@TempDir Path storage) throws Exception {
        Path runtimeClient = Files.createDirectories(storage.resolve("judge-task/control/attempts/attempt/stage-journal/runtime-client"));
        var before = JavaSupervisorProbe.boundedStorageInventory(storage);
        Files.writeString(runtimeClient.resolve("unexpected"), "changed");
        var after = JavaSupervisorProbe.boundedStorageInventory(storage);
        assertNotEquals(before, after);
    }

    @Test
    void captureResolvedClasspathWithoutPublishingSystemProperties() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertFalse(classpath.isBlank());
        Files.writeString(Path.of("target/java-supervisor-classpath.txt"), classpath);
    }
}
