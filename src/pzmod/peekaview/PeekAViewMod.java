package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Exposer;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.vehicles.BaseVehicle;

// Kahlua global registered by ZombieBuddy from @Exposer.LuaClass under
// the simple class name. Package paths are not resolvable from Lua.
@Exposer.LuaClass
public class PeekAViewMod {
    public static final PeekAViewMod instance = new PeekAViewMod();

    public static final int MIN_RANGE = 5;
    public static final int MAX_RANGE = 20;
    public static final int DEFAULT_RANGE = MAX_RANGE;

    public static final int MAX_DRIVING_SPEED_CAP = 120;
    public static final int DEFAULT_DRIVING_SPEED_THRESHOLD = 35;

    // volatile: render thread reads; Lua UI thread writes via setters.
    public static volatile boolean enabled = true;
    public static volatile int range = DEFAULT_RANGE;
    public static volatile boolean fixB42Adjacency = true;
    public static volatile int maxDrivingSpeedKmh = DEFAULT_DRIVING_SPEED_THRESHOLD;

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static void setRange(int v) {
        int clamped = v < MIN_RANGE ? MIN_RANGE : (v > MAX_RANGE ? MAX_RANGE : v);
        if (clamped == range) return;
        range = clamped;
        Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare.invalidateCache();
    }

    public static void setFixB42Adjacency(boolean v) {
        fixB42Adjacency = v;
    }

    public static void setMaxDrivingSpeedKmh(int v) {
        int clamped = v < 0 ? 0 : (v > MAX_DRIVING_SPEED_CAP ? MAX_DRIVING_SPEED_CAP : v);
        maxDrivingSpeedKmh = clamped;
    }

    // Authoritative gate for every patch's enter/exit. Reads the currently
    // rendering player via IsoCamera.frameState.playerIndex (set per-pass
    // by the engine, distinct per split-screen player).
    public static boolean isActiveForCurrentRenderPlayer() {
        if (!enabled) return false;
        int pIdx = IsoCamera.frameState.playerIndex;
        if (pIdx < 0 || pIdx >= IsoPlayer.MAX) return true;
        IsoPlayer p = IsoPlayer.players[pIdx];
        if (p == null) return true;
        BaseVehicle vehicle = p.getVehicle();
        if (vehicle == null) return true;
        int threshold = maxDrivingSpeedKmh;
        if (threshold <= 0) return false;
        return Math.abs(vehicle.getCurrentSpeedKmHour()) < threshold;
    }

    public void init() {
        trace("PeekAView initialized");
    }

    public static void trace(String msg) {
        System.out.println("[PeekAView] " + msg);
    }

    public static void trace(String msg, Throwable t) {
        System.out.println("[PeekAView] " + msg);
        t.printStackTrace(System.out);
    }
}
