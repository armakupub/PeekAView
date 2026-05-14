package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;

public class Patch_UIManager {

    // Logic-phase override: player.getZ() here returns real-Z (mid-climb
    // sub-floor) before our render-phase swap kicks in. doBuildingInternal
    // later reads buildZ from the fake-Z camera, so without this override
    // the WalkTo marker projects with mismatched z and lands one tile-
    // height above the mouse on an unreachable square.
    //
    // Gates: stairLatchArmed picks up the climb state (isReady can't,
    // its frameCounter equality fails in the logic phase). The
    // lastStrictActivationFrame gate drops the override within ~3 frames
    // of the heading-cone failing, so the marker tracks the visible
    // floor when the player turns away from the upstairs direction.
    @Patch(className = "zombie.ui.UIManager", methodName = "getTileFromMouse")
    public static class Patch_getTileFromMouse {
        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(value = 2, readOnly = false) double z) {
            FakeFrameState ffs = FakeWindow.get(0);
            if (ffs == null || !ffs.stairLatchArmed) return;
            if (IsoCamera.frameState.frameCount - ffs.lastStrictActivationFrame > 3) return;
            z = PZMath.fastfloor(ffs.fakePos.z);
        }
    }
}
