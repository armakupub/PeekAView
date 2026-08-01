package pzmod.peekaview;

import java.util.ArrayList;
import java.util.Arrays;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.math.PZMath;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;

public class Patch_IsoCell {

    // Expands the POI fan that seeds building cutaway. Re-implements
    // vanilla's raster+diamond shape scaled to PeekAViewMod.range.
    @Patch(className = "zombie.iso.IsoCell",
           methodName = "GetSquaresAroundPlayerSquare")
    public static class Patch_GetSquaresAroundPlayerSquare {

        private static final int MAX_RADIUS = PeekAViewMod.MAX_RANGE;

        // Vanilla clips its 10-wide raster at half-width 4.5 (radius
        // 5); mirroring the ratio keeps each +1 slider step growing by
        // a vanilla-shaped ring. A constant sized for MAX_RADIUS never
        // clipped at small radii and emitted ~4× vanilla's tile count.
        private static final float DIAMOND_HALF_WIDTH_PER_RADIUS = 4.5f / 5.0f;

        // Inside this box around the player we mirror vanilla exactly
        // (no wall-adjacency / LOS filter). 5 covers vanilla's 10x10
        // half-width-4.5 diamond envelope.
        private static final int VANILLA_KEEP_RADIUS = 5;

        // Cache stores coordinates, not IsoGridSquare refs: squares
        // live in a pool and WorldReuser.discard can reassign a ref to
        // a different (x,y,z) asynchronously. Per-player slots because
        // split-screen alternates playerIndex within one wall-clock
        // frame; a single shared slot thrashes to 0% hit rate.
        //
        // Fields are public because @Patch.OnEnter inlines the advice
        // into IsoCell's access context — private fields throw
        // IllegalAccessError at runtime. Compile-time constants stay
        // private (javac folds them into literals).
        private static final int MAX_RASTER_SIZE = MAX_RADIUS * 2 + 2;
        private static final int MAX_COORDS = MAX_RASTER_SIZE * MAX_RASTER_SIZE;
        private static final int MAX_PLAYERS = IsoPlayer.MAX;

        public static final IsoCell[] cachedCell = new IsoCell[MAX_PLAYERS];
        public static final int[] cachedPxFloor = new int[MAX_PLAYERS];
        public static final int[] cachedPyFloor = new int[MAX_PLAYERS];
        public static final int[] cachedZ = new int[MAX_PLAYERS];
        public static final int[][] cachedLeftX  = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[][] cachedLeftY  = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[] cachedLeftCount = new int[MAX_PLAYERS];
        public static final int[][] cachedRightX = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[][] cachedRightY = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[] cachedRightCount = new int[MAX_PLAYERS];

        static {
            invalidateCache();
        }

