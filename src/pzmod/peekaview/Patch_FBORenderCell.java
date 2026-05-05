package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoTree;

public class Patch_FBORenderCell {

    // Vanilla fades trees only in the SE quadrant of the player and
    // only inside its ~5-tile stencil-bbox (FBORenderCell:1772-1782).
    // We extend the fade to the PeekAViewMod.treeFadeRange diamond,
    // gated on the player's forward cone: out of cone we leave
    // vanilla's result alone, in cone we flip it for any tile in
    // the diamond. Drives renderFlag (required gate; IsoTree.
    // fadeAlpha only steps DOWN when renderFlag=true) and adds a
    // speed-proportional fade boost for vehicle speeds.
    //
    // LOS-blocking lives in Patch_IsoCell.Patch_DrawStencilMask;
    // this patch is purely a render-layer flip.
    //
    // @Patch advice inlines into the target's bytecode, so non-
    // constant fields it reads or writes must be accessible from
    // there. Compile-time constants stay private (javac folds them).

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "isTranslucentTree")
    public static class Patch_isTranslucentTree {

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoObject object,
                                @Patch.Return(readOnly = false) boolean result) {
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                // Outdoor-only — mirrors the gate in Patch_DrawStencilMask.
                if (PeekAViewMod.isCameraPlayerIndoor()) return;
                if (!(object instanceof IsoTree)) return;
                if (object.square == null) return;

                // Flip only inside the diamond AND in the cone. Out
                // of cone we leave vanilla's result alone so close
                // NW/N/W trees stay opaque when not looked at.
                if (!result) {
                    int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                    int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                    int dx = object.square.x - camX;
                    int dy = object.square.y - camY;

                    if (dx == 0 && dy == 0) return;

                    int adx = dx < 0 ? -dx : dx;
                    int ady = dy < 0 ? -dy : dy;
                    if (adx + ady > PeekAViewMod.treeFadeRange) return;

                    if (!PeekAViewMod.isTileInCameraPlayerCone(object.square)) return;

                    result = true;
                }

                // Speed-proportional fade boost on top of vanilla's
                // alphaStep. Steps DOWN only — fade-up stays vanilla.
                // Below MIN_KMH (~walking pace) we don't touch fadeAlpha
                // at all so vanilla's 0.55 s rate owns the animation;
                // even tiny t³ values would otherwise compound across
                // the 6-10 isTranslucentTree calls per tree per frame.
                // Between MIN_KMH and SPEED_CAP_KMH the cubic ramp
                // builds toward snap; at/above SPEED_CAP_KMH the
                // decrement covers the full vanilla range in one call.
                float speed = PeekAViewMod.currentVehicleSpeedKmh;
                float minBoost = PeekAViewMod.TREE_FADE_SNAP_MIN_KMH;
                if (speed > minBoost) {
                    IsoTree tree = (IsoTree) object;
                    float minAlpha = 0.25f;
                    if (tree.fadeAlpha > minAlpha) {
                        float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH;
                        float t = speed >= cap ? 1.0f : (speed - minBoost) / (cap - minBoost);
                        float decrement = (1.0f - minAlpha) * t * t * t;
                        tree.fadeAlpha -= decrement;
                        if (tree.fadeAlpha < minAlpha) tree.fadeAlpha = minAlpha;
                    }
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_isTranslucentTree exit error", t);
            }
        }
    }

}
