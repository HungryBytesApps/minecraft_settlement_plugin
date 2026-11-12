package settlement;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The fisher's real fishing look (needs the PacketEvents plugin): while it fishes, nearby players see a fake
 * player in its place (same spot, the fisher skin from fisher-skins in the config, no name, not in the tab
 * list) with a real fishing rod, line and bobber. The settler itself is hidden from them meanwhile. All of it
 * exists only on the players' screens: nothing is added to the world.
 */
final class FakeFisher {
    /** Players closer than this see it. */
    private static final double RANGE = 48;
    /** Metadata: the shown skin layers (avatar), and the bobber's bite. */
    private static final int SKIN_PARTS = 16, BITING = 9;

    /** One per fishing settler, kept between casts. */
    private static final Map<UUID, FakeFisher> active = new HashMap<>();

    /** The fake for this settler at its current spot: the one it has (if it hasn't moved), else a new one. */
    static FakeFisher get(Worker w) {
        FakeFisher f = active.get(w.body.getUniqueId());
        if (f != null && !f.removed && f.at.getWorld() == w.body.getWorld() && f.at.distanceSquared(w.body.getLocation()) < 0.3 * 0.3) {
            f.inUse = true;
            return f;
        }
        if (f != null) f.remove();
        f = new FakeFisher(w);
        active.put(w.body.getUniqueId(), f);
        return f;
    }

    /** Fake player entity id → its fake (read on the network thread). */
    private static final Map<Integer, FakeFisher> byId = new java.util.concurrent.ConcurrentHashMap<>();
    private static com.github.retrooper.packetevents.event.PacketListenerCommon listener;

