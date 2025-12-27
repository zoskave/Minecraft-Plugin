package io.github.nsfeconomy.commands;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.bank.BankLocation;
import io.github.nsfeconomy.bank.BankManager;
import io.github.nsfeconomy.currency.CurrencyManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all /bank commands for the NSF Economy plugin.
 */
public class BankCommand implements CommandExecutor, TabCompleter {

    private final NSFEconomy plugin;

    public BankCommand(NSFEconomy plugin) {
        this.plugin = plugin;
        plugin.getCommand("bank").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "deposit" -> handleDeposit(sender, args);
            case "withdraw" -> handleWithdraw(sender, args);
            case "exchange" -> handleExchange(sender, args);
            case "balance" -> handleBalance(sender, args);
            case "queue" -> handleQueue(sender, args);
            case "buyout" -> handleBuyout(sender, args);
            
            // Admin commands
            case "create" -> handleCreate(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender, args);
            case "reserve" -> handleReserve(sender, args);
            case "mint" -> handleMint(sender, args);
            case "destroy" -> handleDestroy(sender, args);
            case "pay" -> handlePay(sender, args);
            case "grant" -> handleGrant(sender, args);
            case "setreserve" -> handleSetReserve(sender, args);
            case "audit" -> handleAudit(sender, args);
            
            default -> sendHelp(sender);
        }

        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Player Commands
    // ══════════════════════════════════════════════════════════════════════

