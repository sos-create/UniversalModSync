package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class UniversalModSyncPlugin extends JavaPlugin {
    private ModSyncHttpServer httpServer;
    private ResourcePackService resourcePacks;
    private ModCompatibilityService compatibility;
    private ModAdapterLoader adapterLoader;
    private final List<ModAdapter> activeAdapters = new ArrayList<>();
    private volatile List<ModDescriptor> detectedMods = List.of();

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
        } catch (RuntimeException exception) {
            getLogger().log(Level.WARNING,
                    "Could not create the default configuration; built-in defaults will be used where possible.",
                    exception);
        }

        initializeModScanner();
        initializeAdapterLoader();
        initializeResourcePackStorage();

        if (compatibility != null && adapterLoader != null) {
            activateAdapters(adapterLoader.load());
        } else if (adapterLoader != null) {
            getLogger().warning("Mod scanning is unavailable; adapters were loaded but not activated.");
        }

        finishResourcePackInitialization();
        startHttpServer();

        getLogger().info("UniversalModSync started. Detected descriptors: " + detectedMods.size()
                + "; active adapters: " + activeAdapters.size()
                + "; HTTP service: " + (httpServer == null ? "disabled" : "enabled") + ".");
        getLogger().info("Forge/Fabric/Quilt archives are inspected for metadata only; their original bytecode is not executed.");
    }

    private void initializeModScanner() {
        try {
            compatibility = new ModCompatibilityService(configuredDirectory("mods.directory", "mods"));
            detectedMods = compatibility.scan();
            getLogger().info("Scanned " + detectedMods.size() + " mod descriptor(s) from "
                    + compatibility.getModsDirectory() + ".");
        } catch (IOException | RuntimeException | LinkageError exception) {
            compatibility = null;
            detectedMods = List.of();
            getLogger().log(Level.WARNING,
                    "Mod scanning could not be initialized. The rest of the server and plugin will continue.", exception);
        }
    }

    private void initializeAdapterLoader() {
        try {
            adapterLoader = new ModAdapterLoader(this, configuredDirectory("adapters.directory", "adapters"));
        } catch (IOException | RuntimeException | LinkageError exception) {
            adapterLoader = null;
            getLogger().log(Level.WARNING,
                    "Adapter loading could not be initialized. Mod discovery and other features remain available.",
                    exception);
        }
    }

    private void initializeResourcePackStorage() {
        try {
            // Create the resource-pack directory before adapters start so they can add assets.
            resourcePacks = new ResourcePackService(getDataFolder().toPath());
        } catch (IOException | RuntimeException | LinkageError exception) {
            resourcePacks = null;
            getLogger().log(Level.WARNING,
                    "Resource-pack storage could not be initialized. Other plugin features remain available.", exception);
        }
    }

    private void finishResourcePackInitialization() {
        if (resourcePacks == null) return;

        try {
            ResourcePackService.PackInfo packInfo = resourcePacks.rebuild();
            getLogger().info("Resource pack built: " + packInfo.size() + " bytes, SHA-1 " + packInfo.sha1());
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.WARNING,
                    "Resource pack could not be built. Mod discovery and adapters remain available.", exception);
        }

        try {
            getServer().getPluginManager().registerEvents(new ResourcePackListener(
                    resourcePacks,
                    getConfig().getBoolean("resource-pack.enabled", true),
                    getConfig().getString("resource-pack.public-url", ""),
                    getConfig().getString("resource-pack.prompt", ""),
                    getConfig().getBoolean("resource-pack.required", false),
                    getLogger()), this);
        } catch (RuntimeException | LinkageError exception) {
            getLogger().log(Level.WARNING, "Resource pack join listener could not be registered.", exception);
        }
    }

    private Path configuredDirectory(String key, String fallback) {
        String configured = getConfig().getString(key, fallback);
        if (configured == null || configured.isBlank()) configured = fallback;
        try {
            Path path = Path.of(configured.trim());
            if (!path.isAbsolute()) path = getDataFolder().toPath().resolve(path);
            return path.toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            getLogger().warning("Invalid path in config '" + key + "'; using '" + fallback + "'.");
            return getDataFolder().toPath().resolve(fallback).toAbsolutePath().normalize();
        }
    }

    private void activateAdapters(List<ModAdapter> adapters) {
        Set<String> implementedFiles = new HashSet<>();
        for (ModAdapter adapter : adapters) {
            Set<String> declared;
            try {
                declared = adapter.supportedModFiles();
                if (declared == null || declared.isEmpty()) {
                    getLogger().warning("Adapter " + adapter.getClass().getName()
                            + " declares no target archive file names.");
                    continue;
                }
            } catch (RuntimeException | LinkageError error) {
                getLogger().log(Level.WARNING,
                        "Could not read target filenames from adapter " + adapter.getClass().getName(), error);
                continue;
            }

            List<ModDescriptor> matching = detectedMods.stream()
                    .filter(mod -> !implementedFiles.contains(mod.fileName().toLowerCase(Locale.ROOT)))
                    .filter(mod -> ModAdapter.targetsFile(declared, mod.fileName()))
                    .toList();
            if (matching.isEmpty()) continue;

            try {
                adapter.onEnable(this, matching);
                activeAdapters.add(adapter);
                matching.forEach(mod -> implementedFiles.add(mod.fileName().toLowerCase(Locale.ROOT)));
                getLogger().info("Activated adapter '" + adapterDisplayName(adapter) + "' for "
                        + matching.stream().map(ModDescriptor::fileName).distinct().toList() + ".");
            } catch (Exception | LinkageError error) {
                getLogger().log(Level.SEVERE,
                        "Adapter activation failed for " + matching.stream().map(ModDescriptor::fileName).distinct().toList()
                                + "; server startup will continue.",
                        error);
                safelyDisableAdapter(adapter);
            }
        }

        Set<String> reportedFiles = new HashSet<>();
        for (ModDescriptor mod : detectedMods) {
            String key = mod.fileName().toLowerCase(Locale.ROOT);
            if (!reportedFiles.add(key) || implementedFiles.contains(key)) continue;
            if (mod.compatibility().equals("CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER")) {
                getLogger().warning(mod.fileName() + " (" + mod.modId()
                        + "): client-only mod; it cannot run as a server plugin on Paper.");
            } else {
                getLogger().warning(mod.fileName() + " (" + mod.modId()
                        + "): detected, but no working Paper adapter targets this exact archive filename.");
            }
        }
    }

    private String adapterDisplayName(ModAdapter adapter) {
        try {
            String name = adapter.getName();
            return name == null || name.isBlank() ? adapter.getClass().getName() : name;
        } catch (RuntimeException | LinkageError error) {
            return adapter.getClass().getName();
        }
    }

    private void safelyDisableAdapter(ModAdapter adapter) {
        try {
            adapter.onDisable();
        } catch (RuntimeException | LinkageError error) {
            getLogger().log(Level.WARNING, "Adapter cleanup failed: " + adapter.getClass().getName(), error);
        }
    }

    private void startHttpServer() {
        if (!getConfig().getBoolean("http.enabled", false)) return;
        if (resourcePacks == null) {
            getLogger().warning("HTTP service was not started because resource-pack storage is unavailable.");
            return;
        }

        String address = getConfig().getString("http.bind-address", "127.0.0.1");
        if (address == null || address.isBlank()) address = "127.0.0.1";
        int port = getConfig().getInt("http.port", 8765);
        if (port < 1 || port > 65535) {
            getLogger().warning("HTTP service disabled: invalid port " + port + ".");
            return;
        }

        try {
            InetSocketAddress socketAddress = new InetSocketAddress(address.trim(), port);
            InetAddress resolved = socketAddress.getAddress();
            boolean publicBindAllowed = getConfig().getBoolean("http.allow-public-bind", false);
            if (resolved != null && resolved.isAnyLocalAddress() && !publicBindAllowed) {
                getLogger().warning("HTTP service was not bound to all network interfaces. "
                        + "Set http.allow-public-bind: true only when public access is intentional and protected by a firewall.");
                return;
            }

            httpServer = new ModSyncHttpServer(socketAddress, resourcePacks);
            httpServer.start();
            getLogger().info("HTTP service listening on " + socketAddress + ".");
        } catch (IOException | RuntimeException | LinkageError exception) {
            httpServer = null;
            getLogger().log(Level.WARNING,
                    "HTTP service could not start; the main plugin remains active.", exception);
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
            sender.sendMessage(ChatColor.GRAY + "Active adapters: " + activeAdapters.size());
            sender.sendMessage(ChatColor.GRAY + "Mod scanner: " + (compatibility == null ? "unavailable" : "ready"));
            sender.sendMessage(ChatColor.GRAY + "Adapter loader: " + (adapterLoader == null ? "unavailable" : "ready"));
            sender.sendMessage(ChatColor.GRAY + "Resource pack HTTP: " + (httpServer == null ? "disabled" : "enabled"));
            return true;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("mods"))) {
            if (compatibility == null) {
                sender.sendMessage(ChatColor.RED + "Mod scanner is unavailable. Check the server log.");
                return true;
            }
            try {
                detectedMods = compatibility.scan();
                sender.sendMessage(ChatColor.GREEN + "Found " + detectedMods.size() + " mod descriptor(s).");
                for (ModDescriptor mod : detectedMods) {
                    sender.sendMessage(ChatColor.GRAY + mod.fileName() + " | " + mod.loader() + " | "
                            + mod.modId() + " " + mod.version() + " | " + mod.compatibility()
                            + " | dependencies: " + String.join(", ", mod.dependencies()));
                }
            } catch (IOException | RuntimeException exception) {
                sender.sendMessage(ChatColor.RED + "Scan failed: " + exception.getMessage());
                getLogger().log(Level.WARNING, "Manual mod scan failed", exception);
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("adapters")) {
            List<ModAdapter> adapters = adapterLoader == null ? List.of() : adapterLoader.adapters();
            sender.sendMessage(ChatColor.GREEN + "Loaded adapter providers: " + adapters.size());
            for (ModAdapter adapter : adapters) {
                try {
                    sender.sendMessage(ChatColor.GRAY + adapterDisplayName(adapter) + " — files: "
                            + String.join(", ", adapter.supportedModFiles()));
                } catch (RuntimeException | LinkageError error) {
                    sender.sendMessage(ChatColor.RED + adapter.getClass().getName() + " — invalid adapter metadata");
                }
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(ChatColor.YELLOW + "Adapter JAR changes require a full server restart.");
            if (compatibility != null) {
                try {
                    detectedMods = compatibility.scan();
                    sender.sendMessage(ChatColor.GREEN + "Mod scan complete: " + detectedMods.size() + " descriptor(s).");
                } catch (IOException | RuntimeException exception) {
                    sender.sendMessage(ChatColor.RED + "Mod scan failed: " + exception.getMessage());
                }
            }
            if (resourcePacks != null) {
                try {
                    ResourcePackService.PackInfo pack = resourcePacks.rebuild();
                    sender.sendMessage(ChatColor.GREEN + "Resource pack rebuilt (" + pack.size() + " bytes).");
                } catch (IOException | RuntimeException exception) {
                    sender.sendMessage(ChatColor.RED + "Resource pack rebuild failed: " + exception.getMessage());
                }
            }
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /modsync <status|scan|mods|adapters|reload>");
        return true;
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            try {
                httpServer.stop();
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "HTTP service shutdown failed", exception);
            }
            httpServer = null;
        }

        for (int index = activeAdapters.size() - 1; index >= 0; index--) {
            safelyDisableAdapter(activeAdapters.get(index));
        }
        activeAdapters.clear();

        if (adapterLoader != null) {
            adapterLoader.close();
            adapterLoader = null;
        }
    }
}
