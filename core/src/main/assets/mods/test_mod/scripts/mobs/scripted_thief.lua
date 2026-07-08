-- M10a port of remished ScriptedThief.lua. On attack, steals one random
-- backpack item from the hero (once — the stolen item drops as loot on the
-- mob's death), then switches to fleeing. Faithful via the M6d item API
-- (stealRandomItem/stolenLootName).
-- Note: RPD.GLog takes a single string (no %s formatting), so the line is
-- built with '..' and RPD.charName — Lua holds no Char object, only ids.
register_mob {
    id = "scripted_thief",
    name = "Scripted Thief",
    hp = 22,
    ht = 22,
    attack = 8,
    defense = 4,
    sprite = "gnoll",

    attackProc = function(selfId, enemyId, baseDamage)
        if RPD.stolenLootName(selfId) == nil then
            local stolen = RPD.stealRandomItem(selfId, enemyId)
            if stolen ~= nil then
                RPD.GLog(RPD.charName(selfId) .. " stole " .. stolen
                        .. " from " .. RPD.charName(enemyId))
                RPD.setMobAi(selfId, "fleeing")
            end
        end
        return baseDamage
    end,
}
