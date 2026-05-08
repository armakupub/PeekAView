package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;

// Stair feature — inverted pair on IsoGameCharacter.renderlast.
//
// Why: renderlast() is called by FBORenderCell.renderInternal AFTER the
// already inverted-pair'd renderPlayers() block. Our outer renderInternal
// patch re-mutates the camChar position to fake (upper-floor Z) once
// renderPlayers exits and restores the renderingFake ThreadLocal.
// IsoGameCharacter.renderlast() then computes sx/sy for halo, chat
// bubbles, userName tag, and progress-bar via this.getX/Y/Z() — which
// returns fake-Z because the field is fake AND the patched getter
// returns fake on the render thread with renderingFake set. Result:
// halo and other text overlays drawn at upper-floor screen position,
// not next to the player sprite on the stairs.
//
// Fix: temporarily restore real-pos for the duration of renderlast on
// the camChar only (non-camera-chars don't have fake-window state to
// restore from). Same inverted-pair pattern as
// Patch_FBORenderCell.Patch_renderPlayers, nested inside the outer
// renderInternal pair.
public class Patch_IsoGameCharacter {

    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "renderlast")
    public static class Patch_renderlast {

        @Patch.OnEnter
        public static void enter(
                @Patch.This IsoGameCharacter self,
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
                if (ffs.camChar != self) return;

                IsoCamera.FrameState fs = IsoCamera.frameState;
                idx = fs.playerIndex;
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                saved = ffs;
                // Captures done — commit early so any throw downstream still
                // hits the exit re-mutate path.
                paused = true;

                fs.camCharacterX = ffs.realPos.x;
                fs.camCharacterY = ffs.realPos.y;
                fs.camCharacterZ = ffs.realPos.z;
                fs.camCharacterSquare = ffs.realSquare;

                if (ffs.camChar != null) {
                    savedCurrent = ffs.camChar.getCurrentSquare();
                    ffs.camChar.setCurrent(ffs.realSquare);
                    // De-mutate order: writeRealPos BEFORE fieldMutated.set(0).
                    // During the gap a non-render reader sees flag=1 and gets
                    // realPos.x via the read-path shadow, matching the
                    // now-real field.
                    FakeWindow.writeRealPos(ffs.camChar, ffs.realPos.x, ffs.realPos.y, ffs.realPos.z);
                    FakeWindow.fieldMutated.set(idx, 0);
                }

                FakeWindow.renderingFake.remove();
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoGameCharacter.renderlast enter failed", t);
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
                    // Re-mutate order: flag BEFORE writeFakePos. See the
                    // ordering rationale on the renderInternal enter site.
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
