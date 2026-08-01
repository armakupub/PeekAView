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

    // Fixed vehicle tree-fade radius (was a slider; fixed at its old
    // minimum). The fade is a complement to normal driving — holding
    // an aim key opens up more view via vanilla's cursor fade.
    public static final int TREE_FADE_RANGE = 15;

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
    // Opt-in: the workaround alters rendering of player-built tiles
    // at vanilla facades even for players the bug never hits.
    public static volatile boolean fixB42Adjacency = false;
    // All-or-nothing (replaces the prior km/h slider): false = no
    // cutaway in any vehicle.
    public static volatile boolean cutawayActiveInVehicle = true;
    public static volatile boolean aimStanceOnly = false;
    public static volatile boolean fadeNWTrees = true;
    public static volatile boolean stairEnabled = true;
    // Per-frame caches, written in refreshActiveCache. Speed is
    // |vehicle.currentSpeedKmHour|, 0f outside a vehicle.
    public static volatile float currentVehicleSpeedKmh = 0f;
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

    // Vehicle-only: on foot vanilla 42.20's own tree fade (SE quadrant
    // + aim-gated cursor/player masks) applies unchanged. The section
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
            activeCacheTreeFade = false;
            return;
        }
        IsoPlayer p = IsoPlayer.players[pIdx];
        if (p == null) {
            activeCacheCutaway = true;
            activeCacheTreeFade = false;
            return;
        }
        BaseVehicle vehicle = p.getVehicle();
        boolean inVehicle = vehicle != null;
        float signedSpeed = inVehicle ? vehicle.getCurrentSpeedKmHour() : 0f;
        currentVehicleSpeedKmh = Math.abs(signedSpeed);
        // 1 km/h dead-zone so braking through 0 doesn't oscillate the
        // forward-direction flip; only sustained reverse triggers it.
        isVehicleReversing = signedSpeed < -1.0f;

        // Cap at 0.0: trait/state modifiers ≤ 0 pass through, the
        // vehicle's 1.0 (360°) must not widen the forward-cone gate.
        try {
            float vanillaCone = p.calculateVisibilityData().getCone();
            currentCameraPlayerConeDot = Math.min(vanillaCone, 0.0f);
        } catch (Throwable t) {
            currentCameraPlayerConeDot = -0.2f;
        }

        boolean aimBlocks = aimStanceOnly && !p.isAiming();
        if (!cutawayEnabled || aimBlocks) {
            activeCacheCutaway = false;
        } else if (inVehicle) {
            activeCacheCutaway = cutawayActiveInVehicle;
        } else {
            activeCacheCutaway = true;
        }
        activeCacheTreeFade = inVehicle;
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

    // 0.05 keeps the cone boundary clear of axis-aligned tile
    // positions (dot exactly 0 / ±1) where float noise would flip the
    // gate per frame.
    private static final float TREE_FADE_CONE_STABILITY_BUFFER = 0.05f;

    // "Clearly behind" threshold (back ~140° cone). Geometric, not
    // vision-derived — Eagle-Eyed etc. don't change what's physically
    // behind the vehicle. Partitions the in-vehicle fade zone into
    // 220° forward DOWN-fade and 140° back UP-refade.
    public static final float TREE_REFADE_BEHIND_THRESHOLD_DOT = 0.34f;

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
