package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import zombie.characters.IsoZombie;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

// Stair feature — render-time alpha override for zombies on the
// upper floor while a fake-window render pass is active.
//
// Background: the stair-feature fake-window (Patch_IsoWorld.computeFake
// + outer FBORenderCell/IsoCell render-pass swap) lifts the camera to
// the upper floor so its geometry renders. Zombies on that floor are
// drawn by the same render pass, but their per-frame visibility alpha
// is updated on the game thread from the LOS pass — which uses the
// real player position (mid-stair, no LOS to upper-floor squares more
// than ~1 tile from the stair top). targetAlpha never rises for those
// squares, so the zombie sprite stays invisible even though it would
// be in the rendered region.
//
// This patch overrides getAlpha(int) for IsoZombies on the upper
// floor while the fake window is active. We gate on FakeWindow.isReady
// rather than the renderingFake ThreadLocal because PZ may pull alpha
// from a setup thread (ModelSlotRenderData.init) where the ThreadLocal
// isn't set. isReady is frame-based — true on every thread for the
// duration of a frame where computeFake committed.
//
// Cone gate: using PeekAViewMod.isTileInCameraPlayerCone, only zombies
// in the player's forward view become visible. Zombies behind the
// camChar fade per vanilla LOS — restores the "see what your character
// sees" theme on the upper floor and gives the user the
// turn-around-and-things-fade behavior they asked for.
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
            if (!PeekAViewMod.isTileInCameraPlayerCone(sq)) return false;
            // Also write the per-player alpha field. Reason: when the
            // zombie's tile leaves the forward cone (player turns,
            // animation head-bob, stair completion), the next call to
            // getAlpha returns whatever vanilla had cached. With
            // LOS-blocked upstairs squares that's 0, producing a hard
            // snap from visible to invisible. By bumping the field to
            // 1.0 each frame we're in cone, vanilla's game-thread
            // updateAlpha keeps animating it (decaying toward whatever
            // its targetAlpha is) — once we stop writing, the field
            // drifts down smoothly instead of being read at 0. Float
            // writes are atomic on all reasonable JVMs and the field
            // is purely render-presentation, no gameplay state.
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
