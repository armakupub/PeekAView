# PeekAView — Technical Documentation

Technical reference for contributors. Not shipped with the mod.

## Architecture

PeekAView patches Project Zomboid render-pipeline methods via
ZombieBuddy bytecode patches. All runtime state lives on the Java side
in `PeekAViewMod` (with per-frame and per-position caches in the
patches) plus `FakeWindow` for the stair feature. The Lua layer
(`PeekAView_Options.lua` / `_Keybind.lua`) is UI, persistence, and the
F8 toggle; Java is the read path for the render thread.

Three feature groups, all flat in `pzmod.peekaview` (ZombieBuddy's
`@Patch` scanner only matches the package set in `mod.info
javaPkgName=` exactly, no sub-packages):

```
PZAPI.ModOptions ──► Lua ──► applyToJava ──► PeekAViewMod.setXxx()
                                                  │
                                                  ▼
                                         static volatile state
                                                  │
              ┌───────────────────────┬──────────┴──────────┬──────────────────┐
              ▼                       ▼                     ▼                  ▼
       Wall cutaway             Tree fade             Stair view         Coordination
       Patch_IsoCell:           Patch_FBORenderCell:  Patch_IsoWorld:    FakeWindow,
       Patch_GetSquaresAround   Patch_isTranslucent   Patch_renderInt    FakeFrameState
       Patch_FBORenderCutaways: Patch_IsoCell:        + 8 inner patches
       Patch_cutawayVisit       Patch_DrawStencilMask across the render
       Patch_shouldCutaway                            pipeline
       Patch_isAdjacentToOrphan
```

## File Index

| File | Describes |
|------|-----------|
| [`iso-geometry.md`](iso-geometry.md) | World coordinates, iso projection, render order (anti-diagonal), sprite extent vs. tile footprint, why we fade all four quadrants |
| [`PeekAViewMod.md`](PeekAViewMod.md) | Main class, state fields, cutaway / tree-fade gates, per-frame memo, upstream Staircast detection, render-state helpers |
| [`Patch_IsoCell.md`](Patch_IsoCell.md) | POI raster expansion + cache + wall/LOS filter (vanilla pass-through at `MIN_RANGE`); tree-fade stencil-mask extension; non-FBO stair render-pass swap |
| [`Patch_FBORenderCutaways.md`](Patch_FBORenderCutaways.md) | cutawayVisit dedup + B42 adjacency-kill fix |
| [`Patch_FBORenderCell.md`](Patch_FBORenderCell.md) | Tree-fade `isTranslucentTree` extension + speed-proportional fade boost; FBO stair render-pass swap and inverted player-sprite restore |
| [`Stair_feature.md`](Stair_feature.md) | Stair view feature: render-time camera uplift while on stairs, the multi-patch coordination via `FakeWindow` ThreadLocal, read-path shadow on `IsoMovingObject` getters, lighting / weather / tree-pass uplift, upstream Staircast yield-on-detect |
| [`PeekAView_Options.md`](PeekAView_Options.md) | Lua options UI + defensive bridge + Java-missing detection |
| [`TESTING.md`](TESTING.md) | Manual test notes from the 1.2.0 prep cycle plus open split-screen item |

## Build / Deploy

- Source: `src/pzmod/peekaview/*.java`
- Mod files: `mod_files/42.13/` (Build 42.13)
- ZombieBuddy dependency — required for `@Patch` and `@Exposer.LuaClass`
- Target: PZ Build 42.13 or later
