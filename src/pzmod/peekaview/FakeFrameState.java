package pzmod.peekaview;

import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoGridSquare;
import zombie.iso.Vector3;

// Stair feature — per-player render-pass state filled by
// Patch_IsoWorld.computeFake. Consumed by every patch that swaps
// camera/character position to the upper floor while rendering on
// stairs.
public final class FakeFrameState {
    public final Vector3 realPos = new Vector3();
    public final Vector3 fakePos = new Vector3();
    public IsoGameCharacter camChar;
    public IsoGridSquare realSquare;
    public IsoGridSquare fakeSquare;
    public IsoGridSquare floorSquare;
    public int frameCounter = -1;
    // Last frame where all activation checks (cone, head-Z, fastfloor)
    // passed. Hysteresis source: if a later frame's boundary check
    // briefly fails but we were active recently, keep the fake-window
    // open with current-frame data instead of toggling off. Eliminates
    // the per-frame on/off "ghost jumping" flicker from camera/
    // animation wobble at half-Z points.
    public int lastStrictActivationFrame = -1;
    // State-driven stair-climb latch. Armed when strictPass succeeds
    // while the camChar is on a stair tile; cleared as soon as the
    // camChar steps off all stair tiles. While armed AND on a stair
    // tile, computeFake bypasses the strict gate and reuses the prior
    // frame's fake values. Avoids hardcoding climb duration: the latch
    // tracks "is the player still on the stair?" rather than "has it
    // been less than N frames?" — robust to TIS animation-speed
    // changes that would invalidate any fixed frame budget.
    public boolean stairLatchArmed;
    // Peak Z observed since the latch armed. The latch should bridge
    // sub-frame animation wobble during ascent but NOT survive an
    // intentional turn-around / descent on the same stair. We can't
    // tell those apart from a single-frame charZ drop (key-pose wobble
    // can drop a few cm), but a sustained drop relative to the climb's
    // peak is a clean descent signal. Threshold at 0.05f (~5% of stair
    // height = 1.0) — small enough to ignore key-pose noise, large
    // enough to catch a real reversal within a few frames. The 0.05f
    // is a fraction of the Z scale, not a frame budget, so TIS
    // animation-speed changes don't invalidate it.
    public float peakCharZ = Float.NEGATIVE_INFINITY;
    // Last frame in which peakCharZ was advanced. Used to gate the
    // stair-tile latch on recent ascent: the latch contributes if
    // peakCharZ has been climbing within the last HYSTERESIS_FRAMES
    // window. Frame-by-frame ascending detection (charZ > lastCharZ)
    // turned out too strict — animation key-poses have stationary
    // frames inside an active climb, and rejecting those caused the
    // fake window to flicker shut for one frame at a time, toggling
    // wall cutaway and snapping zombie alpha. The hysteresis window
    // bridges those key-pose pauses while still releasing the latch
    // after a sustained no-progress period (turn-around without
    // descent). HYSTERESIS_FRAMES is the same 30-frame budget used
    // by the strict-pass hysteresis, so the closure timing matches
    // original Staircast.
    public int lastZIncreaseFrame = -1;
    public float lastViewpointZ;
    public boolean renderLighting;
}
