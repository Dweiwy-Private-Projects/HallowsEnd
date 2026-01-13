package me.siwannie.hallowsend.modules.mobs;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.game.GamePhase;
import me.siwannie.hallowsend.game.area.DefinedArea;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

public class MobManager {

    private final HallowsEnd plugin;
    private final Random random = new Random();
    private final NamespacedKey eventMobKey;
    private final NamespacedKey mobIdKey;
    private final Map<String, CustomMob> mobDefinitions = new HashMap<>();
    private final List<CustomMob> phase2Mobs = new ArrayList<>();
    private final List<CustomMob> phase3Mobs = new ArrayList<>();
    private LivingEntity headlessHorseman = null;
    private LivingEntity manorEvoker = null;
    private LivingEntity witch = null;
    private LivingEntity harbingerVisual = null;
    private boolean hasManorEvokerSpawned = false;
    private boolean hasWitchSpawned = false;
    private boolean hasHorsemanSpawned = false;
    private int mobCapPerPlayer;
    private int spawnRadius;
    private double harbingerAttackChance;
    private Team mobTeam;

    private DefinedArea sanctuaryAreaCache = null;

    private int horsemanAbilityCooldown = 0;

    public MobManager(HallowsEnd plugin) {
        this.plugin = plugin;
        this.eventMobKey = new NamespacedKey(plugin, "event_mob");
        this.mobIdKey = new NamespacedKey(plugin, "mob_id");
        setupMobTeam();
        loadCustomMobs();
    }

    public void loadCustomMobs() {
        ConfigurationSection config = plugin.getConfigManager().getConfig();
        mobDefinitions.clear();
        phase2Mobs.clear();
        phase3Mobs.clear();

        this.mobCapPerPlayer = config.getInt("mob-spawning.mob-cap-per-player", 4);
        this.spawnRadius = config.getInt("mob-spawning.spawn-radius", 32);
        this.harbingerAttackChance = config.getDouble("mob-spawning.harbinger-attack-chance", 0.05);

        this.sanctuaryAreaCache = plugin.getConfigManager().getAreaById("church");
        if (this.sanctuaryAreaCache == null) {
            plugin.getLogger().warning("MobManager: Sanctuary area 'church' not found in config. Mobs might spawn inside.");
        }

        loadMobsFromConfigSection(config.getConfigurationSection("mob-spawning.phase-2-mobs"), phase2Mobs);
        loadMobsFromConfigSection(config.getConfigurationSection("mob-spawning.phase-3-mobs"), phase3Mobs);
        loadMobsFromConfigSection(config.getConfigurationSection("mob-spawning.bosses"), null);
    }

    private void loadMobsFromConfigSection(ConfigurationSection section, List<CustomMob> listToPopulate) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection mobSection = section.getConfigurationSection(key);
            if (mobSection == null) continue;

