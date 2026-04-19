-- Hotkey to flip the master Enable toggle in normal gameplay. The key
-- is registered in shared/keyBinding.lua with default F8; users can
-- rebind it in Options → Keybinds → [PeekAView]. We read the live
-- key code via getCore():getKey so a rebind takes effect immediately.

require "PeekAView_Options"

local BINDING_NAME = "PeekAView Toggle"

local function onKeyPressed(key)
    if key ~= getCore():getKey(BINDING_NAME) then return end
    local opt = PeekAView and PeekAView.enableOpt
    if not opt then return end
    local newValue = not opt:getValue()
    opt:setValue(newValue)
    PeekAViewMod.setEnabled(newValue)

    local label = getText(newValue and "UI_PAV_EnabledText" or "UI_PAV_DisabledText")
    local player = getPlayer()
    if HaloTextHelper and player then
        local color = newValue and HaloTextHelper.getColorGreen() or HaloTextHelper.getColorRed()
        HaloTextHelper.addText(player, label, "", color)
    else
        print("[PeekAView] " .. label)
    end
end

Events.OnKeyPressed.Add(onKeyPressed)
