package gunging.ootilities.gunging_ootilities_plugin.events;

import gunging.ootilities.gunging_ootilities_plugin.Gunging_Ootilities_Plugin;
import gunging.ootilities.gunging_ootilities_plugin.misc.FileConfigPair;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Private drops: items a mob drops belong for a while to the player who dealt it the most damage.
 * Only the owner (and, optionally, their MMOCore party) can pick them up until the ownership
 * expires or the owner leaves. Configured in {@code private-drops.yml}.
 */
public final class PrivateDrops implements Listener {

    private static boolean enabled;
    private static int ownershipSeconds = 60;
    private static boolean releaseOnQuit = true;
    private static boolean shareWithParty = false;

    @Nullable private static NamespacedKey ownerKey;

    /** Damage dealt to each living mob, by player */
    private final Map<UUID, Map<UUID, Double>> damage = new HashMap<>();
    /** Owned item entities and their owners, to release them on quit */
    private final Map<UUID, UUID> ownedItems = new HashMap<>();

    public static void reload(@NotNull Gunging_Ootilities_Plugin plugin) {
        ownerKey = new NamespacedKey(plugin, "private_drop_owner");
        FileConfigPair pair = plugin.GetConfigAt(null, "private-drops.yml", true, false);
        if (pair == null) { enabled = false; return; }
        YamlConfiguration c = pair.getStorage();
        enabled = c.getBoolean("Enabled", false);
        ownershipSeconds = Math.max(1, c.getInt("OwnershipSeconds", 60));
        releaseOnQuit = c.getBoolean("ReleaseOnQuit", true);
        shareWithParty = c.getBoolean("ShareWithMMOCoreParty", false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!enabled || !(event.getEntity() instanceof LivingEntity) || event.getEntity() instanceof Player) { return; }
        Player attacker = playerBehind(event.getDamager());
        if (attacker == null) { return; }
        damage.computeIfAbsent(event.getEntity().getUniqueId(), k -> new HashMap<>())
              .merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    /** Runs after other plugins (such as the MMOItems converter) changed the drops */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Map<UUID, Double> dealt = damage.remove(event.getEntity().getUniqueId());
        if (!enabled || event.getEntity() instanceof Player || dealt == null || event.getDrops().isEmpty()) { return; }

        UUID owner = null;
        double best = 0;
        for (Map.Entry<UUID, Double> e : dealt.entrySet()) { if (e.getValue() > best) { best = e.getValue(); owner = e.getKey(); } }
        if (owner == null || Bukkit.getPlayer(owner) == null) { return; }

        // Drop the items ourselves so each entity can be tagged with its owner
        World world = event.getEntity().getWorld();
        ArrayList<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        for (ItemStack stack : drops) {
            if (stack == null || stack.getType().isAir()) { continue; }
            Item item = world.dropItemNaturally(event.getEntity().getLocation(), stack);
            claim(item, owner);
        }
    }

    void claim(@NotNull Item item, @NotNull UUID owner) {
        item.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        ownedItems.put(item.getUniqueId(), owner);
        UUID itemId = item.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Gunging_Ootilities_Plugin.theMain, () -> release(itemId), ownershipSeconds * 20L);
    }

    void release(@NotNull UUID itemId) {
        ownedItems.remove(itemId);
        Entity e = Bukkit.getEntity(itemId);
        if (e instanceof Item) { e.getPersistentDataContainer().remove(ownerKey); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        UUID owner = ownerOf(event.getItem());
        if (owner == null) { return; }
        if (event.getEntity() instanceof Player && mayPickUp((Player) event.getEntity(), owner)) {
            ownedItems.remove(event.getItem().getUniqueId());
            return;
        }
        event.setCancelled(true);
    }

    /** Hoppers cannot steal owned drops either */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (ownerOf(event.getItem()) != null) { event.setCancelled(true); }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!releaseOnQuit) { return; }
        UUID quitter = event.getPlayer().getUniqueId();
        for (Iterator<Map.Entry<UUID, UUID>> it = ownedItems.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, UUID> e = it.next();
            if (!e.getValue().equals(quitter)) { continue; }
            it.remove();
            Entity item = Bukkit.getEntity(e.getKey());
            if (item instanceof Item) { item.getPersistentDataContainer().remove(ownerKey); }
        }
    }

    @Nullable static UUID ownerOf(@NotNull Item item) {
        if (ownerKey == null) { return null; }
        String raw = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) { return null; }
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    static boolean mayPickUp(@NotNull Player picker, @NotNull UUID owner) {
        if (picker.getUniqueId().equals(owner)) { return true; }
        if (!shareWithParty || !Gunging_Ootilities_Plugin.foundMMOCore) { return false; }
        Player ownerPlayer = Bukkit.getPlayer(owner);
        return ownerPlayer != null && PartyCheck.sameParty(ownerPlayer, picker);
    }

    @Nullable static Player playerBehind(@NotNull Entity damager) {
        if (damager instanceof Player) { return (Player) damager; }
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) { return (Player) ((Projectile) damager).getShooter(); }
        return null;
    }

    /** Kept in its own class so MMOCore classes only load when MMOCore is installed */
    static final class PartyCheck {
        static boolean sameParty(@NotNull Player owner, @NotNull Player other) {
            try {
                net.Indyuce.mmocore.api.player.PlayerData data = net.Indyuce.mmocore.api.player.PlayerData.get(owner);
                return data != null && data.getParty() != null && data.getParty().hasMember(other);
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        }
    }
}
