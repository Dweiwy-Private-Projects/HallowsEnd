package me.siwannie.hallowsend.modules.loot;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.MessageManager;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DeathChestManager implements Listener {

    private final HallowsEnd plugin;
    private final GameManager gameManager;
    private final RitualManager ritualManager;
    private final MessageManager messageManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private record DeathChestInfo(
            UUID ownerId,
            String playerName,
            Location location,
            ArmorStand hologram,
            Inventory inventory,
            long despawnTime
    ) {}

    private final Map<BlockLocation, DeathChestInfo> activeChests = new ConcurrentHashMap<>();
    private final Map<Inventory, BlockLocation> openGuis = new ConcurrentHashMap<>();
    private final int DESPAWN_SECONDS = 60;

    public DeathChestManager(HallowsEnd plugin) {
        this.plugin = plugin;
        this.gameManager = plugin.getGameManager();
        this.ritualManager = plugin.getRitualManager();
        this.messageManager = plugin.getMessageManager();
    }

    public void createDeathChest(Player player, Location location, List<ItemStack> allItems) {
        createDeathChest(player.getUniqueId(), player.getName(), location, allItems);
    }

    public void createDeathChest(UUID playerId, String playerName, Location location, List<ItemStack> allItems) {
        if (gameManager == null || ritualManager == null) {
            plugin.getLogger().severe("DeathChestManager: Managers are null! Chest creation failed.");
            return;
        }

        List<ItemStack> itemsForChest = new ArrayList<>();
        List<ItemStack> itemsToDrop = new ArrayList<>();

        for (ItemStack item : allItems) {
            if (item == null || item.getType().isAir()) continue;
            if (ritualManager.getArtifactFromItemStack(item).isPresent()) {
                itemsToDrop.add(item);
            } else {
                itemsForChest.add(item);
            }
        }

        dropItemsAtLocation(location, itemsToDrop);

        if (itemsForChest.isEmpty()) {
            plugin.getLogger().info("Player " + playerName + " had no items to store in a death chest.");
            return;
        }

        Block block = location.getBlock();
        block.setType(Material.CHEST);
        BlockLocation blockLoc = BlockLocation.from(location);

        int invSize = (int) (Math.ceil(itemsForChest.size() / 9.0) * 9);
        invSize = Math.max(9, Math.min(54, invSize));
        Inventory virtualInv = Bukkit.createInventory(null, invSize, miniMessage.deserialize("<dark_gray>" + playerName + "'s Items"));

        for (ItemStack item : itemsForChest) {
            virtualInv.addItem(item);
        }

        long despawnTime = System.currentTimeMillis() + (DESPAWN_SECONDS * 1000L);
        ArmorStand hologram = spawnHologram(location.clone().add(0.5, 1.0, 0.5), playerName, DESPAWN_SECONDS);

        DeathChestInfo info = new DeathChestInfo(playerId, playerName, location, hologram, virtualInv, despawnTime);
        activeChests.put(blockLoc, info);
        plugin.getLogger().info("Created Death Chest for " + playerName + " at " + blockLoc);
    }

    private ArmorStand spawnHologram(Location loc, String playerName, int timeRemaining) {
        ArmorStand hologram = loc.getWorld().spawn(loc, ArmorStand.class, holo -> {
            holo.setGravity(false);
            holo.setInvisible(true);
            holo.setMarker(true);
            holo.setCustomNameVisible(true);
        });
        updateHologramText(hologram, playerName, timeRemaining);
        return hologram;
    }

    private void updateHologramText(ArmorStand hologram, String playerName, int timeRemaining) {
        if (messageManager == null) {
            hologram.customName(Component.text(playerName + "'s Grave - " + timeRemaining + "s"));
            return;
        }
        String timeString = String.format("%d:%02d", timeRemaining / 60, timeRemaining % 60);
        Component hologramText = messageManager.getMessage("holograms.death-chest",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("time", timeString)
        );
        hologram.customName(hologramText);
    }

    public void tick() {
        if (activeChests.isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        activeChests.forEach((blockLoc, info) -> {
            long timeLeftMillis = info.despawnTime() - currentTime;
            int timeLeftSeconds = (int) Math.max(0, timeLeftMillis / 1000L);

            if (timeLeftMillis <= 0) {
                despawnChest(blockLoc, false);
            } else {
                updateHologramText(info.hologram(), info.playerName(), timeLeftSeconds);
            }
        });

        activeChests.entrySet().removeIf(entry -> !entry.getValue().hologram().isValid());
    }

    private void despawnChest(BlockLocation blockLoc, boolean dropRemainingItems) {
        DeathChestInfo info = activeChests.remove(blockLoc);
        if (info == null) return;

        plugin.getLogger().info("Despawning Death Chest for " + info.playerName() + " at " + blockLoc);

        if (info.hologram() != null) {
            info.hologram().remove();
        }

        new ArrayList<>(info.inventory().getViewers()).forEach(viewer -> viewer.closeInventory());
        openGuis.remove(info.inventory());

        if (dropRemainingItems) {
            dropItemsAtLocation(info.location().clone().add(0.5, 0.5, 0.5), Arrays.asList(info.inventory().getContents()));
        }

        info.inventory().clear();
        Block block = info.location().getBlock();
        if (block.getType() == Material.CHEST) {
            block.setType(Material.AIR);
        }
    }

    public void reset() {
        for (BlockLocation loc : new ArrayList<>(activeChests.keySet())) {
            despawnChest(loc, false);
        }
        activeChests.clear();
        openGuis.clear();
        plugin.getLogger().info("All Death Chests cleared.");
    }

    private void dropItemsAtLocation(Location loc, List<ItemStack> items) {
        if (items == null || loc == null || !loc.isWorldLoaded()) return;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                loc.getWorld().dropItemNaturally(loc, item);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (gameManager == null || !gameManager.isGameRunning()) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.CHEST) return;

        BlockLocation blockLoc = BlockLocation.from(clickedBlock.getLocation());
        DeathChestInfo info = activeChests.get(blockLoc);

        if (info != null) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
            player.openInventory(info.inventory());
            openGuis.put(info.inventory(), blockLoc);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();

        if (!openGuis.containsKey(topInventory)) {
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        BlockLocation blockLoc = BlockLocation.from(event.getBlock().getLocation());
        if (activeChests.containsKey(blockLoc)) {
            event.setCancelled(true);
            messageManager.sendMessage(event.getPlayer(), "misc.death-chest-indestructible");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> activeChests.containsKey(BlockLocation.from(block.getLocation())));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        BlockLocation blockLoc = openGuis.remove(event.getInventory());
        if (blockLoc == null) return;

        if (event.getPlayer() instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f);
        }

        DeathChestInfo info = activeChests.get(blockLoc);
        if (info != null) {
            if (info.inventory().isEmpty() && info.inventory().getViewers().size() <= 1) {
                plugin.getLogger().info("Death Chest for " + info.playerName() + " is empty. Removing early.");
                despawnChest(blockLoc, false);
            }
        }
    }
}