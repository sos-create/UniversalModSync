package com.soscreate.universalmodsync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class ModSyncHttpServer {
    private final HttpServer server;
    private final ModPackService modPackService;
    private final Logger logger;
    private final String pluginVersion;

    public ModSyncHttpServer(InetSocketAddress address, ModPackService service, Logger logger, String pluginVersion) throws IOException {
        this.server = HttpServer.create(address, 0);
        this.modPackService = service;
        this.logger = logger;
        this.pluginVersion = pluginVersion;
        server.createContext("/v1/manifest", this::handleManifest);
        server.createContext("/v1/mods/", this::handleMod);
        server.setExecutor(ExecutorsHolder.EXECUTOR);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(1); }

    private void handleManifest(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { respond(exchange, 405, "Method not allowed"); return; }
        StringBuilder json = new StringBuilder("{\"protocol\":1,\"pluginVersion\":\"")
                .append(escape(pluginVersion)).append("\",\"mods\":[");
        boolean first = true;
        for (ModPackService.ModEntry entry : modPackService.getEntries()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"file\":\"").append(escape(entry.fileName()))
                    .append("\",\"size\":").append(entry.size())
                    .append(",\"sha256\":\"").append(entry.sha256()).append("\"}");
        }
        json.append("]}");
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private void handleMod(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { respond(exchange, 405, "Method not allowed"); return; }
        String raw = exchange.getRequestURI().getRawPath().substring("/v1/mods/".length());
        String name;
        try { name = URLDecoder.decode(raw, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException badEncoding) { respond(exchange, 400, "Bad file name"); return; }
        Path file = modPackService.resolveMod(name);
        if (file == null) { respond(exchange, 404, "Mod not found"); return; }
        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replace("\"", "") + "\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, Files.size(file));
        try (var output = exchange.getResponseBody(); var input = Files.newInputStream(file)) { input.transferTo(output); }
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class ExecutorsHolder {
        private static final java.util.concurrent.Executor EXECUTOR = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "UniversalModSync-HTTP");
            thread.setDaemon(true);
            return thread;
        });
    }
}
