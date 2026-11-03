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

public final class UniversalModSyncPlugin extends JavaPlugin {
    private ModPackService modPackService;
    private ModSyncHttpServer httpServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Path modsDirectory = getDataFolder().toPath().resolve("mods");
        try {
            Files.createDirectories(modsDirectory);
            modPackService = new ModPackService(modsDirectory);
            modPackService.refresh();
            startHttpServer();
        } catch (Exception exception) {
            getLogger().severe("UniversalModSync could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("UniversalModSync enabled. Mod files: " + modPackService.getEntries().size());
        getLogger().info("Put client-side Forge mod JARs in: " + modsDirectory);
    }

    private void startHttpServer() throws IOException {
        if (!getConfig().getBoolean("http.enabled", true)) return;
        String address = getConfig().getString("http.bind-address", "0.0.0.0");
        int port = getConfig().getInt("http.port", 8765);
        if (port < 1 || port > 65535) throw new IOException("Invalid HTTP port: " + port);
        httpServer = new ModSyncHttpServer(
                new InetSocketAddress(address, port), modPackService, getLogger(), getDescription().getVersion());
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
            sender.sendMessage(ChatColor.GREEN + "UniversalModSync is running.");
            sender.sendMessage(ChatColor.GRAY + "Published JAR files: " + modPackService.getEntries().size());
            sender.sendMessage(ChatColor.GRAY + "Mods folder: " + getDataFolder().toPath().resolve("mods"));
            sender.sendMessage(ChatColor.GRAY + "HTTP: " + (httpServer == null ? "disabled" : "enabled"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                modPackService.refresh();
                sender.sendMessage(ChatColor.GREEN + "Mod manifest refreshed. Files: " + modPackService.getEntries().size());
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
        if (httpServer != null) httpServer.stop();
    }
}
