package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Exposer;

import java.util.ArrayList;

import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.input.AimingReticle;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.objects.IsoTree;
import zombie.vehicles.BaseVehicle;

// Kahlua global registered by ZombieBuddy from @Exposer.LuaClass under
// the simple class name. Package paths are not resolvable from Lua.
@Exposer.LuaClass
public class PeekAViewMod {
    public static final PeekAViewMod instance = new PeekAViewMod();

    public static final int MIN_RANGE = 5;
    public static final int MAX_RANGE = 20;
    public static final int DEFAULT_RANGE = 10;

    public static final int TREE_FADE_VEHICLE_RANGE = 15;
    // On-foot base radius (normal trees), sized to the visible core
    // of vanilla's player mask (50%-dither boundary ~230px ≈ 5-6
    // tiles) plus a 6-tile margin. Bigger trees scale up via
    // onFootTreeFadeRange.
    public static final int TREE_FADE_ON_FOOT_RANGE = 11;
    // Exit hysteresis: already-faded trees keep zone membership up to
    // range+2, so boundary jitter doesn't flip renderFlag — each flip
    // reverses the fadeAlpha ramp mid-fade and reads as rim pulsing.
    public static final int TREE_FADE_EXIT_HYSTERESIS = 2;

    // Speed range for Patch_isTranslucentTree's fade boost. Below
    // MIN: pure vanilla alphaStep (no boost). Between MIN and CAP:
    // cubic ramp from no-boost to full-snap. At/above CAP: snap in
    // one call. Down-fade only; refades run on vanilla's ramp so
    // every tree restores with the same fade.
    public static final float TREE_FADE_SNAP_MIN_KMH = 10f;
    public static final float TREE_FADE_SNAP_SPEED_CAP_KMH = 50f;

    // volatile: render thread reads; Lua UI thread writes via setters.
    public static volatile boolean enabled = true;
    public static volatile boolean cutawayEnabled = true;
    public static volatile int range = DEFAULT_RANGE;
    // Opt-in: the workaround alters rendering of player-built tiles
    // at vanilla facades even for players the bug never hits.
    public static volatile boolean fixB42Adjacency = false;
    // All-or-nothing (replaces the prior km/h slider): false = no
    // cutaway in any vehicle.
    public static volatile boolean cutawayActiveInVehicle = true;
    public static volatile boolean aimStanceOnly = false;
    public static volatile boolean fadeNWTrees = true;
    public static volatile boolean treeFadeActiveOnFoot = true;
    public static volatile boolean stairEnabled = true;
    // Per-frame caches, written in refreshActiveCache. Speed is
    // |vehicle.currentSpeedKmHour|, 0f outside a vehicle.
    public static volatile float currentVehicleSpeedKmh = 0f;
    public static volatile boolean isVehicleReversing = false;
    public static volatile boolean currentCameraPlayerInVehicle = false;
    public static volatile boolean currentCameraPlayerAiming = false;
    public static volatile boolean aimReleasedThisFrame = false;
    public static volatile boolean currentCameraPlayerFacingSE = false;

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

    public static void setTreeFadeActiveOnFoot(boolean v) {
        treeFadeActiveOnFoot = v;
    }

    public static void setStairEnabled(boolean v) {
        stairEnabled = v;
    }

    // Once-per-JVM trace flags for first detection so console.txt shows a
    // clear signal that a gate engaged. Separate from the detection
    // result; the result itself is recomputed each call.
    private static volatile boolean externalStairFeatureTraceLogged = false;
    private static volatile boolean peekAViewPhantomTraceLogged = false;

    // Yield gate: upstream Workshop Staircast ("Staircast") or our
    // standalone read-path fork ("StaircastRP") owns the stair-render
    // path when active — running both stacks on the same camChar would
    // corrupt the field-mutation handshake. getModIDs() instead of
    // Class.forName: classes stay resolvable for the JVM lifetime
    // across world reloads, the mod list is per-save (see
    // docs/README.md).
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

    // Self-check against ZB advice persistence (ZombieBuddy#13): woven
    // advice outlives PeekAView's removal from the mod list within one
    // JVM, so saves loaded without PeekAView would keep all features
    // without this gate. Returns true on detection failure so
    // legitimate sessions never lose features when getModIDs throws.
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

    // Per-frame memo shared by both gates, keyed (frameCount,
    // playerIndex); refreshActiveCache fills both slots in one pass.
    // Public for the advice-inlining access context (docs/README.md).
    public static volatile int activeCacheFrameCount = Integer.MIN_VALUE;
    public static volatile int activeCachePlayerIndex = Integer.MIN_VALUE;
    public static volatile boolean activeCacheCutaway = false;
    public static volatile boolean activeCacheTreeFade = false;

