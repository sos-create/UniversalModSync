package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
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
            Path dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
            resourcePacks = new ResourcePackService(dataFolder);
            ResourcePackService.PackInfo packInfo = resourcePacks.rebuild();

            compatibility = new ModCompatibilityService(this, configuredDirectory("mods.directory", "mods"));
            adapterLoader = new ModAdapterLoader(this, configuredDirectory("adapters.directory", "adapters"));
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
            getLogger().info("Original Forge/Fabric JARs are metadata-only; Paper adapters provide server-side behavior.");
            getLogger().info("Resource pack archive: " + packInfo.size() + " bytes; SHA-1 " + packInfo.sha1());
        } catch (Exception exception) {
            getLogger().severe("UniversalModSync could not start: " + exception.getMessage());
            getLogger().fine("Startup failure details: " + exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private Path configuredDirectory(String key, String fallback) {
        String configured = getConfig().getString(key, fallback);
        if (configured == null || configured.isBlank()) configured = fallback;
        Path path = Path.of(configured.trim());
        if (!path.isAbsolute()) path = getDataFolder().toPath().resolve(path);
        return path.toAbsolutePath().normalize();
    }

    private void activateAdapters(List<ModAdapter> adapters) {
        Set<String> implemented = new HashSet<>();
        for (ModAdapter adapter : adapters) {
            try {
                Set<String> declared = adapter.supportedMods();
                if (declared == null || declared.isEmpty()) {
                    getLogger().warning("Adapter '" + adapter.getName() + "' declares no supported mod IDs.");
                    continue;
                }
                Set<String> ids = new HashSet<>();
                for (String id : declared) {
                    if (id != null && !id.isBlank()) ids.add(id.trim().toLowerCase(Locale.ROOT));
                }
                List<ModDescriptor> matching = detectedMods.stream()
                        .filter(mod -> ids.contains(mod.modId().toLowerCase(Locale.ROOT)))
                        .toList();
                if (matching.isEmpty()) continue;
                adapter.onEnable(this, matching);
                matching.forEach(mod -> implemented.add(mod.modId().toLowerCase(Locale.ROOT)));
                getLogger().info("Adapter '" + adapter.getName() + "' activated for " + matching.size() + " mod(s).");
            } catch (Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE,
                        "Adapter activation failed; server startup will continue: " + adapter.getClass().getName(), error);
            }
        }
        for (ModDescriptor mod : detectedMods) {
            String id = mod.modId().toLowerCase(Locale.ROOT);
            if (mod.compatibility().equals("CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER")) {
                getLogger().warning(mod.modId() + ": client-only mod; cannot run on Paper.");
            } else if (!implemented.contains(id)) {
                getLogger().warning(mod.modId() + ": detected, but no Paper adapter is installed.");
            }
        }
    }

    private void startHttpServer() {
        if (!getConfig().getBoolean("http.enabled", true)) return;
        String address = getConfig().getString("http.bind-address", "0.0.0.0");
        int port = getConfig().getInt("http.port", 8765);
        if (port < 1 || port > 65535) {
            getLogger().warning("HTTP service disabled: invalid port " + port + ".");
            return;
        }
        try {
            httpServer = new ModSyncHttpServer(new InetSocketAddress(address, port), resourcePacks);
            httpServer.start();
            getLogger().info("HTTP service listening on " + address + ":" + port + ".");
        } catch (IOException | IllegalArgumentException exception) {
            httpServer = null;
            getLogger().warning("HTTP service could not bind and was disabled; the main plugin remains active: "
                    + exception.getMessage());
        }
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
            sender.sendMessage(ChatColor.GRAY + "Loaded adapter providers: "
                    + (adapterLoader == null ? 0 : adapterLoader.adapters().size()));
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
            List<ModAdapter> adapters = adapterLoader == null ? List.of() : adapterLoader.adapters();
            sender.sendMessage(ChatColor.GREEN + "Adapter providers: " + adapters.size());
            for (ModAdapter adapter : adapters) {
                sender.sendMessage(ChatColor.GRAY + adapter.getName() + " — " + String.join(", ", adapter.supportedMods()));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(ChatColor.YELLOW + "Adapter changes require a full server restart.");
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
