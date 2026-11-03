package com.soscreate.universalmodsync;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class AdventureContentService implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey itemId;
    private final Random random = new Random();

    public AdventureContentService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemId = new NamespacedKey(plugin, "adventure_item");
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("dungeon")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used by a player.");
                return true;
            }
            createDungeon(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("boss")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used by a player.");
                return true;
            }
            spawnBoss(player.getLocation(), player);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            Player target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player is not online.");
                return true;
            }
            ItemStack item = createItem(args[2]);
            if (item == null) {
                sender.sendMessage(ChatColor.RED + "Unknown item. Use ember_blade, healing_orb or dungeon_key.");
                return true;
            }
            target.getInventory().addItem(item).values().forEach(leftover ->
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            sender.sendMessage(ChatColor.GREEN + "Given " + args[2] + " to " + target.getName() + ".");
            return true;
        }
        return false;
    }

    public ItemStack createItem(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        Material material;
        String name;
        String lore;
        int model;
        switch (normalized) {
            case "ember_blade" -> {
                material = Material.IRON_SWORD;
                name = ChatColor.GOLD + "Ember Blade";
                lore = ChatColor.GRAY + "Right-click to strike and ignite a target.";
                model = 1001;
            }
            case "healing_orb" -> {
                material = Material.AMETHYST_SHARD;
                name = ChatColor.LIGHT_PURPLE + "Healing Orb";
                lore = ChatColor.GRAY + "Right-click to restore 4 hearts.";
                model = 1002;
            }
            case "dungeon_key" -> {
                material = Material.TRIPWIRE_HOOK;
                name = ChatColor.YELLOW + "Ancient Dungeon Key";
                lore = ChatColor.GRAY + "A relic from forgotten halls.";
                model = 1003;
            }
            default -> { return null; }
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        meta.setCustomModelData(model);
        meta.getPersistentDataContainer().set(itemId, PersistentDataType.STRING, normalized);
        stack.setItemMeta(meta);
        return stack;
    }

    private String getItemId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(itemId, PersistentDataType.STRING);
    }

    @EventHandler
    public void onUseItem(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        String id = getItemId(hand);
        if (id == null) return;

        if (id.equals("ember_blade")) {
            event.setCancelled(true);
            if (player.hasCooldown(Material.IRON_SWORD)) return;
            player.setCooldown(Material.IRON_SWORD, 12);
            RayTraceResult hit = player.getWorld().rayTraceEntities(
                    player.getEyeLocation(), player.getEyeLocation().getDirection(),
                    5.0, 0.8, entity -> entity instanceof LivingEntity && entity != player);
            if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
                target.damage(9.0, player);
                target.setFireTicks(Math.max(target.getFireTicks(), 80));
                player.getWorld().strikeLightningEffect(target.getLocation());
                player.sendMessage(ChatColor.GOLD + "The Ember Blade burns its target!");
            } else {
                player.sendMessage(ChatColor.GRAY + "No target in range.");
            }
        } else if (id.equals("healing_orb")) {
            event.setCancelled(true);
            if (player.getHealth() >= player.getAttribute(Attribute.MAX_HEALTH).getValue()) {
                player.sendMessage(ChatColor.GRAY + "Your health is already full.");
                return;
            }
            double max = player.getAttribute(Attribute.MAX_HEALTH).getValue();
            player.setHealth(Math.min(max, player.getHealth() + 8.0));
            player.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                    player.getLocation().add(0, 1, 0), 8, 0.45, 0.5, 0.45, 0.0);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
            consumeOne(player, hand);
        } else if (id.equals("dungeon_key")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.YELLOW + "The ancient key hums. Search the dungeon for a sealed treasure chest.");
        }
    }

    private void consumeOne(Player player, ItemStack hand) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    public void createDungeon(Player player) {
        Location origin = player.getLocation().getBlock().getLocation();
        int cx = origin.getBlockX();
        int cy = Math.max(player.getWorld().getMinHeight() + 5, origin.getBlockY() - 1);
        int cz = origin.getBlockZ();
        var world = player.getWorld();

        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                for (int y = 0; y <= 5; y++) {
                    Block block = world.getBlockAt(cx + x, cy + y, cz + z);
                    boolean boundary = Math.abs(x) == 7 || Math.abs(z) == 7 || y == 0 || y == 5;
                    if (boundary) {
                        Material material = y == 0 ? Material.DEEPSLATE_BRICKS
                                : (random.nextInt(5) == 0 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS);
                        block.setType(material, false);
                    } else {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }

        for (int[] pos : new int[][]{{-5, -5}, {-5, 5}, {5, -5}, {5, 5}}) {
            Block block = world.getBlockAt(cx + pos[0], cy + 1, cz + pos[1]);
            block.setType(Material.CHEST, false);
            if (block.getState() instanceof Chest chest) {
                chest.getBlockInventory().addItem(createItem(random.nextBoolean() ? "healing_orb" : "dungeon_key"));
                if (random.nextBoolean()) chest.getBlockInventory().addItem(new ItemStack(Material.GOLD_INGOT, 2 + random.nextInt(5)));
                chest.update(true, false);
            }
        }

        world.getBlockAt(cx, cy + 1, cz - 6).setType(Material.SOUL_LANTERN, false);
        world.getBlockAt(cx, cy + 1, cz + 6).setType(Material.SOUL_LANTERN, false);
        player.teleport(new Location(world, cx + 0.5, cy + 1, cz + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch()));
        spawnBoss(new Location(world, cx + 0.5, cy + 1, cz + 0.5), player);
        player.sendMessage(ChatColor.DARK_PURPLE + "An ancient dungeon has emerged around you. Defeat its guardian and claim the treasure!");
    }

    private void spawnBoss(Location location, Player target) {
        WitherSkeleton boss = location.getWorld().spawn(location, WitherSkeleton.class, entity -> {
            entity.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE ASHEN WARDEN");
            entity.setCustomNameVisible(true);
            entity.setPersistent(true);
            entity.setRemoveWhenFarAway(false);
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100.0);
            entity.setHealth(100.0);
            entity.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(12.0);
            entity.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.30);
            entity.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
            entity.getEquipment().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            entity.getEquipment().setItemInMainHandDropChance(0.0f);
            entity.setTarget(target);
        });
        boss.setGlowing(true);
        location.getWorld().strikeLightningEffect(location);
        target.sendTitle(ChatColor.DARK_RED + "THE ASHEN WARDEN", ChatColor.GRAY + "Dungeon guardian", 10, 50, 15);
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof WitherSkeleton skeleton)) return;
        if (skeleton.getCustomName() == null || !ChatColor.stripColor(skeleton.getCustomName()).equals("THE ASHEN WARDEN")) return;
        event.getDrops().clear();
        event.getDrops().add(createItem("ember_blade"));
        event.getDrops().add(createItem("healing_orb"));
        event.setDroppedExp(80);
        Player killer = skeleton.getKiller();
        if (killer != null) killer.sendMessage(ChatColor.GOLD + "The Ashen Warden has fallen. The Ember Blade is yours!");
    }
}
