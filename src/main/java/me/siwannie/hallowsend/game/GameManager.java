package me.siwannie.hallowsend.game;

import com.destroystokyo.paper.profile.PlayerProfile;
import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.ConfigManager;
import me.siwannie.hallowsend.config.MessageManager;
import me.siwannie.hallowsend.game.area.DefinedArea;
import me.siwannie.hallowsend.modules.mobs.MobManager;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import me.siwannie.hallowsend.modules.sanity.SanityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class GameManager {

    private final HallowsEnd plugin;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private SanityManager sanityManager;
    private RitualManager ritualManager;
    private MobManager mobManager;
    private IntroCutscene introCutscene;
    private GameTask gameTask;

    private GamePhase currentPhase = GamePhase.WAITING;
    private final Set<UUID> alivePlayers = new HashSet<>();
    private int timeLeftInPhase;
    private int pvpCountdown = -1;
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<Location, ArmorStand> inheritanceHolograms = new HashMap<>();
    private final Set<Location> inheritanceChests = new HashSet<>();

    private final Random random = new Random();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private DefinedArea sanctuaryArea;
    private double maxDistanceSquared = 1.0;
    private int rampageGracePeriod = 30;
    private int rampageTotalDuration = 120;
    private boolean phase5TriggeredBySafety = false;
    private double rampageDamageAmount = 10.0;

    private Material originalCryptVaultMaterial = Material.AIR;
    private Material originalLibraryPistonMaterial = Material.AIR;

    private final Map<UUID, DisconnectedPlayerInfo> disconnectedPlayers = new ConcurrentHashMap<>();

    private static class DisconnectedPlayerInfo {
        final UUID playerId;
        final String playerName;
        final Location logoutLocation;
        final ItemStack[] mainInventory;
        final ItemStack[] armorInventory;
        final ItemStack offHandItem;
        final long disconnectTimestamp;
        final GamePhase disconnectPhase;
        DisconnectedPlayerInfo(Player player, GamePhase phase) {
            this.playerId = player.getUniqueId();
            this.playerName = player.getName();
            this.logoutLocation = player.getLocation();
            this.mainInventory = player.getInventory().getContents();
            this.armorInventory = player.getInventory().getArmorContents();
            this.offHandItem = player.getInventory().getItemInOffHand();
            this.disconnectTimestamp = System.currentTimeMillis();
            this.disconnectPhase = phase;
        }
    }

    public GameManager(HallowsEnd plugin) {
        this.plugin = plugin;
        loadManagers();
        this.introCutscene = new IntroCutscene(plugin, this);
    }

    public void loadManagers() {
        this.configManager = plugin.getConfigManager();
        this.messageManager = plugin.getMessageManager();
        this.sanityManager = plugin.getSanityManager();
        this.ritualManager = plugin.getRitualManager();
        this.mobManager = plugin.getMobManager();
        this.rampageGracePeriod = configManager.getConfig().getInt("game-settings.rampage.grace-period-seconds", 30);
        this.rampageTotalDuration = configManager.getPhaseDuration(GamePhase.PHASE_4_RAMPAGE);
        this.rampageDamageAmount = configManager.getConfig().getDouble("game-settings.rampage.damage-per-strike", 10.0);
        if (this.rampageTotalDuration <= 0) this.rampageTotalDuration = 120;
    }

    public void startGame() {
        if (isGameRunning()) {
            messageManager.broadcast("game-state.already-running");
            return;
        }

        loadManagers();
        this.sanctuaryArea = configManager.getAreaById("church");
        phase5TriggeredBySafety = false;

        Location cryptVaultLoc = configManager.getLocation("event-objects.crypt-vault");
        Location libraryPistonLoc = configManager.getLocation("event-objects.library-puzzle-piston");

        if (cryptVaultLoc != null && cryptVaultLoc.isWorldLoaded()) {
            originalCryptVaultMaterial = cryptVaultLoc.getBlock().getType();
        } else {
            originalCryptVaultMaterial = Material.AIR;
        }

        if (libraryPistonLoc != null && libraryPistonLoc.isWorldLoaded()) {
            originalLibraryPistonMaterial = libraryPistonLoc.getBlock().getType();
        } else {
            originalLibraryPistonMaterial = Material.AIR;
        }

        alivePlayers.clear();
        eliminationOrder.clear();
        disconnectedPlayers.clear();
        Location startLocation = configManager.getStartLocation();
        String staffPermission = configManager.getStaffBypassPermission();

        if (startLocation == null) {
            plugin.getLogger().severe("START ABORTED: Game start location (start-location) is not set in config.yml!");
            messageManager.broadcast("misc.error-no-start-location");
            resetPuzzleBlocks();
            return;
        }

        Location cutsceneStartLocation = configManager.getCutsceneStartLocation();
        if (cutsceneStartLocation == null) {
            plugin.getLogger().severe("START ABORTED: Cutscene start location (cutscene-start-location) is not set in config.yml!");
            messageManager.broadcast("misc.error-no-start-location");
            resetPuzzleBlocks();
            return;
        }

        clearInheritanceChests();

        Set<Player> playersToStart = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(staffPermission)) {
                messageManager.sendMessage(player, "misc.staff-bypass");
                continue;
            }

            player.teleport(cutsceneStartLocation);
            player.setGameMode(GameMode.SPECTATOR);

            playersToStart.add(player);
            alivePlayers.add(player.getUniqueId());
        }

        if (playersToStart.isEmpty()) {
            messageManager.broadcast("misc.error-no-players");
            resetPuzzleBlocks();
            return;
        }

        ritualManager.reset();
        mobManager.reset();
        sanityManager.resetAll();
        plugin.getPuzzleListener().reset();
        plugin.getLootChestManager().reset();
        plugin.getPlayerMoveListener().reset();
        resetPuzzleBlocks();

        setPhase(GamePhase.INTRO_CUTSCENE);
        introCutscene.start(playersToStart);

        gameTask = new GameTask(this);
        gameTask.runTaskTimer(plugin, 0L, 20L);
    }

    public void beginPhase1() {
        Location startLocation = configManager.getStartLocation();
        if (startLocation == null) {
            plugin.getLogger().severe("Cannot start Phase 1, start location is null!");
            stopGame(true);
            return;
        }

        for (UUID playerUUID : alivePlayers) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.setGameMode(GameMode.ADVENTURE);
                player.teleport(startLocation);
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false));
                resetPlayerState(player);
                sanityManager.initializePlayer(player);
            }
        }

        setPhase(GamePhase.PHASE_1_CALM);
        messageManager.broadcast("game-state.start");
    }


    public void addPlayerToGame(Player player) {
        if (disconnectedPlayers.containsKey(player.getUniqueId())) {
            DisconnectedPlayerInfo info = disconnectedPlayers.remove(player.getUniqueId());
            boolean restoreItems = false;
            String rejoinReason = "reconnected";

            if (info.disconnectPhase == GamePhase.PHASE_1_CALM) {
                restoreItems = true;
                rejoinReason = "reconnected from Phase 1";
            } else if (info.disconnectPhase == GamePhase.PHASE_2_AWAKENING) {
                long timeElapsed = System.currentTimeMillis() - info.disconnectTimestamp;
                long twoMinutes = 120 * 1000;

                if (timeElapsed <= twoMinutes) {
                    restoreItems = true;
                    rejoinReason = "reconnected from Phase 2 in time";
                } else {
                    plugin.getLogger().info("Player " + player.getName() + " reconnected too late from Phase 2. Items were dropped.");
                }
            }

            if (restoreItems) {
                plugin.getLogger().info("Player " + player.getName() + " " + rejoinReason + ". Restoring to game.");

                alivePlayers.add(player.getUniqueId());
                eliminationOrder.remove(player.getUniqueId());

                player.teleport(configManager.getStartLocation());
                player.setGameMode(GameMode.ADVENTURE);

                resetPlayerState(player);
                player.getInventory().setContents(info.mainInventory);
                player.getInventory().setArmorContents(info.armorInventory);
                player.getInventory().setItemInOffHand(info.offHandItem);

                sanityManager.initializePlayer(player);
                messageManager.sendMessage(player, "commands.rejoin-success-player");
                messageManager.broadcast("commands.rejoin-success-broadcast", Placeholder.component("player", player.displayName()));
                return;
            }
        }


        if (!isGameRunning()) {
            messageManager.sendMessage(player, "game-state.not-running");
            return;
        }
        if (alivePlayers.contains(player.getUniqueId())) {
            messageManager.sendMessage(player, "commands.already-in-game");
            return;
        }
        if (player.hasPermission(configManager.getStaffBypassPermission())) {
            messageManager.sendMessage(player, "commands.staff-cannot-join");
            return;
        }

        Location startLocation = configManager.getStartLocation();
        if (startLocation == null) {
            plugin.getLogger().severe("A player tried to join, but the start location is not set!");
            messageManager.sendMessage(player, "misc.error-no-start-location");
            return;
        }

        if (currentPhase == GamePhase.INTRO_CUTSCENE) {
            alivePlayers.add(player.getUniqueId());
            eliminationOrder.removeIf(uuid -> uuid.equals(player.getUniqueId()));

            Location cutsceneStartLocation = configManager.getCutsceneStartLocation();
            if (cutsceneStartLocation != null) {
                player.teleport(cutsceneStartLocation);
            }
            introCutscene.addPlayer(player);
            messageManager.sendMessage(player, "commands.join-success-player");
            messageManager.broadcast("commands.join-success-broadcast", Placeholder.component("player", player.displayName()));
        } else if (currentPhase == GamePhase.PHASE_1_CALM || currentPhase == GamePhase.PHASE_2_AWAKENING) {
            alivePlayers.add(player.getUniqueId());
            eliminationOrder.removeIf(uuid -> uuid.equals(player.getUniqueId()));

            player.teleport(startLocation);
            player.setGameMode(GameMode.ADVENTURE);
            resetPlayerState(player);
            sanityManager.initializePlayer(player);

            messageManager.sendMessage(player, "commands.join-success-player");
            messageManager.broadcast("commands.join-success-broadcast", Placeholder.component("player", player.displayName()));
        } else {
            player.teleport(startLocation);
            player.setGameMode(GameMode.SPECTATOR);
            messageManager.sendMessage(player, "commands.join-spectator");
        }
    }

    public void resetPlayerState(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setExp(0);
        player.setLevel(0);
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttribute != null) {
            player.setHealth(maxHealthAttribute.getValue());
        } else {
            player.setHealth(20.0);
        }
        player.setFoodLevel(20);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setFireTicks(0);

        PlayerInventory inv = player.getInventory();

        inv.addItem(new ItemStack(Material.BREAD, 8));

        ItemStack sword = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta swordMeta = sword.getItemMeta();
        if (swordMeta != null) {
            swordMeta.displayName(miniMessage.deserialize("<gray><!i>Makeshift Blade</!i></gray>"));
            swordMeta.setUnbreakable(true);
            sword.setItemMeta(swordMeta);
        }
        inv.addItem(sword);

        inv.setHelmet(createNamedArmor(Material.LEATHER_HELMET, "<dark_gray><!i>Tattered Cowl</!i></dark_gray>"));
        inv.setChestplate(createNamedArmor(Material.LEATHER_CHESTPLATE, "<dark_gray><!i>Worn Tunic</!i></dark_gray>"));
        inv.setLeggings(createNamedArmor(Material.LEATHER_LEGGINGS, "<dark_gray><!i>Frayed Trousers</!i></dark_gray>"));
        inv.setBoots(createNamedArmor(Material.LEATHER_BOOTS, "<dark_gray><!i>Scuffed Boots</!i></dark_gray>"));
    }

    private ItemStack createNamedArmor(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }


    public void stopGame(boolean forcedByAdmin) {
        if (!isGameRunning()) return;

        if (introCutscene.isRunning()) {
            introCutscene.stop();
        }

        clearInheritanceChests();
        resetPuzzleBlocks();
        mobManager.reset();
        plugin.getDeathChestManager().reset();

        disconnectedPlayers.forEach((uuid, info) -> {
            List<ItemStack> allItems = new ArrayList<>();
            for (ItemStack item : info.mainInventory) {
                if (item != null && !item.getType().isAir()) allItems.add(item);
            }
            for (ItemStack item : info.armorInventory) {
                if (item != null && !item.getType().isAir()) allItems.add(item);
            }
            if (info.offHandItem != null && !info.offHandItem.getType().isAir()) {
                allItems.add(info.offHandItem);
            }

            plugin.getDeathChestManager().createDeathChest(info.playerId, info.playerName, info.logoutLocation, allItems);
        });
        disconnectedPlayers.clear();

        if (forcedByAdmin) {
            messageManager.broadcast("game-state.stop");
        } else {
            processAndDisplayRankings();
        }

        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }

        currentPhase = GamePhase.WAITING;
        pvpCountdown = -1;
        phase5TriggeredBySafety = false;

        Set<UUID> playersToReset = new HashSet<>(alivePlayers);
        for (UUID uuid : eliminationOrder) {
            playersToReset.add(uuid);
        }
        alivePlayers.clear();

        for (UUID uuid : playersToReset) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    p.setSpectatorTarget(null);
                }

                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                p.setExp(0);
                p.setLevel(0);
                AttributeInstance maxHealthAttribute = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (maxHealthAttribute != null) {
                    p.setHealth(maxHealthAttribute.getValue());
                } else {
                    p.setHealth(20.0);
                }
                p.setFoodLevel(20);
                p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
                p.setFireTicks(0);
                Location startLoc = configManager.getStartLocation();
                if (startLoc != null) p.teleport(startLoc);
            }
        }
        eliminationOrder.clear();
    }

    private void resetPuzzleBlocks() {
        Location cryptVaultLoc = configManager.getLocation("event-objects.crypt-vault");
        Location libraryPistonLoc = configManager.getLocation("event-objects.library-puzzle-piston");

        if (cryptVaultLoc != null && cryptVaultLoc.isWorldLoaded() && originalCryptVaultMaterial != Material.AIR) {
            if (cryptVaultLoc.getBlock().getType() != originalCryptVaultMaterial) {
                cryptVaultLoc.getBlock().setType(originalCryptVaultMaterial);
                plugin.getLogger().info("Reset Crypt Vault block to " + originalCryptVaultMaterial);
            }
        } else if (originalCryptVaultMaterial != Material.AIR) {
            plugin.getLogger().warning("Could not reset Crypt Vault block (Location null, world unloaded, or original was AIR).");
        }


        if (libraryPistonLoc != null && libraryPistonLoc.isWorldLoaded() && originalLibraryPistonMaterial != Material.AIR) {
            if (libraryPistonLoc.getBlock().getType() != originalLibraryPistonMaterial) {
                libraryPistonLoc.getBlock().setType(originalLibraryPistonMaterial);
                plugin.getLogger().info("Reset Library Piston block to " + originalLibraryPistonMaterial);
            }
        } else if (originalLibraryPistonMaterial != Material.AIR) {
            plugin.getLogger().warning("Could not reset Library Piston block (Location null, world unloaded, or original was AIR).");
        }

        originalCryptVaultMaterial = Material.AIR;
        originalLibraryPistonMaterial = Material.AIR;
    }

    public void setPhase(GamePhase newPhase) {
        plugin.getLogger().info("Setting phase to: " + newPhase);
        if (newPhase == GamePhase.PHASE_3_BLOOD_MOON) {
            disconnectedPlayers.values().removeIf(info -> {
                if (info.disconnectPhase == GamePhase.PHASE_1_CALM || info.disconnectPhase == GamePhase.PHASE_2_AWAKENING) {
                    plugin.getLogger().info("Phase 3 starting. Dropping items for disconnected player: " + info.playerName + " (from " + info.disconnectPhase.name() + ")");

                    List<ItemStack> allItems = new ArrayList<>();
                    for (ItemStack item : info.mainInventory) {
                        if (item != null && !item.getType().isAir()) allItems.add(item);
                    }
                    for (ItemStack item : info.armorInventory) {
                        if (item != null && !item.getType().isAir()) allItems.add(item);
                    }
                    if (info.offHandItem != null && !info.offHandItem.getType().isAir()) {
                        allItems.add(info.offHandItem);
                    }

                    plugin.getDeathChestManager().createDeathChest(info.playerId, info.playerName, info.logoutLocation, allItems);
                    return true;
                }
                return false;
            });
        }
        this.currentPhase = newPhase;
        this.timeLeftInPhase = configManager.getPhaseDuration(newPhase);
        if (newPhase == GamePhase.PHASE_4_RAMPAGE) {
            this.rampageTotalDuration = this.timeLeftInPhase;
            this.phase5TriggeredBySafety = false;
        }

        String phaseKey = "";

        switch (newPhase) {
            case INTRO_CUTSCENE -> phaseKey = "game-state.intro";
            case PHASE_1_CALM -> phaseKey = "game-state.phase-1";
            case PHASE_2_AWAKENING -> phaseKey = "game-state.phase-2";
            case PHASE_3_BLOOD_MOON -> phaseKey = "game-state.phase-3";
            case PHASE_4_RAMPAGE -> {
                phaseKey = "game-state.phase-4-rampage";
                if (sanctuaryArea != null) {
                    Location sanctuaryCenter = sanctuaryArea.getCenter();
                    maxDistanceSquared = 0;
                    for (UUID uuid : alivePlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            maxDistanceSquared = Math.max(maxDistanceSquared, p.getLocation().distanceSquared(sanctuaryCenter));
                        }
                    }
                    if (maxDistanceSquared <= 0) maxDistanceSquared = 1.0;
                } else {
                    plugin.getLogger().warning("Rampage started but sanctuary 'church' area not defined!");
                }
            }
            case PHASE_5_LMS -> phaseKey = "game-state.phase-5-lms";
            default -> { return; }
        }

        if (phaseKey.isEmpty() || newPhase == GamePhase.WAITING) return;

        Component title = messageManager.getMessage(phaseKey + ".title");
        Component subtitle = messageManager.getMessage(phaseKey + ".subtitle");
        Title.Times times = Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(2));
        Title phaseTitle = Title.title(title, subtitle, times);

        for(Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(phaseTitle);
        }

        List<String> broadcastLines = configManager.getMessagesConfig().getStringList(phaseKey + ".broadcast");
        if (broadcastLines != null && !broadcastLines.isEmpty()) {
            for (String line : broadcastLines) {
                Bukkit.broadcast(miniMessage.deserialize(line));
            }
        } else {
            plugin.getLogger().warning("No broadcast messages found for phase key: " + phaseKey);
        }

        if (newPhase == GamePhase.PHASE_5_LMS) {
            plugin.getLogger().info("Setting PvP countdown.");
            this.pvpCountdown = configManager.getPvpCountdownSeconds();
            mobManager.spawnHarbingerVisual();
        }
    }

    public void advancePhase() {
        plugin.getLogger().info("advancePhase() called. Current phase: " + currentPhase);
        if (currentPhase == GamePhase.PHASE_5_LMS) {
            plugin.getLogger().info("advancePhase called, but game is already in Phase 5. Ignoring.");
            return;
        }

        if (currentPhase == GamePhase.PHASE_4_RAMPAGE) {
        }

        GamePhase nextPhase = currentPhase.next();
        plugin.getLogger().info("Next phase determined: " + nextPhase);
        if (nextPhase == GamePhase.WAITING) {
            if (alivePlayers.size() != 1) {
                messageManager.broadcast("game-state.no-winner");
            }
            stopGame(false);
        } else {
            setPhase(nextPhase);
        }
    }

    public void tick() {
        if (!isGameRunning()) return;

        if (currentPhase != GamePhase.INTRO_CUTSCENE && alivePlayers.size() <= 1 && currentPhase.ordinal() >= GamePhase.PHASE_3_BLOOD_MOON.ordinal()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isGameRunning()) {
                    plugin.getLogger().info("Win condition (<=1 player) detected during tick. Stopping game.");
                    stopGame(false);
                }
            }, 1L);
            return;
        }

        if (currentPhase == GamePhase.INTRO_CUTSCENE) {
        } else {
            sanityManager.tick();
            mobManager.tick();
            ritualManager.tick();
            plugin.getDeathChestManager().tick();
        }

        if (currentPhase == GamePhase.PHASE_2_AWAKENING) {
            long currentTime = System.currentTimeMillis();
            long timeout = 120 * 1000;
            disconnectedPlayers.values().removeIf(info -> {
                if (currentTime - info.disconnectTimestamp > timeout) {
                    plugin.getLogger().info("Disconnect timer expired for " + info.playerId + ". Creating Death Chest.");

                    List<ItemStack> allItems = new ArrayList<>();
                    for (ItemStack item : info.mainInventory) {
                        if (item != null && !item.getType().isAir()) allItems.add(item);
                    }
                    for (ItemStack item : info.armorInventory) {
                        if (item != null && !item.getType().isAir()) allItems.add(item);
                    }
                    if (info.offHandItem != null && !info.offHandItem.getType().isAir()) {
                        allItems.add(info.offHandItem);
                    }

                    plugin.getDeathChestManager().createDeathChest(info.playerId, info.playerName, info.logoutLocation, allItems);
                    return true;
                }
                return false;
            });
        }

        if (currentPhase == GamePhase.PHASE_4_RAMPAGE) {
            handleRampageTick();
        } else if (currentPhase == GamePhase.PHASE_5_LMS) {
            handleLmsTick();
        }

        if (currentPhase == GamePhase.PHASE_5_LMS) {
        } else if (timeLeftInPhase > 0) {
            timeLeftInPhase--;
        }

        if (timeLeftInPhase == 0 && currentPhase != GamePhase.PHASE_5_LMS && currentPhase != GamePhase.WAITING) {
            if (currentPhase == GamePhase.INTRO_CUTSCENE) {
                plugin.getLogger().info("Intro cutscene timer ended. Forcing stop and starting Phase 1.");
                introCutscene.stop();
                beginPhase1();
            } else if (!phase5TriggeredBySafety || currentPhase != GamePhase.PHASE_4_RAMPAGE) {
                if (currentPhase == GamePhase.PHASE_4_RAMPAGE) {
                    plugin.getLogger().info("Rampage timer reached zero naturally.");
                    endRampage();
                } else {
                    plugin.getLogger().info("Phase " + currentPhase + " timer ended. Advancing phase.");
                    advancePhase();
                }
            } else {
                plugin.getLogger().info("Phase 4 timer hit zero, but safety already triggered advance. Ignoring timer advance.");
            }
        }
    }


    private void handleRampageTick() {
        if (sanctuaryArea == null) return;
        if (phase5TriggeredBySafety) return;

        boolean allPlayersSafe = true;
        boolean applyEffects = timeLeftInPhase <= (rampageTotalDuration - rampageGracePeriod);

        for (UUID uuid : alivePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && !sanctuaryArea.isInArea(player.getLocation())) {
                allPlayersSafe = false;
                break;
            }
        }

        for (UUID uuid : alivePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            if (!sanctuaryArea.isInArea(player.getLocation())) {
                if (applyEffects) {
                    applyRampageEffects(player);
                }
            } else {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
            }
        }

        if (allPlayersSafe && !alivePlayers.isEmpty()) {
            if (currentPhase == GamePhase.PHASE_4_RAMPAGE) {
                plugin.getLogger().info("All players are safe. Immediately advancing to Phase 5.");
                messageManager.broadcast("game-state.rampage-all-safe");

                phase5TriggeredBySafety = true;

                advancePhase();
            }
        }
    }


    private void applyRampageEffects(Player player) {
        if (sanctuaryArea == null) return;
        Location sanctuaryCenter = sanctuaryArea.getCenter();
        double distanceSquared = player.getLocation().distanceSquared(sanctuaryCenter);
        double distanceIntensity = Math.min(1.0, distanceSquared / maxDistanceSquared);

        ConfigurationSection rampageConfig = configManager.getConfig().getConfigurationSection("game-settings.rampage");
        if (rampageConfig == null) return;

        int maxSlownessAmplifier = rampageConfig.getInt("slowness-intensity", 4);
        double effectDuration = rampageTotalDuration - rampageGracePeriod;
        double timeSinceEffectsStarted = (rampageTotalDuration - timeLeftInPhase) - rampageGracePeriod;
        double progress = 0.0;
        if (effectDuration > 0 && timeSinceEffectsStarted > 0) {
            progress = Math.min(1.0, timeSinceEffectsStarted / effectDuration);
        }
        int currentSlownessAmplifier = (int) Math.floor(progress * (maxSlownessAmplifier + 1));
        currentSlownessAmplifier = Math.min(currentSlownessAmplifier, maxSlownessAmplifier);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, currentSlownessAmplifier, true, false));

        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, true, false));

        if (random.nextDouble() < rampageConfig.getDouble("scary-sound-chance", 0.15)) {
            player.playSound(player.getLocation(), Sound.ENTITY_GHAST_SCREAM, SoundCategory.AMBIENT, 0.5f, 2.0f);
        }

        if (random.nextDouble() < rampageConfig.getDouble("jumpscare-chance", 0.10)) {
            Location inFront = player.getEyeLocation().add(player.getLocation().getDirection().multiply(3));
            inFront.setY(Math.max(player.getLocation().getY(), player.getEyeLocation().getY() - 0.5));
            ArmorStand ghost = player.getWorld().spawn(inFront, ArmorStand.class, armorStand -> {
                armorStand.setInvisible(true);
                armorStand.setGravity(false);
                armorStand.setMarker(true);

                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                if (skull.getItemMeta() instanceof SkullMeta meta) {
                    PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                    PlayerTextures textures = profile.getTextures();
                    String textureValue = rampageConfig.getString("jumpscare-head-texture");
                    if (textureValue != null && !textureValue.isEmpty()) {
                        try {
                            textures.setSkin(URI.create("https://textures.minecraft.net/texture/" + textureValue).toURL());
                            profile.setTextures(textures);
                            meta.setPlayerProfile(profile);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Invalid jumpscare head texture value in config!");
                        }
                    }
                    skull.setItemMeta(meta);

                    EntityEquipment equipment = armorStand.getEquipment();
                    if (equipment != null) {
                        equipment.setHelmet(skull);
                    }
                }
            });


            try {
                Sound sound = Sound.valueOf(rampageConfig.getString("jumpscare-sound", "ENTITY_WITHER_HURT").toUpperCase());
                player.playSound(ghost.getLocation(), sound, 1.0f, 0.5f);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid jumpscare sound in config!");
            }

            Bukkit.getScheduler().runTaskLater(plugin, ghost::remove, 15L);
        }
    }

    private void endRampage() {
        if (sanctuaryArea == null) {
            plugin.getLogger().severe("Cannot end rampage: Sanctuary area not defined!");
            stopGame(false);
            return;
        }
        if (phase5TriggeredBySafety) {
            plugin.getLogger().info("endRampage called, but Phase 5 already triggered by safety. Skipping eliminations.");
            return;
        }
        plugin.getLogger().info("Rampage timer ended. Processing eliminations for players outside sanctuary...");

        List<Player> playersToEliminate = new ArrayList<>();
        for (UUID uuid : new HashSet<>(alivePlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && !sanctuaryArea.isInArea(player.getLocation())) {
                playersToEliminate.add(player);
            }
        }

        if (!playersToEliminate.isEmpty()) {
            plugin.getLogger().info("Found " + playersToEliminate.size() + " players outside sanctuary.");

            for (Player player : playersToEliminate) {
                if (player != null && player.isOnline() && alivePlayers.contains(player.getUniqueId())) {
                    plugin.getLogger().info("Eliminating " + player.getName() + " for being outside sanctuary at Rampage end.");

                    player.getWorld().strikeLightningEffect(player.getLocation());
                    player.damage(rampageDamageAmount * 20);
                }
            }
            plugin.getLogger().info("Finished triggering eliminations for " + playersToEliminate.size() + " players.");

        } else {
            plugin.getLogger().info("No players were outside the sanctuary.");
        }


        phase5TriggeredBySafety = true;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getLogger().info("Post-endRampage check. Players alive: " + alivePlayers.size());
            if (!isGameRunning()) {
                plugin.getLogger().info("Game already stopped during endRampage processing. Skipping phase advance.");
                return;
            }

            if (!alivePlayers.isEmpty()) {
                plugin.getLogger().info("Players remain. Advancing to Phase 5 from endRampage.");
                advancePhase();
            } else {
                plugin.getLogger().info("No players left after Rampage eliminations. Ending game.");
                stopGame(false);
            }
        }, 5L);
    }


    public void createInheritanceChest(Player player, List<ItemStack> itemsToTransfer) {
        Location chestLoc = findEmptySpotInSanctuary();
        if (chestLoc == null) {
            plugin.getLogger().warning("Could not find empty spot in sanctuary for inheritance chest for " + player.getName() + ". Dropping items at start.");
            Location startLoc = configManager.getStartLocation();
            if (startLoc == null || !startLoc.isWorldLoaded()) return;

            for (ItemStack item : itemsToTransfer) {
                if (item != null && !item.getType().isAir()) {
                    startLoc.getWorld().dropItemNaturally(startLoc, item.clone());
                }
            }
            return;
        }

        Block chestBlock = chestLoc.getBlock();
        chestBlock.setType(Material.CHEST);
        if (chestBlock.getState() instanceof Chest chestState) {

            if (itemsToTransfer.size() > chestState.getInventory().getSize()) {
                plugin.getLogger().warning("Player " + player.getName() + " inventory (" + itemsToTransfer.size() + " items) exceeds inheritance chest capacity. Dropping excess items.");
                for(int i = 0; i < chestState.getInventory().getSize(); i++) {
                    chestState.getInventory().setItem(i, itemsToTransfer.get(i));
                }
                for (int i = chestState.getInventory().getSize(); i < itemsToTransfer.size(); i++) {
                    chestLoc.getWorld().dropItemNaturally(chestLoc.clone().add(0.5, 0.5, 0.5), itemsToTransfer.get(i));
                }
            } else {
                chestState.getInventory().setContents(itemsToTransfer.toArray(new ItemStack[0]));
            }

            chestState.update();
            inheritanceChests.add(chestLoc.getBlock().getLocation());

            Location hologramLoc = chestLoc.clone().add(0.5, 1.2, 0.5);
            ArmorStand hologram = hologramLoc.getWorld().spawn(hologramLoc, ArmorStand.class, holo -> {
                holo.setGravity(false);
                holo.setInvisible(true);
                holo.setMarker(true);
                Component hologramName = messageManager.getMessage("holograms.inheritance-chest", Placeholder.unparsed("player", player.getName()));
                holo.customName(hologramName);
                holo.setCustomNameVisible(true);
            });

            inheritanceHolograms.put(chestLoc.getBlock().getLocation(), hologram);

        } else {
            plugin.getLogger().severe("Failed to place inheritance chest at " + chestLoc + ". Block state is not a Chest! Dropping items instead.");
            Location startLoc = configManager.getStartLocation();
            if (startLoc == null || !startLoc.isWorldLoaded()) return;
            for (ItemStack item : itemsToTransfer) {
                if (item != null && !item.getType().isAir()) {
                    startLoc.getWorld().dropItemNaturally(startLoc, item.clone());
                }
            }
        }
    }

    private Location findEmptySpotInSanctuary() {
        if (sanctuaryArea == null) return null;
        Location center = sanctuaryArea.getCenter();
        World world = center.getWorld();
        if (world == null) return null;

        List<Location> potentialSpots = new ArrayList<>();
        int targetY = -46;

        int minX = sanctuaryArea.minX(); int maxX = sanctuaryArea.maxX();
        int minZ = sanctuaryArea.minZ(); int maxZ = sanctuaryArea.maxZ();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Location loc = new Location(world, x, targetY, z);
                if (!sanctuaryArea.isInArea(loc)) continue;

                Block blockAt = loc.getBlock();
                Block blockBelow = loc.clone().add(0, -1, 0).getBlock();
                Block blockAbove = loc.clone().add(0, 1, 0).getBlock();

                if (blockAt.getType().isAir() && !blockAt.isLiquid() &&
                        blockBelow.getType().isSolid() && !blockBelow.getType().isInteractable() &&
                        blockAbove.getType().isAir() && !blockAbove.isLiquid() )
                {
                    potentialSpots.add(loc);
                }
            }
        }

        Collections.shuffle(potentialSpots);

        for(Location loc : potentialSpots) {
            if (!inheritanceChests.contains(loc.getBlock().getLocation())) {
                return loc;
            }
        }

        plugin.getLogger().warning("Could not find any suitable spot inside sanctuary at Y=" + targetY + " for inheritance chest.");
        return null;
    }


    private void clearInheritanceChests() {
        plugin.getLogger().info("Clearing inheritance chests and holograms...");
        for (ArmorStand hologram : inheritanceHolograms.values()) {
            if (hologram != null && hologram.isValid()) {
                hologram.remove();
            }
        }
        inheritanceHolograms.clear();

        for (Location loc : inheritanceChests) {
            if (loc.isWorldLoaded()) {
                Block block = loc.getBlock();
                if(block.getType() == Material.CHEST){
                    block.setType(Material.AIR);
                }
            }
        }
        inheritanceChests.clear();
        plugin.getLogger().info("Finished clearing inheritance chests.");
    }


    private void handleLmsTick() {
        plugin.getLogger().fine("handleLmsTick called. Countdown: " + pvpCountdown);
        if (pvpCountdown > 0) {
            if (pvpCountdown <= 5 || pvpCountdown == 10) {
                Component countdownMsg = messageManager.getMessage("final-battle.countdown", Placeholder.unparsed("seconds", String.valueOf(pvpCountdown)));
                for (UUID uuid : alivePlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.sendMessage(countdownMsg);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
                    }
                }
            }
            pvpCountdown--;
            if (pvpCountdown == 0) {
                messageManager.broadcast("final-battle.start");
                for (UUID uuid : alivePlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
                    }
                }
                plugin.getLogger().info("PvP enabled in Phase 5.");
            }
        }
        mobManager.handleHarbingerAttack(currentPhase);
    }

    public void eliminatePlayer(Player player) {
        if (!alivePlayers.contains(player.getUniqueId())) return;

        alivePlayers.remove(player.getUniqueId());
        eliminationOrder.add(0, player.getUniqueId());

        plugin.getLogger().info(player.getName() + " eliminated. " + alivePlayers.size() + " players remain.");
    }

    public void handlePlayerDisconnect(Player player) {
        if (currentPhase != GamePhase.PHASE_1_CALM && currentPhase != GamePhase.PHASE_2_AWAKENING) return;
        if (disconnectedPlayers.containsKey(player.getUniqueId())) return;

        plugin.getLogger().info("Player " + player.getName() + " disconnected during " + currentPhase.name() + ". Saving inventory.");
        disconnectedPlayers.put(player.getUniqueId(), new DisconnectedPlayerInfo(player, currentPhase));
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
    }

    public void dropPlayerInventory(Player player) {
        plugin.getLogger().info("Creating Death Chest for " + player.getName() + " at " + player.getLocation());

        List<ItemStack> allItems = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) allItems.add(item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) allItems.add(item);
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            allItems.add(offhand);
        }

        plugin.getDeathChestManager().createDeathChest(player, player.getLocation(), allItems);

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
    }

    private void dropAllItems(Location loc, ItemStack[] main, ItemStack[] armor, ItemStack offhand) {
        if (loc == null || !loc.isWorldLoaded()) {
            loc = configManager.getStartLocation();
            if (loc == null || !loc.isWorldLoaded()) return;
        }
        World world = loc.getWorld();
        if (world == null) return;

        for (ItemStack item : main) {
            if (item != null && !item.getType().isAir()) world.dropItemNaturally(loc, item);
        }
        for (ItemStack item : armor) {
            if (item != null && !item.getType().isAir()) world.dropItemNaturally(loc, item);
        }
        if (offhand != null && !offhand.getType().isAir()) world.dropItemNaturally(loc, offhand);
    }


    private void processAndDisplayRankings() {
        plugin.getLogger().info("Processing final rankings...");
        List<UUID> finalRankings = new ArrayList<>();

        finalRankings.addAll(alivePlayers);
        finalRankings.addAll(eliminationOrder);


        plugin.getLogger().info("Final ranking order determined with " + finalRankings.size() + " participants.");

        displayTopRankingsInChat(finalRankings);
        saveRankingsToFile(finalRankings);
    }


    private void displayTopRankingsInChat(List<UUID> finalRankings) {
        if (alivePlayers.size() == 1) {
            UUID winnerUUID = alivePlayers.iterator().next();
            if (!finalRankings.isEmpty() && finalRankings.get(0).equals(winnerUUID)) {
                OfflinePlayer winner = Bukkit.getOfflinePlayer(winnerUUID);
                if (winner.getName() != null) {
                    messageManager.broadcast("misc.winner-announcement", Placeholder.unparsed("winner", winner.getName()));
                } else {
                    messageManager.broadcast("misc.winner-announcement-unknown");
                }
            } else {
                plugin.getLogger().warning("Winner inconsistency detected! Alive player list doesn't match ranking list head.");
                messageManager.broadcast("misc.winner-announcement-unknown");
            }
        } else {
            messageManager.broadcast("game-state.no-winner");
        }

        messageManager.broadcast("misc.rankings-header");
        int limit = Math.min(10, finalRankings.size());

        for (int i = 0; i < limit; i++) {
            UUID playerUUID = finalRankings.get(i);
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
            String playerName = player.getName() != null ? player.getName() : "Unknown ("+playerUUID.toString().substring(0, 8)+")";

            messageManager.broadcast("misc.rankings-entry",
                    Placeholder.unparsed("rank", String.valueOf(i + 1)),
                    Placeholder.unparsed("player", playerName)
            );
        }
        messageManager.broadcast("misc.rankings-footer");
    }

    private void saveRankingsToFile(List<UUID> finalRankings) {
        File rankingsDir = new File(plugin.getDataFolder(), "rankings");
        if (!rankingsDir.exists() && !rankingsDir.mkdirs()) {
            plugin.getLogger().severe("Could not create rankings directory!");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File rankingFile = new File(rankingsDir, "rankings-" + timestamp + ".txt");

        try (PrintWriter writer = new PrintWriter(rankingFile)) {
            writer.println("Hallow's End Final Rankings - " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("=============================================");
            for (int i = 0; i < finalRankings.size(); i++) {
                UUID playerUUID = finalRankings.get(i);
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
                String playerName = player.getName() != null ? player.getName() : "Unknown Player";
                writer.println("#" + (i + 1) + " - " + playerName + " (UUID: " + playerUUID + ")");
            }
            writer.println("=============================================");
            plugin.getLogger().info("Successfully saved game rankings to " + rankingFile.getName());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save rankings to file: " + e.getMessage());
        }
    }

    public String getTimeLeftFormatted() {
        if (timeLeftInPhase == -1) return "∞";
        int minutes = timeLeftInPhase / 60;
        int seconds = timeLeftInPhase % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public boolean isGameRunning() {
        return currentPhase != GamePhase.WAITING;
    }

    public boolean isPvpEnabled() {
        return currentPhase == GamePhase.PHASE_5_LMS && pvpCountdown <= 0;
    }

    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public Set<UUID> getAlivePlayers() {
        return Collections.unmodifiableSet(alivePlayers);
    }

    public RitualManager getRitualManager() {
        return ritualManager;
    }

    public DefinedArea getSanctuaryArea() {
        if (this.sanctuaryArea == null) {
            this.sanctuaryArea = configManager.getAreaById("church");
            if (this.sanctuaryArea == null) {
                plugin.getLogger().warning("getSanctuaryArea() called but 'church' area not found in config.");
            }
        }
        return this.sanctuaryArea;
    }

    public Set<Player> getPlayersInArea(DefinedArea area) {
        if (area == null) {
            return Collections.emptySet();
        }
        Set<Player> playersInArea = new HashSet<>();
        for (UUID uuid : alivePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && area.isInArea(player.getLocation())) {
                playersInArea.add(player);
            }
        }
        return playersInArea;
    }
}