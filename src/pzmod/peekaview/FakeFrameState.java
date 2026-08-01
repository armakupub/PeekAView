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
    // Hysteresis source: last frame where all activation checks
    // passed. Keeps the fake window open through brief boundary-check
    // wobble (cone, head-Z) instead of toggling per frame.
    public int lastStrictActivationFrame = -1;
    // Climb latch: armed on strict pass while on a stair tile, cleared
    // on stepping off. Tracks stair presence instead of a frame budget
    // so TIS animation-speed changes can't invalidate it.
    public boolean stairLatchArmed;
    // Peak Z since arming. A sustained drop below peak (> 0.05, a
    // fraction of the 1.0 stair-Z scale) is the descent signal —
    // single-frame charZ drops are key-pose wobble and must not
    // release the latch.
    public float peakCharZ = Float.NEGATIVE_INFINITY;
    // Frame of the last peakCharZ advance. The latch only contributes
    // while ascent was recent: animation key-poses have stationary
    // frames inside an active climb (frame-by-frame ascent checks
    // flickered the window shut per frame), while sustained
    // no-progress — a turn-around without descent — must still
    // release.
    public int lastZIncreaseFrame = -1;
    public float lastViewpointZ;
    public boolean renderLighting;
    // Room of the landing (floorSquare) at computeFake time, -1 if
    // none. Snapshot instead of reading fakeSquare.room live: the
    // render swap mutates fake.room mid-frame while getAlpha can run
    // on a setup thread.
    public long landingRoomId = -1L;
}
