package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.core.PerformanceSettings;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;

// Stair feature — non-FBO render path. Inverted pair of the outer
// FakeWindow swap: temporarily restores real position/square so the
// player sprite draws at its real spot while everything else renders
// the upper floor. Only active when fboRenderChunk is off.
public class Patch_IsoPlayer {

    @Patch(className = "zombie.characters.IsoPlayer", methodName = "render")
    public static class Patch_render {

        @Patch.OnEnter
        public static void enter(
                @Patch.This IsoPlayer self,
                @Patch.Argument(value = 0, readOnly = false) float x,
                @Patch.Argument(value = 1, readOnly = false) float y,
                @Patch.Argument(value = 2, readOnly = false) float z,
                @Patch.Local("paused") boolean paused,
                @Patch.Local("idx") int idx,
                @Patch.Local("saved") FakeFrameState saved,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent) {
            try {
                if (PerformanceSettings.fboRenderChunk) return;
                FakeFrameState ffs = FakeWindow.renderingFake.get();
                if (ffs == null || self != ffs.camChar) return;

                IsoCamera.FrameState fs = IsoCamera.frameState;
                idx = fs.playerIndex;
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                saved = ffs;
                paused = true;

                fs.camCharacterX = ffs.realPos.x;
                fs.camCharacterY = ffs.realPos.y;
                fs.camCharacterZ = ffs.realPos.z;
                fs.camCharacterSquare = ffs.realSquare;

                savedCurrent = self.getCurrentSquare();
                self.setCurrent(ffs.realSquare);
                FakeWindow.writeRealPos(self, ffs.realPos.x, ffs.realPos.y, ffs.realPos.z);
                FakeWindow.fieldMutated.set(idx, 0);

                x = ffs.realPos.x;
                y = ffs.realPos.y;
                z = ffs.realPos.z;

                FakeWindow.renderingFake.remove();
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoPlayer.render enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("paused") boolean paused,
                @Patch.Local("idx") int idx,
                @Patch.Local("saved") FakeFrameState saved,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent) {
            if (!paused) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;
                if (saved != null && saved.camChar != null && savedCurrent != null) {
                    saved.camChar.setCurrent(savedCurrent);
                    // Re-mutate: flag BEFORE writeFakePos. See
                    // FBORenderCell.Patch_renderInternal enter for rationale.
                    FakeWindow.fieldMutated.set(idx, 1);
                    if (!FakeWindow.writeFakePos(saved.camChar, saved.fakePos.x, saved.fakePos.y, saved.fakePos.z)) {
                        FakeWindow.fieldMutated.set(idx, 0);
                    }
                }
            } finally {
                if (saved != null) FakeWindow.renderingFake.set(saved);
            }
        }
    }
}
