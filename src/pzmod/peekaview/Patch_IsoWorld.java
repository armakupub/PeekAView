package pzmod.peekaview;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.GameTime;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.Vector3;

// Stair feature — outer render-pass entrypoint. Decides per frame
// whether the upper floor should be rendered, and fills FakeWindow
// state for the inner patches (Patch_FBORenderCell.Patch_renderInternal,
// Patch_IsoCell.Patch_renderInternal, Patch_LightingJNI etc.) to consume.
public class Patch_IsoWorld {

    // 0.5 s @ 60 FPS of grace after a strict pass — rides out
    // animation key-pose oscillations (head bob, idle sway) without
    // re-triggering the cutaway state machine per frame.
    private static final int HYSTERESIS_FRAMES = 30;

    private static Field FIELD_DRAW_WORLD;

    static {
        try {
            FIELD_DRAW_WORLD = IsoWorld.class.getDeclaredField("drawWorld");
            FIELD_DRAW_WORLD.trySetAccessible();
        } catch (NoSuchFieldException e) {
            FIELD_DRAW_WORLD = null;
        }
    }

    public static boolean readDrawWorld(IsoWorld world) {
        if (FIELD_DRAW_WORLD == null) return true;
        try {
            return FIELD_DRAW_WORLD.getBoolean(world);
        } catch (Exception e) {
            return true;
        }
    }

    @Patch(className = "zombie.iso.IsoWorld", methodName = "renderInternal")
    public static class Patch_renderInternal {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoWorld self) {
            try {
                computeFake(self);
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: Patch_IsoWorld.renderInternal failed", t);
            }
        }

