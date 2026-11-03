package com.soscreate.universalmodsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModSyncHttpServerTest {
    @TempDir
    Path tempDir;

    @Test
    void servesHealthAndResourcePackWithSafeMethodHandling() throws Exception {
        ResourcePackService packs = new ResourcePackService(tempDir);
        Files.writeString(packs.getDirectory().resolve("test.txt"), "resource-pack-content");
        ResourcePackService.PackInfo info = packs.rebuild();

        try (ModSyncHttpServer server = new ModSyncHttpServer(
                new InetSocketAddress("127.0.0.1", 0), packs)) {
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("PAPER_ADAPTER_FRAMEWORK"));

            HttpResponse<String> unsupported = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/health")).POST(
                            HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, unsupported.statusCode());
            assertEquals("GET, HEAD", unsupported.headers().firstValue("Allow").orElse(""));

            HttpResponse<byte[]> pack = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/resource-pack.zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, pack.statusCode());
            assertArrayEquals(Files.readAllBytes(info.path()), pack.body());
            String etag = pack.headers().firstValue("ETag").orElseThrow();

            HttpResponse<byte[]> cached = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/resource-pack.zip"))
                            .header("If-None-Match", etag).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(304, cached.statusCode());
            assertEquals(0, cached.body().length);

            HttpResponse<byte[]> head = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/resource-pack.zip")).method("HEAD",
                            HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
        }
    }

    @Test
    void stopIsSafeWhenCalledMoreThanOnce() throws Exception {
        ResourcePackService packs = new ResourcePackService(tempDir);
        packs.rebuild();
        ModSyncHttpServer server = new ModSyncHttpServer(new InetSocketAddress("127.0.0.1", 0), packs);
        server.start();
        assertDoesNotThrow(server::stop);
        assertDoesNotThrow(server::stop);
    }
}
