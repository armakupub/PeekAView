package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoTree;

public class Patch_FBORenderCell {

    // Vanilla fades trees only in the SE quadrant of the player and
    // only inside its ~5-tile stencil-bbox (FBORenderCell:1772-1782).
    // We extend the fade to a Euclidean circle of radius
    // PeekAViewMod.treeFadeRange, gated on the player's forward
    // cone: out of cone we leave vanilla's result alone, in cone we
    // flip it for any tile inside the circle. Euclidean (not
    // Manhattan) so diagonal travel doesn't shrink the effective
    // reach asymmetrically. Drives renderFlag (required gate;
    // IsoTree.fadeAlpha only steps DOWN when renderFlag=true) and
    // adds a bidirectional speed-proportional fade snap for vehicle
    // speeds: DOWN when in zone, UP when clearly behind.
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

                // Classify the tile for the bidirectional speed-snap:
                //   clearlyBehind — back ~140° cone (geometric, vision-
                //                   independent). Snap-UP for refade.
                //                   Checked first so trees behind the
                //                   player route to refade, not fade,
                //                   even if they're inside the tree-
                //                   fade cone (vehicle cone is 360° so
                //                   anything behind would otherwise
                //                   overlap inZone).
                //   inZone        — Euclidean radius (slider) AND tree-
                //                   fade cone. Drives renderFlag, snap
                //                   accelerates vanilla's DOWN step.
                // Tree-fade cone uses the uncapped vanilla cone (360° in
                // vehicles) so there's no gap between in-cone and
                // clearly-behind where vanilla's SE-quadrant logic could
                // toggle result and flicker the tile in the user's view.
                // For walking the tree-fade cone collapses back to the
                // dynamic forward cone (vanilla returns ≤0 on foot).
                boolean inZone = result;
                boolean clearlyBehind = false;
                if (!result) {
                    int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                    int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                    int dx = object.square.x - camX;
                    int dy = object.square.y - camY;

                    if (dx == 0 && dy == 0) return;

                    if (PeekAViewMod.isTileClearlyBehindCameraPlayer(object.square)) {
                        clearlyBehind = true;
                    } else {
                        int range = PeekAViewMod.treeFadeRange;
                        if (dx * dx + dy * dy <= range * range
                            && PeekAViewMod.isTileInTreeFadeCone(object.square)) {
                            inZone = true;
                            result = true;
                        }
                    }
                }

