# Testing

## Open

### Split-screen per-player raster cache — verify in 2-player mode

`Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare` now caches per
`playerIndex` (slots `[0..IsoPlayer.MAX-1]`). Implementation shipped,
not yet fully tested on actual split-screen.

**What to verify:**
1. Run split-screen with 2 players, stand each on a different tile.
2. Expected: each player's pass hits its own cache slot; steady-state
   hit rate ~59/60 per player. No visible thrash, no raster lag.
3. Stress: have both players walk simultaneously, close to different
   buildings. Watch for any visual difference vs single-player
   (cutaway timing, missing walls, flicker).
4. Z-change edge case: one player climbs stairs while the other stays
   on ground level — confirm each player's `cachedZ[i]` invalidates
   independently.

**Not a correctness concern** — raster is a pure function of position;
any cache mis-hit returns a semantically identical list. Downstream
filters (`isCouldSee(playerIndex)`, `square.getBuilding() != player.getBuilding()`)
run in the caller. Testing is about performance and visual parity.

**Fallback if problems show up:** drop `playerIndex` slot and use a
single shared slot + `cachedPlayerIndex` in the miss key — one-line
change. Thrashes in split-screen but correct.

## Done (ongoing sanity-checks for regressions)

- Single-player: 60 fps steady state, cutaway extends to slider-set range
- Driving: speed gate flips mod off above threshold without flicker
- B42 fix: player-built stair next to vanilla wall no longer hides the wall
- `fixB42Adjacency` toggle OFF: vanilla adjacency-kill bug reappears (confirms patch is the reason it's gone)
- `aimStanceOnly` toggle ON: mod is off until RMB is held, snaps on/off in sync with the aim stance
