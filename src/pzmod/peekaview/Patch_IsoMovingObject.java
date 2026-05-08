package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;

// Stair feature — read-path patches on the live position/square
// getters. Two-mode behavior:
//   - Render thread (ThreadLocal renderingFake set): return fake
//     value (upper-floor Z / fakeSquare) so render code sees the
//     upstairs perspective.
//   - Non-render thread with field currently mutated by render-pass:
//     return the saved REAL value so update logic (updateFalling on
//     game thread, AI/sound/lighting on background threads) doesn't
//     see the brief fake-window mutation. Without this,
//     updateFalling reads fake Z while x/y/z fields are mutated and
//     triggers an infinite stair-fall loop.
//   - Otherwise: skip, vanilla getter returns the field value.
public class Patch_IsoMovingObject {

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "getX")
    public static class Patch_getX {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoMovingObject self, @Patch.Local("v") float v) {
            FakeFrameState renderFfs = FakeWindow.renderingFake.get();
            if (renderFfs != null && self == renderFfs.camChar) {
                v = renderFfs.fakePos.x;
                return true;
            }
            FakeFrameState mutated = FakeWindow.findMutatedFor(self);
            if (mutated != null) {
                v = mutated.realPos.x;
                return true;
            }
            return false;
        }

        @Patch.OnExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Patch.Local("v") float v,
                                @Patch.Return(readOnly = false) float ret) {
            if (skipped) ret = v;
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "getY")
    public static class Patch_getY {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoMovingObject self, @Patch.Local("v") float v) {
            FakeFrameState renderFfs = FakeWindow.renderingFake.get();
            if (renderFfs != null && self == renderFfs.camChar) {
                v = renderFfs.fakePos.y;
                return true;
            }
            FakeFrameState mutated = FakeWindow.findMutatedFor(self);
            if (mutated != null) {
                v = mutated.realPos.y;
                return true;
            }
            return false;
        }

        @Patch.OnExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Patch.Local("v") float v,
                                @Patch.Return(readOnly = false) float ret) {
            if (skipped) ret = v;
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "getZ")
    public static class Patch_getZ {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoMovingObject self, @Patch.Local("v") float v) {
            FakeFrameState renderFfs = FakeWindow.renderingFake.get();
            if (renderFfs != null && self == renderFfs.camChar) {
                v = renderFfs.fakePos.z;
                return true;
            }
            FakeFrameState mutated = FakeWindow.findMutatedFor(self);
            if (mutated != null) {
                v = mutated.realPos.z;
                return true;
            }
            return false;
        }

        @Patch.OnExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Patch.Local("v") float v,
                                @Patch.Return(readOnly = false) float ret) {
            if (skipped) ret = v;
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "getCurrentSquare")
    public static class Patch_getCurrentSquare {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoMovingObject self, @Patch.Local("v") IsoGridSquare v) {
            FakeFrameState renderFfs = FakeWindow.renderingFake.get();
            if (renderFfs != null && self == renderFfs.camChar) {
                v = renderFfs.fakeSquare;
                return true;
            }
            FakeFrameState mutated = FakeWindow.findMutatedFor(self);
            if (mutated != null) {
                v = mutated.realSquare;
                return true;
            }
            return false;
        }

        @Patch.OnExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Patch.Local("v") IsoGridSquare v,
                                @Patch.Return(readOnly = false) IsoGridSquare ret) {
            if (skipped) ret = v;
        }
    }
}
