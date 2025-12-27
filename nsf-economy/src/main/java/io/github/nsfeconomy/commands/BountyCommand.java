package io.github.nsfeconomy.commands;

import io.github.nsfeconomy.NSFEconomy;
import io.github.nsfeconomy.bounty.BountyManager;
import io.github.nsfeconomy.bounty.BountyManager.Bounty;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles /bounty commands for the bounty board system
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    private final NSFEconomy plugin;

    public BountyCommand(NSFEconomy plugin) {
        this.plugin = plugin;
        plugin.getCommand("bounty").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "list" -> handleList(sender, args);
            case "post" -> handlePost(sender, args);
            case "claim" -> handleClaim(sender, args);
            case "submit" -> handleSubmit(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "info" -> handleInfo(sender, args);
            case "approve" -> handleApprove(sender, args);
            case "reject" -> handleReject(sender, args);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.bounty.view")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        String filter = args.length > 1 ? args[1].toLowerCase() : "open";
        BountyManager bountyManager = plugin.getBountyManager();
        List<Bounty> bounties;

        switch (filter) {
            case "open" -> bounties = bountyManager.getOpenBounties();
            case "my" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.colorize("&cThis filter requires a player."));
                    return;
                }
                bounties = bountyManager.getPlayerBounties(player.getUniqueId());
            }
            case "claimed" -> bounties = bountyManager.getBountiesByStatus("claimed");
            case "all" -> {
                if (!sender.hasPermission("nsf.admin.bounty")) {
                    sender.sendMessage(plugin.getMessage("error_no_permission"));
                    return;
                }
                bounties = bountyManager.getAllBounties();
            }
            default -> {
                sender.sendMessage(plugin.colorize("&cInvalid filter. Use: open, my, claimed, all"));
                return;
            }
        }

        if (bounties.isEmpty()) {
            sender.sendMessage(plugin.colorize("&7No bounties found."));
            return;
        }

        sender.sendMessage(plugin.colorize("&6══════ &lBounty Board &r&6══════"));
        for (Bounty bounty : bounties) {
            String status = getStatusColor(bounty.getStatus()) + bounty.getStatus();
            sender.sendMessage(plugin.colorize(String.format(
                "&7[#%d] &f%s &7- &e%s &7(%s&7)",
                bounty.getId(),
                truncate(bounty.getDescription(), 30),
                plugin.getCurrencyManager().formatCurrency(bounty.getReward()),
                status
            )));
        }
        sender.sendMessage(plugin.colorize("&6══════════════════════════════"));
        sender.sendMessage(plugin.colorize("&7Use &e/bounty info <id> &7for details."));
    }

    private void handlePost(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bounty.post")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty post <reward> <description>"));
            sender.sendMessage(plugin.colorize("&7Example: /bounty post F$10 Gather 64 oak logs"));
            return;
        }

        double reward;
        try {
            String rewardStr = args[1].replace(plugin.getCurrencyManager().getCurrencySymbol(), "");
            reward = Double.parseDouble(rewardStr);
            if (reward <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessage("error_invalid_amount"));
            return;
        }

        double minReward = plugin.getConfig().getDouble("bounty.min_reward", 1.0);
        if (reward < minReward) {
            sender.sendMessage(plugin.colorize("&cMinimum bounty reward is " + 
                plugin.getCurrencyManager().formatCurrency(minReward)));
            return;
        }

        // Check if player is at bank for payment
        if (!plugin.getBankManager().isAtBank(player)) {
            sender.sendMessage(plugin.getMessage("not_at_bank"));
            return;
        }

        // Build description from remaining args
        StringBuilder description = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            description.append(args[i]).append(" ");
        }
        String desc = description.toString().trim();

        if (desc.length() > 200) {
            sender.sendMessage(plugin.colorize("&cDescription too long (max 200 characters)."));
            return;
        }

        // TODO: Verify player has enough F-notes and deduct them
        // For now, create the bounty directly
        
        BountyManager.BountyResult result = plugin.getBountyManager().createBounty(
            player.getUniqueId(),
            desc,
            reward
        );

        if (result.isSuccess()) {
            sender.sendMessage(plugin.colorize("&aBounty posted! ID: #" + result.getBountyId()));
            sender.sendMessage(plugin.colorize("&7Reward: &e" + plugin.getCurrencyManager().formatCurrency(reward)));
            
            // Announce to server
            plugin.getServer().broadcastMessage(plugin.colorize(
                "&6[Bounty] &e" + player.getName() + " &7posted a new bounty for &e" +
                plugin.getCurrencyManager().formatCurrency(reward) + "&7!"
            ));
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to post bounty: " + result.getMessage()));
        }
    }

    private void handleClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bounty.claim")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty claim <id>"));
            return;
        }

        int bountyId;
        try {
            bountyId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.colorize("&cInvalid bounty ID."));
            return;
        }

        Bounty bounty = plugin.getBountyManager().getBounty(bountyId);
        if (bounty == null) {
            sender.sendMessage(plugin.colorize("&cBounty #" + bountyId + " not found."));
            return;
        }

        if (bounty.getPosterId().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.colorize("&cYou cannot claim your own bounty."));
            return;
        }

        if (!bounty.getStatus().equals("open")) {
            sender.sendMessage(plugin.colorize("&cThis bounty is not available for claiming."));
            return;
        }

        if (plugin.getBountyManager().claimBounty(bountyId, player.getUniqueId())) {
            sender.sendMessage(plugin.colorize("&aYou claimed bounty #" + bountyId + "!"));
            sender.sendMessage(plugin.colorize("&7Task: &f" + bounty.getDescription()));
            sender.sendMessage(plugin.colorize("&7Use &e/bounty submit " + bountyId + " &7when complete."));
            
            // Notify poster if online
            Player poster = plugin.getServer().getPlayer(bounty.getPosterId());
            if (poster != null) {
                poster.sendMessage(plugin.colorize("&6[Bounty] &e" + player.getName() + 
                    " &7claimed your bounty #" + bountyId));
            }
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to claim bounty."));
        }
    }

    private void handleSubmit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (!player.hasPermission("nsf.bounty.claim")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty submit <id>"));
            return;
        }

        int bountyId;
        try {
            bountyId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.colorize("&cInvalid bounty ID."));
            return;
        }

        Bounty bounty = plugin.getBountyManager().getBounty(bountyId);
        if (bounty == null) {
            sender.sendMessage(plugin.colorize("&cBounty #" + bountyId + " not found."));
            return;
        }

        if (bounty.getClaimerId() == null || !bounty.getClaimerId().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.colorize("&cYou have not claimed this bounty."));
            return;
        }

        if (!bounty.getStatus().equals("claimed")) {
            sender.sendMessage(plugin.colorize("&cThis bounty cannot be submitted."));
            return;
        }

        if (plugin.getBountyManager().submitBounty(bountyId)) {
            sender.sendMessage(plugin.colorize("&aBounty #" + bountyId + " submitted for approval!"));
            sender.sendMessage(plugin.colorize("&7Waiting for the poster to approve."));
            
            // Notify poster
            Player poster = plugin.getServer().getPlayer(bounty.getPosterId());
            if (poster != null) {
                poster.sendMessage(plugin.colorize("&6[Bounty] &e" + player.getName() + 
                    " &7submitted bounty #" + bountyId + " for approval!"));
                poster.sendMessage(plugin.colorize("&7Use &e/bounty approve " + bountyId + 
                    " &7or &e/bounty reject " + bountyId));
            }
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to submit bounty."));
        }
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty cancel <id>"));
            return;
        }

        int bountyId;
        try {
            bountyId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.colorize("&cInvalid bounty ID."));
            return;
        }

        Bounty bounty = plugin.getBountyManager().getBounty(bountyId);
        if (bounty == null) {
            sender.sendMessage(plugin.colorize("&cBounty #" + bountyId + " not found."));
            return;
        }

        // Only poster or admin can cancel
        boolean isAdmin = sender.hasPermission("nsf.admin.bounty");
        if (!bounty.getPosterId().equals(player.getUniqueId()) && !isAdmin) {
            sender.sendMessage(plugin.colorize("&cYou can only cancel your own bounties."));
            return;
        }

        if (!bounty.getStatus().equals("open") && !isAdmin) {
            sender.sendMessage(plugin.colorize("&cCannot cancel a bounty that has been claimed."));
            return;
        }

        if (plugin.getBountyManager().cancelBounty(bountyId)) {
            sender.sendMessage(plugin.colorize("&aBounty #" + bountyId + " cancelled."));
            // TODO: Refund the reward to the poster
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to cancel bounty."));
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nsf.bounty.view")) {
            sender.sendMessage(plugin.getMessage("error_no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty info <id>"));
            return;
        }

        int bountyId;
        try {
            bountyId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.colorize("&cInvalid bounty ID."));
            return;
        }

        Bounty bounty = plugin.getBountyManager().getBounty(bountyId);
        if (bounty == null) {
            sender.sendMessage(plugin.colorize("&cBounty #" + bountyId + " not found."));
            return;
        }

        sender.sendMessage(plugin.colorize("&6══════ &lBounty #" + bountyId + " &r&6══════"));
        sender.sendMessage(plugin.colorize("&7Description: &f" + bounty.getDescription()));
        sender.sendMessage(plugin.colorize("&7Reward: &e" + 
            plugin.getCurrencyManager().formatCurrency(bounty.getReward())));
        sender.sendMessage(plugin.colorize("&7Status: " + getStatusColor(bounty.getStatus()) + 
            bounty.getStatus()));
        sender.sendMessage(plugin.colorize("&7Posted by: &f" + 
            plugin.getServer().getOfflinePlayer(bounty.getPosterId()).getName()));
        
        if (bounty.getClaimerId() != null) {
            sender.sendMessage(plugin.colorize("&7Claimed by: &f" + 
                plugin.getServer().getOfflinePlayer(bounty.getClaimerId()).getName()));
        }
        
        sender.sendMessage(plugin.colorize("&7Created: &f" + 
            bounty.getCreatedAt().toString().substring(0, 16)));
        sender.sendMessage(plugin.colorize("&6══════════════════════════════"));
    }

    private void handleApprove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty approve <id>"));
            return;
        }

        int bountyId;
        try {
            bountyId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.colorize("&cInvalid bounty ID."));
            return;
        }

        Bounty bounty = plugin.getBountyManager().getBounty(bountyId);
        if (bounty == null) {
            sender.sendMessage(plugin.colorize("&cBounty #" + bountyId + " not found."));
            return;
        }

        boolean isAdmin = sender.hasPermission("nsf.admin.bounty");
        if (!bounty.getPosterId().equals(player.getUniqueId()) && !isAdmin) {
            sender.sendMessage(plugin.colorize("&cOnly the bounty poster can approve."));
            return;
        }

        if (!bounty.getStatus().equals("submitted")) {
            sender.sendMessage(plugin.colorize("&cThis bounty has not been submitted for approval."));
            return;
        }

        if (plugin.getBountyManager().approveBounty(bountyId)) {
            sender.sendMessage(plugin.colorize("&aBounty #" + bountyId + " approved! Reward sent."));
            
            // Notify claimer
            if (bounty.getClaimerId() != null) {
                Player claimer = plugin.getServer().getPlayer(bounty.getClaimerId());
                if (claimer != null) {
                    claimer.sendMessage(plugin.colorize("&6[Bounty] &aYour bounty #" + bountyId + 
                        " was approved! You earned &e" + 
                        plugin.getCurrencyManager().formatCurrency(bounty.getReward())));
                }
            }
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to approve bounty."));
        }
    }

    private void handleReject(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.colorize("&cThis command can only be used by players."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.colorize("&cUsage: /bounty reject <id>"));
            return;
        }

        int bountyId;
        try {
            bountyId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.colorize("&cInvalid bounty ID."));
            return;
        }

        Bounty bounty = plugin.getBountyManager().getBounty(bountyId);
        if (bounty == null) {
            sender.sendMessage(plugin.colorize("&cBounty #" + bountyId + " not found."));
            return;
        }

        boolean isAdmin = sender.hasPermission("nsf.admin.bounty");
        if (!bounty.getPosterId().equals(player.getUniqueId()) && !isAdmin) {
            sender.sendMessage(plugin.colorize("&cOnly the bounty poster can reject."));
            return;
        }

        if (!bounty.getStatus().equals("submitted")) {
            sender.sendMessage(plugin.colorize("&cThis bounty has not been submitted."));
            return;
        }

        // Rejection returns bounty to claimed status for the claimer to retry
        if (plugin.getBountyManager().rejectBounty(bountyId)) {
            sender.sendMessage(plugin.colorize("&eBounty #" + bountyId + " rejected. " +
                "Claimer can resubmit or you can re-open it."));
            
            if (bounty.getClaimerId() != null) {
                Player claimer = plugin.getServer().getPlayer(bounty.getClaimerId());
                if (claimer != null) {
                    claimer.sendMessage(plugin.colorize("&6[Bounty] &cYour submission for bounty #" + 
                        bountyId + " was rejected. Please try again."));
                }
            }
        } else {
            sender.sendMessage(plugin.colorize("&cFailed to reject bounty."));
        }
    }

    private String getStatusColor(String status) {
        return switch (status.toLowerCase()) {
            case "open" -> "&a";
            case "claimed" -> "&e";
            case "submitted" -> "&b";
            case "completed" -> "&2";
            case "cancelled", "expired" -> "&c";
            default -> "&7";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.colorize("&6══════ &lNSF Economy - Bounty Commands &r&6══════"));
        sender.sendMessage(plugin.colorize("&e/bounty list [open|my|claimed] &7- List bounties"));
        sender.sendMessage(plugin.colorize("&e/bounty post <reward> <description> &7- Post a bounty"));
        sender.sendMessage(plugin.colorize("&e/bounty claim <id> &7- Claim a bounty"));
        sender.sendMessage(plugin.colorize("&e/bounty submit <id> &7- Submit for approval"));
        sender.sendMessage(plugin.colorize("&e/bounty cancel <id> &7- Cancel your bounty"));
        sender.sendMessage(plugin.colorize("&e/bounty info <id> &7- View bounty details"));
        sender.sendMessage(plugin.colorize("&e/bounty approve <id> &7- Approve submission"));
        sender.sendMessage(plugin.colorize("&e/bounty reject <id> &7- Reject submission"));
        sender.sendMessage(plugin.colorize("&6════════════════════════════════════════"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList("list", "post", "claim", "submit", "cancel", "info", "approve", "reject"));
            return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("list")) {
                completions.addAll(Arrays.asList("open", "my", "claimed"));
                if (sender.hasPermission("nsf.admin.bounty")) {
                    completions.add("all");
                }
            }
            // For ID-based commands, could show available bounty IDs
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}
