package com.soscreate.universalmodsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModPackService {
    private static final Pattern TOML_SECTION = Pattern.compile("(?m)^\\[\\[([^]]+)]]\\s*$");
    private static final Pattern TOML_VALUE = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern QUOTED = Pattern.compile("^\\"(.*)\\"$");

    private final Path modsDirectory;
    private final Logger logger;
    private final AtomicLong revision = new AtomicLong(1);
    private volatile List<ModEntry> entries = List.of();
    private volatile String manifestFingerprint = "";

    public ModPackService(Path modsDirectory, Logger logger) {
        this.modsDirectory = modsDirectory.toAbsolutePath().normalize();
        this.logger = logger;
    }

    public synchronized RefreshResult refresh() throws IOException {
        List<ModEntry> found = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        try (var paths = Files.list(modsDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).toList()) {
                if (Files.isSymbolicLink(path)) continue;
                String name = path.getFileName().toString();
                if (!name.matches("[A-Za-z0-9._+ -]+\\.jar")) {
                    logger.warning("Skipping mod with unsupported filename: " + name);
                    continue;
                }
                try {
                    long size = Files.size(path);
                    FileTime modified = Files.getLastModifiedTime(path);
                    ModEntry entry = inspect(path, name, size, modified);
                    found.add(entry);
                    fingerprints.add(name + ":" + size + ":" + modified.toMillis() + ":" + entry.sha256());
                } catch (Exception exception) {
                    logger.warning("Skipping invalid or incomplete JAR " + name + ": " + exception.getMessage());
                }
            }
        }

        String fingerprint = String.join("|", fingerprints);
        boolean changed = !Objects.equals(fingerprint, manifestFingerprint);
        if (changed) {
            entries = List.copyOf(found);
            manifestFingerprint = fingerprint;
            revision.incrementAndGet();
        }
        return new RefreshResult(changed, entries.size(), revision.get());
    }

    private ModEntry inspect(Path path, String fileName, long size, FileTime modified) throws IOException {
        String hash = sha256(path);
        List<ModInfo> mods = new ArrayList<>();
        List<ModDependency> dependencies = new ArrayList<>();
        String loader = "unknown";
        try (ZipFile jar = new ZipFile(path.toFile())) {
            ZipEntry forgeMetadata = jar.getEntry("META-INF/mods.toml");
            if (forgeMetadata != null) {
                loader = "forge";
                String toml;
                try (var input = jar.getInputStream(forgeMetadata)) {
                    toml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                parseForgeToml(toml, mods, dependencies);
            } else if (jar.getEntry("mcmod.info") != null) {
                loader = "forge-legacy";
            } else {
                logger.warning("JAR does not contain META-INF/mods.toml or mcmod.info (not identified as Forge): " + fileName);
            }
        }
        if (mods.isEmpty()) {
            String fallback = fileName.substring(0, fileName.length() - 4);
            mods.add(new ModInfo(fallback, fallback, "unknown", ""));
        }
        return new ModEntry(fileName, size, hash, loader, List.copyOf(mods),
                List.copyOf(dependencies), modified.toMillis());
    }

    private static void parseForgeToml(String toml, List<ModInfo> mods, List<ModDependency> dependencies) {
        Matcher sections = TOML_SECTION.matcher(toml);
        List<Section> parsed = new ArrayList<>();
        int start = 0;
        String current = null;
        while (sections.find()) {
            if (current != null) parsed.add(new Section(current, toml.substring(start, sections.start())));
            current = sections.group(1).trim();
            start = sections.end();
        }
        if (current != null) parsed.add(new Section(current, toml.substring(start)));

        for (Section section : parsed) {
            Map<String, String> values = parseValues(section.body());
            if (section.name().equals("mods")) {
                String id = unquote(values.getOrDefault("modId", ""));
                if (!id.isBlank()) {
                    mods.add(new ModInfo(id,
                            unquote(values.getOrDefault("displayName", id)),
                            unquote(values.getOrDefault("version", "unknown")),
                            unquote(values.getOrDefault("description", ""))));
                }
            } else if (section.name().startsWith("dependencies.")) {
                String owner = section.name().substring("dependencies.".length());
                String target = unquote(values.getOrDefault("modId", ""));
                if (!target.isBlank()) {
                    dependencies.add(new ModDependency(owner, target,
                            unquote(values.getOrDefault("versionRange", "")),
                            Boolean.parseBoolean(values.getOrDefault("mandatory", "true")),
                            unquote(values.getOrDefault("side", "BOTH"))));
                }
            }
        }
    }

    private static Map<String, String> parseValues(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = TOML_VALUE.matcher(body);
        while (matcher.find()) values.put(matcher.group(1), matcher.group(2).trim());
        return values;
    }

    private static String unquote(String value) {
        if (value == null) return "";
        Matcher matcher = QUOTED.matcher(value);
        return matcher.matches() ? matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"") : value;
    }

    public List<ModEntry> getEntries() { return entries; }
    public long getRevision() { return revision.get(); }

    public Path resolveMod(String fileName) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._+ -]+\\.jar")) return null;
        Path candidate = modsDirectory.resolve(fileName).normalize();
        if (!candidate.getParent().equals(modsDirectory) || !Files.isRegularFile(candidate)
                || Files.isSymbolicLink(candidate)) return null;
        return candidate;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Section(String name, String body) { }
    public record ModInfo(String modId, String displayName, String version, String description) { }
    public record ModDependency(String ownerModId, String modId, String versionRange,
                                boolean mandatory, String side) { }
    public record ModEntry(String fileName, long size, String sha256, String loader,
                           List<ModInfo> mods, List<ModDependency> dependencies, long lastModified) { }
    public record RefreshResult(boolean changed, int modCount, long revision) { }
}
