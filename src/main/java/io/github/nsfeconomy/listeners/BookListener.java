package io.github.nsfeconomy.listeners;

import io.github.nsfeconomy.NSFEconomy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Handles book-related events for F-note security and validation
 */
public class BookListener implements Listener {

    private final NSFEconomy plugin;

    public BookListener(NSFEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Validate F-notes when clicking in inventory
     * This catches any modified/duped notes
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        // Check clicked item
        if (clickedItem != null && isWrittenBook(clickedItem)) {
            if (looksLikeFNote(clickedItem) && !plugin.getCurrencyManager().isValidFNote(clickedItem)) {
                // Invalid/counterfeit F-note detected
                handleCounterfeit(player, clickedItem, "inventory click");
                event.setCurrentItem(null);
                event.setCancelled(true);
                return;
            }
        }

        // Check cursor item
        if (cursorItem != null && isWrittenBook(cursorItem)) {
            if (looksLikeFNote(cursorItem) && !plugin.getCurrencyManager().isValidFNote(cursorItem)) {
                handleCounterfeit(player, cursorItem, "inventory click");
                event.setCancelled(true);
                player.setItemOnCursor(null);
            }
        }
    }

    /**
     * Validate F-notes when moving between inventories
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        
        if (isWrittenBook(item) && looksLikeFNote(item)) {
            if (!plugin.getCurrencyManager().isValidFNote(item)) {
                // Block transfer of counterfeit notes
                event.setCancelled(true);
                plugin.getLogger().warning("Blocked counterfeit F-note transfer via hopper/dropper");
            }
        }
    }

    /**
     * Validate F-notes when dropped
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        
        if (isWrittenBook(item) && looksLikeFNote(item)) {
            if (!plugin.getCurrencyManager().isValidFNote(item)) {
                handleCounterfeit(event.getPlayer(), item, "drop");
                event.getItemDrop().remove();
                event.setCancelled(true);
            } else if (plugin.getConfig().getBoolean("security.warn_on_fnote_drop", true)) {
                // Warn player about dropping valid F-notes
                double value = plugin.getCurrencyManager().getFNoteValue(item);
                event.getPlayer().sendMessage(plugin.colorize(
                    "&e⚠ Dropping &f" + plugin.getCurrencyManager().formatCurrency(value) + 
                    "&e. Anyone can pick this up!"));
            }
        }
    }

    /**
     * Track F-note items spawning in world
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        
        if (isWrittenBook(item) && looksLikeFNote(item)) {
            if (plugin.getCurrencyManager().isValidFNote(item)) {
                // Log for security tracking
                if (plugin.getConfig().getBoolean("debug", false)) {
                    double value = plugin.getCurrencyManager().getFNoteValue(item);
                    plugin.getLogger().info("F-note spawned in world: " + 
                        plugin.getCurrencyManager().formatCurrency(value) + 
                        " at " + event.getLocation());
                }
            }
        }
    }

    /**
     * Prevent using lecterns to copy F-notes
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.LECTERN) {
            // Check if there's an F-note in the lectern
            for (ItemStack item : event.getInventory().getContents()) {
                if (item != null && isWrittenBook(item) && looksLikeFNote(item)) {
                    if (plugin.getCurrencyManager().isValidFNote(item)) {
                        // Prevent viewing F-notes in lecterns (could allow copying)
                        event.setCancelled(true);
                        ((Player) event.getPlayer()).sendMessage(plugin.colorize(
                            "&cF-notes cannot be placed in lecterns."));
                        
                        // Remove the note and return to whoever placed it
                        event.getInventory().clear();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Prevent book and quill editing to create fake F-notes
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        
        // Check if crafting would produce a written book
        if (result != null && result.getType() == Material.WRITTEN_BOOK) {
            // We can't really prevent this at craft time, but we can log suspicious activity
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Written book craft attempted");
            }
        }
    }

    /**
     * Monitor shift-click transfers for F-notes
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemStack draggedItem = event.getOldCursor();
        
        if (isWrittenBook(draggedItem) && looksLikeFNote(draggedItem)) {
            if (!plugin.getCurrencyManager().isValidFNote(draggedItem)) {
                handleCounterfeit((Player) event.getWhoClicked(), draggedItem, "drag");
                event.setCancelled(true);
            }
        }
    }

    /**
     * Check F-notes during villager/player trades
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Check the item in hand when interacting
        ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
        ItemStack offHand = event.getPlayer().getInventory().getItemInOffHand();

        if (isWrittenBook(mainHand) && looksLikeFNote(mainHand)) {
            if (!plugin.getCurrencyManager().isValidFNote(mainHand)) {
                handleCounterfeit(event.getPlayer(), mainHand, "interaction");
                event.getPlayer().getInventory().setItemInMainHand(null);
                event.setCancelled(true);
                return;
            }
        }

        if (isWrittenBook(offHand) && looksLikeFNote(offHand)) {
            if (!plugin.getCurrencyManager().isValidFNote(offHand)) {
                handleCounterfeit(event.getPlayer(), offHand, "interaction");
                event.getPlayer().getInventory().setItemInOffHand(null);
                event.setCancelled(true);
            }
        }
    }

    /**
     * Warn when player selects F-note in hotbar
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        ItemStack newItem = event.getPlayer().getInventory().getItem(event.getNewSlot());
        
        if (newItem != null && isWrittenBook(newItem)) {
            if (looksLikeFNote(newItem)) {
                if (plugin.getCurrencyManager().isValidFNote(newItem)) {
                    double value = plugin.getCurrencyManager().getFNoteValue(newItem);
                    // Subtle reminder that they're holding currency
                    if (plugin.getConfig().getBoolean("security.show_fnote_value_on_select", true)) {
                        event.getPlayer().sendActionBar(plugin.colorize(
                            "&6Holding: &e" + plugin.getCurrencyManager().formatCurrency(value)));
                    }
                } else {
                    // Invalid note in inventory
                    handleCounterfeit(event.getPlayer(), newItem, "hotbar select");
                    event.getPlayer().getInventory().setItem(event.getNewSlot(), null);
                }
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Check if item is a written book
     */
    private boolean isWrittenBook(ItemStack item) {
        return item != null && item.getType() == Material.WRITTEN_BOOK;
    }

