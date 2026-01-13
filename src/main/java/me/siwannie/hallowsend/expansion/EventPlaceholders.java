package me.siwannie.hallowsend.expansion;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.ConfigManager;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.game.GamePhase;
import me.siwannie.hallowsend.game.area.DefinedArea;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import java.util.Comparator;

public class EventPlaceholders extends PlaceholderExpansion {

    private final HallowsEnd plugin;
    private final GameManager gameManager;
    private final ConfigManager configManager;
    private final RitualManager ritualManager;

    public EventPlaceholders(HallowsEnd plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
        this.configManager = plugin.getConfigManager();
        this.ritualManager = plugin.getRitualManager();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hallowsend";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Siwannie";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }

        if (!gameManager.isGameRunning()) {
            switch (identifier) {
                case "phase":
                    return "Waiting";
                case "time_left":
                    return "00:00";
                case "players_alive":
                    return "0";
                case "artifacts_found_display":
                    return "0 / 4";
                case "location_name":
                    return "Hallow's End";
                case "location_objective":
                    return "Waiting for event...";
                case "location_playercount":
                    return "N/A";
                default:
                    return null;
            }
        }

        GamePhase currentPhase = gameManager.getCurrentPhase();

        switch (identifier) {
            case "phase":
                return currentPhase.getDisplayName();
            case "time_left":
                return gameManager.getTimeLeftFormatted();
            case "players_alive":
                return String.valueOf(gameManager.getAlivePlayers().size());
            case "artifacts_found_display":
                return ritualManager.getFoundArtifactsCount() + " / 4";
        }

        DefinedArea currentArea = configManager.getAllAreas().stream()
                .filter(area -> area.isInArea(player.getLocation()))
                .min(Comparator.comparingLong(DefinedArea::getApproximateVolume))
                .orElse(null);

        if (currentArea != null) {
            switch (identifier) {
                case "location_name":
                    return currentArea.title();
                case "location_objective":
                    return currentArea.getObjectiveForPhase(currentPhase);
                case "location_playercount":
                    return String.valueOf(gameManager.getPlayersInArea(currentArea).size());
            }
        } else {
            switch (identifier) {
                case "location_name":
                    return "Hallow's End";
                case "location_objective":
                    switch (currentPhase) {
                        case PHASE_1_CALM:
                            return "Scavenge the map for supplies.";
                        case PHASE_2_AWAKENING:
                            return "Survive the horde and gear up.";
                        case PHASE_3_BLOOD_MOON:
                            return "Obtain artifacts.";
                        case PHASE_4_RAMPAGE:
                            return "Seek shelter in the church!";
                        case PHASE_5_LMS:
                            return "The final battle is underway.";
                        case WAITING:
                        default:
                            return "Explore the eerie village...";
                    }
                case "location_playercount":
                    return "N/A";
            }
        }

        return null;
    }
}

