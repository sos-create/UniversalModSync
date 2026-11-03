package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UniversalModSyncPlugin extends JavaPlugin {
    private ModSyncHttpServer httpServer;
    private ResourcePackService resourcePacks;
    private ModCompatibilityService compatibility;
    private ModAdapterLoader adapterLoader;
    private List<ModDescriptor> detectedMods = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            resourcePacks = new ResourcePackService(getDataFolder().toPath());
            ResourcePackService.PackInfo packInfo = resourcePacks.rebuild();
            compatibility = new ModCompatibilityService(this);
            adapterLoader = new ModAdapterLoader(this);
            detectedMods = compatibility.scan();
            activateAdapters(adapterLoader.load());

            getServer().getPluginManager().registerEvents(new ResourcePackListener(
                    resourcePacks,
                    getConfig().getBoolean("resource-pack.enabled", true),
                    getConfig().getString("resource-pack.public-url", ""),
                    getConfig().getString("resource-pack.prompt", ""),
                    getConfig().getBoolean("resource-pack.required", false),
                    getLogger()), this);

            startHttpServer();
            getLogger().info("UniversalModSync started: " + detectedMods.size() + " mod descriptor(s), "
                    + adapterLoader.adapters().size() + " adapter provider(s).");
            getLogger().info("Mod JARs are inspected, never executed by Paper. Only trusted Paper adapters implement behavior.");
            getLogger().info("Resource pack: " + packInfo.size() + " bytes; SHA-1 " + packInfo.sha1());
        } catch (Exception exception) {
            getLogger().severe("UniversalModSync could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void activateAdapters(List<ModAdapter> adapters) {
        Set<String> known = new HashSet<>();
        for (ModAdapter adapter : adapters) {
            Set<String> ids = new HashSet<>();
            for (String id : adapter.supportedMods()) ids.add(id.toLowerCase(Locale.ROOT));
            List<ModDescriptor> matching = detectedMods.stream()
                    .filter(mod -> ids.contains(mod.modId().toLowerCase(Locale.ROOT)))
                    .toList();
            if (matching.isEmpty()) continue;
            try {
                adapter.onEnable(this, matching);
                matching.forEach(mod -> known.add(mod.modId().toLowerCase(Locale.ROOT)));
                getLogger().info("Adapter '" + adapter.getName() + "' activated for " + matching.size() + " mod(s).");
            } catch (Throwable error) {
                getLogger().severe("Adapter '" + adapter.getName() + "' failed: " + error.getMessage());
            }
        }
        for (ModDescriptor mod : detectedMods) {
            String id = mod.modId().toLowerCase(Locale.ROOT);
            if (mod.compatibility().equals("CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER")) {
                getLogger().warning(mod.modId() + ": client-only mod; cannot run on Paper.");
            } else if (!known.contains(id)) {
                getLogger().warning(mod.modId() + ": detected, but no Paper adapter is installed.");
            }
        }
    }

    private void startHttpServer() throws IOException {
        if (!getConfig().getBoolean("http.enabled", true)) return;
        String address = getConfig().getString("http.bind-address", "0.0.0.0");
        int port = getConfig().getInt("http.port", 8765);
        if (port < 1 || port > 65535) throw new IOException("Invalid HTTP port: " + port);
        httpServer = new ModSyncHttpServer(new InetSocketAddress(address, port), resourcePacks);
        httpServer.start();
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
            sender.sendMessage(ChatColor.GRAY + "Detected mod descriptors: " + detectedMods.size());
            sender.sendMessage(ChatColor.GRAY + "Loaded adapter providers: " + adapterLoader.adapters().size());
            sender.sendMessage(ChatColor.GRAY + "Resource pack HTTP: " + (httpServer == null ? "disabled" : "enabled"));
            return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("mods"))) {
            try {
                detectedMods = compatibility.scan();
                sender.sendMessage(ChatColor.GREEN + "Found " + detectedMods.size() + " mod descriptor(s).");
                for (ModDescriptor mod : detectedMods) {
                    sender.sendMessage(ChatColor.GRAY + mod.loader() + " | " + mod.modId() + " " + mod.version()
                            + " | " + mod.compatibility() + " | dependencies: " + String.join(", ", mod.dependencies()));
                }
            } catch (IOException exception) {
                sender.sendMessage(ChatColor.RED + "Scan failed: " + exception.getMessage());
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("adapters")) {
            sender.sendMessage(ChatColor.GREEN + "Adapter providers: " + adapterLoader.adapters().size());
            for (ModAdapter adapter : adapterLoader.adapters()) {
                sender.sendMessage(ChatColor.GRAY + adapter.getName() + " — " + String.join(", ", adapter.supportedMods()));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(ChatColor.YELLOW + "Resource pack can be rebuilt now; adapter changes require a full server restart.");
            try {
                ResourcePackService.PackInfo pack = resourcePacks.rebuild();
                detectedMods = compatibility.scan();
                sender.sendMessage(ChatColor.GREEN + "Scan complete: " + detectedMods.size()
                        + " descriptor(s); resource pack rebuilt (" + pack.size() + " bytes).");
            } catch (IOException exception) {
                sender.sendMessage(ChatColor.RED + "Reload failed: " + exception.getMessage());
            }
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Usage: /modsync <status|scan|mods|adapters|reload>");
        return true;
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop();
        if (adapterLoader != null) adapterLoader.close();
    }
}
