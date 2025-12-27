package io.github.nsfeconomy.commands;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.bank.BankLocation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles /nsf commands for general plugin administration and utilities
 */
public class NSFCommand implements CommandExecutor, TabCompleter {

    private final NSFEconomy plugin;

    public NSFCommand(NSFEconomy plugin) {
        this.plugin = plugin;
        plugin.getCommand("nsf").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "info" -> handleInfo(sender, args);
            case "reload" -> handleReload(sender, args);
            case "stats" -> handleStats(sender, args);
            case "economy" -> handleEconomy(sender, args);
            case "debug" -> handleDebug(sender, args);
            case "emergency" -> handleEmergency(sender, args);
            case "audit" -> handleAudit(sender, args);
            case "version" -> handleVersion(sender, args);
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleInfo(CommandSender sender, String[] args) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy &r&6══════"));
        sender.sendMessage(plugin.colorize("&7A Nether Star-backed fractional reserve banking system."));
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eKey Features:"));
        sender.sendMessage(plugin.colorize("  &7• Physical currency (F-notes) backed by Nether Stars"));
        sender.sendMessage(plugin.colorize("  &7• Fractional reserve banking with configurable ratios"));
        sender.sendMessage(plugin.colorize("  &7• Tax system with land and sales taxes"));
        sender.sendMessage(plugin.colorize("  &7• Bounty board for player contracts"));
        sender.sendMessage(plugin.colorize("  &7• Dimension access permits"));
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eExchange Rate:"));
        sender.sendMessage(plugin.colorize("  &f1728 Nether Stars = &eF$1"));
        sender.sendMessage(plugin.colorize("&6═══════════════════════════════"));
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.reload")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        try {
            plugin.reloadConfig();
            sender.sendMessage(plugin.colorize("&aNSF Economy configuration reloaded!"));
        } catch (Exception e) {
            sender.sendMessage(plugin.colorize("&cError reloading config: " + e.getMessage()));
            plugin.getLogger().severe("Config reload error: " + e.getMessage());
        }
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.stats")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        sender.sendMessage(plugin.colorize("&6══════ &lEconomy Statistics &r&6══════"));
        
        // Reserve stats
        double reserves = plugin.getBankManager().getReserveBalance();
        double circulating = plugin.getBankManager().getCirculatingSupply();
        double reserveRatio = circulating > 0 ? (reserves / circulating) * 100 : 100;
        
        sender.sendMessage(plugin.colorize("&eMoney Supply:"));
        sender.sendMessage(plugin.colorize("  &7Circulating: &f" + 
            plugin.getCurrencyManager().formatCurrency(circulating)));
        sender.sendMessage(plugin.colorize("  &7Reserve (NS): &f" + String.format("%,.0f", reserves)));
        sender.sendMessage(plugin.colorize("  &7Reserve Ratio: &f" + String.format("%.2f%%", reserveRatio)));
        
        // Emergency status
        boolean emergencyMode = plugin.getBankManager().isEmergencyMode();
        String emergencyStatus = emergencyMode ? "&c[ACTIVE]" : "&a[NORMAL]";
        sender.sendMessage(plugin.colorize("  &7Emergency Mode: " + emergencyStatus));

        // Bank locations
        List<BankLocation> banks = plugin.getBankManager().getBankLocations();
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eBank Locations: &f" + banks.size()));
        for (BankLocation bank : banks) {
            String type = bank.getType().equalsIgnoreCase("main") ? "&6[MAIN]" : "&7[BRANCH]";
            sender.sendMessage(plugin.colorize("  " + type + " &f" + bank.getName() + 
                " &7(" + bank.getWorld() + ")"));
        }

        // Active permits
        int activePermits = countActivePermits();
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eActive Permits: &f" + activePermits));

        // Open bounties
        int openBounties = plugin.getBountyManager().getOpenBounties().size();
        sender.sendMessage(plugin.colorize("&eOpen Bounties: &f" + openBounties));

