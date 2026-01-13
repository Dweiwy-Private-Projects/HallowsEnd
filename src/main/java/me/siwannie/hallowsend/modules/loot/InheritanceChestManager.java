package me.siwannie.hallowsend.modules.loot;

import me.siwannie.hallowsend.HallowsEnd;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InheritanceChestManager implements Listener {

    private final HallowsEnd plugin;
    private final Map<UUID, Inventory> inheritanceInventories = new ConcurrentHashMap<>();
    private final Map<BlockLocation, UUID> chestLocations = new ConcurrentHashMap<>();

    public InheritanceChestManager(HallowsEnd plugin) {
        this.plugin = plugin;
    }

    public void createChest(Player eliminatedPlayer, List<ItemStack> items) {
        Location loc = eliminatedPlayer.getLocation();
        Block block = loc.getBlock();

        block.setType(Material.TRAPPED_CHEST);

        Component chestTitle = plugin.getMessageManager().getMessage("loot.inheritance-chest-title",
                Placeholder.component("player", Component.text(eliminatedPlayer.getName()))
        );

        Inventory sharedLoot = Bukkit.createInventory(null, 54, chestTitle);

        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                sharedLoot.addItem(item);
            }
        }

        UUID eliminatedPlayerUUID = eliminatedPlayer.getUniqueId();
        inheritanceInventories.put(eliminatedPlayerUUID, sharedLoot);
        chestLocations.put(BlockLocation.from(loc), eliminatedPlayerUUID);

        plugin.getLogger().info("Created Inheritance Chest for " + eliminatedPlayer.getName() + " at " + loc);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getGameManager().isGameRunning()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.TRAPPED_CHEST) {
            return;
        }

        BlockLocation blockLoc = BlockLocation.from(clickedBlock.getLocation());
        UUID eliminatedPlayerUUID = chestLocations.get(blockLoc);

        if (eliminatedPlayerUUID != null) {
            event.setCancelled(true);
            Inventory sharedLoot = inheritanceInventories.get(eliminatedPlayerUUID);

            if (sharedLoot != null) {
                event.getPlayer().openInventory(sharedLoot);
            } else {
                plugin.getLogger().warning("Inheritance chest block found at " + blockLoc + " but had no matching inventory. Removing block.");
                clickedBlock.setType(Material.AIR);
                chestLocations.remove(blockLoc);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();

        if (!inheritanceInventories.containsValue(topInventory)) {
            return;
        }

        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null) return;

        if (topInventory.equals(clickedInventory)) {
            if (!event.getCursor().getType().isAir()) {
                event.setCancelled(true);
            }
        }
        else {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();

        if (!inheritanceInventories.containsValue(inv)) {
            return;
        }

        if (inv.isEmpty() && inv.getViewers().size() <= 1) {
            plugin.getLogger().info("Inheritance chest is now empty and last viewer closed it. Removing.");

            UUID elimPlayerUUID = null;
            BlockLocation blockLoc = null;

            for (Map.Entry<UUID, Inventory> entry : inheritanceInventories.entrySet()) {
                if (entry.getValue().equals(inv)) {
                    elimPlayerUUID = entry.getKey();
                    break;
                }
            }

            if (elimPlayerUUID == null) return;

            for (Map.Entry<BlockLocation, UUID> entry : chestLocations.entrySet()) {
                if (entry.getValue().equals(elimPlayerUUID)) {
                    blockLoc = entry.getKey();
                    break;
                }
            }

            if (blockLoc != null) {
                Location loc = blockLoc.toLocation(plugin);
                if (loc != null && loc.getBlock().getType() == Material.TRAPPED_CHEST) {
                    loc.getBlock().setType(Material.AIR);
                }
                chestLocations.remove(blockLoc);
            }
            inheritanceInventories.remove(elimPlayerUUID);
        }
    }

    public void reset() {
        for (Inventory inv : inheritanceInventories.values()) {
            new java.util.ArrayList<>(inv.getViewers()).forEach(viewer -> viewer.closeInventory());
        }

        for (BlockLocation blockLoc : chestLocations.keySet()) {
            Location loc = blockLoc.toLocation(plugin);
            if (loc != null && loc.getBlock().getType() == Material.TRAPPED_CHEST) {
                loc.getBlock().setType(Material.AIR);
            }
        }

        inheritanceInventories.clear();
        chestLocations.clear();
        plugin.getLogger().info("Cleared all Inheritance Chests.");
    }
}