-- M10a port of Remished TestSpell. Original: no-op self spell for dev/sanity.
-- Faithful port: register a no-op spell so the Dev affinity list has a test entry.
-- Distinct id from M6d's test_spell.lua (the SPD-fork test fixture) to avoid a registry collision.
-- Original: ../remixed-dungeon/scripts/spells/TestSpell.lua
register_spell {
    id = "remished_test_spell",
    name = "测试法术(Remished)",
    desc = "Remished TestSpell 的占位移植:使用即无效,仅验证注册。",
    image = 1,
    castTime = 1,
    useMode = "mana",
    spellCost = 0,
    targeting = "self",

    onUse = function(heroId)
        return true
    end,
}
