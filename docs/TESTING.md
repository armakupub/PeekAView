# Testing

Single-player Build 42.17 with ~50 background mods loaded, manual
test runs over the 1.2.0 prep cycle.

**Cutaway** — slider 5 falls through to vanilla cleanly; 6–20
extends the raster as expected (per-step effect is direction-
dependent, see [`Patch_IsoCell.md`](Patch_IsoCell.md)). Driving
speed-gate flips on/off without flicker. B42 wall-hiding fix
reproduces vanilla behavior on toggle-off and resolves it on
toggle-on; verified that crossing a building threshold flips the
fix off (vanilla cutaway resumes indoor) and back on when stepping
outside.

**Tree fade** — fades cleanly on foot through dense forest, snaps
fully translucent above ~30 km/h while driving, holds for tall
pines (trunk + crown together), respects LOS so unseen forest
stays opaque. Vehicle in/out and driving-speed gate transition
without stuck states. Both `Stay on while driving` / `Stay on
while on foot` overrides verified against the aim-stance gate.

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
