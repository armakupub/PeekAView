# PeekAView — Technical Documentation

Technical reference for contributors. Not shipped with the mod.

## Architecture

PeekAView patches four vanilla Project Zomboid methods via ZombieBuddy
bytecode patches. All runtime state lives on the Java side in
`PeekAViewMod`. The Lua layer (`PeekAView_Options.lua`) is UI and
persistence only; Java is the read path for the render thread.

```
PZAPI.ModOptions  ──►  Lua ──►  PeekAViewMod.setXxx()
                                      │
                                      ▼
                             static volatile state
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
           Patch_IsoCell    Patch_FBORenderCutaways  (render thread)
           (POI raster)     (3 patches)
```

## File Index

| File | Describes |
|------|-----------|
| [`PeekAViewMod.md`](PeekAViewMod.md) | Main class, state fields, Lua bridge, driving-speed gate |
| [`Patch_IsoCell.md`](Patch_IsoCell.md) | POI raster expansion, cache, wall-adjacency filter, LOS filter |
| [`Patch_FBORenderCutaways.md`](Patch_FBORenderCutaways.md) | cutawayVisit dedup + B42 adjacency-kill fix (2 patches) |
| [`PeekAView_Options.md`](PeekAView_Options.md) | Lua options UI, slider label-formatter pattern |
| [`TESTING.md`](TESTING.md) | Open testing items |

## Build / Deploy

- Source: `src/pzmod/peekaview/*.java`
- Mod files: `mod_files/42.13/` (Build 42.13)
- ZombieBuddy dependency — required for `@Patch` and `@Exposer.LuaClass`
- Target: PZ Build 42.13 or later
