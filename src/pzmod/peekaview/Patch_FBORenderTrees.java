package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.Core;
import zombie.core.textures.Texture;
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
                @Patch.Argument(0) Texture texture,
                @Patch.Argument(2) float x,
                @Patch.Argument(3) float y,
                @Patch.Argument(value = 10, readOnly = false) boolean bUseStencil,
                @Patch.Argument(value = 11, readOnly = false) float fadeAlpha,
                @Patch.Argument(value = 12, readOnly = false) boolean transparent,
                @Patch.Argument(value = 13, readOnly = false) float cutawayAlpha) {
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                if (PeekAViewMod.isCameraPlayerIndoor()) return;
                if (transparent) {
                    // Vanilla's XL-crown cutaway (aim key): the crown
                    // ramps 0.045/frame around two discrete composite
                    // swaps, and on release the whole tree fades back
                    // in from invisible. The bubble-gated field pin
                    // (Patch_isTranslucentTree) holds cutawayAlpha at
                    // its endpoints with tile hysteresis; snapping
                    // the DISPLAY copy to the nearest endpoint
                    // absorbs the one vanilla ramp step that runs
                    // between pin and draw. No geometry here — a
                    // second, screen-space bubble test flipped crowns
                    // on pixel jitter at the bubble edge.
                    cutawayAlpha = cutawayAlpha < 0.5f ? 0.0f : 1.0f;
                    return;
                }
                if (!bUseStencil) return;
                bUseStencil = false;
                transparent = true;
                // Render takes min(cutawayAlpha, fadeAlpha) — lift
                // both display copies to the visibility floor. When
                // this sprite covers the char on screen (base at or
                // below him, crown reaching up over him, laterally
                // overlapping), blend toward the deep floor so the
                // char stays readable through the crown — eased over
                // the footprint border instead of stepping.
                float floor = PeekAViewMod.TREE_FADE_MIN_VISIBLE_ALPHA;
                if (texture != null) {
                    int ts = Core.tileScale;
                    float cdx = x - IsoCamera.frameState.camCharacterX;
                    float cdy = y - IsoCamera.frameState.camCharacterY;
                    float sdx = (cdx - cdy) * 32f * ts;
                    float sdy = (cdx + cdy) * 16f * ts;
                    float halfW = texture.getWidth() * 0.5f + 24f * ts;
                    float inside = Math.min(
                            Math.min(sdy, texture.getHeight() - 64f * ts - sdy),
                            Math.min(sdx + halfW, halfW - sdx));
                    if (inside > 0f) {
                        float t = Math.min(1f, inside / (PeekAViewMod.TREE_FADE_COVER_BLEND_PX * ts));
                        floor += (PeekAViewMod.TREE_FADE_COVER_MIN_VISIBLE_ALPHA - floor) * t;
                    }
                }
                fadeAlpha = Math.max(fadeAlpha, floor);
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
