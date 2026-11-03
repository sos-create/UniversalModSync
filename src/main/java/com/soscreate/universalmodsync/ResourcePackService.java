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
        createDefaultAssets();
    }

    private void createDefaultAssets() throws IOException {
        writeIfMissing("assets/minecraft/models/item/iron_sword.json", """
                {\n  \"parent\": \"minecraft:item/handheld\",\n  \"textures\": {\"layer0\": \"minecraft:item/iron_sword\"},\n  \"overrides\": [{\"predicate\": {\"custom_model_data\": 1001}, \"model\": \"universalmodsync:item/ember_blade\"}]\n}\n                """);
        writeIfMissing("assets/minecraft/models/item/amethyst_shard.json", """
                {\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\"layer0\": \"minecraft:item/amethyst_shard\"},\n  \"overrides\": [{\"predicate\": {\"custom_model_data\": 1002}, \"model\": \"universalmodsync:item/healing_orb\"}]\n}\n                """);
        writeIfMissing("assets/minecraft/models/item/tripwire_hook.json", """
                {\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\"layer0\": \"minecraft:item/tripwire_hook\"},\n  \"overrides\": [{\"predicate\": {\"custom_model_data\": 1003}, \"model\": \"universalmodsync:item/dungeon_key\"}]\n}\n                """);
        writeIfMissing("assets/universalmodsync/models/item/ember_blade.json", """
                {
                  "parent": "minecraft:item/handheld",
                  "textures": {"layer0": "universalmodsync:item/ember_blade"}
                }
                """);
        writeIfMissing("assets/universalmodsync/models/item/healing_orb.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": {"layer0": "universalmodsync:item/healing_orb"}
                }
                """);
        writeIfMissing("assets/universalmodsync/models/item/dungeon_key.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": {"layer0": "universalmodsync:item/dungeon_key"}
                }
                """);
        createTexture("assets/universalmodsync/textures/item/ember_blade.png", 0xFF3210, 0xFFFF45, 0xA9A9A9, 1);
        createTexture("assets/universalmodsync/textures/item/healing_orb.png", 0x9C39FF, 0xF4C9FF, 0xFFFFFF, 2);
        createTexture("assets/universalmodsync/textures/item/dungeon_key.png", 0xA66B22, 0xFFE078, 0x5B3514, 3);
    }

    private void writeIfMissing(String relative, String contents) throws IOException {
        Path target = directory.resolve(relative).normalize();
        if (!target.startsWith(directory)) throw new IOException("Invalid resource path: " + relative);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) Files.writeString(target, contents);
    }

    private void createTexture(String relative, int primary, int highlight, int shadow, int style) throws IOException {
        Path target = directory.resolve(relative).normalize();
        if (Files.exists(target)) return;
        Files.createDirectories(target.getParent());
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(primary));
        if (style == 1) {
            graphics.fillPolygon(new int[]{7, 9, 11, 9, 8, 6}, new int[]{0, 5, 9, 11, 15, 8}, 6);
            graphics.setColor(new java.awt.Color(highlight)); graphics.drawLine(8, 1, 9, 8);
            graphics.setColor(new java.awt.Color(shadow)); graphics.fillRect(7, 10, 2, 5);
            graphics.fillRect(5, 11, 6, 2);
        } else if (style == 2) {
            graphics.fillOval(2, 2, 12, 12);
            graphics.setColor(new java.awt.Color(highlight)); graphics.fillOval(5, 3, 5, 5);
            graphics.setColor(new java.awt.Color(shadow)); graphics.drawOval(2, 2, 12, 12);
        } else {
            graphics.fillRect(6, 1, 4, 10); graphics.fillRect(8, 8, 7, 3);
            graphics.fillOval(1, 1, 7, 7); graphics.fillRect(9, 9, 2, 6);
            graphics.setColor(new java.awt.Color(highlight)); graphics.fillOval(2, 2, 4, 4);
            graphics.setColor(new java.awt.Color(shadow)); graphics.drawLine(8, 10, 14, 10);
        }
        graphics.dispose();
        javax.imageio.ImageIO.write(image, "PNG", target.toFile());
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
        } catch (IOException exception) {
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
