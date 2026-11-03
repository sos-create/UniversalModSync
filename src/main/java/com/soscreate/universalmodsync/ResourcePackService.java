package com.soscreate.universalmodsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ResourcePackService {
    private final Path directory;
    private final Path archive;

    public ResourcePackService(Path pluginFolder) throws IOException {
        this.directory = pluginFolder.resolve("resource-pack").toAbsolutePath().normalize();
        this.archive = pluginFolder.resolve("resource-pack.zip").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path metadata = directory.resolve("pack.mcmeta");
        if (!Files.exists(metadata)) {
            Files.writeString(metadata, """
                    {
                      "pack": {
                        "pack_format": 46,
                        "description": "UniversalModSync server resource pack"
                      }
                    }
                    """);
        }
    }

    public synchronized PackInfo rebuild() throws IOException {
        Path temporary = archive.resolveSibling("resource-pack.zip.tmp");
        Files.deleteIfExists(temporary);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .filter(path -> !Files.isSymbolicLink(path))
                        .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                        .toList()) {
                    String name = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    if (name.isBlank() || name.startsWith("/") || name.contains("../")) continue;
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream input = Files.newInputStream(file)) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        try {
            Files.move(temporary, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return getInfo();
    }

    public PackInfo getInfo() throws IOException {
        if (!Files.isRegularFile(archive)) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            byte[] hash = digest.digest();
            return new PackInfo(archive, hash, HexFormat.of().formatHex(hash), Files.size(archive));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
    }

    public Path getArchive() { return archive; }
    public Path getDirectory() { return directory; }

    public record PackInfo(Path path, byte[] hash, String sha1, long size) { }
}
