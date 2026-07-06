-- M6c port of Remished scripts/buffs/Encumbrance.lua
-- Remished: random yell on charAct about the most encumbering item. M6c has no
-- charAct or belongings/encumbrance bridge; degraded to metadata-only.
register_buff{
    id = "encumbrance",
    name = "Encumbrance",
    info = "Encumbrance (M6c degraded: encumbrance/yell hooks not bridged)",
    icon = 50,
    degraded = true,
    degradation = "Remished uses target:getBelongings():encumbranceCheck() and target:yell(); M6c exposes neither for a generic buff.",

    attachTo = function(targetId, state)
        return true
    end,
}
