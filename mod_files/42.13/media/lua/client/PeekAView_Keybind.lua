-- Hotkey to flip the master Enable toggle in normal gameplay. The key
-- is registered in shared/keyBinding.lua with default F8; users can
-- rebind it in Options → Keybinds → [PeekAView]. We read the live
-- key code via getCore():getKey so a rebind takes effect immediately.

require "PeekAView_Options"

local BINDING_NAME = "PeekAView Toggle"

local function showHalo(label, color)
    local player = getPlayer()
    if HaloTextHelper and player then
        HaloTextHelper.addText(player, label, "", color)
    else
        print("[PeekAView] " .. label)
    end
end

local function onKeyPressed(key)
    if key ~= getCore():getKey(BINDING_NAME) then return end

    -- Java bridge missing: show error halo instead of silently no-op'ing
    -- the toggle. javaReady is set by PeekAView_Options on load.
    if not (PeekAView and PeekAView.javaReady) then
        showHalo(getText("UI_PAV_JavaMissingHalo"),
                 HaloTextHelper and HaloTextHelper.getColorRed())
        return
    end

    local opt = PeekAView.enableOpt
    if not opt then return end
    local newValue = not opt:getValue()
    opt:setValue(newValue)

    local ok, err = pcall(PeekAViewMod.setEnabled, newValue)
    if not ok then
        PeekAView.javaReady = false
        PeekAView.lastJavaError = tostring(err)
        print("[PeekAView] setEnabled failed: " .. tostring(err))
        showHalo(getText("UI_PAV_JavaMissingHalo"),
                 HaloTextHelper and HaloTextHelper.getColorRed())
        return
    end

    -- opt:setValue only updates the in-memory ModOption; PZAPI persists
    -- to ModOptions.ini on Apply from the options screen. Without this
    -- save, hotkey toggles are lost across restarts and the next launch
    -- reads the last screen-applied value.
    PZAPI.ModOptions:save()

    local label = getText(newValue and "UI_PAV_EnabledText" or "UI_PAV_DisabledText")
    local color = newValue and HaloTextHelper.getColorGreen() or HaloTextHelper.getColorRed()
    showHalo(label, color)
end

Events.OnKeyPressed.Add(onKeyPressed)
