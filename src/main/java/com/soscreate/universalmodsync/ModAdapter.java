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

    /** Exact filename comparison, ignoring letter case; rejects path-like entries. */
    static boolean targetsFile(Set<String> supportedFiles, String detectedFileName) {
        if (supportedFiles == null || supportedFiles.isEmpty()
                || detectedFileName == null || detectedFileName.isBlank()) {
            return false;
        }
        String detected = detectedFileName.trim();
        for (String configured : supportedFiles) {
            if (configured == null) continue;
            String candidate = configured.trim();
            if (candidate.isEmpty() || candidate.contains("/") || candidate.indexOf(92) >= 0) continue;
            if (candidate.equalsIgnoreCase(detected)) return true;
        }
        return false;
    }
}
