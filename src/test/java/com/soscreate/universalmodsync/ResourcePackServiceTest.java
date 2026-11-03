package com.soscreate.universalmodsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsPackWithMetadataAndAdapterAssets() throws Exception {
        ResourcePackService service = new ResourcePackService(tempDir);
        Path asset = service.getDirectory().resolve("assets/example/lang/en_us.json");
        Files.createDirectories(asset.getParent());
        Files.writeString(asset, "{\"key\":\"value\"}", StandardCharsets.UTF_8);

        ResourcePackService.PackInfo info = service.rebuild();

        assertNotNull(info);
        assertTrue(info.size() > 0);
        assertEquals(20, info.hash().length);
        assertEquals(HexFormat.of().formatHex(info.hash()), info.sha1());
        try (ZipFile zip = new ZipFile(info.path().toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertNotNull(zip.getEntry("assets/example/lang/en_us.json"));
            assertEquals("{\"key\":\"value\"}",
                    new String(zip.getInputStream(zip.getEntry("assets/example/lang/en_us.json")).readAllBytes(),
                            StandardCharsets.UTF_8));
        }
    }

    @Test
    void returnedHashCannotBeMutatedByCallers() throws Exception {
        ResourcePackService service = new ResourcePackService(tempDir);
        ResourcePackService.PackInfo info = service.rebuild();
        byte[] first = info.hash();
        byte original = first[0];
        first[0] = (byte) (first[0] + 1);

        assertEquals(original, info.hash()[0]);
    }

    @Test
    void cachesTheHashAndRefreshesItWhenTheArchiveChanges() throws Exception {
        ResourcePackService service = new ResourcePackService(tempDir);
        ResourcePackService.PackInfo first = service.rebuild();

        assertSame(first, service.getInfo());

        byte[] replacement = "an externally replaced pack archive with another size".getBytes(StandardCharsets.UTF_8);
        Files.write(service.getArchive(), replacement);
        ResourcePackService.PackInfo refreshed = service.getInfo();

        assertNotSame(first, refreshed);
        assertEquals(replacement.length, refreshed.size());
        assertNotEquals(first.sha1(), refreshed.sha1());
    }

    @Test
    void rebuildReplacesThePreviousArchiveCleanly() throws Exception {
        ResourcePackService service = new ResourcePackService(tempDir);
        ResourcePackService.PackInfo first = service.rebuild();
        Files.writeString(service.getDirectory().resolve("added.txt"), "new content");
        ResourcePackService.PackInfo second = service.rebuild();

        assertTrue(second.size() > first.size());
        try (ZipFile zip = new ZipFile(second.path().toFile())) {
            assertNotNull(zip.getEntry("added.txt"));
        }
        assertFalse(Files.exists(service.getArchive().resolveSibling("resource-pack.zip.tmp")));
    }
}
