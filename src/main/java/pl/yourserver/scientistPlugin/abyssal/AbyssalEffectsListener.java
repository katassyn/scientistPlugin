package pl.yourserver.scientistPlugin.abyssal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.UUID;

public class AbyssalEffectsListener implements Listener {
    private final ScientistPlugin plugin;
    private final AbyssalService service;
    private final Map<UUID, AttributeModifier> chronoApplied = new ConcurrentHashMap<>();
    private final Map<UUID, Debuff> severing = new ConcurrentHashMap<>();
    private final Map<UUID, AttributeModifier> chestHP = new ConcurrentHashMap<>();
    private final Map<UUID, AttributeModifier> legsSpeed = new ConcurrentHashMap<>();
    private final Map<UUID, Double> nextAttackBonus = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTrail = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> comboStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> comboExpires = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> markedTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> spellMenderCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPackTick = new ConcurrentHashMap<>();

    public AbyssalEffectsListener(ScientistPlugin plugin, AbyssalService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private JsonObject readTag(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String raw = pdc.get(new org.bukkit.NamespacedKey(plugin, "abyssal"), PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return null;
        try { return JsonParser.parseString(raw).getAsJsonObject(); } catch (Exception e) { return null; }
    }

    private void withAbyssal(ItemStack item, Consumer<JsonObject> consumer) {
        JsonObject jo = readTag(item);
        if (jo != null) consumer.accept(jo);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) e.getDamager();
            if (proj.getShooter() instanceof Player) attacker = (Player) proj.getShooter();
        }
        if (attacker == null) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        ItemStack main = attacker.getInventory().getItem(EquipmentSlot.HAND);
        JsonObject tag = readTag(main);
        if (tag == null) return;
        String key = optString(tag, "modifier_key");
        int tier = optInt(tag, "tier", 1);
        double val = optDouble(tag, "value", 0.0);

        double dmg = e.getDamage();

        switch (key) {
            case "void_rend" -> {
                // +% Damage
                double bonus = dmg * (val / 100.0);
                e.setDamage(dmg + bonus);
            }
            case "rift_pierce" -> {
                // Treat as extra % damage (placeholder for armor pen integration)
                double bonus = dmg * (val / 100.0);
                e.setDamage(dmg + bonus);
            }
            case "bloodsiphon" -> {
                // Lifesteal % of final damage (post event)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    double finalDmg = e.getFinalDamage();
                    double amp = 0.0;
                    try {
                        Double a = attacker.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "leech_amp"), org.bukkit.persistence.PersistentDataType.DOUBLE);
                        if (a != null) amp = a;
                    } catch (Throwable ignored) {}
                    double heal = finalDmg * ((val + amp) / 100.0);
                    attacker.setHealth(Math.min(attacker.getMaxHealth(), attacker.getHealth() + heal));
                });
            }
            case "severing_hex" -> {
                // Healing received debuff on target (3s)
                long until = System.currentTimeMillis() + 3000L;
                severing.put(target.getUniqueId(), new Debuff(until, val));
            }
            case "frostbrand" -> {
                // On-hit slow chance (2s); value is chance %
                if (ThreadLocalRandom.current().nextDouble(100.0) <= val) {
                    int ticks = 40; // 2s
                    target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, ticks, Math.max(0, tier - 1), false, true, true));
                }
            }
            case "emberbrand" -> {
                // Burn DoT based on tier: 20/40/60 fire ticks
                int fireTicks = 20 * tier;
                target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));
            }
            case "echo_slash" -> {
                // Chance to echo strike for 50% damage
                if (ThreadLocalRandom.current().nextDouble(100.0) <= val) {
                    double extra = e.getFinalDamage() * 0.5;
                    double dealt = Math.max(0.0, extra);
                    Bukkit.getScheduler().runTask(plugin, () -> target.damage(dealt, attacker));
                }
            }
            case "braced_core" -> {
                // Outgoing knockback reduction when wearer attacks (scale target velocity)
                if (e.getEntity() instanceof LivingEntity le) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        var vel = le.getVelocity();
                        le.setVelocity(vel.multiply(Math.max(0.0, 1.0 - (val / 100.0))));
                    });
                }
            }
            case "soul_harvester" -> {
                // Damage vs Elites/Bosses % (treat any MythicMob as elite/boss)
                if (isMythic(target)) {
                    double bonus = dmg * (val / 100.0);
                    e.setDamage(dmg + bonus);
                }
            }
            case "cold_focus" -> {
                // Crit Damage % (approximate as +% damage)
                double bonus = dmg * (val / 100.0);
                e.setDamage(dmg + bonus);
            }
            case "stormlash" -> {
                // Chain lightning chance: tier-based damage multiplier
                if (ThreadLocalRandom.current().nextDouble(100.0) <= val) {
                    double mult = switch (tier) {
                        case 1 -> 0.30; case 2 -> 0.35; default -> 0.40; };
                    double chainDmg = e.getFinalDamage() * mult;
                    int bounces = Math.min(3, 1 + tier); // 2-3 targets
                    var nearby = target.getWorld().getNearbyEntities(target.getLocation(), 5, 5, 5);
                    int hit = 0;
                    for (var en : nearby) {
                        if (hit >= bounces) break;
                        if (en instanceof LivingEntity le && !le.getUniqueId().equals(target.getUniqueId()) && !le.getUniqueId().equals(attacker.getUniqueId())) {
                            hit++;
                            LivingEntity le2 = le;
                            Bukkit.getScheduler().runTask(plugin, () -> le2.damage(Math.max(0.0, chainDmg), attacker));
                        }
                    }
                }
            }
            default -> {
                // no-op for now
            }
        }
        // GLOVES: vampiric_grasp – lifesteal on crit (% of final damage)
        double vg = getAccessoryValue(attacker, "GLOVES", "vampiric_grasp");
        if (vg > 0) {
            boolean crit = false;
            try {
                Class<?> expCls = Class.forName("com.maks.myexperienceplugin.MyExperiencePlugin");
                Object expInst = expCls.getMethod("getInstance").invoke(null);
                Object css = expCls.getMethod("getCriticalStrikeSystem").invoke(expInst);
                crit = (boolean) css.getClass().getMethod("rollForCritical", Player.class).invoke(css, attacker);
            } catch (Throwable ignored) {}
            if (crit) {
                double amp = 0.0;
                try {
                    Double a = attacker.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "leech_amp"), org.bukkit.persistence.PersistentDataType.DOUBLE);
                    if (a != null) amp = a;
                } catch (Throwable ignored) {}
                double heal = e.getFinalDamage() * Math.max(0.0, (vg + amp) / 100.0);
                double newHp = Math.min(attacker.getMaxHealth(), attacker.getHealth() + heal);
                Player atk = attacker;
                Bukkit.getScheduler().runTask(plugin, () -> atk.setHealth(newHp));
            }
        }
        // RINGS: puncturing – flat pen as flat extra damage
        double punct = getAccessoryValue(attacker, "RING_1", "puncturing") + getAccessoryValue(attacker, "RING_2", "puncturing");
        if (punct > 0) {
            e.setDamage(e.getDamage() + punct);
        }
        // RINGS: bane_of_beasts – damage vs mobs (non-players) %
        if (!(target instanceof Player)) {
            double bane = Math.max(getAccessoryValue(attacker, "RING_1", "bane_of_beasts"), getAccessoryValue(attacker, "RING_2", "bane_of_beasts"));
            if (bane > 0) e.setDamage(e.getDamage() * (1.0 + bane / 100.0));
        }
        // RINGS: hunter_mark – mark + dmg vs marked
        double markChance = Math.max(getAccessoryValue(attacker, "RING_1", "hunter_mark"), getAccessoryValue(attacker, "RING_2", "hunter_mark"));
        if (markChance > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= markChance) {
            markedTargets.computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>())
                    .put(target.getUniqueId(), System.currentTimeMillis() + 6000L);
        }
        Long until = markedTargets.getOrDefault(attacker.getUniqueId(), Collections.emptyMap()).get(target.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            double vsMarked = switch (tier) { case 1 -> 5.0; case 2 -> 8.0; default -> 12.0; };
            e.setDamage(e.getDamage() * (1.0 + vsMarked / 100.0));
        }

        // GLOVES: combo_artist – stacking dmg per hit (max 5 stacks, decay 3s)
        double comboVal = getAccessoryValue(attacker, "GLOVES", "combo_artist");
        if (comboVal > 0) {
            long now = System.currentTimeMillis();
            int stacks = comboStacks.getOrDefault(attacker.getUniqueId(), 0);
            if (now > comboExpires.getOrDefault(attacker.getUniqueId(), 0L)) stacks = 0;
            stacks = Math.min(5, stacks + 1);
            comboStacks.put(attacker.getUniqueId(), stacks);
            comboExpires.put(attacker.getUniqueId(), now + 3000L);
            e.setDamage(e.getDamage() * (1.0 + (stacks * comboVal) / 100.0));
        }

        // Mark attacker in combat
        lastCombat.put(attacker.getUniqueId(), System.currentTimeMillis());
        // Belt: adrenal_surge – bonus dmg while below 35% HP
        double ratio = attacker.getHealth() / Math.max(1.0, attacker.getMaxHealth());
        if (ratio < 0.35) {
            double surge = getAccessoryValue(attacker, "BELT", "adrenal_surge");
            if (surge > 0) e.setDamage(e.getDamage() * (1.0 + surge / 100.0));
        }

        // Mark attacker as in-combat
        lastCombat.put(attacker.getUniqueId(), System.currentTimeMillis());

        // Apply one-time next-attack bonus from shield (counter_edge)
        Double buff = nextAttackBonus.remove(attacker.getUniqueId());
        if (buff != null && buff > 0) {
            e.setDamage(e.getDamage() * (1.0 + buff / 100.0));
        }

        // HELM battle_instinct – first hit vs full HP target
        ItemStack helm = attacker.getInventory().getItem(EquipmentSlot.HEAD);
        JsonObject helmTag = readTag(helm);
        if (helmTag != null && "battle_instinct".equals(optString(helmTag, "modifier_key"))) {
            if (Math.abs(target.getHealth() - target.getMaxHealth()) < 0.001) {
                double bi = optDouble(helmTag, "value", 0.0);
                e.setDamage(e.getDamage() * (1.0 + bi / 100.0));
            }
        }
        // GLOVES: chill_touch – slow chance %
        double ct = getAccessoryValue(attacker, "GLOVES", "chill_touch");
        if (ct > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= ct) {
            int ticks = 40;
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, ticks, 0, false, true, true));
        }
        // GLOVES: bloodletter – apply wither as bleed
        double bl = getAccessoryValue(attacker, "GLOVES", "bloodletter");
        if (bl > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= bl) {
            int ticks = 60;
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WITHER, ticks, 0, false, true, true));
        }
        // GLOVES: sandman – sleep/slow
        double sman = getAccessoryValue(attacker, "GLOVES", "sandman");
        if (sman > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= sman) {
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, 40, 1, false, true, true));
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 40, 0, false, true, true));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        double dmg = e.getDamage();

        // HELM warding_thought – ranged damage reduction
        ItemStack helmItem = victim.getEquipment() != null ? victim.getEquipment().getHelmet() : null;
        JsonObject helmTag = readTag(helmItem);
        if (helmTag != null && "warding_thought".equals(optString(helmTag, "modifier_key"))) {
            boolean ranged = e.getCause() == EntityDamageEvent.DamageCause.PROJECTILE;
            if (!ranged && e instanceof EntityDamageByEntityEvent ebe) ranged = (ebe.getDamager() instanceof Projectile);
            if (ranged) {
                double dr = optDouble(helmTag, "value", 0.0);
                e.setDamage(dmg * Math.max(0.0, 1.0 - dr / 100.0));
                dmg = e.getDamage();
            }
        }

        // Chest: abyssal_carapace – All Damage Reduction %
        ItemStack chestItem = victim.getEquipment() != null ? victim.getEquipment().getChestplate() : null;
        JsonObject chestTag = readTag(chestItem);
        if (chestTag != null && "abyssal_carapace".equals(optString(chestTag, "modifier_key"))) {
            double val = optDouble(chestTag, "value", 0.0);
            e.setDamage(dmg * Math.max(0.0, 1.0 - val / 100.0));
            dmg = e.getDamage();
        }

        // Boots: shadowstep – Evade chance % -> cancel damage
        ItemStack bootsItem = victim.getEquipment() != null ? victim.getEquipment().getBoots() : null;
        JsonObject bootsTag = readTag(bootsItem);
        if (bootsTag != null && "shadowstep".equals(optString(bootsTag, "modifier_key"))) {
            double chance = optDouble(bootsTag, "value", 0.0);
            if (ThreadLocalRandom.current().nextDouble(100.0) <= chance) {
                e.setCancelled(true);
                return;
            }
        }
        // CLOAK: windveil – projectile dodge chance via accessory
        if (victim instanceof Player) {
            Player vpWind = (Player) victim;
            double wind = getAccessoryValue(vpWind, "CLOAK", "windveil");
            if (wind > 0) {
                boolean ranged = e.getCause() == EntityDamageEvent.DamageCause.PROJECTILE;
                if (!ranged && e instanceof EntityDamageByEntityEvent) {
                    EntityDamageByEntityEvent ebe2 = (EntityDamageByEntityEvent) e;
                    ranged = (ebe2.getDamager() instanceof Projectile);
                }
                if (ranged && ThreadLocalRandom.current().nextDouble(100.0) <= wind) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // Shield: bastion – Block Chance % when blocking with shield in offhand
        if (victim instanceof Player) {
            Player p = (Player) victim;
            ItemStack off = p.getInventory().getItem(EquipmentSlot.OFF_HAND);
            JsonObject offTag = readTag(off);
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "bastion".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking()) {
                    double chance = optDouble(offTag, "value", 0.0);
                    if (ThreadLocalRandom.current().nextDouble(100.0) <= chance) {
                        e.setCancelled(true);
                        return;
                    }
                }
            }
            // Shield shield_bash – stun attacker on block
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "shield_bash".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking() && e instanceof EntityDamageByEntityEvent) {
                    EntityDamageByEntityEvent ebe = (EntityDamageByEntityEvent) e;
                    double chance = optDouble(offTag, "value", 0.0);
                    if (ThreadLocalRandom.current().nextDouble(100.0) <= chance) {
                        int tier = optInt(offTag, "tier", 1);
                        int stunTicks = switch (tier) { case 1 -> 10; case 2 -> 14; default -> 20; }; // 0.5/0.7/1.0s
                        var damager = ebe.getDamager();
                        if (damager instanceof LivingEntity) {
                            LivingEntity target = (LivingEntity) damager;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, stunTicks, 10, false, true, true));
                                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_DIGGING, stunTicks, 10, false, true, true));
                            });
                        }
                    }
                }
            }
            // Shield turtle_shell – DR while blocking
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "turtle_shell".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking()) {
                    double dr = optDouble(offTag, "value", 0.0);
                    e.setDamage(dmg * Math.max(0.0, 1.0 - dr / 100.0));
                    dmg = e.getDamage();
                }
            }
            // Shield riposte – return % damage on block (to attacker)
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "riposte".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking() && e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
                    org.bukkit.event.entity.EntityDamageByEntityEvent ebe = (org.bukkit.event.entity.EntityDamageByEntityEvent) e;
                    double retPct = optDouble(offTag, "value", 0.0);
                    double ret = dmg * (retPct / 100.0);
                    var damager = ebe.getDamager();
                    LivingEntity tgt = null;
                    if (damager instanceof LivingEntity) tgt = (LivingEntity) damager;
                    else if (damager instanceof Projectile) {
                        Projectile pr = (Projectile) damager;
                        if (pr.getShooter() instanceof LivingEntity) tgt = (LivingEntity) pr.getShooter();
                    }
                    if (tgt != null && ret > 0.0) {
                        LivingEntity finalTgt = tgt;
                        Bukkit.getScheduler().runTask(plugin, () -> finalTgt.damage(Math.max(0.0, ret), p));
                    }
                }
            }
            // Shield brace_up – speed after block (2s)
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "brace_up".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking()) {
                    int ticks = 40; // 2s
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, ticks, 0, false, true, true));
                }
            }
            // Shield bulwark_flow – small heal after block
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "bulwark_flow".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking()) {
                    double heal = optDouble(offTag, "value", 0.0);
                    double newHp = Math.min(p.getMaxHealth(), p.getHealth() + heal);
                    p.setHealth(newHp);
                }
            }
            // Shield counter_edge – buff next attack after block
            if (off != null && off.getType() == org.bukkit.Material.SHIELD && offTag != null && "counter_edge".equals(optString(offTag, "modifier_key"))) {
                if (p.isBlocking()) {
                    double pct = optDouble(offTag, "value", 0.0);
                    nextAttackBonus.put(p.getUniqueId(), pct);
                }
            }
        }

        // CLOAK: null_drape – projectile damage taken reduction
        if (victim instanceof Player) {
            Player vpNull = (Player) victim;
            double nd = getAccessoryValue(vpNull, "CLOAK", "null_drape");
            if (nd > 0) {
                boolean ranged = e.getCause() == EntityDamageEvent.DamageCause.PROJECTILE;
                if (!ranged && e instanceof EntityDamageByEntityEvent) {
                    EntityDamageByEntityEvent ebe3 = (EntityDamageByEntityEvent) e;
                    ranged = (ebe3.getDamager() instanceof Projectile);
                }
                if (ranged) {
                    e.setDamage(dmg * Math.max(0.0, 1.0 - nd / 100.0));
                    dmg = e.getDamage();
                }
            }
        }

        // BOOTS: shock_absorb – small AoE on landing (fall damage)
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && victim instanceof Player) {
            Player vpFall = (Player) victim;
            ItemStack boots = vpFall.getInventory().getItem(EquipmentSlot.FEET);
            JsonObject bt = readTag(boots);
            if (bt != null && "shock_absorb".equals(optString(bt, "modifier_key"))) {
                double stomp = optDouble(bt, "value", 0.0);
                var near = vpFall.getWorld().getNearbyLivingEntities(vpFall.getLocation(), 3.0, 2.0, 3.0, le -> !le.getUniqueId().equals(vpFall.getUniqueId()));
                for (LivingEntity le : near) {
                    LivingEntity tgt = le;
                    Bukkit.getScheduler().runTask(plugin, () -> tgt.damage(Math.max(0.0, stomp), vpFall));
                }
            }
        }

        // CHEST: guardian_aura – AoE damage taken reduction (explosions)
        ItemStack chestAura = victim.getEquipment() != null ? victim.getEquipment().getChestplate() : null;
        JsonObject chestAuraTag = readTag(chestAura);
        if (chestAuraTag != null && "guardian_aura".equals(optString(chestAuraTag, "modifier_key"))) {
            EntityDamageEvent.DamageCause c = e.getCause();
            if (c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                double dr = optDouble(chestAuraTag, "value", 0.0);
                e.setDamage(dmg * Math.max(0.0, 1.0 - dr / 100.0));
                dmg = e.getDamage();
            }
        }

        // CHEST: stoic_guard – flat damage mitigation
        ItemStack chestItem2 = victim.getEquipment() != null ? victim.getEquipment().getChestplate() : null;
        JsonObject chest2 = readTag(chestItem2);
        if (chest2 != null && "stoic_guard".equals(optString(chest2, "modifier_key"))) {
            double flat = optDouble(chest2, "value", 0.0);
            e.setDamage(Math.max(0.0, dmg - flat));
            dmg = e.getDamage();
        }
        // CHEST: battle_hardened – DR while below 35% HP
        if (chest2 != null && "battle_hardened".equals(optString(chest2, "modifier_key"))) {
            double ratio = victim.getHealth() / Math.max(1.0, victim.getMaxHealth());
            if (ratio < 0.35) {
                double dr = optDouble(chest2, "value", 0.0);
                e.setDamage(dmg * Math.max(0.0, 1.0 - dr / 100.0));
                dmg = e.getDamage();
            }
        }
        // CHEST: purge_skin – chance to cleanse a negative effect on hit taken
        if (chest2 != null && "purge_skin".equals(optString(chest2, "modifier_key"))) {
            double chance = optDouble(chest2, "value", 0.0);
            if (ThreadLocalRandom.current().nextDouble(100.0) <= chance) {
                var active = victim.getActivePotionEffects();
                for (var pe : active) {
                    if (isNegativeEffect(pe.getType())) {
                        victim.removePotionEffect(pe.getType());
                        break;
                    }
                }
            }
        }

        // CHEST: banner_of_valor – Nearby allies DR % (5 blocks)
        if (victim instanceof Player) {
            Player vp2 = (Player) victim;
            double best = 0.0;
            for (Player near : vp2.getWorld().getNearbyPlayers(vp2.getLocation(), 5.0)) {
                if (near.getUniqueId().equals(vp2.getUniqueId())) continue;
                if (!areInSameParty(near, vp2)) continue;
                ItemStack nc = near.getInventory().getItem(EquipmentSlot.CHEST);
                JsonObject nt = readTag(nc);
                if (nt != null && "banner_of_valor".equals(optString(nt, "modifier_key"))) {
                    best = Math.max(best, optDouble(nt, "value", 0.0));
                }
            }
            if (best > 0.0) {
                e.setDamage(dmg * Math.max(0.0, 1.0 - best / 100.0));
                dmg = e.getDamage();
            }
        }

        // ADORNMENT: backlash_net – chance to silence caster on hit taken
        if (victim instanceof Player) {
            Player vpBN = (Player) victim;
            if (e instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent ebeBN = (EntityDamageByEntityEvent) e;
                double back = getAccessoryValue(vpBN, "ADORNMENT", "backlash_net");
                if (back > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= back) {
                    Player caster = null;
                    var damager = ebeBN.getDamager();
                    if (damager instanceof Player) caster = (Player) damager;
                    else if (damager instanceof Projectile) {
                        Projectile pr = (Projectile) damager;
                        if (pr.getShooter() instanceof Player) caster = (Player) pr.getShooter();
                    }
                    if (caster != null) {
                        applySilence(caster, 40);
                    }
                }
            }
        }

        // Mark victim as in-combat
        if (victim instanceof Player) {
            Player vp = (Player) victim;
            lastCombat.put(vp.getUniqueId(), System.currentTimeMillis());
            // CLOAK: smoke_screen – chance to drop decoy on hit
            double smoke = getAccessoryValue(vp, "CLOAK", "smoke_screen");
            if (smoke > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= smoke) {
                vp.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY, 40, 0, false, true, true));
                vp.getWorld().spawnParticle(org.bukkit.Particle.SMOKE_LARGE, vp.getLocation(), 12, 0.5, 0.5, 0.5, 0.01);
            }
            // CLOAK: pace_keeper – keep sprint after hit
            double pace = getAccessoryValue(vp, "CLOAK", "pace_keeper");
            if (pace > 0 && ThreadLocalRandom.current().nextDouble(100.0) <= pace) {
                vp.setSprinting(true);
                vp.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 40, 0, false, true, true));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        // LEGS: root_breaker – slow resistance
        ItemStack legs = le.getEquipment() != null ? le.getEquipment().getLeggings() : null;
        JsonObject legsTag = readTag(legs);
        if (legsTag != null && "root_breaker".equals(optString(legsTag, "modifier_key")) && e.getNewEffect() != null && e.getNewEffect().getType() == org.bukkit.potion.PotionEffectType.SLOW) {
            double res = optDouble(legsTag, "value", 0.0);
            int dur = e.getNewEffect().getDuration();
            int newDur = (int) Math.max(1, dur * Math.max(0.0, 1.0 - res / 100.0));
            e.setCancelled(true);
            le.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, newDur, e.getNewEffect().getAmplifier(), false, true, true));
        }
        // HELM: null_veil – reduce negative durations
        ItemStack helm = le.getEquipment() != null ? le.getEquipment().getHelmet() : null;
        JsonObject helmTag2 = readTag(helm);
        if (helmTag2 != null && "null_veil".equals(optString(helmTag2, "modifier_key")) && e.getNewEffect() != null) {
            org.bukkit.potion.PotionEffectType type = e.getNewEffect().getType();
            if (isNegativeEffect(type)) {
                double res = optDouble(helmTag2, "value", 0.0);
                int dur = e.getNewEffect().getDuration();
                int newDur = (int) Math.max(1, dur * Math.max(0.0, 1.0 - res / 100.0));
                e.setCancelled(true);
                le.addPotionEffect(new org.bukkit.potion.PotionEffect(type, newDur, e.getNewEffect().getAmplifier(), false, true, true));
            }
        }
        // NECKLACE: purity_seal – reduce negative durations
        if (le instanceof Player && e.getNewEffect() != null) {
            Player pNeck = (Player) le;
            org.bukkit.potion.PotionEffectType type = e.getNewEffect().getType();
            if (isNegativeEffect(type)) {
                double res = getAccessoryValue(pNeck, "NECKLACE", "purity_seal");
                if (res > 0) {
                    int dur = e.getNewEffect().getDuration();
                    int newDur = (int) Math.max(1, dur * Math.max(0.0, 1.0 - res / 100.0));
                    e.setCancelled(true);
                    le.addPotionEffect(new org.bukkit.potion.PotionEffect(type, newDur, e.getNewEffect().getAmplifier(), false, true, true));
                }
            }
        }
        // ADORNMENT: hexguard – reduce negative durations
        if (le instanceof Player && e.getNewEffect() != null) {
            Player pAd = (Player) le;
            org.bukkit.potion.PotionEffectType type = e.getNewEffect().getType();
            if (isNegativeEffect(type)) {
                double res = getAccessoryValue(pAd, "ADORNMENT", "hexguard");
                if (res > 0) {
                    int dur = e.getNewEffect().getDuration();
                    int newDur = (int) Math.max(1, dur * Math.max(0.0, 1.0 - res / 100.0));
                    e.setCancelled(true);
                    le.addPotionEffect(new org.bukkit.potion.PotionEffect(type, newDur, e.getNewEffect().getAmplifier(), false, true, true));
                }
            }
        }
        // BELT: hexbuffer – negative effect duration reduction (curse resistance)
        if (le instanceof Player && e.getNewEffect() != null) {
            Player pBelt = (Player) le;
            org.bukkit.potion.PotionEffectType type = e.getNewEffect().getType();
            if (isNegativeEffect(type)) {
                double res = getAccessoryValue(pBelt, "BELT", "hexbuffer");
                if (res > 0) {
                    int dur = e.getNewEffect().getDuration();
                    int newDur = (int) Math.max(1, dur * Math.max(0.0, 1.0 - res / 100.0));
                    e.setCancelled(true);
                    le.addPotionEffect(new org.bukkit.potion.PotionEffect(type, newDur, e.getNewEffect().getAmplifier(), false, true, true));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!(e.getTarget() instanceof Player)) return;
        Player p = (Player) e.getTarget();
        ItemStack boots = p.getInventory().getItem(EquipmentSlot.FEET);
        JsonObject tag = readTag(boots);
        if (tag != null && "silent_steps".equals(optString(tag, "modifier_key"))) {
            double red = optDouble(tag, "value", 0.0) / 100.0;
            double dist = e.getEntity().getLocation().distance(p.getLocation());
            double base = 16.0;
            if (dist > base * (1.0 - red)) e.setCancelled(true);
        }
        // CLOAK: shadowmeld
        double cloakRed = getAccessoryValue(p, "CLOAK", "shadowmeld");
        if (cloakRed > 0) {
            double red = cloakRed / 100.0;
            double dist = e.getEntity().getLocation().distance(p.getLocation());
            double base = 16.0;
            if (dist > base * (1.0 - red)) e.setCancelled(true);
        }
        // ADORNMENT: veilweaver – extra camouflage strength
        double camo = getAccessoryValue(p, "ADORNMENT", "veilweaver");
        if (camo > 0) {
            double red = camo / 100.0;
            double dist = e.getEntity().getLocation().distance(p.getLocation());
            double base = 16.0;
            if (dist > base * (1.0 - red)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        ItemStack boots = p.getInventory().getItem(EquipmentSlot.FEET);
        JsonObject tag = readTag(boots);
        if (tag != null && "trailblazer".equals(optString(tag, "modifier_key"))) {
            long last = lastCombat.getOrDefault(p.getUniqueId(), 0L);
            if (System.currentTimeMillis() - last > 8000L) {
                double val = optDouble(tag, "value", 0.0);
                int amp = val >= 12.0 ? 1 : 0;
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 40, amp, false, true, true));
            }
        }
        // CLOAK: nightglide – OOC speed from accessory
        double night = getAccessoryValue(p, "CLOAK", "nightglide");
        if (night > 0) {
            long last = lastCombat.getOrDefault(p.getUniqueId(), 0L);
            if (System.currentTimeMillis() - last > 8000L) {
                int amp = night >= 12.0 ? 1 : 0;
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 40, amp, false, true, true));
            }
        }
        // LEGS: slipstream – boost in water
        ItemStack legs = p.getInventory().getItem(EquipmentSlot.LEGS);
        JsonObject legsTag = readTag(legs);
        if (legsTag != null && "slipstream".equals(optString(legsTag, "modifier_key"))) {
            if (p.isInWater() || p.isSwimming()) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE, 40, 0, false, true, true));
            }
        }
        if (tag != null && "ember_tracks".equals(optString(tag, "modifier_key"))) {
            long now = System.currentTimeMillis();
            long lt = lastTrail.getOrDefault(p.getUniqueId(), 0L);
            if (now - lt > 500L) {
                double chance = optDouble(tag, "value", 0.0);
                if (ThreadLocalRandom.current().nextDouble(100.0) <= chance) {
                    lastTrail.put(p.getUniqueId(), now);
                    var loc = p.getLocation();
                    var nearby = loc.getWorld().getNearbyLivingEntities(loc, 2.0, 1.0, 2.0, le -> !le.getUniqueId().equals(p.getUniqueId()));
                    for (LivingEntity le : nearby) {
                        le.setFireTicks(Math.max(le.getFireTicks(), 20));
                        LivingEntity target = le;
                        Bukkit.getScheduler().runTask(plugin, () -> target.damage(2.5, p)); // ~50 dmg/s scaled per tick
                    }
                }
            }
        }
        // RINGS: glacial_aura – small slow aura around player (2 blocks)
        double aura = Math.max(getAccessoryValue(p, "RING_1", "glacial_aura"), getAccessoryValue(p, "RING_2", "glacial_aura"));
        if (aura > 0) {
            var near = p.getWorld().getNearbyLivingEntities(p.getLocation(), 2.0, 1.5, 2.0, le -> !le.getUniqueId().equals(p.getUniqueId()));
            for (LivingEntity le : near) {
                le.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, 40, 0, false, true, true));
            }
        }
        // BELT: pack_mule – QoL pickup radius increase (attract items nearby)
        double pm = getAccessoryValue(p, "BELT", "pack_mule");
        if (pm > 0) {
            long now = System.currentTimeMillis();
            long last = lastPackTick.getOrDefault(p.getUniqueId(), 0L);
            if (now - last > 250L) {
                lastPackTick.put(p.getUniqueId(), now);
                double base = 1.5; // base pickup
                double radius = base * (1.0 + pm / 100.0);
                var items = p.getWorld().getNearbyEntities(p.getLocation(), radius, 1.0, radius, en -> en instanceof org.bukkit.entity.Item);
                for (var en : items) {
                    org.bukkit.entity.Item it = (org.bukkit.entity.Item) en;
                    it.setVelocity(p.getLocation().toVector().subtract(it.getLocation().toVector()).normalize().multiply(0.4));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        // ADORNMENT: spell_mender – grant absorption on ability-like use (right-click), with cooldown
        double sm = getAccessoryValue(p, "ADORNMENT", "spell_mender");
        if (sm <= 0) return;
        switch (e.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                long now = System.currentTimeMillis();
                long cdUntil = spellMenderCooldown.getOrDefault(p.getUniqueId(), 0L);
                if (now < cdUntil) return;
                // Apply absorption using MyExperience AbsorptionEffect if available; fallback to vanilla
                boolean applied = false;
                try {
                    Class<?> ae = Class.forName("com.maks.myexperienceplugin.alchemy.AbsorptionEffect");
                    java.lang.reflect.Constructor<?> ctor = ae.getConstructor(Player.class, double.class, long.class, long.class, String.class);
                    Object eff = ctor.newInstance(p, sm, 5000L, 0L, "Spell Mender");
                    ae.getMethod("apply").invoke(eff);
                    applied = true;
                } catch (Throwable ignored) {}
                if (!applied) {
                    double newAbs = Math.min(40.0, p.getAbsorptionAmount() + sm);
                    Bukkit.getScheduler().runTask(plugin, () -> p.setAbsorptionAmount(newAbs));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> p.setAbsorptionAmount(Math.max(0.0, p.getAbsorptionAmount() - sm)), 100L);
                }
                // 10s cooldown
                spellMenderCooldown.put(p.getUniqueId(), now + 10_000L);
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        Debuff d = severing.get(le.getUniqueId());
        if (d == null) return;
        if (System.currentTimeMillis() > d.until) { severing.remove(le.getUniqueId()); return; }
        double reduced = e.getAmount() * (1.0 - (d.value / 100.0));
        e.setAmount(Math.max(0.0, reduced));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { applyHeldEffects(p); applyEquipmentAttributes(p); });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTask(plugin, () -> { applyHeldEffects(e.getPlayer()); applyEquipmentAttributes(e.getPlayer()); }); }
    @EventHandler
    public void onQuit(PlayerQuitEvent e) { removeChrono(e.getPlayer()); removeEquipAttrs(e.getPlayer()); }

    @EventHandler
    public void onInventoryChange(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player) {
            Player p = (Player) e.getWhoClicked();
            Bukkit.getScheduler().runTask(plugin, () -> applyEquipmentAttributes(p));
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        // LEGS: battle_runner – speed after kill
        ItemStack legs = p.getInventory().getItem(EquipmentSlot.LEGS);
        JsonObject legsTag = readTag(legs);
        if (legsTag != null && "battle_runner".equals(optString(legsTag, "modifier_key"))) {
            double spd = optDouble(legsTag, "value", 0.0);
            int amp = spd >= 16.0 ? 1 : 0;
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 60, amp, false, true, true));
        }
        // BELT: second_wind – heal on kill (flat hp)
        double sw = getAccessoryValue(p, "BELT", "second_wind");
        if (sw > 0) {
            double newHp = Math.min(p.getMaxHealth(), p.getHealth() + sw);
            p.setHealth(newHp);
        }
    }

    private void applyHeldEffects(Player p) {
        ItemStack main = p.getInventory().getItem(EquipmentSlot.HAND);
        JsonObject tag = readTag(main);
        if (tag == null) { removeChrono(p); return; }
        String key = optString(tag, "modifier_key");
        double val = optDouble(tag, "value", 0.0);
        if ("chrono_edge".equals(key)) {
            // Attack speed % while holding
            double base = 4.0; // vanilla base
            double delta = base * (val / 100.0);
            applyChrono(p, delta);
        } else {
            removeChrono(p);
        }
    }

    private void applyChrono(Player p, double add) {
        AttributeInstance inst = p.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (inst == null) return;
        // Remove previous if exists
        removeChrono(p);
        AttributeModifier mod = new AttributeModifier(UUID.randomUUID(), "sci-chrono", add, AttributeModifier.Operation.ADD_NUMBER);
        inst.addModifier(mod);
        chronoApplied.put(p.getUniqueId(), mod);
    }

    private void removeChrono(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        AttributeModifier mod = chronoApplied.remove(p.getUniqueId());
        if (inst != null && mod != null) inst.removeModifier(mod);
    }

    private void applyEquipmentAttributes(Player p) {
        // Chest: iron_will – max health %
        ItemStack chest = p.getInventory().getItem(EquipmentSlot.CHEST);
        JsonObject chestTag = readTag(chest);
        AttributeInstance hp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        AttributeModifier oldHp = chestHP.remove(p.getUniqueId());
        if (hp != null && oldHp != null) hp.removeModifier(oldHp);
        if (hp != null && chestTag != null && "iron_will".equals(optString(chestTag, "modifier_key"))) {
            double pct = optDouble(chestTag, "value", 0.0) / 100.0;
            AttributeModifier mod = new AttributeModifier(UUID.randomUUID(), "sci-ironwill", pct, AttributeModifier.Operation.ADD_SCALAR);
            hp.addModifier(mod);
            chestHP.put(p.getUniqueId(), mod);
        }
        // Legs: striders_pace – movement speed %
        ItemStack legs = p.getInventory().getItem(EquipmentSlot.LEGS);
        JsonObject legsTag = readTag(legs);
        AttributeInstance ms = p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        AttributeModifier oldSp = legsSpeed.remove(p.getUniqueId());
        if (ms != null && oldSp != null) ms.removeModifier(oldSp);
        if (ms != null && legsTag != null && "striders_pace".equals(optString(legsTag, "modifier_key"))) {
            double pct = optDouble(legsTag, "value", 0.0) / 100.0;
            AttributeModifier mod = new AttributeModifier(UUID.randomUUID(), "sci-striders", pct, AttributeModifier.Operation.ADD_SCALAR);
            ms.addModifier(mod);
            legsSpeed.put(p.getUniqueId(), mod);
        }
        // Gloves via Trinkets: nimble – Haste effect (level based)
        double nim = getAccessoryValue(p, "GLOVES", "nimble");
        if (nim > 0) {
            int amp = (int)Math.max(0, Math.round(nim) - 1);
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FAST_DIGGING, 200, amp, false, true, true));
        }
    }

    private void removeEquipAttrs(Player p) {
        AttributeInstance hp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        AttributeModifier m1 = chestHP.remove(p.getUniqueId());
        if (hp != null && m1 != null) hp.removeModifier(m1);
        AttributeInstance ms = p.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        AttributeModifier m2 = legsSpeed.remove(p.getUniqueId());
        if (ms != null && m2 != null) ms.removeModifier(m2);
    }

    // Read a modifier value from a Trinkets accessory slot
    private double getAccessoryValue(Player p, String typeName, String matchKey) {
        try {
            Class<?> trinketsCls = Class.forName("com.maks.trinketsplugin.TrinketsPlugin");
            Object trInst = trinketsCls.getMethod("getInstance").invoke(null);
            Object dbm = trinketsCls.getMethod("getDatabaseManager").invoke(trInst);
            Object pdata = dbm.getClass().getMethod("getPlayerData", java.util.UUID.class).invoke(dbm, p.getUniqueId());
            if (pdata == null) return 0.0;
            Class<?> accType = Class.forName("com.maks.trinketsplugin.AccessoryType");
            Object enumConst = null;
            for (Object c : (Object[]) accType.getMethod("values").invoke(null)) {
                if (c.toString().equalsIgnoreCase(typeName)) { enumConst = c; break; }
            }
            if (enumConst == null) return 0.0;
            Object item = pdata.getClass().getMethod("getAccessory", accType).invoke(pdata, enumConst);
            if (!(item instanceof ItemStack is)) return 0.0;
            ItemMeta meta = is.getItemMeta();
            if (meta == null) return 0.0;
            String raw = meta.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "abyssal"), PersistentDataType.STRING);
            if (raw == null || raw.isEmpty()) return 0.0;
            JsonObject jo = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (!jo.has("modifier_key") || !jo.get("modifier_key").getAsString().equals(matchKey)) return 0.0;
            return jo.get("value").getAsDouble();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private String optString(JsonObject jo, String k) { return jo.has(k) ? jo.get(k).getAsString() : null; }
    private int optInt(JsonObject jo, String k, int d) { return jo.has(k) ? jo.get(k).getAsInt() : d; }
    private double optDouble(JsonObject jo, String k, double d) { return jo.has(k) ? jo.get(k).getAsDouble() : d; }

    private boolean isMythic(LivingEntity ent) {
        try {
            Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mythic.getMethod("inst").invoke(null);
            Object mobMgr = mythic.getMethod("getMobManager").invoke(inst);
            // getActiveMob(UUID) returns Optional/ActiveMob depending on API; try both
            try {
                java.lang.reflect.Method getActive = mobMgr.getClass().getMethod("getActiveMob", java.util.UUID.class);
                Object res = getActive.invoke(mobMgr, ent.getUniqueId());
                if (res != null) {
                    if (res instanceof java.util.Optional<?> opt) return opt.isPresent();
                    return true;
                }
            } catch (NoSuchMethodException ignored) {}
            // Fallback: isMythicMob(entity)
            try {
                java.lang.reflect.Method isMythic = mobMgr.getClass().getMethod("isMythicMob", org.bukkit.entity.Entity.class);
                Object res = isMythic.invoke(mobMgr, ent);
                if (res instanceof Boolean b) return b;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) { }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        Object shooter = e.getEntity().getShooter();
        if (!(shooter instanceof Player)) return;
        Player p = (Player) shooter;
        double speed = getAccessoryValue(p, "ADORNMENT", "aether_surge");
        if (speed > 0) {
            e.getEntity().setVelocity(e.getEntity().getVelocity().multiply(1.0 + speed/100.0));
        }
    }

    private static class Debuff {
        final long until; final double value;
        Debuff(long u, double v) { this.until = u; this.value = v; }
    }

    private void applySilence(Player target, int ticks) {
        try {
            Class<?> expCls = Class.forName("com.maks.myexperienceplugin.MyExperiencePlugin");
            Object expInst = expCls.getMethod("getInstance").invoke(null);
            Object seh = expCls.getMethod("getSkillEffectsHandler").invoke(expInst);
            Class<?> sehCls = seh.getClass();
            boolean ok = false;
            try { sehCls.getMethod("applySilence", Player.class, int.class).invoke(seh, target, ticks); ok = true; } catch (NoSuchMethodException ignored) {}
            if (!ok) try { sehCls.getMethod("setSilenced", Player.class, boolean.class, int.class).invoke(seh, target, true, ticks); ok = true; } catch (NoSuchMethodException ignored) {}
            if (!ok) try { sehCls.getMethod("addStatusEffect", Player.class, String.class, int.class).invoke(seh, target, "SILENCE", ticks); ok = true; } catch (NoSuchMethodException ignored) {}
            if (!ok) try { sehCls.getMethod("silencePlayer", Player.class, int.class).invoke(seh, target, ticks); } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) { }
    }

    private boolean areInSameParty(Player a, Player b) {
        try {
            Class<?> api = Class.forName("com.maks.myexperienceplugin.party.PartyAPI");
            return (boolean) api.getMethod("areInSameParty", Player.class, Player.class).invoke(null, a, b);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isNegativeEffect(PotionEffectType type) {
        if (type == null) return false;
        try {
            return type.getEffectCategory() == PotionEffectTypeCategory.HARMFUL;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
