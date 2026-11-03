package com.soscreate.universalmodsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds a generic server resource pack from files supplied by installed adapters. */
public final class ResourcePackService {
    private static final String ARCHIVE_NAME = "resource-pack.zip";
    private final Path directory;
    private final Path archive;

    public ResourcePackService(Path pluginFolder) throws IOException {
        if (pluginFolder == null) throw new IllegalArgumentException("Plugin folder cannot be null");
        Path root = pluginFolder.toAbsolutePath().normalize();
        directory = root.resolve("resource-pack").normalize();
        archive = root.resolve(ARCHIVE_NAME).normalize();
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resource-pack path is not a real directory: " + directory);
        }

        Path metadata = directory.resolve("pack.mcmeta");
        if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.writeString(metadata, """
                        {
                          "pack": {
                            "pack_format": 46,
                            "description": "UniversalModSync compatibility resource pack"
                          }
                        }
                        """, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another startup task created it first.
            }
        }
        if (Files.isSymbolicLink(metadata) || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resource-pack metadata is not a regular file: " + metadata);
        }
    }

    public synchronized PackInfo rebuild() throws IOException {
        Path temporary = archive.resolveSibling(ARCHIVE_NAME + ".tmp");
        Files.deleteIfExists(temporary);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                 var paths = Files.walk(directory)) {
                for (Path file : paths
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path ->
                                directory.relativize(path).toString().replace(java.io.File.separatorChar, '/')))
                        .toList()) {
                    Path relative = directory.relativize(file).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")) continue;
                    String name = relative.toString().replace(java.io.File.separatorChar, '/');
                    if (name.isBlank() || name.startsWith("/") || name.contains("../")) continue;

                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream input = Files.newInputStream(file)) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }

            try {
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            return getInfo();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized PackInfo getInfo() throws IOException {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(archive)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            long size = 0;
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    size += count;
                }
            }
            byte[] hash = digest.digest();
            return new PackInfo(archive, hash, HexFormat.of().formatHex(hash), size);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
    }

    public Path getArchive() { return archive; }
    public Path getDirectory() { return directory; }

    public record PackInfo(Path path, byte[] hash, String sha1, long size) {
        public PackInfo {
            hash = hash == null ? new byte[0] : hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }
}
