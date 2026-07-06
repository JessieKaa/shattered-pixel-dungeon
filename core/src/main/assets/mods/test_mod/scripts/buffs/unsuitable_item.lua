-- M6c port of Remished scripts/buffs/UnsuitableItem.lua
-- Remished: random yell on charAct about an unsuitable item. M6c has no
-- charAct or belongings bridge; degraded to metadata-only.
register_buff{
    id = "unsuitable_item",
    name = "UnsuitableItem",
    info = "UnsuitableItem (M6c degraded: encumbrance/yell hooks not bridged)",
    icon = 52,
    degraded = true,
    degradation = "Remished uses target:getBelongings() + target:yell(); M6c exposes neither for a generic buff.",

    attachTo = function(targetId, state)
        return true
    end,
}
