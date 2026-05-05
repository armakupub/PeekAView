# Testing

Single-player Build 42.17 with ~50 background mods loaded, manual
test runs over the 1.2.0 prep cycle.

**Cutaway** — slider 5 falls through to vanilla cleanly; 6–20
extends the raster smoothly (each +1 step grows by a vanilla-shaped
ring; per-step effect is direction-dependent, see
[`Patch_IsoCell.md`](Patch_IsoCell.md)). `Active in vehicles` toggle
flips on/off without flicker. B42 wall-hiding fix reproduces vanilla
behavior on toggle-off and resolves it on toggle-on; crossing a
building threshold flips the fix off (vanilla cutaway resumes
indoor) and back on when stepping outside.

**Tree fade** — fades cleanly on foot through dense forest. In
vehicles, vanilla's alphaStep owns the fade below 10 km/h; cubic
ramp 10–80 km/h, snap from 80 km/h up. Tall pines fade trunk +
crown together; outermost leaves of trees at the diamond edge stay
covered (192-px stencil width). LOS keeps unseen forest opaque,
cone keeps trees the player is not facing opaque
(`isTileInCameraPlayerCone`, capped at 0.0 so vehicle's vanilla 360°
override doesn't widen tree-fade past forward 180°). Reversing flips
the cone 180° so trees in direction-of-travel fade. Trees leaving
the cone fade back to opaque smoothly via the stencil-persistence
buffer. Vehicle in/out transitions without stuck states.

**Performance** — JFR on a 120 s Rosewood→Muldraugh driving run
puts the mod at ~2.8% CPU samples at defaults, ~0.1% with the
master `Enable` toggle off. Tile-filter optimization confirmed
via the `squareHasFadingInObjects` downstream-walk dropping out
of the sample profile. Numbers in
[`PeekAViewMod.md`](PeekAViewMod.md#performance).

## Open

**Split-screen** is untested on real hardware. The per-player
raster cache slots in `Patch_GetSquaresAroundPlayerSquare` should
hit ~59/60 per player and invalidate independently on Z-change;
fallback if broken is a single shared slot keyed by
`cachedPlayerIndex`.