                // Speed-proportional fade boost on top of vanilla's
                // alphaStep. Below MIN_KMH (~walking pace) we don't
                // touch fadeAlpha at all so vanilla's 0.55 s rate owns
                // the animation; even tiny t³ values would otherwise
                // compound across the 6-10 isTranslucentTree calls per
                // tree per frame. Between MIN_KMH and SPEED_CAP_KMH
                // the cubic ramp builds toward snap; at/above
                // SPEED_CAP_KMH a single call covers the full range.
                float speed = PeekAViewMod.currentVehicleSpeedKmh;
                float minBoost = PeekAViewMod.TREE_FADE_SNAP_MIN_KMH;
                if (speed > minBoost) {
                    IsoTree tree = (IsoTree) object;
                    float minAlpha = 0.25f;
                    float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH;
                    float t = speed >= cap ? 1.0f : (speed - minBoost) / (cap - minBoost);
                    float step = (1.0f - minAlpha) * t * t * t;
                    if (inZone && tree.fadeAlpha > minAlpha) {
                        tree.fadeAlpha -= step;
                        if (tree.fadeAlpha < minAlpha) tree.fadeAlpha = minAlpha;
                    } else if (clearlyBehind && tree.fadeAlpha < 1.0f) {
                        tree.fadeAlpha += step;
                        if (tree.fadeAlpha > 1.0f) tree.fadeAlpha = 1.0f;
                    }
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_isTranslucentTree exit error", t);
            }
        }
    }

    // == Stair feature ==
    // Outer FBO render-pass swap: replace camera position/square with the
    // upper-floor fake values so the chunk renders the floor above. See
    // Patch_IsoWorld.computeFake for how the FakeWindow is filled, and
    // Patch_IsoMovingObject for the read-path shadow that keeps the
    // game thread seeing real values.

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "renderInternal")
    public static class Patch_renderInternal {

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("idx") int idx,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentSwapped") boolean currentSwapped,
                @Patch.Local("posMutated") boolean posMutated,
                @Patch.Local("sqSwapped") boolean sqSwapped,
                @Patch.Local("savedRoom") IsoRoom savedRoom,
                @Patch.Local("savedRoomId") long savedRoomId,
                @Patch.Local("savedExterior") boolean savedExterior) {
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                idx = fs.playerIndex;
                if (!FakeWindow.isReady(idx)) return;

                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs == null) return;

                // Captures only — no mutation, no throws. Commit opened=true
                // immediately after so any throw further down still hits the
                // exit cleanup path. Each cleanup branch is gated on its own
                // step-flag (currentSwapped, posMutated, sqSwapped) to know
                // which mutations actually landed.
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                opened = true;
                FakeWindow.renderingFake.set(ffs);

                fs.camCharacterX = ffs.fakePos.x;
                fs.camCharacterY = ffs.fakePos.y;
                fs.camCharacterZ = ffs.fakePos.z;
                fs.camCharacterSquare = ffs.fakeSquare;

                if (ffs.camChar != null && ffs.fakeSquare != null) {
                    savedCurrent = ffs.camChar.getCurrentSquare();
                    ffs.camChar.setCurrent(ffs.fakeSquare);
                    currentSwapped = true;
                }

                // Set the visibility flag BEFORE writing fake fields. With
                // the original (write-then-flag) order, a non-render thread
                // reading getX between the two ops sees fake-Z via the
                // vanilla getter (no shadow yet). Pre-setting the flag
                // means that during the gap the shadow returns realPos.x,
                // which matches the still-real field; once writeFakePos
                // lands, the shadow keeps returning realPos.x while
                // render-path reads via TL get fakePos.x. Rollback on
                // Reflection failure.
                if (ffs.camChar != null) {
                    FakeWindow.fieldMutated.set(idx, 1);
                    if (FakeWindow.writeFakePos(ffs.camChar, ffs.fakePos.x, ffs.fakePos.y, ffs.fakePos.z)) {
                        posMutated = true;
                    } else {
                        FakeWindow.fieldMutated.set(idx, 0);
                    }
                }

                IsoGridSquare fake = ffs.fakeSquare;
                IsoGridSquare floor = ffs.floorSquare;
                if (fake != null && floor != null && fake.room == null && floor.room != null) {
                    savedRoom = fake.room;
                    savedRoomId = fake.roomId;
                    savedExterior = fake.getProperties().has(IsoFlagType.exterior);
                    // Commit before mutation so a partial throw mid-swap
                    // still gets cleaned up by exit's sqSwapped branch.
                    sqSwapped = true;
                    fake.room = floor.room;
                    fake.roomId = floor.getRoomID();
                    if (savedExterior) {
                        fake.getProperties().unset(IsoFlagType.exterior);
                    }
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: FBORenderCell.renderInternal enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("idx") int idx,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentSwapped") boolean currentSwapped,
                @Patch.Local("posMutated") boolean posMutated,
                @Patch.Local("sqSwapped") boolean sqSwapped,
                @Patch.Local("savedRoom") IsoRoom savedRoom,
                @Patch.Local("savedRoomId") long savedRoomId,
                @Patch.Local("savedExterior") boolean savedExterior) {
            if (!opened) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;

                // Use FakeWindow.get(idx) instead of TL — TL may have been
                // cleared by a nested inverse-pair patch (renderPlayers,
                // FBORenderTrees) that ran inside this render window.
                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs != null) {
                    if (posMutated && ffs.camChar != null) {
                        FakeWindow.writeRealPos(ffs.camChar, savedX, savedY, savedZ);
                        FakeWindow.fieldMutated.set(idx, 0);
                    }
                    if (currentSwapped && ffs.camChar != null) {
                        ffs.camChar.setCurrent(savedCurrent);
                    }
                    if (sqSwapped && ffs.fakeSquare != null) {
                        IsoGridSquare fake = ffs.fakeSquare;
                        fake.room = savedRoom;
                        fake.roomId = savedRoomId;
                        if (savedExterior) {
                            fake.getProperties().set(IsoFlagType.exterior);
                        }
                    }
                }
            } finally {
                FakeWindow.renderingFake.remove();
            }
        }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "isPotentiallyObscuringObject")
    public static class Patch_isPotentiallyObscuringObject {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            if (object == null || object.getSprite() == null) return false;
            FakeFrameState ffs = FakeWindow.renderingFake.get();
            if (ffs == null || ffs.fakeSquare == null || object.square == null) return false;
            return ffs.fakeSquare.z == object.square.z;
        }

        @Patch.OnExit
        public static void exit(@Advice.Enter boolean skipped, @Patch.Return(readOnly = false) boolean ret) {
            if (skipped) {
                ret = true;
            }
        }
    }

    // Inverted pair: temporarily restore real values so the player sprite
    // is drawn at its real position while the rest of the frame renders
    // the upper floor.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "renderPlayers")
    public static class Patch_renderPlayers {

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("paused") boolean paused,
                @Patch.Local("idx") int idx,
                @Patch.Local("saved") FakeFrameState saved,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent) {
            try {
                FakeFrameState ffs = FakeWindow.renderingFake.get();
                if (ffs == null) return;

                IsoCamera.FrameState fs = IsoCamera.frameState;
                idx = fs.playerIndex;
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                saved = ffs;
                // Captures done — commit early so any throw downstream still
                // hits the exit re-mutate path and restores the fake window.
                paused = true;

                fs.camCharacterX = ffs.realPos.x;
                fs.camCharacterY = ffs.realPos.y;
                fs.camCharacterZ = ffs.realPos.z;
                fs.camCharacterSquare = ffs.realSquare;

                // De-mutate order: writeRealPos BEFORE fieldMutated.set(0).
                // Already correct — during the gap a non-render reader sees
                // flag=1 and gets realPos.x, matching the now-real field.
                if (ffs.camChar != null) {
                    savedCurrent = ffs.camChar.getCurrentSquare();
                    ffs.camChar.setCurrent(ffs.realSquare);
                    FakeWindow.writeRealPos(ffs.camChar, ffs.realPos.x, ffs.realPos.y, ffs.realPos.z);
                    FakeWindow.fieldMutated.set(idx, 0);
                }

                FakeWindow.renderingFake.remove();
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: FBORenderCell.renderPlayers enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("paused") boolean paused,
                @Patch.Local("idx") int idx,
                @Patch.Local("saved") FakeFrameState saved,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent) {
            if (!paused) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;
                if (saved != null && saved.camChar != null && savedCurrent != null) {
                    saved.camChar.setCurrent(savedCurrent);
                    // Re-mutate order: flag BEFORE writeFakePos. See the
                    // ordering rationale on the renderInternal enter site.
                    FakeWindow.fieldMutated.set(idx, 1);
                    if (!FakeWindow.writeFakePos(saved.camChar, saved.fakePos.x, saved.fakePos.y, saved.fakePos.z)) {
                        FakeWindow.fieldMutated.set(idx, 0);
                    }
                }
            } finally {
                if (saved != null) FakeWindow.renderingFake.set(saved);
            }
        }
    }
}
