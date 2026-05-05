package pzmod.peekaview;

import java.util.ArrayList;
import java.util.Arrays;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoUtils;
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
                if (!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()) return false;
                if (cell == null || player == null || square == null) return false;

                int playerIndex = IsoCamera.frameState.playerIndex;
                if (playerIndex < 0 || playerIndex >= MAX_PLAYERS) return false;

                // Slider at MIN_RANGE = vanilla cutaway. Fall through
                // so vanilla's 10×10 raster + diamond-half-width-4.5
                // shape runs unmodified. Our reimplementation diverges
                // slightly (rasterSize = radius*2+2 adds an extra
                // ring; DIAMOND_HALF_WIDTH = 22.5 is sized for max
                // radius and doesn't clip at small radii), so even at
                // radius==5 the extended path emits tiles vanilla
                // would not. Pass-through guarantees 1:1 vanilla
                // behavior at the user's "off / vanilla" slider end.
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

    // Vanilla DrawStencilMask renders a circular alpha texture
    // (media/mask_circledithernew.png) once per frame centered on the
    // player. The stencil buffer it writes caps where translucent
    // passes (tree fade, wall cutaway) can render on screen — outside
    // the mask, nothing. Vanilla's size matches the ~5-tile cutaway
    // raster, so our N/W/NE/SW tree fades are invisible beyond that.
    //
    // We keep vanilla's circle unchanged (accurate close range, cheap)
    // and after it runs, add additional stencil coverage per LOS-lit
    // tile in the three fade quadrants relative to the player, up to
    // PeekAViewMod.treeFadeRange. Stencil is additive via GL_REPLACE with
    // ref 128, so extra writes layer on top of vanilla's circle. The
    // resulting mask shape matches the character's actual line of
    // sight — no fade through walls, no ghost fade in unseen areas.
    @Patch(className = "zombie.iso.IsoCell",
           methodName = "DrawStencilMask")
    public static class Patch_DrawStencilMask {

        @Patch.OnExit
        public static void exit(@Patch.This IsoCell cell) {
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                // Outdoor-only: indoor we don't extend the stencil
                // mask. Mirrors the gate in Patch_isTranslucentTree
                // so the renderFlag and stencil-coverage halves of
                // the tree-fade feature stay in sync.
                if (PeekAViewMod.isCameraPlayerIndoor()) return;

                // Use IsoCamera.frameState.playerIndex (currently
                // rendering player) to stay consistent with every other
                // patch in the mod. IsoPlayer.getPlayerIndex() returns
                // the local active player and would write the stencil
                // mask for the wrong player in split-screen.
                int pidx = IsoCamera.frameState.playerIndex;
                if (pidx < 0 || pidx >= IsoPlayer.MAX) return;
                IsoPlayer player = IsoPlayer.players[pidx];
                if (player == null) return;

                Texture tex2 = Texture.getSharedTexture("media/mask_circledithernew.png");
                if (tex2 == null) return;

                // player.getX/Y/Z, not getCurrentSquare(): the latter is
                // null while the player is in a vehicle, which would
                // skip the stencil extension and leave faded trees
                // outside vanilla's 5-tile circle invisible.
                int px = PZMath.fastfloor(player.getX());
                int py = PZMath.fastfloor(player.getY());
                int pz = PZMath.fastfloor(player.getZ());
                int range = PeekAViewMod.treeFadeRange;
                int ts = Core.tileScale;

                // Per-tile mask geometry. 128×256 overshoots the iso-
                // tile footprint (64×32) on purpose: trees extend
                // upward on screen from their base tile, so we need
                // stencil coverage ABOVE the tile base for the tree
                // sprite's GL_EQUAL pass to pass the stencil test.
                // tileFootprintYOffset = 192 places the patch 6 tile-
                // heights above the floor, sized to cover any PZ
                // tree sprite's crown.
                int renderW = 128 * ts;
                int renderH = 256 * ts;
                int halfW = renderW / 2;
                int tileFootprintYOffset = 192 * ts;

                IndieGL.glStencilMask(255);
                IndieGL.enableStencilTest();
                IndieGL.enableAlphaTest();
                IndieGL.glAlphaFunc(516, 0.1f);
                IndieGL.glStencilFunc(519, 128, 255);
                IndieGL.glStencilOp(7680, 7680, 7681);
                IndieGL.glColorMask(false, false, false, false);

                float offX = IsoCamera.getOffX();
                float offY = IsoCamera.getOffY();

                // Vanilla's circular stencil already covers roughly the
                // inner 4 tiles around the player. Skipping that area
                // avoids overlapping our per-tile dither with vanilla's
                // gradient — the two independent patterns produce a
                // flickery moiré at sub-pixel camera shifts.
                int vanillaSkip = PeekAViewMod.MIN_RANGE - 1;

                for (int dy = -range; dy <= range; ++dy) {
                    for (int dx = -range; dx <= range; ++dx) {
                        int adx = dx < 0 ? -dx : dx;
                        int ady = dy < 0 ? -dy : dy;
                        if (adx + ady > range) continue;
                        if (adx <= vanillaSkip && ady <= vanillaSkip) continue;

                        // Full diamond — every tile in range (origin
                        // excluded by the vanillaSkip check above).
                        // The LOS gate (`sq.isCanSee(pidx)` below)
                        // suppresses stencil writes for tiles outside
                        // the rendering player's visibility cone, so
                        // NW/N-axis/W-axis tiles only get coverage
                        // when the player is actually looking that
                        // way — no ghost-fade in unseen forest.

                        int tx = px + dx;
                        int ty = py + dy;
                        IsoGridSquare sq = cell.getGridSquare(tx, ty, pz);
                        if (sq == null) continue;
                        if (!sq.isCanSee(pidx)) continue;

                        float sx = IsoUtils.XToScreen(tx, ty, pz, 0) - offX;
                        float sy = IsoUtils.YToScreen(tx, ty, pz, 0) - offY;
                        tex2.renderstrip((int) sx - halfW, (int) sy - tileFootprintYOffset,
                                renderW, renderH, 1.0f, 1.0f, 1.0f, 1.0f, null);
                    }
                }

                IndieGL.glColorMask(true, true, true, true);
                IndieGL.glStencilFunc(519, 0, 255);
                IndieGL.glStencilOp(7680, 7680, 7680);
                IndieGL.glStencilMask(127);
                IndieGL.glAlphaFunc(519, 0.0f);
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_DrawStencilMask exit error", t);
            }
        }
    }
}
