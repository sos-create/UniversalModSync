package com.soscreate.universalmodsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModCompatibilityServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsFabricTopLevelMetadataAndDependenciesWithoutNestedFalseMatches() throws Exception {
        createArchive("fabric-example.jar", "fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "example_fabric",
                  "version": "2.3.1",
                  "environment": "*",
                  "custom": {
                    "id": "must_not_replace_mod_id",
                    "version": "9.9.9"
                  },
                  "depends": {
                    "minecraft": ">=1.21",
                    "fabricloader": ">=0.16.0"
                  }
                }
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(1, mods.size());
        assertEquals("fabric-example.jar", mods.get(0).fileName());
        assertEquals("Fabric", mods.get(0).loader());
        assertEquals("example_fabric", mods.get(0).modId());
        assertEquals("2.3.1", mods.get(0).version());
        assertTrue(mods.get(0).dependencies().contains("minecraft"));
        assertTrue(mods.get(0).dependencies().contains("fabricloader"));
        assertEquals("ADAPTER_REQUIRED", mods.get(0).compatibility());
    }

    @Test
    void decodesEscapedJsonAndDetectsClientOnlyEnvironment() throws Exception {
        createArchive("client-addon.jar", "fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "client\\u005faddon",
                  "version": "1.0.0",
                  "environment": "client",
                  "name": "contains } and escaped quote: \\"ok\\""
                }
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals("client_addon", mods.get(0).modId());
        assertEquals("CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER", mods.get(0).compatibility());
    }

    @Test
    void detectsForgeModBlocksAndDependenciesIndependently() throws Exception {
        createArchive("forge-example.jar", "META-INF/mods.toml", """
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
                [[mods]]
                modId="second_forge"
                version="2.0.0"
                [[dependencies.second_forge]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                side="BOTH"
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(2, mods.size());
        assertEquals("example_forge", mods.get(0).modId());
        assertEquals("1.2.0", mods.get(0).version());
        assertEquals(java.util.List.of("minecraft"), mods.get(0).dependencies());
        assertEquals("second_forge", mods.get(1).modId());
        assertEquals(java.util.List.of("forge"), mods.get(1).dependencies());
    }

    @Test
    void detectsQuiltMetadataAndDependencyIds() throws Exception {
        createArchive("quilt-addon.zip", "quilt.mod.json", """
                {
                  "quilt_loader": {
                    "id": "quilt_addon",
                    "version": "3.4.5",
                    "metadata": { "name": "Quilt Addon" },
                    "depends": [
                      { "id": "quilt_loader", "versions": "*" },
                      { "id": "minecraft", "versions": ">=1.21" }
                    ]
                  }
                }
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(1, mods.size());
        assertEquals("quilt-addon.zip", mods.get(0).fileName());
        assertEquals("Quilt", mods.get(0).loader());
        assertEquals("quilt_addon", mods.get(0).modId());
        assertEquals("3.4.5", mods.get(0).version());
        assertEquals(java.util.List.of("quilt_loader", "minecraft"), mods.get(0).dependencies());
    }

    @Test
    void reportsCorruptArchivesWithoutStoppingTheScan() throws Exception {
        Files.writeString(tempDir.resolve("broken.jar"), "not a zip archive");
        createArchive("valid.zip", "fabric.mod.json", """
                {"schemaVersion":1,"id":"valid_mod","version":"1.0"}
                """);

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(2, mods.size());
        assertTrue(mods.stream().anyMatch(mod ->
                mod.compatibility().equals("INVALID_OR_UNREADABLE_ARCHIVE")));
        assertTrue(mods.stream().anyMatch(mod -> mod.modId().equals("valid_mod")));
    }

    @Test
    void marksMalformedJsonAsUnreadableMetadata() throws Exception {
        createArchive("bad-json.jar", "fabric.mod.json", "{ \"id\": \"broken\", }");

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(1, mods.size());
        assertEquals("INVALID_OR_UNREADABLE_ARCHIVE", mods.get(0).compatibility());
    }

    @Test
    void ignoresNonArchiveFilesAndDirectories() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "not a mod");
        Files.createDirectory(tempDir.resolve("nested"));
        createArchive("valid.jar", "fabric.mod.json",
                "{\"id\":\"valid_mod\",\"version\":\"1.0\"}");

        var mods = new ModCompatibilityService(tempDir).scan();

        assertEquals(1, mods.size());
        assertEquals("valid_mod", mods.get(0).modId());
    }

    private void createArchive(String fileName, String entryName, String content) throws IOException {
        Path archive = tempDir.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
