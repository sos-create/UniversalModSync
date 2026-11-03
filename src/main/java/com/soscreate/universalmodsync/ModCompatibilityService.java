package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads mod metadata only. Mod bytecode is never loaded or executed. */
public final class ModCompatibilityService {
    private static final Pattern TOML_MOD_BLOCK = Pattern.compile("(?ms)^\\s*\\[\\[mods\\]\\](.*?)(?=^\\s*\\[\\[|\\z)");
    private static final Pattern TOML_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([^\"'\\r\\n]+)[\"']");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"'\\r\\n]+)[\"']");
    private static final Pattern FABRIC_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_ENV = Pattern.compile("\"environment\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEPENDENCY_KEY = Pattern.compile("\"([^\"]+)\"\\s*:");
    private final Path modsDirectory;
    private List<ModDescriptor> lastMods = List.of();

    public ModCompatibilityService(JavaPlugin plugin) throws IOException {
        this(plugin.getDataFolder().toPath().resolve("mods"));
    }

    public ModCompatibilityService(JavaPlugin plugin, Path directory) throws IOException {
        this(directory);
    }

    /** Constructor used by tests and standalone metadata inspection. */
    public ModCompatibilityService(Path directory) throws IOException {
        modsDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(modsDirectory);
        if (!Files.isDirectory(modsDirectory)) throw new IOException("Mods path is not a directory: " + modsDirectory);
    }

    public List<ModDescriptor> scan() throws IOException {
        List<ModDescriptor> result = new ArrayList<>();
        try (var files = Files.list(modsDirectory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList()) {
                result.addAll(inspect(file));
            }
        }
        lastMods = List.copyOf(result);
        return lastMods;
    }

    private List<ModDescriptor> inspect(Path file) {
        String name = file.getFileName().toString();
        try (JarFile jar = new JarFile(file.toFile(), false)) {
            var forge = jar.getJarEntry("META-INF/mods.toml");
            if (forge != null) {
                String toml;
                try (var in = jar.getInputStream(forge)) { toml = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                List<ModDescriptor> found = new ArrayList<>();
                Matcher blocks = TOML_MOD_BLOCK.matcher(toml);
                while (blocks.find()) {
                    String block = blocks.group(1);
                    String id = first(TOML_ID, block, stripJar(name));
                    String version = first(TOML_VERSION, block, "unknown");
                    found.add(new ModDescriptor(name, "Forge", id, version,
                            forgeDependencies(toml, id), "unknown", "ADAPTER_REQUIRED"));
                }
                if (!found.isEmpty()) return found;
                return List.of(new ModDescriptor(name, "Forge", stripJar(name), "unknown",
                        List.of(), "unknown", "METADATA_INCOMPLETE"));
            }
            var fabric = jar.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                String json;
                try (var in = jar.getInputStream(fabric)) { json = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                String id = first(FABRIC_ID, json, stripJar(name));
                String version = first(FABRIC_VERSION, json, "unknown");
                String environment = first(FABRIC_ENV, json, "*");
                List<String> dependencies = fabricDependencies(json);
                String compatibility = environment.equalsIgnoreCase("client")
                        ? "CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER" : "ADAPTER_REQUIRED";
                return List.of(new ModDescriptor(name, "Fabric", id, version, dependencies, environment, compatibility));
            }
            return List.of(new ModDescriptor(name, "Unknown", stripJar(name), "unknown",
                    List.of(), "unknown", "UNRECOGNIZED_JAR"));
        } catch (Exception exception) {
            return List.of(new ModDescriptor(name, "Unknown", stripJar(name), "unknown",
                    List.of(), "unknown", "INVALID_OR_UNREADABLE_JAR"));
        }
    }

    private static List<String> forgeDependencies(String toml, String modId) {
        Pattern section = Pattern.compile("(?ms)^\\s*\\[\\[dependencies\\." + Pattern.quote(modId)
                + "\\]\\](.*?)(?=^\\s*\\[\\[|\\z)");
        Matcher matcher = section.matcher(toml);
        Set<String> dependencies = new LinkedHashSet<>();
        while (matcher.find()) {
            Matcher id = TOML_ID.matcher(matcher.group(1));
            if (id.find()) dependencies.add(id.group(1));
        }
        return List.copyOf(dependencies);
    }

    private static List<String> fabricDependencies(String json) {
        Pattern section = Pattern.compile("(?s)\"depends\"\\s*:\\s*\\{(.*?)\\}");
        Matcher sectionMatcher = section.matcher(json);
        if (!sectionMatcher.find()) return List.of();
        Matcher keys = DEPENDENCY_KEY.matcher(sectionMatcher.group(1));
        List<String> result = new ArrayList<>();
        while (keys.find()) result.add(keys.group(1));
        return List.copyOf(result);
    }

    private static String first(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private static String stripJar(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }

    public List<ModDescriptor> getLastMods() { return lastMods; }
    public Path getModsDirectory() { return modsDirectory; }
}
