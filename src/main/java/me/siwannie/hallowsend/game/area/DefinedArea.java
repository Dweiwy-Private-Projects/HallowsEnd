package me.siwannie.hallowsend.game.area;

import me.siwannie.hallowsend.game.GamePhase;
import org.bukkit.Location;
import org.bukkit.World;
import java.util.List;
import java.util.Map;

public record DefinedArea(
        String id,
        String title,
        Map<GamePhase, String> objectives,
        String defaultObjective,
        World world,
        int minY,
        int maxY,
        List<Point> corners
) {

    public static record Point(int x, int z) {}

    public String getObjectiveForPhase(GamePhase phase) {
        return objectives.getOrDefault(phase, defaultObjective);
    }

    public boolean isInArea(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(this.world)) {
            return false;
        }

        if (loc.getY() < minY || loc.getY() > maxY) {
            return false;
        }

        return isPointInPolygon(loc.getBlockX(), loc.getBlockZ());
    }

    private boolean isPointInPolygon(int x, int z) {
        if (corners == null || corners.isEmpty()) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++) {
            int xi = corners.get(i).x();
            int zi = corners.get(i).z();
            int xj = corners.get(j).x();
            int zj = corners.get(j).z();

            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (double)(zj - zi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    public Location getCenter() {
        if (corners == null || corners.isEmpty()) {
            return new Location(world, 0, (minY + maxY) / 2.0, 0);
        }
        double centerX = corners.stream().mapToInt(Point::x).average().orElse(0);
        double centerZ = corners.stream().mapToInt(Point::z).average().orElse(0);
        return new Location(world, centerX, (minY + maxY) / 2.0, centerZ);
    }

    public int minX() { return corners.stream().mapToInt(Point::x).min().orElse(0); }
    public int maxX() { return corners.stream().mapToInt(Point::x).max().orElse(0); }
    public int minZ() { return corners.stream().mapToInt(Point::z).min().orElse(0); }
    public int maxZ() { return corners.stream().mapToInt(Point::z).max().orElse(0); }

    public long getApproximateVolume() {
        if (corners == null || corners.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long width = Math.abs(maxX() - minX());
        long depth = Math.abs(maxZ() - minZ());
        long height = Math.abs(maxY - minY);

        if (width == 0) width = 1;
        if (depth == 0) depth = 1;
        if (height == 0) height = 1;

        return width * depth * height;
    }
}

