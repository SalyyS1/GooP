package gunging.ootilities.gunging_ootilities_plugin.misc;

import gunging.ootilities.gunging_ootilities_plugin.Gunging_Ootilities_Plugin;
import gunging.ootilities.gunging_ootilities_plugin.OotilityCeption;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What items break down into at a deconstruction station, from {@code deconstruct-tables.yml}.
 * Items are matched and produced with GooP's usual item filters ({@code v MATERIAL 0},
 * {@code m TYPE ID}, with {@code *} wildcards).
 */
public final class DeconstructTables {

    private DeconstructTables() { }

    static final class Output {
        @NotNull final String item;
        final int min, max;
        final double chance;
        Output(@NotNull String item, int min, int max, double chance) { this.item = item; this.min = min; this.max = max; this.chance = chance; }
    }

    static final class Table {
        @NotNull final String[] match;
        @NotNull final List<Output> outputs;
        Table(@NotNull String[] match, @NotNull List<Output> outputs) { this.match = match; this.outputs = outputs; }
    }

    @NotNull private static final Map<String, Table> tables = new LinkedHashMap<>();

    public static void reload(@NotNull Gunging_Ootilities_Plugin plugin) {
        tables.clear();
        FileConfigPair pair = plugin.GetConfigAt(null, "deconstruct-tables.yml", true, false);
        ConfigurationSection root = pair == null ? null : pair.getStorage().getConfigurationSection("Tables");
        if (root == null) { return; }
        for (String name : root.getKeys(false)) {
            ConfigurationSection t = root.getConfigurationSection(name);
            if (t == null) { continue; }
            String[] match = t.getString("Match", "").trim().split(" ");
            if (match.length != 3) {
                plugin.getLogger().warning("deconstruct-tables.yml: table '" + name + "' needs Match like 'v IRON_SWORD 0' or 'm SWORD *'");
                continue;
            }
            List<Output> outputs = new ArrayList<>();
            for (Map<?, ?> o : t.getMapList("Outputs")) {
                Object item = o.get("Item");
                if (item == null) { continue; }
                String amount = String.valueOf(o.containsKey("Amount") ? o.get("Amount") : "1");
                int min, max;
                if (amount.contains("..")) {
                    min = OotilityCeption.ParseInt(amount.substring(0, amount.indexOf("..")));
                    max = OotilityCeption.ParseInt(amount.substring(amount.indexOf("..") + 2));
                } else { min = max = OotilityCeption.ParseInt(amount); }
                double chance = o.containsKey("Chance") ? Double.parseDouble(String.valueOf(o.get("Chance"))) : 100;
                outputs.add(new Output(String.valueOf(item), Math.max(0, min), Math.max(min, max), chance));
            }
            tables.put(name, new Table(match, outputs));
        }
    }

    /** @return If some table can deconstruct this item */
    public static boolean canDeconstruct(@Nullable ItemStack item) { return find(item, null) != null; }

    /**
     * Rolls the outputs of the first table matching this item, or of the named table.
     *
     * @return The items obtained (can be empty if all chances failed), or null if no table matches
     */
    @Nullable public static List<ItemStack> roll(@Nullable ItemStack item, @Nullable String tableName) {
        Table table = find(item, tableName);
        if (table == null) { return null; }
        List<ItemStack> result = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int times = item.getAmount();
        for (int n = 0; n < times; n++) {
            for (Output o : table.outputs) {
                if (random.nextDouble() * 100 >= o.chance) { continue; }
                int amount = o.min == o.max ? o.min : random.nextInt(o.min, o.max + 1);
                if (amount <= 0) { continue; }
                ItemStack produced = OotilityCeption.ItemFromNBTTestString(o.item, null);
                if (produced == null) { continue; }
                produced.setAmount(amount);
                result.add(produced);
            }
        }
        return result;
    }

    @Nullable private static Table find(@Nullable ItemStack item, @Nullable String tableName) {
        if (OotilityCeption.IsAirNullAllowed(item)) { return null; }
        if (tableName != null) {
            Table t = tables.get(tableName);
            return t != null && matches(item, t) ? t : null;
        }
        for (Table t : tables.values()) { if (matches(item, t)) { return t; } }
        return null;
    }

    private static boolean matches(@NotNull ItemStack item, @NotNull Table t) {
        return OotilityCeption.MatchesItemNBTtestString(item, t.match[0], t.match[1], t.match[2], (String) null, null);
    }
}