    // Honors enabled, cutawayEnabled, aimStanceOnly and
    // cutawayActiveInVehicle. The B42-fix patches check flags
    // individually instead — they intentionally ignore aimStanceOnly.
    public static boolean isActiveCutawayForCurrentRenderPlayer() {
        refreshActiveCache();
        return activeCacheCutaway;
    }

    // Vehicle always; on foot only with treeFadeActiveOnFoot — off,
    // vanilla 42.20's own tree fade (SE quadrant + aim-gated
    // cursor/player masks) applies unchanged there. The section
    // toggle fadeNWTrees is checked in the patch bodies.
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
        // Memo key commits LAST (payload-before-key): a concurrent
        // caller mid-refresh recomputes redundantly instead of
        // reading a half-written cache.

        if (!enabled) {
            activeCacheCutaway = false;
            activeCacheTreeFade = false;
            camPlayerSnapshotValid = false;
            aimPointValid = false;
            activeCacheFrameCount = fCount;
            activeCachePlayerIndex = pIdx;
            return;
        }
        if (!isPeekAViewActive()) {
            activeCacheCutaway = false;
            activeCacheTreeFade = false;
            camPlayerSnapshotValid = false;
            aimPointValid = false;
            activeCacheFrameCount = fCount;
            activeCachePlayerIndex = pIdx;
            return;
        }
        if (pIdx < 0 || pIdx >= IsoPlayer.MAX) {
            activeCacheCutaway = true;
            activeCacheTreeFade = false;
            camPlayerSnapshotValid = false;
            aimPointValid = false;
            activeCacheFrameCount = fCount;
            activeCachePlayerIndex = pIdx;
            return;
        }
        IsoPlayer p = IsoPlayer.players[pIdx];
        if (p == null) {
            activeCacheCutaway = true;
            activeCacheTreeFade = false;
            camPlayerSnapshotValid = false;
            aimPointValid = false;
            activeCacheFrameCount = fCount;
            activeCachePlayerIndex = pIdx;
            return;
        }
        BaseVehicle vehicle = p.getVehicle();
        boolean inVehicle = vehicle != null;
        currentCameraPlayerInVehicle = inVehicle;
        // Same key check vanilla's isTranslucentTree aim gate uses.
        // Release edge drives the instant snap-back of aim fades.
        boolean wasAiming = currentCameraPlayerAiming;
        currentCameraPlayerAiming = p.isAnyAimKeyDown();
        aimReleasedThisFrame = wasAiming && !currentCameraPlayerAiming;
        // Reticle → world via the vanilla mouse pattern (reticle
        // coords are already offscreen-space: Mouse × zoom, gamepad
        // handled inside AimingReticle). Floor anchors the +3/level
        // iso shift; trees live at ground level, camZ covers the
        // driving and on-foot cases alike.
        boolean aimValid = false;
        if (currentCameraPlayerAiming) {
            try {
                int rx = AimingReticle.getX(pIdx);
                int ry = AimingReticle.getY(pIdx);
                float floor = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
                float ax = IsoUtils.XToIso(pIdx, rx, ry, floor);
                float ay = IsoUtils.YToIso(pIdx, rx, ry, floor);
                float ddx = ax - aimPointX;
                float ddy = ay - aimPointY;
                if (!aimPointValid || ddx * ddx + ddy * ddy > AIM_ANCHOR_DEADBAND_SQ) {
                    aimPointX = ax;
                    aimPointY = ay;
                    aimTileX = PZMath.fastfloor(ax);
                    aimTileY = PZMath.fastfloor(ay);
                }
                aimValid = true;
            } catch (Throwable t) {
                // leave invalid — aim reveal off beats everything-on
            }
        }
        aimPointValid = aimValid;
        // Facing gate for the SE gaze bonus: both forward components
        // clearly positive = looking toward the camera; the 0.2f
        // floor keeps pure-E/pure-S facings and axis wobble out.
        currentCameraPlayerFacingSE = p.getForwardDirectionX() > 0.2f
                && p.getForwardDirectionY() > 0.2f;
        float signedSpeed = inVehicle ? vehicle.getCurrentSpeedKmHour() : 0f;
        currentVehicleSpeedKmh = Math.abs(signedSpeed);
        // 1 km/h dead-zone so braking through 0 doesn't oscillate the
        // forward-direction flip; only sustained reverse triggers it.
        isVehicleReversing = signedSpeed < -1.0f;

