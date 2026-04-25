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
    public static final int DEFAULT_RANGE = 15;

    public static final int MIN_TREE_FADE_RANGE = 5;
    public static final int MAX_TREE_FADE_RANGE = 25;
    public static final int DEFAULT_TREE_FADE_RANGE = 20;

    public static final int MAX_DRIVING_SPEED_CAP = 120;
    public static final int DEFAULT_DRIVING_SPEED_THRESHOLD = 35;
    public static final int DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD = 50;

    // volatile: render thread reads; Lua UI thread writes via setters.
    public static volatile boolean enabled = true;
    public static volatile int range = DEFAULT_RANGE;
    public static volatile int treeFadeRange = DEFAULT_TREE_FADE_RANGE;
    public static volatile boolean fixB42Adjacency = true;
    public static volatile int maxDrivingSpeedKmh = DEFAULT_DRIVING_SPEED_THRESHOLD;
    public static volatile int treeFadeMaxDrivingSpeedKmh = DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD;
    public static volatile boolean aimStanceOnly = false;
    public static volatile boolean fadeNWTrees = true;
    // Diagnostic trace rate-limit. Public so inlined patch advice can
    // read/write from the target class's access context.
    public static volatile long lastTraceMs = 0L;

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static void setRange(int v) {
        int clamped = v < MIN_RANGE ? MIN_RANGE : (v > MAX_RANGE ? MAX_RANGE : v);
        if (clamped == range) return;
        range = clamped;
        Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare.invalidateCache();
    }

    public static void setTreeFadeRange(int v) {
        int clamped = v < MIN_TREE_FADE_RANGE ? MIN_TREE_FADE_RANGE
                : (v > MAX_TREE_FADE_RANGE ? MAX_TREE_FADE_RANGE : v);
        if (clamped == treeFadeRange) return;
        treeFadeRange = clamped;
        Patch_FBORenderCell.Patch_calculateObjectsObscuringPlayer.invalidateCache();
    }

    public static void setFixB42Adjacency(boolean v) {
        fixB42Adjacency = v;
    }

    public static void setMaxDrivingSpeedKmh(int v) {
        int clamped = v < 0 ? 0 : (v > MAX_DRIVING_SPEED_CAP ? MAX_DRIVING_SPEED_CAP : v);
        maxDrivingSpeedKmh = clamped;
    }

    public static void setTreeFadeMaxDrivingSpeedKmh(int v) {
        int clamped = v < 0 ? 0 : (v > MAX_DRIVING_SPEED_CAP ? MAX_DRIVING_SPEED_CAP : v);
        treeFadeMaxDrivingSpeedKmh = clamped;
    }

    public static void setAimStanceOnly(boolean v) {
        aimStanceOnly = v;
    }

    public static void setFadeNWTrees(boolean v) {
        if (v == fadeNWTrees) return;
        fadeNWTrees = v;
        // Toggle-off-then-on across a range change would otherwise
        // serve a stale cache.
        Patch_FBORenderCell.Patch_calculateObjectsObscuringPlayer.invalidateCache();
    }

    // Per-frame memo for the two gates. Both gates share the same
    // cache key — `(frameCount, playerIndex)` — and refreshActiveCache
    // computes both result slots in one pass to avoid two recomputes.
    // Public/volatile/static for the @Patch advice-inlining access
    // context. Helper methods called from inlined advice must also be
    // public to avoid IllegalAccessError.
    public static volatile int activeCacheFrameCount = Integer.MIN_VALUE;
    public static volatile int activeCachePlayerIndex = Integer.MIN_VALUE;
    public static volatile boolean activeCacheCutaway = false;
    public static volatile boolean activeCacheTreeFade = false;

    // Cutaway-side gate (POI fan, cutawayVisit, shouldCutaway,
    // isAdjacentToOrphanStructure). Honors maxDrivingSpeedKmh.
    public static boolean isActiveCutawayForCurrentRenderPlayer() {
        refreshActiveCache();
        return activeCacheCutaway;
    }

    // Tree-fade gate (isTranslucentTree, calculateObjectsObscuringPlayer,
    // DrawStencilMask). Honors treeFadeMaxDrivingSpeedKmh.
    public static boolean isActiveTreeFadeForCurrentRenderPlayer() {
        refreshActiveCache();
        return activeCacheTreeFade;
    }

    public static void refreshActiveCache() {
        int pIdx = IsoCamera.frameState.playerIndex;
        int fCount = IsoCamera.frameState.frameCount;
        if (fCount == activeCacheFrameCount && pIdx == activeCachePlayerIndex) {
            return;
        }
        activeCacheFrameCount = fCount;
        activeCachePlayerIndex = pIdx;

        if (!enabled) {
            activeCacheCutaway = false;
            activeCacheTreeFade = false;
            return;
        }
        if (pIdx < 0 || pIdx >= IsoPlayer.MAX) {
            activeCacheCutaway = true;
            activeCacheTreeFade = true;
            return;
        }
        IsoPlayer p = IsoPlayer.players[pIdx];
        if (p == null) {
            activeCacheCutaway = true;
            activeCacheTreeFade = true;
            return;
        }
        if (aimStanceOnly && !p.isAiming()) {
            activeCacheCutaway = false;
            activeCacheTreeFade = false;
            return;
        }
        BaseVehicle vehicle = p.getVehicle();
        if (vehicle == null) {
            activeCacheCutaway = true;
            activeCacheTreeFade = true;
            return;
        }
        // In a vehicle: each gate has its own driving threshold. 0 = always off.
        float speed = Math.abs(vehicle.getCurrentSpeedKmHour());
        int cutawayT = maxDrivingSpeedKmh;
        activeCacheCutaway = cutawayT > 0 && speed < cutawayT;
        int treeFadeT = treeFadeMaxDrivingSpeedKmh;
        activeCacheTreeFade = treeFadeT > 0 && speed < treeFadeT;
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
