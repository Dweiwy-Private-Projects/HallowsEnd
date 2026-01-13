package me.siwannie.hallowsend.listeners;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.ConfigManager;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.game.GamePhase;
import me.siwannie.hallowsend.game.area.DefinedArea;
import me.siwannie.hallowsend.modules.mobs.MobManager;
import me.siwannie.hallowsend.modules.sanity.SanityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;

public class PlayerMoveListener implements Listener {

    private final HallowsEnd plugin;
    private final GameManager gameManager;
    private final MobManager mobManager;
    private final ConfigManager configManager;
    private final SanityManager sanityManager;

    private final Map<UUID, DefinedArea> playerAreaTracker = new HashMap<>();
    private final Map<UUID, Set<String>> playerSeenAreaTitles = new HashMap<>();

    public PlayerMoveListener(HallowsEnd plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
        this.mobManager = plugin.getMobManager();
        this.configManager = plugin.getConfigManager();
        this.sanityManager = plugin.getSanityManager();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!gameManager.isGameRunning()) return;

        Player player = event.getPlayer();
        Location to = event.getTo();
        Location from = event.getFrom();

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        GamePhase currentPhase = gameManager.getCurrentPhase();

        Collection<DefinedArea> allAreas = configManager.getAllAreas();
        DefinedArea bestArea = null;
        double smallestVolume = Double.MAX_VALUE;

        for (DefinedArea area : allAreas) {
            if (area.isInArea(to)) {
                double volume = area.getApproximateVolume();
                if (volume < smallestVolume) {
                    smallestVolume = volume;
                    bestArea = area;
                }
            }
        }

        DefinedArea lastArea = playerAreaTracker.get(player.getUniqueId());
        DefinedArea currentArea = bestArea;

        if (!Objects.equals(lastArea, currentArea)) {
            if (currentArea != null) {
                Component title = plugin.getMessageManager().miniMessage.deserialize(currentArea.title());
                String objectiveString = currentArea.getObjectiveForPhase(currentPhase);
                Component subtitle = plugin.getMessageManager().miniMessage.deserialize(objectiveString);
                Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000));
                Title areaTitle = Title.title(title, subtitle, times);

                double sanity = sanityManager.getSanity(player);
                if (sanity < 30.0) {
                    new BukkitRunnable() {
                        private int count = 0;
                        private final Title.Times flickerTimes = Title.Times.times(Duration.ofMillis(0), Duration.ofMillis(200), Duration.ofMillis(100));
                        private final Title emptyTitle = Title.title(Component.empty(), Component.empty(), flickerTimes);
                        private final Title flickerTitle = Title.title(title, subtitle, flickerTimes);

                        @Override
                        public void run() {
                            if (count >= 5 || !player.isOnline() || !gameManager.isGameRunning()) {
                                this.cancel();
                                return;
                            }

                            if (count % 2 == 0) {
                                player.showTitle(flickerTitle);
                                player.playSound(player.getLocation(), Sound.ENTITY_GHAST_SCREAM, SoundCategory.AMBIENT, 0.1f, 2.0f);
                            } else {
                                player.showTitle(emptyTitle);
                            }
                            count++;
                        }
                    }.runTaskTimer(plugin, 0L, 4L);
                } else {
                    player.showTitle(areaTitle);
                }
            }
            playerAreaTracker.put(player.getUniqueId(), currentArea);
        }

        if (!gameManager.getAlivePlayers().contains(player.getUniqueId())) {
            return;
        }

        DefinedArea atticArea = configManager.getAreaById("manor-attic");
        DefinedArea witchHut = configManager.getAreaById("witch-hut");

        if (atticArea != null && atticArea.isInArea(to) && !mobManager.isManorEvokerAlive() && !mobManager.hasManorEvokerSpawned() && currentPhase.ordinal() >= GamePhase.PHASE_2_AWAKENING.ordinal()) {
            mobManager.spawnManorEvoker(to);
        }

        if (witchHut != null && witchHut.isInArea(to) && !mobManager.isWitchAlive() && !mobManager.hasWitchSpawned() && currentPhase.ordinal() >= GamePhase.PHASE_3_BLOOD_MOON.ordinal()) {
            mobManager.spawnWitch(to);
        }

        if (atticArea != null && atticArea.isInArea(from) && !atticArea.isInArea(to)) {
            if (mobManager.isManorEvokerAlive()) {
                pushPlayerBack(player, event, from, "player.boss-barrier-message");
                return;
            }
        }

        if (witchHut != null && witchHut.isInArea(from) && !witchHut.isInArea(to)) {
            if (mobManager.isWitchAlive()) {
                pushPlayerBack(player, event, from, "player.witch-barrier-message");
                return;
            }
        }

        DefinedArea sanctuaryArea = configManager.getAreaById("church");
        if ((currentPhase == GamePhase.PHASE_4_RAMPAGE || currentPhase == GamePhase.PHASE_5_LMS) && sanctuaryArea != null) {
            if (sanctuaryArea.isInArea(from) && !sanctuaryArea.isInArea(to)) {
                plugin.getLogger().fine("Player " + player.getName() + " tried to leave sanctuary during Rampage/LMS. Pushing back.");
                pushPlayerBack(player, event, from, "player.sanctuary-barrier-message");
            }
        }
    }

    private void pushPlayerBack(Player player, PlayerMoveEvent event, Location returnLocation, String messageKey) {
        event.setCancelled(true);

        Location safeReturnLocation = returnLocation.clone();

        safeReturnLocation.setPitch(event.getTo().getPitch());
        safeReturnLocation.setYaw(event.getTo().getYaw());

        if (safeReturnLocation.isWorldLoaded() && safeReturnLocation.getWorld().isChunkLoaded(safeReturnLocation.getChunk())) {
            event.setTo(safeReturnLocation);
        } else {
            plugin.getLogger().warning("pushPlayerBack: returnLocation was not loaded! " + returnLocation);
            Vector pushDirection = returnLocation.toVector().subtract(event.getTo().toVector()).normalize().multiply(0.5);
            pushDirection.setY(0);
            if (player.isOnGround()) {
                pushDirection.add(new Vector(0, 0.2, 0));
            }
            player.setVelocity(pushDirection);

            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
            player.sendActionBar(plugin.getMessageManager().getMessage(messageKey));
            return;
        }

        Vector pushDirection = returnLocation.toVector().subtract(event.getTo().toVector()).normalize().multiply(0.3);

        if (pushDirection.lengthSquared() > 0.01) {
            player.setVelocity(player.getVelocity().add(pushDirection));
        }


        player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
        player.sendActionBar(plugin.getMessageManager().getMessage(messageKey));
    }


    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerAreaTracker.remove(event.getPlayer().getUniqueId());
        playerSeenAreaTitles.remove(event.getPlayer().getUniqueId());
    }

    public void reset() {
        playerSeenAreaTitles.clear();
        playerAreaTracker.clear();
    }
}