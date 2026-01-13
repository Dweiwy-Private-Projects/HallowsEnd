package me.siwannie.hallowsend.game;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

public class IntroCutscene {

    private final HallowsEnd plugin;
    private final GameManager gameManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Random random = new Random();

    private ArmorStand camera;
    private final List<CutsceneNode> sceneNodes = new ArrayList<>();
    private final Set<Player> cutscenePlayers = new HashSet<>();
    private int currentNodeIndex = 0;
    private int sceneTick = 0;
    private int worldEnforceTick = 0;
    private BukkitTask cutsceneTask;

    private Location startInterpLoc;
    private Location endInterpLoc;
    private static final Title.Times TITLE_TICK_TIMES = Title.Times.times(Duration.ofMillis(0), Duration.ofMillis(250), Duration.ofMillis(0));
    private static final int TITLE_UPDATE_FREQUENCY = 4;

    public IntroCutscene(HallowsEnd plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.configManager = plugin.getConfigManager();
    }

    public void start(Set<Player> playersToStart) {
        Location startLoc = configManager.getCutsceneStartLocation();
        if (startLoc == null) {
            plugin.getLogger().warning("Cutscene start location not set. Skipping to Phase 1.");
            gameManager.beginPhase1();
            return;
        }

        loadNodesFromConfig();
        if (sceneNodes.isEmpty()) {
            plugin.getLogger().warning("Intro cutscene has no scenes defined. Skipping to Phase 1.");
            gameManager.beginPhase1();
            return;
        }

        this.cutscenePlayers.clear();
        this.cutscenePlayers.addAll(playersToStart);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameManager.isGameRunning() || gameManager.getCurrentPhase() != GamePhase.INTRO_CUTSCENE) {
                    return;
                }
                runCutsceneStart(startLoc);
            }
        }.runTaskLater(plugin, 20L);
    }

    private void runCutsceneStart(Location startLoc) {
        camera = startLoc.getWorld().spawn(startLoc, ArmorStand.class, armorStand -> {
            armorStand.setGravity(false);
            armorStand.setInvisible(true);
            armorStand.setInvulnerable(true);
            armorStand.setMarker(true);
        });

        for (Player player : cutscenePlayers) {
            if (player == null || !player.isOnline()) continue;

            if (!player.hasPermission(configManager.getStaffBypassPermission())) {
                player.teleport(startLoc);
                player.setGameMode(GameMode.SPECTATOR);
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (camera == null || !camera.isValid() || !gameManager.isGameRunning()) {
                    return;
                }

                for (Player player : cutscenePlayers) {
                    if (player == null || !player.isOnline()) continue;

                    if (!player.hasPermission(configManager.getStaffBypassPermission())) {
                        try {
                            player.setSpectatorTarget(camera);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to set spectator target for " + player.getName() + " after 1-tick delay.");
                        }
                    }
                }

                currentNodeIndex = 0;
                sceneTick = 0;
                startCurrentScene();

                cutsceneTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        tick();
                    }
                }.runTaskTimer(plugin, 0L, 1L);
            }
        }.runTaskLater(plugin, 1L);
    }


    private void loadNodesFromConfig() {
        List<ConfigurationSection> nodes = configManager.getCutsceneNodes();
        if (nodes == null || nodes.isEmpty()) {
            plugin.getLogger().warning("No cutscene nodes found in ConfigManager.");
            return;
        }
        sceneNodes.clear();
        for (ConfigurationSection nodeSection : nodes) {
            sceneNodes.add(new CutsceneNode(nodeSection));
        }
    }

    private void startCurrentScene() {
        if (currentNodeIndex >= sceneNodes.size()) {
            if (gameManager.isGameRunning() && gameManager.getCurrentPhase() == GamePhase.INTRO_CUTSCENE) {
                gameManager.beginPhase1();
            }
            stop();
            return;
        }

        sceneTick = 0;
        CutsceneNode node = sceneNodes.get(currentNodeIndex);

        if (node.location == null) {
            plugin.getLogger().severe("Cutscene node " + currentNodeIndex + " has a NULL location. Stopping cutscene.");
            if (gameManager.isGameRunning() && gameManager.getCurrentPhase() == GamePhase.INTRO_CUTSCENE) {
                gameManager.beginPhase1();
            }
            stop();
            return;
        }

        this.startInterpLoc = camera.getLocation().clone();
        this.endInterpLoc = node.location;

    }

    public void tick() {
        if (camera == null || !camera.isValid() || currentNodeIndex >= sceneNodes.size()) {
            if (gameManager.isGameRunning() && gameManager.getCurrentPhase() == GamePhase.INTRO_CUTSCENE) {
                gameManager.beginPhase1();
            }
            stop();
            return;
        }

        if (worldEnforceTick++ % 20 == 0) {
            enforceKnownPlayerState();
        }

        playAmbientEffects();

        CutsceneNode node = sceneNodes.get(currentNodeIndex);
        int totalTicksForTravel = node.travelTime * 20;
        int totalTicksForScene = (node.travelTime + node.duration) * 20;

        if (sceneTick < totalTicksForTravel) {
            double progress = (double) sceneTick / totalTicksForTravel;
            progress = easeInOutCubic(progress);

            Location newLoc = interpolateLocation(startInterpLoc, endInterpLoc, progress);
            camera.teleport(newLoc);

            playTravelEffects();

        } else if (sceneTick == totalTicksForTravel) {
            camera.teleport(endInterpLoc);
            playArrivalEffects(node);

        } else if (sceneTick >= totalTicksForScene) {
            currentNodeIndex++;
            startCurrentScene();
        }

        sceneTick++;
    }

    private void enforceKnownPlayerState() {
        Iterator<Player> playerIterator = cutscenePlayers.iterator();
        while (playerIterator.hasNext()) {
            Player player = playerIterator.next();
            if (player == null || !player.isOnline()) {
                playerIterator.remove();
                continue;
            }
            enforceCutsceneState(player, false);
        }
    }


    private void enforceCutsceneState(Player player, boolean isWorldCheck) {
        if (player == null || !player.isOnline() || camera == null || !camera.isValid()) {
            return;
        }

        if (!player.hasPermission(configManager.getStaffBypassPermission()) &&
                (cutscenePlayers.contains(player) || (gameManager.getCurrentPhase() == GamePhase.INTRO_CUTSCENE && player.getWorld().equals(camera.getWorld())))) {

            if (isWorldCheck && !cutscenePlayers.contains(player)) {
                addPlayer(player);
                return;
            }

            if (player.getGameMode() != GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SPECTATOR);
            }

            if (player.getSpectatorTarget() == null || !player.getSpectatorTarget().equals(camera)) {
                try {
                    player.setSpectatorTarget(camera);
                } catch (Exception e) {
                }
            }
        }
    }


    public void stop() {
        if (this.cutsceneTask != null && !this.cutsceneTask.isCancelled()) {
            this.cutsceneTask.cancel();
            this.cutsceneTask = null;
        }

        Iterator<Player> playerIterator = cutscenePlayers.iterator();
        while (playerIterator.hasNext()) {
            Player player = playerIterator.next();
            if (player == null || !player.isOnline()) {
                playerIterator.remove();
                continue;
            }

            if (player.getGameMode() == GameMode.SPECTATOR && player.getSpectatorTarget() != null) {
                if (player.getSpectatorTarget().equals(camera)) {
                    player.setSpectatorTarget(null);
                }
            }
            playerIterator.remove();
        }

        if (camera != null) {
            camera.remove();
            camera = null;
        }
    }

    public void addPlayer(Player player) {
        if (camera == null || !camera.isValid() || player.hasPermission(configManager.getStaffBypassPermission())) {
            return;
        }

        player.teleport(camera.getLocation());
        player.setGameMode(GameMode.SPECTATOR);

        cutscenePlayers.add(player);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && camera != null && camera.isValid()) {
                    try {
                        player.setSpectatorTarget(camera);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to set spectator target for late-joiner " + player.getName());
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    public boolean isRunning() {
        return cutsceneTask != null && !cutsceneTask.isCancelled();
    }

    private void playAmbientEffects() {
        if (sceneTick % 40 == 0 && camera != null) {
            camera.getWorld().playSound(camera.getLocation(), Sound.ENTITY_GHAST_AMBIENT, SoundCategory.AMBIENT, 0.2f, 0.5f);

        }
    }

    private void playTravelEffects() {
        if (camera == null) return;

        if (sceneTick % 10 == 0) {
            camera.getWorld().playSound(camera.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, SoundCategory.AMBIENT, 0.3f, 1.5f);
        }
    }

    private void playArrivalEffects(CutsceneNode node) {
        if (camera == null) return;

        camera.getWorld().playSound(camera.getLocation(), Sound.BLOCK_BELL_RESONATE, SoundCategory.MASTER, 1.0f, 0.5f);
        camera.getWorld().playSound(camera.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 0.3f, 0.5f);

        Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(node.duration), Duration.ofMillis(500));
        Title t = Title.title(miniMessage.deserialize(node.title), miniMessage.deserialize(node.subtitle), times);

        Component chatMessage = miniMessage.deserialize(node.chat);
        boolean hasChat = node.chat != null && !node.chat.isEmpty();

        for (Player player : cutscenePlayers) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.showTitle(t);
                if (hasChat) {
                    player.sendMessage(chatMessage);
                }
            }
        }
    }

    private Location interpolateLocation(Location start, Location end, double progress) {
        double x = start.getX() + (end.getX() - start.getX()) * progress;
        double y = start.getY() + (end.getY() - start.getY()) * progress;
        double z = start.getZ() + (end.getZ() - start.getZ()) * progress;

        double startYaw = start.getYaw();
        double endYaw = end.getYaw();
        double yawDiff = endYaw - startYaw;
        while (yawDiff < -180) yawDiff += 360;
        while (yawDiff >= 180) yawDiff -= 360;
        float yaw = (float) (startYaw + yawDiff * progress);

        float pitch = (float) (start.getPitch() + (end.getPitch() - start.getPitch()) * progress);

        return new Location(start.getWorld(), x, y, z, yaw, pitch);
    }

    private double easeInOutCubic(double x) {
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }

    private static class CutsceneNode {
        Location location;
        String title;
        String subtitle;
        String chat;
        String plainTitle;
        String plainSubtitle;
        int travelTime;
        int duration;

        CutsceneNode(ConfigurationSection nodeSection) {
            this.location = nodeSection.getLocation("location");

            this.title = nodeSection.getString("title", "");
            this.subtitle = nodeSection.getString("subtitle", "");
            this.chat = nodeSection.getString("chat", "");

            this.plainTitle = MiniMessage.miniMessage().stripTags(this.title);
            this.plainSubtitle = MiniMessage.miniMessage().stripTags(this.subtitle);

            this.travelTime = nodeSection.getInt("travel-time", 5);
            this.duration = nodeSection.getInt("duration", 5);

            if (this.location == null) {
                HallowsEnd.getInstance().getLogger().warning("Failed to load location for a cutscene node in config.yml. Path: " + nodeSection.getCurrentPath());
            }
        }
    }
}