        float fwdX = p.getForwardDirectionX();
        float fwdY = p.getForwardDirectionY();
        // Flip so the cone follows travel (see isTileInCameraPlayerCone).
        if (isVehicleReversing) {
            fwdX = -fwdX;
            fwdY = -fwdY;
        }
        camPlayerFwdX = fwdX;
        camPlayerFwdY = fwdY;
        camPlayerX = p.getX();
        camPlayerY = p.getY();
        camPlayerSnapshotValid = true;

        // Cap at 0.0: trait/state modifiers ≤ 0 pass through, the
        // vehicle's 1.0 (360°) must not widen the forward-cone gate.
        try {
            float vanillaCone = p.calculateVisibilityData().getCone();
            currentCameraPlayerConeDot = Math.min(vanillaCone, 0.0f);
            currentTreeFadeConeDot = inVehicle ? TREE_FADE_VEHICLE_CONE_DOT
                    : vanillaCone - TREE_FADE_ON_FOOT_CONE_TIGHTEN;
        } catch (Throwable t) {
            currentCameraPlayerConeDot = -0.2f;
            currentTreeFadeConeDot = -0.2f;
        }

        boolean aimBlocks = aimStanceOnly && !p.isAiming();
        if (!cutawayEnabled || aimBlocks) {
            activeCacheCutaway = false;
        } else if (inVehicle) {
            activeCacheCutaway = cutawayActiveInVehicle;
        } else {
            activeCacheCutaway = true;
        }
        activeCacheTreeFade = inVehicle || treeFadeActiveOnFoot;

