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

modOptions:addDescription("UI_PAV_Spacer")

-- == Wall cutaway ==
addSection(modOptions, "UI_PAV_WallCutawaySectionTitle")

local cutawayEnabledOpt = modOptions:addTickBox(
    "cutawayEnabled",
    getText("UI_PAV_CutawayEnabledLabel"),
    true,
    getText("UI_PAV_CutawayEnabledTooltip"))
cutawayEnabledOpt.onChangeApply = function(self, value)
    applyToJava("setCutawayEnabled", value)
end

-- Persistence key bumped from "range" to "cutawayRange" in 1.3.0
-- so the slider resets to its new default 10 (down from 15) on
-- first launch with this version. PZAPI ModOptions persists by the
-- first string arg, so the rename invalidates saved values for this
-- slider only — no other settings touched.
local rangeOpt = modOptions:addSlider(
    "cutawayRange",
    getText("UI_PAV_RangeLabel"),
    5, 20, 1,
    10,
    getText("UI_PAV_RangeTooltip"))
rangeOpt.onChangeApply = function(self, value)
    applyToJava("setRange", value)
end

modOptions:addDescription("UI_PAV_RangePerformanceDescription")

local aimStanceOnlyOpt = modOptions:addTickBox(
    "aimStanceOnly",
    getText("UI_PAV_AimStanceOnlyLabel"),
    false,
    getText("UI_PAV_AimStanceOnlyTooltip"))
aimStanceOnlyOpt.onChangeApply = function(self, value)
    applyToJava("setAimStanceOnly", value)
end

-- Binary on/off in vehicles. Replaces the prior km/h slider in 1.3.0.
-- Default on — pairs with the lower cutaway range default (10) since
-- the smaller raster is unobtrusive enough to keep running while driving.
local cutawayActiveInVehicleOpt = modOptions:addTickBox(
    "cutawayActiveInVehicle",
    getText("UI_PAV_CutawayActiveInVehicleLabel"),
    true,
    getText("UI_PAV_CutawayActiveInVehicleTooltip"))
cutawayActiveInVehicleOpt.onChangeApply = function(self, value)
    applyToJava("setCutawayActiveInVehicle", value)
end

local fixB42Opt = modOptions:addTickBox(
    "fixB42",
    getText("UI_PAV_FixB42Label"),
    false,
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
    15, 25, 1,
    15,
    getText("UI_PAV_TreeFadeRangeTooltip"))
treeFadeRangeOpt.onChangeApply = function(self, value)
    applyToJava("setTreeFadeRange", value)
end

modOptions:addDescription("UI_PAV_Spacer")

-- == Stairs ==
addSection(modOptions, "UI_PAV_StairsSectionTitle")

-- One-shot probe of upstream Staircast presence. Java side gates
-- internally regardless of this UI state — the description here is
-- purely informational so the user understands why the stair feature
-- silently does nothing.
local stairConflictActive = false
if PeekAView.javaReady and ModJava.isExternalStairFeatureActive then
    local ok, result = pcall(ModJava.isExternalStairFeatureActive)
    if ok then stairConflictActive = result end
end

if stairConflictActive then
    modOptions:addDescription("UI_PAV_StairsConflictDescription")
end

local stairEnabledOpt = modOptions:addTickBox(
    "stairEnabled",
    getText("UI_PAV_StairEnabledLabel"),
    true,
    getText("UI_PAV_StairEnabledTooltip"))
stairEnabledOpt.onChangeApply = function(self, value)
    applyToJava("setStairEnabled", value)
end

-- PZAPI.ModOptions:load() only auto-runs on first Options-screen open.
-- Load+push on OnGameBoot so patches see saved values from frame one.
-- Idempotent with MainOptions' later load.
local function syncToJava()
    PZAPI.ModOptions:load()
    applyToJava("setEnabled", enableOpt:getValue())
    applyToJava("setCutawayEnabled", cutawayEnabledOpt:getValue())
    applyToJava("setAimStanceOnly", aimStanceOnlyOpt:getValue())
    applyToJava("setRange", rangeOpt:getValue())
    applyToJava("setCutawayActiveInVehicle", cutawayActiveInVehicleOpt:getValue())
    applyToJava("setFixB42Adjacency", fixB42Opt:getValue())
    applyToJava("setFadeNWTrees", fadeNWTreesOpt:getValue())
    applyToJava("setTreeFadeRange", treeFadeRangeOpt:getValue())
    applyToJava("setStairEnabled", stairEnabledOpt:getValue())
end

Events.OnGameBoot.Add(syncToJava)

PeekAView.syncToJava = syncToJava
PeekAView.enableOpt = enableOpt
PeekAView.cutawayEnabledOpt = cutawayEnabledOpt
PeekAView.aimStanceOnlyOpt = aimStanceOnlyOpt
PeekAView.rangeOpt = rangeOpt
PeekAView.cutawayActiveInVehicleOpt = cutawayActiveInVehicleOpt
PeekAView.fixB42Opt = fixB42Opt
PeekAView.fadeNWTreesOpt = fadeNWTreesOpt
PeekAView.treeFadeRangeOpt = treeFadeRangeOpt
PeekAView.stairEnabledOpt = stairEnabledOpt
