-- User-facing options. State lives in Java (PeekAViewMod static
-- volatile fields); this file is the Lua UI that writes via setters.

PeekAView = PeekAView or {}

-- Kahlua global registered by ZombieBuddy from @Exposer.LuaClass.
-- nil only when the user declined loading the mod's JAR through
-- ZombieBuddy's startup prompt — ZombieBuddy is a hard dependency
-- so its own absence isn't a reachable state. When nil, applyToJava
-- no-ops and the warning surfaces in options + on F8.
local ModJava = PeekAViewMod
PeekAView.javaReady = ModJava ~= nil
PeekAView.lastJavaError = nil

local function applyToJava(setterName, value)
    if not PeekAView.javaReady then return end
    local fn = ModJava[setterName]
    if not fn then
        PeekAView.javaReady = false
        PeekAView.lastJavaError = "missing setter: " .. setterName
        print("[PeekAView] Java setter missing: " .. setterName)
        return
    end
    local ok, err = pcall(fn, value)
    if not ok then
        PeekAView.javaReady = false
        PeekAView.lastJavaError = tostring(err)
        print("[PeekAView] Java call " .. setterName .. " failed: " .. tostring(err))
    end
end

local modOptions = PZAPI.ModOptions:create("PeekAView", getText("UI_PAV_ModName"))

if not PeekAView.javaReady then
    modOptions:addDescription("UI_PAV_JavaMissingDescription")
    modOptions:addDescription("UI_PAV_Spacer")
end

-- PZAPI may or may not have addTitle in any given build; addDescription
-- always exists. Use the larger title widget when available, fall back
-- to a description so a missing API doesn't crash the options screen.
local function addSection(opts, key)
    if opts.addTitle then
        opts:addTitle(getText(key))
    else
        opts:addDescription(getText(key))
    end
end

-- == Global ==
addSection(modOptions, "UI_PAV_GlobalSectionTitle")

local enableOpt = modOptions:addTickBox(
    "enable",
    getText("UI_PAV_EnableLabel"),
    true,
    getText("UI_PAV_EnableTooltip"))
enableOpt.onChangeApply = function(self, value)
    applyToJava("setEnabled", value)
end

local aimStanceOnlyOpt = modOptions:addTickBox(
    "aimStanceOnly",
    getText("UI_PAV_AimStanceOnlyLabel"),
    false,
    getText("UI_PAV_AimStanceOnlyTooltip"))
aimStanceOnlyOpt.onChangeApply = function(self, value)
    applyToJava("setAimStanceOnly", value)
end

modOptions:addDescription("UI_PAV_Spacer")

-- == Wall cutaway ==
addSection(modOptions, "UI_PAV_WallCutawaySectionTitle")

local rangeOpt = modOptions:addSlider(
    "range",
    getText("UI_PAV_RangeLabel"),
    5, 20, 1,
    15,
    getText("UI_PAV_RangeTooltip"))
rangeOpt.onChangeApply = function(self, value)
    applyToJava("setRange", value)
end

modOptions:addDescription("UI_PAV_RangePerformanceDescription")

-- Driving-speed threshold for wall cutaway. 0 = off in any vehicle;
-- higher = active up to that km/h. Range 0-120 covers vanilla car top
-- speeds; step 5 gives 25 slider positions on clean integer values.
local drivingSpeedOpt = modOptions:addSlider(
    "drivingSpeed",
    getText("UI_PAV_DrivingSpeedLabel"),
    0, 120, 5,
    35,
    getText("UI_PAV_DrivingSpeedTooltip"))
drivingSpeedOpt.onChangeApply = function(self, value)
    applyToJava("setMaxDrivingSpeedKmh", value)
end

modOptions:addDescription("UI_PAV_DrivingSpeedDescription")

local fixB42Opt = modOptions:addTickBox(
    "fixB42",
    getText("UI_PAV_FixB42Label"),
    true,
    getText("UI_PAV_FixB42Tooltip"))
fixB42Opt.onChangeApply = function(self, value)
    applyToJava("setFixB42Adjacency", value)
end

modOptions:addDescription("UI_PAV_Spacer")

-- == Tree fade ==
addSection(modOptions, "UI_PAV_TreeFadeSectionTitle")

local fadeNWTreesOpt = modOptions:addTickBox(
    "fadeNWTrees",
    getText("UI_PAV_FadeNWTreesLabel"),
    true,
    getText("UI_PAV_FadeNWTreesTooltip"))
fadeNWTreesOpt.onChangeApply = function(self, value)
    applyToJava("setFadeNWTrees", value)
end

local treeFadeRangeOpt = modOptions:addSlider(
    "treeFadeRange",
    getText("UI_PAV_TreeFadeRangeLabel"),
    5, 25, 1,
    20,
    getText("UI_PAV_TreeFadeRangeTooltip"))
treeFadeRangeOpt.onChangeApply = function(self, value)
    applyToJava("setTreeFadeRange", value)
end

-- Driving-speed threshold for tree fade. Default 50 — at higher speeds
-- the per-tree fade-up animation can't keep up with how fast scenery
-- enters the screen, so trees pop instead of easing. 0 = off in any
-- vehicle.
local treeFadeDrivingSpeedOpt = modOptions:addSlider(
    "treeFadeDrivingSpeed",
    getText("UI_PAV_TreeFadeDrivingSpeedLabel"),
    0, 120, 5,
    50,
    getText("UI_PAV_TreeFadeDrivingSpeedTooltip"))
treeFadeDrivingSpeedOpt.onChangeApply = function(self, value)
    applyToJava("setTreeFadeMaxDrivingSpeedKmh", value)
end

modOptions:addDescription("UI_PAV_TreeFadeDrivingSpeedDescription")

-- PZAPI.ModOptions:load() only auto-runs on first Options-screen open.
-- Load+push on OnGameBoot so patches see saved values from frame one.
-- Idempotent with MainOptions' later load.
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

PeekAView.syncToJava = syncToJava
PeekAView.enableOpt = enableOpt
PeekAView.aimStanceOnlyOpt = aimStanceOnlyOpt
PeekAView.rangeOpt = rangeOpt
PeekAView.drivingSpeedOpt = drivingSpeedOpt
PeekAView.fixB42Opt = fixB42Opt
PeekAView.fadeNWTreesOpt = fadeNWTreesOpt
PeekAView.treeFadeRangeOpt = treeFadeRangeOpt
PeekAView.treeFadeDrivingSpeedOpt = treeFadeDrivingSpeedOpt