        sender.sendMessage(plugin.colorize("&6═══════════════════════════════════"));
    }

    private void handleEconomy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.economy")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /nsf economy <status|freeze|unfreeze>"));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "status" -> {
                boolean frozen = plugin.getConfig().getBoolean("economy.frozen", false);
                String status = frozen ? "&cFROZEN" : "&aACTIVE";
                sender.sendMessage(plugin.colorize("&7Economy Status: " + status));
            }
            case "freeze" -> {
                plugin.getConfig().set("economy.frozen", true);
                plugin.saveConfig();
                sender.sendMessage(plugin.colorize("&c⚠ Economy has been FROZEN. All transactions disabled."));
                plugin.getServer().broadcastMessage(plugin.colorize(
                    "&c[NSF Economy] &7The economy has been temporarily frozen by an administrator."
                ));
            }
            case "unfreeze" -> {
                plugin.getConfig().set("economy.frozen", false);
                plugin.saveConfig();
                sender.sendMessage(plugin.colorize("&a✓ Economy has been UNFROZEN. Normal operations resumed."));
                plugin.getServer().broadcastMessage(plugin.colorize(
                    "&a[NSF Economy] &7The economy has been restored to normal operations."
                ));
            }
            default -> sender.sendMessage(plugin.colorize("&cUnknown action. Use: status, freeze, unfreeze"));
        }
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.debug")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            boolean debugEnabled = plugin.getConfig().getBoolean("debug", false);
            sender.sendMessage(plugin.colorize("&7Debug mode: " + (debugEnabled ? "&aON" : "&cOFF")));
            sender.sendMessage(plugin.colorize("&7Toggle with: /nsf debug <on|off>"));
            return;
        }

        boolean enable = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
        plugin.getConfig().set("debug", enable);
        plugin.saveConfig();
        
        sender.sendMessage(plugin.colorize("&7Debug mode " + (enable ? "&aenabled" : "&cdisabled")));
    }

    private void handleEmergency(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.emergency")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            boolean emergencyMode = plugin.getBankManager().isEmergencyMode();
            sender.sendMessage(plugin.colorize("&7Emergency Mode: " + 
                (emergencyMode ? "&c[ACTIVE]" : "&a[NORMAL]")));
            sender.sendMessage(plugin.colorize("&7Use: /nsf emergency <activate|deactivate|status>"));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "activate" -> {
                plugin.getBankManager().setEmergencyMode(true);
                sender.sendMessage(plugin.colorize("&c⚠ Emergency mode ACTIVATED!"));
                sender.sendMessage(plugin.colorize("&7Withdrawal limits and fees now in effect."));
                plugin.getServer().broadcastMessage(plugin.colorize(
                    "&c[NSF Economy] &7Emergency banking measures activated. Withdrawal limits in effect."
                ));
            }
            case "deactivate" -> {
                plugin.getBankManager().setEmergencyMode(false);
                sender.sendMessage(plugin.colorize("&a✓ Emergency mode DEACTIVATED."));
                sender.sendMessage(plugin.colorize("&7Normal banking operations resumed."));
                plugin.getServer().broadcastMessage(plugin.colorize(
                    "&a[NSF Economy] &7Banking operations have returned to normal."
                ));
            }
            case "status" -> {
                boolean emergencyMode = plugin.getBankManager().isEmergencyMode();
                double reserves = plugin.getBankManager().getReserveBalance();
                double circulating = plugin.getBankManager().getCirculatingSupply();
                double ratio = circulating > 0 ? (reserves / circulating) * 100 : 100;
                double criticalThreshold = plugin.getConfig().getDouble("bank.critical_reserve_ratio", 0.05) * 100;
                
                sender.sendMessage(plugin.colorize("&6══════ &lEmergency Status &r&6══════"));
                sender.sendMessage(plugin.colorize("&7Mode: " + 
                    (emergencyMode ? "&c[ACTIVE]" : "&a[NORMAL]")));
                sender.sendMessage(plugin.colorize("&7Current Reserve Ratio: &f" + 
                    String.format("%.2f%%", ratio)));
                sender.sendMessage(plugin.colorize("&7Critical Threshold: &f" + 
                    String.format("%.2f%%", criticalThreshold)));
                
                if (emergencyMode) {
                    double limit = plugin.getConfig().getDouble("bank.emergency.daily_withdrawal_limit", 10.0);
                    double fee = plugin.getConfig().getDouble("bank.emergency.withdrawal_fee", 0.05) * 100;
                    sender.sendMessage(plugin.colorize("&7Daily Limit: &e" + 
                        plugin.getCurrencyManager().formatCurrency(limit)));
                    sender.sendMessage(plugin.colorize("&7Withdrawal Fee: &e" + 
                        String.format("%.1f%%", fee)));
                }
                sender.sendMessage(plugin.colorize("&6═══════════════════════════════"));
            }
            default -> sender.sendMessage(plugin.colorize("&cUnknown action. Use: activate, deactivate, status"));
        }
    }

    private void handleAudit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.admin.audit")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /nsf audit <player|transactions|full>"));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "player" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.colorize("&cUsage: /nsf audit player <name>"));
                    return;
                }
                
                Player target = plugin.getServer().getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(plugin.colorize("&cPlayer not found: " + args[2]));
                    return;
                }

                sender.sendMessage(plugin.colorize("&6══════ &lPlayer Audit: " + target.getName() + " &r&6══════"));
                
                // Get player's F-notes
                double holdings = plugin.getCurrencyManager().countPlayerCurrency(target);
                sender.sendMessage(plugin.colorize("&7Current Holdings: &e" + 
                    plugin.getCurrencyManager().formatCurrency(holdings)));
                
                // Tax obligations
                double taxOwed = plugin.getTaxManager().getTotalOwed(target.getUniqueId());
                sender.sendMessage(plugin.colorize("&7Tax Owed: &c" + 
                    plugin.getCurrencyManager().formatCurrency(taxOwed)));
                
                // Active permits
                var permits = plugin.getPermitManager().getPlayerPermits(target.getUniqueId());
                sender.sendMessage(plugin.colorize("&7Active Permits: &f" + permits.size()));
                
                sender.sendMessage(plugin.colorize("&6═══════════════════════════════════════"));
            }
            case "transactions" -> {
                int limit = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                sender.sendMessage(plugin.colorize("&7Recent transaction audit requires database query."));
                sender.sendMessage(plugin.colorize("&7Use: /bank audit for detailed transaction logs."));
            }
            case "full" -> {
                sender.sendMessage(plugin.colorize("&7Generating full audit report..."));
                generateFullAudit(sender);
            }
            default -> sender.sendMessage(plugin.colorize("&cUnknown audit type. Use: player, transactions, full"));
        }
    }

    private void generateFullAudit(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════════ &lFULL ECONOMY AUDIT &r&6══════════"));
        
        // Money supply
        double circulating = plugin.getBankManager().getCirculatingSupply();
        double reserves = plugin.getBankManager().getReserveBalance();
        
        sender.sendMessage(plugin.colorize("&eMoney Supply"));
        sender.sendMessage(plugin.colorize("  &7Total Circulating: &f" + 
            plugin.getCurrencyManager().formatCurrency(circulating)));
        sender.sendMessage(plugin.colorize("  &7Nether Star Reserve: &f" + String.format("%,.0f", reserves)));
        sender.sendMessage(plugin.colorize("  &7Backing Ratio: &f" + 
            String.format("%.4f%%", circulating > 0 ? (reserves / circulating) * 100 : 0)));
        
        // Denomination breakdown
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eDenomination Breakdown"));
        Map<Integer, Integer> denomCounts = plugin.getBankManager().getCirculatingByDenomination();
        for (var entry : denomCounts.entrySet()) {
            sender.sendMessage(plugin.colorize("  &7F$" + entry.getKey() + " notes: &f" + entry.getValue()));
        }
        
        // Bank locations
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eBank Infrastructure"));
        List<BankLocation> banks = plugin.getBankManager().getBankLocations();
        int mainCount = (int) banks.stream().filter(b -> b.getType().equals("main")).count();
        int branchCount = banks.size() - mainCount;
        sender.sendMessage(plugin.colorize("  &7Main Vaults: &f" + mainCount));
        sender.sendMessage(plugin.colorize("  &7Branch Banks: &f" + branchCount));
        
        // System status
        sender.sendMessage(plugin.colorize(""));
        sender.sendMessage(plugin.colorize("&eSystem Status"));
        boolean frozen = plugin.getConfig().getBoolean("economy.frozen", false);
        boolean emergency = plugin.getBankManager().isEmergencyMode();
        sender.sendMessage(plugin.colorize("  &7Economy: " + (frozen ? "&cFROZEN" : "&aACTIVE")));
        sender.sendMessage(plugin.colorize("  &7Emergency Mode: " + (emergency ? "&cACTIVE" : "&aNORMAL")));
        
        sender.sendMessage(plugin.colorize("&6═════════════════════════════════════════"));
        sender.sendMessage(plugin.colorize("&7Audit generated at: " + new Date()));
    }

    private void handleVersion(CommandSender sender, String[] args) {
        sender.sendMessage(plugin.colorize("&6NSF Economy &7v" + 
            plugin.getDescription().getVersion()));
        sender.sendMessage(plugin.colorize("&7Developed for Paper/Spigot 1.21+"));
    }

    private int countActivePermits() {
        // This would ideally query the database
        // For now, return a placeholder
        return 0;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy Commands &r&6══════"));
        sender.sendMessage(plugin.colorize("&e/nsf info &7- About NSF Economy"));
        sender.sendMessage(plugin.colorize("&e/nsf version &7- Show version"));
        
        if (sender.hasPermission("nsf.admin.stats")) {
            sender.sendMessage(plugin.colorize("&c/nsf stats &7- Economy statistics"));
        }
        if (sender.hasPermission("nsf.admin.reload")) {
            sender.sendMessage(plugin.colorize("&c/nsf reload &7- Reload configuration"));
        }
        if (sender.hasPermission("nsf.admin.economy")) {
            sender.sendMessage(plugin.colorize("&c/nsf economy <status|freeze|unfreeze> &7- Control economy"));
        }
        if (sender.hasPermission("nsf.admin.emergency")) {
            sender.sendMessage(plugin.colorize("&c/nsf emergency <activate|deactivate|status>"));
        }
        if (sender.hasPermission("nsf.admin.audit")) {
            sender.sendMessage(plugin.colorize("&c/nsf audit <player|transactions|full>"));
        }
        if (sender.hasPermission("nsf.admin.debug")) {
            sender.sendMessage(plugin.colorize("&c/nsf debug [on|off] &7- Toggle debug mode"));
        }
        
        sender.sendMessage(plugin.colorize("&6══════════════════════════════════════"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("info");
            completions.add("version");
            completions.add("help");
            
            if (sender.hasPermission("nsf.admin.stats")) completions.add("stats");
            if (sender.hasPermission("nsf.admin.reload")) completions.add("reload");
            if (sender.hasPermission("nsf.admin.economy")) completions.add("economy");
            if (sender.hasPermission("nsf.admin.emergency")) completions.add("emergency");
            if (sender.hasPermission("nsf.admin.audit")) completions.add("audit");
            if (sender.hasPermission("nsf.admin.debug")) completions.add("debug");
            
            return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "economy" -> completions.addAll(Arrays.asList("status", "freeze", "unfreeze"));
                case "emergency" -> completions.addAll(Arrays.asList("activate", "deactivate", "status"));
                case "audit" -> completions.addAll(Arrays.asList("player", "transactions", "full"));
                case "debug" -> completions.addAll(Arrays.asList("on", "off"));
            }
        }
        
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("audit") && args[1].equalsIgnoreCase("player")) {
                return null; // Show player names
            }
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}
