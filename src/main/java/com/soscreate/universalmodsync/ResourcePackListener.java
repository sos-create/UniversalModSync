package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ResourcePackListener implements Listener {
    private final ResourcePackService resourcePacks;
    private final boolean enabled;
    private final String publicUrl;
    private final String prompt;
    private final boolean required;
    private final Logger logger;

    public ResourcePackListener(ResourcePackService resourcePacks, boolean enabled, String publicUrl,
                                String prompt, boolean required, Logger logger) {
        this.resourcePacks = resourcePacks;
        this.enabled = enabled;
        this.publicUrl = validateUrl(publicUrl, logger);
        this.prompt = prompt == null || prompt.isBlank()
                ? "This server provides custom assets through a server resource pack."
                : prompt;
        this.required = required;
        this.logger = logger;
    }

    private static String validateUrl(String value, Logger logger) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || scheme == null
                    || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                throw new IllegalArgumentException("URL must be an absolute HTTP(S) URL without user information");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            if (logger != null) logger.warning("Resource-pack public URL is invalid; automatic delivery is disabled: "
                    + exception.getMessage());
            return "";
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled || publicUrl.isBlank() || resourcePacks == null) return;
        Player player = event.getPlayer();
        try {
            ResourcePackService.PackInfo pack = resourcePacks.getInfo();
            if (pack == null) {
                logger.warning("Resource-pack archive is missing; run /modsync reload after adding pack assets.");
                return;
            }
            player.setResourcePack(publicUrl, pack.hash(),
                    ChatColor.translateAlternateColorCodes('&', prompt), required);
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING,
                    "Could not send the resource pack to player " + player.getName() + ".", exception);
        }
    }
}
