package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.util.logging.Logger;

public final class ResourcePackListener implements Listener {
    private final ResourcePackService resourcePacks;
    private final String publicUrl;
    private final String prompt;
    private final boolean required;
    private final Logger logger;

    public ResourcePackListener(ResourcePackService resourcePacks, String publicUrl,
                                String prompt, boolean required, Logger logger) {
        this.resourcePacks = resourcePacks;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim();
        this.prompt = prompt == null || prompt.isBlank()
                ? "This server uses custom items, creatures and dungeon visuals."
                : prompt;
        this.required = required;
        this.logger = logger;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (publicUrl.isBlank()) return;
        Player player = event.getPlayer();
        try {
            ResourcePackService.PackInfo pack = resourcePacks.getInfo();
            if (pack == null) {
                logger.warning("Resource pack archive is missing; run /modsync reload.");
                return;
            }
            player.setResourcePack(publicUrl, pack.hash(), ChatColor.translateAlternateColorCodes('&', prompt), required);
        } catch (IOException exception) {
            logger.warning("Could not prepare resource pack for " + player.getName() + ": " + exception.getMessage());
        }
    }
}
