package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * Extension point for trusted Paper-side implementations of existing mods.
 *
 * An adapter re-implements supported mod behavior using the Paper API. It does
 * not execute or convert the original Forge/Fabric bytecode.
 */
public interface ModAdapter {
    String getName();

    /** Mod IDs implemented by this adapter, e.g. "create" or "waystones". */
    Set<String> supportedMods();

    /**
     * Called once at server startup with only the detected mods this adapter
     * declares support for.
     */
    void onEnable(JavaPlugin plugin, List<ModDescriptor> supportedMods) throws Exception;

    default void onDisable() { }
}
