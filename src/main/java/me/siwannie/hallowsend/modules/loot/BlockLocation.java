package me.siwannie.hallowsend.modules.loot;

import me.siwannie.hallowsend.HallowsEnd;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record BlockLocation(String worldName, int x, int y, int z) {

    public static BlockLocation from(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("Location and world cannot be null.");
        }
        return new BlockLocation(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location toLocation(HallowsEnd plugin) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Could not find world with name: " + worldName + " for BlockLocation.");
            return null;
        }
        return new Location(world, x, y, z);
    }
}
