package me.siwannie.hallowsend.listeners;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.MessageManager;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.game.GamePhase;
import me.siwannie.hallowsend.game.area.DefinedArea;
import me.siwannie.hallowsend.modules.loot.DeathChestManager;
import me.siwannie.hallowsend.modules.loot.LootManager;
import me.siwannie.hallowsend.modules.ritual.RitualArtifact;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class WorldInteractionListener implements Listener {

    private final HallowsEnd plugin;
    private final GameManager gameManager;
    private final RitualManager ritualManager;
    private final LootManager lootManager;
    private final MessageManager messageManager;
    private final NamespacedKey mobIdKey;

    private final Map<UUID, Map<RitualArtifact, Long>> artifactCooldowns = new HashMap<>();
    private final Random random = new Random();

    public WorldInteractionListener(HallowsEnd plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
        this.ritualManager = plugin.getRitualManager();
        this.lootManager = plugin.getLootManager();
        this.messageManager = plugin.getMessageManager();
        this.mobIdKey = new NamespacedKey(plugin, "mob_id");
    }

    @EventHandler
    public void handleArtifactPickupEvent(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        Optional<RitualArtifact> artifactOpt = ritualManager.getArtifactFromItemStack(item);

        if (artifactOpt.isPresent()) {
            RitualArtifact artifact = artifactOpt.get();
            ritualManager.handleArtifactPickup(player, artifact);
        }
    }

    @EventHandler
    public void onPlayerRightClick(PlayerInteractEvent event) {
        if (!gameManager.isGameRunning()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta()) return;

        NamespacedKey potionKey = plugin.getCustomItemManager().getPotionEffectsKey();
        if (item.getItemMeta().getPersistentDataContainer().has(potionKey, PersistentDataType.STRING)) {
            handlePotionUse(event, player, item);
            return;
        }

        ritualManager.getArtifactFromItemStack(item).ifPresent(artifact ->
                handleArtifactUse(event, player, artifact)
        );
    }

    private void handlePotionUse(PlayerInteractEvent event, Player player, ItemStack item) {
        event.setCancelled(true);
        NamespacedKey potionKey = plugin.getCustomItemManager().getPotionEffectsKey();
        String effectsString = item.getItemMeta().getPersistentDataContainer().get(potionKey, PersistentDataType.STRING);
        if (effectsString == null) return;

        for (String effectData : effectsString.split(";")) {
            try {
                String[] parts = effectData.split(":");
                PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase());
                if (type == null) continue;

                int durationInSeconds = Integer.parseInt(parts[1]);
                int amplifier = Integer.parseInt(parts[2]);
                int durationInTicks = type.isInstant() ? 1 : durationInSeconds * 20;

                player.addPotionEffect(new PotionEffect(type, durationInTicks, amplifier));
            } catch (Exception e) {
                plugin.getLogger().warning("Could not apply invalid potion effect from custom item: " + effectData);
            }
        }
        item.setAmount(item.getAmount() - 1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 1.0f);
    }

    private void handleArtifactUse(PlayerInteractEvent event, Player player, RitualArtifact artifact) {
        switch (artifact) {
            case CURSED_LEDGER, WEEPING_BELL -> {
                long currentTime = System.currentTimeMillis();
                Map<RitualArtifact, Long> playerCooldowns = artifactCooldowns.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(RitualArtifact.class));
                long cooldownEnd = playerCooldowns.getOrDefault(artifact, 0L);

                if (cooldownEnd > currentTime) {
                    long timeLeftSeconds = (cooldownEnd - currentTime) / 1000;
                    Component cooldownMessage = messageManager.getMessage("player.artifact-cooldown",
                            Placeholder.unparsed("seconds", String.valueOf(timeLeftSeconds + 1))
                    );
                    player.sendActionBar(cooldownMessage);
                    event.setCancelled(true);
                    return;
                }

                ConfigurationSection artifactConfig = plugin.getConfigManager().getConfig().getConfigurationSection("ritual-artifacts." + artifact.name().toLowerCase().replace("_", "-"));
                if (artifactConfig == null) {
                    plugin.getLogger().warning("Missing configuration for artifact: " + artifact.name());
                    return;
                }

                event.setCancelled(true);
                long cooldownMillis = artifactConfig.getLong("cooldown-seconds", 60) * 1000L;
                playerCooldowns.put(artifact, currentTime + cooldownMillis);

                if (artifact == RitualArtifact.CURSED_LEDGER) {
                    spawnWolvesForPlayer(player, artifactConfig);
                } else if (artifact == RitualArtifact.WEEPING_BELL) {
                    activateBellAbility(player, artifactConfig);
                }
            }
            default -> {
            }
        }
    }

    private void spawnWolvesForPlayer(Player player, ConfigurationSection config) {
        int wolfCount = config.getInt("wolf-count", 3);
        int wolfDuration = config.getInt("wolf-duration-seconds", 20);

        List<Wolf> spawnedWolves = new ArrayList<>();
        for (int i = 0; i < wolfCount; i++) {
            Wolf wolf = player.getWorld().spawn(player.getLocation(), Wolf.class);
            wolf.setOwner(player);
            wolf.setTamed(true);
            spawnedWolves.add(wolf);
        }

        findBestTarget(player).ifPresent(target ->
                spawnedWolves.forEach(wolf -> wolf.setTarget(target))
        );

        new BukkitRunnable() {
            @Override
            public void run() {
                spawnedWolves.forEach(wolf -> {
                    if (wolf.isValid()) wolf.remove();
                });
            }
        }.runTaskLater(plugin, wolfDuration * 20L);
    }

    private void activateBellAbility(Player player, ConfigurationSection config) {
        double radius = config.getDouble("ability-radius", 12.0);
        int durationTicks = config.getInt("slowness-duration-seconds", 7) * 20;
        int amplifier = config.getInt("slowness-amplifier", 2);

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 2.0f, 0.5f);
        messageManager.sendMessage(player, "player.weeping-bell-use");

        player.getNearbyEntities(radius, radius, radius).stream()
                .filter(e -> e instanceof LivingEntity && !e.equals(player))
                .map(e -> (LivingEntity) e)
                .filter(livingEntity -> {
                    if (livingEntity instanceof Monster) {
                        return true;
                    }
                    if (livingEntity instanceof Player targetPlayer) {
                        return gameManager.isPvpEnabled() && gameManager.getAlivePlayers().contains(targetPlayer.getUniqueId());
                    }
                    return false;
                })
                .forEach(entity -> {
                    Vector knockback = entity.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(1.5);
                    knockback.setY(0.5);
                    entity.setVelocity(knockback);
                    entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, amplifier));

                    if (entity instanceof Player targetPlayer) {
                        targetPlayer.playSound(targetPlayer.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.5f, 2.0f);
                        targetPlayer.sendActionBar(messageManager.getMessage("player.weeping-bell-affected"));
                    }
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamageMob(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }

        Optional<RitualArtifact> mainHandArtifact = ritualManager.getArtifactFromItemStack(player.getInventory().getItemInMainHand());
        Optional<RitualArtifact> offHandArtifact = ritualManager.getArtifactFromItemStack(player.getInventory().getItemInOffHand());

        if (mainHandArtifact.isPresent() && mainHandArtifact.get() == RitualArtifact.CRYPT_LORDS_SKULL ||
                offHandArtifact.isPresent() && offHandArtifact.get() == RitualArtifact.CRYPT_LORDS_SKULL) {

            ConfigurationSection config = plugin.getConfigManager().getConfig().getConfigurationSection("ritual-artifacts.crypt-lords-skull");
            if (config == null) return;

            double lifestealPercent = config.getDouble("lifesteal-percent", 0.20);
            double damage = event.getFinalDamage();
            double healAmount = damage * lifestealPercent;

            if (healAmount > 0) {
                player.setHealth(Math.min(player.getHealth() + healAmount, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
            }
        }
    }

    private Optional<? extends LivingEntity> findBestTarget(Player player) {
        double radius = 25.0;
        if (gameManager.isPvpEnabled()) {
            return gameManager.getAlivePlayers().stream()
                    .map(Bukkit::getPlayer)
                    .filter(p -> p != null && !p.equals(player) && p.getLocation().distanceSquared(player.getLocation()) < radius * radius)
                    .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(player.getLocation())));
        } else {
            return player.getNearbyEntities(radius, radius, radius).stream()
                    .filter(e -> e instanceof Monster)
                    .map(e -> (LivingEntity) e)
                    .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomMobDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity instanceof Player) {
            return;
        }
        if (entity.getPersistentDataContainer().has(mobIdKey, PersistentDataType.STRING)) {
            if (entity.getHealth() - event.getFinalDamage() <= 0) {
                entity.customName(null);
                entity.setCustomNameVisible(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gameManager.isGameRunning() || !gameManager.getAlivePlayers().contains(player.getUniqueId())) return;

        if (player.getHealth() - event.getFinalDamage() <= 0.5) {
            event.setCancelled(true);
            handlePlayerDeath(player, event);
        }
    }

    private void handlePlayerDeath(Player player, EntityDamageEvent event) {
        broadcastCustomDeathMessage(player, event);

        ItemStack[] mainInv = player.getInventory().getStorageContents().clone();
        ItemStack[] armorInv = player.getInventory().getArmorContents().clone();
        ItemStack offhand = player.getInventory().getItemInOffHand().clone();

        List<ItemStack> allItems = new ArrayList<>(Arrays.asList(mainInv));
        allItems.addAll(Arrays.asList(armorInv));
        allItems.add(offhand);
        allItems.removeIf(item -> item == null || item.getType().isAir());

        ritualManager.handlePlayerDeath(player, allItems);
        Location deathLocation = player.getLocation().clone();

        List<ItemStack> artifactsToDrop = new ArrayList<>();
        List<ItemStack> itemsToProcess = new ArrayList<>();

        for (ItemStack item : allItems) {
            if (ritualManager.getArtifactFromItemStack(item).isPresent()) {
                artifactsToDrop.add(item);
            } else {
                itemsToProcess.add(item);
            }
        }

        dropItemsAtLocation(deathLocation, artifactsToDrop);

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);

        if (gameManager.getCurrentPhase().ordinal() < GamePhase.PHASE_3_BLOOD_MOON.ordinal()) {
            if (gameManager.getCurrentPhase() == GamePhase.PHASE_1_CALM) {
                handleRespawn(player, itemsToProcess);
            } else {
                if (itemsToProcess.isEmpty()) {
                    handleRespawn(player, null);
                } else {
                    Collections.shuffle(itemsToProcess);
                    int itemsToTake = Math.min(random.nextInt(3) + 3, itemsToProcess.size());

                    List<ItemStack> itemsForChest = new ArrayList<>(itemsToProcess.subList(0, itemsToTake));
                    List<ItemStack> itemsToKeep = new ArrayList<>(itemsToProcess.subList(itemsToTake, itemsToProcess.size()));

                    plugin.getDeathChestManager().createDeathChest(player, deathLocation, itemsForChest);

                    handleRespawn(player, itemsToKeep);
                }
            }
        } else {
            handlePlayerElimination(player, itemsToProcess);
        }
    }

    private List<ItemStack> getAllPlayerItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            items.add(offhand.clone());
        }
        return items;
    }

    private void dropItemsAtLocation(Location loc, List<ItemStack> items) {
        if (items == null || loc == null || !loc.isWorldLoaded()) return;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                loc.getWorld().dropItemNaturally(loc, item);
            }
        }
    }

    private void handleRespawn(Player player, List<ItemStack> itemsToRestore) {
        Location respawnLocation = plugin.getConfigManager().getStartLocation();
        if (respawnLocation == null) {
            plugin.getLogger().warning("Cannot respawn player, start location is not set!");
            player.setGameMode(GameMode.SPECTATOR);
            return;
        }

        final Component titleText = messageManager.getMessage("respawn.title");
        final Component subtitleText = messageManager.getMessage("respawn.subtitle");
        final Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000));
        final Title respawnTitle = Title.title(titleText, subtitleText, times);
        player.showTitle(respawnTitle);
        messageManager.sendMessage(player, "respawn.chat");

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(respawnLocation);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !gameManager.isGameRunning() || !gameManager.getAlivePlayers().contains(player.getUniqueId())) return;

                player.setGameMode(GameMode.ADVENTURE);
                player.teleport(respawnLocation);

                AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (maxHealthAttribute != null) {
                    player.setHealth(maxHealthAttribute.getValue());
                } else {
                    player.setHealth(20.0);
                }
                player.setFoodLevel(20);
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
                player.setFireTicks(0);

                player.getInventory().clear();

                if (itemsToRestore != null && !itemsToRestore.isEmpty()) {
                    for (ItemStack item : itemsToRestore) {
                        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
                        for (ItemStack leftover : leftovers.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 5 * 20L);
    }

    private void handlePlayerElimination(Player player, List<ItemStack> items) {
        Location playerLocation = player.getLocation();

        plugin.getDeathChestManager().createDeathChest(player, playerLocation, items);

        gameManager.eliminatePlayer(player);

        player.setGameMode(GameMode.SPECTATOR);
        player.setHealth(Objects.requireNonNull(player.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue());
        player.setFoodLevel(20);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);
        Location spectatorLocation = plugin.getConfigManager().getStartLocation();
        if (spectatorLocation != null) {
            player.teleport(spectatorLocation);
        }
    }


    private void broadcastCustomDeathMessage(Player victim, EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent damageByEntityEvent) {
            Entity killerEntity = damageByEntityEvent.getDamager();
            if (killerEntity instanceof Player killerPlayer) {
                if (gameManager.getAlivePlayers().contains(killerPlayer.getUniqueId())) {
                    messageManager.broadcastRandomFromList("death-messages.pvp",
                            Placeholder.unparsed("victim", victim.getName()),
                            Placeholder.unparsed("killer", killerPlayer.getName())
                    );
                    return;
                }
            } else if (killerEntity instanceof Mob mob) {
                Component customName = mob.customName();
                Component mobName = (customName != null) ? customName : Component.text(mob.getName());
                messageManager.broadcastRandomFromList("death-messages.mob",
                        Placeholder.unparsed("victim", victim.getName()),
                        Placeholder.component("mob", mobName)
                );
                return;
            }
            else if (killerEntity instanceof Projectile projectile) {
                if (projectile.getShooter() instanceof Player shooterPlayer) {
                    if (gameManager.getAlivePlayers().contains(shooterPlayer.getUniqueId())) {
                        messageManager.broadcastRandomFromList("death-messages.pvp",
                                Placeholder.unparsed("victim", victim.getName()),
                                Placeholder.unparsed("killer", shooterPlayer.getName())
                        );
                        return;
                    }
                } else if (projectile.getShooter() instanceof Mob shooterMob) {
                    Component customName = shooterMob.customName();
                    Component mobName = (customName != null) ? customName : Component.text(shooterMob.getName());
                    messageManager.broadcastRandomFromList("death-messages.mob",
                            Placeholder.unparsed("victim", victim.getName()),
                            Placeholder.component("mob", mobName)
                    );
                    return;
                }
            }
        }

        String messageKey = switch (event.getCause()) {
            case FALL -> "death-messages.fall";
            case VOID -> "death-messages.void";
            case FIRE, FIRE_TICK, LAVA -> "death-messages.fire";
            case DROWNING -> "death-messages.drowning";
            case CUSTOM, ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> {
                if (gameManager.getCurrentPhase() == GamePhase.PHASE_4_RAMPAGE &&
                        (event.getCause() == EntityDamageEvent.DamageCause.CUSTOM ||
                                victim.getLastDamageCause() == null)) {
                    yield "death-messages.generic";
                }
                yield "death-messages.generic";
            }
            default -> "death-messages.generic";
        };

        if (gameManager.getCurrentPhase() == GamePhase.PHASE_4_RAMPAGE &&
                gameManager.getSanctuaryArea() != null &&
                !gameManager.getSanctuaryArea().isInArea(victim.getLocation()) &&
                event.getCause() == EntityDamageEvent.DamageCause.CUSTOM) {
            return;
        }

        messageManager.broadcastRandomFromList(messageKey, Placeholder.unparsed("victim", victim.getName()));
    }


    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        if (victim.hasMetadata("NPC")) {
            return;
        }

        if (!gameManager.isGameRunning() || gameManager.isPvpEnabled() || !gameManager.getAlivePlayers().contains(victim.getUniqueId())) {
            return;
        }

        Player attacker = null;

        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        }
        else if (event.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        if (attacker != null && gameManager.getAlivePlayers().contains(attacker.getUniqueId())) {
            if (attacker.getUniqueId().equals(victim.getUniqueId())) {
                return;
            }

            event.setCancelled(true);
            event.setDamage(0.0);
            messageManager.sendMessage(attacker, "player.pvp-disabled");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMobDeath(EntityDeathEvent event) {
        if (!gameManager.isGameRunning()) return;
        LivingEntity entity = event.getEntity();
        String mobId = entity.getPersistentDataContainer().get(mobIdKey, PersistentDataType.STRING);

        if (mobId != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            lootManager.handleMobDeath(entity, mobId);

            Player killer = entity.getKiller();
            if (killer != null && gameManager.getAlivePlayers().contains(killer.getUniqueId())) {
                switch (mobId.toUpperCase()) {
                    case "HEADLESS_HORSEMAN" -> ritualManager.rewardArtifact(killer, RitualArtifact.CURSED_LEDGER);
                    case "MANOR_EVOKER" -> ritualManager.rewardArtifact(killer, RitualArtifact.WEEPING_BELL);
                    case "WITCH_OF_THE_MIRE" -> {
                        ItemStack rose = new ItemStack(Material.WITHER_ROSE);
                        ItemMeta meta = rose.getItemMeta();
                        MessageManager mm = plugin.getMessageManager();
                        meta.displayName(mm.getMessage("items.witchs-rose-name"));

                        List<String> loreLines = plugin.getConfigManager().getMessagesConfig().getStringList("items.witchs-rose-lore");
                        List<Component> loreComponents = loreLines.stream().map(line -> mm.miniMessage.deserialize(line)).collect(Collectors.toList());
                        meta.lore(loreComponents);

                        rose.setItemMeta(meta);
                        entity.getWorld().dropItemNaturally(entity.getLocation(), rose);
                    }
                }
            }
        }
    }
}