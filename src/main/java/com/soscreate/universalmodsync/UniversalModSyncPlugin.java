package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class UniversalModSyncPlugin extends JavaPlugin {
    private ModPackService modPackService;
    private ModSyncHttpServer httpServer;
    private ResourcePackService resourcePacks;
    private ScheduledExecutorService scanner;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Path modsDirectory = getDataFolder().toPath().resolve("mods");
        try {
            Files.createDirectories(modsDirectory);
            modPackService = new ModPackService(modsDirectory, getLogger(),
                    getConfig().getBoolean("mods.include-unrecognized-jars", false));
            ModPackService.RefreshResult initial = modPackService.refresh();
            resourcePacks = new ResourcePackService(getDataFolder().toPath());
            ResourcePackService.PackInfo packInfo = resourcePacks.rebuild();
            getServer().getPluginManager().registerEvents(new ResourcePackListener(resourcePacks,
                    getConfig().getString("resource-pack.public-url", ""),
                    getConfig().getString("resource-pack.prompt", ""),
                    getConfig().getBoolean("resource-pack.required", false), getLogger()), this);
            startHttpServer();
            startFolderScanner();
            getLogger().info("UniversalModSync enabled for CLIENT-SINGLEPLAYER Forge modpacks.");
            getLogger().info("Published client mods: " + initial.modCount());
            getLogger().info("Server resource pack built: " + packInfo.size() + " bytes (SHA-1 " + packInfo.sha1() + ").");
            getLogger().info("Resource pack files: " + resourcePacks.getDirectory());
            if (getConfig().getString("resource-pack.public-url", "").isBlank()) {
                getLogger().warning("Set resource-pack.public-url to a publicly reachable URL ending in /v1/resource-pack.zip to send the pack to players.");
            }
            getLogger().info("Place client Forge JARs in: " + modsDirectory);
            getLogger().info("Paper distributes these files; it does not execute Forge mods.");
        } catch (Exception exception) {
            getLogger().severe("UniversalModSync could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void startFolderScanner() {
        int interval = Math.max(5, getConfig().getInt("mods.scan-interval-seconds", 10));
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "UniversalModSync-Scanner");
            thread.setDaemon(true);
            return thread;
        };
        scanner = Executors.newSingleThreadScheduledExecutor(factory);
        scanner.scheduleWithFixedDelay(() -> {
            try {
                ModPackService.RefreshResult result = modPackService.refresh();
                if (result.changed()) {
                    getLogger().info("Client modpack changed. Published " + result.modCount()
                            + " mod(s); manifest revision " + result.revision());
                }
            } catch (Exception exception) {
                getLogger().warning("Could not refresh mod folder: " + exception.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private void startHttpServer() throws IOException {
        if (!getConfig().getBoolean("http.enabled", true)) return;
        String address = getConfig().getString("http.bind-address", "0.0.0.0");
        int port = getConfig().getInt("http.port", 8765);
        if (port < 1 || port > 65535) throw new IOException("Invalid HTTP port: " + port);
        httpServer = new ModSyncHttpServer(new InetSocketAddress(address, port), modPackService,
                getLogger(), getDescription().getVersion(),
                getConfig().getString("pack.name", "UniversalModSync Client Pack"),
                getConfig().getString("pack.minecraft-version", "unspecified"),
                getConfig().getString("pack.forge-version", "unspecified"), resourcePacks);
        httpServer.start();
        getLogger().info("ModSync HTTP endpoint listening on " + address + ":" + port);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("modsync")) return false;
        if (!sender.hasPermission("universalmodsync.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GREEN + "UniversalModSync client pack is running.");
            sender.sendMessage(ChatColor.GRAY + "Client mods: " + modPackService.getEntries().size());
            sender.sendMessage(ChatColor.GRAY + "Mods folder: " + getDataFolder().toPath().resolve("mods"));
            sender.sendMessage(ChatColor.GRAY + "Manifest revision: " + modPackService.getRevision());
            sender.sendMessage(ChatColor.GRAY + "HTTP: " + (httpServer == null ? "disabled" : "enabled"));
            sender.sendMessage(ChatColor.GRAY + "Resource pack: " + (resourcePacks == null ? "not built" : "built"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                ModPackService.RefreshResult result = modPackService.refresh();
                ResourcePackService.PackInfo pack = resourcePacks.rebuild();
                sender.sendMessage(ChatColor.GREEN + "Pack refreshed. Legacy Forge files: " + result.modCount()
                        + ", resource pack: " + pack.size() + " bytes, SHA-1: " + pack.sha1());
            } catch (IOException exception) {
                sender.sendMessage(ChatColor.RED + "Refresh failed: " + exception.getMessage());
            }
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Usage: /modsync <status|reload>");
        return true;
    }

    @Override
    public void onDisable() {
        if (scanner != null) scanner.shutdownNow();
        if (httpServer != null) httpServer.stop();
    }
}
