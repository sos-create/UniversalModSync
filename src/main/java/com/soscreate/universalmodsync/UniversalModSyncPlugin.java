package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

public final class UniversalModSyncPlugin extends JavaPlugin {
    private ModSyncHttpServer httpServer;
    private ResourcePackService resourcePacks;
    private ModCompatibilityService compatibility;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            resourcePacks = new ResourcePackService(getDataFolder().toPath());
            ResourcePackService.PackInfo packInfo = resourcePacks.rebuild();
            compatibility = new ModCompatibilityService(this);
            List<ModCompatibilityService.ModReport> reports = compatibility.scan();

            getServer().getPluginManager().registerEvents(new ResourcePackListener(
                    resourcePacks,
                    getConfig().getBoolean("resource-pack.enabled", true),
                    getConfig().getString("resource-pack.public-url", ""),
                    getConfig().getString("resource-pack.prompt", ""),
                    getConfig().getBoolean("resource-pack.required", false),
                    getLogger()), this);

            startHttpServer();
            getLogger().info("UniversalModSync compatibility core enabled.");
            getLogger().info("Scanned " + reports.size() + " mod archive(s); Paper adapters are required to run mod functionality.");
            getLogger().info("Generic resource pack archive: " + packInfo.size() + " bytes; SHA-1 " + packInfo.sha1());
            if (getConfig().getString("resource-pack.public-url", "").isBlank()) {
                getLogger().info("No public resource-pack URL configured; resource-pack delivery is inactive.");
            }
        } catch (Exception exception) {
            getLogger().severe("UniversalModSync could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void startHttpServer() throws IOException {
        if (!getConfig().getBoolean("http.enabled", true)) return;
        String address = getConfig().getString("http.bind-address", "0.0.0.0");
        int port = getConfig().getInt("http.port", 8765);
        if (port < 1 || port > 65535) throw new IOException("Invalid HTTP port: " + port);
        httpServer = new ModSyncHttpServer(new InetSocketAddress(address, port), resourcePacks);
        httpServer.start();
        getLogger().info("HTTP service listening on " + address + ":" + port);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("modsync")) return false;
        if (!sender.hasPermission("universalmodsync.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GREEN + "UniversalModSync compatibility core is running.");
            sender.sendMessage(ChatColor.GRAY + "Detected mod archives: " + compatibility.getLastReports().size());
            sender.sendMessage(ChatColor.GRAY + "Registered Paper adapters: " + compatibility.getAdapterCount());
            sender.sendMessage(ChatColor.GRAY + "Resource pack HTTP: " + (httpServer == null ? "disabled" : "enabled"));
            return true;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("mods"))) {
            List<ModCompatibilityService.ModReport> reports = compatibility.scan();
            sender.sendMessage(ChatColor.GREEN + "Mod scan complete: " + reports.size() + " archive(s).");
            for (ModCompatibilityService.ModReport report : reports) {
                sender.sendMessage(ChatColor.GRAY + report.fileName() + " — " + report.modId()
                        + " " + report.version() + " — " + report.status());
            }
            if (reports.isEmpty()) sender.sendMessage(ChatColor.YELLOW + "No mod JARs found in the configured mods folder.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                ResourcePackService.PackInfo pack = resourcePacks.rebuild();
                List<ModCompatibilityService.ModReport> reports = compatibility.scan();
                sender.sendMessage(ChatColor.GREEN + "Scan complete: " + reports.size()
                        + " mod archive(s); resource pack rebuilt (" + pack.size() + " bytes).");
            } catch (IOException exception) {
                sender.sendMessage(ChatColor.RED + "Reload failed: " + exception.getMessage());
            }
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /modsync <status|scan|mods|reload>");
        return true;
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop();
    }
}