            try {
                EntityType type = EntityType.valueOf(mobSection.getString("type", "ZOMBIE"));
                String name = mobSection.getString("name", "");
                double health = mobSection.getDouble("health", 20.0);
                double spawnChance = mobSection.getDouble("spawn-chance", 1.0);
                double speedMultiplier = mobSection.getDouble("speed-multiplier", 1.0);
                double scale = mobSection.getDouble("scale", 1.0);

                Map<String, Material> equipment = new HashMap<>();
                ConfigurationSection equipSection = mobSection.getConfigurationSection("equipment");
                if (equipSection != null) {
                    for (String slot : equipSection.getKeys(false)) {
                        equipment.put(slot, Material.valueOf(equipSection.getString(slot, "AIR")));
                    }
                }

                List<String> effects = mobSection.getStringList("potion-effects");

                CustomMob mob = new CustomMob(key, type, name, health, spawnChance, speedMultiplier, scale, equipment, effects);
                mobDefinitions.put(key, mob);
                if (listToPopulate != null) {
                    listToPopulate.add(mob);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load custom mob '" + key + "': " + e.getMessage());
            }
        }
    }

    private void setupMobTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        this.mobTeam = scoreboard.getTeam("HallowsEndMobs");
        if (this.mobTeam == null) {
            this.mobTeam = scoreboard.registerNewTeam("HallowsEndMobs");
        }
        this.mobTeam.setAllowFriendlyFire(false);
        this.mobTeam.setCanSeeFriendlyInvisibles(true);
    }

    public void tick() {
        GameManager gameManager = plugin.getGameManager();
        if (gameManager == null || !gameManager.isGameRunning()) return;

        if (sanctuaryAreaCache == null) {
            this.sanctuaryAreaCache = plugin.getConfigManager().getAreaById("church");
        }

        GamePhase phase = gameManager.getCurrentPhase();
        handleRegularSpawning(phase);
        handleBossSpawning(phase);
        handleBossAbilities(phase);
        handleHarbingerAttack(phase);
        confineBosses();
    }

    private void confineBosses() {
        if (isManorEvokerAlive()) {
            DefinedArea atticArea = plugin.getConfigManager().getAreaById("manor-attic");
            if (atticArea != null && manorEvoker != null && manorEvoker.isValid() && !atticArea.isInArea(manorEvoker.getLocation())) {
                manorEvoker.teleport(atticArea.getCenter());
            }
        }
        if (isWitchAlive()) {
            DefinedArea witchArea = plugin.getConfigManager().getAreaById("witch-hut");
            if (witchArea != null && witch != null && witch.isValid() && !witchArea.isInArea(witch.getLocation())) {
                witch.teleport(witchArea.getCenter());
            }
        }
    }

    public void handleHarbingerAttack(GamePhase phase) {
        if (phase != GamePhase.PHASE_5_LMS) return;

        if (random.nextDouble() < harbingerAttackChance) {
            getRandomPlayerInSanctuary().ifPresent(target -> {
                plugin.getMessageManager().broadcast("player.harbinger-attack", Placeholder.component("player", target.displayName()));
                target.getWorld().strikeLightning(target.getLocation());
            });
        }
    }


    private void handleRegularSpawning(GamePhase phase) {
        final List<CustomMob> mobsForThisPhase;

        switch (phase) {
            case PHASE_2_AWAKENING:
                mobsForThisPhase = phase2Mobs;
                break;
            case PHASE_3_BLOOD_MOON:
                mobsForThisPhase = new ArrayList<>(phase2Mobs);
                mobsForThisPhase.addAll(phase3Mobs);
                break;
            case PHASE_4_RAMPAGE:
                mobsForThisPhase = new ArrayList<>(phase2Mobs);
                mobsForThisPhase.addAll(phase3Mobs);
                break;
            default:
                return;
        }

        if (mobsForThisPhase.isEmpty()) return;

        long currentMobCount = Bukkit.getWorlds().stream()
                .flatMap(world -> world.getLivingEntities().stream())
                .filter(this::isEventMob)
                .count();

        long maxMobs = (long) plugin.getGameManager().getAlivePlayers().size() * mobCapPerPlayer;
        if (currentMobCount >= maxMobs) return;

        getRandomPlayerOutsideSanctuary().ifPresent(player -> {
            CustomMob mobToSpawn = selectRandomMob(mobsForThisPhase);
            if (mobToSpawn != null) {
                findSafeSpawnLocationNearPlayer(player).ifPresent(loc -> spawnCustomMob(mobToSpawn, loc, true));
            }
        });
    }

    private void handleBossSpawning(GamePhase phase) {
        if (phase == GamePhase.PHASE_3_BLOOD_MOON) {
            if (!hasHorsemanSpawned && (headlessHorseman == null || !headlessHorseman.isValid())) {
                Location startLocation = plugin.getConfigManager().getStartLocation();
                if (startLocation == null) {
                    plugin.getLogger().severe("Cannot spawn Headless Horseman, start-location is not set!");
                    return;
                }

                CustomMob horsemanDef = mobDefinitions.get("HEADLESS_HORSEMAN");
                if (horsemanDef == null) {
                    plugin.getLogger().warning("Headless Horseman definition not found in config!");
                    return;
                }

                findSafeSpawnLocationNear(startLocation, 25, 35).ifPresent(loc -> {
                    SkeletonHorse horse = loc.getWorld().spawn(loc, SkeletonHorse.class);
                    horse.setTamed(true);
                    horse.setInvulnerable(true);
                    AttributeInstance horseHealth = horse.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    if (horseHealth != null) {
                        horseHealth.setBaseValue(1000.0);
                    }
                    horse.setHealth(1000.0);
                    horse.setRemoveWhenFarAway(false);
                    horse.getPersistentDataContainer().set(eventMobKey, PersistentDataType.BYTE, (byte) 1);


                    spawnCustomMob(horsemanDef, loc, true).ifPresent(newHorseman -> {
                        horse.addPassenger(newHorseman);
                        this.headlessHorseman = newHorseman;
                        this.hasHorsemanSpawned = true;
                        this.horsemanAbilityCooldown = 0;
                        plugin.getMessageManager().broadcast("misc.horseman-spawn");
                    });
                });
            }
        } else {
            if (headlessHorseman != null && headlessHorseman.isValid()) {
                Entity vehicle = headlessHorseman.getVehicle();
                if (vehicle != null && vehicle.isValid()) {
                    vehicle.remove();
                }
                headlessHorseman.remove();
                headlessHorseman = null;
            }
        }
    }

    private void handleBossAbilities(GamePhase phase) {
        if (phase != GamePhase.PHASE_3_BLOOD_MOON || headlessHorseman == null || !headlessHorseman.isValid()) {
            return;
        }

        if (horsemanAbilityCooldown > 0) {
            horsemanAbilityCooldown--;
            return;
        }

        if (!(headlessHorseman instanceof Mob mob)) {
            return;
        }

        if (mob.getTarget() == null || !mob.getTarget().isValid() || random.nextInt(100) < 20) {
            findClosestPlayer(headlessHorseman, 40).ifPresent(mob::setTarget);
        }
        LivingEntity currentTarget = mob.getTarget();

        if (currentTarget != null && currentTarget.isValid()) {
            double distanceSquared = currentTarget.getLocation().distanceSquared(headlessHorseman.getLocation());
            double yDifference = currentTarget.getLocation().getY() - headlessHorseman.getLocation().getY();

            if (yDifference > 7 && distanceSquared < 25 * 25) {
                pullPlayerDown(currentTarget);
                horsemanAbilityCooldown = 100;
                return;
            }
        }

        if (random.nextDouble() < 0.30) {
            getRandomPlayerInRange(headlessHorseman, 30).ifPresent(randomTarget -> {
                plugin.getLogger().info("Horseman firing skull at random target: " + randomTarget.getName());
                launchWitherSkull(mob, randomTarget);
                horsemanAbilityCooldown = 160;
            });
        }
    }

    private Optional<Player> findClosestPlayer(LivingEntity mob, double radius) {
        return mob.getWorld().getPlayers().stream()
                .filter(p -> plugin.getGameManager().getAlivePlayers().contains(p.getUniqueId()))
                .filter(p -> p.getLocation().distanceSquared(mob.getLocation()) <= radius * radius)
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(mob.getLocation())));
    }

    private Optional<Player> getRandomPlayerInRange(LivingEntity mob, double radius) {
        List<Player> nearbyPlayers = mob.getWorld().getPlayers().stream()
                .filter(p -> plugin.getGameManager().getAlivePlayers().contains(p.getUniqueId()))
                .filter(p -> p.getLocation().distanceSquared(mob.getLocation()) <= radius * radius)
                .collect(Collectors.toList());

        if (nearbyPlayers.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(nearbyPlayers.get(random.nextInt(nearbyPlayers.size())));
    }

    private void pullPlayerDown(LivingEntity target) {
        if (target instanceof Player player) {
            plugin.getMessageManager().sendMessage(player, "player.horseman-pull");
        }
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GHAST_WARN, 1.0f, 0.8f);
        Vector pullVector = new Vector(0, -2.0, 0);
        target.setVelocity(target.getVelocity().add(pullVector));
    }

    private void launchWitherSkull(Mob shooter, LivingEntity target) {
        shooter.getWorld().playSound(shooter.getEyeLocation(), Sound.ENTITY_WITHER_SHOOT, 2.0f, 1.0f);
        WitherSkull skull = shooter.launchProjectile(WitherSkull.class);
        Vector direction = target.getEyeLocation().toVector().subtract(shooter.getEyeLocation().toVector()).normalize();
        skull.setDirection(direction);
        skull.setShooter(shooter);
    }


    public void spawnManorEvoker(Location location) {
        if (hasManorEvokerSpawned || isManorEvokerAlive()) return;

        CustomMob evokerDef = mobDefinitions.get("MANOR_EVOKER");
        if (evokerDef != null) {
            findSafeSpawnLocationNear(location, 1, 5).ifPresent(spawnLoc -> {
                spawnCustomMob(evokerDef, spawnLoc, true).ifPresent(entity -> {
                    this.manorEvoker = entity;
                    this.hasManorEvokerSpawned = true;
                    plugin.getMessageManager().broadcast("misc.attic-guardian-spawn");
                });
            });
        } else {
            plugin.getLogger().warning("Manor Evoker definition 'MANOR_EVOKER' not found in config! Cannot spawn.");
        }
    }

    public void spawnWitch(Location location) {
        if (hasWitchSpawned || isWitchAlive()) return;

        CustomMob witchDef = mobDefinitions.get("WITCH_OF_THE_MIRE");
        if (witchDef != null) {
            findSafeSpawnLocationNear(location, 1, 5).ifPresent(spawnLoc -> {
                spawnCustomMob(witchDef, spawnLoc, true).ifPresent(entity -> {
                    this.witch = entity;
                    this.hasWitchSpawned = true;
                    plugin.getMessageManager().broadcast("misc.witch-spawn");
                });
            });
        } else {
            plugin.getLogger().warning("Witch definition 'WITCH_OF_THE_MIRE' not found in config! Cannot spawn.");
        }
    }

    public void spawnHarbingerVisual() {
        if (harbingerVisual != null && harbingerVisual.isValid()) {
            return;
        }

        if (sanctuaryAreaCache == null) {
            this.sanctuaryAreaCache = plugin.getConfigManager().getAreaById("church");
        }

        if (sanctuaryAreaCache == null) {
            plugin.getLogger().severe("Cannot spawn Harbinger visual: Sanctuary 'church' area not defined!");
            return;
        }

        CustomMob harbingerDef = mobDefinitions.get("HARBINGER");
        if (harbingerDef == null) {
            plugin.getLogger().warning("Harbinger definition 'HARBINGER' not found in config! Cannot spawn visual.");
            return;
        }

        Location spawnLoc = sanctuaryAreaCache.getCenter();
        spawnLoc.setY(sanctuaryAreaCache.minY() + 12);

        spawnCustomMob(harbingerDef, spawnLoc, true).ifPresent(entity -> {
            this.harbingerVisual = entity;
            entity.setInvulnerable(true);
            entity.setCollidable(false);
            if (entity instanceof Allay allay) {
                allay.setCanDuplicate(false);
            }
        });
    }

    private Optional<LivingEntity> spawnCustomMob(CustomMob mobData, Location location, boolean isEventMob) {
        Entity entity = location.getWorld().spawnEntity(location, mobData.type());
        if (!(entity instanceof LivingEntity livingEntity)) {
            entity.remove();
            return Optional.empty();
        }

        mobTeam.addEntry(livingEntity.getUniqueId().toString());

        livingEntity.getPersistentDataContainer().set(mobIdKey, PersistentDataType.STRING, mobData.id());

        if (isEventMob) {
            livingEntity.getPersistentDataContainer().set(eventMobKey, PersistentDataType.BYTE, (byte) 1);
        }

        livingEntity.setCustomNameVisible(true);
        livingEntity.customName(MiniMessage.miniMessage().deserialize(mobData.name()));

        AttributeInstance maxHealthAttr = livingEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(mobData.health());
        } else {
            plugin.getLogger().warning("Could not set max health for mob " + mobData.id() + ". Attribute not found.");
        }
        livingEntity.setHealth(mobData.health());

        livingEntity.setSilent(true);
        livingEntity.setRemoveWhenFarAway(false);

        if (mobData.speedMultiplier() != 1.0) {
            AttributeInstance speedAttr = livingEntity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.setBaseValue(speedAttr.getDefaultValue() * mobData.speedMultiplier());
            } else {
                plugin.getLogger().warning("Could not set speed for mob " + mobData.id() + ". Attribute not found.");
            }
        }

        if (mobData.scale() != 1.0) {
            try {
                AttributeInstance scaleAttr = livingEntity.getAttribute(Attribute.GENERIC_SCALE);
                if (scaleAttr != null) {
                    scaleAttr.setBaseValue(mobData.scale());
                } else {
                    plugin.getLogger().warning("Could not set scale for mob " + mobData.id() + ". Attribute GENERIC_SCALE not found.");
                }
            } catch (NoSuchMethodError | NoClassDefFoundError e) {
                plugin.getLogger().warning("Could not set scale for mob " + mobData.id() + ". Attribute GENERIC_SCALE might not be available on this server version/API.");
            } catch (Exception e) {
                plugin.getLogger().severe("An unexpected error occurred while setting scale for mob " + mobData.id() + ": " + e.getMessage());
            }
        }

        EntityEquipment equipment = livingEntity.getEquipment();
        if (equipment != null) {
            mobData.equipment().forEach((slot, material) -> {
                if (material != Material.AIR) {
                    ItemStack item = new ItemStack(material);
                    switch (slot.toLowerCase()) {
                        case "helmet" -> equipment.setHelmet(item);
                        case "chestplate" -> equipment.setChestplate(item);
                        case "leggings" -> equipment.setLeggings(item);
                        case "boots" -> equipment.setBoots(item);
                        case "mainhand" -> equipment.setItemInMainHand(item);
                        case "offhand" -> equipment.setItemInOffHand(item);
                        default -> plugin.getLogger().warning("Invalid equipment slot '" + slot + "' for mob " + mobData.id());
                    }
                }
            });
            equipment.setHelmetDropChance(0f);
            equipment.setChestplateDropChance(0f);
            equipment.setLeggingsDropChance(0f);
            equipment.setBootsDropChance(0f);
            equipment.setItemInMainHandDropChance(0f);
            equipment.setItemInOffHandDropChance(0f);
        }

        mobData.potionEffects().forEach(effectString -> {
            try {
                String[] parts = effectString.split(":");
                PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase());
                int durationTicks = Integer.MAX_VALUE / 2;
                int amplifier = 0;

                if (parts.length > 1 && !parts[1].equalsIgnoreCase("infinite")) {
                    try {
                        int durationSeconds = Integer.parseInt(parts[1]);
                        durationTicks = durationSeconds > (Integer.MAX_VALUE / 20) ? Integer.MAX_VALUE / 2 : durationSeconds * 20;
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid duration '" + parts[1] + "' for potion effect on mob " + mobData.id() + ". Using infinite.");
                    }
                }
                if (parts.length > 2) {
                    try {
                        amplifier = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid amplifier '" + parts[2] + "' for potion effect on mob " + mobData.id() + ". Using 0.");
                    }
                }

                if (type != null) {
                    livingEntity.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, true, false));
                }
                else plugin.getLogger().warning("Invalid potion effect type '" + parts[0] + "' for mob " + mobData.id());

            } catch (Exception e) {
                plugin.getLogger().warning("Invalid potion effect format '" + effectString + "' for mob " + mobData.id() + ": " + e.getMessage());
            }
        });

        return Optional.of(livingEntity);
    }


    private boolean isEventMob(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(eventMobKey, PersistentDataType.BYTE) ||
                entity.equals(headlessHorseman) ||
                entity.equals(manorEvoker) ||
                entity.equals(witch) ||
                entity.equals(harbingerVisual) ||
                (headlessHorseman != null && entity.equals(headlessHorseman.getVehicle()));
    }

    private Optional<Player> getRandomPlayerOutsideSanctuary() {
        GameManager gameManager = plugin.getGameManager();
        if (gameManager == null) return Optional.empty();

        List<UUID> validTargets = new ArrayList<>();

        if (sanctuaryAreaCache == null) {
            plugin.getLogger().warning("sanctuaryAreaCache is null in getRandomPlayerOutsideSanctuary! Defaulting to all alive players.");
            validTargets.addAll(gameManager.getAlivePlayers());
        } else {
            for (UUID uuid : gameManager.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline() && !sanctuaryAreaCache.isInArea(p.getLocation())) {
                    validTargets.add(uuid);
                }
            }
        }

        if (validTargets.isEmpty()) {
            plugin.getLogger().fine("No players outside sanctuary. No mobs will be spawned.");
            return Optional.empty();
        }

        UUID randomUUID = validTargets.get(random.nextInt(validTargets.size()));
        return Optional.ofNullable(Bukkit.getPlayer(randomUUID));
    }

    private Optional<Player> getRandomPlayerInSanctuary() {
        GameManager gameManager = plugin.getGameManager();
        if (gameManager == null) return Optional.empty();

        List<UUID> validTargets = new ArrayList<>();

        if (sanctuaryAreaCache == null) {
            plugin.getLogger().warning("sanctuaryAreaCache is null in getRandomPlayerInSanctuary! Cannot find players.");
            return Optional.empty();
        }

        for (UUID uuid : gameManager.getAlivePlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && sanctuaryAreaCache.isInArea(p.getLocation())) {
                validTargets.add(uuid);
            }
        }

        if (validTargets.isEmpty()) {
            plugin.getLogger().fine("No players inside sanctuary for Harbinger to target.");
            return Optional.empty();
        }

        UUID randomUUID = validTargets.get(random.nextInt(validTargets.size()));
        return Optional.ofNullable(Bukkit.getPlayer(randomUUID));
    }

    private CustomMob selectRandomMob(List<CustomMob> mobList) {
        if (mobList.isEmpty()) return null;
        double totalChance = mobList.stream().mapToDouble(CustomMob::spawnChance).sum();
        if (totalChance <= 0) return mobList.get(random.nextInt(mobList.size()));

        double randomValue = random.nextDouble() * totalChance;
        double cumulativeChance = 0;
        for (CustomMob mob : mobList) {
            cumulativeChance += mob.spawnChance();
            if (randomValue <= cumulativeChance) return mob;
        }
        return mobList.get(mobList.size() - 1);
    }

    private Optional<Location> findSafeSpawnLocationNearPlayer(Player player) {
        return findSafeSpawnLocationNear(player.getLocation(), 10, spawnRadius);
    }

    private Optional<Location> findSafeSpawnLocationNear(Location centerLoc, int minRadius, int maxRadius) {
        World world = centerLoc.getWorld();
        if (world == null) return Optional.empty();

        for (int i = 0; i < 20; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);
            int x = centerLoc.getBlockX() + (int) (radius * Math.cos(angle));
            int z = centerLoc.getBlockZ() + (int) (radius * Math.sin(angle));

            for (int yOffset = 5; yOffset >= -10; yOffset--) {
                int y = centerLoc.getBlockY() + yOffset;
                if (y < world.getMinHeight() || y >= world.getMaxHeight() -1) continue;

                Location potentialLoc = new Location(world, x + 0.5, y, z + 0.5);

                if (sanctuaryAreaCache != null && sanctuaryAreaCache.isInArea(potentialLoc)) {
                    continue;
                }

                Block blockBelow = potentialLoc.clone().add(0, -1, 0).getBlock();
                Block blockAt = potentialLoc.getBlock();
                Block blockAbove = potentialLoc.clone().add(0, 1, 0).getBlock();

                if (blockBelow.getType().isSolid() && !blockBelow.getType().isInteractable() &&
                        !blockAt.getType().isSolid() && !blockAt.isLiquid() &&
                        !blockAbove.getType().isSolid() && !blockAbove.isLiquid())
                {
                    return Optional.of(potentialLoc);
                }
            }
        }
        plugin.getLogger().warning("Could not find suitable safe spawn location near " + centerLoc.getBlockX() + "," + centerLoc.getBlockZ() + " after 20 attempts.");
        return Optional.empty();
    }


    public void reset() {
        plugin.getLogger().info("Resetting MobManager...");
        Bukkit.getScheduler().runTask(plugin, () -> {
            int removedCount = 0;
            for (World world : Bukkit.getWorlds()) {
                for (LivingEntity entity : world.getLivingEntities()) {
                    if (isEventMob(entity)) {
                        if (!entity.getPassengers().isEmpty()) {
                            entity.getPassengers().forEach(Entity::remove);
                        }
                        if(headlessHorseman != null && entity.equals(headlessHorseman.getVehicle())) {
                            entity.remove();
                        } else if (!entity.isDead()) {
                            entity.remove();
                        }
                        removedCount++;
                    }
                }
            }
            plugin.getLogger().info("Removed " + removedCount + " event mobs.");
        });

        headlessHorseman = null;
        manorEvoker = null;
        witch = null;
        harbingerVisual = null;
        hasManorEvokerSpawned = false;
        hasWitchSpawned = false;
        hasHorsemanSpawned = false;
        sanctuaryAreaCache = null;

        if (mobTeam != null) {
            try {
                mobTeam.getEntries().forEach(mobTeam::removeEntry);
                mobTeam.unregister();
            } catch (IllegalStateException e) {
                plugin.getLogger().fine("Mob team already unregistered.");
            }
            mobTeam = null;
        }
        setupMobTeam();
        plugin.getLogger().info("MobManager reset complete.");
    }

    public boolean isManorEvokerAlive() {
        return manorEvoker != null && manorEvoker.isValid() && !manorEvoker.isDead();
    }

    public boolean isWitchAlive() {
        return witch != null && witch.isValid() && !witch.isDead();
    }

    public boolean hasManorEvokerSpawned() {
        return hasManorEvokerSpawned;
    }

    public boolean hasWitchSpawned() { return hasWitchSpawned; }
}