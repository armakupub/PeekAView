package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;

public class Patch_FBORenderTrees {

    // Vehicle tree-fade display path. 42.20 renders a fading tree
    // (useStencil) in two stencil-gated passes: opaque outside the
    // mask areas, faded plus outlined inside. Extending the mask to
    // the fade radius stamps a visibly oversized circle around the
    // car, so instead fading trees are rerouted onto vanilla's
    // `transparent` path (the XL-crown mechanism): drawn once,
    // uniformly at fadeAlpha, no stencil, no outline. cutawayAlpha
    // is set to fadeAlpha so renderTree's min() resolves to
    // fadeAlpha. bUseStencil is only true during the render thread's
    // translucent pass, so this never fires on chunk-texture bakes.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderTrees", methodName = "addTree")
    public static class Patch_addTree {

        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(value = 10, readOnly = false) boolean bUseStencil,
                @Patch.Argument(11) float fadeAlpha,
                @Patch.Argument(value = 12, readOnly = false) boolean transparent,
                @Patch.Argument(value = 13, readOnly = false) float cutawayAlpha) {
            try {
                if (!bUseStencil) return;
                if (transparent) return; // vanilla XL-crown call owns it
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                if (PeekAViewMod.isCameraPlayerIndoor()) return;
                bUseStencil = false;
                transparent = true;
                cutawayAlpha = fadeAlpha;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_addTree enter failed", t);
            }
        }
    }

    // Stair feature — restores real position during the tree pass so
    // trees render at their true world Z while the rest of the chunk
    // renders the upper floor.
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
                    savedCurrent = FakeWindow.readCurrentField(ffs.camChar);
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
                    // Re-mutate: flag BEFORE writeFakePos (see FakeWindow).
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
