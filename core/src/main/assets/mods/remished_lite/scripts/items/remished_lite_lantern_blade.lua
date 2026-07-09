-- remished_lite showcase weapon: a LuaItem (MeleeWeapon wrapper). Obtained inside the
-- hub via the guide NPC's RPD.giveItem (LuaItemRegistry.createItem path), NOT a main-game
-- drop — Generator.LUA_ITEM has firstProb/secondProb 0 so the standard drop deck never
-- selects it (C3). register_item requires id/name (+ tier unless type="material").
--
-- Balance (M14b, vanilla-baselined): LuaItem extends MeleeWeapon without overriding
-- min()/max()/damageRoll(), so tier=2 gives the vanilla tier2 base of 2-15 — identical to
-- a vanilla Shortsword (MeleeWeapon: min=tier, max=5*(tier+1)). The attackProc is a small
-- on-hit bump that proves the weapon is functional (not a stub). It was +2/hit (4-17),
-- which strictly beat the Shortsword/tier2 damage baseline with no tradeoff (偏 OP); toned
-- to +1/hit (3-16, avg ~9.5 vs Shortsword avg ~8.5) so it stays within the tier2 band.
-- Note: vanilla tier2 also includes Spear/Dirk/Quarterstaff with speed/defense/stealth
-- tradeoffs, so a flat +dmg is a niche, not a blanket dominance over all tier2 weapons.
register_item {
    id = "remished_lite_lantern_blade",
    name = "灯笼刃 (Lua)",
    desc = "Remished Lite 展示武器:命中时额外造成 1 点伤害。由 hub 关卡的向导 NPC 一次性发放。",
    image = 75,
    tier = 2,

    attackProc = function(attackerId, defenderId, baseDamage, state)
        return baseDamage + 1
    end,
}
