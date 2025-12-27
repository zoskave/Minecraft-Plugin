package io.github.nsfeconomy.commands;

import io.github.nsfeconomy.NSFEconomy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles /trade commands for secure player-to-player trading with sales tax
 */
public class TradeCommand implements CommandExecutor, TabCompleter {

    private final NSFEconomy plugin;
    
    // Active trade sessions: initiator UUID -> TradeSession
    private final Map<UUID, TradeSession> activeTrades = new ConcurrentHashMap<>();
    // Pending trade requests: target UUID -> initiator UUID
    private final Map<UUID, UUID> pendingRequests = new ConcurrentHashMap<>();

    public TradeCommand(NSFEconomy plugin) {
        this.plugin = plugin;
        plugin.getCommand("trade").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "request" -> handleRequest(player, args);
            case "accept" -> handleAccept(player, args);
            case "decline" -> handleDecline(player, args);
            case "offer" -> handleOffer(player, args);
            case "confirm" -> handleConfirm(player, args);
            case "cancel" -> handleCancel(player, args);
            case "status" -> handleStatus(player, args);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleRequest(Player player, String[] args) {
        if (!player.hasPermission("nsf.trade")) {
            player.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.colorize("&cUsage: /trade request <player>"));
            return;
        }

        // Check if already in a trade
        if (activeTrades.containsKey(player.getUniqueId())) {
            player.sendMessage(plugin.colorize("&cYou are already in an active trade. Use /trade cancel first."));
            return;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            player.sendMessage(plugin.colorize("&cPlayer not found: " + args[1]));
            return;
        }

        if (target.equals(player)) {
            player.sendMessage(plugin.colorize("&cYou cannot trade with yourself."));
            return;
        }

        // Check if target is already in a trade
        if (activeTrades.containsKey(target.getUniqueId())) {
            player.sendMessage(plugin.colorize("&c" + target.getName() + " is already in a trade."));
            return;
        }

        // Check proximity if configured
        double maxDistance = plugin.getConfig().getDouble("trade.max_distance", 10.0);
        if (maxDistance > 0 && player.getLocation().distance(target.getLocation()) > maxDistance) {
            player.sendMessage(plugin.colorize("&cYou must be within " + maxDistance + " blocks to trade."));
            return;
        }

        // Send trade request
        pendingRequests.put(target.getUniqueId(), player.getUniqueId());
        
        player.sendMessage(plugin.colorize("&aTrade request sent to " + target.getName() + "!"));
        player.sendMessage(plugin.colorize("&7Waiting for them to accept..."));
        
        target.sendMessage(plugin.colorize("&6══════════════════════════════════"));
        target.sendMessage(plugin.colorize("&e" + player.getName() + " &7wants to trade with you!"));
        target.sendMessage(plugin.colorize("&7Use &a/trade accept &7to begin trading"));
        target.sendMessage(plugin.colorize("&7Use &c/trade decline &7to refuse"));
        target.sendMessage(plugin.colorize("&6══════════════════════════════════"));

        // Expire request after 60 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pendingRequests.get(target.getUniqueId()) != null &&
                pendingRequests.get(target.getUniqueId()).equals(player.getUniqueId())) {
                pendingRequests.remove(target.getUniqueId());
                if (player.isOnline()) {
                    player.sendMessage(plugin.colorize("&cTrade request to " + target.getName() + " expired."));
                }
            }
        }, 20L * 60); // 60 seconds
    }

    private void handleAccept(Player player, String[] args) {
        UUID initiatorId = pendingRequests.get(player.getUniqueId());
        
        if (initiatorId == null) {
            player.sendMessage(plugin.colorize("&cYou have no pending trade requests."));
            return;
        }

        Player initiator = plugin.getServer().getPlayer(initiatorId);
        if (initiator == null || !initiator.isOnline()) {
            pendingRequests.remove(player.getUniqueId());
            player.sendMessage(plugin.colorize("&cThe player who requested the trade is no longer online."));
            return;
        }

        // Create trade session
        TradeSession session = new TradeSession(initiator.getUniqueId(), player.getUniqueId());
        activeTrades.put(initiator.getUniqueId(), session);
        activeTrades.put(player.getUniqueId(), session);
        pendingRequests.remove(player.getUniqueId());

        player.sendMessage(plugin.colorize("&aTrade accepted! Trading with " + initiator.getName()));
        initiator.sendMessage(plugin.colorize("&a" + player.getName() + " accepted your trade request!"));
        
        sendTradeInstructions(player);
        sendTradeInstructions(initiator);
    }

    private void handleDecline(Player player, String[] args) {
        UUID initiatorId = pendingRequests.remove(player.getUniqueId());
        
        if (initiatorId == null) {
            player.sendMessage(plugin.colorize("&cYou have no pending trade requests."));
            return;
        }

        Player initiator = plugin.getServer().getPlayer(initiatorId);
        
        player.sendMessage(plugin.colorize("&eTrade request declined."));
        if (initiator != null && initiator.isOnline()) {
            initiator.sendMessage(plugin.colorize("&c" + player.getName() + " declined your trade request."));
        }
    }

    private void handleOffer(Player player, String[] args) {
        TradeSession session = activeTrades.get(player.getUniqueId());
        
        if (session == null) {
            player.sendMessage(plugin.colorize("&cYou are not in an active trade."));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.colorize("&cUsage: /trade offer <F$amount>"));
            player.sendMessage(plugin.colorize("&7Or hold an item and use: /trade offer hand"));
            return;
        }

        UUID partnerId = session.getPartner(player.getUniqueId());
        Player partner = plugin.getServer().getPlayer(partnerId);
        
        if (partner == null || !partner.isOnline()) {
            endTrade(player.getUniqueId(), "Partner disconnected.");
            return;
        }

        // Reset confirmations when offer changes
        session.resetConfirmations();

        if (args[1].equalsIgnoreCase("hand")) {
            // Offer item in hand
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.getType().isAir()) {
                player.sendMessage(plugin.colorize("&cYou must be holding an item."));
                return;
            }
            
            session.setItemOffer(player.getUniqueId(), item.clone());
            
            player.sendMessage(plugin.colorize("&aOffered: &f" + item.getAmount() + "x " + 
                formatItemName(item.getType().name())));
            partner.sendMessage(plugin.colorize("&e" + player.getName() + " &7offered: &f" + 
                item.getAmount() + "x " + formatItemName(item.getType().name())));
        } else {
            // Offer currency
            try {
                String amountStr = args[1].replace(plugin.getCurrencyManager().getCurrencySymbol(), "");
                double amount = Double.parseDouble(amountStr);
                
                if (amount < 0) throw new NumberFormatException();
                
                session.setCurrencyOffer(player.getUniqueId(), amount);
                
                String formatted = plugin.getCurrencyManager().formatCurrency(amount);
                player.sendMessage(plugin.colorize("&aOffered: &e" + formatted));
                partner.sendMessage(plugin.colorize("&e" + player.getName() + " &7offered: &e" + formatted));
                
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getMessage("error_invalid_amount"));
            }
        }

        showTradeStatus(player, session);
        showTradeStatus(partner, session);
    }

    private void handleConfirm(Player player, String[] args) {
        TradeSession session = activeTrades.get(player.getUniqueId());
        
        if (session == null) {
            player.sendMessage(plugin.colorize("&cYou are not in an active trade."));
            return;
        }

        UUID partnerId = session.getPartner(player.getUniqueId());
        Player partner = plugin.getServer().getPlayer(partnerId);
        
        if (partner == null || !partner.isOnline()) {
            endTrade(player.getUniqueId(), "Partner disconnected.");
            return;
        }

        session.confirm(player.getUniqueId());
        
        player.sendMessage(plugin.colorize("&aYou confirmed the trade!"));
        partner.sendMessage(plugin.colorize("&e" + player.getName() + " &7confirmed the trade."));

        // Check if both confirmed
        if (session.bothConfirmed()) {
            executeTrade(session);
        } else {
            partner.sendMessage(plugin.colorize("&7Use &a/trade confirm &7to complete the trade."));
        }
    }

    private void handleCancel(Player player, String[] args) {
        TradeSession session = activeTrades.get(player.getUniqueId());
        
        if (session == null) {
            // Check for pending requests
            pendingRequests.values().removeIf(id -> id.equals(player.getUniqueId()));
            player.sendMessage(plugin.colorize("&ePending trade requests cancelled."));
            return;
        }

        UUID partnerId = session.getPartner(player.getUniqueId());
        endTrade(player.getUniqueId(), player.getName() + " cancelled the trade.");
    }

    private void handleStatus(Player player, String[] args) {
        TradeSession session = activeTrades.get(player.getUniqueId());
        
        if (session == null) {
            player.sendMessage(plugin.colorize("&cYou are not in an active trade."));
            return;
        }

        showTradeStatus(player, session);
    }

    private void executeTrade(TradeSession session) {
        Player player1 = plugin.getServer().getPlayer(session.getPlayer1());
        Player player2 = plugin.getServer().getPlayer(session.getPlayer2());
        
        if (player1 == null || player2 == null) {
            if (player1 != null) endTrade(session.getPlayer1(), "Partner disconnected.");
            if (player2 != null) endTrade(session.getPlayer2(), "Partner disconnected.");
            return;
        }

        // Calculate total monetary value for sales tax
        double totalValue = session.getCurrencyOffer(session.getPlayer1()) + 
                           session.getCurrencyOffer(session.getPlayer2());
        double salesTaxRate = plugin.getConfig().getDouble("tax.sales_tax.rate", 0.05);
        double salesTax = totalValue * salesTaxRate;

        // TODO: Verify both players have the items/currency they offered
        // For now, do basic validation
        
        double p1Currency = session.getCurrencyOffer(session.getPlayer1());
        double p2Currency = session.getCurrencyOffer(session.getPlayer2());
        ItemStack p1Item = session.getItemOffer(session.getPlayer1());
        ItemStack p2Item = session.getItemOffer(session.getPlayer2());

        // Execute swaps
        boolean success = true;
        
        // TODO: Implement actual F-note transfer using CurrencyManager
        // For now, log the trade
        
        // Item swaps
        if (p1Item != null && !p1Item.getType().isAir()) {
            player1.getInventory().removeItem(p1Item);
            player2.getInventory().addItem(p1Item);
        }
        
        if (p2Item != null && !p2Item.getType().isAir()) {
            player2.getInventory().removeItem(p2Item);
            player1.getInventory().addItem(p2Item);
        }

        if (success) {
            // Log transaction
            plugin.getDatabaseManager().logTransaction(
                session.getPlayer1(), "trade",
                "Trade with " + player2.getName(),
                p2Currency - p1Currency, null
            );
            plugin.getDatabaseManager().logTransaction(
                session.getPlayer2(), "trade", 
                "Trade with " + player1.getName(),
                p1Currency - p2Currency, null
            );

            // Create tax obligation for sales tax if significant
            if (salesTax >= 0.01) {
                // Split tax between both parties
                plugin.getTaxManager().createObligation(session.getPlayer1(), "sales", salesTax / 2);
                plugin.getTaxManager().createObligation(session.getPlayer2(), "sales", salesTax / 2);
            }

            player1.sendMessage(plugin.colorize("&a═══════════════════════════════"));
            player1.sendMessage(plugin.colorize("&a✓ Trade completed with " + player2.getName() + "!"));
            if (salesTax > 0) {
                player1.sendMessage(plugin.colorize("&7Sales tax owed: &e" + 
                    plugin.getCurrencyManager().formatCurrency(salesTax / 2)));
            }
            player1.sendMessage(plugin.colorize("&a═══════════════════════════════"));

            player2.sendMessage(plugin.colorize("&a═══════════════════════════════"));
            player2.sendMessage(plugin.colorize("&a✓ Trade completed with " + player1.getName() + "!"));
            if (salesTax > 0) {
                player2.sendMessage(plugin.colorize("&7Sales tax owed: &e" + 
                    plugin.getCurrencyManager().formatCurrency(salesTax / 2)));
            }
            player2.sendMessage(plugin.colorize("&a═══════════════════════════════"));
        }

        // Clean up
        activeTrades.remove(session.getPlayer1());
        activeTrades.remove(session.getPlayer2());
    }

    private void endTrade(UUID playerId, String reason) {
        TradeSession session = activeTrades.remove(playerId);
        if (session != null) {
            UUID partnerId = session.getPartner(playerId);
            activeTrades.remove(partnerId);
            
            Player player = plugin.getServer().getPlayer(playerId);
            Player partner = plugin.getServer().getPlayer(partnerId);
            
            if (player != null && player.isOnline()) {
                player.sendMessage(plugin.colorize("&cTrade ended: " + reason));
            }
            if (partner != null && partner.isOnline()) {
                partner.sendMessage(plugin.colorize("&cTrade ended: " + reason));
            }
        }
    }

    private void showTradeStatus(Player player, TradeSession session) {
        UUID partnerId = session.getPartner(player.getUniqueId());
        Player partner = plugin.getServer().getPlayer(partnerId);
        String partnerName = partner != null ? partner.getName() : "Unknown";

        player.sendMessage(plugin.colorize("&6══════ &lTrade Status &r&6══════"));
        
        // Your offer
        player.sendMessage(plugin.colorize("&a▸ Your Offer:"));
        double yourCurrency = session.getCurrencyOffer(player.getUniqueId());
        ItemStack yourItem = session.getItemOffer(player.getUniqueId());
        
        if (yourCurrency > 0) {
            player.sendMessage(plugin.colorize("    &e" + plugin.getCurrencyManager().formatCurrency(yourCurrency)));
        }
        if (yourItem != null && !yourItem.getType().isAir()) {
            player.sendMessage(plugin.colorize("    &f" + yourItem.getAmount() + "x " + 
                formatItemName(yourItem.getType().name())));
        }
        if (yourCurrency <= 0 && (yourItem == null || yourItem.getType().isAir())) {
            player.sendMessage(plugin.colorize("    &7(nothing)"));
        }
        
        String yourStatus = session.hasConfirmed(player.getUniqueId()) ? "&a✓ CONFIRMED" : "&e○ Not confirmed";
        player.sendMessage(plugin.colorize("    " + yourStatus));

        // Their offer
        player.sendMessage(plugin.colorize("&c▸ " + partnerName + "'s Offer:"));
        double theirCurrency = session.getCurrencyOffer(partnerId);
        ItemStack theirItem = session.getItemOffer(partnerId);
        
        if (theirCurrency > 0) {
            player.sendMessage(plugin.colorize("    &e" + plugin.getCurrencyManager().formatCurrency(theirCurrency)));
        }
        if (theirItem != null && !theirItem.getType().isAir()) {
            player.sendMessage(plugin.colorize("    &f" + theirItem.getAmount() + "x " + 
                formatItemName(theirItem.getType().name())));
        }
        if (theirCurrency <= 0 && (theirItem == null || theirItem.getType().isAir())) {
            player.sendMessage(plugin.colorize("    &7(nothing)"));
        }
        
        String theirStatus = session.hasConfirmed(partnerId) ? "&a✓ CONFIRMED" : "&e○ Not confirmed";
        player.sendMessage(plugin.colorize("    " + theirStatus));

        player.sendMessage(plugin.colorize("&6═══════════════════════════════"));
    }

    private void sendTradeInstructions(Player player) {
        player.sendMessage(plugin.colorize("&7═══════════════════════════════"));
        player.sendMessage(plugin.colorize("&e/trade offer <F$amount> &7- Offer currency"));
        player.sendMessage(plugin.colorize("&e/trade offer hand &7- Offer held item"));
        player.sendMessage(plugin.colorize("&e/trade confirm &7- Confirm the trade"));
        player.sendMessage(plugin.colorize("&e/trade cancel &7- Cancel the trade"));
        player.sendMessage(plugin.colorize("&e/trade status &7- View current offers"));
        player.sendMessage(plugin.colorize("&7═══════════════════════════════"));
    }

    private String formatItemName(String name) {
        return name.toLowerCase().replace("_", " ");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy - Trade Commands &r&6══════"));
        sender.sendMessage(plugin.colorize("&e/trade request <player> &7- Request a trade"));
        sender.sendMessage(plugin.colorize("&e/trade accept &7- Accept a trade request"));
        sender.sendMessage(plugin.colorize("&e/trade decline &7- Decline a trade request"));
        sender.sendMessage(plugin.colorize("&e/trade offer <amount|hand> &7- Offer currency/item"));
        sender.sendMessage(plugin.colorize("&e/trade confirm &7- Confirm the trade"));
        sender.sendMessage(plugin.colorize("&e/trade cancel &7- Cancel current trade"));
        sender.sendMessage(plugin.colorize("&e/trade status &7- View trade status"));
        sender.sendMessage(plugin.colorize("&6════════════════════════════════════════"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList("request", "accept", "decline", "offer", "confirm", "cancel", "status"));
            return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("request")) {
                return null; // Show player names
            }
            if (args[0].equalsIgnoreCase("offer")) {
                completions.addAll(Arrays.asList("hand", "F$1", "F$10", "F$100"));
            }
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }

    /**
     * Clean up trades when players disconnect
     */
    public void handlePlayerQuit(UUID playerId) {
        endTrade(playerId, "Player disconnected.");
        pendingRequests.remove(playerId);
        pendingRequests.values().removeIf(id -> id.equals(playerId));
    }

    // Inner class for trade session
    private static class TradeSession {
        private final UUID player1;
        private final UUID player2;
        private double currency1 = 0;
        private double currency2 = 0;
        private ItemStack item1 = null;
        private ItemStack item2 = null;
        private boolean confirmed1 = false;
        private boolean confirmed2 = false;

        public TradeSession(UUID player1, UUID player2) {
            this.player1 = player1;
            this.player2 = player2;
        }

        public UUID getPlayer1() { return player1; }
        public UUID getPlayer2() { return player2; }

        public UUID getPartner(UUID playerId) {
            return playerId.equals(player1) ? player2 : player1;
        }

        public void setCurrencyOffer(UUID playerId, double amount) {
            if (playerId.equals(player1)) currency1 = amount;
            else currency2 = amount;
        }

        public double getCurrencyOffer(UUID playerId) {
            return playerId.equals(player1) ? currency1 : currency2;
        }

        public void setItemOffer(UUID playerId, ItemStack item) {
            if (playerId.equals(player1)) item1 = item;
            else item2 = item;
        }

        public ItemStack getItemOffer(UUID playerId) {
            return playerId.equals(player1) ? item1 : item2;
        }

        public void confirm(UUID playerId) {
            if (playerId.equals(player1)) confirmed1 = true;
            else confirmed2 = true;
        }

        public boolean hasConfirmed(UUID playerId) {
            return playerId.equals(player1) ? confirmed1 : confirmed2;
        }

        public boolean bothConfirmed() {
            return confirmed1 && confirmed2;
        }

        public void resetConfirmations() {
            confirmed1 = false;
            confirmed2 = false;
        }
    }
}
