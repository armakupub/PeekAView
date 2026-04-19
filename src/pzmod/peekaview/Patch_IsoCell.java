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

public class Patch_IsoCell {

    // Expands the POI fan that seeds building cutaway. Re-implements
    // vanilla's raster+diamond shape scaled to PeekAViewMod.range.
    @Patch(className = "zombie.iso.IsoCell",
           methodName = "GetSquaresAroundPlayerSquare")
    public static class Patch_GetSquaresAroundPlayerSquare {

        private static final int MAX_RADIUS = PeekAViewMod.MAX_RANGE;
        private static final float DIAMOND_HALF_WIDTH = 22.5f;

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
                if (!PeekAViewMod.isActiveForCurrentRenderPlayer()) return false;
                if (cell == null || player == null || square == null) return false;

                int playerIndex = IsoCamera.frameState.playerIndex;
                if (playerIndex < 0 || playerIndex >= MAX_PLAYERS) return false;

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

                int startX = PZMath.fastfloor(px - (float) radius);
                int startY = PZMath.fastfloor(py - (float) radius);

                for (int y = startY; y < startY + rasterSize; ++y) {
                    for (int x = startX; x < startX + rasterSize; ++x) {
                        if (x < pxFloor && y < pyFloor) continue;
                        if (x == pxFloor && y == pyFloor) continue;
                        float deltaX = (float) x - px;
                        float deltaY = (float) y - py;
                        if (!(deltaY < deltaX + DIAMOND_HALF_WIDTH)) continue;
                        if (!(deltaY > deltaX - DIAMOND_HALF_WIDTH)) continue;
                        IsoGridSquare iterSquare = cell.getGridSquare(x, y, z);
                        if (iterSquare == null) continue;

                        // Outside vanilla envelope: drop non-wall-adjacent
                        // squares (each emitted POI seeds a full
                        // cutawayVisit pass downstream, so raster is only
                        // useful adjacent to a wall) and POIs behind the
                        // first wall/window/door from the player (keep
                        // feature scope: extended cutaway, not see-through-
                        // rooms).
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
}