    /** Right-clicking the fake works like right-clicking the settler. */
    static void listen(SettlementPlugin plugin) {
        listener = PacketEvents.getAPI().getEventManager().registerListener(new com.github.retrooper.packetevents.event.PacketListener() {
            @Override
            public void onPacketReceive(com.github.retrooper.packetevents.event.PacketReceiveEvent e) {
                if (e.getPacketType() != com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.INTERACT_ENTITY) return;
                com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity pk =
                        new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity(e);
                FakeFisher f = byId.get(pk.getEntityId());
                if (f == null || pk.getHand() != com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND) return;
                Object who = e.getPlayer();
                if (!(who instanceof Player p)) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline() && f.w.body.isValid()) WorkerListener.clickSettler(plugin, p, f.w);
                });
            }
        }, com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL);
    }

    static void unlisten() {
        if (listener != null) PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        listener = null;
    }

    /** Server stopping / plugin reload: every settler back for everyone. */
    static void removeAll() {
        for (FakeFisher f : new ArrayList<>(active.values())) f.remove();
        active.clear();
    }

    private final Worker w;
    private final int id = SpigotReflectionUtil.generateEntityId(), hookId = SpigotReflectionUtil.generateEntityId();
    private final UUID uuid = UUID.randomUUID();
    private final String name = "fisher" + Integer.toHexString(id & 0xFFFFF), team = "stl" + Integer.toHexString(id & 0xFFFFFF);
    private final Location at;
    private final Set<UUID> viewers = new HashSet<>();
    private float yaw, pitch;
    /** Where the bobber is (null: not out), and whether a fish bites. */
    private Vector3d hook;
    private boolean biting, removed, inUse = true;
    private int sinceUpdate;

    private FakeFisher(Worker w) {
        this.w = w;
        this.at = w.body.getLocation();
        this.yaw = at.getYaw();
        this.pitch = at.getPitch();
        byId.put(id, this);
        update();
    }

    // ---- Who sees it ----

    /** Ticks between telling a player about the fake (so its game downloads the skin) and showing it. */
    private static final int PRELOAD = 30;
    /** Players told about it, not shown yet: since when. */
    private final Map<UUID, Integer> pending = new HashMap<>();

    /** Call every tick while fishing: players coming close see it, players leaving get the settler back. */
    void tick() {
        if (++sinceUpdate >= 10) update();
        if (pending.isEmpty() || removed) return;
        int now = Bukkit.getCurrentTick();
        for (java.util.Iterator<Map.Entry<UUID, Integer>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> e = it.next();
            if (now - e.getValue() < PRELOAD && now >= e.getValue()) continue;
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            viewers.add(e.getKey());
            showTo(p);
        }
    }

    /** Everyone close by sees the fake now (the skin had time to load): it can cast. */
    boolean ready() {
        return pending.isEmpty();
    }

    private void update() {
        sinceUpdate = 0;
        if (removed) return;
        Set<UUID> now = new HashSet<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= RANGE * RANGE) now.add(p.getUniqueId());
        }
        for (UUID v : new ArrayList<>(viewers)) {
            if (now.contains(v)) continue;
            viewers.remove(v);
            Player p = Bukkit.getPlayer(v);
            if (p != null) hideFrom(p);
        }
        for (UUID v : new ArrayList<>(pending.keySet())) {
            if (now.contains(v)) continue;
            pending.remove(v);
            Player p = Bukkit.getPlayer(v);
            if (p != null) forget(p);
        }
        for (UUID v : now) {
            if (viewers.contains(v) || pending.containsKey(v)) continue;
            Player p = Bukkit.getPlayer(v);
            if (p == null) continue;
            pending.put(v, Bukkit.getCurrentTick());
            preload(p); // the settler stays visible until the skin is there
        }
    }

    /** Tell the player's game about the fake (name hidden, skin): it starts downloading the skin. Not shown yet. */
    private void preload(Player p) {
        send(p, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.CREATE,
                new WrapperPlayServerTeams.ScoreBoardTeamInfo(net.kyori.adventure.text.Component.empty(), null, null,
                        WrapperPlayServerTeams.NameTagVisibility.NEVER, WrapperPlayServerTeams.CollisionRule.NEVER,
                        net.kyori.adventure.text.format.NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE), name));
        send(p, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER),
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile(), false, 0, GameMode.SURVIVAL, null, null)));
    }

    /** Swap: the settler hidden, the fake shown in its place. */
    private void showTo(Player p) {
        p.hideEntity(w.plugin, w.body);
        send(p, new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.PLAYER, new Vector3d(at.getX(), at.getY(), at.getZ()),
                pitch, yaw, yaw, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(SKIN_PARTS, EntityDataTypes.BYTE, (byte) 0x7F)); // all skin layers (jacket, sleeves, hat...)
        send(p, new WrapperPlayServerEntityMetadata(id, data));
        List<Equipment> eq = new ArrayList<>();
        eq.add(new Equipment(EquipmentSlot.MAIN_HAND, SpigotConversionUtil.fromBukkitItemStack(new ItemStack(Material.FISHING_ROD))));
        send(p, new WrapperPlayServerEntityEquipment(id, eq));
        send(p, new WrapperPlayServerEntityHeadLook(id, yaw));
        if (hook != null) {
            send(p, spawnHook(hook, null));
            if (biting) send(p, bitingData(true));
        }
    }

    private void hideFrom(Player p) {
        send(p, new WrapperPlayServerDestroyEntities(hookId, id));
        forget(p);
        p.showEntity(w.plugin, w.body);
    }

    /** The player's game forgets the fake (name and skin entry). */
    private void forget(Player p) {
        send(p, new WrapperPlayServerPlayerInfoRemove(uuid));
        send(p, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));
    }

    /** The fisher skin (fisher-skins: <person>: value / signature); none set: a default skin. */
    private UserProfile profile() {
        List<TextureProperty> tex = new ArrayList<>();
        String person = SettlerSkins.person(w, w.plugin.settings());
        String[] skin = FisherSkins.get(w.plugin, person);
        if (skin != null && !skin[0].isBlank()) tex.add(new TextureProperty("textures", skin[0], skin[1].isBlank() ? null : skin[1]));
        return new UserProfile(uuid, name, tex);
    }

    // ---- What it does ----

    void look(Location target) {
        org.bukkit.util.Vector d = target.toVector().subtract(at.clone().add(0, 1.62, 0).toVector());
        if (d.lengthSquared() < 1e-6) return;
        yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        pitch = (float) Math.toDegrees(-Math.atan2(d.getY(), Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ())));
        all(() -> new WrapperPlayServerEntityRotation(id, yaw, pitch, true));
        all(() -> new WrapperPlayServerEntityHeadLook(id, yaw));
    }

    void swing() {
        // 26.3 has its own swing packet (the old animation packet no longer swings arms).
        all(() -> new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation(id,
                com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND,
                new com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation(
                        com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation.Type.WHACK, 6)));
    }

    /** Throw: the bobber flies from the hand towards {@code target} (the game moves it, like a player's). */
    void cast(Location target) {
        swing();
        double rad = Math.toRadians(yaw);
        Vector3d hand = new Vector3d(at.getX() - Math.sin(rad) * 0.3, at.getY() + 1.4, at.getZ() + Math.cos(rad) * 0.3);
        Vector3d to = new Vector3d(target.getX(), target.getY(), target.getZ());
        Vector3d vel = throwVelocity(hand, to);
        hook = to;
        biting = false;
        all(() -> spawnHook(hand, vel));
    }

    /** Landed: put it exactly where it should float. */
    void land() {
        if (hook == null) return;
        Vector3d h = hook;
        all(() -> new WrapperPlayServerEntityTeleport(hookId, h, 0f, 0f, false));
    }

    /** A bite: the bobber is pulled under. */
    void bite() {
        if (hook == null) return;
        biting = true;
        all(() -> bitingData(true));
    }

    /** Reel in: the bobber and line are gone. */
    void reel() {
        swing();
        hook = null;
        biting = false;
        all(() -> new WrapperPlayServerDestroyEntities(hookId));
    }

    /** Done for now: the fake stays a moment (the next cast may follow), unless the settler walks off. */
    void release() {
        inUse = false;
        if (!w.plugin.isEnabled()) { // shutting down
            remove();
            return;
        }
        reelQuietly();
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (inUse || removed) {
                    cancel();
                    return;
                }
                boolean moved = !w.body.isValid() || w.body.getWorld() != at.getWorld() || w.body.getLocation().distanceSquared(at) > 0.2 * 0.2;
                if (moved || ++t > 30) {
                    remove();
                    cancel();
                }
            }
        }.runTaskTimer(w.plugin, 1, 1);
    }

    private void reelQuietly() {
        if (hook == null) return;
        hook = null;
        biting = false;
        all(() -> new WrapperPlayServerDestroyEntities(hookId));
    }

    /** Gone: the settler is shown again to everyone who saw the fake. */
    void remove() {
        if (removed) return;
        removed = true;
        byId.remove(id);
        for (UUID v : viewers) {
            Player p = Bukkit.getPlayer(v);
            if (p != null) hideFrom(p);
        }
        for (UUID v : pending.keySet()) {
            Player p = Bukkit.getPlayer(v);
            if (p != null) forget(p);
        }
        viewers.clear();
        pending.clear();
        if (active.get(w.body.getUniqueId()) == this) active.remove(w.body.getUniqueId());
    }

    // ---- Packets ----

    private WrapperPlayServerSpawnEntity spawnHook(Vector3d pos, Vector3d velocity) {
        return new WrapperPlayServerSpawnEntity(hookId, Optional.of(UUID.randomUUID()), EntityTypes.FISHING_BOBBER, pos, 0f, 0f, 0f,
                id, Optional.ofNullable(velocity)); // data = the owner: the line runs to its hand
    }

    private WrapperPlayServerEntityMetadata bitingData(boolean on) {
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(BITING, EntityDataTypes.BOOLEAN, on));
        return new WrapperPlayServerEntityMetadata(hookId, data);
    }

    /** A new packet for every viewer (a packet object is used up when sent). */
    private void all(Supplier<PacketWrapper<?>> make) {
        for (UUID v : viewers) {
            Player p = Bukkit.getPlayer(v);
            if (p != null) send(p, make.get());
        }
    }

    private static int errorUntil;

    /** Send one packet; a packet that fails is skipped (logged at most once a minute), the rest goes on. */
    private static void send(Player p, PacketWrapper<?> packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, packet);
        } catch (RuntimeException ex) {
            int now = Bukkit.getCurrentTick();
            if (now >= errorUntil) {
                errorUntil = now + 1200;
                Bukkit.getLogger().warning("[Settlement] Fishing look: " + packet.getClass().getSimpleName() + " failed: " + ex.getMessage());
            }
        }
    }

    /** A throw that lands about at {@code to} (the bobber's flight: drag 0.92 a tick, gravity 0.03). */
    private static Vector3d throwVelocity(Vector3d from, Vector3d to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double dist = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
        double up = 0.2, best = 0.4, bestErr = Double.MAX_VALUE;
        for (double h = 0.05; h <= 1.6; h += 0.01) {
            double x = 0, y = from.getY(), vx = h, vy = up;
            for (int i = 0; i < 80; i++) {
                x += vx;
                y += vy;
                vx *= 0.92;
                vy = vy * 0.92 - 0.03;
                if (y <= to.getY()) break;
            }
            double err = Math.abs(x - dist);
            if (err < bestErr) {
                bestErr = err;
                best = h;
            }
        }
        return new Vector3d(dx / dist * best, up, dz / dist * best);
    }
}
