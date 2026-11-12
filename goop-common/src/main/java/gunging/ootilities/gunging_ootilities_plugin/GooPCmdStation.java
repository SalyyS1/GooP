package gunging.ootilities.gunging_ootilities_plugin;

import gunging.ootilities.gunging_ootilities_plugin.compatibilities.GooPMMOItems;
import gunging.ootilities.gunging_ootilities_plugin.compatibilities.GooPVault;
import gunging.ootilities.gunging_ootilities_plugin.misc.DeconstructTables;
import gunging.ootilities.gunging_ootilities_plugin.misc.PlusMinusPercent;
import gunging.ootilities.gunging_ootilities_plugin.misc.goop.TargetedItem;
import gunging.ootilities.gunging_ootilities_plugin.misc.goop.TargetedItems;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /goop station <action> <player> <slot> [cost] [success%] [break%] [confirm]}
 * <p>
 * One command for crafting-station buttons: reforge, upgrade, repair or deconstruct the item in a
 * slot (usually a Station Container slot such as {@code |ForgeBench|4}), charging a Vault cost
 * and rolling for success. On failure the cost is still paid and the item may break. With
 * {@code confirm}, the first use only asks the player to click again within five seconds.
 * Put it in a container slot's click commands to turn a Station Container into a forge.
 */
final class GooPCmdStation {

    private GooPCmdStation() { }

    private static final String[] ACTIONS = {"reforge", "upgrade", "repair", "deconstruct"};

    /** Last unconfirmed request per player: key and time */
    private static final Map<UUID, Map.Entry<String, Long>> pendingConfirm = new HashMap<>();

    static Boolean run(@NotNull GooPCommandContext c) {
        String[] args = c.args;
        List<String> logReturn = c.logReturn;
        String usage = "/goop station <action> <player> <slot> [cost] [success%] [break%] [confirm]";
        String subcategory = "Station";

        if (args.length < 4 || args.length > 8) {
            logReturn.add("§e______________________________________________");
            logReturn.add("§3Station,§7 runs a crafting-station action on an item, with a cost and chances.");
            logReturn.add("§3Usage: §e" + usage);
            logReturn.add("§3 - §e<action> §7reforge, upgrade, repair or deconstruct");
            logReturn.add("§3 - §e[cost] §7Vault money charged per use (0 = free)");
            logReturn.add("§3 - §e[success%] §7Chance the action works (default 100)");
            logReturn.add("§3 - §e[break%] §7Chance a failed attempt destroys the item (default 0)");
            logReturn.add("§3 - §e[confirm] §7'confirm' to require a second use within 5 seconds");
            return null;
        }

        String action = args[1].toLowerCase();
        if (!java.util.Arrays.asList(ACTIONS).contains(action)) {
            logReturn.add(OotilityCeption.LogFormat(subcategory, "Unknown action §e" + args[1] + "§7, expected reforge, upgrade, repair or deconstruct."));
            return null;
        }
        if ((action.equals("reforge") || action.equals("upgrade")) && !Gunging_Ootilities_Plugin.foundMMOItems) {
            logReturn.add(OotilityCeption.LogFormat(subcategory, "§e" + action + "§7 needs MMOItems."));
            return null;
        }

        double cost = args.length >= 5 ? number(args[4]) : 0;
        double success = args.length >= 6 ? number(args[5]) : 100;
        double breaking = args.length >= 7 ? number(args[6]) : 0;
        boolean confirm = args.length >= 8 && args[7].equalsIgnoreCase("confirm");
        if (cost > 0 && !Gunging_Ootilities_Plugin.foundVault) {
            logReturn.add(OotilityCeption.LogFormat(subcategory, "A cost needs Vault and an economy plugin."));
            return null;
        }

        ArrayList<Player> targets = OotilityCeption.GetPlayers(c.senderLocation, args[2], null);
        Entity asDropped = OotilityCeption.getEntityByUniqueId(args[2]);
        if (targets.isEmpty() && !(asDropped instanceof Item)) {
            logReturn.add(OotilityCeption.LogFormat(subcategory, "Target must be an online player!"));
            return null;
        }

        // Double confirmation: remember the request, run it on the second use
        if (confirm) {
            String key = String.join(" ", args);
            ArrayList<Player> unconfirmed = new ArrayList<>();
            for (Player p : targets) {
                Map.Entry<String, Long> last = pendingConfirm.get(p.getUniqueId());
                long now = System.currentTimeMillis();
                if (last != null && last.getKey().equals(key) && now - last.getValue() <= 5000) {
                    pendingConfirm.remove(p.getUniqueId());
                } else {
                    pendingConfirm.put(p.getUniqueId(), new java.util.AbstractMap.SimpleEntry<>(key, now));
                    p.sendMessage("§eClick again to confirm" + (cost > 0 ? " §7(costs §6" + OotilityCeption.RemoveDecimalZeros(String.valueOf(cost)) + "§7)" : "") + "§e.");
                    unconfirmed.add(p);
                }
            }
            targets.removeAll(unconfirmed);
            if (targets.isEmpty()) { return null; }
        }

        TargetedItems executor = new TargetedItems(false, true, c.chained, c.commandChain, c.sender, c.failMessage,
                iSource -> process(iSource, action, cost, success, breaking),
                iSource -> iSource.getResult() != null,
                null);
        if (asDropped instanceof Item) { executor.registerDroppedItem((Item) asDropped); }
        executor.registerPlayers(targets, args[3], executor.getIncludedStrBuilder());
        executor.process();
        if (executor.getIncludedStrBuilder().length() > 0) { logReturn.add(OotilityCeption.LogFormat(subcategory, executor.getIncludedStrBuilder().toString())); }
        return null;
    }

