package gunging.ootilities.gunging_ootilities_plugin.misc;

import gunging.ootilities.goop.core.CronExpression;
import gunging.ootilities.gunging_ootilities_plugin.Gunging_Ootilities_Plugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs console commands (including {@code /goop} chains) at real-world times, configured in
 * {@code scheduler.yml} with five-field cron expressions in the server's local time.
 */
public final class GooPScheduler {

    private GooPScheduler() { }

    static final class Schedule {
        @NotNull final String name;
        @NotNull final CronExpression cron;
        @NotNull final List<String> commands;
        Schedule(@NotNull String name, @NotNull CronExpression cron, @NotNull List<String> commands) { this.name = name; this.cron = cron; this.commands = commands; }
    }

    @NotNull private static final List<Schedule> schedules = new ArrayList<>();
    @Nullable private static BukkitTask ticker;
    @Nullable private static LocalDateTime lastMinute;

    /** Reads scheduler.yml and (re)starts the once-a-second check */
    public static void reload(@NotNull Gunging_Ootilities_Plugin plugin) {
        schedules.clear();
        FileConfigPair pair = plugin.GetConfigAt(null, "scheduler.yml", true, false);
        ConfigurationSection root = pair == null ? null : pair.getStorage().getConfigurationSection("Schedules");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(name);
                if (s == null || !s.getBoolean("Enabled", true)) { continue; }
                try {
                    schedules.add(new Schedule(name, CronExpression.parse(s.getString("Cron", "")), s.getStringList("Commands")));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("scheduler.yml: schedule '" + name + "' has an invalid Cron: " + e.getMessage());
                }
            }
        }

        if (ticker != null) { ticker.cancel(); ticker = null; }
        if (schedules.isEmpty()) { return; }
        lastMinute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, GooPScheduler::tick, 20L, 20L);
    }

    /** Fires each schedule once for every minute that passed, so a lag spike does not skip one */
    static void tick() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        if (lastMinute == null || !now.isAfter(lastMinute)) { lastMinute = now; return; }
        for (LocalDateTime t = lastMinute.plusMinutes(1); !t.isAfter(now); t = t.plusMinutes(1)) {
            for (Schedule s : schedules) {
                if (!s.cron.matches(t)) { continue; }
                for (String command : s.commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
                }
            }
        }
        lastMinute = now;
    }
}
