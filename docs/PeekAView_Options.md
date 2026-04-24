# PeekAView_Options.lua

User-facing options UI. Java owns state; Lua writes into it via setters.

**File:** `mod_files/42.13/media/lua/client/PeekAView_Options.lua`
**Load path:** `client/` (PZ keybinding convention — inserts in `shared/`
trigger load-order NPE).

## Bridge

```lua
local ModJava = PeekAViewMod
```

`PeekAViewMod` is a Kahlua global registered by ZombieBuddy from
`@Exposer.LuaClass` on the Java class. Only the simple class name works
— `pzmod.peekaview.PeekAViewMod` errors with `non-table: null` at
module load.

## Options layout (`Options → Mods → Peek a View`)

| Widget | Key | Range / Default | Setter |
|--------|-----|-----------------|--------|
| TickBox | `enable` | default `true` | `ModJava.setEnabled(v)` |
| TickBox | `aimStanceOnly` | default `false` | `ModJava.setAimStanceOnly(v)` |
| Slider | `range` | 5–20, step 1, default 15 | `ModJava.setRange(v)` |
| Description | `UI_PAV_RangePerformanceDescription` | — | — |
| Description | `UI_PAV_Spacer` | — | — |
| Slider | `drivingSpeed` | 0–120, step 5, default 35 | `ModJava.setMaxDrivingSpeedKmh(v)` |
| Description | `UI_PAV_DrivingSpeedDescription` | — | — |
| Description | `UI_PAV_Spacer` | — | — |
| TickBox | `fixB42` | default `true` | `ModJava.setFixB42Adjacency(v)` |

### Driving-speed slider UX

Label shows `N km/h` directly on the slider handle. The 0-means-always-off
case is explained in the description line below the slider (slider
label has no room for explanatory text).

`addTitle` above the slider identifies WHAT the slider controls — the
slider's own label is just the unit.

## Slider label formatter pattern (ZBBetterFPS-style)

`PZAPI.ModOptions:addSlider` offers no label-formatter hook. We rewrite
the raw JLabel text each time the value changes:

```lua
local function updateDrivingSpeedLabel(slider, newValue)
    if not slider or not slider.element then return end
    local label = slider.element.label
    if not label or not label.setName then return end
    if not label.setName_PeekAView then
        label.setName_PeekAView = label.setName
        label.setName = function(self, name) end   -- shim original to no-op
    end
    newValue = newValue or slider.value or slider:getValue()
    label:setName_PeekAView(tostring(newValue) .. " km/h")
end

drivingSpeedOpt.getValue = function(self)
    updateDrivingSpeedLabel(self, self.value); return self.value
end
drivingSpeedOpt.setValue = function(self, value)
    self.value = value; updateDrivingSpeedLabel(self, value)
end
drivingSpeedOpt.onChange = function(self, newValue)
    updateDrivingSpeedLabel(self, newValue)
end
drivingSpeedOpt.onChangeApply = function(self, value)
    ModJava.setMaxDrivingSpeedKmh(value)
end
```

Why the `setName` shim: vanilla periodically rewrites the label to
`"title<SPACE>value"`. Shimming `setName` to a no-op after capturing the
original as `setName_PeekAView` keeps our formatted text from being
stomped. Same pattern used in ZBBetterFPS's render-distance slider.

## Boot sync

`PZAPI.ModOptions:load()` auto-runs only when the user opens the Options
screen (via `MainOptions:addModOptionsPanel`). On a fresh boot the saved
values would not reach Java until the first Options-screen visit. We
load-and-push on `OnGameBoot`:

```lua
local function syncToJava()
    PZAPI.ModOptions:load()
    ModJava.setEnabled(enableOpt:getValue())
    ModJava.setRange(rangeOpt:getValue())
    ModJava.setFixB42Adjacency(fixB42Opt:getValue())
    ModJava.setAimStanceOnly(aimStanceOnlyOpt:getValue())
    ModJava.setMaxDrivingSpeedKmh(drivingSpeedOpt:getValue())
end

Events.OnGameBoot.Add(syncToJava)
```

Idempotent with `MainOptions`' later load.

## Exports

```lua
PeekAView.syncToJava = syncToJava
PeekAView.enableOpt / rangeOpt / fixB42Opt / aimStanceOnlyOpt / drivingSpeedOpt
```

## Translations

Keys live in `media/lua/shared/Translate/<LANG>/UI.json`. 12 languages
ship: EN, DE, FR, ES, RU, PL, PTBR, IT, TR, CN, KO, JP (same set as
PassThroughWindow / AutoFFWalkTo). Folder is `PTBR` (no dash); user-facing
label is `PT-BR`.

Keys:
- `UI_PAV_ModName`
- `UI_PAV_EnableLabel` / `UI_PAV_EnableTooltip`
- `UI_PAV_AimStanceOnlyLabel` / `UI_PAV_AimStanceOnlyTooltip`
- `UI_PAV_RangeLabel` / `UI_PAV_RangeTooltip`
- `UI_PAV_RangePerformanceDescription`
- `UI_PAV_DrivingSpeedLabel` / `UI_PAV_DrivingSpeedTooltip` / `UI_PAV_DrivingSpeedDescription`
- `UI_PAV_FixB42Label` / `UI_PAV_FixB42Tooltip`
- `UI_PAV_Spacer` (single space, used via `addDescription` as vertical separator)
- `UI_PAV_EnabledText` / `UI_PAV_DisabledText` (halo toggle text)
- `UI_optionscreen_binding_PeekAView` / `UI_optionscreen_binding_PeekAView Toggle`
