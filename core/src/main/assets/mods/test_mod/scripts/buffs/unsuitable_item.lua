-- M7b port of Remished scripts/buffs/UnsuitableItem.lua
-- Remished: charAct had a 5% yell about an unsuitable (STR-overloaded) item,
-- but the yell line was commented out in source — so the buff was a no-op.
-- M7b upgrades the stub to the intended behaviour: charAct + RPD.encumbranceItemName
-- + RPD.yell. "Unsuitable" maps to the same STR-overload check as encumbrance
-- (Remished reused encumbranceCheck); the difference is flavour text.
register_buff{
    id = "unsuitable_item",
    name = "UnsuitableItem",
    info = "UnsuitableItem (M7b: charAct random yell about unsuitable item)",
    icon = 52,

    attachTo = function(targetId, state)
        return true
    end,

    charAct = function(selfId, targetId, state)
        if math.random() < 0.05 then
            local item = RPD.encumbranceItemName(targetId)
            if item then
                RPD.yell(targetId, "这件 " .. item .. " 不称手!")
            end
        end
    end,
}
