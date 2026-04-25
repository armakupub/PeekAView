package pzmod.peekaview;

import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoTree;

public class Patch_FBORenderCell {

    // Vanilla fades trees only in the SE quadrant of the character
    // (tree.x >= camX && tree.y >= camY). We extend the fade to the
    // strict NW quadrant (tree.x < camX && tree.y < camY) within
    // PeekAViewMod.treeFadeRange, so distant zombies whose line of sight the
    // character already covers stop being hidden behind an opaque tree
    // sprite. The diagonal axes (dx==0 or dy==0) stay vanilla because
    // the visual overlap there is minimal and expanding them tends to
    // fade trees the user is walking toward from the side.
    //
    // Two patches, orthogonal:
    //
    // Patch_isTranslucentTree drives the renderFlag — required gate,
    // since IsoTree.fadeAlpha only steps DOWN when renderFlag=true.
    //
    // Patch_calculateObjectsObscuringPlayer appends the same NW tiles
    // to squaresObscuringPlayer, so calculateObjectTargetAlpha_NotDoor-
    // OrWall returns 0.66 for solid objects on those tiles. Without
    // this complement the fade-up ceiling stays at 1.0 and a toggled-
    // off NW tree snaps back to full opacity instead of easing.
    //
    // LOS untouched — IsoTree is not in LosUtil / specialObjects, so
    // translucency is purely a render-layer change.
    //
    // Note on access context: @Patch advice is inlined into the target
    // class's bytecode at runtime. Any non-constant field our advice
    // reads or writes must be accessible from the target — private
    // fields throw IllegalAccessError at runtime. Compile-time
    // constants can stay private because javac folds them into
    // literals before the advice sees a field reference. We therefore
    // keep only local variables inside the advice methods.

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "isTranslucentTree")
    public static class Patch_isTranslucentTree {

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoObject object,
                                @Patch.Return(readOnly = false) boolean result) {
            if (result) return;
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                if (!(object instanceof IsoTree)) return;
                if (object.square == null) return;

                int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                int tx = object.square.x;
                int ty = object.square.y;
                int dx = tx - camX;
                int dy = ty - camY;

                // Fade when the tree is SW or NE of the player (one
                // axis positive, the other negative). Skips the SE-of-
                // tree quadrant (dx<0 && dy<0) per user scope — trees
                // behind the player in iso draw order. NW of tree
                // (dx>0, dy>0) is vanilla's domain, our OnExit bails
                // via `if (result) return;` for that case.
                // Diamond range, matching Patch_calculateObjectsObscuring-
                // Player and Patch_DrawStencilMask — a Box would let
                // trees in the diagonal corners fade without any
                // matching stencil coverage / fadeAlpha ceiling.
                boolean fade = (dx < 0 && dy > 0) || (dx > 0 && dy < 0);
                if (fade) {
                    int adx = dx < 0 ? -dx : dx;
                    int ady = dy < 0 ? -dy : dy;
                    if (adx + ady > PeekAViewMod.treeFadeRange) fade = false;
                }

                if (fade) result = true;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_isTranslucentTree exit error", t);
            }
        }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "calculateObjectsObscuringPlayer")
    public static class Patch_calculateObjectsObscuringPlayer {

        // Cached pre-built Location list for the current (range, px, py,
        // pz) tuple. Output is a deterministic function of those four
        // ints — invariant across frames while the player stands still
        // and changes only on tile-cross / Z-change / range-change.
        // JFR (range=20, ON-run) showed Location accounted for 74.55%
        // of total allocation pressure; cache hit avoids that entirely.
        // Public/volatile/array-list for the @Patch advice-inlining
        // access context.
        public static volatile int cacheRange = Integer.MIN_VALUE;
        public static volatile int cachePx = Integer.MIN_VALUE;
        public static volatile int cachePy = Integer.MIN_VALUE;
        public static volatile int cachePz = Integer.MIN_VALUE;
        public static final ArrayList<IsoGameCharacter.Location> cachedLocations = new ArrayList<>(420);

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

                if (range != cacheRange || px != cachePx
                        || py != cachePy || pz != cachePz) {
                    rebuildCache(px, py, pz, range);
                }

                // Manual loop + pre-grow instead of addAll(Collection):
                // addAll internally calls c.toArray() which allocates a
                // fresh Object[N] mirror every frame — JFR after Item 3
                // showed that single allocation dominating the heap at
                // ~86%. ensureCapacity collapses the per-add growth
                // chain into a single up-front grow.
                final int n = cachedLocations.size();
                locations.ensureCapacity(locations.size() + n);
                for (int i = 0; i < n; ++i) {
                    locations.add(cachedLocations.get(i));
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_calculateObjectsObscuringPlayer exit error", t);
            }
        }

        // Tree SW of player (dx<0, dy>0) and tree NE of player
        // (dx>0, dy<0). Diamond-enveloped, matches the fade gate in
        // Patch_isTranslucentTree and Patch_DrawStencilMask.
        // Public for the @Patch advice-inlining access context — a
        // private helper throws IllegalAccessError when called from
        // the inlined advice in FBORenderCell.
        public static void rebuildCache(int px, int py, int pz, int range) {
            cachedLocations.clear();
            for (int dy = 1; dy <= range; ++dy) {
                for (int dx = -range; dx < 0; ++dx) {
                    if (-dx + dy > range) continue;
                    cachedLocations.add(new IsoGameCharacter.Location(
                            px + dx, py + dy, pz));
                }
            }
            for (int dy = -range; dy < 0; ++dy) {
                for (int dx = 1; dx <= range; ++dx) {
                    if (dx + -dy > range) continue;
                    cachedLocations.add(new IsoGameCharacter.Location(
                            px + dx, py + dy, pz));
                }
            }
            cacheRange = range;
            cachePx = px;
            cachePy = py;
            cachePz = pz;
        }
    }
}
