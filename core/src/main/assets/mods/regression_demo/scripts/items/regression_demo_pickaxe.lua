-- M6d item + M11c action/state/glowing + RPD dig API representative.
-- A LuaItem (MeleeWeapon wrapper) exercising the M11c action layer end-to-end:
--   actions/actionNames        — declare a Lua-only MINE action (built-ins auto-kept)
--   defaultAction(state)       — fn(state) form: MINE when equipped, else EQUIP
--   execute(heroId,action,state) — dispatch MINE → RPD.dig + RPD.dropItem
--   glowing(state)             — {color,period} after first dig (state-persisted flag)
--   onEquip/onDeactivate(state) — seed state.equipped for defaultAction
--   attackProc(atk,def,dmg,state) — the 4-arg M11c signature (+1 damage bump)
-- RPD API hit: charPos, isSolid, levelWidth, dig, dropItem, GLog/GLogW.
register_item {
    id = "regression_demo_pickaxe",
    name = "回归鹤嘴锄 (Lua)",
    desc = "M11c 动作层 + RPD dig API 压测:装备后可挖墙(挖后发红光),命中加 1 伤。",
    image = 75,
    tier = 3,

    actions = {"MINE"},
    actionNames = {
        MINE = "挖矿",
    },

    defaultAction = function(state)
        return state.equipped and "MINE" or "EQUIP"
    end,

    onEquip = function(heroId, state)
        state.equipped = true
    end,

    onDeactivate = function(heroId, state)
        state.equipped = false
    end,

    execute = function(heroId, action, state)
        if action ~= "MINE" then return end

        local heroPos = RPD.charPos(heroId)
        if heroPos == nil then return end

        -- scan forward 1..3 cells (same row), then 8 neighbours for the nearest solid
        local target = -1
        for dist = 1, 3 do
            local c = heroPos + dist
            if RPD.isSolid(c) then target = c break end
        end
        if target == -1 then
            local w = RPD.levelWidth()
            if w ~= nil then
                local dirs = {-(w + 1), -w, -(w - 1), -1, 1, w - 1, w, w + 1}
                for _, d in ipairs(dirs) do
                    local c = heroPos + d
                    if RPD.isSolid(c) then target = c break end
                end
            end
        end

        if target == -1 then
            RPD.GLogW("这里没什么可挖的。")
            return
        end

        if RPD.dig(target) then
            state.dug = true
            RPD.dropItem(target, "dark_gold", 1)
            RPD.GLog("[regression_demo] 挖开了这面墙。")
        else
            RPD.GLogW("这里无法挖掘。")
        end
    end,

    attackProc = function(attackerId, defenderId, baseDamage, state)
        return baseDamage + 1
    end,

    glowing = function(state)
        if state.dug then
            return { color = 0xAA0000, period = 0.3 }
        end
        return nil
    end,
}
