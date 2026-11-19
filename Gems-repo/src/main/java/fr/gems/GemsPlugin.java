package fr.gems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GemsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** Effets positifs donnés par le Gem Block (sans saturation, sans health boost pour respecter les 20 cœurs). */
    private static final PotionEffectType[] BLOCK_EFFECTS = {
            PotionEffectType.SPEED, PotionEffectType.HASTE, PotionEffectType.STRENGTH,
            PotionEffectType.JUMP_BOOST, PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE,
            PotionEffectType.FIRE_RESISTANCE, PotionEffectType.WATER_BREATHING, PotionEffectType.INVISIBILITY,
            PotionEffectType.NIGHT_VISION, PotionEffectType.ABSORPTION, PotionEffectType.LUCK,
            PotionEffectType.SLOW_FALLING, PotionEffectType.CONDUIT_POWER, PotionEffectType.DOLPHINS_GRACE,
            PotionEffectType.HERO_OF_THE_VILLAGE
    };

    private record Tracking(UUID target, long until) {}

    private record Freecam(GameMode gameMode, Location origin, ArmorStand stand, long until) {}

    private NamespacedKey gemKey;
    private NamespacedKey maceKey;
    private NamespacedKey freecamKey;
    private NamespacedKey healthKey;

    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Long> intangible = new HashMap<>();
    private final Map<UUID, Long> strength = new HashMap<>();
    private final Map<UUID, Long> autocrit = new HashMap<>();
    private final Map<UUID, Long> maceUntil = new HashMap<>();
    private final Map<UUID, Tracking> tracking = new HashMap<>();
    private final Map<UUID, Freecam> freecams = new HashMap<>();
    private final Map<UUID, Set<PotionEffectType>> appliedEffects = new HashMap<>();
    private int tickCounter = 0;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    public void onEnable() {
        saveDefaultConfig();
        gemKey = new NamespacedKey(this, "gem");
        maceKey = new NamespacedKey(this, "mace_original");
        freecamKey = new NamespacedKey(this, "freecam");
        healthKey = new NamespacedKey(this, "gems_bonus_health");

        Bukkit.getPluginManager().registerEvents(this, this);
        var command = getCommand("gem");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 5L, 5L);
        getLogger().info("Gems activé.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            cleanup(p);
        }
    }

    private void cleanup(Player p) {
        UUID id = p.getUniqueId();
        endFreecam(p);
        revertMace(p);
        intangible.remove(id);
        strength.remove(id);
        autocrit.remove(id);
        tracking.remove(id);
        p.setCollidable(true);

        Set<PotionEffectType> applied = appliedEffects.remove(id);
        if (applied != null) {
            for (PotionEffectType t : applied) {
                PotionEffect cur = p.getPotionEffect(t);
                if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION) {
                    p.removePotionEffect(t);
                }
            }
        }
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            for (AttributeModifier m : new ArrayList<>(attr.getModifiers())) {
                if (m.getKey().equals(healthKey)) attr.removeModifier(m);
            }
        }
    }

    // ------------------------------------------------------------------ items

    public ItemStack createGem(Gem g) {
        ItemStack item = new ItemStack(g.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(g.displayName, g.color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        for (String line : g.lore) {
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(gemKey, PersistentDataType.STRING, g.name());
        item.setItemMeta(meta);
        return item;
    }

    private Gem gemOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(gemKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return Gem.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Gems présentes n'importe où dans l'inventaire (passifs). */
    private EnumSet<Gem> gemsIn(Player p) {
        EnumSet<Gem> set = EnumSet.noneOf(Gem.class);
        for (ItemStack it : p.getInventory().getContents()) {
            Gem g = gemOf(it);
            if (g != null) set.add(g);
        }
        return set;
    }

    private Gem heldGem(Player p) {
        return gemOf(p.getInventory().getItemInMainHand());
    }

    // ------------------------------------------------------------------ utilitaires

    private int cfg(String path) {
        return getConfig().getInt(path);
    }

    private double cfgD(String path) {
        return getConfig().getDouble(path);
    }

    private void info(Player p, String text, TextColor color) {
        p.sendActionBar(Component.text(text, color));
    }

    private long remainingMs(Player p, String key) {
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0;
        Long until = m.get(key);
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    private boolean ready(Player p, String key) {
        long r = remainingMs(p, key);
        if (r > 0) {
            info(p, "Cooldown : " + ((r + 999) / 1000) + "s", NamedTextColor.RED);
            return false;
        }
        return true;
    }

    private void startCooldown(Player p, String key, int seconds) {
        if (seconds <= 0) return;
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .put(key, System.currentTimeMillis() + seconds * 1000L);
    }

    private boolean isActive(Map<UUID, Long> map, Player p) {
        Long until = map.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private boolean expired(Map<UUID, Long> map, UUID id, long now) {
        Long until = map.get(id);
        if (until != null && now >= until) {
            map.remove(id);
            return true;
        }
        return false;
    }

    private void sound(Player p, Sound s) {
        p.getWorld().playSound(p.getLocation(), s, 1f, 1f);
    }

    // ------------------------------------------------------------------ boucle principale (toutes les 5 ticks)

    private void tick() {
        tickCounter++;
        long now = System.currentTimeMillis();
        boolean passives = tickCounter % 4 == 0; // 1x par seconde

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();

            if (passives) applyPassives(p);

            if (expired(intangible, id, now)) {
                p.setCollidable(true);
                info(p, "Intangibilité terminée", NamedTextColor.GRAY);
            } else if (intangible.containsKey(id)) {
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0.01);
            }

            if (expired(strength, id, now)) info(p, "Force x1.25 terminée", NamedTextColor.GRAY);
            if (expired(autocrit, id, now)) info(p, "Auto-crit terminé", NamedTextColor.GRAY);

            if (expired(maceUntil, id, now)) {
                revertMace(p);
                info(p, "Ta mace redevient une épée", NamedTextColor.GRAY);
            }

            Tracking t = tracking.get(id);
            if (t != null) {
                Player target = Bukkit.getPlayer(t.target());
                if (now >= t.until() || target == null || !target.isOnline()) {
                    tracking.remove(id);
                    info(p, "Tracking terminé", NamedTextColor.GRAY);
                } else if (!target.getWorld().equals(p.getWorld())) {
                    info(p, "Tracking : " + target.getName() + " est dans une autre dimension", NamedTextColor.RED);
                } else {
                    double dist = target.getLocation().distance(p.getLocation());
                    p.setCompassTarget(target.getLocation());
                    info(p, "Tracking : " + target.getName() + " - " + (int) dist + " m "
                            + arrow(p, target.getLocation()), NamedTextColor.RED);
                }
            }

            Freecam f = freecams.get(id);
            if (f != null) {
                if (now >= f.until()) {
                    endFreecam(p);
                    info(p, "Freecam terminée", NamedTextColor.GRAY);
                } else {
                    double r = cfg("purple.freecam-radius");
                    if (!p.getWorld().equals(f.origin().getWorld())
                            || p.getLocation().distanceSquared(f.origin()) > r * r) {
                        p.teleport(f.origin());
                        info(p, "Tu es trop loin de ton corps", NamedTextColor.RED);
                    } else {
                        long left = (f.until() - now + 999) / 1000;
                        info(p, "Freecam : " + left + "s", NamedTextColor.DARK_PURPLE);
                    }
                }
            }
        }
    }

    private String arrow(Player p, Location target) {
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = targetYaw - p.getLocation().getYaw();
        diff = ((diff + 180) % 360 + 360) % 360 - 180;
        int idx = (int) Math.round(diff / 45.0);
        idx = ((idx % 8) + 8) % 8;
        return ARROWS[idx];
    }

    // ------------------------------------------------------------------ passifs

    private void applyPassives(Player p) {
        EnumSet<Gem> gems = gemsIn(p);
        UUID id = p.getUniqueId();

        Map<PotionEffectType, Integer> want = new HashMap<>();
        double bonusHp = 0;

        if (gems.contains(Gem.WHITE)) want.merge(PotionEffectType.INVISIBILITY, 0, Math::max);
        if (gems.contains(Gem.GREEN)) want.merge(PotionEffectType.HERO_OF_THE_VILLAGE, 4, Math::max);
        if (gems.contains(Gem.BLUE)) want.merge(PotionEffectType.SPEED, 0, Math::max);
        if (gems.contains(Gem.ORANGE)) want.merge(PotionEffectType.FIRE_RESISTANCE, 0, Math::max);
        if (gems.contains(Gem.BLOCK)) {
            for (PotionEffectType t : BLOCK_EFFECTS) want.merge(t, 0, Math::max);
        }

        if (gems.contains(Gem.PINK)) bonusHp += cfg("pink.bonus-hearts") * 2.0;
        if (gems.contains(Gem.BLACK) && p.getWorld().getEnvironment() == World.Environment.THE_END) {
            bonusHp += Math.max(0, cfg("black.end-hearts") * 2.0 - 20.0);
        }
        if (gems.contains(Gem.BLOCK)) bonusHp += Math.max(0, cfg("block.hearts") * 2.0 - 20.0);

        if (gems.contains(Gem.BLACK)) p.removePotionEffect(PotionEffectType.WITHER);

        // Effets (durée infinie, suivis pour pouvoir être retirés quand la gem disparaît)
        Set<PotionEffectType> applied = appliedEffects.computeIfAbsent(id, k -> new HashSet<>());
        for (Map.Entry<PotionEffectType, Integer> e : want.entrySet()) {
            PotionEffectType type = e.getKey();
            int amp = e.getValue();
            PotionEffect cur = p.getPotionEffect(type);
            if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION && cur.getAmplifier() != amp) {
                p.removePotionEffect(type);
                cur = null;
            }
            if (cur == null || cur.getAmplifier() < amp) {
                p.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amp, false, false, true));
                applied.add(type);
            }
        }
        Iterator<PotionEffectType> it = applied.iterator();
        while (it.hasNext()) {
            PotionEffectType t = it.next();
            if (!want.containsKey(t)) {
                PotionEffect cur = p.getPotionEffect(t);
                if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION) {
                    p.removePotionEffect(t);
                }
                it.remove();
            }
        }

        // Cœurs bonus
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            AttributeModifier existing = null;
            for (AttributeModifier m : attr.getModifiers()) {
                if (m.getKey().equals(healthKey)) existing = m;
            }
            if (bonusHp <= 0) {
                if (existing != null) attr.removeModifier(existing);
            } else if (existing == null || existing.getAmount() != bonusHp) {
                if (existing != null) attr.removeModifier(existing);
                attr.addModifier(new AttributeModifier(healthKey, bonusHp, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    // ------------------------------------------------------------------ clics

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Gem g = heldGem(p);
        if (g == null) return;

        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            leftClick(p, g);
            return;
        }
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            e.setUseItemInHand(Event.Result.DENY); // empêche d'utiliser la teinture, etc.
            Block clicked = e.getClickedBlock();
            if (a == Action.RIGHT_CLICK_BLOCK && !p.isSneaking() && clicked != null
                    && clicked.getType().isInteractable()) {
                return; // laisse ouvrir les coffres, portes, etc.
            }
            rightClick(p, g);
        }
    }

    private void leftClick(Player p, Gem g) {
        if (g == Gem.WHITE) whiteIntangible(p);
    }

    private void rightClick(Player p, Gem g) {
        switch (g) {
            case RED -> {
                if (p.isSneaking()) redStrength(p);
                else redTrack(p);
            }
            case PINK -> pinkHeal(p);
            case YELLOW -> yellowMace(p);
            case BLUE -> blueDash(p);
            case BLACK -> blackSkulls(p);
            case PURPLE -> {
                if (p.isSneaking()) purpleTeleport(p);
                else purpleFreecam(p);
            }
            case BLOCK -> {
                if (p.isSneaking()) blockAutocrit(p);
                else blockDash(p);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        ItemStack used = e.getPlayer().getInventory().getItem(e.getHand());
        if (gemOf(used) == null) return;
        Entity target = e.getRightClicked();
        if (target instanceof Sheep || target instanceof Wolf || target instanceof Cat) {
            e.setCancelled(true); // pas de teinture sur les moutons/loups/chats
        }
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (gemOf(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ WHITE

    private void whiteIntangible(Player p) {
        if (!ready(p, "white")) return;
        startCooldown(p, "white", cfg("white.cooldown"));
        intangible.put(p.getUniqueId(), System.currentTimeMillis() + cfg("white.intangible-duration") * 1000L);
        p.setCollidable(false);
        info(p, "Intangibilité activée", NamedTextColor.WHITE);
        sound(p, Sound.ENTITY_ENDERMAN_TELEPORT);
    }

    // ------------------------------------------------------------------ RED

    private void redTrack(Player p) {
        if (!ready(p, "red_track")) return;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || o.getGameMode() == GameMode.SPECTATOR) continue;
            double d = o.getLocation().distanceSquared(p.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = o;
            }
        }
        if (best == null) {
            info(p, "Aucun joueur à traquer dans cette dimension", NamedTextColor.RED);
            return;
        }
        startCooldown(p, "red_track", cfg("red.tracking-cooldown"));
        tracking.put(p.getUniqueId(),
                new Tracking(best.getUniqueId(), System.currentTimeMillis() + cfg("red.tracking-duration") * 1000L));
        info(p, "Tracking de " + best.getName(), NamedTextColor.RED);
        sound(p, Sound.BLOCK_BEACON_ACTIVATE);
    }

    private void redStrength(Player p) {
        if (!ready(p, "red_strength")) return;
        startCooldown(p, "red_strength", cfg("red.strength-cooldown"));
        strength.put(p.getUniqueId(), System.currentTimeMillis() + cfg("red.strength-duration") * 1000L);
        info(p, "Force x" + cfgD("red.strength-multiplier") + " activée", NamedTextColor.RED);
        sound(p, Sound.ENTITY_PLAYER_LEVELUP);
    }

    // ------------------------------------------------------------------ PINK

    private void pinkHeal(Player p) {
        if (!ready(p, "pink_heal")) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean any = false;
        for (ItemStack a : armor) {
            if (a == null || a.getType().isAir()) continue;
            if (a.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()) {
                d.setDamage(0);
                a.setItemMeta(d);
                any = true;
            }
        }
        if (!any) {
            info(p, "Ton armure est déjà intacte", NamedTextColor.LIGHT_PURPLE);
            return;
        }
        inv.setArmorContents(armor);
        startCooldown(p, "pink_heal", cfg("pink.heal-cooldown"));
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.5, 0), 8, 0.4, 0.4, 0.4, 0);
        info(p, "Armure réparée", NamedTextColor.LIGHT_PURPLE);
        sound(p, Sound.BLOCK_ANVIL_USE);
    }

    // ------------------------------------------------------------------ YELLOW

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (!e.getItem().getType().isEdible()) return;
        if (!gemsIn(p).contains(Gem.YELLOW)) return;
        if (remainingMs(p, "yellow_food") > 0) return;
        startCooldown(p, "yellow_food", cfg("yellow.food-cooldown"));
        Bukkit.getScheduler().runTask(this, () -> {
            p.setFoodLevel(20);
            p.setSaturation(20f);
        });
        info(p, "Saturation complète", NamedTextColor.YELLOW);
    }

    private void yellowMace(Player p) {
        if (!ready(p, "yellow_mace")) return;
        UUID id = p.getUniqueId();
        if (maceUntil.containsKey(id)) {
            info(p, "Ta mace est déjà active", NamedTextColor.YELLOW);
            return;
        }
        PlayerInventory inv = p.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        int slot = -1;
        for (int i = 0; i < storage.length; i++) {
            if (storage[i] != null && Tag.ITEMS_SWORDS.isTagged(storage[i].getType())) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            info(p, "Aucune épée dans ton inventaire", NamedTextColor.RED);
            return;
        }
        ItemStack original = storage[slot];
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.displayName(Component.text("Mace temporaire", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(maceKey, PersistentDataType.BYTE_ARRAY, original.serializeAsBytes());
        mace.setItemMeta(meta);
        inv.setItem(slot, mace);

        startCooldown(p, "yellow_mace", cfg("yellow.mace-cooldown"));
        maceUntil.put(id, System.currentTimeMillis() + cfg("yellow.mace-duration") * 1000L);
        info(p, "Ton épée devient une mace", NamedTextColor.YELLOW);
        sound(p, Sound.BLOCK_ANVIL_USE);
    }

    private ItemStack originalOf(ItemStack it) {
        if (it == null || it.getType() != Material.MACE || !it.hasItemMeta()) return null;
        byte[] bytes = it.getItemMeta().getPersistentDataContainer().get(maceKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return null;
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception ex) {
            return null;
        }
    }

    private void revertMace(Player p) {
        maceUntil.remove(p.getUniqueId());
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack orig = originalOf(inv.getItem(i));
            if (orig != null) inv.setItem(i, orig);
        }
        ItemStack orig = originalOf(p.getItemOnCursor());
        if (orig != null) p.setItemOnCursor(orig);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (originalOf(e.getItemDrop().getItemStack()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) return;

        boolean shift = e.isShiftClick() && originalOf(e.getCurrentItem()) != null;
        boolean place = e.getClickedInventory() != null && !e.getClickedInventory().equals(p.getInventory())
                && originalOf(e.getCursor()) != null;
        boolean swap = false;
        if (e.getClick() == ClickType.NUMBER_KEY && e.getClickedInventory() != null
                && !e.getClickedInventory().equals(p.getInventory())) {
            swap = originalOf(p.getInventory().getItem(e.getHotbarButton())) != null;
        }
        if (shift || place || swap) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        ListIterator<ItemStack> it = e.getDrops().listIterator();
        while (it.hasNext()) {
            ItemStack orig = originalOf(it.next());
            if (orig != null) it.set(orig);
        }
        maceUntil.remove(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ BLUE / BLOCK : dash

    private void blueDash(Player p) {
        if (!ready(p, "blue_dash")) return;
        if (getConfig().getBoolean("blue.block-sprint") && p.isSprinting()) {
            info(p, "Impossible de dash en sprintant", NamedTextColor.RED);
            return;
        }
        if (dash(p, cfgD("blue.dash-distance"))) {
            startCooldown(p, "blue_dash", cfg("blue.dash-cooldown"));
        }
    }

    private void blockDash(Player p) {
        if (!ready(p, "block_dash")) return;
        if (dash(p, cfgD("block.dash-distance"))) {
            startCooldown(p, "block_dash", cfg("block.dash-cooldown"));
        }
    }

    private boolean dash(Player p, double distance) {
        Location loc = p.getLocation();
        World w = p.getWorld();
        Vector dir = p.getEyeLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            double yaw = Math.toRadians(loc.getYaw());
            dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        dir.normalize();

        double d = distance;
        for (double h : new double[]{0.1, 0.9, 1.7}) {
            Location origin = loc.clone().add(0, h, 0);
            RayTraceResult r = w.rayTraceBlocks(origin, dir, distance + 0.5, FluidCollisionMode.NEVER, true);
            if (r != null && r.getHitPosition() != null) {
                d = Math.min(d, Math.max(0, r.getHitPosition().distance(origin.toVector()) - 0.5));
            }
        }
        if (d < 0.5) {
            info(p, "Dash bloqué", NamedTextColor.RED);
            return false;
        }
        for (double t = 0; t <= d; t += 0.5) {
            w.spawnParticle(Particle.END_ROD, loc.clone().add(dir.clone().multiply(t)).add(0, 1, 0), 1, 0.1, 0.3, 0.1, 0);
        }
        Location dest = loc.clone().add(dir.clone().multiply(d));
        p.teleport(dest);
        sound(p, Sound.ENTITY_PLAYER_ATTACK_SWEEP);
        return true;
    }

    // ------------------------------------------------------------------ BLACK

    private void blackSkulls(Player p) {
        if (!ready(p, "black_skulls")) return;
        startCooldown(p, "black_skulls", cfg("black.skull-cooldown"));
        Vector base = p.getEyeLocation().getDirection();
        for (int i = -1; i <= 1; i++) {
            Vector v = base.clone().rotateAroundY(Math.toRadians(i * 12.0));
            p.launchProjectile(WitherSkull.class, v);
        }
        sound(p, Sound.ENTITY_WITHER_SHOOT);
        info(p, "3 têtes de Wither lancées", NamedTextColor.DARK_GRAY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PotionEffect n = e.getNewEffect();
        if (n != null && n.getType().equals(PotionEffectType.WITHER) && gemsIn(p).contains(Gem.BLACK)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ PURPLE

    private void purpleTeleport(Player p) {
        if (!ready(p, "purple_tp")) return;
        double range = cfgD("purple.teleport-range");
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);

        Location base;
        if (r != null && r.getHitPosition() != null) {
            Vector hit = r.getHitPosition();
            BlockFace face = r.getHitBlockFace();
            Vector off = face != null ? face.getDirection().multiply(0.5) : new Vector();
            base = hit.clone().add(off).toLocation(p.getWorld());
            if (face == BlockFace.DOWN) base.add(0, -1.8, 0);
        } else {
            base = eye.clone().add(eye.getDirection().multiply(range));
        }
        base.setYaw(p.getLocation().getYaw());
        base.setPitch(p.getLocation().getPitch());

        Location dest = safeSpot(base);
        if (dest == null) {
            info(p, "Pas de place pour se téléporter ici", NamedTextColor.RED);
            return;
        }
        startCooldown(p, "purple_tp", cfg("purple.teleport-cooldown"));
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        p.teleport(dest);
        p.setFallDistance(0);
        p.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        sound(p, Sound.ENTITY_ENDERMAN_TELEPORT);
    }

    private Location safeSpot(Location base) {
        for (double dy : new double[]{0, -1, -2, 1}) {
            Location c = base.clone().add(0, dy, 0);
            Block feet = c.getBlock();
            Block head = c.clone().add(0, 1, 0).getBlock();
            if (feet.isPassable() && head.isPassable()) return c;
        }
        return null;
    }

    private void purpleFreecam(Player p) {
        UUID id = p.getUniqueId();
        if (freecams.containsKey(id)) return;
        if (!ready(p, "purple_freecam")) return;
        startCooldown(p, "purple_freecam", cfg("purple.freecam-cooldown"));

        Location origin = p.getLocation().clone();
        GameMode gm = p.getGameMode();

        ArmorStand stand = origin.getWorld().spawn(origin, ArmorStand.class, as -> {
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.customName(Component.text(p.getName()));
            as.setCustomNameVisible(true);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(p);
            head.setItemMeta(sm);
            as.getEquipment().setHelmet(head);
        });

        long until = System.currentTimeMillis() + cfg("purple.freecam-duration") * 1000L;
        freecams.put(id, new Freecam(gm, origin, stand, until));
        p.getPersistentDataContainer().set(freecamKey, PersistentDataType.STRING,
                gm.name() + ";" + origin.getWorld().getName() + ";" + origin.getX() + ";" + origin.getY() + ";"
                        + origin.getZ() + ";" + origin.getYaw() + ";" + origin.getPitch());
        p.setGameMode(GameMode.SPECTATOR);
        sound(p, Sound.ENTITY_ENDERMAN_TELEPORT);
    }

    private void endFreecam(Player p) {
        Freecam f = freecams.remove(p.getUniqueId());
        if (f == null) return;
        p.setGameMode(f.gameMode());
        p.teleport(f.origin());
        p.setFallDistance(0);
        if (f.stand() != null) f.stand().remove();
        p.getPersistentDataContainer().remove(freecamKey);
    }

    /** Récupération si le serveur a crashé pendant une freecam. */
    private void recoverFreecam(Player p) {
        String data = p.getPersistentDataContainer().get(freecamKey, PersistentDataType.STRING);
        if (data == null) return;
        p.getPersistentDataContainer().remove(freecamKey);
        try {
            String[] s = data.split(";");
            p.setGameMode(GameMode.valueOf(s[0]));
            World w = Bukkit.getWorld(s[1]);
            if (w != null) {
                p.teleport(new Location(w, Double.parseDouble(s[2]), Double.parseDouble(s[3]),
                        Double.parseDouble(s[4]), Float.parseFloat(s[5]), Float.parseFloat(s[6])));
            }
        } catch (Exception ex) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    // ------------------------------------------------------------------ BLOCK

    private void blockAutocrit(Player p) {
        if (!ready(p, "block_autocrit")) return;
        startCooldown(p, "block_autocrit", cfg("block.autocrit-cooldown"));
        autocrit.put(p.getUniqueId(), System.currentTimeMillis() + cfg("block.autocrit-duration") * 1000L);
        info(p, "Auto-crit activé", NamedTextColor.AQUA);
        sound(p, Sound.ENTITY_PLAYER_LEVELUP);
    }

    // ------------------------------------------------------------------ combat

    /** Dégâts reçus : intangibilité, immunité Wither, immunité foudre du Gem Block. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        EntityDamageEvent.DamageCause cause = e.getCause();

        if (cause == EntityDamageEvent.DamageCause.WITHER && gemsIn(victim).contains(Gem.BLACK)) {
            e.setCancelled(true);
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.LIGHTNING && gemsIn(victim).contains(Gem.BLOCK)) {
            e.setCancelled(true);
            return;
        }
        if (isActive(intangible, victim)
                && cause != EntityDamageEvent.DamageCause.VOID
                && cause != EntityDamageEvent.DamageCause.KILL
                && cause != EntityDamageEvent.DamageCause.SUICIDE) {
            e.setDamage(e.getDamage() * cfgD("white.damage-multiplier"));
        }
    }

    /** Dégâts infligés : crits, force, éclair, hunger, clic gauche. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        Gem held = heldGem(attacker);
        EnumSet<Gem> gems = gemsIn(attacker);

        // Clic gauche sur une entité avec la gem en main
        if (held == Gem.WHITE) leftClick(attacker, Gem.WHITE);
        if (held == Gem.GREEN && e.getEntity() instanceof Player target) {
            if (remainingMs(attacker, "green_hunger") == 0) {
                startCooldown(attacker, "green_hunger", cfg("green.hunger-cooldown"));
                target.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER,
                        cfg("green.hunger-duration") * 20, Math.max(0, cfg("green.hunger-level") - 1)));
                info(attacker, target.getName() + " a faim !", NamedTextColor.GREEN);
            }
        }

        // Auto-crit
        boolean crit = false;
        if (gems.contains(Gem.RED) && attacker.getWorld().getEnvironment() == World.Environment.NETHER) crit = true;
        if (isActive(autocrit, attacker)) crit = true;

        double mult = 1.0;
        if (crit) mult *= cfgD("red.nether-crit-multiplier");
        if (isActive(strength, attacker)) mult *= cfgD("red.strength-multiplier");
        if (mult != 1.0) {
            e.setDamage(e.getDamage() * mult);
        }
        if (crit) {
            e.getEntity().getWorld().spawnParticle(Particle.CRIT,
                    e.getEntity().getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.1);
        }

        // Éclair à chaque coup (Gem Block)
        if (gems.contains(Gem.BLOCK) && e.getEntity() instanceof LivingEntity victim && victim != attacker) {
            Location loc = victim.getLocation();
            Bukkit.getScheduler().runTask(this, () -> loc.getWorld().strikeLightning(loc));
        }
    }

    // ------------------------------------------------------------------ intangibilité (White)

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getHitEntity() instanceof Player p && isActive(intangible, p)) {
            e.setCancelled(true); // les projectiles traversent
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && isActive(intangible, p)) {
            e.setCancelled(true); // les mobs ne ciblent plus
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        if (isActive(intangible, e.getPlayer())) {
            e.setCancelled(true); // pas de recul
        }
    }

    // ------------------------------------------------------------------ connexion / déconnexion

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setCollidable(true);
        recoverFreecam(p);
        revertMace(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cleanup(e.getPlayer());
    }

    // ------------------------------------------------------------------ commande /gem

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/gem <give <joueur> <gem> [nombre] | list | reload>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("/gem give <joueur> <gem> [nombre]", NamedTextColor.YELLOW));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Joueur introuvable.", NamedTextColor.RED));
                    return true;
                }
                Gem gem;
                try {
                    gem = Gem.valueOf(args[2].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(Component.text("Gem inconnue. /gem list", NamedTextColor.RED));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ignored) {
                        // garde 1
                    }
                }
                for (int i = 0; i < amount; i++) {
                    Map<Integer, ItemStack> left = target.getInventory().addItem(createGem(gem));
                    left.values().forEach(it -> target.getWorld().dropItemNaturally(target.getLocation(), it));
                }
                sender.sendMessage(Component.text(amount + "x " + gem.displayName + " donné à " + target.getName(),
                        NamedTextColor.GREEN));
            }
            case "list" -> {
                for (Gem g : Gem.values()) {
                    sender.sendMessage(Component.text(g.name().toLowerCase(Locale.ROOT) + " - " + g.displayName, g.color));
                }
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(Component.text("Config rechargée.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Sous-commande inconnue.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"give", "list", "reload"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Gem g : Gem.values()) {
                String n = g.name().toLowerCase(Locale.ROOT);
                if (n.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(n);
            }
        }
        return out;
    }
}
