package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;

// Stair feature — uplifts the weather FX origin so rain/snow renders
// at the upper-floor reference plane (without this, particles use the
// player's real Z and clip through the rendered upper floor).
//
// PlayerFxMask.initMask calls player.getMasterRegion(), which reads
// IsoMovingObject.current as a field — the ThreadLocal-gated getter
// shadow doesn't catch it. Revert current to realSquare for the
// duration so the mask's region lookup resolves to the player's real
// region, otherwise hasMaskToDraw can flip true on a fogMask-region
// landing and wipe outdoor rain.
public class Patch_WeatherFxMask {

    @Patch(className = "zombie.iso.weather.fx.WeatherFxMask", methodName = "initMask", warmUp = true)
    public static class Patch_initMask {

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentReverted") boolean currentReverted) {
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                int idx = fs.playerIndex;
                if (!FakeWindow.isReady(idx)) return;
                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs == null) return;

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

                if (ffs.camChar != null && ffs.realSquare != null) {
                    savedCurrent = FakeWindow.readCurrentField(ffs.camChar);
                    ffs.camChar.setCurrent(ffs.realSquare);
                    currentReverted = true;
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: WeatherFxMask.initMask enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentReverted") boolean currentReverted) {
            if (!opened) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;

                if (currentReverted) {
                    FakeFrameState ffs = FakeWindow.get(fs.playerIndex);
                    if (ffs != null && ffs.camChar != null) {
                        ffs.camChar.setCurrent(savedCurrent);
                    }
                }
            } finally {
                if (savedTL != null) FakeWindow.renderingFake.set(savedTL);
                else FakeWindow.renderingFake.remove();
            }
        }
    }

    @Patch(className = "zombie.iso.weather.fx.WeatherFxMask", methodName = "renderFxMask", warmUp = true)
    public static class Patch_renderFxMask {

        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(0) int playerIndex,
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentReverted") boolean currentReverted) {
            try {
                if (!FakeWindow.isReady(playerIndex)) return;
                FakeFrameState ffs = FakeWindow.get(playerIndex);
                if (ffs == null) return;

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

                if (ffs.camChar != null && ffs.realSquare != null) {
                    savedCurrent = FakeWindow.readCurrentField(ffs.camChar);
                    ffs.camChar.setCurrent(ffs.realSquare);
                    currentReverted = true;
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: WeatherFxMask.renderFxMask enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Argument(0) int playerIndex,
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedTL") FakeFrameState savedTL,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentReverted") boolean currentReverted) {
            if (!opened) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;

                if (currentReverted) {
                    FakeFrameState ffs = FakeWindow.get(playerIndex);
                    if (ffs != null && ffs.camChar != null) {
                        ffs.camChar.setCurrent(savedCurrent);
                    }
                }
            } finally {
                if (savedTL != null) FakeWindow.renderingFake.set(savedTL);
                else FakeWindow.renderingFake.remove();
            }
        }
    }
}
