package me.siwannie.hallowsend.listeners;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.ConfigManager;
import me.siwannie.hallowsend.config.MessageManager;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.game.GamePhase;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import me.siwannie.hallowsend.modules.sanity.SanityManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerConnectionListener implements Listener {

    private final HallowsEnd plugin;
    private final GameManager gameManager;
    private final SanityManager sanityManager;
    private final ConfigManager configManager;
    private final MessageManager messageManager;

    public PlayerConnectionListener(HallowsEnd plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
        this.sanityManager = plugin.getSanityManager();
        this.configManager = plugin.getConfigManager();
        this.messageManager = plugin.getMessageManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sanityManager.initializePlayer(player);

        if (configManager.isResourcePackEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Component prompt = messageManager.getMessage("resource-pack-prompt");
                player.setResourcePack(
                        configManager.getResourcePackUrl(),
                        configManager.getResourcePackHash(),
                        configManager.isResourcePackRequired(),
                        prompt
                );
            }, 20L);
        }

        if (gameManager.isGameRunning() || player.hasPermission(configManager.getStaffBypassPermission())) {

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {

                    if (player.hasPermission(configManager.getStaffBypassPermission())) {
                        if (gameManager.isGameRunning()) {
                            messageManager.sendMessage(player, "misc.staff-bypass");
                        }
                        return;
                    }

                    if (gameManager.isGameRunning()) {
                        gameManager.addPlayerToGame(player);
                    }
                }
            }, 60L);
        }
    }

    @EventHandler
    public void onPlayerResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();

        if (configManager.isResourcePackRequired()) {
            if (status == PlayerResourcePackStatusEvent.Status.DECLINED || status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
                Component kickMessage = messageManager.getMessage("resource-pack-kick");
                player.kick(kickMessage);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sanityManager.removePlayer(player);

        if (gameManager.isGameRunning() && gameManager.getAlivePlayers().contains(player.getUniqueId())) {
            GamePhase phase = gameManager.getCurrentPhase();

            if (phase == GamePhase.PHASE_1_CALM || phase == GamePhase.PHASE_2_AWAKENING) {

                gameManager.handlePlayerDisconnect(player);
            } else if (phase.ordinal() >= GamePhase.PHASE_3_BLOOD_MOON.ordinal()) {
                gameManager.dropPlayerInventory(player);
            }
            gameManager.eliminatePlayer(player);
        }
    }
}