-- M10a port of Remished CustomSpellsList. NOT a spell — an affinity→spell-id metadata table.
-- SPD fork has no magic-affinity system; this is a documentation stub returning the remished
-- grouping with ids lowered to the snake_case M10a ports. Loaded as data; does not call register_spell.
-- Original: ../remixed-dungeon/scripts/spells/CustomSpellsList.lua
return {
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
