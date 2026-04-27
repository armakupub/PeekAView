# Iso Geometry Reference

Ground truth for the patch-level decisions about which trees and tiles
to mark translucent. The PZ engine assumes this geometry; without it
the quadrant choices in `Patch_FBORenderCell` and `Patch_IsoCell` look
arbitrary.

## World coordinates

PZ stores world position on `IsoGridSquare` and `IsoObject` as floats
in tile units:

- `x` increases to the **east**
- `y` increases to the **south**
- `z` is the floor / story level

`IsoCamera.frameState.camCharacterX/Y/Z` follow the rendering player —
they are set in `IsoCamera$FrameState.set()` directly to
`camCharacter.getX()/getY()`, with no vehicle offset, no aim-pan, no
zoom transform. Patches that read `camCharacterX/Y` see the same
values as `player.getX()/getY()` for the rendering player.

## Iso projection

`IsoUtils.XToScreen / YToScreen`:

```
screenX = (objectX - objectY) * 32 * tileScale
screenY = (objectX + objectY) * 16 * tileScale + (screenZ - objectZ) * 96 * tileScale
```

Camera sits up and to the south-east of the world it draws and looks
toward the north-west / origin. On screen:

| World direction | Screen direction |
|---|---|
| +x (east) | down-right |
| +y (south) | down-left |
| +z (up) | up |
| origin (0, 0) | top of screen (far from camera) |
| (large x, large y) | bottom of screen (near camera) |

Quadrant aliases used throughout the patches, relative to the player
tile at `(px, py)`:

| Player-relative position | dx | dy | Where on screen |
|---|---|---|---|
| NW | <0 | <0 | up-left direction |
| N-axis | 0 | <0 | up-right (toward origin along y) |
| NE | >0 | <0 | right (more) |
| E-axis | >0 | 0 | down-right |
| SE | >0 | >0 | bottom |
| S-axis | 0 | >0 | down-left |
| SW | <0 | >0 | left |
| W-axis | <0 | 0 | up-left (toward origin along x) |

## Render order — the anti-diagonal

Iso tiles are rendered roughly in **`x + y` ascending** order: low sum
first (top of screen, far from camera), high sum last (bottom of
screen, near camera). Late draws sit on top of earlier ones.

The relevant test for "tree T can occlude player P":

```
T.x + T.y > P.x + P.y
```

Geometrically: T sits on the south-east side of the anti-diagonal line
through the player. SE quadrant is unconditionally on that side; NE
and SW partially (depending on which delta dominates); NW
unconditionally not.

## Sprite extent vs. tile footprint

A tree's `IsoObject` lives on its **base tile** (the trunk's foot).
The sprite is a 2D image that extends upward on screen — typically
3-5 tile-heights above the base. PZ does not split sprites across
multiple tiles; the entire sprite belongs to the one base tile.

This matters for the **stencil**. The stencil mask is per-tile, but
the mask area must cover where the sprite's pixels land on screen,
not just where the base tile is. A short stencil patch around the
tile floor leaves the upper sprite pixels outside the mask, where
`GL_NOTEQUAL` renders them at full opacity — observable as "trunk
fades, crown stays opaque" on tall trees. See
[`Patch_IsoCell.md`](Patch_IsoCell.md#per-tile-render) for the
overshoot constants used to absorb this.

## Why we fade all four quadrants

Tree-fade applies to every tile in the `treeFadeRange` diamond around
the player, excluding the player tile itself. Three reasons that
matter together:

- **SE / E-axis / S-axis** trees sit on the south-east side of the
  anti-diagonal — they can occlude the player sprite directly. Vanilla
  already fades these inside its ~5-tile bbox; we extend to the full
  diamond.
- **NE / SW** trees are partially in the front half-plane (depending
  on which delta dominates) and sit visually beside the player on
  screen. Their sprites can sweep across the player when the camera
  pans.
- **NW / N-axis / W-axis** trees are behind the player in render order
  and cannot occlude the player sprite directly. But tall sprites in
  those positions cover screen area **above** the player on screen —
  exactly the area the user looks toward when scanning northward in
  the world. A high pine 3 tile-heights above the screen-player blocks
  the view of zombies further out in that screen direction.

The `isCanSee(playerIndex)` LOS gate in `Patch_DrawStencilMask`
suppresses stencil writes for tiles outside the rendering player's
visibility cone. So "fade everything in range" does not ghost-fade
unseen forest: NW trees fade only when the player is actively looking
toward them. Behind walls, in fog of war: stencil isn't written, the
tree stays opaque.

Origin tile (`dx == 0 && dy == 0`) is excluded from the patch's
quadrant logic — vanilla owns it via its `>=` quadrant check.
