package uz.vulcboss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * "Vulkan Titani" - Minecraftda yo'q, o'zimiz yasagan ulkan boss.
 * Tanasi BlockDisplay'lardan yasalgan (animatsiyali qo'l/oyoqlar), hitbox esa ko'rinmas slime'lar.
 */
public class VolcanoBoss {

    private static final int GROUND = 61;

    private enum State { RISING, ACTIVE, DYING, DONE }

    private enum Attack { NONE, SLAM, FIREBALLS, ERUPTION, METEOR }

    private static class Part {
        BlockDisplay d;
        int type; // 0 statik, 1 chap qo'l, 2 o'ng qo'l, 3 chap oyoq, 4 o'ng oyoq
        Vector3f c, s, pivot;
    }

    private final VulcBossPlugin plugin;
    private final World world;
    private final Location center;
    private final Random rnd = new Random();

    private final List<Part> parts = new ArrayList<>();
    private final List<Slime> hitboxes = new ArrayList<>();
    private final Set<FallingBlock> meteors = new HashSet<>();
    private final Map<Block, BlockData> crater = new HashMap<>();
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Set<UUID> hitSet = new HashSet<>();
    private final List<Location> eruptSpots = new ArrayList<>();

    private BossBar bar;
    private BukkitTask task;
    private State state = State.RISING;
    private Attack attack = Attack.NONE;

    private Location pos;
    private float yaw = 180f;
    private double hp, maxHp;
    private int tick = 0, at = 0, cooldown = 60, dyingTick = 0;
    private boolean enraged = false;
    private float armAngle = 0f, armTarget = 0f;
    private double walkPhase = 0, legAmp = 0;
    private int lastStep = 0;
    private Vector jitter = new Vector();

    public VolcanoBoss(VulcBossPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.center = new Location(world, 0.5, GROUND, 0.5);
    }

    // ================================================================ start

