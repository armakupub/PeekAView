package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoTree;

public class Patch_FBORenderCell {

    // Vanilla fades trees only in the SE quadrant of the player and
    // only inside its ~5-tile stencil-bbox (FBORenderCell:1772-1782).
    // We extend the fade to the full PeekAViewMod.treeFadeRange diamond
    // around the player (origin excluded).
    //
    // Patch_isTranslucentTree drives the renderFlag — required gate,
    // since IsoTree.fadeAlpha only steps DOWN when renderFlag=true.
    // It also applies a speed-proportional fade boost so trees snap
    // translucent at driving speeds instead of crawling at vanilla's
    // 0.045/frame alphaStep.
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

}