        // Called from PeekAViewMod.setRange on slider change.
        public static void invalidateCache() {
            Arrays.fill(cachedPxFloor, Integer.MIN_VALUE);
            Arrays.fill(cachedPyFloor, Integer.MIN_VALUE);
            Arrays.fill(cachedZ, Integer.MIN_VALUE);
        }

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoCell cell,
                                    @Patch.Argument(0) IsoPlayer player,
                                    @Patch.Argument(1) IsoGridSquare square,
                                    @Patch.Argument(2) ArrayList outLeft,
                                    @Patch.Argument(3) ArrayList outRight) {
            try {
                if (!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()) return false;
                if (cell == null || player == null || square == null) return false;

                int playerIndex = IsoCamera.frameState.playerIndex;
                if (playerIndex < 0 || playerIndex >= MAX_PLAYERS) return false;

                // Indoor and slider-at-MIN both fall through to
                // vanilla's own raster, unmodified.
                if (PeekAViewMod.isCameraPlayerIndoor()) return false;
                if (PeekAViewMod.range <= PeekAViewMod.MIN_RANGE) return false;

                float px = player.getX();
                float py = player.getY();
                int pxFloor = PZMath.fastfloor(px);
                int pyFloor = PZMath.fastfloor(py);
                int z = square.getZ();

                int[] leftX = cachedLeftX[playerIndex];
                int[] leftY = cachedLeftY[playerIndex];
                int[] rightX = cachedRightX[playerIndex];
                int[] rightY = cachedRightY[playerIndex];
                int leftCount = cachedLeftCount[playerIndex];
                int rightCount = cachedRightCount[playerIndex];

                if (cell == cachedCell[playerIndex]
                        && pxFloor == cachedPxFloor[playerIndex]
                        && pyFloor == cachedPyFloor[playerIndex]
                        && z == cachedZ[playerIndex]) {
                    for (int i = 0; i < leftCount; ++i) {
                        IsoGridSquare sq = cell.getGridSquare(leftX[i], leftY[i], z);
                        if (sq != null) outLeft.add(sq);
                    }
                    for (int i = 0; i < rightCount; ++i) {
                        IsoGridSquare sq = cell.getGridSquare(rightX[i], rightY[i], z);
                        if (sq != null) outRight.add(sq);
                    }
                    return true;
                }

                leftCount = 0;
                rightCount = 0;

                // Snapshot once per miss so bounds stay consistent if
                // Lua flips the slider mid-frame.
                int radius = PeekAViewMod.range;
                if (radius < PeekAViewMod.MIN_RANGE) radius = PeekAViewMod.MIN_RANGE;
                if (radius > MAX_RADIUS) radius = MAX_RADIUS;
                int rasterSize = radius * 2 + 2;
                float diamondHalfWidth = (float) radius * DIAMOND_HALF_WIDTH_PER_RADIUS;

                int startX = PZMath.fastfloor(px - (float) radius);
                int startY = PZMath.fastfloor(py - (float) radius);

                for (int y = startY; y < startY + rasterSize; ++y) {
                    for (int x = startX; x < startX + rasterSize; ++x) {
                        if (x < pxFloor && y < pyFloor) continue;
                        if (x == pxFloor && y == pyFloor) continue;
                        float deltaX = (float) x - px;
                        float deltaY = (float) y - py;
                        if (!(deltaY < deltaX + diamondHalfWidth)) continue;
                        if (!(deltaY > deltaX - diamondHalfWidth)) continue;
                        IsoGridSquare iterSquare = cell.getGridSquare(x, y, z);
                        if (iterSquare == null) continue;

                        // Outside the vanilla envelope: drop squares
                        // that can't seed cutaway (not wall-adjacent)
                        // and POIs behind the first wall/window/door —
                        // extended cutaway, not see-through-rooms.
                        int dx = x - pxFloor; if (dx < 0) dx = -dx;
                        int dy = y - pyFloor; if (dy < 0) dy = -dy;
                        if (dx > VANILLA_KEEP_RADIUS || dy > VANILLA_KEEP_RADIUS) {
                            if (!isNearWall(iterSquare, cell, x, y, z)) continue;
                            if (!hasLineOfSight(cell, pxFloor, pyFloor, x, y, z)) continue;
                        }

                        if (deltaY >= deltaX) {
                            leftX[leftCount] = x;
                            leftY[leftCount] = y;
                            leftCount++;
                            outLeft.add(iterSquare);
                        }
                        if (deltaY <= deltaX) {
                            rightX[rightCount] = x;
                            rightY[rightCount] = y;
                            rightCount++;
                            outRight.add(iterSquare);
                        }
                    }
                }

                cachedCell[playerIndex] = cell;
                cachedPxFloor[playerIndex] = pxFloor;
                cachedPyFloor[playerIndex] = pyFloor;
                cachedZ[playerIndex] = z;
                cachedLeftCount[playerIndex] = leftCount;
                cachedRightCount[playerIndex] = rightCount;
                return true;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_GetSquaresAroundPlayerSquare enter error", t);
                return false;
            }
        }

        // PZ stores walls on the owning tile: N/W on the square itself,
        // S = y+1's N, E = x+1's W. Three lookups cover all four sides.
        // Public for advice-inlining access context.
        public static boolean isNearWall(IsoGridSquare sq, IsoCell cell, int x, int y, int z) {
            if (sq.getWall() != null) return true;
            IsoGridSquare s;
            if ((s = cell.getGridSquare(x + 1, y, z)) != null && s.getWall() != null) return true;
            if ((s = cell.getGridSquare(x, y + 1, z)) != null && s.getWall() != null) return true;
            return false;
        }

        // Bresenham walk from (px,py) to (tx,ty) on z. Drops on the
        // first wall/window/door crossing (zero crossings only).
        public static boolean hasLineOfSight(IsoCell cell, int px, int py, int tx, int ty, int z) {
            int dx = tx - px; if (dx < 0) dx = -dx;
            int dy = ty - py; if (dy < 0) dy = -dy;
            int sx = px < tx ? 1 : -1;
            int sy = py < ty ? 1 : -1;
            int err = dx - dy;
            int cx = px, cy = py;
            // Cap iterations defensively — worst case ≈ RADIUS*2 steps.
            for (int guard = 0; guard < 64; ++guard) {
                if (cx == tx && cy == ty) return true;
                int prevX = cx, prevY = cy;
                int e2 = err * 2;
                if (e2 > -dy) { err -= dy; cx += sx; }
                if (e2 < dx) { err += dx; cy += sy; }
                if (crossesWall(cell, prevX, prevY, cx, cy, z)) {
                    return false;
                }
            }
            return true;
        }

