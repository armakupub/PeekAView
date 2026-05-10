package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Exposer;

import java.util.ArrayList;

import zombie.ZomboidFileSystem;
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

    public static final int MIN_TREE_FADE_RANGE = 15;
    public static final int MAX_TREE_FADE_RANGE = 25;
    public static final int DEFAULT_TREE_FADE_RANGE = 15;

    // Speed range for Patch_isTranslucentTree's fade boost. Below
    // MIN: pure vanilla alphaStep (no boost). Between MIN and CAP:
    // cubic ramp from no-boost to full-snap. At/above CAP: snap in
    // one call. Applied symmetrically: DOWN when in zone, UP when
    // clearly behind (refade behind moving vehicle).
    public static final float TREE_FADE_SNAP_MIN_KMH = 10f;
    public static final float TREE_FADE_SNAP_SPEED_CAP_KMH = 50f;

    // volatile: render thread reads; Lua UI thread writes via setters.
    public static volatile boolean enabled = true;
    public static volatile boolean cutawayEnabled = true;
    public static volatile int range = DEFAULT_RANGE;
    public static volatile int treeFadeRange = DEFAULT_TREE_FADE_RANGE;
    public static volatile boolean fixB42Adjacency = false;
    // When true (default), wall cutaway runs in vehicles regardless
    // of speed. When false, cutaway is off in any vehicle. Replaces
    // the prior km/h slider — all-or-nothing matches how users
    // actually use the feature in practice. Default on pairs with
    // the lower DEFAULT_RANGE = 10 since the smaller raster is
    // less visually noisy in vehicles.
    public static volatile boolean cutawayActiveInVehicle = true;
    public static volatile boolean aimStanceOnly = false;
    public static volatile boolean fadeNWTrees = true;
    public static volatile boolean stairEnabled = true;
    // Per-frame |vehicle.currentSpeedKmHour|, written in
    // refreshActiveCache. Read by Patch_isTranslucentTree for the
    // speed-proportional fade boost. 0f outside a vehicle.
    public static volatile float currentVehicleSpeedKmh = 0f;
    // True iff the vehicle is moving backwards faster than the
    // braking dead-zone. Used by isTileInCameraPlayerCone to flip
    // the forward direction so the cone follows direction-of-travel
    // when reversing.
    public static volatile boolean isVehicleReversing = false;

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static void setCutawayEnabled(boolean v) {
        cutawayEnabled = v;
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

    public static void setStairEnabled(boolean v) {
        stairEnabled = v;
    }

    // Once-per-JVM trace flags for first detection so console.txt shows a
    // clear signal that a gate engaged. Separate from the detection
    // result; the result itself is recomputed each call.
    private static volatile boolean externalStairFeatureTraceLogged = false;
    private static volatile boolean peekAViewPhantomTraceLogged = false;

    // Detection of an external stair-view feature in the active mod set:
    // upstream Workshop Staircast (id "Staircast" by copiumsawsed) or its
    // standalone read-path repo (id "StaircastRP", armakupub). Either one
    // owns the stair-view render path and PeekAView yields its stair
    // feature when one is present.
    //
    // mod.info already declares incompatible=StaircastRP, so the
    // StaircastRP match is a phantom-safety net rather than a normal-path
    // gate: a JVM session that activated StaircastRP earlier and then
    // loaded a save with PeekAView (without StaircastRP) leaves
    // StaircastRP's @Patch advice woven in via ZB's global registry. The
    // mod-list match here is live (per save), so once StaircastRP is no
    // longer active the gate releases.
    //
    // Probed before any FakeWindow mutation in Patch_IsoWorld.computeFake;
    // mutating x/y/z on a non-render thread without the read-path shadow
    // risks the updateFalling infinite-loop.
    //
    // Reads the live session's mod list from ZomboidFileSystem.
    // Class.forName looked tempting (one classloader hashmap lookup) but
    // a class loaded by a prior save's mod activation stays in the
    // classloader for the JVM lifetime even after that mod is disabled.
    // PZ shares one JVM across world reloads. getModIDs() returns the
    // list populated by the current save's loadMods() call, so it tracks
    // runtime mod activation correctly.
    public static boolean isExternalStairFeatureActive() {
        boolean detected = false;
        try {
            ArrayList<String> modIds = ZomboidFileSystem.instance.getModIDs();
            if (modIds != null) {
                for (int i = 0, n = modIds.size(); i < n; i++) {
                    String id = modIds.get(i);
                    if ("Staircast".equals(id) || "StaircastRP".equals(id)) {
                        detected = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            detected = false;
        }
        if (detected && !externalStairFeatureTraceLogged) {
            externalStairFeatureTraceLogged = true;
            trace("External stair feature detected — stair view yields");
        }
        return detected;
    }

    // Self-check against ZombieBuddy advice persistence (filed as
    // zed-0xff/ZombieBuddy#13): once ZB scans this mod's @Patch classes,
    // they stay in the global registry for the JVM lifetime even after
    // PeekAView is removed from the active mod set. Without this gate,
    // cutaway / tree-fade / stair view would keep firing in saves the
    // user explicitly loaded without PeekAView. Reading getModIDs() once
    // per refreshActiveCache (cutaway, tree-fade) and once per
    // computeFake call (stair view) is cheap; the linear scan over a
    // typical 20-50-mod list is negligible against the render cost it
    // gates. Returns true on detection failure so legitimate sessions
    // never lose features when getModIDs throws.
    public static boolean isPeekAViewActive() {
        boolean active = true;
        try {
            ArrayList<String> modIds = ZomboidFileSystem.instance.getModIDs();
            if (modIds != null) {
                active = false;
                for (int i = 0, n = modIds.size(); i < n; i++) {
                    if ("PeekAView".equals(modIds.get(i))) {
                        active = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            active = true;
        }
        if (!active && !peekAViewPhantomTraceLogged) {
            peekAViewPhantomTraceLogged = true;
            trace("PeekAView not in active mod set — patches yield (ZB advice persists across mod-list changes within the same JVM)");
        }
        return active;
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

    // Cutaway-side gate consumed by Patch_GetSquaresAroundPlayerSquare
    // and Patch_cutawayVisit. Honors enabled, cutawayEnabled,
    // aimStanceOnly, and cutawayActiveInVehicle. The B42-fix patches
    // (Patch_shouldCutaway, Patch_isAdjacentToOrphanStructure) check
    // those flags individually instead because they intentionally
    // ignore aimStanceOnly — the vanilla B42 bug exists at vanilla
    // cutaway range too, so the fix runs regardless of stance.
    public static boolean isActiveCutawayForCurrentRenderPlayer() {
        refreshActiveCache();
        return activeCacheCutaway;
    }

    // Tree-fade gate consumed by Patch_isTranslucentTree and
    // Patch_DrawStencilMask. Honors only `enabled`; the per-section
    // toggle `fadeNWTrees` is checked in the patch bodies, and
    // aim-stance / vehicle context don't gate tree-fade (the
    // per-frame cost is cheap and Patch_isTranslucentTree's
    // speed-snap handles fast-driving visuals).
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
        if (!isPeekAViewActive()) {
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
        // tile inside the cone gates. Two derived fields:
        //   currentCameraPlayerConeDot — capped at 0.0 (zombie cone,
        //     forward 180° hemisphere). Trait and state modifiers below
        //     0 (fatigue, panic, default, Eagle-Eyed at 0.0) pass
        //     through unchanged; vehicle 1.0 caps to 0.0.
        //   currentTreeFadeConeDot — uncapped (tree cone). Vehicle 1.0
        //     stays at 1.0 → 360° tree-fade. Avoids the flicker zone
        //     that opens between an artificially-narrowed cone and the
        //     clearlyBehind threshold where vanilla's SE-quadrant
        //     decision oscillates per frame.
        try {
            float vanillaCone = p.calculateVisibilityData().getCone();
            currentCameraPlayerConeDot = Math.min(vanillaCone, 0.0f);
            currentTreeFadeConeDot = vanillaCone;
        } catch (Throwable t) {
            currentCameraPlayerConeDot = -0.2f;
            currentTreeFadeConeDot = -0.2f;
        }

        // Cutaway: section enable, then aim-stance gate, then vehicle
        // binary toggle. Tree-fade: master toggle owns it; no
        // aim-stance, no vehicle gate (its own per-frame cost is cheap
        // and the speed-snap in Patch_isTranslucentTree handles fast-
        // driving visuals).
        boolean aimBlocks = aimStanceOnly && !p.isAiming();
        if (!cutawayEnabled || aimBlocks) {
            activeCacheCutaway = false;
        } else if (inVehicle) {
            activeCacheCutaway = cutawayActiveInVehicle;
        } else {
            activeCacheCutaway = true;
        }
        activeCacheTreeFade = true;
    }

    // True iff the rendering player's square is inside a room.
    // Outdoor-only gate consumed by:
    //   - Patch_GetSquaresAroundPlayerSquare (cutaway extension;
    //     indoor falls back to vanilla 5-tile raster)
    //   - Patch_DrawStencilMask, Patch_isTranslucentTree (tree-fade)
    //   - Patch_shouldCutaway, Patch_isAdjacentToOrphanStructure
    //     (B42 fix)
    // The stair view feature does NOT consult this — its gates run
    // inside Patch_IsoWorld.computeFake.
    public static boolean isCameraPlayerIndoor() {
        IsoGridSquare camSq = IsoCamera.frameState.camCharacterSquare;
        return camSq != null && camSq.isInARoom();
    }

    // Per-frame cache of the rendering player's vanilla cone (refreshed
    // in refreshActiveCache from IsoGameCharacter.calculateVisibilityData).
    // Picks up fatigue, drunk, panic, Eagle-Eyed and vehicle (cone=1.0).
    // Capped at 0.0 — used by zombie alpha gating (Patch_IsoObject) where
    // the forward-180° hemisphere is the right semantic.
    public static volatile float currentCameraPlayerConeDot = -0.2f;

    // Same source, no cap — used only by tree-fade (Patch_FBORenderCell).
    // In a vehicle vanilla returns 1.0 (360° awareness), and tree-fade
    // wants that full sweep so trees just past perpendicular don't fall
    // into a vanilla-owned zone where the SE-quadrant logic flickers
    // result between true/false. clearlyBehind (dot > 0.34) still carves
    // out the back-cone for refade-snap; the priority order in the patch
    // makes that the dominant classification.
    public static volatile float currentTreeFadeConeDot = -0.2f;

    // Small stability buffer added to the cached cone-dot when testing
    // tiles in-cone. 0.05 keeps the boundary clear of axis-aligned
    // tree positions (perpendicular dot=0, cardinals at ±1) where
    // float noise would otherwise flip the gate per frame. Walking
    // default char (cone-dot=-0.2) gets boundary at -0.15 ≈ vanilla
    // LOS cone (~163° vs vanilla's 157°). Vehicle (cone-dot=1.0,
    // uncapped) always passes dot < 1.05 — buffer is a no-op on the
    // 360° tree-fade.
    private static final float TREE_FADE_CONE_STABILITY_BUFFER = 0.05f;

    // Threshold for "clearly behind" tile classification, used by the
    // refade-snap path in Patch_isTranslucentTree. Geometric, not
    // character-state-derived: a tile with (tile→player)·forward
    // greater than this is in the back ~140° cone regardless of the
    // dynamic vision cone in front. Boundary partitions the uncapped
    // tree-fade cone into a 220° forward DOWN-fade zone and a 140°
    // back UP-refade zone — matches the user's perceived in-vehicle
    // visible LOS area, which is narrower than a full hemisphere
    // would suggest.
    public static final float TREE_REFADE_BEHIND_THRESHOLD_DOT = 0.34f;

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

    // Same dot math as isTileInCameraPlayerCone but with the uncapped
    // tree-fade cone. In a vehicle this returns true for the full 360°
    // sweep, eliminating the gap between the zombie cone (180°) and
    // clearlyBehind (140° back) where vanilla's SE-quadrant logic would
    // otherwise drive the result toggle that the user sees as flicker.
    public static boolean isTileInTreeFadeCone(IsoGridSquare sq) {
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
        if (isVehicleReversing) {
            fdx = -fdx;
            fdy = -fdy;
        }
        float dot = dx * fdx + dy * fdy;
        return dot < currentTreeFadeConeDot + TREE_FADE_CONE_STABILITY_BUFFER;
    }

    // True iff the tile is clearly behind the rendering player, i.e.
    // in the back ~140° cone relative to direction-of-travel. Uses a
    // fixed threshold (TREE_REFADE_BEHIND_THRESHOLD_DOT) instead of
    // mirroring currentCameraPlayerConeDot because "behind" is a
    // geometric property of motion, not vision capability — Eagle-Eyed
    // doesn't change what's physically behind the vehicle. Same dot
    // math as isTileInCameraPlayerCone but with the back-threshold.
    public static boolean isTileClearlyBehindCameraPlayer(IsoGridSquare sq) {
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
        if (lenSq < 0.0001f) return false;
        float invLen = 1.0f / (float) Math.sqrt(lenSq);
        dx *= invLen;
        dy *= invLen;

        float fdx = p.getForwardDirectionX();
        float fdy = p.getForwardDirectionY();
        if (isVehicleReversing) {
            fdx = -fdx;
            fdy = -fdy;
        }
        float dot = dx * fdx + dy * fdy;
        return dot > TREE_REFADE_BEHIND_THRESHOLD_DOT;
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
