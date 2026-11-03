package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;

/** Loads explicitly installed, administrator-trusted Paper adapter JARs. */
public final class ModAdapterLoader {
    private final JavaPlugin plugin;
    private final Path directory;
    private final List<LoadedAdapter> loaded = new ArrayList<>();

    public ModAdapterLoader(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("adapters").toAbsolutePath().normalize();
        Files.createDirectories(directory);
    }

    public List<ModAdapter> load() {
        close();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(this::loadOne);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not scan adapter directory", exception);
        }
        return loaded.stream().map(LoadedAdapter::adapter).toList();
    }

    private void loadOne(Path jar) {
        URLClassLoader classLoader = null;
        try {
            classLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                    ModAdapter.class.getClassLoader());
            List<ModAdapter> providers = ServiceLoader.load(ModAdapter.class, classLoader)
                    .stream().map(ServiceLoader.Provider::get).toList();
            if (providers.isEmpty()) {
                plugin.getLogger().warning("Adapter JAR has no ModAdapter service declaration: " + jar.getFileName());
                classLoader.close();
                return;
            }
            for (ModAdapter adapter : providers) loaded.add(new LoadedAdapter(adapter, classLoader));
            plugin.getLogger().info("Loaded " + providers.size() + " adapter provider(s) from " + jar.getFileName());
        } catch (Throwable error) {
            if (classLoader != null) {
                try { classLoader.close(); } catch (IOException ignored) { }
            }
            plugin.getLogger().log(Level.WARNING, "Adapter could not be loaded: " + jar.getFileName(), error);
        }
    }

    public List<ModAdapter> adapters() {
        return loaded.stream().map(LoadedAdapter::adapter).toList();
    }

    public void close() {
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedAdapter entry = loaded.get(i);
            try { entry.adapter().onDisable(); }
            catch (Throwable error) { plugin.getLogger().log(Level.WARNING, "Adapter shutdown failed", error); }
        }
        loaded.stream().map(LoadedAdapter::classLoader).distinct().forEach(loader -> {
            try { loader.close(); } catch (IOException ignored) { }
        });
        loaded.clear();
    }

    private record LoadedAdapter(ModAdapter adapter, URLClassLoader classLoader) { }
}
