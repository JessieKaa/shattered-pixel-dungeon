-- M10a port of Remished DieHard. Original: self buff "DieHard"(custom damage reduction) + level scaling.
-- Degrade: DieHard custom buff (needs M10c) → Barkskin (whitelist armor buff). buffLevel / skill scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/DieHard.lua
register_spell {
    id = "die_hard",
    name = "坚韧术",
    desc = "获得树皮护甲抵御伤害(Barkskin 近似;原版 DieHard 自定义减伤 buff,降级为 Barkskin)。",
    image = 1,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Barkskin", 40)
        end
    end,
}
