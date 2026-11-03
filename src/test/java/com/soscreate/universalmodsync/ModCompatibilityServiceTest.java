package com.soscreate.universalmodsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModCompatibilityServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsFabricMetadataAndDependencies() throws Exception {
        createJar("fabric-example.jar", "fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "example_fabric",
                  "version": "2.3.1",
                  "environment": "*",
                  "depends": {
                    "minecraft": ">=1.21",
                    "fabricloader": ">=0.16.0"
                  }
                }
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(1, mods.size());
        assertEquals("Fabric", mods.get(0).loader());
        assertEquals("example_fabric", mods.get(0).modId());
        assertEquals("2.3.1", mods.get(0).version());
        assertTrue(mods.get(0).dependencies().contains("minecraft"));
        assertTrue(mods.get(0).dependencies().contains("fabricloader"));
        assertEquals("ADAPTER_REQUIRED", mods.get(0).compatibility());
    }

    @Test
    void marksClientOnlyFabricModsAsNotRunnableOnPaper() throws Exception {
        createJar("client-addon.jar", "fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "client_addon",
                  "version": "1.0.0",
                  "environment": "client"
                }
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals("CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER", mods.get(0).compatibility());
    }

    @Test
    void detectsForgeMetadataAndDependencies() throws Exception {
        createJar("forge-example.jar", "META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[47,)"
                [[mods]]
                modId="example_forge"
                version="1.2.0"
                [[dependencies.example_forge]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,)"
                side="BOTH"
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(1, mods.size());
        assertEquals("Forge", mods.get(0).loader());
        assertEquals("example_forge", mods.get(0).modId());
        assertEquals("1.2.0", mods.get(0).version());
        assertTrue(mods.get(0).dependencies().contains("minecraft"));
    }

    @Test
    void reportsCorruptJarWithoutStoppingTheScan() throws Exception {
        Files.writeString(tempDir.resolve("broken.jar"), "not a zip archive");
        createJar("valid.jar", "fabric.mod.json", """
                {"schemaVersion":1,"id":"valid_mod","version":"1.0"}
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(2, mods.size());
        assertTrue(mods.stream().anyMatch(mod -> mod.compatibility().equals("INVALID_OR_UNREADABLE_JAR")));
        assertTrue(mods.stream().anyMatch(mod -> mod.modId().equals("valid_mod")));
    }

    private void createJar(String fileName, String entryName, String content) throws IOException {
        Path jar = tempDir.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