    public void start() {
        int players = Math.max(1, world.getPlayers().size());
        maxHp = plugin.getConfig().getDouble("base-hp", 2000)
                + plugin.getConfig().getDouble("hp-per-extra-player", 500) * (players - 1);
        hp = maxHp;

        pos = center.clone().add(0, -7, 0);
        buildParts();

        hitboxes.add(spawnHitbox(pos.clone(), 12));
        hitboxes.add(spawnHitbox(pos.clone().add(0, 6.2, 0), 8));

        bar = BossBar.bossBar(Component.text("🌋 VULKAN TITANI", NamedTextColor.RED),
                0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        refreshBar();

        for (int i = 0; i < 3; i++) world.strikeLightningEffect(center);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    private Slime spawnHitbox(Location l, int size) {
        return world.spawn(l, Slime.class, s -> {
            s.setSize(size);
            s.setAI(false);
            s.setInvisible(true);
            s.setSilent(true);
            s.setPersistent(false);
            s.setRemoveWhenFarAway(false);
            s.setCollidable(false);
            s.addScoreboardTag("vb_hitbox");
        });
    }

    // ================================================================ model

    private void buildParts() {
        Material BS = Material.BLACKSTONE, OB = Material.OBSIDIAN, MG = Material.MAGMA_BLOCK;
        // oyoqlar
        add(3, BS, -1.6f, 1.5f, 0, 2, 3, 2, -1.6f, 3f, 0);
        add(4, BS, 1.6f, 1.5f, 0, 2, 3, 2, 1.6f, 3f, 0);
        // tos va tana
        add(0, Material.POLISHED_BLACKSTONE, 0, 3.4f, 0, 4.4f, 1, 2.6f, 0, 0, 0);
        add(0, BS, 0, 6.2f, 0, 5, 5, 3, 0, 0, 0);
        add(0, MG, 0, 6.2f, 1.4f, 2.8f, 2.8f, 0.4f, 0, 0, 0);       // ko'krakdagi lava yuragi
        add(0, MG, -1.5f, 8f, -1.8f, 0.8f, 2, 0.8f, 0, 0, 0);       // orqadagi vulkan teshiklari
        add(0, MG, 1.5f, 8f, -1.8f, 0.8f, 2, 0.8f, 0, 0, 0);
        add(0, OB, -3.2f, 8.6f, 0, 2, 1.2f, 2.4f, 0, 0, 0);         // yelka
        add(0, OB, 3.2f, 8.6f, 0, 2, 1.2f, 2.4f, 0, 0, 0);
        // bosh
        add(0, OB, 0, 10f, 0.2f, 2.8f, 2.6f, 2.8f, 0, 0, 0);
        add(0, Material.SHROOMLIGHT, -0.8f, 10.3f, 1.65f, 0.8f, 0.4f, 0.2f, 0, 0, 0);
        add(0, Material.SHROOMLIGHT, 0.8f, 10.3f, 1.65f, 0.8f, 0.4f, 0.2f, 0, 0, 0);
        add(0, MG, 0, 9.4f, 1.55f, 1.6f, 0.3f, 0.2f, 0, 0, 0);
        add(0, OB, -1.4f, 11.9f, 0.2f, 0.6f, 1.8f, 0.6f, 0, 0, 0);  // shoxlar
        add(0, OB, 1.4f, 11.9f, 0.2f, 0.6f, 1.8f, 0.6f, 0, 0, 0);
        // qo'llar (yelkadan aylanadi) + musht
        add(1, BS, -3.6f, 6.2f, 0, 1.8f, 5, 1.8f, -3.6f, 8.6f, 0);
        add(1, MG, -3.6f, 3.3f, 0, 2.4f, 1.8f, 2.4f, -3.6f, 8.6f, 0);
        add(2, BS, 3.6f, 6.2f, 0, 1.8f, 5, 1.8f, 3.6f, 8.6f, 0);
        add(2, MG, 3.6f, 3.3f, 0, 2.4f, 1.8f, 2.4f, 3.6f, 8.6f, 0);

        for (Part p : parts) applyTransform(p, 0f);
    }

    private void add(int type, Material m, float cx, float cy, float cz,
                     float sx, float sy, float sz, float px, float py, float pz) {
        Part p = new Part();
        p.type = type;
        p.c = new Vector3f(cx, cy, cz);
        p.s = new Vector3f(sx, sy, sz);
        p.pivot = new Vector3f(px, py, pz);
        Location l = pos.clone();
        l.setYaw(yaw);
        p.d = world.spawn(l, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setViewRange(4f);
            e.setTeleportDuration(2);
            e.setInterpolationDuration(2);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.addScoreboardTag("vb_fx");
        });
        parts.add(p);
    }

    /** Qismni pivot atrofida X o'qi bo'yicha burish (qo'l/oyoq animatsiyasi). */
    private void applyTransform(Part p, float angle) {
        Quaternionf rot = new Quaternionf().rotateX(angle);
        Vector3f rel = new Vector3f(p.c).sub(new Vector3f(p.s).mul(0.5f)).sub(p.pivot);
        rot.transform(rel);
        Vector3f t = new Vector3f(p.pivot).add(rel);
        p.d.setTransformation(new Transformation(t, rot, new Vector3f(p.s), new Quaternionf()));
        p.d.setInterpolationDelay(0);
    }

    private void syncParts() {
        Location base = pos.clone().add(jitter);
        base.setYaw(yaw);
        base.setPitch(0f);
        for (Part p : parts) p.d.teleport(base);
        if (hitboxes.size() == 2) {
            hitboxes.get(0).teleport(pos);
            hitboxes.get(1).teleport(pos.clone().add(0, 6.2, 0));
        }
    }

    // ================================================================ asosiy tsikl

    private void tick() {
        try {
            tick++;
            switch (state) {
                case RISING -> tickRise();
                case ACTIVE -> tickActive();
                case DYING -> tickDying();
                default -> { }
            }
            tickMeteors();
            if (state != State.DONE) syncParts();
            if (tick % 20 == 0) refreshBar();
        } catch (Exception ex) {
            plugin.getLogger().warning("Boss tick xatosi: " + ex);
        }
    }

    // ---------------------------------------------------------------- yer ostidan chiqish

    private void tickRise() {
        final int T = 120;
        double t = Math.min(1.0, tick / (double) T);
        double ease = 1 - Math.pow(1 - t, 3);
        pos.setY(GROUND - 7 + 7 * ease);

        if (tick <= 90) erupt(tick);
        if (tick % 8 == 0) world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        if (tick % 25 == 1) world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.5f);

        world.spawnParticle(Particle.LAVA, center, 8, 6, 1, 6, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, center, 6, 5, 2, 5, 0.02);
        ring(center.clone().add(0, 0.2, 0), 3 + t * 9, Particle.FLAME, 40);

        if (tick % 10 == 0) {
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distance(center) < 10) {
                    Vector away = p.getLocation().toVector().subtract(center.toVector()).setY(0);
                    if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                    p.setVelocity(away.normalize().multiply(1.1).setY(0.8));
                    p.damage(4);
                }
            }
        }

        if (tick >= T) {
            pos.setY(GROUND);
            state = State.ACTIVE;
            cooldown = 40;
            world.playSound(center, Sound.ENTITY_WARDEN_ROAR, 6f, 0.5f);
            world.playSound(center, Sound.ENTITY_WITHER_SPAWN, 6f, 0.6f);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0, 3, 0), 6, 4, 2, 4, 0);
            world.strikeLightningEffect(center);
            for (Player p : world.getPlayers()) {
                p.showTitle(Title.title(Component.text("VULKAN TITANI", NamedTextColor.RED),
                        Component.text("Uni mag'lub et!", NamedTextColor.GOLD)));
            }
        }
    }

    /** Yer ostidan bloklar otilib chiqishi (krater ochiladi). */
    private void erupt(int t) {
        double r = 2 + t * 0.13;
        for (int i = 0; i < 5; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double rr = Math.max(0, r - rnd.nextDouble() * 1.6);
            int x = (int) Math.floor(0.5 + Math.cos(ang) * rr);
            int z = (int) Math.floor(0.5 + Math.sin(ang) * rr);
            Block top = world.getBlockAt(x, GROUND - 1, z);
            if (rr <= 12 && !crater.containsKey(top)) {
                Block under = top.getRelative(0, -1, 0);
                crater.put(top, top.getBlockData());
                crater.put(under, under.getBlockData());
                BlockData fly = top.getBlockData();
                top.setType(Material.AIR, false);
                under.setType(Material.MAGMA_BLOCK, false);
                launch(new Location(world, x + 0.5, GROUND + 0.2, z + 0.5),
                        fly.getMaterial().isAir() ? Material.BLACKSTONE.createBlockData() : fly,
                        rd(0.9, 1.8));
                world.playSound(new Location(world, x, GROUND, z), Sound.BLOCK_LAVA_POP, 2f, 0.6f);
            }
        }
    }

    private void launch(Location l, BlockData data, double up) {
        FallingBlock fb = world.spawnFallingBlock(l, data);
        fb.setDropItem(false);
        fb.setHurtEntities(false);
        fb.addScoreboardTag("vb_fx");
        fb.setVelocity(new Vector(rd(-0.4, 0.4), up, rd(-0.4, 0.4)));
        Bukkit.getScheduler().runTaskLater(plugin, fb::remove, 120L);
        world.spawnParticle(Particle.LAVA, l, 3, 0.3, 0.3, 0.3, 0);
    }

    // ---------------------------------------------------------------- jang

    private void tickActive() {
        Player target = nearestTarget();

        if (!enraged && hp < maxHp * 0.5) enrage();
        if (tick > 20 * 60 * plugin.getConfig().getInt("time-limit-minutes", 20)) {
            for (Player p : world.getPlayers())
                p.sendMessage(Component.text("Vulkan Titani yer ostiga qaytib ketdi...", NamedTextColor.GRAY));
            forceStop();
            return;
        }

        boolean moving = false;
        pos.setY(GROUND);

        if (target != null) {
            Vector d = target.getLocation().toVector().subtract(pos.toVector()).setY(0);
            double dist = d.length();
            float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            float diff = wrap(want - yaw);
            float turn = attack == Attack.SLAM ? 2f : 6f;
            yaw += Math.max(-turn, Math.min(turn, diff));

            if (attack == Attack.NONE) {
                if (dist > 6) {
                    pos.add(d.normalize().multiply(enraged ? 0.2 : 0.13));
                    moving = true;
                }
                double fromCenter = Math.hypot(pos.getX() - 0.5, pos.getZ() - 0.5);
                if (fromCenter > 55) {
                    Vector back = new Vector(0.5 - pos.getX(), 0, 0.5 - pos.getZ()).normalize().multiply(0.3);
                    pos.add(back);
                }
                cooldown--;
                if (cooldown <= 0) chooseAttack(dist);
            }
        }

        if (attack != Attack.NONE) runAttack(target);
        updateLimbs(moving);
    }

    private void chooseAttack(double dist) {
        List<Attack> opts = new ArrayList<>();
        if (dist < 9) {
            opts.add(Attack.SLAM);
            opts.add(Attack.SLAM);
            opts.add(Attack.ERUPTION);
        } else {
            opts.add(Attack.FIREBALLS);
            opts.add(Attack.FIREBALLS);
            opts.add(Attack.ERUPTION);
        }
        if (enraged) {
            opts.add(Attack.METEOR);
            opts.add(Attack.METEOR);
            opts.add(Attack.SLAM);
        }
        attack = opts.get(rnd.nextInt(opts.size()));
        at = 0;
        hitSet.clear();
        eruptSpots.clear();
    }

    private void runAttack(Player target) {
        at++;
        switch (attack) {
            case SLAM -> {
                if (at == 1) {
                    armTarget = -2.7f;
                    world.playSound(pos, Sound.ENTITY_WARDEN_ROAR, 5f, 0.7f);
                }
                if (at < 26 && at % 2 == 0) ring(pos.clone().add(0, 0.2, 0), 9, Particle.FLAME, 24);
                if (at == 26) armTarget = 0.5f;
                if (at == 30) {
                    world.playSound(pos, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.5f);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, pos.clone().add(0, 1, 0), 3, 2, 0.5, 2, 0);
                }
                if (at >= 30 && at <= 44) shockwave(2 + (at - 30) * 0.85);
                if (at >= 50) endAttack(enraged ? 30 : 55);
            }
            case FIREBALLS -> {
                if (at == 1) armTarget = -1.5f;
                if (at >= 10 && at <= 50 && at % 8 == 0 && target != null) shootFireballs(target);
                if (at >= 64) endAttack(enraged ? 25 : 40);
            }
            case ERUPTION -> {
                if (at == 1) {
                    armTarget = -0.5f;
                    world.playSound(pos, Sound.ENTITY_ENDER_DRAGON_GROWL, 5f, 0.6f);
                    List<Player> ps = targets();
                    int count = enraged ? 6 : 3;
                    for (int i = 0; i < count && !ps.isEmpty(); i++) {
                        Player p = i == 0 && target != null ? target : ps.get(rnd.nextInt(ps.size()));
                        Location l = p.getLocation().clone();
                        l.setY(GROUND);
                        if (i > 0) l.add(rd(-3, 3), 0, rd(-3, 3));
                        eruptSpots.add(l);
                    }
                }
                if (at <= 40) {
                    for (Location l : eruptSpots) {
                        ring(l.clone().add(0, 0.2, 0), 3.0 - at * 0.02, Particle.FLAME, 20);
                        world.spawnParticle(Particle.LAVA, l, 1, 1, 0.2, 1, 0);
                    }
                    if (at % 10 == 0) world.playSound(pos, Sound.BLOCK_LAVA_POP, 3f, 0.5f);
                }
                if (at == 40) {
                    for (Location l : eruptSpots) geyser(l);
                }
                if (at >= 60) endAttack(enraged ? 25 : 35);
            }
            case METEOR -> {
                if (at == 1) {
                    armTarget = -2.4f;
                    world.playSound(pos, Sound.ENTITY_WARDEN_ROAR, 5f, 0.5f);
                    for (Player p : world.getPlayers())
                        p.sendMessage(Component.text("☄ Osmondan olov yog'ilmoqda!", NamedTextColor.GOLD));
                }
                if (at >= 10 && at <= 90 && at % 4 == 0) spawnMeteor();
                if (at >= 110) endAttack(40);
            }
            default -> { }
        }
    }

    private void endAttack(int cd) {
        attack = Attack.NONE;
        armTarget = 0f;
        cooldown = cd + rnd.nextInt(20);
    }

    // ---------------------------------------------------------------- hujumlar

    private void shockwave(double r) {
        ring(pos.clone().add(0, 0.3, 0), r, Particle.FLAME, (int) (r * 8));
        ring(pos.clone().add(0, 0.5, 0), r, Particle.LARGE_SMOKE, (int) (r * 3));
        int shards = (int) (r * 1.5);
        for (int i = 0; i < shards; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location l = new Location(world, pos.getX() + Math.cos(a) * r, GROUND + 0.2, pos.getZ() + Math.sin(a) * r);
            launch(l, rnd.nextBoolean() ? Material.BLACKSTONE.createBlockData() : Material.MAGMA_BLOCK.createBlockData(),
                    rd(0.4, 0.9));
        }
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location pl = p.getLocation();
            double dist = Math.hypot(pl.getX() - pos.getX(), pl.getZ() - pos.getZ());
            if (dist >= r - 1.6 && dist <= r + 1.6 && pl.getY() < GROUND + 2.2 && hitSet.add(p.getUniqueId())) {
                p.damage(enraged ? 16 : 12);
                Vector away = pl.toVector().subtract(pos.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                p.setVelocity(away.normalize().multiply(0.8).setY(1.0));
            }
        }
    }

    private void shootFireballs(Player target) {
        Vector fwd = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        Location head = pos.clone().add(0, 10, 0).add(fwd.clone().multiply(2));
        Vector dir = target.getEyeLocation().toVector().subtract(head.toVector()).normalize();
        int n = enraged ? 3 : 1;
        for (int i = 0; i < n; i++) {
            Vector v = dir.clone();
            if (n > 1) v.rotateAroundY((i - 1) * 0.22);
            Fireball f = world.spawn(head, Fireball.class, fb -> {
                fb.setShooter(hitboxes.get(1));
                fb.setYield(2.5f);
                fb.setIsIncendiary(false);
                fb.addScoreboardTag("vb_fb");
                fb.setDirection(v);
                fb.setVelocity(v.clone().multiply(1.1));
            });
        }
        world.playSound(head, Sound.ENTITY_BLAZE_SHOOT, 4f, 0.5f);
        world.spawnParticle(Particle.FLAME, head, 25, 0.5, 0.5, 0.5, 0.05);
    }

    private void geyser(Location l) {
        for (int i = 0; i < 16; i++) {
            launch(l.clone().add(rd(-1, 1), 0.3, rd(-1, 1)), Material.MAGMA_BLOCK.createBlockData(), rd(1.0, 1.8));
        }
        world.spawnParticle(Particle.EXPLOSION, l, 8, 1, 0.5, 1, 0);
        world.spawnParticle(Particle.LAVA, l.clone().add(0, 1, 0), 50, 1.2, 2, 1.2, 0);
        world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f);
        for (Player p : world.getPlayers()) {
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - l.getX(), pl.getZ() - l.getZ()) <= 3.2 && Math.abs(pl.getY() - l.getY()) < 4) {
                p.damage(14);
                p.setFireTicks(100);
                p.setVelocity(new Vector(0, 1.4, 0));
            }
        }
    }

    private void spawnMeteor() {
        List<Player> ps = targets();
        if (ps.isEmpty()) return;
        Player p = ps.get(rnd.nextInt(ps.size()));
        double x = p.getLocation().getX() + rd(-9, 9);
        double z = p.getLocation().getZ() + rd(-9, 9);
        ring(new Location(world, x, GROUND + 0.2, z), 2, Particle.FLAME, 20);

        FallingBlock fb = world.spawnFallingBlock(new Location(world, x, GROUND + 45, z),
                Material.MAGMA_BLOCK.createBlockData());
        fb.setDropItem(false);
        fb.setHurtEntities(false);
        fb.addScoreboardTag("vb_meteor");
        fb.setVelocity(new Vector(0, -0.6, 0));
        meteors.add(fb);
        Bukkit.getScheduler().runTaskLater(plugin, fb::remove, 200L);
    }

    private void tickMeteors() {
        Iterator<FallingBlock> it = meteors.iterator();
        while (it.hasNext()) {
            FallingBlock fb = it.next();
            if (!fb.isValid()) {
                it.remove();
                continue;
            }
            Location l = fb.getLocation();
            world.spawnParticle(Particle.FLAME, l, 8, 0.4, 0.4, 0.4, 0.02);
            world.spawnParticle(Particle.LARGE_SMOKE, l, 3, 0.3, 0.3, 0.3, 0.01);
        }
    }

    /** Listener tomonidan meteorit yerga tushganda chaqiriladi. */
    public void meteorImpact(Location l) {
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 2, 1, 0.5, 1, 0);
        world.spawnParticle(Particle.LAVA, l, 30, 2, 0.5, 2, 0);
        world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.8f);
        for (int i = 0; i < 6; i++) {
            launch(l.clone().add(0, 0.5, 0), Material.MAGMA_BLOCK.createBlockData(), rd(0.5, 1.1));
        }
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distance(l) <= 4.5) {
                p.damage(9);
                p.setFireTicks(60);
                p.setVelocity(new Vector(0, 0.7, 0));
            }
        }
    }

    private void enrage() {
        enraged = true;
        bar.color(BossBar.Color.PURPLE);
        world.strikeLightningEffect(pos);
        world.playSound(pos, Sound.ENTITY_WARDEN_ROAR, 8f, 0.4f);
        world.playSound(pos, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 8f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, pos.clone().add(0, 5, 0), 5, 3, 3, 3, 0);
        for (Player p : world.getPlayers()) {
            p.showTitle(Title.title(Component.text("G'AZABLANDI!", NamedTextColor.DARK_PURPLE),
                    Component.text("Meteoritlar yog'adi...", NamedTextColor.GOLD)));
        }
    }

    // ---------------------------------------------------------------- animatsiya

    private void updateLimbs(boolean moving) {
        float k = (attack == Attack.SLAM && at >= 26) ? 0.55f : 0.2f;
        armAngle += (armTarget - armAngle) * k;
        walkPhase += moving ? (enraged ? 0.3 : 0.22) : 0;
        legAmp += ((moving ? 0.55 : 0) - legAmp) * 0.2;

        double s = Math.sin(walkPhase);
        boolean idle = attack == Attack.NONE;
        for (Part p : parts) {
            switch (p.type) {
                case 1 -> applyTransform(p, (float) (armAngle + (idle ? -s * legAmp * 0.6 + Math.sin(tick * 0.07) * 0.05 : 0)));
                case 2 -> applyTransform(p, (float) (armAngle + (idle ? s * legAmp * 0.6 + Math.sin(tick * 0.07 + 1) * 0.05 : 0)));
                case 3 -> applyTransform(p, (float) (s * legAmp));
                case 4 -> applyTransform(p, (float) (-s * legAmp));
                default -> { }
            }
        }

        if (moving) {
            int step = (int) (walkPhase / Math.PI);
            if (step != lastStep) {
                lastStep = step;
                world.playSound(pos, Sound.ENTITY_IRON_GOLEM_STEP, 5f, 0.4f);
                world.spawnParticle(Particle.LARGE_SMOKE, pos, 8, 1.5, 0.2, 1.5, 0.01);
                world.spawnParticle(Particle.LAVA, pos, 3, 1.5, 0.2, 1.5, 0);
            }
        }
    }

    // ================================================================ zarar / o'lim

    public void damage(Player p, double amount) {
        if (state != State.ACTIVE) return;
        hp -= amount;
        damageDealt.merge(p.getUniqueId(), amount, Double::sum);
        Location hit = pos.clone().add(0, 5, 0);
        world.spawnParticle(Particle.BLOCK, hit, 12, 1.5, 2, 1.5, 0, Material.MAGMA_BLOCK.createBlockData());
        world.playSound(hit, Sound.ENTITY_IRON_GOLEM_DAMAGE, 2f, 0.5f);
        bar.progress((float) Math.max(0, Math.min(1, hp / maxHp)));
        if (hp <= 0) die();
    }

    private void die() {
        state = State.DYING;
        attack = Attack.NONE;
        armTarget = -1.2f;
        dyingTick = 0;
        for (FallingBlock fb : meteors) fb.remove();
        meteors.clear();
        bar.progress(0f);
        bar.name(Component.text("🌋 VULKAN TITANI qulayapti...", NamedTextColor.GOLD));
        world.playSound(pos, Sound.ENTITY_WITHER_SPAWN, 6f, 0.4f);
    }

    private void tickDying() {
        dyingTick++;
        jitter = new Vector(rd(-0.25, 0.25), 0, rd(-0.25, 0.25));
        armAngle += (armTarget - armAngle) * 0.1f;
        for (Part p : parts) {
            if (p.type == 1 || p.type == 2) applyTransform(p, armAngle);
        }

        if (dyingTick % 3 == 0) {
            Location l = pos.clone().add(rd(-3, 3), rd(1, 11), rd(-3, 3));
            world.spawnParticle(Particle.EXPLOSION, l, 2, 0.5, 0.5, 0.5, 0);
            world.spawnParticle(Particle.LAVA, l, 8, 1, 1, 1, 0);
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 3f, (float) rd(0.5, 1.2));
        }
        if (dyingTick % 12 == 0) {
            world.strikeLightningEffect(pos.clone().add(rd(-10, 10), 0, rd(-10, 10)));
        }
        if (dyingTick >= 120) finish();
    }

    private void finish() {
        state = State.DONE;
        Location c = pos.clone().add(0, 5, 0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 12, 4, 4, 4, 0);
        world.spawnParticle(Particle.LAVA, c, 150, 4, 5, 4, 0);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 10f, 0.5f);
        world.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 10f, 0.4f);
        for (int i = 0; i < 40; i++) {
            launch(c.clone(), Material.MAGMA_BLOCK.createBlockData(), rd(0.8, 2.0));
        }

        giveRewards();
        removeEntities();

        Bukkit.getScheduler().runTaskLater(plugin, this::restoreCrater, 20L * 30);
        plugin.bossEnded();
    }

    private void giveRewards() {
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(damageDealt.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(sorted.get(i).getKey());
            top.append("\n ").append(i + 1).append(". ").append(op.getName())
                    .append(" - ").append((int) (double) sorted.get(i).getValue()).append(" zarar");
        }
        Bukkit.broadcast(Component.text("🏆 VULKAN TITANI MAG'LUB ETILDI!" + top, NamedTextColor.GOLD));

        boolean first = true;
        List<String> cmds = plugin.getConfig().getStringList("reward-commands");
        for (Map.Entry<UUID, Double> e : sorted) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            List<ItemStack> items = new ArrayList<>();
            items.add(new ItemStack(Material.NETHERITE_INGOT, 2));
            items.add(new ItemStack(Material.DIAMOND, 16));
            items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
            if (first) items.add(new ItemStack(Material.NETHER_STAR, 1));
            for (ItemStack it : items) {
                for (ItemStack left : p.getInventory().addItem(it).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
            p.giveExp(1000);
            for (String cmd : cmds) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            p.sendMessage(Component.text("Mukofotlar berildi!", NamedTextColor.GREEN));
            first = false;
        }
    }

    // ================================================================ tozalash

    public void forceStop() {
        if (state == State.DONE && parts.isEmpty()) return;
        state = State.DONE;
        for (FallingBlock fb : meteors) fb.remove();
        meteors.clear();
        removeEntities();
        restoreCrater();
        plugin.bossEnded();
    }

    private void removeEntities() {
        if (task != null) task.cancel();
        for (Part p : parts) p.d.remove();
        parts.clear();
        for (Slime s : hitboxes) s.remove();
        hitboxes.clear();
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        }
    }

    private void restoreCrater() {
        for (Map.Entry<Block, BlockData> e : crater.entrySet()) e.getKey().setBlockData(e.getValue(), false);
        crater.clear();
    }

    // ================================================================ yordamchilar

    private void refreshBar() {
        if (bar == null) return;
        if (state == State.ACTIVE) bar.progress((float) Math.max(0, Math.min(1, hp / maxHp)));
        else if (state == State.RISING) bar.progress(1f);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(world)) p.showBossBar(bar);
            else p.hideBossBar(bar);
        }
    }

    private List<Player> targets() {
        List<Player> list = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead())
                list.add(p);
        }
        return list;
    }

    private Player nearestTarget() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : targets()) {
            double d = p.getLocation().distanceSquared(pos);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private void ring(Location c, double r, Particle particle, int points) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(particle, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r,
                    1, 0, 0, 0, 0);
        }
    }

    private double rd(double a, double b) {
        return a + rnd.nextDouble() * (b - a);
    }

    private static float wrap(float d) {
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }
}
