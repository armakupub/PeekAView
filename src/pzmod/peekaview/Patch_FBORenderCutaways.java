package pzmod.peekaview;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

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

        @Patch.OnExit
        public static void exit(@Patch.This Object self,
                                @Patch.Return(readOnly = false) boolean result) {
            if (!result) return;
            try {
                if (!PeekAViewMod.fixB42Adjacency) return;
                if (!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()) return;
                if (!initialized) tryInit();
                if (!initialized) return;
                if (isTooFarFromPlayer(self)) result = false;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_shouldCutaway exit error", t);
            }
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
                if (!PeekAViewMod.fixB42Adjacency) return;
                if (!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()) return;
                if (square == null) return;
                if (square.associatedBuilding == null) return;
                result = false;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_isAdjacentToOrphanStructure exit error", t);
            }
        }

    }
}
