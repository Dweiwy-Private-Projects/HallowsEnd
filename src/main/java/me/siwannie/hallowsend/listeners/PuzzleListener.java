package me.siwannie.hallowsend.listeners;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.modules.ritual.RitualArtifact;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class PuzzleListener implements Listener {

    private final HallowsEnd plugin;
    private final RitualManager ritualManager;
    private Location cryptAltarLocation;
    private Location cryptVaultLocation;
    private Location libraryPuzzleLocation;

    private static final int SOULS_REQUIRED = 10;
    private final NamespacedKey cryptKeyFlag;

    private final Set<UUID> receivedRoseHint = new HashSet<>();

    public PuzzleListener(HallowsEnd plugin) {
        this.plugin = plugin;
        this.ritualManager = plugin.getRitualManager();
        this.cryptKeyFlag = new NamespacedKey(plugin, "crypt_key");
        loadLocations();
    }

    private void loadLocations() {
        this.cryptAltarLocation = plugin.getConfigManager().getLocation("event-objects.crypt-trial-spawner");
        this.cryptVaultLocation = plugin.getConfigManager().getLocation("event-objects.crypt-vault");
        this.libraryPuzzleLocation = plugin.getConfigManager().getLocation("event-objects.library-puzzle-pot");

        if (cryptAltarLocation == null) plugin.getLogger().warning("PuzzleListener: cryptAltarLocation (crypt-trial-spawner) not loaded.");
        if (cryptVaultLocation == null) plugin.getLogger().warning("PuzzleListener: cryptVaultLocation (crypt-vault) not loaded.");
        if (libraryPuzzleLocation == null) plugin.getLogger().warning("PuzzleListener: libraryPuzzleLocation (library-puzzle-pot) not loaded.");
    }

    @EventHandler
    public void onRosePickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getItem().getItemStack().getType() == Material.WITHER_ROSE) {
            if (receivedRoseHint.contains(player.getUniqueId())) {
                return;
            }
            plugin.getMessageManager().sendMessage(player, "wither-rose-pickup-hint");
            receivedRoseHint.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getGameManager().isGameRunning()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        Player player = event.getPlayer();
        Location clickedLocation = clickedBlock.getLocation();

        if (cryptAltarLocation != null && locationsMatch(clickedLocation, cryptAltarLocation)) {
            plugin.getLogger().info("Interaction matched cryptAltarLocation.");
            event.setCancelled(true);
            int soulsFound = getTotalItems(player.getInventory(), Material.GHAST_TEAR);

            if (soulsFound >= SOULS_REQUIRED) {
                plugin.getLogger().info("Sufficient souls, processing offering.");
                if (consumeItems(player.getInventory(), Material.GHAST_TEAR, SOULS_REQUIRED)) {
                    ItemStack key = new ItemStack(Material.OMINOUS_TRIAL_KEY);
                    key.editMeta(meta -> {
                        meta.displayName(plugin.getMessageManager().getMessage("items.crypt-key-name"));
                        meta.getPersistentDataContainer().set(cryptKeyFlag, PersistentDataType.BYTE, (byte) 1);
                    });
                    player.getInventory().addItem(key);
                    plugin.getMessageManager().sendMessage(player, "loot.offering-success");
                    clickedBlock.getWorld().playSound(clickedLocation, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 1.0f);
                } else {
                    plugin.getLogger().warning("Failed to consume souls from " + player.getName() + " even though count was sufficient.");
                }
            } else {
                plugin.getLogger().info("Insufficient souls.");
                plugin.getMessageManager().sendMessage(player, "loot.offering-failure",
                        Placeholder.unparsed("required", String.valueOf(SOULS_REQUIRED)),
                        Placeholder.unparsed("current", String.valueOf(soulsFound))
                );
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f);
            }
            return;
        }

        if (cryptVaultLocation != null && locationsMatch(clickedLocation, cryptVaultLocation)) {
            plugin.getLogger().info("Interaction matched cryptVaultLocation.");
            event.setCancelled(true);

            if (ritualManager.isArtifactFound(RitualArtifact.CRYPT_LORDS_SKULL)) {
                plugin.getLogger().info("Crypt Lord's Skull already found.");
                ritualManager.getArtifactCarrier(RitualArtifact.CRYPT_LORDS_SKULL).ifPresentOrElse(
                        carrier -> plugin.getMessageManager().sendMessage(player, "loot.artifact-already-found",
                                Placeholder.component("artifact", plugin.getMessageManager().miniMessage.deserialize(RitualArtifact.CRYPT_LORDS_SKULL.getDisplayName())),
                                Placeholder.unparsed("player", carrier.getName())
                        ),
                        () -> plugin.getMessageManager().sendMessage(player, "loot.artifact-already-found-unknown",
                                Placeholder.component("artifact", plugin.getMessageManager().miniMessage.deserialize(RitualArtifact.CRYPT_LORDS_SKULL.getDisplayName()))
                        )
                );
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
            } else if (consumeItems(player.getInventory(), Material.OMINOUS_TRIAL_KEY, 1, true, cryptKeyFlag)) {
                plugin.getLogger().info("Player holding correct Crypt Key. Consuming key and giving artifact.");
                ritualManager.rewardArtifact(player, RitualArtifact.CRYPT_LORDS_SKULL);
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_IRON_DOOR_OPEN, 1.0f, 0.8f);
            } else {
                plugin.getLogger().info("Player not holding Crypt Key.");
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
            }
            return;
        }

        if (libraryPuzzleLocation != null && locationsMatch(clickedLocation, libraryPuzzleLocation)) {
            plugin.getLogger().info("Interaction matched libraryPuzzleLocation.");
            event.setCancelled(true);

            if (ritualManager.isArtifactFound(RitualArtifact.WITCHS_HEART)) {
                plugin.getLogger().info("Witch's Heart already found.");
                ritualManager.getArtifactCarrier(RitualArtifact.WITCHS_HEART).ifPresentOrElse(
                        carrier -> plugin.getMessageManager().sendMessage(player, "loot.artifact-already-found",
                                Placeholder.component("artifact", plugin.getMessageManager().miniMessage.deserialize(RitualArtifact.WITCHS_HEART.getDisplayName())),
                                Placeholder.unparsed("player", carrier.getName())
                        ),
                        () -> plugin.getMessageManager().sendMessage(player, "loot.artifact-already-found-unknown",
                                Placeholder.component("artifact", plugin.getMessageManager().miniMessage.deserialize(RitualArtifact.WITCHS_HEART.getDisplayName()))
                        )
                );
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_DECORATED_POT_SHATTER, 1.0f, 1.2f);
            } else if (consumeItems(player.getInventory(), Material.WITHER_ROSE, 1, false, null)) {
                plugin.getLogger().info("Player holding WITHER_ROSE. Consuming rose and giving artifact.");
                ritualManager.rewardArtifact(player, RitualArtifact.WITCHS_HEART);
                plugin.getMessageManager().sendMessage(player, "loot.offering-success");
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_DECORATED_POT_INSERT, 1.0f, 1.0f);
            } else {
                plugin.getLogger().info("Player not holding WITHER_ROSE.");
                clickedBlock.getWorld().playSound(clickedLocation, Sound.BLOCK_DECORATED_POT_SHATTER, 1.0f, 1.2f);
            }
        }
    }

    private int getTotalItems(Inventory inventory, Material material) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private boolean consumeItems(Inventory inventory, Material material, int amountToConsume) {
        return consumeItems(inventory, material, amountToConsume, false, null);
    }

    private boolean consumeItems(Inventory inventory, Material material, int amountToConsume, boolean checkTag, NamespacedKey tagKey) {
        int amountFound = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == material) {
                if (checkTag) {
                    if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE)) {
                        amountFound += item.getAmount();
                    }
                } else {
                    amountFound += item.getAmount();
                }
            }
        }

        if (amountFound < amountToConsume) {
            return false;
        }

        int amountLeftToConsume = amountToConsume;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == material) {
                boolean itemMatches = !checkTag || (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE));

                if (itemMatches) {
                    int amountInStack = item.getAmount();
                    if (amountInStack >= amountLeftToConsume) {
                        item.setAmount(amountInStack - amountLeftToConsume);
                        return true;
                    } else {
                        item.setAmount(0);
                        amountLeftToConsume -= amountInStack;
                    }
                }
            }
            if (amountLeftToConsume <= 0) {
                return true;
            }
        }
        return true;
    }


    private boolean locationsMatch(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) return false;
        return loc1.getWorld().equals(loc2.getWorld()) &&
                loc1.getBlockX() == loc2.getBlockX() &&
                loc1.getBlockY() == loc2.getBlockY() &&
                loc1.getBlockZ() == loc2.getBlockZ();
    }

    public void reset() {
        this.receivedRoseHint.clear();
        loadLocations();
        plugin.getLogger().info("PuzzleListener reset and locations reloaded.");
    }
}