        public static void computeFake(IsoWorld self) {
            // Hard gates before any FakeWindow mutation — downstream
            // patches inherit them via FakeWindow.isReady going false.
            if (!PeekAViewMod.enabled) return;
            if (!PeekAViewMod.stairEnabled) return;
            if (!PeekAViewMod.isPeekAViewActive()) return;
            if (PeekAViewMod.isExternalStairFeatureActive()) return;

            // Applicability checks — fail without hysteresis.
            IsoGameCharacter camChar = IsoCamera.getCameraCharacter();
            if (camChar == null) return;
            if (!readDrawWorld(self)) return;

            IsoCamera.FrameState fs = IsoCamera.frameState;

            // Pause freeze: render frames keep advancing during pause
            // while the game thread stops, so the frame-based
            // hysteresis windows would expire and blank the upper
            // floor + char sprite mid-climb. Bump frameCounter only;
            // the <= 1 gate thaws just-active windows, stale ones fall
            // through to the normal flow.
            if (GameTime.isGamePaused()) {
                int idxP = fs.playerIndex;
                FakeFrameState ffsP = FakeWindow.get(idxP);
                if (ffsP != null && (fs.frameCount - ffsP.frameCounter) <= 1) {
                    ffsP.frameCounter = fs.frameCount;
                    return;
                }
            }

            IsoGridSquare square = fs.camCharacterSquare;
            if (square == null) return;
            if (camChar.getVehicle() != null) return;
            if (!camChar.hasActiveModel()) return;

            int playerIndex = fs.playerIndex;
            FakeFrameState ffs = FakeWindow.get(playerIndex);
            boolean recentlyActive = ffs != null
                    && ffs.lastStrictActivationFrame >= 0
                    && (fs.frameCount - ffs.lastStrictActivationFrame) <= HYSTERESIS_FRAMES;

            boolean onStair = square.HasStairs();
            if (!onStair && ffs != null) {
                ffs.stairLatchArmed = false;
                // Landing arrival: clip the hysteresis, otherwise the
                // fake pass keeps the upper floor visible ~30 frames
                // after stepping off the top of the stairs.
                if (square.hasFloorAtTopOfStairs()
                        && ffs.fakeSquare != null
                        && square.z >= ffs.fakeSquare.z) {
                    ffs.lastStrictActivationFrame = -1;
                    ffs.lastZIncreaseFrame = -1;
                    recentlyActive = false;
                }
            }

            float charX = fs.camCharacterX;
            float charY = fs.camCharacterY;
            float charZ = fs.camCharacterZ;

            // Descent release + recent-ascent tracking; the why lives
            // on the FakeFrameState fields.
            boolean ascendingRecently = false;
            if (onStair && ffs != null && ffs.stairLatchArmed) {
                if (charZ > ffs.peakCharZ) {
                    ffs.peakCharZ = charZ;
                    ffs.lastZIncreaseFrame = fs.frameCount;
                } else if (ffs.peakCharZ - charZ > 0.05f) {
                    ffs.stairLatchArmed = false;
                }
                ascendingRecently = ffs.lastZIncreaseFrame >= 0
                        && (fs.frameCount - ffs.lastZIncreaseFrame) <= HYSTERESIS_FRAMES;
            }
            boolean stairLatch = onStair && ascendingRecently && ffs != null && ffs.stairLatchArmed;

            // Soft checks — wobble-prone boundaries; hysteresis decides.
            // Gate on onStair upfront: HasElevatedFloor is true on most
            // indoor tiles, and without the gate the heading-cone keeps
            // refreshing lastStrictActivationFrame on regular floor
            // tiles after descent, holding the window open off-stair.
            boolean strictPass = onStair;
            if (strictPass && (float) PZMath.fastfloor(charZ + 0.55f) < charZ) strictPass = false;
            if (strictPass && !square.HasElevatedFloor()) strictPass = false;

            boolean stairsNorth = square.HasStairsNorth();
            float heading = PZMath.wrap(
                    camChar.getLookAngleRadians() - (stairsNorth ? (float) Math.PI : 1.5707964f),
                    (float) Math.PI * 2);
            float cone = Math.max(0.5f, 1.1780972f * (1.8181818f * PZMath.frac(charZ + 0.55f)));
            if (strictPass && heading > (float) Math.PI + cone && heading < (float) Math.PI * 2 - cone) {
                strictPass = false;
            }

            if (!strictPass && !recentlyActive && !stairLatch) return;

            // Fresh floor/target squares if the geometry is there;
            // last frame's otherwise (only inside the hysteresis window).
            IsoGridSquare floorSquare = null;
            IsoGridSquare targetSquare = null;
            boolean stairTop = false;
            boolean stairMid = false;

            if (square.HasElevatedFloor()) {
                stairTop = square.has(IsoObjectType.stairsTN) || square.has(IsoObjectType.stairsTW);
                stairMid = square.has(IsoObjectType.stairsMN) || square.has(IsoObjectType.stairsMW);
                int topOffset = stairTop ? 1 : (stairMid ? 2 : 3);
                floorSquare = camChar.getCell().getGridSquare(
                        square.x - (stairsNorth ? 0 : topOffset),
                        square.y - (stairsNorth ? topOffset : 0),
                        square.z + 1);
                // Prefer floorSquare (= landing the player walks onto)
                // over the cell directly above the stair tile, which
                // can sit one tile inside the building at corner stairs.
                IsoGridSquare upperSquare = square.getCell().getGridSquare(square.x, square.y, square.z + 1);
                targetSquare = floorSquare != null ? floorSquare : upperSquare;
            }
            if (floorSquare == null || targetSquare == null) {
                if ((!recentlyActive && !stairLatch) || ffs.floorSquare == null || ffs.fakeSquare == null) return;
                floorSquare = ffs.floorSquare;
                targetSquare = ffs.fakeSquare;
                strictPass = false;
            }

            // Head bone = actual eye height through the stair animation.
            // Snap onto last-frame value at sub-threshold deltas to
            // avoid jitter at the fastfloor crossing.
            Vector3 headPos = new Vector3();
            zombie.CombatManager.getBoneWorldPos((IsoMovingObject) camChar, "Bip01_Head", headPos);
            headPos.z += 0.05f;
            float headZ = (ffs != null && Math.abs(headPos.z - ffs.lastViewpointZ) <= 0.02f)
                    ? ffs.lastViewpointZ
                    : headPos.z;
            if (PZMath.fastfloor(headZ) < targetSquare.z) {
                if (!recentlyActive && !stairLatch) return;
                strictPass = false;
            }

            ffs = FakeWindow.getOrAllocate(playerIndex);

            // Stair-top lighting on, except if a zombie sits on a nearby stair.
            boolean renderLighting = stairTop;
            if (renderLighting && PZMath.fastfloor(charZ + 0.11f) < targetSquare.z) {
                outer:
                for (int y = square.y - 1; y <= square.y + 1; ++y) {
                    for (int x = square.x - 1; x <= square.x + 1; ++x) {
                        IsoGridSquare s = camChar.getCell().getGridSquare(x, y, square.z);
                        if (s != null && s.HasStairs() && s.getZombie() != null) {
                            renderLighting = false;
                            break outer;
                        }
                    }
                }
            }

            ffs.camChar = camChar;
            ffs.realPos.set(charX, charY, charZ);
            ffs.realSquare = square;
            ffs.floorSquare = floorSquare;
            ffs.fakeSquare = targetSquare;
            ffs.landingRoomId = floorSquare.getRoomID();
            ffs.fakePos.set(charX, charY, (float) targetSquare.getZ());
            ffs.lastViewpointZ = headPos.z;
            ffs.renderLighting = renderLighting;
            ffs.frameCounter = fs.frameCount;
            if (strictPass) {
                ffs.lastStrictActivationFrame = fs.frameCount;
                if (onStair) {
                    if (!ffs.stairLatchArmed) {
                        // First arm of this climb — don't track a
                        // prior climb's stale peak.
                        ffs.peakCharZ = charZ;
                        ffs.lastZIncreaseFrame = fs.frameCount;
                    }
                    ffs.stairLatchArmed = true;
                }
            }
        }
    }
}