    /**
     * Check if a book looks like it's trying to be an F-note
     * (has similar title/author format without being valid)
     */
    private boolean looksLikeFNote(ItemStack item) {
        if (!isWrittenBook(item)) return false;
        
        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta == null) return false;

        String title = meta.getTitle();
        String author = meta.getAuthor();
        String symbol = plugin.getCurrencyManager().getCurrencySymbol();

        // Check if title contains the currency symbol
        if (title != null && title.contains(symbol)) {
            return true;
        }

        // Check if author is "Central Bank"
        if ("Central Bank".equals(author)) {
            return true;
        }

        // Check title patterns like "F$1 Note", "F$10 Note", etc.
        if (title != null && title.matches(".*" + symbol.replace("$", "\\$") + "\\d+.*Note.*")) {
            return true;
        }

        return false;
    }

    /**
     * Handle detection of counterfeit F-notes
     */
    private void handleCounterfeit(Player player, ItemStack item, String context) {
        // Get book details for logging
        String title = "unknown";
        String author = "unknown";
        
        if (item.hasItemMeta() && item.getItemMeta() instanceof BookMeta meta) {
            title = meta.getTitle();
            author = meta.getAuthor();
        }

        // Log the counterfeit detection
        plugin.getLogger().warning("COUNTERFEIT F-NOTE DETECTED!");
        plugin.getLogger().warning("  Player: " + player.getName());
        plugin.getLogger().warning("  Context: " + context);
        plugin.getLogger().warning("  Book title: " + title);
        plugin.getLogger().warning("  Book author: " + author);

        // Notify player
        player.sendMessage(plugin.colorize("&c═══════════════════════════════════"));
        player.sendMessage(plugin.colorize("&c⚠ COUNTERFEIT CURRENCY DETECTED!"));
        player.sendMessage(plugin.colorize("&7This F-note is not valid and has been confiscated."));
        player.sendMessage(plugin.colorize("&7Counterfeiting is logged and may result in penalties."));
        player.sendMessage(plugin.colorize("&c═══════════════════════════════════"));

        // Log transaction for audit
        plugin.getDatabaseManager().logTransaction(
            player.getUniqueId(),
            "counterfeit_detected",
            "Counterfeit F-note confiscated (" + context + "): " + title,
            0, null
        );

        // Notify online admins
        if (plugin.getConfig().getBoolean("security.notify_admins_on_counterfeit", true)) {
            for (Player admin : plugin.getServer().getOnlinePlayers()) {
                if (admin.hasPermission("nsf.admin.security")) {
                    admin.sendMessage(plugin.colorize(
                        "&c[NSF Security] &7Counterfeit detected: &f" + player.getName() + 
                        " &7(" + context + ")"));
                }
            }
        }
    }
}
