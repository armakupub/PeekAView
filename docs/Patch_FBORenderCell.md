# Patch_FBORenderCell

Patches `zombie.iso.fboRenderChunk.FBORenderCell.isTranslucentTree`
to extend vanilla's tree-fade so trees the character can see past
become translucent within `PeekAViewMod.treeFadeRange`. Together with
`Patch_DrawStencilMask` (in `Patch_IsoCell`) this makes distant
zombies whose tile the character can already see stop hiding behind
opaque tree sprites.

**File:** `src/pzmod/peekaview/Patch_FBORenderCell.java`
**Gate:** early-returns unless `PeekAViewMod.fadeNWTrees == true`
**and** `PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()` returns
true.

For coordinate conventions, render order, and why the fade covers all
quadrants see [`iso-geometry.md`](iso-geometry.md).

## Vanilla baseline

Vanilla `FBORenderCell.isTranslucentTree` (decompiled at line ~1765)
runs three gates and returns `true` only if all pass:

1. `renderLevels.inStencilRect` — chunk-level early exit.
2. **Per-tree stencil-bbox check** against `cell.stencilX1..Y2` (set
   by `IsoCell.DrawStencilMask:450-453`, sized to the vanilla circular
   stencil texture, ~4-5 tile radius around the player). Trees outside
   that bbox are dropped before any quadrant logic runs.
3. SE-quadrant test:
   `square.x >= camCharacterX && square.y >= camCharacterY`.
   `camCharacterX/Y` is set in `IsoCamera$FrameState.set()` directly
   to `camCharacter.getX()/getY()` — no vehicle offset, no aim-pan.

`IsoTree.fadeAlpha` only steps **down** when `renderFlag == true`, so
without our flag flip the extended quadrants never start to fade.

## Patch — `isTranslucentTree`

**Patched method:** `FBORenderCell.isTranslucentTree(IsoObject)`

`@Patch.OnExit` advice with mutable return:

```java
@Patch.OnExit
public static void exit(@Patch.Argument(0) IsoObject object,
                        @Patch.Return(readOnly = false) boolean result)
```

Bails on any of:
- `!fadeNWTrees` or `!isActiveTreeFadeForCurrentRenderPlayer()`
- `!(object instanceof IsoTree)`
- `object.square == null`

Then two paths share one method:

**Path A — vanilla returned `false`.** Compute `(dx, dy)` from
`camCharacterX/Y` to `square.x/y`. Skip if origin tile, skip if
outside Diamond range, otherwise `result = true` to flip vanilla's
output.

**Path B — vanilla returned `true`** (SE-inside-bbox). Skip the
quadrant logic; `result` already final. Continue to the speed-snap
step. Running the snap on this path keeps the fade behavior
symmetric: SE-bbox trees and our extended-quadrant trees both
respond to driving speed identically.

## Speed-proportional fade boost

After Path A or B has settled `result == true`, the patch applies a
speed-proportional fade-down step on `tree.fadeAlpha` directly:

```java
float speed = PeekAViewMod.currentVehicleSpeedKmh;
if (speed > 0f && tree.fadeAlpha > 0.25f) {
    float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH;  // 30
    float t   = speed >= cap ? 1.0f : speed / cap;
    tree.fadeAlpha += (0.25f - tree.fadeAlpha) * t;
    if (tree.fadeAlpha < 0.25f) tree.fadeAlpha = 0.25f;
}
```

Vanilla's per-frame `alphaStep = 0.045 * thirtyFPSMultiplier` in
`IsoTree.render():302` takes ~0.55 s real-time to ease 1.0 → 0.25.
At 70 km/h ≈ 19 tile/s the player covers `treeFadeRange = 20` in ~1 s
and the fade just barely keeps up; at 100 km/h the tree is still
half-opaque when the bumper passes it. The boost runs every render
call that flips translucent, lerping toward `minAlpha = 0.25` in
proportion to current vehicle speed. At 0 km/h `t = 0` and vanilla
owns the animation. At ≥ 30 km/h `t = 1` and the tree snaps to
`minAlpha` in one frame.

Constraints:

- Steps **down** only — `(minAlpha - fadeAlpha)` is always negative
  while `fadeAlpha > minAlpha`. Range-exit / toggle-off uses
  vanilla's fade-up step.
- `tree.fadeAlpha` is a `public float`, accessible from the inlined
  advice in `FBORenderCell` without `IllegalAccessError`. Indoor
  `minAlpha = 0.05` is ignored — drivers are always outdoors.
- `currentVehicleSpeedKmh` is set in `PeekAViewMod.refreshActiveCache`
  (which runs on the first patch call per frame for the rendering
  player). When the player is not in a vehicle the field is `0f` and
  the boost block is a no-op.

LOS untouched — `IsoTree` is not in `LosUtil` / `specialObjects`, so
this is purely a render-layer change.
