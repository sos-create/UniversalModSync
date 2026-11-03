package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers mod archives and reports whether a Paper-side adapter is available.
 * It deliberately does not attempt to load Forge/Fabric bytecode inside Paper.
 */
public final class ModCompatibilityService {
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']?([a-zA-Z0-9_.-]+)");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']?([^\"'\\r\\n]+)");
    private final Path modsDirectory;
    private List<ModReport> lastReports = List.of();

    public ModCompatibilityService(JavaPlugin plugin) throws IOException {
        modsDirectory = plugin.getDataFolder().toPath().resolve("mods").toAbsolutePath().normalize();
        Files.createDirectories(modsDirectory);
    }

    public List<ModReport> scan() {
        List<ModReport> reports = new ArrayList<>();
        try (var files = Files.list(modsDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> reports.add(inspect(path)));
        } catch (IOException ignored) {
            // The directory is created during initialization.
        }
        lastReports = List.copyOf(reports);
        return lastReports;
    }

    private ModReport inspect(Path path) {
        String fileName = path.getFileName().toString();
        try (JarFile jar = new JarFile(path.toFile())) {
            var forgeMetadata = jar.getJarEntry("META-INF/mods.toml");
            if (forgeMetadata != null) {
                String metadata;
                try (var input = jar.getInputStream(forgeMetadata)) {
                    metadata = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                Matcher id = MOD_ID.matcher(metadata);
                Matcher version = VERSION.matcher(metadata);
                String modId = id.find() ? id.group(1) : stripJar(fileName);
                String modVersion = version.find() ? version.group(1).trim() : "unknown";
                return new ModReport(fileName, modId, modVersion, "FORGE_METADATA_FOUND_ADAPTER_REQUIRED");
            }
            if (jar.getJarEntry("fabric.mod.json") != null) {
                return new ModReport(fileName, stripJar(fileName), "unknown", "FABRIC_METADATA_FOUND_ADAPTER_REQUIRED");
            }
            return new ModReport(fileName, stripJar(fileName), "unknown", "UNRECOGNIZED_JAR");
        } catch (IOException exception) {
            return new ModReport(fileName, stripJar(fileName), "unknown", "INVALID_OR_UNREADABLE_JAR");
        }
    }

    private static String stripJar(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    public List<ModReport> getLastReports() { return lastReports; }
    public int getAdapterCount() { return 0; }
    public Path getModsDirectory() { return modsDirectory; }

    public record ModReport(String fileName, String modId, String version, String status) { }
}
