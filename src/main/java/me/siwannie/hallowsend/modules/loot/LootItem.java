package me.siwannie.hallowsend.modules.loot;

import org.bukkit.Material;

public record LootItem(
        Material material,
        double chance,
        int minAmount,
        int maxAmount
) {}

