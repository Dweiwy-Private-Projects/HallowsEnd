package me.siwannie.hallowsend.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Random;

public class MessageManager {
    private final ConfigManager configManager;
    public final MiniMessage miniMessage;
    private String prefix;
    private final Random random = new Random();

    public MessageManager(ConfigManager configManager) {
        this.configManager = configManager;
        this.miniMessage = MiniMessage.miniMessage();
        loadMessages();
    }

    public void loadMessages() {
        FileConfiguration messages = configManager.getMessagesConfig();
        this.prefix = messages.getString("prefix", "<gray>[<gradient:#ffc300:#c70039>Hallow's End</gradient>]</gray> ");
    }

    public Component getMessage(String key, TagResolver... placeholders) {
        String defaultValue = "<red>Missing message: " + key + "</red>";
        return getMessage(key, defaultValue, placeholders);
    }

    public Component getMessage(String key, String defaultValue, TagResolver... placeholders) {
        String messageFormat = configManager.getMessagesConfig().getString(key, defaultValue);
        return miniMessage.deserialize(messageFormat, placeholders);
    }

    public Component getPrefixedMessage(String key, TagResolver... placeholders) {
        String defaultValue = "<red>Missing message: " + key + "</red>";
        return getPrefixedMessage(key, defaultValue, placeholders);
    }

    public Component getPrefixedMessage(String key, String defaultValue, TagResolver... placeholders) {
        String messageFormat = configManager.getMessagesConfig().getString(key, defaultValue);
        return miniMessage.deserialize(prefix + messageFormat, placeholders);
    }

    public void sendMessage(CommandSender sender, String key, TagResolver... placeholders) {
        sender.sendMessage(getPrefixedMessage(key, placeholders));
    }

    public void broadcast(String key, TagResolver... placeholders) {
        Component message = getPrefixedMessage(key, placeholders);
        Bukkit.broadcast(message);
    }

    public void broadcastRandomFromList(String key, TagResolver... placeholders) {
        List<String> messages = configManager.getMessagesConfig().getStringList(key);
        if (messages == null || messages.isEmpty()) {
            broadcast(key, placeholders);
            return;
        }

        String messageFormat = messages.get(random.nextInt(messages.size()));
        Component message = miniMessage.deserialize(prefix + messageFormat, placeholders);
        Bukkit.broadcast(message);
    }

    public String serialize(Component component) {
        if (component == null) return "";
        return miniMessage.serialize(component);
    }
}