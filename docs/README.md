# PeekAView — Technical Documentation

Technical reference for contributors. Not shipped with the mod.

## Architecture

PeekAView patches seven vanilla Project Zomboid methods via ZombieBuddy
bytecode patches across three Java files. All runtime state lives on
the Java side in `PeekAViewMod` (with per-frame and per-position caches
in the patches). The Lua layer (`PeekAView_Options.lua` /
`_Keybind.lua`) is UI, persistence and the F8 toggle; Java is the read
path for the render thread.

```
PZAPI.ModOptions  ──►  Lua ──►  applyToJava ──►  PeekAViewMod.setXxx()
                                                       │
                                                       ▼
                                              static volatile state
                                                       │
                ┌──────────────────────────────────────┼──────────────────────────────────────┐
                ▼                                      ▼                                      ▼
        Patch_IsoCell                     Patch_FBORenderCutaways                  Patch_FBORenderCell
        (2 patches: POI raster +          (3 patches: cutawayVisit dedup +         (2 patches: tree-fade
         tree-fade stencil mask)           B42 adjacency-kill fix)                   isTranslucent + ceiling)
```

## File Index

| File | Describes |
|------|-----------|
| [`PeekAViewMod.md`](PeekAViewMod.md) | Main class, state fields, split cutaway/tree-fade gates, per-frame `isActive` memo |
| [`Patch_IsoCell.md`](Patch_IsoCell.md) | POI raster expansion + cache + wall/LOS filter; tree-fade stencil-mask extension |
| [`Patch_FBORenderCutaways.md`](Patch_FBORenderCutaways.md) | cutawayVisit dedup + B42 adjacency-kill fix (3 patches) |
| [`Patch_FBORenderCell.md`](Patch_FBORenderCell.md) | Tree-fade `isTranslucentTree` extension + `calculateObjectsObscuringPlayer` Location cache |
| [`PeekAView_Options.md`](PeekAView_Options.md) | Lua options UI + defensive bridge + Java-missing detection |
| [`TESTING.md`](TESTING.md) | Open testing items |

## Build / Deploy

- Source: `src/pzmod/peekaview/*.java`
- Mod files: `mod_files/42.13/` (Build 42.13)
- ZombieBuddy dependency — required for `@Patch` and `@Exposer.LuaClass`
- Target: PZ Build 42.13 or later
