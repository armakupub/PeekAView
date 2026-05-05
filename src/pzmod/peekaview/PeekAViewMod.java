package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Exposer;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.vehicles.BaseVehicle;

// Kahlua global registered by ZombieBuddy from @Exposer.LuaClass under
// the simple class name. Package paths are not resolvable from Lua.
@Exposer.LuaClass
public class PeekAViewMod {
    public static final PeekAViewMod instance = new PeekAViewMod();

    public static final int MIN_RANGE = 5;
    public static final int MAX_RANGE = 20;
    public static final int DEFAULT_RANGE = 10;

    public static final int MIN_TREE_FADE_RANGE = 5;
    public static final int MAX_TREE_FADE_RANGE = 25;
    public static final int DEFAULT_TREE_FADE_RANGE = 20;

    // Speed range for Patch_isTranslucentTree's fade boost. Below
    // MIN: pure vanilla alphaStep (no boost). Between MIN and CAP:
    // cubic ramp from no-boost to full-snap. At/above CAP: snap in
    // one call.
    public static final float TREE_FADE_SNAP_MIN_KMH = 10f;
    public static final float TREE_FADE_SNAP_SPEED_CAP_KMH = 80f;

    // volatile: render thread reads; Lua UI thread writes via setters.
    public static volatile boolean enabled = true;
    public static volatile int range = DEFAULT_RANGE;
    public static volatile int treeFadeRange = DEFAULT_TREE_FADE_RANGE;
    public static volatile boolean fixB42Adjacency = true;
    // When true (default), wall cutaway runs in vehicles regardless
    // of speed. When false, cutaway is off in any vehicle. Replaces
    // the prior km/h slider — all-or-nothing matches how users
    // actually use the feature in practice. Default on pairs with
    // the lower DEFAULT_RANGE = 10 since the smaller raster is
    // less visually noisy in vehicles.
    public static volatile boolean cutawayActiveInVehicle = true;
    public static volatile boolean aimStanceOnly = false;
    public static volatile boolean fadeNWTrees = true;
    // Per-frame |vehicle.currentSpeedKmHour|, written in
    // refreshActiveCache. Read by Patch_isTranslucentTree for the
    // speed-proportional fade boost. 0f outside a vehicle.
    public static volatile float currentVehicleSpeedKmh = 0f;
    // True iff the vehicle is moving backwards faster than the
    // braking dead-zone. Used by isTileInCameraPlayerCone to flip
    // the forward direction so the cone follows direction-of-travel
    // when reversing.
    public static volatile boolean isVehicleReversing = false;
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
    }

    public static void setFixB42Adjacency(boolean v) {
        fixB42Adjacency = v;
    }

    public static void setCutawayActiveInVehicle(boolean v) {
        cutawayActiveInVehicle = v;
    }

    public static void setAimStanceOnly(boolean v) {
        aimStanceOnly = v;
    }

    public static void setFadeNWTrees(boolean v) {
        fadeNWTrees = v;
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
    // isAdjacentToOrphanStructure). Honors aimStanceOnly and
    // cutawayActiveInVehicle.
    public static boolean isActiveCutawayForCurrentRenderPlayer() {
        refreshActiveCache();
        return activeCacheCutaway;
    }

    // Tree-fade gate (isTranslucentTree, DrawStencilMask). Master-
    // toggle only — aim-stance and vehicle context don't gate it.
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
        float signedSpeed = inVehicle ? vehicle.getCurrentSpeedKmHour() : 0f;
        currentVehicleSpeedKmh = Math.abs(signedSpeed);
        // 1 km/h dead-zone so braking through 0 doesn't oscillate the
        // forward-direction flip; only sustained reverse triggers it.
        isVehicleReversing = signedSpeed < -1.0f;

        // One VisibilityData alloc per frame; avoids re-allocating per
        // tile inside the cone gate. Cap at 0.0 so vanilla's vehicle
        // override (cone = 1.0, effectively 360°) doesn't widen our
        // tree-fade gate beyond the forward 180° hemisphere — trait
        // and state modifiers below 0 (fatigue, panic, default,
        // Eagle-Eyed at 0.0) all pass through unchanged.
        try {
            currentCameraPlayerConeDot = Math.min(p.calculateVisibilityData().getCone(), 0.0f);
        } catch (Throwable t) {
            currentCameraPlayerConeDot = -0.2f;
        }

        // Cutaway: aim-stance gate, then vehicle binary toggle.
        // Tree-fade: master toggle owns it; no aim-stance, no
        // vehicle gate (its own per-frame cost is cheap and the
        // speed-snap in Patch_isTranslucentTree handles fast-driving
        // visuals).
        boolean aimBlocks = aimStanceOnly && !p.isAiming();
        if (aimBlocks) {
            activeCacheCutaway = false;
        } else if (inVehicle) {
            activeCacheCutaway = cutawayActiveInVehicle;
        } else {
            activeCacheCutaway = true;
        }
        activeCacheTreeFade = true;
    }

    // True iff the rendering player's square is inside a room.
    // Outdoor-only gate for both B42-fix patches and both tree-fade
    // patches.
    public static boolean isCameraPlayerIndoor() {
        IsoGridSquare camSq = IsoCamera.frameState.camCharacterSquare;
        return camSq != null && camSq.isInARoom();
    }

    // Per-frame cache of the rendering player's vanilla cone (refreshed
    // in refreshActiveCache from IsoGameCharacter.calculateVisibilityData).
    // Picks up fatigue, drunk, panic, Eagle-Eyed and vehicle (cone=1.0).
    public static volatile float currentCameraPlayerConeDot = -0.2f;

    // Buffer added to the cached cone when testing tile in-cone. A
    // strict forward cone has its boundary at perpendicular (dot == 0
    // when player_forward is axis-aligned), where float noise flips
    // the gate per frame and axis-aligned trees flicker. 0.25 clears
    // the boundary at the default cone (-0.2 + 0.25 = +0.05) and keeps
    // clearly-behind tiles out.
    private static final float TREE_FADE_CONE_STABILITY_BUFFER = 0.25f;

    // True iff the tile is in the rendering player's forward cone.
    // Computed per-frame instead of read from IsoGridSquare.isCanSee,
    // which lags during movement (PZ updates LOS on a periodic pass)
    // and would make close trees pop in/out as the player walked.
    // Patch_DrawStencilMask still uses isCanSee for its wall-blocking
    // property; this gate is just "is the player looking that way".
    public static boolean isTileInCameraPlayerCone(IsoGridSquare sq) {
        if (sq == null) return false;
        int pIdx = IsoCamera.frameState.playerIndex;
        if (pIdx < 0 || pIdx >= IsoPlayer.MAX) return false;
        IsoPlayer p = IsoPlayer.players[pIdx];
        if (p == null) return false;

        float tx = (float) sq.x + 0.5f;
        float ty = (float) sq.y + 0.5f;
        float dx = p.getX() - tx;
        float dy = p.getY() - ty;
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 0.0001f) return true;
        float invLen = 1.0f / (float) Math.sqrt(lenSq);
        dx *= invLen;
        dy *= invLen;

        float fdx = p.getForwardDirectionX();
        float fdy = p.getForwardDirectionY();
        // Vehicle reversing: getForwardDirection still points at the
        // vehicle's nominal front (where headlights face), but the
        // direction of travel is the opposite. Flip the cone so it
        // follows the actual movement when the player backs up.
        if (isVehicleReversing) {
            fdx = -fdx;
            fdy = -fdy;
        }
        float dot = dx * fdx + dy * fdy;
        return dot < currentCameraPlayerConeDot + TREE_FADE_CONE_STABILITY_BUFFER;
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
