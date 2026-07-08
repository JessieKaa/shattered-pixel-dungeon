-- M11a guard buff for chaos_shield. Implements Remished charge-up/leveling approximation:
-- owner "does damage" -> attackProc adds charge; owner "takes damage" -> damage callback
-- drains charge. Full charge upgrades the shield (max 5), empty charge degrades it (min 1).
local MAX_LEVEL = 5
local MIN_LEVEL = 1

local function chargeForLevel(level)
    return math.ceil(5 * math.pow(level, 1.5))
end

register_buff {
    id = "chaos_shield_guard",
    name = "混沌盾守护",
    info = "混沌之盾随战斗蓄能升级或耗竭降级。",

    attachTo = function(targetId, state)
        state.level = state.level or 3
        state.charge = state.charge or 0
        return true
    end,

    drBonus = function(selfId, state)
        return state.level or 3
    end,

    attackProc = function(selfId, enemyId, damage, state)
        if not state then return damage end
        local level = state.level or 3
        local charge = state.charge or 0
        local cap = chargeForLevel(level)
        local gained = math.max(1, math.floor(damage / 2))
        charge = math.min(cap, charge + gained)
        if charge >= cap and level < MAX_LEVEL then
            level = level + 1
            charge = 0
        end
        state.level = level
        state.charge = charge
        return damage
    end,

    damage = function(selfId, srcId, damage, state)
        if not state or damage <= 0 then return damage end
        local level = state.level or 3
        local charge = state.charge or 0
        local lost = math.max(1, math.floor(damage / 3))
        charge = charge - lost
        if charge < 0 then
            local overflow = -charge
            charge = 0
            if level > MIN_LEVEL then
                level = level - 1
                -- carry overflow into the new level's half-seeded charge so repeated hits matter
                local newCap = chargeForLevel(level)
                charge = math.min(math.floor(newCap / 2) + overflow, newCap)
            end
        end
        state.level = level
        state.charge = charge
        return damage
    end,

    setGlowing = function(selfId, state)
        if (state.charge or 0) > 0 then
            return 0x9900CC -- chaotic purple aura when charged
        end
        return nil
    end,
}
