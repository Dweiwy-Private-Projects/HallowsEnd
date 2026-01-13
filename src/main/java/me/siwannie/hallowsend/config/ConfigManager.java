package me.siwannie.hallowsend.config;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.game.GamePhase;
import me.siwannie.hallowsend.game.area.DefinedArea;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final HallowsEnd plugin;
    private FileConfiguration pluginConfig;
    private FileConfiguration messagesConfig;
    private final List<DefinedArea> definedAreas = new ArrayList<>();
    private final List<ConfigurationSection> cutsceneNodes = new ArrayList<>();

    public ConfigManager(HallowsEnd plugin) {
        this.plugin = plugin;
        loadConfigs();
    }

    public void loadConfigs() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        pluginConfig = plugin.getConfig();

        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        loadDefinedAreas();
        loadCutsceneNodes();
    }

    public void reloadConfigs() {
        plugin.reloadConfig();
        pluginConfig = plugin.getConfig();
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        loadDefinedAreas();
        loadCutsceneNodes();
        plugin.getMobManager().loadCustomMobs();
        plugin.getMessageManager().loadMessages();
        plugin.getLootChestManager().loadLootChests();
    }

    private void loadDefinedAreas() {
        definedAreas.clear();
        ConfigurationSection areaSection = pluginConfig.getConfigurationSection("scoreboard-areas");
        if (areaSection == null) return;

        for (String key : areaSection.getKeys(false)) {
            try {
                String title = areaSection.getString(key + ".title");

                Map<GamePhase, String> objectives = new EnumMap<>(GamePhase.class);
                String defaultObjective = "<gray>Survive...</gray>";

                ConfigurationSection objSection = areaSection.getConfigurationSection(key + ".objectives");
                if (objSection != null) {
                    for (String phaseKey : objSection.getKeys(false)) {
                        if (phaseKey.equalsIgnoreCase("DEFAULT")) {
                            defaultObjective = objSection.getString(phaseKey);
                        } else {
                            try {
                                GamePhase phase = GamePhase.valueOf(phaseKey.toUpperCase());
                                objectives.put(phase, objSection.getString(phaseKey));
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Invalid GamePhase '" + phaseKey + "' in objectives for area '" + key + "'.");
                            }
                        }
                    }
                } else {
                    String oldObjective = areaSection.getString(key + ".objective");
                    if (oldObjective != null) {
                        defaultObjective = oldObjective;
                        plugin.getLogger().warning("Area '" + key + "' is using the old 'objective' format. Please update to 'objectives' section for phase-specific text.");
                    } else {
                        plugin.getLogger().warning("Area '" + key + "' is missing 'objectives' section. Using fallback default.");
                    }
                }

                String worldName = areaSection.getString(key + ".world");
                World world = Bukkit.getWorld(worldName);

                if (world == null) {
                    plugin.getLogger().warning("Could not load area '" + key + "': World '" + worldName + "' not found.");
                    continue;
                }

                int minY = areaSection.getInt(key + ".y-min");
                int maxY = areaSection.getInt(key + ".y-max");

                List<DefinedArea.Point> corners = new ArrayList<>();
                List<Map<?, ?>> cornerMaps = areaSection.getMapList(key + ".corners");
                for (Map<?, ?> cornerMap : cornerMaps) {
                    int x = ((Number) cornerMap.get("x")).intValue();
                    int z = ((Number) cornerMap.get("z")).intValue();
                    corners.add(new DefinedArea.Point(x, z));
                }

                if (!corners.isEmpty()) {
                    definedAreas.add(new DefinedArea(key, title, objectives, defaultObjective, world, minY, maxY, corners));
                } else {
                    plugin.getLogger().warning("Could not load area '" + key + "'. It has no corners defined.");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error loading area '" + key + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void loadCutsceneNodes() {
        cutsceneNodes.clear();
        ConfigurationSection nodesSection = pluginConfig.getConfigurationSection("game-settings.cutscene-nodes");
        if (nodesSection == null) {
            plugin.getLogger().warning("No cutscene nodes found in config.yml under 'game-settings.cutscene-nodes'");
            return;
        }

        for (String key : nodesSection.getKeys(false)) {
            ConfigurationSection nodeSection = nodesSection.getConfigurationSection(key);
            if (nodeSection != null) {
                cutsceneNodes.add(nodeSection);
            } else {
                plugin.getLogger().warning("Cutscene node '" + key + "' is not a valid section. Skipping.");
            }
        }

        if (cutsceneNodes.isEmpty()) {
            plugin.getLogger().warning("Found 'cutscene-nodes' section, but it contains no valid nodes.");
        }
    }

    public List<ConfigurationSection> getCutsceneNodes() {
        return Collections.unmodifiableList(cutsceneNodes);
    }

    public DefinedArea getAreaByLocation(Location location) {
        DefinedArea smallestArea = null;
        double smallestVolume = Double.MAX_VALUE;

        for (DefinedArea area : definedAreas) {
            if (area.isInArea(location)) {
                double volume = area.getApproximateVolume();
                if (volume < smallestVolume) {
                    smallestVolume = volume;
                    smallestArea = area;
                }
            }
        }
        return smallestArea;
    }

    public DefinedArea getAreaById(String id) {
        for (DefinedArea area : definedAreas) {
            if (area.id().equalsIgnoreCase(id)) {
                return area;
            }
        }
        return null;
    }

    public Location getLocation(String path) {
        return pluginConfig.getLocation(path);
    }

    public boolean isResourcePackEnabled() {
        return pluginConfig.getBoolean("resource-pack.enabled", false);
    }

    public boolean isResourcePackRequired() {
        return pluginConfig.getBoolean("resource-pack.required", true);
    }

    public String getResourcePackUrl() {
        return pluginConfig.getString("resource-pack.url", "");
    }

    public String getResourcePackHash() {
        return pluginConfig.getString("resource-pack.hash", "");
    }

    public int getPhaseDuration(GamePhase phase) {
        String phaseName = phase.name().toLowerCase();
        String path = "game-settings.phase-durations." + phaseName;

        if (!pluginConfig.contains(path)) {
            if (phase == GamePhase.INTRO_CUTSCENE) {
                plugin.getLogger().warning("No duration found for 'intro_cutscene'. Defaulting to 60s.");
            }
            return phase.getDurationSeconds();
        }

        return pluginConfig.getInt(path, phase.getDurationSeconds());
    }
    public Location getFinalBattleTeleportLocation() {
        return pluginConfig.getLocation("game-settings.final-battle.teleport-location");
    }

    public int getPvpCountdownSeconds() {
        return pluginConfig.getInt("game-settings.final-battle.pvp-countdown-seconds", 10);
    }

    public Location getStartLocation() {
        return pluginConfig.getLocation("game-settings.start-location");
    }

    public Location getCutsceneStartLocation() {
        return pluginConfig.getLocation("game-settings.cutscene-start-location");
    }

    public String getStaffBypassPermission() {
        return pluginConfig.getString("game-settings.staff-bypass-permission", "hallowsend.admin");
    }

    public FileConfiguration getConfig() {
        return pluginConfig;
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public List<DefinedArea> getAllAreas() {
        return Collections.unmodifiableList(definedAreas);
    }
}