    /** A number argument; unreadable values count as 0 so a typo never makes a station free of risk */
    private static double number(@NotNull String raw) {
        try { return Double.parseDouble(raw.replace("%", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** @return The changed item when the action succeeded, null when nothing should change */
    @Nullable static ItemStack process(@NotNull TargetedItem iSource, @NotNull String action, double cost, double success, double breaking) {
        ItemStack item = iSource.getValidOriginal();
        Player player = iSource.getPlayer();
        String name = OotilityCeption.GetItemName(item);

        // Can this item take the action at all? Check before charging.
        if (action.equals("deconstruct") && !DeconstructTables.canDeconstruct(item)) {
            iSource.addToLogAddition("§c" + name + "§7 cannot be deconstructed. ");
            return null;
        }
        if ((action.equals("reforge") || action.equals("upgrade")) && !GooPMMOItems.IsMMOItem(item)) {
            iSource.addToLogAddition("§c" + name + "§7 is not an MMOItem. ");
            return null;
        }

        if (cost > 0) {
            if (player == null) { iSource.addToLogAddition("§cOnly players can pay for stations. "); return null; }
            if (GooPVault.GetPlayerBalance(player) < cost) {
                player.sendMessage("§cYou need §6" + OotilityCeption.RemoveDecimalZeros(String.valueOf(cost)) + "§c for this.");
                iSource.addToLogAddition("§7" + player.getName() + " cannot afford " + cost + ". ");
                return null;
            }
            GooPVault.Withdraw(player, cost);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() * 100 >= success) {
            if (random.nextDouble() * 100 < breaking) {
                iSource.Overwrite(null);
                if (player != null) { player.sendMessage("§c" + name + "§c broke!"); }
                iSource.addToLogAddition("§c" + name + "§7 broke. ");
            } else {
                if (player != null) { player.sendMessage("§cThe attempt on " + name + "§c failed."); }
                iSource.addToLogAddition("§7Attempt on " + name + "§7 failed. ");
            }
            return null;
        }

        ItemStack result;
        switch (action) {
            case "reforge":
                // Reroll stats, keep everything the player put into the item
                result = GooPMMOItems.ReforgeMMOItem(item, iSource.getLogAddition(), true, true, true, true, true, true, true, true, true, true, true);
                break;
            case "upgrade":
                result = GooPMMOItems.UpgradeMMOItem(item, PlusMinusPercent.GetPMP("+1", null), false, iSource.getRef_int_a(), iSource.getLogAddition());
                break;
            case "repair":
                result = OotilityCeption.SetDurability(item, player, PlusMinusPercent.GetPMP("0", null), null, iSource.getLogAddition());
                break;
            case "deconstruct": {
                List<ItemStack> outputs = DeconstructTables.roll(item, null);
                if (outputs == null) { return null; }
                giveOrDrop(iSource, outputs);
                result = new ItemStack(Material.AIR);
                break;
            }
            default: return null;
        }
        if (result != null && iSource.getLogAddition().getValue() == null) { iSource.addToLogAddition("§a" + action + "§7 " + name + ". "); }
        return result;
    }

    private static void giveOrDrop(@NotNull TargetedItem iSource, @NotNull List<ItemStack> items) {
        Player player = iSource.getPlayer();
        for (ItemStack i : items) {
            if (player != null) {
                for (ItemStack overflow : player.getInventory().addItem(i).values()) { player.getWorld().dropItemNaturally(player.getLocation(), overflow); }
            } else {
                iSource.getEntity().getWorld().dropItemNaturally(iSource.getEntity().getLocation(), i);
            }
        }
    }
}
