package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * Extension point for trusted Paper-side implementations of existing mods.
 *
 * An adapter re-implements supported mod behavior using the Paper API. It does
 * not execute or convert the original Forge/Fabric bytecode.
 *
 * Adapter authors only need to list the exact archive file names they target,
 * for example "ExampleMod-1.2.0.jar". Matching is case-insensitive; wildcards
 * and paths are deliberately not accepted.
 */
public interface ModAdapter {
    String getName();

    /**
     * Exact Forge/Fabric archive file names implemented by this adapter.
     * Example: Set.of("ExampleMod-1.2.0.jar").
     *
     * A mod archive is normally a .jar file (JAR uses the ZIP container format).
     */
    Set<String> supportedModFiles();

    /**
     * Called once at server startup with only the detected archives whose
     * complete file names match supportedModFiles().
     */
    void onEnable(JavaPlugin plugin, List<ModDescriptor> supportedMods) throws Exception;

    default void onDisable() { }

    /** Exact archive filename comparison, ignoring letter case; no patterns or paths. */
    static boolean targetsFile(Set<String> supportedFiles, String detectedFileName) {
        if (!isArchiveFileName(detectedFileName) || supportedFiles == null || supportedFiles.isEmpty()) {
            return false;
        }
        for (String configured : supportedFiles) {
            if (isArchiveFileName(configured)
                    && configured.trim().equalsIgnoreCase(detectedFileName.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArchiveFileName(String value) {
        if (value == null || value.isBlank()) return false;
        String name = value.trim();
        if (name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf(92) >= 0
                || name.indexOf('*') >= 0 || name.indexOf('?') >= 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

}
