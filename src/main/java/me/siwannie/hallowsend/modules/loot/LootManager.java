package me.siwannie.hallowsend.modules.loot;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.MessageManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;


import java.util.*;
import java.util.stream.Collectors;

public class LootManager {

    private final HallowsEnd plugin;
    private final Random random = new Random();
    private final Map<String, List<LootItem>> mobLootTables = new HashMap<>();

    public LootManager(HallowsEnd plugin) {
        this.plugin = plugin;
        loadLootConfig();
    }

    public void loadLootConfig() {
        mobLootTables.clear();
        ConfigurationSection lootTablesSection = plugin.getConfigManager().getConfig().getConfigurationSection("loot-system.mob-drops");
        if (lootTablesSection == null) {
            plugin.getLogger().warning("Could not load mob drops, 'loot-system.mob-drops' section missing from config.yml");
            return;
        }

        for (String mobId : lootTablesSection.getKeys(false)) {
            List<LootItem> lootList = new ArrayList<>();
            List<Map<?, ?>> items = lootTablesSection.getMapList(mobId);
            for (Map<?, ?> itemMap : items) {
                try {
                    Material material = Material.valueOf(((String) itemMap.get("material")).toUpperCase());
                    double chance = (double) itemMap.get("chance");

                    int minAmount;
                    if (itemMap.containsKey("min-amount")) {
                        minAmount = ((Number) itemMap.get("min-amount")).intValue();
                    } else {
                        minAmount = 1;
                    }

                    int maxAmount;
                    if (itemMap.containsKey("max-amount")) {
                        maxAmount = ((Number) itemMap.get("max-amount")).intValue();
                    } else {
                        maxAmount = 1;
                    }

                    lootList.add(new LootItem(material, chance, minAmount, maxAmount));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load loot item for mob '" + mobId + "': " + e.getMessage());
                }
            }
            mobLootTables.put(mobId.toUpperCase(), lootList);
            plugin.getLogger().info("Loaded " + lootList.size() + " loot items for mob: " + mobId.toUpperCase());
        }
    }

    public void handleMobDeath(LivingEntity mob, String mobId) {
        if (mobId == null) return;

        String upperMobId = mobId.toUpperCase();
        if (!mobLootTables.containsKey(upperMobId)) {
            return;
        }

        List<LootItem> lootTable = mobLootTables.get(upperMobId);
        for (LootItem lootItem : lootTable) {
            if (random.nextDouble() >= lootItem.chance()) {
                continue;
            }

            int min = Math.min(lootItem.minAmount(), lootItem.maxAmount());
            int max = Math.max(lootItem.minAmount(), lootItem.maxAmount());
            int amount = (min == max) ? min : random.nextInt(max - min + 1) + min;

            if (amount <= 0) {
                continue;
            }

            ItemStack drop = new ItemStack(lootItem.material(), amount);
            ItemMeta meta = drop.getItemMeta();

            if (meta != null) {
                if (drop.getType().getMaxDurability() > 0) {
                    meta.setUnbreakable(true);
                }

                if (drop.getType() == Material.GHAST_TEAR) {
                    MessageManager mm = plugin.getMessageManager();
                    meta.displayName(mm.getMessage("items.soul-name"));

                    List<String> loreLines = plugin.getConfigManager().getMessagesConfig().getStringList("items.soul-lore");
                    List<Component> loreComponents = loreLines.stream().map(line -> mm.miniMessage.deserialize(line)).collect(Collectors.toList());
                    meta.lore(loreComponents);
                }

                drop.setItemMeta(meta);
            }

            mob.getWorld().dropItemNaturally(mob.getLocation(), drop);
        }
    }
}