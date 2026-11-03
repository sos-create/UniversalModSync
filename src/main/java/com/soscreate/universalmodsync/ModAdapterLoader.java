package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.logging.Level;

/**
 * Loads explicitly installed, administrator-trusted Paper adapter JARs.
 * Each adapter archive is isolated in its own child class loader.
 */
public final class ModAdapterLoader implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Path directory;
    private final List<LoadedAdapter> loaded = new ArrayList<>();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private boolean closed;
    private boolean loadedOnce;

    public ModAdapterLoader(JavaPlugin plugin) throws IOException {
        this(plugin, plugin.getDataFolder().toPath().resolve("adapters"));
    }

    public ModAdapterLoader(JavaPlugin plugin, Path directory) throws IOException {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        if (directory == null) throw new IllegalArgumentException("Adapter directory cannot be null");
        this.plugin = plugin;
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory)) {
            throw new IOException("Adapter path is not a directory: " + this.directory);
        }
    }

    /** Loads valid adapter providers. One broken provider does not block other JARs. */
    public synchronized List<ModAdapter> load() {
        if (closed) throw new IllegalStateException("Adapter loader is already closed");
        if (loadedOnce) return adapters();
        loadedOnce = true;
        try (var files = Files.list(directory)) {
            files.filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .filter(ModAdapterLoader::isAdapterJar)
                    .sorted(Comparator.comparing(
                            (Path path) -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                            .thenComparing(path -> path.getFileName().toString()))
                    .forEach(this::loadOne);
        } catch (Throwable error) {
            rethrowFatal(error);
            plugin.getLogger().log(Level.WARNING, "Could not scan adapter directory " + directory, error);
        }
        return adapters();
    }

    private static boolean isAdapterJar(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private void loadOne(Path jar) {
        URLClassLoader classLoader = null;
        boolean retained = false;
        try {
            classLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                    ModAdapter.class.getClassLoader());
            ServiceLoader<ModAdapter> services = ServiceLoader.load(ModAdapter.class, classLoader);
            Iterator<ServiceLoader.Provider<ModAdapter>> providers = services.stream().iterator();
            int consecutiveDiscoveryErrors = 0;

            while (true) {
                ServiceLoader.Provider<ModAdapter> provider;
                try {
                    if (!providers.hasNext()) break;
                    provider = providers.next();
                    consecutiveDiscoveryErrors = 0;
                } catch (Throwable error) {
                    rethrowFatal(error);
                    plugin.getLogger().log(Level.WARNING,
                            "Could not read an adapter provider declaration in " + jar.getFileName(), error);
                    if (++consecutiveDiscoveryErrors >= 8) break;
                    continue;
                }

                try {
                    ModAdapter adapter = provider.get();
                    if (adapter == null) {
                        plugin.getLogger().warning("Adapter provider returned null: " + jar.getFileName());
                        continue;
                    }
                    loaded.add(new LoadedAdapter(adapter));
                    retained = true;
                    plugin.getLogger().info("Loaded adapter provider '" + safeName(adapter)
                            + "' from " + jar.getFileName());
                } catch (Throwable error) {
                    rethrowFatal(error);
                    plugin.getLogger().log(Level.WARNING,
                            "An adapter provider could not be constructed from " + jar.getFileName()
                                    + "; other providers will still be considered.", error);
                }
            }

            if (retained) classLoaders.add(classLoader);
            else plugin.getLogger().warning("No usable ModAdapter providers found in " + jar.getFileName());
        } catch (Throwable error) {
            rethrowFatal(error);
            plugin.getLogger().log(Level.WARNING,
                    "Adapter JAR could not be loaded: " + jar.getFileName(), error);
        } finally {
            if (!retained && classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.FINE, "Could not close adapter class loader", exception);
                }
            }
        }
    }

    private static String safeName(ModAdapter adapter) {
        try {
            String name = adapter.getName();
            return name == null || name.isBlank() ? adapter.getClass().getName() : name;
        } catch (Throwable error) {
            rethrowFatal(error);
            return adapter.getClass().getName();
        }
    }

    private static void rethrowFatal(Throwable error) {
        if (error instanceof VirtualMachineError fatal) throw fatal;
        if (error instanceof ThreadDeath fatal) throw fatal;
    }

    public synchronized List<ModAdapter> adapters() {
        return loaded.stream().map(LoadedAdapter::adapter).toList();
    }

    public Path getDirectory() {
        return directory;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeLoadedClassLoaders();
        loaded.clear();
    }

    private void closeLoadedClassLoaders() {
        for (int i = classLoaders.size() - 1; i >= 0; i--) {
            try {
                classLoaders.get(i).close();
            } catch (IOException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not close an adapter class loader", exception);
            }
        }
        classLoaders.clear();
        loaded.clear();
    }

    private record LoadedAdapter(ModAdapter adapter) { }
}
