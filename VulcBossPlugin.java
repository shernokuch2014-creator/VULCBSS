package uz.vulcboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class VulcBossPlugin extends JavaPlugin implements Listener {

    public static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    public static final String WORLD_NAME = "vulcboss_world";

    private World world;
    private Location warpLoc;
    private VolcanoBoss boss;
    private final Set<String> done = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupWorld();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::clock, 40L, 20L);
        getLogger().info("VulcBoss yoqildi. Event vaqti: "
                + getConfig().getInt("event-hour", 19) + ":"
                + String.format("%02d", getConfig().getInt("event-minute", 0)) + " (Toshkent)");
    }

    @Override
    public void onDisable() {
        if (boss != null) boss.forceStop();
    }

    // ------------------------------------------------------------------ world

    private void setupWorld() {
        WorldCreator wc = new WorldCreator(WORLD_NAME);
        wc.generator(new ArenaGenerator());
        wc.environment(World.Environment.NORMAL);
        wc.generateStructures(false);
        world = wc.createWorld();
        if (world == null) throw new IllegalStateException("Dunyoni yaratib bo'lmadi");

        world.setTime(18000);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setStorm(true);
        world.setThundering(true);
        world.setSpawnLocation(0, 61, 45);
        warpLoc = new Location(world, 0.5, 61, 45.5, 180f, 0f);

        // Arena chunklari doim yuklangan tursin
        for (int cx = -5; cx <= 5; cx++)
            for (int cz = -5; cz <= 5; cz++)
                world.getChunkAt(cx, cz).addPluginChunkTicket(this);
    }

    public static class ArenaGenerator extends ChunkGenerator {
        @Override
        public void generateSurface(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
            int min = info.getMinHeight();
            data.setRegion(0, min, 0, 16, min + 1, 16, Material.BEDROCK);
            data.setRegion(0, min + 1, 0, 16, 57, 16, Material.DEEPSLATE);
            data.setRegion(0, 57, 0, 16, 60, 16, Material.BLACKSTONE);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int r = random.nextInt(100);
                    Material m = r < 6 ? Material.MAGMA_BLOCK
                            : r < 22 ? Material.BASALT
                            : r < 34 ? Material.SMOOTH_BASALT
                            : Material.BLACKSTONE;
                    data.setBlock(x, 60, z, m);
                }
            }
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
            return new BiomeProvider() {
                @Override
                public Biome getBiome(WorldInfo w, int x, int y, int z) {
                    return Biome.BASALT_DELTAS;
                }

                @Override
                public List<Biome> getBiomes(WorldInfo w) {
                    return List.of(Biome.BASALT_DELTAS);
                }
            };
        }
    }

    // ------------------------------------------------------------------ vaqt

    private void clock() {
        ZonedDateTime now = ZonedDateTime.now(TZ);
        LocalTime t = LocalTime.of(getConfig().getInt("event-hour", 19), getConfig().getInt("event-minute", 0));
        String day = now.toLocalDate().toString();

        check(now, t.minusMinutes(5), day + "-w5", () -> warn(5));
        check(now, t.minusMinutes(1), day + "-w1", () -> warn(1));
        check(now, t, day + "-go", this::startBoss);
    }

    private void check(ZonedDateTime now, LocalTime lt, String key, Runnable r) {
        if (now.getHour() == lt.getHour() && now.getMinute() == lt.getMinute() && done.add(key)) {
            r.run();
        }
    }

    private void warn(int min) {
        Bukkit.broadcast(Component.text("🔥 Vulkan Boss " + min + " daqiqadan keyin uyg'onadi! ", NamedTextColor.GOLD)
                .append(warpButton()));
    }

    private Component warpButton() {
        return Component.text("[/warp vulcboss]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/warp vulcboss"));
    }

    public void startBoss() {
        if (boss != null) return;
        boss = new VolcanoBoss(this, world);
        boss.start();
        Bukkit.broadcast(Component.text("🌋 VULKAN BOSS UYG'ONDI! ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(warpButton()));
    }

    public void bossEnded() {
        boss = null;
    }

    public void teleportToArena(Player p) {
        p.teleport(warpLoc);
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.7f);
        p.sendMessage(Component.text("Vulkan arenasiga xush kelibsiz!", NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ buyruqlar

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage().trim().toLowerCase();
        if (m.equals("/warp vulcboss") || m.equals("/essentials:warp vulcboss") || m.equals("/ewarp vulcboss")) {
            e.setCancelled(true);
            if (!e.getPlayer().hasPermission("vulcboss.warp")) {
                e.getPlayer().sendMessage(Component.text("Ruxsat yo'q.", NamedTextColor.RED));
                return;
            }
            teleportToArena(e.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "start" -> {
                if (boss != null) s.sendMessage("Boss allaqachon bor.");
                else startBoss();
            }
            case "stop" -> {
                if (boss != null) boss.forceStop();
                s.sendMessage("Boss to'xtatildi.");
            }
            case "warp" -> {
                if (s instanceof Player p) teleportToArena(p);
            }
            default -> s.sendMessage("/vulcboss <start|stop|warp>");
        }
        return true;
    }

    // ------------------------------------------------------------------ eventlar

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!e.getEntity().getScoreboardTags().contains("vb_hitbox")) return;
        e.setCancelled(true);
        if (boss == null || !(e instanceof EntityDamageByEntityEvent ev)) return;
        Entity d = ev.getDamager();
        Player p = null;
        if (d instanceof Player pl) p = pl;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof Player pl) p = pl;
        if (p != null) boss.damage(p, e.getDamage());
    }

    @EventHandler
    public void onLand(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;
        Set<String> tags = fb.getScoreboardTags();
        if (tags.contains("vb_meteor")) {
            e.setCancelled(true);
            Location l = fb.getLocation();
            fb.remove();
            if (boss != null) boss.meteorImpact(l);
        } else if (tags.contains("vb_fx")) {
            e.setCancelled(true);
            fb.remove();
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity().getScoreboardTags().contains("vb_fb")) e.blockList().clear();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld().equals(world) && !e.getPlayer().isOp()) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld().equals(world) && !e.getPlayer().isOp()) e.setCancelled(true);
    }
}
