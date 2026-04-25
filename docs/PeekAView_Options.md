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
| Slider | `range` | 5–20, step 1, default 15 | `setRange(v)` |
| Description | `UI_PAV_RangePerformanceDescription` | — | — |
| Slider | `drivingSpeed` | 0–120, step 5, default 35 | `setMaxDrivingSpeedKmh(v)` |
| Description | `UI_PAV_DrivingSpeedDescription` | — | — |
| TickBox | `fixB42` | default `true` | `setFixB42Adjacency(v)` |

### Tree fade

| Widget | Key | Range / Default | Setter |
|--------|-----|-----------------|--------|
| Title | `UI_PAV_TreeFadeSectionTitle` | — | — |
| TickBox | `fadeNWTrees` | default `true` | `setFadeNWTrees(v)` |
| Slider | `treeFadeRange` | 5–25, step 1, default 20 | `setTreeFadeRange(v)` |
| Slider | `treeFadeDrivingSpeed` | 0–120, step 5, default 50 | `setTreeFadeMaxDrivingSpeedKmh(v)` |
| Description | `UI_PAV_TreeFadeDrivingSpeedDescription` | — | — |

All setters dispatch through `applyToJava(name, v)`.

### Driving-speed sliders

Wall-cutaway and tree-fade each have their own driving-speed slider
because their use-cases diverge: wall-cutaway is most useful at city
speeds and gets noisy at high speed, tree-fade is most useful exactly
when driving past treelines but the per-tree fade-up animation can't
keep up much past 50 km/h.

Both sliders are vanilla PZAPI sliders — no custom label formatter.
The slider title shows the raw value; the unit (`km/h`) and the
0-means-always-off case are spelled out in the description line below.
An earlier custom-format pass (rewriting the JLabel via a `setName`
shim) was removed because PZAPI builds the slider's Java element only
on first Options-screen open and the formatter couldn't reliably hit
that build window — the saved value would render unformatted until the
user dragged the slider.

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
    applyToJava("setMaxDrivingSpeedKmh", drivingSpeedOpt:getValue())
    applyToJava("setFixB42Adjacency", fixB42Opt:getValue())
    applyToJava("setFadeNWTrees", fadeNWTreesOpt:getValue())
    applyToJava("setTreeFadeRange", treeFadeRangeOpt:getValue())
    applyToJava("setTreeFadeMaxDrivingSpeedKmh", treeFadeDrivingSpeedOpt:getValue())
end

Events.OnGameBoot.Add(syncToJava)
```

Idempotent with `MainOptions`' later load. When `javaReady == false`,
every `applyToJava` is a no-op — boot still completes cleanly.

## Exports

```lua
PeekAView.syncToJava = syncToJava
PeekAView.enableOpt / aimStanceOnlyOpt
PeekAView.rangeOpt / drivingSpeedOpt / fixB42Opt
PeekAView.fadeNWTreesOpt / treeFadeRangeOpt / treeFadeDrivingSpeedOpt
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
- Wall cutaway section: `UI_PAV_RangeLabel` / `UI_PAV_RangeTooltip`, `UI_PAV_RangePerformanceDescription`, `UI_PAV_DrivingSpeedLabel` / `UI_PAV_DrivingSpeedTooltip` / `UI_PAV_DrivingSpeedDescription`, `UI_PAV_FixB42Label` / `UI_PAV_FixB42Tooltip`
- Tree fade section: `UI_PAV_FadeNWTreesLabel` / `UI_PAV_FadeNWTreesTooltip`, `UI_PAV_TreeFadeRangeLabel` / `UI_PAV_TreeFadeRangeTooltip`, `UI_PAV_TreeFadeDrivingSpeedLabel` / `UI_PAV_TreeFadeDrivingSpeedTooltip` / `UI_PAV_TreeFadeDrivingSpeedDescription`
- `UI_PAV_Spacer` (single space, used via `addDescription` as vertical separator)
- `UI_PAV_EnabledText` / `UI_PAV_DisabledText` (halo toggle text)
- `UI_PAV_JavaMissingDescription` (red banner shown above the options list when `javaReady == false`)
- `UI_PAV_JavaMissingHalo` (halo shown by F8 keybind when `javaReady == false`)
- `UI_optionscreen_binding_PeekAView` / `UI_optionscreen_binding_PeekAView Toggle`

All tooltips are kept short (one sentence) — earlier verbose tooltips
were replaced 2026-04-26 with sachlich-knapp wording so the user sees
what each control does at a glance without being overloaded by
implementation detail.
