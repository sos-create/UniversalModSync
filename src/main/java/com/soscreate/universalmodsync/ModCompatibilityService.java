package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspects archive metadata only. Original Forge/Fabric/Quilt bytecode is never
 * loaded, linked, or executed by this service.
 */
public final class ModCompatibilityService {
    private static final int MAX_METADATA_BYTES = 1_048_576;
    private static final Pattern TOML_TABLE = Pattern.compile("^\\s*\\[\\[([^]]+)]]\\s*(?:#.*)?$");
    private static final Pattern TOML_STRING = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'([^'\\r\\n]*)')\\s*(?:#.*)?$");
    private static final Path DEFAULT_DIRECTORY = Path.of("mods");

    private final Path modsDirectory;
    private volatile List<ModDescriptor> lastMods = List.of();

    public ModCompatibilityService(JavaPlugin plugin) throws IOException {
        this(plugin.getDataFolder().toPath().resolve(DEFAULT_DIRECTORY));
    }

    public ModCompatibilityService(JavaPlugin plugin, Path directory) throws IOException {
        this(directory);
    }

    /** Constructor used by tests and standalone metadata inspection. */
    public ModCompatibilityService(Path directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("Mods directory cannot be null");
        modsDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(modsDirectory);
        if (!Files.isDirectory(modsDirectory)) {
            throw new IOException("Mods path is not a directory: " + modsDirectory);
        }
    }

    public List<ModDescriptor> scan() throws IOException {
        List<ModDescriptor> result = new ArrayList<>();
        try (var files = Files.list(modsDirectory)) {
            for (Path file : files
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .filter(ModCompatibilityService::isSupportedArchive)
                    .sorted(Comparator.comparing(
                            (Path path) -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList()) {
                result.addAll(inspect(file));
            }
        }
        lastMods = List.copyOf(result);
        return lastMods;
    }

    private List<ModDescriptor> inspect(Path file) {
        String name = file.getFileName().toString();
        String fallbackId = stripArchiveExtension(name);
        try (JarFile archive = new JarFile(file.toFile(), false)) {
            var forge = archive.getJarEntry("META-INF/mods.toml");
            if (forge != null) {
                String toml = readMetadata(archive.getInputStream(forge));
                List<String> modTables = findTomlArrayTables(toml, "mods");
                List<ModDescriptor> found = new ArrayList<>();
                for (String block : modTables) {
                    String id = tomlString(block, "modId");
                    String version = tomlString(block, "version");
                    if (id == null || id.isBlank()) id = fallbackId;
                    if (version == null || version.isBlank()) version = "unknown";
                    found.add(new ModDescriptor(name, "Forge", id, version,
                            forgeDependencies(toml, id), "unknown",
                            id.equals(fallbackId) && tomlString(block, "modId") == null
                                    ? "METADATA_INCOMPLETE" : "ADAPTER_REQUIRED"));
                }
                if (!found.isEmpty()) return List.copyOf(found);
                return List.of(new ModDescriptor(name, "Forge", fallbackId, "unknown",
                        List.of(), "unknown", "METADATA_INCOMPLETE"));
            }

            var fabric = archive.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                Map<String, Object> json = MetadataJsonParser.parseObject(
                        readMetadata(archive.getInputStream(fabric)));
                String id = jsonString(json.get("id"), fallbackId);
                String version = jsonString(json.get("version"), "unknown");
                String environment = jsonString(json.get("environment"), "*");
                List<String> dependencies = objectKeys(json.get("depends"));
                String compatibility = environment.equalsIgnoreCase("client")
                        ? "CLIENT_ONLY_NOT_RUNNABLE_ON_PAPER" : "ADAPTER_REQUIRED";
                return List.of(new ModDescriptor(name, "Fabric", id, version,
                        dependencies, environment, compatibility));
            }

            var quilt = archive.getJarEntry("quilt.mod.json");
            if (quilt != null) {
                Map<String, Object> root = MetadataJsonParser.parseObject(
                        readMetadata(archive.getInputStream(quilt)));
                Map<String, Object> loader = objectValue(root.get("quilt_loader"));
                String id = jsonString(loader.get("id"), fallbackId);
                String version = jsonString(loader.get("version"), "unknown");
                List<String> dependencies = quiltDependencies(loader.get("depends"));
                return List.of(new ModDescriptor(name, "Quilt", id, version,
                        dependencies, "*", "ADAPTER_REQUIRED"));
            }

            return List.of(new ModDescriptor(name, "Unknown", fallbackId, "unknown",
                    List.of(), "unknown", "UNRECOGNIZED_ARCHIVE"));
        } catch (Exception exception) {
            return List.of(new ModDescriptor(name, "Unknown", fallbackId, "unknown",
                    List.of(), "unknown", "INVALID_OR_UNREADABLE_ARCHIVE"));
        }
    }

    private static boolean isSupportedArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static String readMetadata(InputStream input) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new IOException("Metadata entry exceeds " + MAX_METADATA_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static List<String> findTomlArrayTables(String toml, String tableName) {
        List<String> result = new ArrayList<>();
        StringBuilder current = null;
        for (String line : toml.split("\\R", -1)) {
            Matcher header = TOML_TABLE.matcher(line);
            if (header.matches()) {
                if (current != null) result.add(current.toString());
                current = header.group(1).trim().equals(tableName) ? new StringBuilder() : null;
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) result.add(current.toString());
        return result;
    }

    private static String tomlString(String block, String key) {
        for (String line : block.split("\\R")) {
            Matcher matcher = TOML_STRING.matcher(line);
            if (matcher.matches() && matcher.group(1).equals(key)) {
                String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                return matcher.group(2) != null ? unescapeTomlBasicString(value) : value;
            }
        }
        return null;
    }

    private static String unescapeTomlBasicString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case 'b' -> result.append('\b');
                case 't' -> result.append('\t');
                case 'n' -> result.append('\n');
                case 'f' -> result.append('\f');
                case 'r' -> result.append('\r');
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case 'u' -> {
                    if (index + 4 < value.length()) {
                        try {
                            result.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                            index += 4;
                        } catch (NumberFormatException exception) {
                            result.append('u');
                        }
                    } else {
                        result.append('u');
                    }
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    private static List<String> forgeDependencies(String toml, String modId) {
        String target = "dependencies." + modId;
        Set<String> dependencies = new LinkedHashSet<>();
        StringBuilder current = null;
        for (String line : toml.split("\\R", -1)) {
            Matcher header = TOML_TABLE.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    String dependency = tomlString(current.toString(), "modId");
                    if (dependency != null && !dependency.isBlank()) dependencies.add(dependency);
                }
                current = header.group(1).trim().equals(target) ? new StringBuilder() : null;
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            String dependency = tomlString(current.toString(), "modId");
            if (dependency != null && !dependency.isBlank()) dependencies.add(dependency);
        }
        return List.copyOf(dependencies);
    }

    private static List<String> objectKeys(Object value) {
        if (!(value instanceof Map<?, ?> map)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (key instanceof String string) result.add(string);
        }
        return List.copyOf(result);
    }

    private static List<String> quiltDependencies(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) {
                Object id = map.get("id");
                if (id instanceof String string && !string.isBlank()) result.add(string);
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String jsonString(Object value, String fallback) {
        return value instanceof String string && !string.isBlank() ? string : fallback;
    }

    private static String stripArchiveExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    public List<ModDescriptor> getLastMods() { return lastMods; }
    public Path getModsDirectory() { return modsDirectory; }
}
