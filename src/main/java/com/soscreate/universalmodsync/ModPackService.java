package com.soscreate.universalmodsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class ModPackService {
    private final Path modsDirectory;
    private volatile List<ModEntry> entries = List.of();

    public ModPackService(Path modsDirectory) {
        this.modsDirectory = modsDirectory.toAbsolutePath().normalize();
    }

    public synchronized void refresh() throws IOException {
        List<ModEntry> found = new ArrayList<>();
        try (var paths = Files.list(modsDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).toList()) {
                if (Files.isSymbolicLink(path)) continue;
                String name = path.getFileName().toString();
                if (!name.matches("[A-Za-z0-9._+ -]+\\.jar")) continue;
                found.add(new ModEntry(name, Files.size(path), sha256(path)));
            }
        }
        entries = List.copyOf(found);
    }

    public List<ModEntry> getEntries() { return entries; }

    public Path resolveMod(String fileName) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._+ -]+\\.jar")) return null;
        Path candidate = modsDirectory.resolve(fileName).normalize();
        if (!candidate.getParent().equals(modsDirectory) || !Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) return null;
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

    public record ModEntry(String fileName, long size, String sha256) { }
}
