package pzmod.peekaview;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Patch;

import java.util.List;

import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.sprite.IsoSprite;

public class Patch_FBORenderCutaways {

    // Skip redundant cutawayVisit calls within the same render pass.
    // Vanilla loops every POI and re-traverses the same cutaway walls;
    // cutawayVisit's output does not depend on poiSquare. Identity tuple
    // is (frameCount, playerIndex) — same field vanilla uses for
    // "once-per-frame" patterns (splashFrameNum etc).
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCutaways",
           methodName = "cutawayVisit")
    public static class Patch_cutawayVisit {

        public static int lastPlayerIndex = Integer.MIN_VALUE;
        public static int lastFrameCount = Integer.MIN_VALUE;

        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            try {
                if (!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()) return false;

                int playerIndex = IsoCamera.frameState.playerIndex;
                int frameCount = IsoCamera.frameState.frameCount;
                if (playerIndex == lastPlayerIndex && frameCount == lastFrameCount) {
                    return true;
                }
                lastPlayerIndex = playerIndex;
                lastFrameCount = frameCount;
                return false;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_cutawayVisit enter error", t);
                return false;
            }
        }
    }

    // Per-cluster distance gate on OrphanStructures.shouldCutaway.
    // Vanilla uses a GLOBAL cell flag OR-accumulated across every POI,
    // so one player-built cluster anywhere on screen forces cutaway on
    // every on-screen cluster at level>camZ — which combined with the
    // adjacency-kill path in shouldRenderBuildingSquare:682 hides
    // vanilla upper-floor walls far from the actual orphan tile. We
    // override shouldCutaway=true back to false when the cluster has no
    // orphan tile within RADIUS_TILES of the rendering player.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCutaways$OrphanStructures",
           methodName = "shouldCutaway")
    public static class Patch_shouldCutaway {

        private static final int RADIUS_TILES = 6;
        private static final int RADIUS_SQ = RADIUS_TILES * RADIUS_TILES;

        public static volatile Field FIELD_CHUNK_LEVEL_DATA;
        public static volatile Field FIELD_IS_ORPHAN_SQUARE;
        public static volatile boolean initialized;
        public static volatile boolean initFailed;

        public static void tryInit() {
            if (initialized || initFailed) return;
            try {
                Class<?> osClass = Class.forName(
                        "zombie.iso.fboRenderChunk.FBORenderCutaways$OrphanStructures");
                FIELD_CHUNK_LEVEL_DATA = Accessor.findField(osClass, "chunkLevelData");
                FIELD_IS_ORPHAN_SQUARE = Accessor.findField(osClass, "isOrphanStructureSquare");
                if (FIELD_CHUNK_LEVEL_DATA == null || FIELD_IS_ORPHAN_SQUARE == null) {
                    initFailed = true;
                    PeekAViewMod.trace("Patch_shouldCutaway init failed: missing fields");
                    return;
                }
                initialized = true;
            } catch (Throwable t) {
                initFailed = true;
                PeekAViewMod.trace("Patch_shouldCutaway init error", t);
            }
        }

        // Climb-stab radius. Cluster cutaway is suppressed during
        // isClimbing only for clusters whose orphan tiles include at
        // least one non-roof tile within this radius of the player —
        // far clusters keep vanilla behavior, roof clusters keep
        // cutting so the player can see into the room above.
        public static final int NEAR_PLAYER_RADIUS_TILES = 8;
        public static final int NEAR_PLAYER_RADIUS_SQ =
                NEAR_PLAYER_RADIUS_TILES * NEAR_PLAYER_RADIUS_TILES;

        @Patch.OnExit
        public static void exit(@Patch.This Object self,
                                @Patch.Return(readOnly = false) boolean result) {
            if (!result) return;
            try {
                if (!PeekAViewMod.enabled) return;
                if (!PeekAViewMod.cutawayEnabled) return;
                // Climb-stab runs independently of fixB42Adjacency:
                // vanilla cluster cutaway oscillates per frame during
                // stair climbs because checkOrphanStructures reads a
                // camCharacterZ the stair feature swaps in and out
                // mid-frame, producing mid-deck flicker even when the
                // B42 fix is off.
                int pIdx = IsoCamera.frameState.playerIndex;
                if (Patch_IsoObject.isClimbing(pIdx)) {
                    if (!initialized) tryInit();
                    if (initialized && clusterHasNonRoofOrphanNearPlayer(self)) {
                        result = false;
                        return;
                    }
                }
                // Master switch + cutaway-section enable + own checkbox.
                // No aimStanceOnly gate: the vanilla B42 adjacency bug
                // exists at vanilla cutaway range too, so the fix must
                // run regardless of stance — otherwise the bug pops
                // in/out with aiming.
                if (!PeekAViewMod.fixB42Adjacency) return;
                // Outdoor-only: indoor we let vanilla cutaway flow
                // through to avoid B42-fix bleed-throughs of
                // player-built tiles, which only manifest when the
                // player can see them from inside a room.
                if (PeekAViewMod.isCameraPlayerIndoor()) return;
                if (!initialized) tryInit();
                if (!initialized) return;
                // Skip the override when the cluster contains
                // hoppable orphan tiles (player-built railings,
                // low fences). Plain HoppableN/W has no per-object
                // sprite cut, so the only mechanism that hides them
                // when the player walks under them is the cluster
                // playerInRange path. Forcing shouldCutaway=false
                // for those clusters leaves railings drawn on top
                // of the character. Adjacent vanilla walls remain
                // protected by Patch_isAdjacentToOrphanStructure,
                // so letting the cluster go in-range here only
                // affects the orphan tiles themselves.
                if (clusterContainsHoppable(self)) return;
                if (isTooFarFromPlayer(self)) result = false;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_shouldCutaway exit error", t);
            }
        }

        public static boolean clusterContainsHoppable(Object orphanStructures) {
            long mask = Accessor.tryGet(orphanStructures, FIELD_IS_ORPHAN_SQUARE, 0L);
            if (mask == 0L) return false;
            Object cldRaw = Accessor.tryGet(orphanStructures, FIELD_CHUNK_LEVEL_DATA, null);
            if (!(cldRaw instanceof FBORenderCutaways.ChunkLevelData)) return false;
            FBORenderCutaways.ChunkLevelData cld = (FBORenderCutaways.ChunkLevelData) cldRaw;
            FBORenderCutaways.ChunkLevelsData levelsData = cld.levelsData;
            if (levelsData == null) return false;
            IsoChunk chunk = levelsData.chunk;
            if (chunk == null) return false;

            int level = cld.level;

            // chunk.getGridSquare takes chunk-local x,y (0..7); the
            // bit index decodes to local lx = idx&7, ly = idx>>>3.
            long m = mask;
            while (m != 0L) {
                int idx = Long.numberOfTrailingZeros(m);
                m &= m - 1L;
                int lx = idx & 7;
                int ly = idx >>> 3;
                IsoGridSquare sq = chunk.getGridSquare(lx, ly, level);
                if (sq == null) continue;
                List<IsoObject> objs = sq.getObjects();
                for (int i = 0; i < objs.size(); i++) {
                    IsoObject obj = objs.get(i);
                    if (obj == null) continue;
                    IsoSprite sprite = obj.getSprite();
                    if (sprite == null) continue;
                    if (sprite.getProperties().has(IsoFlagType.HoppableN)
                            || sprite.getProperties().has(IsoFlagType.HoppableW)
                            || sprite.getProperties().has(IsoFlagType.TallHoppableN)
                            || sprite.getProperties().has(IsoFlagType.TallHoppableW)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // True if the cluster contains at least one orphan tile within
        // NEAR_PLAYER_RADIUS_TILES of the rendering player AND that
        // tile is not a roof-empty-outside classification. Roof tiles
        // belong to vanilla buildings and need to keep cutting during
        // climb so the room above is visible.
        public static boolean clusterHasNonRoofOrphanNearPlayer(Object orphanStructures) {
            long mask = Accessor.tryGet(orphanStructures, FIELD_IS_ORPHAN_SQUARE, 0L);
            if (mask == 0L) return false;
            Object cldRaw = Accessor.tryGet(orphanStructures, FIELD_CHUNK_LEVEL_DATA, null);
            if (!(cldRaw instanceof FBORenderCutaways.ChunkLevelData)) return false;
            FBORenderCutaways.ChunkLevelData cld = (FBORenderCutaways.ChunkLevelData) cldRaw;
            FBORenderCutaways.ChunkLevelsData levelsData = cld.levelsData;
            if (levelsData == null) return false;
            IsoChunk chunk = levelsData.chunk;
            if (chunk == null) return false;
            int level = cld.level;

            int px = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
            int py = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
            int cwx = chunk.wx * 8;
            int cwy = chunk.wy * 8;

            long m = mask;
            while (m != 0L) {
                int idx = Long.numberOfTrailingZeros(m);
                m &= m - 1L;
                int lx = idx & 7;
                int ly = idx >>> 3;
                int wx = cwx + lx;
                int wy = cwy + ly;
                int dx = wx - px;
                int dy = wy - py;
                if (dx * dx + dy * dy > NEAR_PLAYER_RADIUS_SQ) continue;
                IsoGridSquare sq = chunk.getGridSquare(lx, ly, level);
                if (sq == null) continue;
                if (sq.roofHideBuilding != null) continue;
                return true;
            }
            return false;
        }

        // True iff no orphan tile in this cluster is within RADIUS_TILES
        // of the rendering player's world xy. mask is a 64-bit bitmask
        // over the chunk's 8x8 grid: idx&7 = x, idx>>>3 = y.
        public static boolean isTooFarFromPlayer(Object orphanStructures) {
            long mask = Accessor.tryGet(orphanStructures, FIELD_IS_ORPHAN_SQUARE, 0L);
            if (mask == 0L) return true;
            Object cldRaw = Accessor.tryGet(orphanStructures, FIELD_CHUNK_LEVEL_DATA, null);
            if (!(cldRaw instanceof FBORenderCutaways.ChunkLevelData)) return false;
            FBORenderCutaways.ChunkLevelData cld = (FBORenderCutaways.ChunkLevelData) cldRaw;
            FBORenderCutaways.ChunkLevelsData levelsData = cld.levelsData;
            if (levelsData == null) return false;
            IsoChunk chunk = levelsData.chunk;
            if (chunk == null) return false;

            int px = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
            int py = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
            int cwx = chunk.wx * 8;
            int cwy = chunk.wy * 8;

            long m = mask;
            while (m != 0L) {
                int idx = Long.numberOfTrailingZeros(m);
                m &= m - 1L;
                int wx = cwx + (idx & 7);
                int wy = cwy + (idx >>> 3);
                int dx = wx - px;
                int dy = wy - py;
                if (dx * dx + dy * dy <= RADIUS_SQ) return false;
            }
            return true;
        }
    }

    // B42 adjacency-kill workaround. shouldRenderBuildingSquare has 3
    // return-false paths (buildingsToCollapse, basement, orphan-adjacency);
    // only orphan-adjacency is broken. isAdjacentToOrphanStructure is the
    // surgical patch point — called from exactly one site
    // (shouldRenderBuildingSquare:682) and nowhere else. Flipping its
    // true→false when the queried square belongs to a real vanilla
    // building (associatedBuilding != null — set only by the world
    // generator) neutralizes only the broken path. Real orphan tiles
    // short-circuit at isOrphanStructureSquare(square) above and never
    // reach adjacency.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCutaways$OrphanStructures",
           methodName = "isAdjacentToOrphanStructure")
    public static class Patch_isAdjacentToOrphanStructure {

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGridSquare square,
                                @Patch.Return(readOnly = false) boolean result) {
            if (!result) return;
            try {
                // Master switch + cutaway-section enable + own checkbox.
                // No aimStanceOnly gate — see Patch_shouldCutaway.exit
                // for rationale.
                if (!PeekAViewMod.enabled) return;
                if (!PeekAViewMod.cutawayEnabled) return;
                if (!PeekAViewMod.fixB42Adjacency) return;
                if (PeekAViewMod.isCameraPlayerIndoor()) return;
                if (square == null) return;
                if (square.associatedBuilding == null) return;
                result = false;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_isAdjacentToOrphanStructure exit error", t);
            }
        }

    }
}
