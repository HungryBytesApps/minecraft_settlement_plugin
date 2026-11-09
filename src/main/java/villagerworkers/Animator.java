package villagerworkers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Makes a settler look alive: the head turns smoothly (instead of snapping), glances around now and
 * then, nods, looks down; the settler crouches to work on the ground and really uses items
 * (eating, drawing a bow, blowing a horn). The body follows the head by itself on the client.
 */
final class Animator {
    private final Worker w;
    private float yaw, pitch;               // current head rotation
    private final Deque<float[]> targets = new ArrayDeque<>(); // {yaw, pitch} to turn to, in order
    private int idleAt, holdUntil, crouchUntil = -1, useUntil = -1;

    Animator(Worker w) {
        this.w = w;
        Location l = w.body.getLocation();
        yaw = l.getYaw();
        pitch = l.getPitch();
        idleAt = Bukkit.getCurrentTick() + 40 + w.random.nextInt(60);
    }

    // ---- Requests ----

    /** Turn the head (smoothly) to look at a spot. */
    void look(Location at) {
        Location eye = w.body.getEyeLocation();
        double dx = at.getX() - eye.getX(), dy = at.getY() - eye.getY(), dz = at.getZ() - eye.getZ();
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        turn(ty, clamp(tp, -60, 60), 40);
    }

    /** Look down (reading, working with the hands). */
    void lookDown(float down) {
        turn(yaw, down, 30);
    }

    /** A quick look to the side and maybe up or down. */
    void glance() {
        if (w.watched()) return;
        turn(yaw + w.random.nextInt(100) - 50, w.random.nextInt(35) - 15, 20);
    }

    /** A little nod (yes, mhm). */
    void nod() {
        targets.clear();
        targets.add(new float[]{yaw, clamp(pitch + 20, -60, 60)});
        targets.add(new float[]{yaw, pitch});
        holdUntil = now() + 30;
    }

    /** Crouch for a moment (harvesting, planting). */
    void crouch(int ticks) {
        try {
            w.body.setPose(Pose.SNEAKING, true);
            crouchUntil = now() + ticks;
        } catch (RuntimeException ignored) {
            // pose not supported: just don't crouch
        }
    }

    /** Use the item in hand (eat, draw a bow, blow a horn) for a while. */
    void use(int ticks) {
        try {
            w.body.startUsingItem(EquipmentSlot.HAND);
            useUntil = ticks <= 0 ? -1 : now() + ticks;
        } catch (RuntimeException ignored) {
            // can't use this item: no animation
        }
    }

    void stopUsing() {
        w.body.clearActiveItem();
        useUntil = -1;
    }

    private void turn(float ty, float tp, int hold) {
        targets.clear();
        targets.add(new float[]{ty, tp});
        holdUntil = now() + hold;
    }

    // ---- Every tick ----

    void tick() {
        int now = now();
        if (crouchUntil >= 0 && now >= crouchUntil) {
            crouchUntil = -1;
            try {
                w.body.setPose(Pose.STANDING, false);
            } catch (RuntimeException ignored) {
            }
        }
        if (useUntil >= 0 && now >= useUntil) stopUsing();

        if (w.mover.moving()) {
            // The walk sets the rotation: follow it, no head games.
            Location l = w.body.getLocation();
            yaw = l.getYaw();
            pitch = l.getPitch();
            targets.clear();
            idleAt = now + 20 + w.random.nextInt(40);
            return;
        }
        // Idle: now and then look somewhere else, or back ahead.
        if (targets.isEmpty() && now >= idleAt && now >= holdUntil && !w.watched()) {
            idleAt = now + 50 + w.random.nextInt(110);
            int r = w.random.nextInt(10);
            if (r < 5) glance();
            else if (r < 8) turn(yaw + w.random.nextInt(30) - 15, w.random.nextInt(16) - 4, 0); // small shift
            else turn(w.body.getBodyYaw(), 0, 0); // straight ahead again
        }
        float[] t = targets.peek();
        if (t == null) {
            Location l = w.body.getLocation(); // someone else turned us (sitting down): follow
            yaw = l.getYaw();
            pitch = l.getPitch();
            return;
        }
        float dy = wrap(t[0] - yaw), dp = t[1] - pitch;
        // Ease: fast at first, slow at the end.
        float sy = Math.max(-12f, Math.min(12f, dy * 0.35f)), sp = Math.max(-8f, Math.min(8f, dp * 0.35f));
        if (Math.abs(dy) < 1.5f) sy = dy;
        if (Math.abs(dp) < 1.5f) sp = dp;
        yaw += sy;
        pitch += sp;
        if (sy == dy && sp == dp) targets.poll();
        w.body.setRotation(yaw, pitch);
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int now() {
        return Bukkit.getCurrentTick();
    }
}
