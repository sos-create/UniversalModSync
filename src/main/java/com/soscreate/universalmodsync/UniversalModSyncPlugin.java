package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;

public final class UniversalModSyncPlugin extends JavaPlugin {
    private ModSyncHttpServer httpServer;
    private ResourcePackService resourcePacks;
    private AdventureContentService adventureContent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            resourcePacks = new ResourcePackService(getDataFolder().toPath());
            ResourcePackService.PackInfo packInfo = resourcePacks.rebuild();

            adventureContent = new AdventureContentService(this);
            getServer().getPluginManager().registerEvents(adventureContent, this);
            getServer().getPluginManager().registerEvents(new ResourcePackListener(
                    resourcePacks,
                    getConfig().getBoolean("resource-pack.enabled", true),
                    getConfig().getString("resource-pack.public-url", ""),
                    getConfig().getString("resource-pack.prompt", ""),
                    getConfig().getBoolean("resource-pack.required", false),
                    getLogger()), this);

            startHttpServer();
            getLogger().info("UniversalModSync enabled: server-side Paper adventure system.");
            getLogger().info("Built-in adventure items, dungeon, guardian and locked vault are ready.");
            getLogger().info("Resource pack archive: " + packInfo.size() + " bytes; SHA-1 " + packInfo.sha1());
            if (getConfig().getString("resource-pack.public-url", "").isBlank()) {
                getLogger().warning("Set resource-pack.public-url to the public URL ending in /v1/resource-pack.zip; players will not receive custom textures until it is configured.");
            }
            getLogger().info("Players do not need Forge, client mods, a launcher, or manual cleanup after leaving.");
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
        getLogger().info("Resource pack HTTP endpoint listening on " + address + ":" + port);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("modsync")) return false;
        if (!sender.hasPermission("universalmodsync.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        if (adventureContent != null && adventureContent.handleCommand(sender, args)) return true;

        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GREEN + "UniversalModSync server adventure system is running.");
            sender.sendMessage(ChatColor.GRAY + "Content: Ember Blade, Healing Orb, Dungeon Key, Ashen Warden dungeon.");
            sender.sendMessage(ChatColor.GRAY + "HTTP endpoint: " + (httpServer == null ? "disabled" : "enabled"));
            sender.sendMessage(ChatColor.GRAY + "Resource pack delivery: "
                    + (getConfig().getBoolean("resource-pack.enabled", true) ? "enabled" : "disabled"));
            sender.sendMessage(ChatColor.GRAY + "Public URL configured: "
                    + !getConfig().getString("resource-pack.public-url", "").isBlank());
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                ResourcePackService.PackInfo pack = resourcePacks.rebuild();
                sender.sendMessage(ChatColor.GREEN + "Resource pack rebuilt. Size: " + pack.size()
                        + " bytes; SHA-1: " + pack.sha1());
                sender.sendMessage(ChatColor.GRAY + "Players will receive the updated pack when they join again.");
            } catch (IOException exception) {
                sender.sendMessage(ChatColor.RED + "Resource pack rebuild failed: " + exception.getMessage());
            }
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW
                + "Usage: /modsync <status|reload|dungeon|boss|give <player> <ember_blade|healing_orb|dungeon_key>>");
        return true;
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop();
    }
}
