# Testing

## Open

### Split-screen (2-player)

Per-player raster cache slots in `Patch_GetSquaresAroundPlayerSquare`
shipped, untested on real split-screen. Verify: each player hits its
own cache slot (~59/60 hit rate), Z-change invalidates independently,
no visual thrash. Fallback if broken: single shared slot + add
`cachedPlayerIndex` to the miss key (one-line revert).

## Done

- Single-player: 60 fps steady, cutaway extends to slider range
- Driving: speed gate flips mod off above threshold without flicker
- B42 fix: player-built stair next to vanilla wall no longer hides it
- `fixB42Adjacency` toggle OFF: vanilla bug reappears (confirms patch)
- `aimStanceOnly` toggle ON: snaps on/off with RMB aim
- Tree-fade (`fadeNWTrees`): dense forest, walking, driving, vehicle re-entry, toggle-across-range-change, NW-quadrant-stays-opaque — all passed
- Split cutaway/tree-fade driving-speed gates: each gate honors its own threshold; tree-fade can stay on while cutaway gates off
- Independent `treeFadeRange` slider (5–25, default 20)
- JFR-driven perf optimization (Item 1 + 3 + toArray-bypass + IllegalAccessError fix): GC median ≈ baseline, Location allocator eliminated
