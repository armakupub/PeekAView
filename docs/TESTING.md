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
ramp 10–50 km/h, snap from 50 km/h up. Same cubic ramp on the
refade side: trees passing behind a moving car (dot > 0.34, the
clearlyBehind gate) snap UP at high speed instead of leaving a
ghost-trail at vanilla's 0.55 s rate. Tall pines fade trunk +
crown together; outermost leaves of trees at the circle edge stay
covered (192-px stencil width). LOS keeps unseen forest opaque,
the tree-fade cone keeps trees the player is not facing opaque
(`isTileInTreeFadeCone`, uncapped so vehicle gets 360°; the
back-cone classifier `isTileClearlyBehindCameraPlayer` then carves
out the refade zone). Reversing flips the forward direction so the
cone follows direction-of-travel. The asymmetric stencil keeps
SE-quadrant covered to MAX_TREE_FADE_RANGE regardless of slider, so
vanilla's natural fade reach behind the player is independent of
the user's slider value.

**Performance** — see [`PeekAViewMod.md`](PeekAViewMod.md#performance).
The 1.2.0 baseline measured here is no longer representative after
the 1.2.1 removal of `Patch_calculateObjectsObscuringPlayer`; rerun
JFR before quoting current numbers.

## Open

**Split-screen** is untested on real hardware. The per-player
raster cache slots in `Patch_GetSquaresAroundPlayerSquare` should
hit ~59/60 per player and invalidate independently on Z-change;
fallback if broken is a single shared slot keyed by
`cachedPlayerIndex`.
