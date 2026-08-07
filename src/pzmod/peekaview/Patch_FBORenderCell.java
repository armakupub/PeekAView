package pzmod.peekaview;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.asm.Advice;

import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoTreeJumbo;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoTree;

public class Patch_FBORenderCell {

    // Tree-fade extension: a Euclidean circle (wide radius in a
    // vehicle, compact radius on foot) minus the clearly-behind
    // back-cone, gated on the tree-fade cone (360° in a vehicle, the
    // forward vision cone on foot). Euclidean,
    // not Manhattan — diamonds shrink the reach on diagonal travel.
    // The result flip is additive-only (false→true) and drives
    // renderFlag; IsoTree.fadeAlpha only steps DOWN while it's true,
    // and vanilla's own aim-time fade keeps working on top. Display is
    // handled by Patch_FBORenderTrees.Patch_addTree; this patch is
    // purely the render-layer flip plus the speed snap.

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "isTranslucentTree")
    public static class Patch_isTranslucentTree {

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoObject object,
                                @Patch.Return(readOnly = false) boolean result) {
            try {
                // instanceof first: with wind sprite effects off the
                // layer classifiers call isTranslucentTree for
                // arbitrary objects (walls, furniture), not just trees.
                if (!(object instanceof IsoTree)) return;
                if (object.square == null) return;
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                // Outdoor-only — mirrors the gate in
                // Patch_FBORenderTrees.Patch_addTree.
                if (PeekAViewMod.isCameraPlayerIndoor()) return;
                IsoTree tree = (IsoTree) object;

                boolean aiming = PeekAViewMod.currentCameraPlayerAiming;

                // RMB fells registry jumbos as a hard state flip:
                // pinning cutawayAlpha to its endpoints removes the
                // 0.045/frame ramp window entirely — no crown
                // animation on press, no stale felled look waiting
                // out the ramp on release. canSee mirrors vanilla's
                // main gate (its obscured-squares fallback is
                // dropped; those trees stay whole). Aim frames plus
                // the release frame suffice: outside them vanilla's
                // own ramp holds the field at the 1.0 endpoint
                // (transparent=false ramps up, clamped).
                if ((aiming || PeekAViewMod.aimReleasedThisFrame)
                        && tree.sprite != null && tree.sprite.name != null
                        && IsoTreeJumbo.Jumbos.get(tree.sprite.name) != null) {
                    int pidx = IsoCamera.frameState.playerIndex;
                    boolean felled = aiming
                            && pidx >= 0 && pidx < IsoPlayer.MAX
                            && object.square.lighting[pidx].bCanSee();
                    PeekAViewMod.writeTreeCutawayAlpha(tree, felled ? 0.0f : 1.0f);
                }

                int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                int dx = object.square.x - camX;
                int dy = object.square.y - camY;
                if (dx == 0 && dy == 0) return;

                boolean inVehicle = PeekAViewMod.currentCameraPlayerInVehicle;

                int distSq = dx * dx + dy * dy;
                int range = inVehicle
                        ? PeekAViewMod.TREE_FADE_VEHICLE_RANGE
                        : PeekAViewMod.onFootTreeFadeRange(tree.size);
                int exit = range + PeekAViewMod.TREE_FADE_EXIT_HYSTERESIS;
                // Camera-side corridor eligibility: SE-quadrant tiles,
                // on foot only while the char faces SE, in a vehicle
                // without a facing flag (entry still runs through the
                // travel cone). Vehicle view depth derives from the
                // fade circle's diagonal reach (~range*sqrt2).
                boolean seCorridor = dx >= 0 && dy >= 0
                        && (inVehicle || PeekAViewMod.currentCameraPlayerFacingSE);
                int seViewDepth = inVehicle
                        ? (range * 3) / 2
                        : PeekAViewMod.TREE_FADE_GAZE_VIEW_DEPTH;
                boolean fading = tree.fadeAlpha < 1.0f;

                boolean inZone = result;
                if (!result) {
                    if (PeekAViewMod.inNearOverlap(dx, dy, tree.size)) {
                        // Facing-free near-field: the crown covers the
                        // char, keep him visible in every direction.
                        inZone = true;
                        result = true;
                    } else {
                        boolean inReach = distSq <= range * range
                                || (seCorridor && PeekAViewMod.inSeCorridor(dx, dy, tree.size, fading, seViewDepth));
                        // Common case: out of reach and either opaque
                        // or already past the exit ring — membership
                        // can't change, skip the dot math (sqrt)
                        // entirely.
                        if (!inReach && (!fading || distSq > exit * exit)) return;
                        // Behind blocks membership before the zone
                        // tests: the fade zone is omnidirectional in
                        // a vehicle, and without this the exit-ring
                        // hold would keep passed trees faded behind
                        // the car instead of starting their refade.
                        float dot = PeekAViewMod.cameraPlayerDotTo(object.square);
                        if (!PeekAViewMod.isDotClearlyBehind(dot)
                                && ((inReach && PeekAViewMod.isDotInTreeFadeCone(dot))
                                    || (!aiming && distSq <= exit * exit && fading))) {
                            // Cone gates entry only. A mid-fade tree
                            // holds its membership via the exit ring
                            // alone: near a jumbo the base tile swings
                            // out of the forward cone while the crown
                            // is overhead, and re-fading there would
                            // flicker. The hold pauses while aiming —
                            // the aim reveal must track the cone 1:1,
                            // not leave a ghost trail behind a cursor
                            // sweep.
                            inZone = true;
                            result = true;
                        }
                    }
                } else if (!inVehicle || !aiming) {
                    // Vanilla's SE gate is a coarse screen bbox over
                    // the full mask canvas; its over-trigger used to
                    // stay invisible outside the mask's alpha circle.
                    // With the stencil bypassed every trigger ghosts
                    // the whole tree, so clamp vanilla-true results —
                    // on foot and while driving — to the union of the
                    // occlusion band (crowns lean up-screen toward the
                    // player, 16px per dx+dy step, but pass by quickly
                    // sideways, 32px per |dx-dy| step), the SE
                    // corridor, and our own zone. In-vehicle aiming
                    // falls through untouched.
                    // While aiming, vanilla-true exists in EVERY
                    // direction (the full mask bbox), so the band
                    // needs its SE-quadrant guard — without it the
                    // depth test is a half-open wedge running off to
                    // NW and keeps trees behind the char. NW-side
                    // char cover is the near-zone's job.
                    int hyst = fading
                            ? PeekAViewMod.TREE_FADE_EXIT_HYSTERESIS : 0;
                    boolean covered = (dx >= 0 && dy >= 0
                            && dx + dy <= PeekAViewMod.seFadeDepth(tree.size) + hyst
                            && Math.abs(dx - dy) <= PeekAViewMod.seFadeHalfWidth(tree.size) + hyst)
                            || PeekAViewMod.inNearOverlap(dx, dy, tree.size)
                            || (seCorridor && PeekAViewMod.inSeCorridor(dx, dy, tree.size, fading, seViewDepth));
                    if (!covered) {
                        // Our own zone keeps the tree too: vanilla
                        // flips true for the one-row stripe at its
                        // quadrant boundary, and suppressing there
                        // would pulse a tree the zone entry had just
                        // faded (ghost → opaque → ghost across the
                        // crossing).
                        float dot = PeekAViewMod.cameraPlayerDotTo(object.square);
                        boolean zoneKeep = !PeekAViewMod.isDotClearlyBehind(dot)
                                && ((distSq <= range * range
                                        && PeekAViewMod.isDotInTreeFadeCone(dot))
                                    || (!aiming && distSq <= exit * exit && fading));
                        // On-foot aiming ADDS vanilla's aim reveal on
                        // top of the model: keep along the aim
                        // direction at any distance, cone-restricted
                        // (the char turns with the cursor). Band and
                        // corridor above keep the char himself
                        // uncovered while aiming elsewhere.
                        if (!zoneKeep && !inVehicle && aiming) {
                            zoneKeep = PeekAViewMod.isDotInTreeFadeCone(dot);
                        }
                        if (!zoneKeep) {
                            result = false;
                            inZone = false;
                        }
                    }
                }

                // RMB tracking: while aiming the reveal follows the
                // cursor 1:1 — claimed → floor, unclaimed → opaque,
                // no ghost trail behind a sweep. On release vanilla's
                // ramp fades unclaimed trees back in; only the jumbo
                // crown flip stays hard (the composite path exists
                // only while aiming, so a crown-only fade isn't
                // available on release).
                if (!inVehicle && aiming) {
                    if (result) {
                        if (tree.fadeAlpha > 0.15f) tree.fadeAlpha = 0.15f;
                    } else if (tree.fadeAlpha < 1.0f) {
                        tree.fadeAlpha = 1.0f;
                    }
                }

                // Speed-proportional boost on top of vanilla's
                // alphaStep, down-fade only — refades always run on
                // vanilla's ramp so every tree restores with the same
                // fade. Below MIN_KMH vanilla owns the animation:
                // isTranslucentTree fires 6-10× per tree per frame, so
                // even tiny t³ steps would compound. At/above the cap
                // a single call covers the full range.
                float speed = PeekAViewMod.currentVehicleSpeedKmh;
                float minBoost = PeekAViewMod.TREE_FADE_SNAP_MIN_KMH;
                if (speed > minBoost && inZone && tree.fadeAlpha > 0.15f) {
                    // 0.15 matches vanilla 42.20's outdoor fade floor
                    // so the snap converges where the alphaStep path
                    // would.
                    float minAlpha = 0.15f;
                    float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH;
                    float t = speed >= cap ? 1.0f : (speed - minBoost) / (cap - minBoost);
                    float step = (1.0f - minAlpha) * t * t * t;
                    tree.fadeAlpha -= step;
                    if (tree.fadeAlpha < minAlpha) tree.fadeAlpha = minAlpha;
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

                // Commit opened=true right after the captures so any
                // throw further down still hits exit cleanup; each
                // cleanup branch is gated on its own step-flag.
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
                    savedCurrent = FakeWindow.readCurrentField(ffs.camChar);
                    ffs.camChar.setCurrent(ffs.fakeSquare);
                    currentSwapped = true;
                }

                // Flag BEFORE writeFakePos (ordering invariant, see
                // FakeWindow). Rollback on Reflection failure.
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

                // De-mutate order: writeRealPos BEFORE flag-clear
                // (ordering invariant, see FakeWindow).
                if (ffs.camChar != null) {
                    savedCurrent = FakeWindow.readCurrentField(ffs.camChar);
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
                    // Re-mutate: flag BEFORE writeFakePos (see FakeWindow).
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
