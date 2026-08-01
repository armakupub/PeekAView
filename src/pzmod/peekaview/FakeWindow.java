package pzmod.peekaview;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicIntegerArray;

import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCamera;
import zombie.iso.IsoMovingObject;

// Stair feature — per-player fake-render window registry.
//
// Three-layer state:
//   data          per-player FakeFrameState filled by computeFake
//   renderingFake ThreadLocal set by render-pass patches for their
//                 window; marks "mid-render on this thread"
//   fieldMutated  per-player flag: fake x/y/z currently written onto
//                 the camChar's private fields
//
// Read-path resolution (Patch_IsoMovingObject):
//   render thread, TL set  -> fake values
//   other thread, flag=1   -> saved real values (background threads —
//                             LightingThread, async sound, AI workers —
//                             must not observe the render window)
//   otherwise              -> vanilla getter
//
// Why the field write at all: PZ render code mixes getter calls with
// direct-field reads of x/y/z (e.g. IsoCell.IsCutawaySquare). A
// getter-only shadow leaves direct reads on real values for the whole
// frame — visible cutaway flicker on stairs. Writing the fields makes
// every read see fake during the window; the shadow then re-isolates
// non-render threads.
//
// Ordering invariant for EVERY mutate/de-mutate site: set
// fieldMutated=1 BEFORE writeFakePos, and writeRealPos BEFORE
// fieldMutated=0 (rollback to 0 if the write fails). In both gaps the
// shadow serves realPos, which matches the field's actual content at
// that instant; either order reversed briefly exposes fake values to
// a concurrently reading thread.
public final class FakeWindow {
    public static final int MAX_PLAYERS = 4;

    public static final FakeFrameState[] data = new FakeFrameState[MAX_PLAYERS];

    public static final ThreadLocal<FakeFrameState> renderingFake = new ThreadLocal<>();

    // AtomicIntegerArray, not boolean[]: array elements are never
    // volatile even if the reference is. Without the release/acquire
    // edge a non-render thread could still read 0 after the render
    // thread set 1 and miss the shadow. The release on set(idx, 1)
    // also publishes the FakeFrameState mutations from earlier in the
    // frame.
    public static final AtomicIntegerArray fieldMutated = new AtomicIntegerArray(MAX_PLAYERS);

    private static Field FIELD_X;
    private static Field FIELD_Y;
    private static Field FIELD_Z;
    private static Field FIELD_CURRENT;

    static {
        try {
            FIELD_X = IsoMovingObject.class.getDeclaredField("x");
            FIELD_Y = IsoMovingObject.class.getDeclaredField("y");
            FIELD_Z = IsoMovingObject.class.getDeclaredField("z");
            FIELD_CURRENT = IsoMovingObject.class.getDeclaredField("current");
            FIELD_X.trySetAccessible();
            FIELD_Y.trySetAccessible();
            FIELD_Z.trySetAccessible();
            FIELD_CURRENT.trySetAccessible();
        } catch (NoSuchFieldException e) {
            FIELD_X = null;
            FIELD_Y = null;
            FIELD_Z = null;
            FIELD_CURRENT = null;
        }
    }

    // Bypasses the patched getter. Save-ops in our renderInternal
    // pairs run after renderingFake is set, so getCurrentSquare()
    // would return fakeSquare — restoring that at exit leaks
    // fakeSquare into the next update tick's current.
    public static zombie.iso.IsoGridSquare readCurrentField(IsoMovingObject self) {
        if (FIELD_CURRENT == null) return self.getCurrentSquare();
        try {
            return (zombie.iso.IsoGridSquare) FIELD_CURRENT.get(self);
        } catch (IllegalAccessException e) {
            return self.getCurrentSquare();
        }
    }

    public static FakeFrameState getOrAllocate(int playerIndex) {
        FakeFrameState ffs = data[playerIndex];
        if (ffs == null) {
            ffs = new FakeFrameState();
            data[playerIndex] = ffs;
        }
        return ffs;
    }

    public static FakeFrameState get(int playerIndex) {
        if (playerIndex < 0 || playerIndex >= MAX_PLAYERS) return null;
        return data[playerIndex];
    }

    public static boolean isReady(int playerIndex) {
        FakeFrameState ffs = get(playerIndex);
        return ffs != null && ffs.frameCounter == IsoCamera.frameState.frameCount;
    }

    // Writes the x/y/z fields directly — does NOT touch nx, scriptnx,
    // lx/ly/lz, so game-thread interpolation logic is unaffected.
    public static boolean writeFakePos(IsoGameCharacter camChar, float x, float y, float z) {
        if (FIELD_X == null) return false;
        try {
            FIELD_X.setFloat(camChar, x);
            FIELD_Y.setFloat(camChar, y);
            FIELD_Z.setFloat(camChar, z);
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    public static void writeRealPos(IsoGameCharacter camChar, float x, float y, float z) {
        if (FIELD_X == null) return;
        try {
            FIELD_X.setFloat(camChar, x);
            FIELD_Y.setFloat(camChar, y);
            FIELD_Z.setFloat(camChar, z);
        } catch (IllegalAccessException ignored) {
        }
    }

    // The FakeFrameState whose camChar is `self` while its fields are
    // mutated; null if none.
    public static FakeFrameState findMutatedFor(IsoMovingObject self) {
        if (self == null) return null;
        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (fieldMutated.get(i) == 0) continue;
            FakeFrameState ffs = data[i];
            if (ffs != null && ffs.camChar == self) return ffs;
        }
        return null;
    }
}