    private void handleDeposit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bank.deposit")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank deposit <stars>"));
            return;
        }

        int starAmount;
        try {
            starAmount = Integer.parseInt(args[1]);
            if (starAmount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        BankManager.DepositResult result = plugin.getBankManager().processDeposit(player, starAmount);

        if (result.isSuccess()) {
            // Format the notes issued
            StringBuilder notes = new StringBuilder();
            CurrencyManager cm = plugin.getCurrencyManager();
            for (Map.Entry<Integer, Integer> entry : result.getNotesIssued().entrySet()) {
                if (!notes.isEmpty()) notes.append(", ");
                notes.append(entry.getValue()).append("x ").append(cm.getCurrencySymbol()).append(entry.getKey());
            }
            
            String message = plugin.getRawMessage("deposit_success")
                .replace("{stars}", String.valueOf(result.getStarsUsed()))
                .replace("{notes}", notes.toString());
            sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + message));
        } else {
            handleDepositError(player, result.getReason(), result.getStarsUsed());
        }
    }

    private void handleDepositError(Player player, String reason, long value) {
        switch (reason) {
            case "not_at_bank" -> player.sendMessage(plugin.getMessage("not_at_bank"));
            case "rate_limited" -> player.sendMessage(plugin.colorize("&cYou're doing that too fast. Please wait."));
            case "minimum_deposit" -> player.sendMessage(plugin.colorize(
                "&cMinimum deposit is " + value + " Nether Stars (" + 
                plugin.getCurrencyManager().getCurrencySymbol() + "1)."));
            case "insufficient_stars" -> player.sendMessage(plugin.getMessage("error_insufficient_stars"));
            case "amount_too_small" -> player.sendMessage(plugin.colorize("&cAmount too small for any F-notes."));
            default -> player.sendMessage(plugin.getMessage("error_generic"));
        }
    }

    private void handleWithdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bank.withdraw")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank withdraw <F$amount> [--queue]"));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1].replace(plugin.getCurrencyManager().getCurrencySymbol(), ""));
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        boolean useQueue = args.length >= 3 && "--queue".equalsIgnoreCase(args[2]);

        BankManager.WithdrawalResult result = plugin.getBankManager().processWithdrawal(player, amount);

        if (result.isSuccess()) {
            String message = plugin.getRawMessage("withdraw_success")
                .replace("{stars}", String.valueOf((long) result.getValue()));
            sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + message));
        } else {
            handleWithdrawError(player, result.getReason(), result.getValue(), amount, useQueue);
        }
    }

    private void handleWithdrawError(Player player, String reason, double value, double requestedAmount, boolean useQueue) {
        switch (reason) {
            case "not_at_bank" -> player.sendMessage(plugin.getMessage("not_at_bank"));
            case "rate_limited" -> player.sendMessage(plugin.colorize("&cYou're doing that too fast. Please wait."));
            case "cooldown" -> player.sendMessage(plugin.colorize("&cYou must wait before withdrawing again."));
            case "emergency_limit" -> player.sendMessage(plugin.colorize(
                "&cEmergency mode active. Maximum withdrawal: " + 
                plugin.getCurrencyManager().formatCurrency(value)));
            case "insufficient_notes" -> player.sendMessage(plugin.getMessage("withdraw_insufficient_notes"));
            case "insufficient_reserve" -> {
                player.sendMessage(plugin.getMessage("withdraw_insufficient_reserve"));
                if (useQueue && player.hasPermission("nsf.bank.queue")) {
                    if (plugin.getBankManager().addToWithdrawalQueue(player, requestedAmount)) {
                        int position = plugin.getBankManager().getQueuePosition(player.getUniqueId());
                        String msg = plugin.getRawMessage("queue_joined").replace("{position}", String.valueOf(position));
                        player.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + msg));
                    }
                } else {
                    player.sendMessage(plugin.colorize("&7Tip: Use --queue to join the withdrawal queue."));
                }
            }
            default -> player.sendMessage(plugin.getMessage("error_generic"));
        }
    }

    private void handleExchange(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bank.exchange")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 4) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank exchange <from-denom> <to-denom> <quantity>"));
            sender.sendMessage(plugin.colorize("&7Example: /bank exchange 1 10 10 (exchange 10x F$1 for 1x F$10)"));
            return;
        }

        CurrencyManager cm = plugin.getCurrencyManager();
        
        int fromDenom, toDenom, quantity;
        try {
            fromDenom = Integer.parseInt(args[1]);
            toDenom = Integer.parseInt(args[2]);
            quantity = Integer.parseInt(args[3]);
            
            if (!cm.getDenominations().contains(fromDenom) || !cm.getDenominations().contains(toDenom)) {
                sender.sendMessage(plugin.colorize("&cInvalid denomination. Available: " + cm.getDenominations()));
                return;
            }
            if (quantity <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        if (!plugin.getBankManager().isAtBank(player)) {
            sender.sendMessage(plugin.getMessage("not_at_bank"));
            return;
        }

        // Calculate exchange
        double totalValue = fromDenom * quantity;
        if (totalValue % toDenom != 0) {
            sender.sendMessage(plugin.colorize("&cCannot evenly exchange. Total value must be divisible by target denomination."));
            return;
        }
        int newQuantity = (int) (totalValue / toDenom);

        // TODO: Implement actual exchange logic (remove old notes, create new notes)
        // For now, just send success message
        String message = plugin.getRawMessage("exchange_success")
            .replace("{old_amount}", String.valueOf(quantity))
            .replace("{old_denom}", String.valueOf(fromDenom))
            .replace("{new_amount}", String.valueOf(newQuantity))
            .replace("{new_denom}", String.valueOf(toDenom));
        sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + message));
    }

    private void handleBalance(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bank.balance")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        CurrencyManager cm = plugin.getCurrencyManager();
        Map<Integer, Integer> notes = cm.countNotesInInventory(player.getInventory().getContents());
        double totalValue = cm.calculateTotalValue(notes);

        sender.sendMessage(plugin.colorize("&6══════ &lYour F-Note Balance &r&6══════"));
        
        boolean hasNotes = false;
        for (Integer denom : cm.getDenominations()) {
            int count = notes.getOrDefault(denom, 0);
            if (count > 0) {
                hasNotes = true;
                sender.sendMessage(plugin.colorize("  &f" + cm.getCurrencySymbol() + denom + " notes: &a" + count));
            }
        }
        
        if (!hasNotes) {
            sender.sendMessage(plugin.colorize("  &7No valid F-notes in inventory."));
        }
        
        sender.sendMessage(plugin.colorize("&6Total Value: &a" + cm.formatCurrency(totalValue)));
        sender.sendMessage(plugin.colorize("&6═══════════════════════════════"));
    }

    private void handleQueue(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bank.queue")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length >= 2 && "leave".equalsIgnoreCase(args[1])) {
            if (plugin.getBankManager().removeFromQueue(player.getUniqueId())) {
                sender.sendMessage(plugin.getMessage("queue_left"));
            } else {
                sender.sendMessage(plugin.colorize("&cYou're not in the withdrawal queue."));
            }
            return;
        }

        int position = plugin.getBankManager().getQueuePosition(player.getUniqueId());
        if (position == -1) {
            sender.sendMessage(plugin.colorize("&7You're not in the withdrawal queue."));
        } else {
            sender.sendMessage(plugin.colorize("&aYour queue position: &f" + position));
        }
    }

    private void handleBuyout(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!plugin.getConfig().getBoolean("buyout.enabled", false)) {
            sender.sendMessage(plugin.colorize("&cThe diamond buyout period is not active."));
            return;
        }

        // TODO: Implement diamond buyout logic
        sender.sendMessage(plugin.colorize("&7Diamond buyout feature coming soon."));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Admin Commands
    // ══════════════════════════════════════════════════════════════════════

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.admin.bank.create")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank create <main|branch> <name>"));
            return;
        }

        String type = args[1].toLowerCase();
        if (!type.equals("main") && !type.equals("branch")) {
            sender.sendMessage(plugin.colorize("&cType must be 'main' or 'branch'."));
            return;
        }

        String name = args[2];
        int radius = plugin.getConfig().getInt("bank.default_radius", 10);
        
        if (args.length >= 4) {
            try {
                radius = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {}
        }

        if (plugin.getBankManager().createBankLocation(name, type, player.getLocation(), radius)) {
            String message = plugin.getRawMessage("admin_location_created").replace("{name}", name);
            sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + message));
        } else {
            if (type.equals("main") && plugin.getBankManager().hasMainVault()) {
                sender.sendMessage(plugin.colorize("&cA main vault already exists. Only one is allowed."));
            } else {
                sender.sendMessage(plugin.colorize("&cFailed to create bank location. Name may already exist."));
            }
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.remove")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank remove <name>"));
            return;
        }

        String name = args[1];
        if (plugin.getBankManager().removeBankLocation(name)) {
            String message = plugin.getRawMessage("admin_location_removed").replace("{name}", name);
            sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + message));
        } else {
            sender.sendMessage(plugin.colorize("&cBank location not found: " + name));
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.list")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        Map<String, BankLocation> locations = plugin.getBankManager().getBankLocations();
        
        if (locations.isEmpty()) {
            sender.sendMessage(plugin.colorize("&7No bank locations created yet."));
            return;
        }

        sender.sendMessage(plugin.colorize("&6══════ &lBank Locations &r&6══════"));
        for (BankLocation loc : locations.values()) {
            String typeColor = loc.isMainVault() ? "&c" : "&a";
            sender.sendMessage(plugin.colorize(String.format(
                "  %s%s &7(%s) &fat &7%s: %d, %d, %d &8[r=%d]",
                typeColor, loc.getName(), loc.getType(),
                loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), loc.getRadius()
            )));
        }
        sender.sendMessage(plugin.colorize("&6══════════════════════════════"));
    }

    private void handleReserve(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.reserve")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        BankManager.ReserveStats stats = plugin.getBankManager().getReserveStats();
        CurrencyManager cm = plugin.getCurrencyManager();

        sender.sendMessage(plugin.colorize("&6══════ &lReserve Status &r&6══════"));
        sender.sendMessage(plugin.colorize("  &fNether Stars in Vault: &a" + String.format("%,d", stats.getReserveStars())));
        sender.sendMessage(plugin.colorize("  &fTotal " + cm.getCurrencySymbol() + " Circulating: &e" + 
            cm.formatCurrency(stats.getTotalCirculating())));
        sender.sendMessage(plugin.colorize("  &fCirculating by denomination:"));
        
        for (Map.Entry<Integer, Long> entry : stats.getCirculatingByDenom().entrySet()) {
            if (entry.getValue() > 0) {
                sender.sendMessage(plugin.colorize("    &7" + cm.getCurrencySymbol() + entry.getKey() + 
                    ": &f" + String.format("%,d", entry.getValue()) + " notes"));
            }
        }
        
        String ratioColor = stats.getReserveRatio() < 0.05 ? "&c" : 
                           stats.getReserveRatio() < 0.10 ? "&e" : "&a";
        sender.sendMessage(plugin.colorize("  &fReserve Ratio: " + ratioColor + 
            String.format("%.2f%%", stats.getReserveRatio() * 100)));
        
        if (plugin.getBankManager().isEmergencyModeActive()) {
            sender.sendMessage(plugin.colorize("  &c&l⚠ EMERGENCY MODE ACTIVE"));
        }
        
        sender.sendMessage(plugin.colorize("&6══════════════════════════════"));
    }

    private void handleMint(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.mint")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank mint <denomination> <quantity> [player]"));
            return;
        }

        CurrencyManager cm = plugin.getCurrencyManager();
        
        int denomination, quantity;
        try {
            denomination = Integer.parseInt(args[1]);
            quantity = Integer.parseInt(args[2]);
            
            if (!cm.getDenominations().contains(denomination)) {
                sender.sendMessage(plugin.colorize("&cInvalid denomination. Available: " + cm.getDenominations()));
                return;
            }
            if (quantity <= 0 || quantity > 2304) {
                sender.sendMessage(plugin.colorize("&cQuantity must be between 1 and 2304."));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        Player targetPlayer = null;
        if (args.length >= 4) {
            targetPlayer = plugin.getServer().getPlayer(args[3]);
            if (targetPlayer == null) {
                sender.sendMessage(plugin.colorize("&cPlayer not found: " + args[3]));
                return;
            }
        } else if (sender instanceof Player) {
            targetPlayer = (Player) sender;
        } else {
            sender.sendMessage(plugin.colorize("&cYou must specify a player when using from console."));
            return;
        }

        // Create the notes
        List<ItemStack> notes = cm.createNotes(denomination, quantity, targetPlayer.getUniqueId());
        
        // Give to player
        for (ItemStack note : notes) {
            HashMap<Integer, ItemStack> overflow = targetPlayer.getInventory().addItem(note);
            for (ItemStack item : overflow.values()) {
                targetPlayer.getWorld().dropItemNaturally(targetPlayer.getLocation(), item);
            }
        }

        // Log transaction
        plugin.getDatabaseManager().logTransaction("mint", 
            sender instanceof Player ? ((Player) sender).getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000"),
            denomination * quantity, 0,
            "Admin mint: " + quantity + "x " + cm.getCurrencySymbol() + denomination + " to " + targetPlayer.getName());

        String message = plugin.getRawMessage("admin_mint_success")
            .replace("{amount}", String.valueOf(quantity))
            .replace("{denomination}", String.valueOf(denomination));
        sender.sendMessage(plugin.colorize(plugin.getConfig().getString("messages.prefix", "") + message));
        
        if (targetPlayer != sender) {
            targetPlayer.sendMessage(plugin.colorize("&aYou received " + quantity + "x " + 
                cm.getCurrencySymbol() + denomination + " notes from an admin."));
        }
    }

    private void handleDestroy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.admin.bank.destroy")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        // TODO: Implement destroy logic (remove notes from admin inventory)
        sender.sendMessage(plugin.colorize("&7Destroy command coming soon."));
    }

    private void handlePay(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.pay")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 4) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank pay <player> <amount> <reason>"));
            return;
        }

        // TODO: Implement pay logic
        sender.sendMessage(plugin.colorize("&7Pay command coming soon."));
    }

    private void handleGrant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.grant")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 4) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank grant <player> <amount> <reason>"));
            return;
        }

        // TODO: Implement grant logic
        sender.sendMessage(plugin.colorize("&7Grant command coming soon."));
    }

    private void handleSetReserve(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.setreserve")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bank setreserve <ratio>"));
            sender.sendMessage(plugin.colorize("&7Example: /bank setreserve 0.10 (10%)"));
            return;
        }

        try {
            double ratio = Double.parseDouble(args[1]);
            if (ratio < 0 || ratio > 1) {
                sender.sendMessage(plugin.colorize("&cRatio must be between 0.0 and 1.0"));
                return;
            }
            
            plugin.getConfig().set("bank.reserve_ratio_target", ratio);
            plugin.saveConfig();
            
            sender.sendMessage(plugin.colorize("&aTarget reserve ratio set to " + 
                String.format("%.1f%%", ratio * 100)));
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
        }
    }

    private void handleAudit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.bank.audit")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        // TODO: Generate comprehensive audit report
        sender.sendMessage(plugin.colorize("&6══════ &lAudit Report &r&6══════"));
        sender.sendMessage(plugin.colorize("&7Full audit report generation coming soon."));
        sender.sendMessage(plugin.colorize("&7For now, use /bank reserve for basic stats."));
        sender.sendMessage(plugin.colorize("&6══════════════════════════════"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy - Bank Commands &r&6══════"));
        sender.sendMessage(plugin.colorize("&e/bank deposit <stars> &7- Deposit Nether Stars"));
        sender.sendMessage(plugin.colorize("&e/bank withdraw <F$> &7- Withdraw Nether Stars"));
        sender.sendMessage(plugin.colorize("&e/bank exchange <from> <to> <qty> &7- Exchange denominations"));
        sender.sendMessage(plugin.colorize("&e/bank balance &7- Check your F-note balance"));
        sender.sendMessage(plugin.colorize("&e/bank queue &7- Check withdrawal queue position"));
        
        if (sender.hasPermission("nsf.admin.bank.create")) {
            sender.sendMessage(plugin.colorize("&c/bank create <main|branch> <name> &7- Create bank"));
            sender.sendMessage(plugin.colorize("&c/bank remove <name> &7- Remove bank"));
            sender.sendMessage(plugin.colorize("&c/bank list &7- List all banks"));
            sender.sendMessage(plugin.colorize("&c/bank reserve &7- View reserve status"));
            sender.sendMessage(plugin.colorize("&c/bank mint <denom> <qty> [player] &7- Mint F-notes"));
            sender.sendMessage(plugin.colorize("&c/bank audit &7- Generate audit report"));
        }
        
        sender.sendMessage(plugin.colorize("&6═══════════════════════════════════════════"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList(
                "deposit", "withdraw", "exchange", "balance", "queue", "buyout"
            ));
            if (sender.hasPermission("nsf.admin.bank.create")) {
                subCommands.addAll(Arrays.asList(
                    "create", "remove", "list", "reserve", "mint", "destroy", 
                    "pay", "grant", "setreserve", "audit"
                ));
            }
            return subCommands.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "create" -> completions.addAll(Arrays.asList("main", "branch"));
                case "remove", "list" -> completions.addAll(plugin.getBankManager().getBankLocations().keySet());
                case "mint", "exchange" -> completions.addAll(
                    plugin.getCurrencyManager().getDenominations().stream()
                        .map(String::valueOf)
                        .collect(Collectors.toList())
                );
                case "queue" -> completions.add("leave");
            }
        }
        
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("exchange")) {
                completions.addAll(
                    plugin.getCurrencyManager().getDenominations().stream()
                        .map(String::valueOf)
                        .collect(Collectors.toList())
                );
            }
            if (args[0].equalsIgnoreCase("mint") || args[0].equalsIgnoreCase("pay") || 
                args[0].equalsIgnoreCase("grant")) {
                return null; // Show player names
            }
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}
