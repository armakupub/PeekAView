package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;

// Stair feature — restores real position during the tree pass so trees
// render at their true world Z while the rest of the chunk renders the
// upper floor.
public class Patch_FBORenderTrees {

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderTrees", methodName = "init", warmUp = true)
    public static class Patch_init {

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("paused") boolean paused,
                @Patch.Local("idx") int idx,
                @Patch.Local("saved") FakeFrameState saved,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent) {
            try {
                FakeFrameState ffs = FakeWindow.renderingFake.get();
                if (ffs == null) return;

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

                if (ffs.camChar != null) {
                    savedCurrent = ffs.camChar.getCurrentSquare();
                    ffs.camChar.setCurrent(ffs.realSquare);
                    FakeWindow.writeRealPos(ffs.camChar, ffs.realPos.x, ffs.realPos.y, ffs.realPos.z);
                    FakeWindow.fieldMutated.set(idx, 0);
                }

                FakeWindow.renderingFake.remove();
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: FBORenderTrees.init enter failed", t);
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
