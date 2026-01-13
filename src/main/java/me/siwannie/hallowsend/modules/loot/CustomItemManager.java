package me.siwannie.hallowsend.modules.loot;

import com.destroystokyo.paper.profile.PlayerProfile;
import me.siwannie.hallowsend.HallowsEnd;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class CustomItemManager {

    private final HallowsEnd plugin;
    private final Map<String, ItemStack> customItemRegistry = new HashMap<>();
    private final NamespacedKey potionEffectsKey;

    public CustomItemManager(HallowsEnd plugin) {
        this.plugin = plugin;
        this.potionEffectsKey = new NamespacedKey(plugin, "potion_effects");
        loadCustomItems();
    }

    public void loadCustomItems() {
        customItemRegistry.clear();
        ConfigurationSection itemsSection = plugin.getConfigManager().getConfig().getConfigurationSection("custom-items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
            try {
                Material material = Material.valueOf(itemsSection.getString(key + ".material", "STONE").toUpperCase());
                String name = itemsSection.getString(key + ".name");
                List<String> lore = itemsSection.getStringList(key + ".lore");
                String texture = itemsSection.getString(key + ".texture");
                List<String> effects = itemsSection.getStringList(key + ".effects");

                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();

                if (meta instanceof SkullMeta skullMeta && texture != null) {
                    PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                    PlayerTextures textures = profile.getTextures();
                    textures.setSkin(URI.create("https://textures.minecraft.net/texture/" + texture).toURL());
                    profile.setTextures(textures);
                    skullMeta.setPlayerProfile(profile);
                }

                if (name != null) {
                    meta.displayName(MiniMessage.miniMessage().deserialize(name));
                }
                if (!lore.isEmpty()) {
                    meta.lore(lore.stream().map(MiniMessage.miniMessage()::deserialize).collect(Collectors.toList()));
                }

                if (!effects.isEmpty()) {
                    meta.getPersistentDataContainer().set(potionEffectsKey, PersistentDataType.STRING, String.join(";", effects));
                }

                item.setItemMeta(meta);
                customItemRegistry.put(key.toUpperCase(), item);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load custom item '" + key + "': " + e.getMessage());
            }
        }
    }

    public Optional<ItemStack> getItem(String id) {
        return Optional.ofNullable(customItemRegistry.get(id.toUpperCase())).map(ItemStack::new);
    }

    public NamespacedKey getPotionEffectsKey() {
        return potionEffectsKey;
    }
}