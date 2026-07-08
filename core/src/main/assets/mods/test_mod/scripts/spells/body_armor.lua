-- M10a port of Remished BodyArmor. Original: self buff "BodyArmor"(custom) + level scaling.
-- Degrade: BodyArmor is a custom Remished buff (needs M10c); approximated with Barkskin (whitelist armor buff).
-- skillLevel scaling dropped (fixed duration).
-- Original: ../remixed-dungeon/scripts/spells/BodyArmor.lua
register_spell {
    id = "body_armor",
    name = "体甲术",
    desc = "获得树皮护甲(Barkskin 近似;原版 BodyArmor 为自定义 buff,降级为 Barkskin)。",
    image = 0,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Barkskin", 15)
        end
    end,
}
