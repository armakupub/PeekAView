package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;

// Stair feature — uplifts the lighting probe to the upper floor so the
// stair-top tile is lit with the upper floor's lighting state. Active
// only when FakeFrameState.renderLighting is set (no zombie on a
// nearby stair tile).
public class Patch_LightingJNI {

    @Patch(className = "zombie.iso.LightingJNI", methodName = "updatePlayer")
    public static class Patch_updatePlayer {

        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(0) int playerIndex,
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            try {
                if (!FakeWindow.isReady(playerIndex)) return;
                FakeFrameState ffs = FakeWindow.get(playerIndex);
                if (ffs == null || !ffs.renderLighting) return;

                IsoCamera.FrameState fs = IsoCamera.frameState;
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                savedTL = FakeWindow.renderingFake.get();

                fs.camCharacterX = ffs.fakePos.x;
                fs.camCharacterY = ffs.fakePos.y;
                fs.camCharacterZ = ffs.fakePos.z;
                fs.camCharacterSquare = ffs.fakeSquare;

                FakeWindow.renderingFake.set(ffs);
                opened = true;
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: LightingJNI.updatePlayer enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            if (!opened) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;
            } finally {
                if (savedTL != null) FakeWindow.renderingFake.set(savedTL);
                else FakeWindow.renderingFake.remove();
            }
        }
    }

    @Patch(className = "zombie.iso.LightingJNI", methodName = "checkPlayerTorches")
    public static class Patch_checkPlayerTorches {

        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(0) IsoPlayer player,
                @Patch.Argument(1) int playerIndex,
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            try {
                int idx = playerIndex;
                if (zombie.network.GameClient.client) {
                    if (player != IsoPlayer.getInstance()) return;
                    idx = IsoPlayer.getPlayerIndex();
                }
                if (!FakeWindow.isReady(idx)) return;
                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs == null || !ffs.renderLighting) return;

                IsoCamera.FrameState fs = IsoCamera.frameState;
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                savedTL = FakeWindow.renderingFake.get();

                fs.camCharacterX = ffs.fakePos.x;
                fs.camCharacterY = ffs.fakePos.y;
                fs.camCharacterZ = ffs.fakePos.z;
                fs.camCharacterSquare = ffs.fakeSquare;

                FakeWindow.renderingFake.set(ffs);
                opened = true;
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: LightingJNI.checkPlayerTorches enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            if (!opened) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;
            } finally {
                if (savedTL != null) FakeWindow.renderingFake.set(savedTL);
                else FakeWindow.renderingFake.remove();
            }
        }
    }
}
