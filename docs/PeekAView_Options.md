# PeekAView_Options.lua

User-facing options UI. Java owns state; Lua writes into it via setters.

**File:** `mod_files/42.13/media/lua/client/PeekAView_Options.lua`
**Load path:** `client/` (PZ keybinding convention — inserts in `shared/`
trigger load-order NPE).

## Bridge

```lua
local ModJava = PeekAViewMod
PeekAView.javaReady = ModJava ~= nil
```

`PeekAViewMod` is a Kahlua global registered by ZombieBuddy from
`@Exposer.LuaClass` on the Java class. Only the simple class name works
— `pzmod.peekaview.PeekAViewMod` errors with `non-table: null` at
module load.

`ModJava` is `nil` only when the user declined ZombieBuddy's JAR-loading
prompt for this mod. ZombieBuddy is a hard dependency in `mod.info`, so
its own absence is not a reachable state at runtime.

Every setter call goes through `applyToJava(setterName, value)` which
guards against `javaReady == false` and pcalls the setter. Failures
flip `javaReady` off and stash the error in `PeekAView.lastJavaError`:

```lua
local function applyToJava(setterName, value)
    if not PeekAView.javaReady then return end
    local fn = ModJava[setterName]
    if not fn then
        PeekAView.javaReady = false
        PeekAView.lastJavaError = "missing setter: " .. setterName
        return
    end
    local ok, err = pcall(fn, value)
    if not ok then
        PeekAView.javaReady = false
        PeekAView.lastJavaError = tostring(err)
    end
end
```

When `javaReady == false`, the options screen prepends a warning
description so the user sees why their toggles have no effect:

```lua
if not PeekAView.javaReady then
    modOptions:addDescription("UI_PAV_JavaMissingDescription")
    modOptions:addDescription("UI_PAV_Spacer")
end
```

The F8 keybind (`PeekAView_Keybind.lua`) reads the same flag and emits
a red `UI_PAV_JavaMissingHalo` halo instead of toggling.

## Options layout (`Options → Mods → Peek a View`)

When `javaReady == false`, two extra descriptions are prepended above
the first section title: `UI_PAV_JavaMissingDescription` and
`UI_PAV_Spacer`.

The screen is grouped into three sections, each opened by a section
title (rendered via `addTitle` if PZAPI exposes it, else via a regular
description as a graceful fallback — see `addSection` helper in
`PeekAView_Options.lua`).

### Global

| Widget | Key | Range / Default | Setter |
|--------|-----|-----------------|--------|
| Title | `UI_PAV_GlobalSectionTitle` | — | — |
| TickBox | `enable` | default `true` | `setEnabled(v)` |
| TickBox | `aimStanceOnly` | default `false` | `setAimStanceOnly(v)` |

### Wall cutaway

| Widget | Key | Range / Default | Setter |
|--------|-----|-----------------|--------|
| Title | `UI_PAV_WallCutawaySectionTitle` | — | — |
| Slider | `cutawayRange` | 5–20, step 1, default 10 | `setRange(v)` |
| Description | `UI_PAV_RangePerformanceDescription` | — | — |
| TickBox | `cutawayActiveInVehicle` | default `true` | `setCutawayActiveInVehicle(v)` |
| TickBox | `fixB42` | default `true` | `setFixB42Adjacency(v)` |

The `cutawayRange` key was renamed from `range` in 1.3.0 so the
slider resets to the new default of 10 (down from 15) on first launch
with that version. PZAPI ModOptions persists by the first string arg,
so the rename invalidates saved values for this slider only — no
other settings touched.

### Tree fade

| Widget | Key | Range / Default | Setter |
|--------|-----|-----------------|--------|
| Title | `UI_PAV_TreeFadeSectionTitle` | — | — |
| TickBox | `fadeNWTrees` | default `true` | `setFadeNWTrees(v)` |
| Slider | `treeFadeRange` | 5–25, step 1, default 20 | `setTreeFadeRange(v)` |

All setters dispatch through `applyToJava(name, v)`.

## Boot sync

`PZAPI.ModOptions:load()` auto-runs only when the user opens the Options
screen (via `MainOptions:addModOptionsPanel`). On a fresh boot the saved
values would not reach Java until the first Options-screen visit. We
load-and-push on `OnGameBoot`:

```lua
local function syncToJava()
    PZAPI.ModOptions:load()
    applyToJava("setEnabled", enableOpt:getValue())
    applyToJava("setAimStanceOnly", aimStanceOnlyOpt:getValue())
    applyToJava("setRange", rangeOpt:getValue())
    applyToJava("setCutawayActiveInVehicle", cutawayActiveInVehicleOpt:getValue())
    applyToJava("setFixB42Adjacency", fixB42Opt:getValue())
    applyToJava("setFadeNWTrees", fadeNWTreesOpt:getValue())
    applyToJava("setTreeFadeRange", treeFadeRangeOpt:getValue())
end

Events.OnGameBoot.Add(syncToJava)
```

Idempotent with `MainOptions`' later load. When `javaReady == false`,
every `applyToJava` is a no-op — boot still completes cleanly.

## Exports

```lua
PeekAView.syncToJava = syncToJava
PeekAView.enableOpt / aimStanceOnlyOpt
PeekAView.rangeOpt / cutawayActiveInVehicleOpt / fixB42Opt
PeekAView.fadeNWTreesOpt / treeFadeRangeOpt
```

## Translations

Keys live in `media/lua/shared/Translate/<LANG>/UI.json`. 12 languages
ship: EN, DE, FR, ES, RU, PL, PTBR, IT, TR, CN, KO, JP (same set as
PassThroughWindow / AutoFFWalkTo). Folder is `PTBR` (no dash); user-facing
label is `PT-BR`.

Keys:
- `UI_PAV_ModName`
- Section titles: `UI_PAV_GlobalSectionTitle` / `UI_PAV_WallCutawaySectionTitle` / `UI_PAV_TreeFadeSectionTitle`
- Global section: `UI_PAV_EnableLabel` / `UI_PAV_EnableTooltip`, `UI_PAV_AimStanceOnlyLabel` / `UI_PAV_AimStanceOnlyTooltip`
- Wall cutaway section: `UI_PAV_RangeLabel` / `UI_PAV_RangeTooltip`, `UI_PAV_RangePerformanceDescription`, `UI_PAV_CutawayActiveInVehicleLabel` / `UI_PAV_CutawayActiveInVehicleTooltip`, `UI_PAV_FixB42Label` / `UI_PAV_FixB42Tooltip`
- Tree fade section: `UI_PAV_FadeNWTreesLabel` / `UI_PAV_FadeNWTreesTooltip`, `UI_PAV_TreeFadeRangeLabel` / `UI_PAV_TreeFadeRangeTooltip`
- `UI_PAV_Spacer` (single space, used via `addDescription` as vertical separator)
- `UI_PAV_EnabledText` / `UI_PAV_DisabledText` (halo toggle text)
- `UI_PAV_JavaMissingDescription` (red banner shown above the options list when `javaReady == false`)
- `UI_PAV_JavaMissingHalo` (halo shown by F8 keybind when `javaReady == false`)
- `UI_optionscreen_binding_PeekAView` / `UI_optionscreen_binding_PeekAView Toggle`

All tooltips are kept short (one sentence) — the user sees what each
control does at a glance without being overloaded by implementation
detail.
