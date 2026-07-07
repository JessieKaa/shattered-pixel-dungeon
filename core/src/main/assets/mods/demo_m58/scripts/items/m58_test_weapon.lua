-- M6d demo: LuaItem (MeleeWeapon subclass) exercising all three item callbacks
-- AND giving every demo buff a trigger path:
--   onEquip(heroId)    — attach the 4 hero-side buffs (combat/shield/mana/tint)
--   attackProc(atk,def,dmg) — apply sleep_lock_demo to the ENEMY on hit
--                             (sleep-lock is enemy-targeted, anesthesia-style)
--                             + small damage bump
--   onDeactivate(heroId) — log on unequip
-- LuaItem has NO onUse (only attackProc/onEquip/onDeactivate — see LuaItem.java).
-- The weapon is registered into the LUA_ITEM pool, but Generator.Category.
-- LUA_ITEM has probability 0 (Generator.java:261) so it does not drop naturally;
-- obtain it via debug Generator.random(Category.LUA_ITEM) or by upgrading the
-- MOD_SECOND_TALENT talent (on_upgrade giveItem below).
register_item {
    id = "m58_test_weapon",
    name = "M58 测试武器 (Lua)",
    desc = "装备触发 combat/shield/mana/tint 四 buff;命中给敌人挂 sleep_lock。覆盖 LuaItem onEquip/attackProc/onDeactivate。",
    image = 99,
    tier = 2,

    onEquip = function(heroId)
        RPD.affectBuff(heroId, "combat_hook_demo", 1)
        RPD.affectBuff(heroId, "shield_demo", 1)
        RPD.affectBuff(heroId, "mana_demo", 1)
        RPD.affectBuff(heroId, "tint_demo", 1)
        RPD.GLog("[demo_m58] weapon equipped → 4 hero buffs applied")
    end,

    attackProc = function(attackerId, defenderId, baseDamage)
        RPD.affectBuff(defenderId, "sleep_lock_demo", 1)
        RPD.GLog("[demo_m58] weapon hit " .. defenderId .. " → sleep_lock applied")
        return baseDamage + 1
    end,

    onDeactivate = function(heroId)
        RPD.GLog("[demo_m58] weapon unequipped")
    end,
}
