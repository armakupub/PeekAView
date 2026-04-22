-- User-facing options. State lives in Java (PeekAViewMod static
-- volatile fields); this file is the Lua UI that writes via setters.

PeekAView = PeekAView or {}

-- Kahlua global registered by ZombieBuddy from @Exposer.LuaClass under
-- the simple class name. Package paths are not resolvable.
local ModJava = PeekAViewMod

local modOptions = PZAPI.ModOptions:create("PeekAView", getText("UI_PAV_ModName"))

local enableOpt = modOptions:addTickBox(
    "enable",
    getText("UI_PAV_EnableLabel"),
    true,
    getText("UI_PAV_EnableTooltip"))
enableOpt.onChangeApply = function(self, value)
    ModJava.setEnabled(value)
end

local aimStanceOnlyOpt = modOptions:addTickBox(
    "aimStanceOnly",
    getText("UI_PAV_AimStanceOnlyLabel"),
    false,
    getText("UI_PAV_AimStanceOnlyTooltip"))
aimStanceOnlyOpt.onChangeApply = function(self, value)
    ModJava.setAimStanceOnly(value)
end

local rangeOpt = modOptions:addSlider(
    "range",
    getText("UI_PAV_RangeLabel"),
    5, 20, 1,
    15,
    getText("UI_PAV_RangeTooltip"))
rangeOpt.onChangeApply = function(self, value)
    ModJava.setRange(value)
end

modOptions:addDescription("UI_PAV_RangePerformanceDescription")

modOptions:addDescription("UI_PAV_Spacer")

-- Driving-speed threshold. 0 = mod off in any vehicle; higher = active
-- up to that km/h. Range 0-120 covers vanilla car top speeds; step 5
-- gives 25 slider positions on clean integer values.

local drivingSpeedOpt = modOptions:addSlider(
    "drivingSpeed",
    getText("UI_PAV_DrivingSpeedLabel"),
    0, 120, 5,
    35,
    getText("UI_PAV_DrivingSpeedTooltip"))

modOptions:addDescription("UI_PAV_DrivingSpeedDescription")

-- PZAPI.ModOptions:addSlider has no label-formatter hook. Rewrite the
-- JLabel text on each change; shim the label's setName to a no-op the
-- first time we touch it so vanilla's "title<SPACE>value" rewrite
-- doesn't stomp our formatted text. Same pattern as ZBBetterFPS.
local function updateDrivingSpeedLabel(slider, newValue)
    if not slider or not slider.element then return end
    local label = slider.element.label
    if not label or not label.setName then return end
    if not label.setName_PeekAView then
        label.setName_PeekAView = label.setName
        label.setName = function(self, name) end
    end
    newValue = newValue or slider.value or slider:getValue()
    label:setName_PeekAView(tostring(newValue) .. " km/h")
end

drivingSpeedOpt.getValue = function(self)
    updateDrivingSpeedLabel(self, self.value)
    return self.value
end
drivingSpeedOpt.setValue = function(self, value)
    self.value = value
    updateDrivingSpeedLabel(self, value)
end
drivingSpeedOpt.onChange = function(self, newValue)
    updateDrivingSpeedLabel(self, newValue)
end
drivingSpeedOpt.onChangeApply = function(self, value)
    ModJava.setMaxDrivingSpeedKmh(value)
end

modOptions:addDescription("UI_PAV_Spacer")

local fixB42Opt = modOptions:addTickBox(
    "fixB42",
    getText("UI_PAV_FixB42Label"),
    true,
    getText("UI_PAV_FixB42Tooltip"))
fixB42Opt.onChangeApply = function(self, value)
    ModJava.setFixB42Adjacency(value)
end

-- PZAPI.ModOptions:load() only auto-runs on first Options-screen open.
-- Load+push on OnGameBoot so patches see saved values from frame one.
-- Idempotent with MainOptions' later load.
local function syncToJava()
    PZAPI.ModOptions:load()
    ModJava.setEnabled(enableOpt:getValue())
    ModJava.setRange(rangeOpt:getValue())
    ModJava.setFixB42Adjacency(fixB42Opt:getValue())
    ModJava.setAimStanceOnly(aimStanceOnlyOpt:getValue())
    ModJava.setMaxDrivingSpeedKmh(drivingSpeedOpt:getValue())
end

Events.OnGameBoot.Add(syncToJava)

PeekAView.syncToJava = syncToJava
PeekAView.enableOpt = enableOpt
PeekAView.rangeOpt = rangeOpt
PeekAView.fixB42Opt = fixB42Opt
PeekAView.aimStanceOnlyOpt = aimStanceOnlyOpt
PeekAView.drivingSpeedOpt = drivingSpeedOpt
