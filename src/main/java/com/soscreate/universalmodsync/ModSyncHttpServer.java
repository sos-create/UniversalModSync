package com.soscreate.universalmodsync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ModSyncHttpServer {
    private final HttpServer server;
    private final ResourcePackService resourcePacks;
    private final ExecutorService executor;

    public ModSyncHttpServer(InetSocketAddress address, ResourcePackService resourcePacks) throws IOException {
        this.server = HttpServer.create(address, 0);
        this.resourcePacks = resourcePacks;
        server.createContext("/v1/health", this::handleHealth);
        server.createContext("/v1/resource-pack.zip", this::handleResourcePack);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "UniversalModSync-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
    }

    public void start() { server.start(); }

    public void stop() {
        server.stop(1);
        executor.shutdownNow();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        respond(exchange, 200, "{\"status\":\"ok\",\"target\":\"PAPER_ADAPTER_FRAMEWORK\"}");
    }

    private void handleResourcePack(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        ResourcePackService.PackInfo pack = resourcePacks.getInfo();
        if (pack == null) {
            respond(exchange, 404, "{\"error\":\"resource_pack_not_built\"}");
            return;
        }
        Path path = pack.path();
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"UniversalModSync-resource-pack.zip\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("ETag", "\""+ pack.sha1() + "\"");
        exchange.sendResponseHeaders(200, Files.size(path));
        try (var output = exchange.getResponseBody(); var input = Files.newInputStream(path)) {
            input.transferTo(output);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