        activeCacheFrameCount = fCount;
        activeCachePlayerIndex = pIdx;
    }

    // On-foot radius by tree size, measured to the base tile.
    // Mirrors the crown-reach table vanilla uses in
    // IsoTree.countObscuredSeenSquares (size→HGT): jumbo crowns
    // cover the player from far outside the base radius, so their
    // base must qualify from further out.
    // On foot the tree-fade cone is pulled in well below the ~162°
    // LOS cone — the LOS half-plane plus the facing-free SE band
    // plus the exit-ring hold otherwise add up to a plain radius
    // with no visible gaze direction. 0.3 → ~127° for the default
    // char.
    public static final float TREE_FADE_ON_FOOT_CONE_TIGHTEN = 0.3f;

    // Driving uses a fixed travel-direction cone (~162°, wider than
    // on foot — at speed the reveal should open earlier toward where
    // the car is going). Perpendicular trees neither enter nor sit
    // clearlyBehind, so passing a tree's axis column no longer pulses
    // its fade. Reversing flips the cone with the travel direction.
    public static final float TREE_FADE_VEHICLE_CONE_DOT = -0.2f;

    // SE corridor: a view corridor toward the camera. A tree
    // obstructs it when its crown top pokes above the corridor
    // bottom, i.e. baseDepth(dx+dy) <= viewDepth + crownSteps(size).
    // Adding the crown height per size (instead of per-size
    // Euclidean rings) keeps the reveal inversion-free: along one
    // sightline, if a far tree fades every nearer occluder fades
    // too. On foot the corridor opens while the char faces SE, with
    // this fixed depth; in a vehicle it is always open (the fade is
    // omnidirectional) with the slider circle's diagonal depth.
    public static final int TREE_FADE_GAZE_VIEW_DEPTH = 14;
    public static final int TREE_FADE_GAZE_EXTRA_HALF_WIDTH = 4;

    // Aim reveal bubble: while an aim key is held, tree reveal is
    // confined to the area around the reticle's world point instead
    // of the whole vision cone (on foot) or vanilla's all-direction
    // stencil bbox (in a vehicle — the moving bbox edge against our
    // stencil-less display and asymmetric fade rates is what made
    // trees pulse while driving with RMB held). 128 (1x px) matches
    // the visible core of vanilla's 512px cursor mask.
    public static final float AIM_BUBBLE_RADIUS_PX = 128f;
    // Screen-geometry steps: 16px per dx+dy, 32px per |dx-dy|.
    public static final int AIM_BUBBLE_UP_STEPS = (int) (AIM_BUBBLE_RADIUS_PX / 16f);
    public static final int AIM_BUBBLE_LATERAL_STEPS = (int) (AIM_BUBBLE_RADIUS_PX / 32f);

    // Reticle world point, cached per frame in refreshActiveCache.
    // The published anchor moves only when the raw sample leaves a
    // 0.75-tile deadband: the sample carries pixel jitter
    // (int-truncated camera offsets, mouse micro-motion) that would
    // otherwise dither the fastfloor'd tile across a boundary and
    // pulse every tree at the bubble edge.
    public static volatile boolean aimPointValid = false;
    private static volatile float aimPointX = 0f;
    private static volatile float aimPointY = 0f;
    public static volatile int aimTileX = 0;
    public static volatile int aimTileY = 0;
    private static final float AIM_ANCHOR_DEADBAND_SQ = 0.75f * 0.75f;

    // A tree covers the aim point when its sprite can overlap the
    // bubble: base down-screen up to crown reach (16px per dx+dy
    // step), laterally within crown half-width plus the bubble
    // radius. Same crown model as the SE corridor.
    public static boolean inAimCursorZone(int adx, int ady, int treeSize, boolean fading) {
        int hyst = fading ? TREE_FADE_EXIT_HYSTERESIS : 0;
        return adx + ady >= -AIM_BUBBLE_UP_STEPS - hyst
                && adx + ady <= AIM_BUBBLE_UP_STEPS + crownDepthSteps(treeSize) + hyst
                && Math.abs(adx - ady) <= AIM_BUBBLE_LATERAL_STEPS + nearOverlapHalfWidth(treeSize) + hyst;
    }

    // Crown heights in 16px diagonal steps, measured from the 1x
    // texture packs: normal e_ trees <=125px, JUMBO <=256px,
    // JUMBOXL <=373px, JUMBOXXL <=508px.
    public static int crownDepthSteps(int treeSize) {
        if (treeSize >= 8) return 32;
        if (treeSize == 7) return 24;
        if (treeSize >= 5) return 16;
        if (treeSize >= 3) return 8;
        return 4;
    }

    public static boolean inSeCorridor(int dx, int dy, int treeSize, boolean fading, int viewDepth) {
        int hyst = fading ? TREE_FADE_EXIT_HYSTERESIS : 0;
        return dx + dy <= viewDepth + crownDepthSteps(treeSize) + hyst
                && Math.abs(dx - dy) <= seFadeHalfWidth(treeSize)
                        + TREE_FADE_GAZE_EXTRA_HALF_WIDTH + hyst;
    }

    public static int onFootTreeFadeRange(int treeSize) {
        if (treeSize >= 8) return 22;
        if (treeSize == 7) return 18;
        if (treeSize >= 5) return 14;
        return TREE_FADE_ON_FOOT_RANGE;
    }

    // SE occlusion band for clamping vanilla-true on-foot fades. A
    // tree SE of the player covers the character while its base is
    // close enough down-screen (16px per dx+dy step; tall normal
    // sprites reach ~224px ⇒ 14 steps) and laterally aligned (32px
    // per |dx-dy| step; crown half-width plus the character's own
    // ~32px). Both axes carry a 6-step margin beyond strict vanilla
    // parity.
    public static int seFadeDepth(int treeSize) {
        if (treeSize >= 8) return 38;
        if (treeSize == 7) return 28;
        if (treeSize >= 5) return 24;
        if (treeSize >= 3) return 20;
        return 14;
    }

    public static int seFadeHalfWidth(int treeSize) {
        if (treeSize >= 8) return 16;
        if (treeSize == 7) return 13;
        if (treeSize >= 5) return 11;
        if (treeSize >= 3) return 9;
        return 8;
    }

    // IsoTree.cutawayAlpha is private; reflective write pins it to
    // its endpoints for the hard jumbo-fell flip (no ramp window).
    private static volatile java.lang.reflect.Field treeCutawayAlphaField;
    private static volatile boolean cutawayAlphaWriteFailedLogged = false;

    public static void writeTreeCutawayAlpha(IsoTree tree, float v) {
        try {
            java.lang.reflect.Field f = treeCutawayAlphaField;
            if (f == null) {
                f = IsoTree.class.getDeclaredField("cutawayAlpha");
                f.setAccessible(true);
                treeCutawayAlphaField = f;
            }
            f.setFloat(tree, v);
        } catch (Throwable t) {
            if (!cutawayAlphaWriteFailedLogged) {
                cutawayAlphaWriteFailedLogged = true;
                trace("cutawayAlpha reflective write failed — vanilla crown ramp stays", t);
            }
        }
    }

    // Display floor for rerouted (uniformly faded) trees: vanilla's
    // 0.15 state floor reads as invisible over green terrain. Only
    // the shown alpha is lifted; IsoTree.fadeAlpha keeps vanilla's
    // own floor. Trees whose sprite covers the char on screen drop
    // to the deep floor instead — the char must stay readable
    // through the crown.
    public static final float TREE_FADE_MIN_VISIBLE_ALPHA = 0.3f;
    public static final float TREE_FADE_COVER_MIN_VISIBLE_ALPHA = 0.15f;
    // Border depth (1x px) over which the two floors blend — walking
    // into cover eases down instead of stepping.
    public static final float TREE_FADE_COVER_BLEND_PX = 64f;

    // Near-field char cover, facing-free — our stand-in for
    // vanilla's 360° player mask: a tree base within a few steps
    // up-screen (its trunk/lower crown overlaps the char sprite) or
    // just below, laterally within crown+char width, covers the
    // character no matter where they look. Beats cone, clearlyBehind
    // and the NW rule.
    public static final int NEAR_OVERLAP_UP_STEPS = 6;
    public static final int NEAR_OVERLAP_DOWN_STEPS = 8;

    public static int nearOverlapHalfWidth(int treeSize) {
        return seFadeHalfWidth(treeSize) - TREE_FADE_GAZE_EXTRA_HALF_WIDTH - 2;
    }

    public static boolean inNearOverlap(int dx, int dy, int treeSize) {
        return dx + dy >= -NEAR_OVERLAP_UP_STEPS
                && dx + dy <= NEAR_OVERLAP_DOWN_STEPS
                && Math.abs(dx - dy) <= nearOverlapHalfWidth(treeSize);
    }

    // Outdoor-only gate for the cutaway extension, tree fade and the
    // B42 fix. Stair view intentionally does not consult it.
    public static boolean isCameraPlayerIndoor() {
        IsoGridSquare camSq = IsoCamera.frameState.camCharacterSquare;
        return camSq != null && camSq.isInARoom();
    }

    // Per-frame cache of the vanilla vision cone (fatigue, drunk,
    // panic, Eagle-Eyed, vehicle=1.0), capped at 0.0 in
    // refreshActiveCache.
    public static volatile float currentCameraPlayerConeDot = -0.2f;

    // Tree-fade cone: on foot the dynamic forward vision cone minus
    // the tighten offset (~127°); in a vehicle the fixed travel cone
    // (~162°).
    public static volatile float currentTreeFadeConeDot = -0.2f;

    // 0.05 keeps the cone boundary clear of axis-aligned tile
    // positions (dot exactly 0 / ±1) where float noise would flip the
    // gate per frame.
    private static final float TREE_FADE_CONE_STABILITY_BUFFER = 0.05f;

    // "Clearly behind" threshold (back ~140° cone). Geometric, not
    // vision-derived — Eagle-Eyed etc. don't change what's physically
    // behind the vehicle. Releases zone membership behind the car so
    // passed trees start their vanilla refade immediately instead of
    // holding via the exit ring.
    public static final float TREE_REFADE_BEHIND_THRESHOLD_DOT = 0.34f;

    // Per-frame camera-player snapshot for the tree-fade dot math:
    // world position as the cone apex, forward vector pre-flipped for
    // reverse travel. The stair path stays on live reads instead —
    // getAlpha runs on setup threads and inside fake windows, where
    // the live (possibly fake-mutated) position is the intended apex.
    public static volatile float camPlayerX = 0f;
    public static volatile float camPlayerY = 0f;
    public static volatile float camPlayerFwdX = 0f;
    public static volatile float camPlayerFwdY = 0f;
    public static volatile boolean camPlayerSnapshotValid = false;

    // Computed per-call instead of reading IsoGridSquare.isCanSee,
    // which lags during fast rotation (LOS updates on a periodic
    // pass) and pops nearby tiles in/out.
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
        // getForwardDirection keeps pointing at the vehicle's nominal
        // front while reversing; flip so the cone follows travel.
        if (isVehicleReversing) {
            fdx = -fdx;
            fdy = -fdy;
        }
        float dot = dx * fdx + dy * fdy;
        return dot < currentCameraPlayerConeDot + TREE_FADE_CONE_STABILITY_BUFFER;
    }

    // Normalized forward-dot from the camera player to the tile
    // center — one sqrt serves the clearly-behind and cone
    // thresholds. NEGATIVE_INFINITY on the player's own tile
    // (in-cone, never behind); NaN without a valid snapshot (every
    // threshold compare is false).
    public static float cameraPlayerDotTo(IsoGridSquare sq) {
        if (sq == null || !camPlayerSnapshotValid) return Float.NaN;
        float dx = camPlayerX - ((float) sq.x + 0.5f);
        float dy = camPlayerY - ((float) sq.y + 0.5f);
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 0.0001f) return Float.NEGATIVE_INFINITY;
        return (dx * camPlayerFwdX + dy * camPlayerFwdY) / (float) Math.sqrt(lenSq);
    }

    public static boolean isDotInTreeFadeCone(float dot) {
        return dot < currentTreeFadeConeDot + TREE_FADE_CONE_STABILITY_BUFFER;
    }

    public static boolean isDotClearlyBehind(float dot) {
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
