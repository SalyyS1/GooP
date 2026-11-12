package gunging.ootilities.gunging_ootilities_plugin.events;

import gunging.ootilities.gunging_ootilities_plugin.Gunging_Ootilities_Plugin;
import gunging.ootilities.gunging_ootilities_plugin.compatibilities.versions.GooP_MinecraftVersions;
import gunging.ootilities.gunging_ootilities_plugin.misc.FileConfigPair;
import gunging.ootilities.gunging_ootilities_plugin.misc.GooPUnlockables;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Respawn at the closest respawn point the player has unlocked, from {@code respawn-points.yml}.
 * A point can require a GooP unlockable ({@code /goop unlockables unlock <player> <name>}),
 * so discovering a town can unlock its respawn. Beds and anchors still win unless
 * {@code OverrideBeds} is on.
 */
public final class RespawnPoints implements Listener {

    static final class Point {
        @NotNull final String name;
        @NotNull final String world;
        final double x, y, z;
        final float yaw, pitch;
        @Nullable final String unlockable;
        Point(@NotNull String name, @NotNull String world, double x, double y, double z, float yaw, float pitch, @Nullable String unlockable) {
            this.name = name; this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.unlockable = unlockable;
        }
    }

    private static boolean enabled;
    private static boolean overrideBeds;
    @NotNull private static final List<Point> points = new ArrayList<>();

    public static void reload(@NotNull Gunging_Ootilities_Plugin plugin) {
        points.clear();
        FileConfigPair pair = plugin.GetConfigAt(null, "respawn-points.yml", true, false);
        if (pair == null) { enabled = false; return; }
        enabled = pair.getStorage().getBoolean("Enabled", false);
        overrideBeds = pair.getStorage().getBoolean("OverrideBeds", false);
        ConfigurationSection root = pair.getStorage().getConfigurationSection("Points");
        if (root == null) { return; }
        for (String name : root.getKeys(false)) {
            ConfigurationSection p = root.getConfigurationSection(name);
            if (p == null || p.getString("World") == null) { continue; }
            String unlock = p.getString("Unlockable");
            points.add(new Point(name, p.getString("World"), p.getDouble("X"), p.getDouble("Y"), p.getDouble("Z"),
                    (float) p.getDouble("Yaw"), (float) p.getDouble("Pitch"), unlock == null || unlock.isEmpty() ? null : unlock));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!enabled || points.isEmpty()) { return; }
        if (!overrideBeds && (event.isBedSpawn() || event.isAnchorSpawn())) { return; }
        Location target = closestUnlocked(event.getPlayer(), event.getPlayer().getLocation());
        if (target != null) { event.setRespawnLocation(target); }
    }

    /** @return The closest point in the player's world they may use, or null */
    @Nullable static Location closestUnlocked(@NotNull Player player, @NotNull Location from) {
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        for (Point p : points) {
            World w = GooP_MinecraftVersions.GetWorld(p.world);
            if (w == null || !w.equals(from.getWorld())) { continue; }
            if (p.unlockable != null && !GooPUnlockables.HasUnlocked(player.getUniqueId(), p.unlockable)) { continue; }
            Location l = new Location(w, p.x, p.y, p.z, p.yaw, p.pitch);
            double d = l.distanceSquared(from);
            if (d < bestDist) { bestDist = d; best = l; }
        }
        return best;
    }
}
