package me.siwannie.hallowsend.commands;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.game.GamePhase;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class EventAdminCommand implements CommandExecutor, TabCompleter {

    private final HallowsEnd plugin;
    private final GameManager gameManager;

    public EventAdminCommand(HallowsEnd plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hallowsend.admin")) {
            plugin.getMessageManager().sendMessage(sender, "commands.no-permission");
            return true;
        }

        if (args.length == 0) {
            plugin.getMessageManager().sendMessage(sender, "commands.help");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "start":
                gameManager.startGame();
                plugin.getMessageManager().sendMessage(sender, "game-state.start");
                break;

            case "stop":
                gameManager.stopGame(true);
                plugin.getMessageManager().sendMessage(sender, "game-state.stop");
                break;

            case "reload":
                plugin.getConfigManager().reloadConfigs();
                plugin.getMessageManager().sendMessage(sender, "commands.reload");
                break;

            case "setphase":
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(sender, "commands.setphase-usage");
                    return true;
                }
                if (!gameManager.isGameRunning()) {
                    plugin.getMessageManager().sendMessage(sender, "commands.setphase-not-running");
                    return true;
                }

                String phaseName = args[1].toUpperCase();
                try {
                    GamePhase newPhase = GamePhase.valueOf(phaseName);
                    gameManager.setPhase(newPhase);
                    plugin.getMessageManager().sendMessage(sender, "commands.setphase-success",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("phase", newPhase.getDisplayName())
                    );
                } catch (IllegalArgumentException e) {
                    plugin.getMessageManager().sendMessage(sender, "commands.setphase-invalid",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("phase", phaseName)
                    );
                }
                break;

            default:
                plugin.getMessageManager().sendMessage(sender, "commands.admin-help");
                break;
        }

        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "reload", "setphase").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("setphase")) {
            return Arrays.stream(GamePhase.values())
                    .map(Enum::name)
                    .filter(s -> s.startsWith(args[1].toUpperCase()))
                    .collect(Collectors.toList());
        }

        return List.of();
    }
}