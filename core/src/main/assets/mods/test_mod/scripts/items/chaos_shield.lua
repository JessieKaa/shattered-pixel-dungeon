-- M11a: Remished ChaosShield(混沌盾)。走 LuaItem(weapon 占位 wrapper)。
-- 混沌盾的动态升降级通过 `chaos_shield_guard` LuaBuff 的 state 实现;
-- 命中蓄能(attackProc)近 ownerDoesDamage,受击耗能(damage callback)近 ownerTakesDamage。
local SHIELD_LEVEL = 3
local GUARD_BUFF = "chaos_shield_guard"

register_item {
    id = "chaos_shield",
    name = "混沌盾",
    desc = "一面随战况自我进化的混沌之盾:命中蓄能可升级、受击耗能将降级(无左手槽,占用武器槽;随机混沌格挡特效仍降级)。",
    image = 64,
    tier = 3,
    price = 60,
    shieldLevel = SHIELD_LEVEL,

    onEquip = function(heroId)
        if RPD and RPD.permanentBuff then
            -- seed the guard at level 3; the buff carries per-instance state
            RPD.permanentBuff(heroId, GUARD_BUFF, SHIELD_LEVEL)
        end
    end,

    onDeactivate = function(heroId)
        if RPD and RPD.removeBuff then
            RPD.removeBuff(heroId, GUARD_BUFF)
        end
    end,
}
