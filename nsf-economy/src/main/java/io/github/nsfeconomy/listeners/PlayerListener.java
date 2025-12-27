package io.github.nsfeconomy.listeners;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.commands.TradeCommand;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles player-related events for NSF Economy
 */
public class PlayerListener implements Listener {

    private final NSFEconomy plugin;
    
    // Track players who were warned about permit expiry
    private final Map<UUID, Long> permitWarnings = new HashMap<>();
    // Cooldown for permit warnings (5 minutes)
    private static final long WARNING_COOLDOWN = 5 * 60 * 1000;

    public PlayerListener(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle player joining - check for any pending notifications
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Check for outstanding taxes (delayed to let player fully load)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                checkTaxReminders(player);
                checkPermitExpiry(player);
                checkWithdrawalQueue(player);
            }
        }, 60L); // 3 seconds delay
    }

    /**
     * Handle player quitting - cleanup trade sessions
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Clean up any active trade sessions
        TradeCommand tradeCommand = plugin.getTradeCommand();
        if (tradeCommand != null) {
            tradeCommand.handlePlayerQuit(player.getUniqueId());
        }
        
        // Clear permit warning cache
        permitWarnings.remove(player.getUniqueId());
    }

    /**
     * Enforce dimension permits when player tries to enter restricted dimensions
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!plugin.getConfig().getBoolean("permits.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        World.Environment targetEnv = null;
        
        // Determine target dimension
        if (event.getTo() != null && event.getTo().getWorld() != null) {
            targetEnv = event.getTo().getWorld().getEnvironment();
        }
        
        if (targetEnv == null) {
            return;
        }

        String dimension = getDimensionName(targetEnv);
        
        // Check if this dimension requires a permit
        if (!plugin.getConfig().contains("permits." + dimension)) {
            return;
        }

        // Check for bypass permission
        if (player.hasPermission("nsf.permit.bypass")) {
            return;
        }

        // Check if player has valid permit
        if (!plugin.getPermitManager().hasValidPermit(player.getUniqueId(), dimension)) {
            event.setCancelled(true);
            
            double price = plugin.getPermitManager().getPermitPrice(dimension);
            player.sendMessage(plugin.colorize("&c═══════════════════════════════════"));
            player.sendMessage(plugin.colorize("&c⚠ ACCESS DENIED"));
            player.sendMessage(plugin.colorize("&7You need a &e" + capitalize(dimension) + " Permit &7to enter!"));
            player.sendMessage(plugin.colorize("&7Price: &e" + plugin.getCurrencyManager().formatCurrency(price)));
            player.sendMessage(plugin.colorize("&7Purchase at any bank: &e/permit buy " + dimension));
            player.sendMessage(plugin.colorize("&c═══════════════════════════════════"));
        }
    }

    /**
     * Check dimension access on teleport (for command-based teleports)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfig().getBoolean("permits.enabled", true)) {
            return;
        }

        // Only check cross-world teleports
        if (event.getFrom().getWorld() == null || event.getTo() == null || 
            event.getTo().getWorld() == null) {
            return;
        }

        World.Environment fromEnv = event.getFrom().getWorld().getEnvironment();
        World.Environment toEnv = event.getTo().getWorld().getEnvironment();

        // Skip if same dimension
        if (fromEnv == toEnv) {
            return;
        }

        Player player = event.getPlayer();
        String dimension = getDimensionName(toEnv);

        // Check if this dimension requires a permit
        if (!plugin.getConfig().contains("permits." + dimension)) {
            return;
        }

        // Check for bypass permission
        if (player.hasPermission("nsf.permit.bypass")) {
            return;
        }

        // Skip ENDER_PEARL and similar - they're usually short range
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }

        if (!plugin.getPermitManager().hasValidPermit(player.getUniqueId(), dimension)) {
            event.setCancelled(true);
            
            double price = plugin.getPermitManager().getPermitPrice(dimension);
            player.sendMessage(plugin.colorize("&c⚠ You need a &e" + capitalize(dimension) + 
                " Permit &cto teleport there!"));
            player.sendMessage(plugin.colorize("&7Purchase at any bank: &e/permit buy " + dimension));
        }
    }

    /**
     * Warn players in restricted dimensions when permit is about to expire
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check significant movement (block change)
        if (!hasChangedBlock(event.getFrom(), event.getTo())) {
            return;
        }

        if (!plugin.getConfig().getBoolean("permits.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        World.Environment env = player.getWorld().getEnvironment();
        String dimension = getDimensionName(env);

        // Check if this dimension requires a permit
        if (!plugin.getConfig().contains("permits." + dimension)) {
            return;
        }

        // Check for bypass
        if (player.hasPermission("nsf.permit.bypass")) {
            return;
        }

        // Check warning cooldown
        Long lastWarning = permitWarnings.get(player.getUniqueId());
        if (lastWarning != null && System.currentTimeMillis() - lastWarning < WARNING_COOLDOWN) {
            return;
        }

        // Check permit status
        var permit = plugin.getPermitManager().getPermit(player.getUniqueId(), dimension);
        if (permit == null || permit.isExpired()) {
            // Permit expired while in dimension!
            permitWarnings.put(player.getUniqueId(), System.currentTimeMillis());
            
            player.sendMessage(plugin.colorize("&c⚠ &lWARNING: &cYour " + capitalize(dimension) + 
                " permit has EXPIRED!"));
            player.sendMessage(plugin.colorize("&7Return to the Overworld or renew at a bank!"));
            player.sendMessage(plugin.colorize("&7Use: &e/permit extend " + dimension));
        } else if (permit.getDaysRemaining() <= 1) {
            // Warn about imminent expiry
            permitWarnings.put(player.getUniqueId(), System.currentTimeMillis());
            
            player.sendMessage(plugin.colorize("&e⚠ Your " + capitalize(dimension) + 
                " permit expires in &c" + permit.getDaysRemaining() + " day(s)&e!"));
            player.sendMessage(plugin.colorize("&7Extend at any bank: &e/permit extend " + dimension));
        }
    }

    /**
     * Handle player death - potential F-note loss
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // F-notes drop as items naturally since they're written books
        // This is intentional - adds risk to carrying large amounts
        
        Player player = event.getEntity();
        
        // Count F-notes being dropped
        double droppedValue = event.getDrops().stream()
            .filter(item -> plugin.getCurrencyManager().isValidFNote(item))
            .mapToDouble(item -> plugin.getCurrencyManager().getFNoteValue(item))
            .sum();

        if (droppedValue > 0) {
            plugin.getLogger().info(player.getName() + " died carrying " + 
                plugin.getCurrencyManager().formatCurrency(droppedValue) + " in F-notes");
        }
    }

    /**
     * Handle respawn - inform about any dropped currency
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Could add notifications about dropped F-notes here if needed
    }

    // ==================== Helper Methods ====================

    private void checkTaxReminders(Player player) {
        double owed = plugin.getTaxManager().getTotalOwed(player.getUniqueId());
        if (owed > 0) {
            player.sendMessage(plugin.colorize("&6[Tax Notice] &7You have &c" + 
                plugin.getCurrencyManager().formatCurrency(owed) + " &7in outstanding taxes."));
            player.sendMessage(plugin.colorize("&7View details: &e/tax owed"));
        }
    }

    private void checkPermitExpiry(Player player) {
        var permits = plugin.getPermitManager().getPlayerPermits(player.getUniqueId());
        for (var permit : permits) {
            if (permit.getDaysRemaining() <= 3 && permit.getDaysRemaining() > 0) {
                player.sendMessage(plugin.colorize("&e[Permit Notice] &7Your &f" + 
                    capitalize(permit.getDimension()) + " &7permit expires in &e" + 
                    permit.getDaysRemaining() + " day(s)&7!"));
            }
        }
    }

    private void checkWithdrawalQueue(Player player) {
        // Check if player has pending withdrawals in queue
        int queuePosition = plugin.getBankManager().getQueuePosition(player.getUniqueId());
        if (queuePosition > 0) {
            player.sendMessage(plugin.colorize("&6[Bank Notice] &7You have a pending withdrawal."));
            player.sendMessage(plugin.colorize("&7Queue position: &e#" + queuePosition));
            player.sendMessage(plugin.colorize("&7Check status: &e/bank queue"));
        }
    }

    private String getDimensionName(World.Environment env) {
        return switch (env) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private boolean hasChangedBlock(Location from, Location to) {
        if (to == null) return false;
        return from.getBlockX() != to.getBlockX() ||
               from.getBlockY() != to.getBlockY() ||
               from.getBlockZ() != to.getBlockZ();
    }
}
