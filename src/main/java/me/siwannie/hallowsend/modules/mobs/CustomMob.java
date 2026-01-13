package me.siwannie.hallowsend.modules.mobs;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Map;

public record CustomMob(
        String id,
        EntityType type,
        String name,
        double health,
        double spawnChance,
        double speedMultiplier,
        double scale,
        Map<String, Material> equipment,
        List<String> potionEffects
) {}
