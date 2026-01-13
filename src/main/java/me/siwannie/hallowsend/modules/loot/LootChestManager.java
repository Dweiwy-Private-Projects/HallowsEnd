package me.siwannie.hallowsend.modules.loot;

import me.siwannie.hallowsend.HallowsEnd;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class LootChestManager implements Listener {

    private record LootChest(Location location, Component displayName, String emptyMessage, String alreadyLootedMessage, List<List<ItemStack>> initialItems) {}

    private final HallowsEnd plugin;
    private final CustomItemManager customItemManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<BlockLocation, LootChest> configuredChests = new HashMap<>();
    private final Map<BlockLocation, LinkedList<List<ItemStack>>> chestStock = new ConcurrentHashMap<>();
    private final Map<BlockLocation, Set<UUID>> lootedBy = new ConcurrentHashMap<>();
    private final Map<Inventory, BlockLocation> openLootGuis = new ConcurrentHashMap<>();

    public LootChestManager(HallowsEnd plugin) {
        this.plugin = plugin;
        this.customItemManager = plugin.getCustomItemManager();
    }

    public void loadLootChests() {
        configuredChests.clear();
        ConfigurationSection chestSection = plugin.getConfigManager().getConfig().getConfigurationSection("phase-1-loot-chests");
        if (chestSection == null) {
            plugin.getLogger().info("No 'phase-1-loot-chests' section found in config.yml. Skipping.");
            return;
        }
        plugin.getLogger().info("Loading Phase 1 loot chests...");
        for (String key : chestSection.getKeys(false)) {
            parseLootChestFromConfig(key, chestSection).ifPresent(lootChest -> {
                BlockLocation blockLoc = BlockLocation.from(lootChest.location());
                configuredChests.put(blockLoc, lootChest);
            });
        }
        plugin.getLogger().info("Successfully loaded " + configuredChests.size() + " loot chests.");
    }

    public void reset() {
        chestStock.clear();
        lootedBy.clear();

        for (Inventory inv : openLootGuis.keySet()) {
            new ArrayList<>(inv.getViewers()).forEach(viewer -> viewer.closeInventory());
        }
        openLootGuis.clear();

        for (LootChest chest : configuredChests.values()) {
            BlockLocation key = BlockLocation.from(chest.location());
            chestStock.put(key, new LinkedList<>(chest.initialItems()));
            lootedBy.put(key, ConcurrentHashMap.newKeySet());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        BlockLocation blockLoc = BlockLocation.from(clickedBlock.getLocation());
        LootChest chestConfig = configuredChests.get(blockLoc);

        if (chestConfig == null || !plugin.getGameManager().isGameRunning()) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Inventory lootGui = Bukkit.createInventory(null, 27, chestConfig.displayName());

        if (lootedBy.getOrDefault(blockLoc, Collections.emptySet()).contains(player.getUniqueId())) {
            player.sendMessage(miniMessage.deserialize(chestConfig.alreadyLootedMessage()));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.2f);
            lootGui.setItem(13, createEmptyIndicator("<gray><!i>You have already searched this."));
            openLootGuis.put(lootGui, blockLoc);
            player.openInventory(lootGui);
            return;
        }

        LinkedList<List<ItemStack>> stock = chestStock.get(blockLoc);
        if (stock == null || stock.isEmpty()) {
            player.sendMessage(miniMessage.deserialize(chestConfig.emptyMessage()));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.2f);
            lootGui.setItem(13, createEmptyIndicator("<gray><!i>This container has been picked clean."));
            openLootGuis.put(lootGui, blockLoc);
            player.openInventory(lootGui);
            return;
        }

        List<ItemStack> itemBundleToShow = stock.peekFirst();

        if (itemBundleToShow == null || itemBundleToShow.isEmpty()) {
            player.sendMessage(miniMessage.deserialize(chestConfig.emptyMessage()));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.2f);
            lootGui.setItem(13, createEmptyIndicator("<gray><!i>This container has been picked clean."));
            openLootGuis.put(lootGui, blockLoc);
            player.openInventory(lootGui);
            return;
        }

        for (int i = 0; i < itemBundleToShow.size() && i < 9; i++) {
            lootGui.setItem(i + 9, itemBundleToShow.get(i));
        }

        openLootGuis.put(lootGui, blockLoc);
        player.openInventory(lootGui);
        plugin.getMessageManager().sendMessage(player, "loot.chest-opened");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        BlockLocation blockLoc = openLootGuis.get(topInventory);

        if (blockLoc == null) {
            return;
        }

        Inventory clickedInventory = event.getClickedInventory();
        Player player = (Player) event.getWhoClicked();

        if (!topInventory.equals(clickedInventory)) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);

        if (!event.getCursor().getType().isAir()) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem == null || clickedItem.getType().isAir() || clickedItem.getType() == Material.BARRIER) {
            return;
        }

        if (event.getAction().name().contains("PLACE") || event.getAction().name().contains("SWAP") || event.getAction().name().contains("DROP")) {
            return;
        }

        if (lootedBy.getOrDefault(blockLoc, Collections.emptySet()).contains(player.getUniqueId())) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.2f);
            return;
        }

        LinkedList<List<ItemStack>> stock = chestStock.get(blockLoc);
        if (stock == null) {
            plugin.getLogger().warning("Stock is null for chest at " + blockLoc + " during click event. This shouldn't happen.");
            player.closeInventory();
            return;
        }

        synchronized (stock) {
            if (stock.isEmpty() || stock.peekFirst() == null) {
                plugin.getLogger().warning("Stock became empty or invalid for chest at " + blockLoc + " between opening and clicking.");
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.5f, 1.2f);
                topInventory.clear();
                topInventory.setItem(13, createEmptyIndicator("<gray><!i>This container has been picked clean."));
                return;
            }

            List<ItemStack> itemBundle = stock.removeFirst();
            lootedBy.computeIfAbsent(blockLoc, k -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());

            if (itemBundle != null) {
                for (ItemStack item : itemBundle) {
                    if (item == null || item.getType().isAir()) continue;
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
                    for (ItemStack drop : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                }
            } else {
                plugin.getLogger().warning("Removed a null bundle from stock for chest at " + blockLoc);
            }

            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

            player.closeInventory();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                updateAllOpenGuisForLocation(blockLoc);
            }, 1L);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openLootGuis.remove(event.getInventory());
    }

    private void updateAllOpenGuisForLocation(BlockLocation location) {
        LinkedList<List<ItemStack>> stock = chestStock.get(location);
        List<ItemStack> nextBundle = (stock != null && !stock.isEmpty()) ? stock.peekFirst() : null;

        Iterator<Map.Entry<Inventory, BlockLocation>> iterator = openLootGuis.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Inventory, BlockLocation> entry = iterator.next();
            if (entry.getValue().equals(location)) {
                Inventory inv = entry.getKey();

                if (inv.getViewers().isEmpty()) {
                    iterator.remove();
                    continue;
                }

                inv.clear();
                if (nextBundle != null && !nextBundle.isEmpty()) {
                    for (int i = 0; i < nextBundle.size() && i < 9; i++) {
                        inv.setItem(i + 9, nextBundle.get(i));
                    }
                } else {
                    inv.setItem(13, createEmptyIndicator("<gray><!i>This container has been picked clean."));
                }
            }
        }
    }

    private Optional<LootChest> parseLootChestFromConfig(String key, ConfigurationSection section) {
        ConfigurationSection specificChestSection = section.getConfigurationSection(key);
        if (specificChestSection == null) {
            plugin.getLogger().warning("Could not find a valid configuration section for loot chest key: '" + key + "'. Please check YAML indentation.");
            return Optional.empty();
        }

        try {
            Location loc = specificChestSection.getLocation("location");
            if (loc == null) {
                if (specificChestSection.getString("location.world") == null) {
                    throw new IllegalArgumentException("The 'location' section for chest '" + key + "' is missing the 'world' key or is malformed.");
                }
                throw new IllegalArgumentException("Location for chest '" + key + "' could not be parsed. Check formatting.");
            }

            String displayNameStr = specificChestSection.getString("display-name", "<dark_gray><!i>Container");
            Component displayName = miniMessage.deserialize(displayNameStr);
            String emptyMessage = specificChestSection.getString("empty-message", "<gray>This container is globally empty.");
            String alreadyLootedMessage = specificChestSection.getString("already-looted-message", "<gray>You have already searched this.");

            List<List<ItemStack>> initialItems = new ArrayList<>();
            List<Map<?, ?>> bundleMaps = specificChestSection.getMapList("loot-bundles");

            if (bundleMaps != null && !bundleMaps.isEmpty()) {
                plugin.getLogger().info("Parsing loot chest '" + key + "' using new 'loot-bundles' logic.");
                for (Map<?, ?> bundleMap : bundleMaps) {
                    List<Map<?, ?>> itemsInBundleMaps = (List<Map<?, ?>>) bundleMap.get("bundle");
                    int amount = bundleMap.containsKey("amount") ? ((Number) bundleMap.get("amount")).intValue() : 1;

                    if (itemsInBundleMaps == null || itemsInBundleMaps.isEmpty()) {
                        plugin.getLogger().warning("Skipping empty or invalid bundle in '" + key + "'.");
                        continue;
                    }

                    List<ItemStack> itemBundle = new ArrayList<>();
                    for (Map<?, ?> itemMap : itemsInBundleMaps) {
                        parseItemFromMap(itemMap).ifPresent(itemBundle::add);
                    }

                    if (!itemBundle.isEmpty()) {
                        for (int i = 0; i < amount; i++) {
                            initialItems.add(new ArrayList<>(itemBundle));
                        }
                    }
                }
            } else {
                plugin.getLogger().info("Parsing loot chest '" + key + "' using old 'loot-items' logic (each item is a 1-item bundle).");
                List<Map<?, ?>> itemMaps = specificChestSection.getMapList("loot-items");
                if (itemMaps != null) {
                    for (Map<?, ?> itemMap : itemMaps) {
                        parseItemFromMap(itemMap).ifPresent(item -> {
                            initialItems.add(List.of(item));
                        });
                    }
                }
            }

            if (initialItems.isEmpty()) {
                throw new IllegalArgumentException("Loot items or bundles list is empty for '" + key + "'.");
            }

            Collections.shuffle(initialItems);
            return Optional.of(new LootChest(loc, displayName, emptyMessage, alreadyLootedMessage, initialItems));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load Phase 1 loot chest '" + key + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ItemStack> parseItemFromMap(Map<?, ?> itemMap) {
        Optional<ItemStack> itemOptional;

        if (itemMap.containsKey("custom-id")) {
            itemOptional = customItemManager.getItem((String) itemMap.get("custom-id")).map(ItemStack::clone);
        } else if (itemMap.containsKey("material")) {
            try {
                Material material = Material.valueOf(((String) itemMap.get("material")).toUpperCase());
                int amount = itemMap.containsKey("amount") ? ((Number) itemMap.get("amount")).intValue() : 1;
                ItemStack item = new ItemStack(material, amount);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    if (itemMap.containsKey("name")) {
                        meta.displayName(miniMessage.deserialize((String) itemMap.get("name")));
                    }
                    if (itemMap.containsKey("lore")) {
                        List<?> loreRaw = (List<?>) itemMap.get("lore");
                        if (loreRaw != null) {
                            List<String> lore = loreRaw.stream().map(String::valueOf).collect(Collectors.toList());
                            meta.lore(lore.stream().map(miniMessage::deserialize).collect(Collectors.toList()));
                        }
                    }
                    item.setItemMeta(meta);
                }
                itemOptional = Optional.of(item);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse loot item from map: " + itemMap + " - " + e.getMessage());
                itemOptional = Optional.empty();
            }
        } else {
            itemOptional = Optional.empty();
        }

        itemOptional.ifPresent(item -> {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (item.getType().getMaxDurability() > 0) {
                    meta.setUnbreakable(true);
                    item.setItemMeta(meta);
                }
            }
        });

        return itemOptional;
    }

    private ItemStack createEmptyIndicator(String lore) {
        ItemStack emptyIndicator = new ItemStack(Material.BARRIER);
        ItemMeta meta = emptyIndicator.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize("<red><!i>Empty"));
            meta.lore(List.of(miniMessage.deserialize(lore)));
            emptyIndicator.setItemMeta(meta);
        }
        return emptyIndicator;
    }
}