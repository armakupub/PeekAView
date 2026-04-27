package pzmod.peekaview;

import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoTree;
import zombie.util.list.PZArrayList;

public class Patch_FBORenderCell {

    // Vanilla fades trees only in the SE quadrant of the player and
    // only inside its ~5-tile stencil-bbox (FBORenderCell:1772-1782).
    // We extend the fade to the full PeekAViewMod.treeFadeRange diamond
    // around the player (origin excluded). Two orthogonal patches:
    //
    // Patch_isTranslucentTree drives the renderFlag — required gate,
    // since IsoTree.fadeAlpha only steps DOWN when renderFlag=true.
    // It also applies a speed-proportional fade boost so trees snap
    // translucent at driving speeds instead of crawling at vanilla's
    // 0.045/frame alphaStep.
    //
    // Patch_calculateObjectsObscuringPlayer appends the same diamond
    // tiles to squaresObscuringPlayer, so calculateObjectTargetAlpha_-
    // NotDoorOrWall returns 0.66 for solid objects on those tiles.
    // Without this complement the fade-up ceiling stays at 1.0 and a
    // toggled-off tree snaps back to full opacity instead of easing.
    //
    // LOS untouched — IsoTree is not in LosUtil / specialObjects, so
    // translucency is purely a render-layer change. The actual LOS
    // gate that decides whether a fade-flagged tree gets rendered
    // translucent lives in Patch_IsoCell.Patch_DrawStencilMask.
    //
    // Note on access context: @Patch advice is inlined into the target
    // class's bytecode at runtime. Any non-constant field our advice
    // reads or writes must be accessible from the target — private
    // fields throw IllegalAccessError at runtime. Compile-time
    // constants can stay private because javac folds them into
    // literals before the advice sees a field reference.

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "isTranslucentTree")
    public static class Patch_isTranslucentTree {

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoObject object,
                                @Patch.Return(readOnly = false) boolean result) {
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                if (!(object instanceof IsoTree)) return;
                if (object.square == null) return;

                // Flip translucent for any tile in the treeFadeRange
                // diamond around the player (origin excluded). The
                // LOS gate in Patch_DrawStencilMask decides what
                // actually fades on screen — flagging every diamond
                // tile here is safe.
                if (!result) {
                    int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                    int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                    int dx = object.square.x - camX;
                    int dy = object.square.y - camY;

                    if (dx == 0 && dy == 0) return;

                    int adx = dx < 0 ? -dx : dx;
                    int ady = dy < 0 ? -dy : dy;
                    if (adx + ady > PeekAViewMod.treeFadeRange) return;

                    result = true;
                }

                // Speed-proportional fade boost on top of vanilla's
                // per-frame alphaStep. Runs for both vanilla- and
                // patch-flipped trees so all sides respond the same
                // to driving speed. Steps DOWN only — range-exit
                // fade-up stays vanilla.
                float speed = PeekAViewMod.currentVehicleSpeedKmh;
                if (speed > 0f) {
                    IsoTree tree = (IsoTree) object;
                    float minAlpha = 0.25f;
                    if (tree.fadeAlpha > minAlpha) {
                        float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH;
                        float t = speed >= cap ? 1.0f : speed / cap;
                        tree.fadeAlpha += (minAlpha - tree.fadeAlpha) * t;
                        if (tree.fadeAlpha < minAlpha) tree.fadeAlpha = minAlpha;
                    }
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_isTranslucentTree exit error", t);
            }
        }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "calculateObjectsObscuringPlayer")
    public static class Patch_calculateObjectsObscuringPlayer {

        // Cached Location list for (range, px, py, pz). Cache hit
        // avoids per-frame Location allocation; rebuilt on tile-cross
        // / Z-change / range-change. Public/volatile for inlined-
        // advice access context.
        public static volatile int cacheRange = Integer.MIN_VALUE;
        public static volatile int cachePx = Integer.MIN_VALUE;
        public static volatile int cachePy = Integer.MIN_VALUE;
        public static volatile int cachePz = Integer.MIN_VALUE;
        public static final ArrayList<IsoGameCharacter.Location> cachedLocations = new ArrayList<>(1300);

        // Called from PeekAViewMod.setRange and setFadeNWTrees so a
        // toggle-off doesn't leak a stale list into a later toggle-on
        // at a different range.
        public static void invalidateCache() {
            cacheRange = Integer.MIN_VALUE;
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int playerIndex,
                                @Patch.Argument(1) ArrayList locations) {
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                if (locations == null) return;
                if (playerIndex < 0 || playerIndex >= IsoPlayer.MAX) return;

                IsoPlayer player = IsoPlayer.players[playerIndex];
                if (player == null) return;

                // Use player.getX/Y/Z, not getCurrentSquare(): the
                // latter returns null while the player is in a vehicle,
                // which would silently disable the fadeAlpha ceiling
                // override for trees during driving.
                int px = PZMath.fastfloor(player.getX());
                int py = PZMath.fastfloor(player.getY());
                int pz = PZMath.fastfloor(player.getZ());
                int range = PeekAViewMod.treeFadeRange;

                IsoCell cell = IsoWorld.instance.currentCell;
                if (range != cacheRange || px != cachePx
                        || py != cachePy || pz != cachePz) {
                    rebuildCache(cell, px, py, pz, range);
                }

                final int n = cachedLocations.size();
                if (n == 0) return;
                locations.ensureCapacity(locations.size() + n);
                for (int i = 0; i < n; ++i) {
                    locations.add(cachedLocations.get(i));
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_calculateObjectsObscuringPlayer exit error", t);
            }
        }

        // Diamond around the player (origin excluded), filtered to
        // tiles that actually contain at least one IsoTree. Vanilla
        // downstream walks this list per-frame in
        // squareHasFadingInObjects / isPotentiallyObscuringObject /
        // listContainsLocation; trimming non-tree tiles proportionally
        // shrinks every query. Public for the inlined-advice access
        // context.
        public static void rebuildCache(IsoCell cell, int px, int py, int pz, int range) {
            cachedLocations.clear();
            cacheRange = range;
            cachePx = px;
            cachePy = py;
            cachePz = pz;
            if (cell == null) return;
            for (int dy = -range; dy <= range; ++dy) {
                for (int dx = -range; dx <= range; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    int adx = dx < 0 ? -dx : dx;
                    int ady = dy < 0 ? -dy : dy;
                    if (adx + ady > range) continue;
                    int x = px + dx;
                    int y = py + dy;
                    if (!tileHasTree(cell, x, y, pz)) continue;
                    cachedLocations.add(new IsoGameCharacter.Location(x, y, pz));
                }
            }
        }

        // True iff the tile holds at least one IsoTree. Public for
        // the inlined-advice access context.
        public static boolean tileHasTree(IsoCell cell, int x, int y, int z) {
            IsoGridSquare sq = cell.getGridSquare(x, y, z);
            if (sq == null) return false;
            PZArrayList<IsoObject> objs = sq.getObjects();
            if (objs == null || objs.isEmpty()) return false;
            for (int i = 0, n = objs.size(); i < n; ++i) {
                if (objs.get(i) instanceof IsoTree) return true;
            }
            return false;
        }
    }
}
