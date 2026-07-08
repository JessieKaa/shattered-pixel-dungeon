-- M10a port of Remished SpellsByAffinity. NOT a spell — a helper module over the spell-affinity table.
-- SPD fork has no affinity system; this is a documentation stub mirroring the remished API
-- (getSpellsList / haveSpell / loadSpells) over an inline copy of CustomSpellsList.
-- Does not call register_spell. Note: SPD has no require("scripts/...") module path, so the table
-- is inlined here to keep this file loadable without a resolver.
-- Original: ../remixed-dungeon/scripts/spells/SpellsByAffinity.lua
local spells = {
    Necromancy   = {"raise_dead", "exhumation", "dark_sacrifice", "possess"},
    Common       = {"town_portal", "heal", "raise_dead", "cloak", "calm", "charm"},
    Combat       = {"die_hard", "dash", "body_armor", "smash"},
    Rogue        = {"cloak", "backstab", "kunai_throw", "haste"},
    Witchcraft   = {"roar", "lightning_bolt", "heal", "order"},
    Huntress     = {"calm", "charm", "shoot_in_eye", "summon_beast"},
    Elf          = {"magic_arrow", "sprout", "hide_in_grass", "nature_armor"},
    Priest       = {"heal", "calm", "charm", "order"},
    PlagueDoctor = {"anesthesia", "heal", "blood_transfusion", "corpse_explosion"},
    Dev          = {"remished_test_spell", "curse_item"},
}

local module = {}
function module.getSpellsList(affinity)
    if affinity then return spells[affinity] or {} end
    local ret = {}
    for _, aff in pairs(spells) do
        for _, s in pairs(aff) do table.insert(ret, s) end
    end
    return ret
end
function module.haveSpell(spell)
    for _, aff in pairs(spells) do
        for _, s in pairs(aff) do
            if s == spell then return true end
        end
    end
    return false
end
function module.loadSpells()
    -- SPD loads spell scripts via LuaEngine.scanScripts, not require(); this is a no-op stub
    -- preserved for API parity with the remished helper.
end
return module