        // Bresenham steps are axis-aligned — 4 directions. Windows and
        // door-walls count as crossings (else a window in the nearest
        // wall lets the walk slip into the room behind it).
        public static boolean crossesWall(IsoCell cell, int fx, int fy, int tx, int ty, int z) {
            if (tx > fx) {
                IsoGridSquare to = cell.getGridSquare(tx, ty, z);
                return to != null && hasWestBarrier(to);
            }
            if (tx < fx) {
                IsoGridSquare from = cell.getGridSquare(fx, fy, z);
                return from != null && hasWestBarrier(from);
            }
            if (ty > fy) {
                IsoGridSquare to = cell.getGridSquare(tx, ty, z);
                return to != null && hasNorthBarrier(to);
            }
            if (ty < fy) {
                IsoGridSquare from = cell.getGridSquare(fx, fy, z);
                return from != null && hasNorthBarrier(from);
            }
            return false;
        }

        public static boolean hasNorthBarrier(IsoGridSquare sq) {
            return sq.has(IsoFlagType.WallN)
                || sq.has(IsoFlagType.WindowN)
                || sq.has(IsoFlagType.DoorWallN)
                || sq.has(IsoFlagType.doorN);
        }

        public static boolean hasWestBarrier(IsoGridSquare sq) {
            return sq.has(IsoFlagType.WallW)
                || sq.has(IsoFlagType.WindowW)
                || sq.has(IsoFlagType.DoorWallW)
                || sq.has(IsoFlagType.doorW);
        }
    }

    // == Stair feature ==
    // Restore frameState.camCharacterSquare to realSquare for the
    // duration of IsoCell.update. updateWeatherFx runs inside, and
    // IsoWeatherFX.update reads camCharacterSquare.has(exterior) to
    // gate the indoorsAlphaMod ramp; a fakeSquare leftover from the
    // prior render — common on outdoor stair → upper-floor landing
    // tiles that aren't marked exterior — would otherwise ramp rain
    // alpha to zero mid-climb. RECENT_FRAMES > 0 because frameCount
    // has already been bumped by GameWindow.frameStep when update()
    // runs, so the strict FakeWindow.isReady equality check misses.
    @Patch(className = "zombie.iso.IsoCell", methodName = "update")
    public static class Patch_update {

        private static final int RECENT_FRAMES = 2;

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                int idx = fs.playerIndex;
                if (idx < 0 || idx >= FakeWindow.MAX_PLAYERS) return;
                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs == null || ffs.realSquare == null) return;
                if ((fs.frameCount - ffs.frameCounter) > RECENT_FRAMES) return;
                if (fs.camCharacterSquare == ffs.realSquare) return;
                savedSquare = fs.camCharacterSquare;
                fs.camCharacterSquare = ffs.realSquare;
                opened = true;
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoCell.update enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            if (!opened) return;
            try {
                IsoCamera.frameState.camCharacterSquare = savedSquare;
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoCell.update exit failed", t);
            }
        }
    }

    // == Stair feature ==
    // Non-FBO render-pass swap: same idea as Patch_FBORenderCell.Patch_renderInternal
    // but on the legacy IsoCell path. Active only when fboRenderChunk is off.
    @Patch(className = "zombie.iso.IsoCell", methodName = "renderInternal")
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

                // Mirror of FBORenderCell.Patch_renderInternal; commit
                // opened=true right after the captures.
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
                    sqSwapped = true;
                    fake.room = floor.room;
                    fake.roomId = floor.getRoomID();
                    if (savedExterior) {
                        fake.getProperties().unset(IsoFlagType.exterior);
                    }
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoCell.renderInternal enter failed", t);
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

    // doBuildingInternal reads IsoCamera.getCameraCharacterZ(), which
    // calls isoCameraGameCharacter.getZ() — a dynamic getter, not the
    // frameState field. Arm renderingFake for the method's duration so
    // Patch_getZ returns fake, keeping buildZ consistent with the
    // pickedTile projection from Patch_UIManager. Without this, the
    // bRender=false click-trigger from UIManager.update reads real-Z
    // and the WalkTo target lands on a mid-air square.
    @Patch(className = "zombie.iso.IsoCell", methodName = "doBuildingInternal")
    public static class Patch_doBuildingInternal {
        @Patch.OnEnter
        public static void enter(@Patch.Local("opened") boolean opened) {
            FakeFrameState ffs = FakeWindow.get(0);
            if (ffs == null || !ffs.stairLatchArmed) return;
            if (IsoCamera.frameState.frameCount - ffs.lastStrictActivationFrame > 3) return;
            if (FakeWindow.renderingFake.get() != null) return;
            FakeWindow.renderingFake.set(ffs);
            opened = true;
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Local("opened") boolean opened) {
            if (!opened) return;
            FakeWindow.renderingFake.remove();
        }
    }
}
