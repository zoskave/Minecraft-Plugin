package io.github.nsfeconomy.bank;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Represents a bank location in the world.
 */
public class BankLocation {

    private final int id;
    private final String name;
    private final String type; // "main" or "branch"
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final int radius;

    public BankLocation(int id, String name, String type, String world, int x, int y, int z, int radius) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getRadius() {
        return radius;
    }

    /**
     * Get a Bukkit Location object for this bank
     */
    public Location toBukkitLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return null;
        }
        return new Location(bukkitWorld, x + 0.5, y, z + 0.5);
    }

    /**
     * Check if this is the main vault
     */
    public boolean isMainVault() {
        return "main".equalsIgnoreCase(type);
    }

    /**
     * Check if this is a branch bank
     */
    public boolean isBranch() {
        return "branch".equalsIgnoreCase(type);
    }

    @Override
    public String toString() {
        return String.format("%s (%s) at %s: %d, %d, %d [r=%d]", 
            name, type, world, x, y, z, radius);
    }
}
