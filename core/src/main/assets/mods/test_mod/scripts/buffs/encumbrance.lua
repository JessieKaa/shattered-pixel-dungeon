-- M7b port of Remished scripts/buffs/Encumbrance.lua
-- Remished: on charAct, 5% chance to yell a phrase about the most encumbering
-- equipped item (encumbranceCheck). M7b bridges both hooks: charAct (fired by
-- Actor.process every Char turn) + RPD.encumbranceItemName (STR-overload check)
-- + RPD.yell. Phrases are inline ZH (fork does not ship Remished i18n keys).
register_buff{
    id = "encumbrance",
    name = "Encumbrance",
    info = "Encumbrance (M7b: charAct random yell about encumbering item)",
    icon = 50,

    attachTo = function(targetId, state)
        return true
    end,

    charAct = function(selfId, targetId, state)
        if math.random() < 0.05 then
            local item = RPD.encumbranceItemName(targetId)
            if item then
                RPD.yell(targetId, "好沉的 " .. item .. "!")
            end
        end
    end,
}
