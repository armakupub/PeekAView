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
    public static final int DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD = 100;

    // Speed at which Patch_isTranslucentTree's fade boost lerps fully
    // to minAlpha in one frame. Below the cap the lerp factor is
    // speed/cap. Constant, no UI.
    public static final float TREE_FADE_SNAP_SPEED_CAP_KMH = 30f;

    // volatile: render thread reads; Lua UI thread writes via setters.
    public static volatile boolean enabled = true;
    public static volatile int range = DEFAULT_RANGE;
    public static volatile int treeFadeRange = DEFAULT_TREE_FADE_RANGE;
    public static volatile boolean fixB42Adjacency = true;
    public static volatile int maxDrivingSpeedKmh = DEFAULT_DRIVING_SPEED_THRESHOLD;
    public static volatile int treeFadeMaxDrivingSpeedKmh = DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD;
    public static volatile boolean aimStanceOnly = false;
    public static volatile boolean fadeNWTrees = true;
    // When true, the aimStanceOnly gate does not block tree-fade
    // while the player is in a vehicle.
    public static volatile boolean treeFadeStayOnWhileDriving = false;
    // Same idea, but for foot. Default true: the most common reason
    // to enable aimStanceOnly is the wall-cutaway peek-around-the-
    // corner play style — tree-fade is unintrusive enough that most
    // users want it always active.
    public static volatile boolean treeFadeStayOnWhileOnFoot = true;
    // Per-frame |vehicle.currentSpeedKmHour|, written in
    // refreshActiveCache. Read by Patch_isTranslucentTree for the
    // speed-proportional fade boost. 0f outside a vehicle.
    public static volatile float currentVehicleSpeedKmh = 0f;
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

    public static void setTreeFadeStayOnWhileDriving(boolean v) {
        treeFadeStayOnWhileDriving = v;
    }

    public static void setTreeFadeStayOnWhileOnFoot(boolean v) {
        treeFadeStayOnWhileOnFoot = v;
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
        BaseVehicle vehicle = p.getVehicle();
        boolean inVehicle = vehicle != null;
        float speed = inVehicle ? Math.abs(vehicle.getCurrentSpeedKmHour()) : 0f;
        currentVehicleSpeedKmh = speed;

        // Aim-stance gate. Cutaway honors it strictly. Tree-fade has
        // two independent overrides: stay-on-while-driving for
        // vehicle context, stay-on-while-on-foot for non-vehicle. By
        // default the foot override is on, so tree-fade ignores
        // aim-stance unless the user explicitly tightens it.
        boolean aimBlocks = aimStanceOnly && !p.isAiming();
        boolean aimBlocksTreeFade = aimBlocks
                && !(treeFadeStayOnWhileDriving && inVehicle)
                && !(treeFadeStayOnWhileOnFoot && !inVehicle);

        if (aimBlocks) {
            activeCacheCutaway = false;
        } else if (inVehicle) {
            int cutawayT = maxDrivingSpeedKmh;
            activeCacheCutaway = cutawayT > 0 && speed < cutawayT;
        } else {
            activeCacheCutaway = true;
        }

        if (aimBlocksTreeFade) {
            activeCacheTreeFade = false;
        } else if (inVehicle) {
            int treeFadeT = treeFadeMaxDrivingSpeedKmh;
            activeCacheTreeFade = treeFadeT > 0 && speed < treeFadeT;
        } else {
            activeCacheTreeFade = true;
        }
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
