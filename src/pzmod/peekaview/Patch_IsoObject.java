package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import zombie.characters.IsoZombie;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

// Stair feature — the fake render pass draws upper-floor zombies, but
// their per-player alpha comes from the game-thread LOS pass, which
// uses the real mid-stair position: upstairs squares never gain LOS,
// alpha stays 0, sprites stay invisible. Override getAlpha for
// IsoZombies on the fake floor inside the forward cone (zombies behind
// the camChar keep vanilla LOS fade). Gated on FakeWindow.isReady
// (frame-based) rather than the ThreadLocal — ModelSlotRenderData.init
// pulls alpha from a setup thread where the TL isn't set.
public class Patch_IsoObject {

    public static final int CLIMB_GRACE_FRAMES = 30;

    // True while the rendering player is mid-climb or in the grace
    // window after one. FakeWindow.get(pIdx) != null is unusable as
    // a gate — that slot is allocated once and never cleared, so it
    // would latch permanently after the first climb of a session.
    // Four indicators OR'd together: latch armed, recent strict
    // activation, recent frameCounter commit, or current square is
    // a stair / landing tile.
    public static boolean isClimbing(int pIdx) {
        FakeFrameState ffs = FakeWindow.get(pIdx);
        if (ffs == null) return camOnStairPath();
        if (ffs.stairLatchArmed) return true;
        int frame = IsoCamera.frameState.frameCount;
        if (ffs.lastStrictActivationFrame >= 0
                && frame - ffs.lastStrictActivationFrame <= CLIMB_GRACE_FRAMES) {
            return true;
        }
        if (ffs.frameCounter >= 0
                && frame - ffs.frameCounter <= CLIMB_GRACE_FRAMES) {
            return true;
        }
        return camOnStairPath();
    }

    public static boolean camOnStairPath() {
        IsoGridSquare camSq = IsoCamera.frameState.camCharacterSquare;
        if (camSq == null) return false;
        return camSq.HasStairs() || camSq.hasFloorAtTopOfStairs();
    }

    @Patch(className = "zombie.iso.IsoObject", methodName = "getAlpha")
    public static class Patch_getAlpha {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoObject self,
                                    @Patch.Argument(0) int playerIndex,
                                    @Patch.Local("v") float v) {
            if (!(self instanceof IsoZombie)) return false;
            int pIdx = IsoCamera.frameState.playerIndex;
            if (!FakeWindow.isReady(pIdx)) return false;
            FakeFrameState ffs = FakeWindow.get(pIdx);
            if (ffs == null || ffs.fakeSquare == null) return false;
            IsoGridSquare sq = self.square;
            if (sq == null || sq.z != ffs.fakeSquare.z) return false;
            // Landing-room gate: without it any upper-floor zombie in
            // the cone lights up, including neighbor rooms vanilla LOS
            // would never show (the climb cutaway opens the sightline).
            // Carve-outs: staircase-top tiles can report roomId -1, and
            // a zombie on the stairs / at the landing edge is the
            // warning case this feature exists for.
            if (sq.getRoomID() != ffs.landingRoomId
                    && !sq.HasStairsBelow()
                    && (Math.abs(sq.x - ffs.fakeSquare.x) > 1
                        || Math.abs(sq.y - ffs.fakeSquare.y) > 1)) {
                return false;
            }
            if (!PeekAViewMod.isTileInCameraPlayerCone(sq)) return false;
            // Also write the field: once the tile leaves the cone the
            // next getAlpha returns vanilla's cached value — 0 for
            // LOS-blocked upstairs squares, a hard visible→invisible
            // snap. Bumping the field each override frame lets the
            // game-thread updateAlpha decay it smoothly once we stop.
            // Float writes are atomic; render-presentation state only.
            self.setAlpha(pIdx, 1.0f);
            v = 1.0f;
            return true;
        }

        @Patch.OnExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Patch.Local("v") float v,
                                @Patch.Return(readOnly = false) float ret) {
            if (skipped) ret = v;
        }
    }
}
