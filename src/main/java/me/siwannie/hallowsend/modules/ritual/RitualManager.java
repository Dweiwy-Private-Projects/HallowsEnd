package me.siwannie.hallowsend.modules.ritual;

import me.siwannie.hallowsend.HallowsEnd;
import me.siwannie.hallowsend.config.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.stream.Collectors;

public class RitualManager {

    private final HallowsEnd plugin;
    private final NamespacedKey artifactKey;
    private final Map<RitualArtifact, UUID> artifactCarriers = new EnumMap<>(RitualArtifact.class);
    private final Set<RitualArtifact> foundArtifacts = new HashSet<>();
    private final Map<RitualArtifact, UUID> currentHolder = new EnumMap<>(RitualArtifact.class);
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public RitualManager(HallowsEnd plugin) {
        this.plugin = plugin;
        this.artifactKey = new NamespacedKey(plugin, "hallows_artifact");
    }

    public void reset() {
        artifactCarriers.clear();
        foundArtifacts.clear();
        currentHolder.clear();
    }

    public void tick() {
        if (!plugin.getGameManager().isGameRunning()) return;

        for (UUID playerUUID : plugin.getGameManager().getAlivePlayers()) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null || !player.isOnline()) continue;

            Set<RitualArtifact> heldArtifacts = new HashSet<>();
            getArtifactFromItemStack(player.getInventory().getItemInMainHand()).ifPresent(heldArtifacts::add);
            getArtifactFromItemStack(player.getInventory().getItemInOffHand()).ifPresent(heldArtifacts::add);

            for (RitualArtifact artifact : heldArtifacts) {
                switch (artifact) {
                    case WITCHS_HEART ->
                            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
                }
            }
        }
    }

    public Optional<RitualArtifact> getArtifactFromItemStack(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String artifactName = item.getItemMeta().getPersistentDataContainer().get(artifactKey, PersistentDataType.STRING);
        if (artifactName == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RitualArtifact.valueOf(artifactName));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isArtifactFound(RitualArtifact artifact) {
        return foundArtifacts.contains(artifact);
    }

    public void rewardArtifact(Player player, RitualArtifact artifact) {
        MessageManager messageManager = plugin.getMessageManager();
        if (messageManager == null) {
            plugin.getLogger().severe("MessageManager is null! Cannot reward artifact.");
            return;
        }

        if (foundArtifacts.contains(artifact)) {
            getArtifactCarrier(artifact).ifPresentOrElse(
                    carrier -> messageManager.sendMessage(player, "loot.artifact-already-found",
                            Placeholder.component("artifact", miniMessage.deserialize(artifact.getDisplayName())),
                            Placeholder.unparsed("player", carrier.getName())
                    ),
                    () -> messageManager.sendMessage(player, "loot.artifact-already-found-unknown",
                            Placeholder.component("artifact", miniMessage.deserialize(artifact.getDisplayName()))
                    )
            );
            return;
        }

        foundArtifacts.add(artifact);
        artifactCarriers.putIfAbsent(artifact, player.getUniqueId());

        ItemStack artifactItem = createArtifactItem(artifact);

        if (player.getInventory().addItem(artifactItem).isEmpty()) {
            setArtifactHolder(player, artifact);
            broadcastArtifactObtained(player, artifact);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), artifactItem);
            messageManager.sendMessage(player, "loot.inventory-full-drop",
                    Placeholder.component("artifact", miniMessage.deserialize(artifact.getDisplayName()))
            );
        }
    }

    public void handleArtifactPickup(Player player, RitualArtifact artifact) {
        UUID lastHolder = currentHolder.get(artifact);
        if (lastHolder != null && lastHolder.equals(player.getUniqueId())) {
            return;
        }

        setArtifactHolder(player, artifact);
        broadcastArtifactObtained(player, artifact);
    }

    private void broadcastArtifactObtained(Player player, RitualArtifact artifact) {
        MessageManager messageManager = plugin.getMessageManager();
        if (messageManager != null) {
            Component artifactDisplayNameComponent = miniMessage.deserialize(artifact.getDisplayName());
            Component broadcastMessage = messageManager.getMessage("artifact-found",
                    Placeholder.component("player", player.displayName()),
                    Placeholder.component("artifact", artifactDisplayNameComponent)
            );
            Bukkit.broadcast(broadcastMessage);
        } else {
            Bukkit.broadcast(Component.text(player.getName() + " found the " + artifact.name() + "!"));
            plugin.getLogger().warning("MessageManager was null when trying to broadcast artifact found message.");
        }
    }


    public void setArtifactHolder(Player player, RitualArtifact artifact) {
        currentHolder.put(artifact, player.getUniqueId());
    }

    public void removeArtifactHolder(RitualArtifact artifact) {
        currentHolder.remove(artifact);
    }

    public void handlePlayerDeath(Player player, List<ItemStack> itemsToDrop) {
        for (ItemStack item : itemsToDrop) {
            getArtifactFromItemStack(item).ifPresent(this::removeArtifactHolder);
        }
    }

    public Optional<Player> getArtifactCarrier(RitualArtifact artifact) {
        UUID carrierId = artifactCarriers.get(artifact);
        if (carrierId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bukkit.getPlayer(carrierId));
    }

    public int getFoundArtifactsCount() {
        return foundArtifacts.size();
    }

    private ItemStack createArtifactItem(RitualArtifact artifact) {
        ItemStack item = new ItemStack(artifact.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(artifact.getDisplayName()));

            List<Component> loreComponents = artifact.getLore().stream()
                    .map(miniMessage::deserialize)
                    .collect(Collectors.toList());
            meta.lore(loreComponents);

            meta.setUnbreakable(true);

            meta.getPersistentDataContainer().set(artifactKey, PersistentDataType.STRING, artifact.name());

            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean giveArtifactToPlayer(Player player, String artifactId) {
        RitualArtifact artifact;
        try {
            artifact = RitualArtifact.valueOf(artifactId.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return false;
        }

        ItemStack artifactItem = createArtifactItem(artifact);
        boolean hadSpace = player.getInventory().addItem(artifactItem).isEmpty();
        if (!hadSpace) {
            player.getWorld().dropItemNaturally(player.getLocation(), artifactItem);
        }

        MessageManager mm = plugin.getMessageManager();
        if (mm == null) {
            plugin.getLogger().severe("MessageManager is null, cannot process /giveartifact messages.");
            return true;
        }

        if (foundArtifacts.add(artifact)) {
            artifactCarriers.putIfAbsent(artifact, player.getUniqueId());
        }

        if (hadSpace) {
            setArtifactHolder(player, artifact);
            broadcastArtifactObtained(player, artifact);
            player.sendMessage(mm.getMessage("commands.artifact-given-player",
                    Placeholder.component("artifact", miniMessage.deserialize(artifact.getDisplayName()))
            ));
        } else {
            player.sendMessage(mm.getMessage("loot.inventory-full-drop",
                    Placeholder.component("artifact", miniMessage.deserialize(artifact.getDisplayName()))
            ));
        }
        return true;
    }

    public Component getArtifactComponent(String artifactId) {
        try {
            RitualArtifact artifact = RitualArtifact.valueOf(artifactId.toUpperCase().replace("-", "_"));
            return miniMessage.deserialize(artifact.getDisplayName());
        } catch (IllegalArgumentException e) {
            return Component.text(artifactId);
        }
    }
}

