package me.siwannie.hallowsend.modules.sanity;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.MessageManager;
import me.siwannie.hallowsend.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SanityManager {

    private final HallowsEnd plugin;
    private final Map<UUID, Double> playerSanity = new HashMap<>();
    private static final double MAX_SANITY = 100.0;
    private static final double DRAIN_RATE = 1.0;
    private static final double REGEN_RATE = 0.5;

    public SanityManager(HallowsEnd plugin) {
        this.plugin = plugin;
    }

    public void initializePlayer(Player player) {
        playerSanity.put(player.getUniqueId(), MAX_SANITY);
    }

    public void removePlayer(Player player) {
        playerSanity.remove(player.getUniqueId());
    }

    public double getSanity(Player player) {
        return playerSanity.getOrDefault(player.getUniqueId(), MAX_SANITY);
    }

    public void tick() {
        GameManager gameManager = plugin.getGameManager();
        if(gameManager == null) return;

        for (UUID uuid : gameManager.getAlivePlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;

            double currentSanity = getSanity(player);
            Block block = player.getLocation().getBlock();

            int skyLight = block.getLightFromSky();
            int blockLight = block.getLightFromBlocks();

            if (skyLight == 15 || blockLight >= 3) {
                currentSanity += REGEN_RATE;
            } else {
                currentSanity -= DRAIN_RATE;
            }

            currentSanity = Math.max(0, Math.min(MAX_SANITY, currentSanity));
            playerSanity.put(uuid, currentSanity);

            applySanityEffects(player, currentSanity);
            sendSanityActionBar(player, (int) currentSanity);
        }
    }

    private void applySanityEffects(Player player, double sanity) {
        if (sanity < 25) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
        } else if (sanity < 50) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
        }
    }

    private void sendSanityActionBar(Player p, int level) {
        int bars = Math.max(0, (int) (level / 2.5));
        Component barComponent;

        TextColor color = NamedTextColor.GREEN;
        if (level < 30) color = NamedTextColor.RED;
        else if (level < 60) color = NamedTextColor.YELLOW;

        Component filled = Component.text("|", color);
        Component empty = Component.text("|", NamedTextColor.GRAY);
        Component percentage = Component.text(" (" + level + "%)", NamedTextColor.GRAY);

        Component bar = Component.text("Sanity: ").color(NamedTextColor.WHITE);
        for (int i = 0; i < 40; i++) {
            bar = bar.append(i < bars ? filled : empty);
        }
        bar = bar.append(percentage);

        p.sendActionBar(bar);
    }

    public void resetAll() {
        playerSanity.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            initializePlayer(player);
        }
    }
}