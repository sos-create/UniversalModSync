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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModSyncHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ResourcePackService resourcePacks;
    private final ExecutorService executor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private boolean started;

    public ModSyncHttpServer(InetSocketAddress address, ResourcePackService resourcePacks) throws IOException {
        if (address == null) throw new IllegalArgumentException("HTTP bind address cannot be null");
        if (resourcePacks == null) throw new IllegalArgumentException("Resource-pack service cannot be null");

        HttpServer created = HttpServer.create(address, 16);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable,
                    "UniversalModSync-HTTP-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            created.createContext("/v1/health", this::handleHealth);
            created.createContext("/v1/resource-pack.zip", this::handleResourcePack);
            created.setExecutor(workers);
        } catch (RuntimeException | LinkageError error) {
            workers.shutdownNow();
            created.stop(0);
            throw error;
        }

        this.server = created;
        this.executor = workers;
        this.resourcePacks = resourcePacks;
    }

    public synchronized void start() {
        if (stopped.get()) throw new IllegalStateException("HTTP service is already stopped");
        if (started) return;
        try {
            server.start();
            started = true;
        } catch (RuntimeException | LinkageError error) {
            stop();
            throw error;
        }
    }

    public InetSocketAddress getAddress() {
        return server.getAddress();
    }

    @Override
    public void close() {
        stop();
    }

    public synchronized void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        if (started) server.stop(0);
        executor.shutdownNow();
        started = false;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/v1/health")) {
            respond(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        respond(exchange, 200, "{\"status\":\"ok\",\"target\":\"PAPER_ADAPTER_FRAMEWORK\"}");
    }

    private void handleResourcePack(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/v1/resource-pack.zip")) {
            respond(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        ResourcePackService.PackInfo pack = resourcePacks.getInfo();
        if (pack == null || !Files.isRegularFile(pack.path())) {
            respond(exchange, 404, "{\"error\":\"resource_pack_not_built\"}");
            return;
        }

        String etag = "\"" + pack.sha1() + "\"";
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"UniversalModSync-resource-pack.zip\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("ETag", etag);

        if (matchesEtag(exchange.getRequestHeaders().getFirst("If-None-Match"), etag)) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        if (method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(pack.size()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, pack.size());
        try (var output = exchange.getResponseBody(); var input = Files.newInputStream(pack.path())) {
            input.transferTo(output);
        }
    }

    private static boolean matchesEtag(String header, String etag) {
        if (header == null || header.isBlank()) return false;
        for (String token : header.split(",")) {
            String candidate = token.trim();
            if (candidate.equals("*") || candidate.equals(etag)) return true;
        }
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
