-- M20b remixed_full ally: Guard Pup. A sturdy melee escort that follows the
-- hero (inherited DirectableAlly AI) and lands a bonus cleave hit on whatever
-- it strikes. Not in the vanilla spawn pool — only enters a level via
-- RPD.spawnAlly("remixed_full_guard_pup", pos). Pure content: uses the existing
-- attackProc(selfId, enemyId, baseDamage) seam (enemyId is the hostile mob Java
-- already targeted for the ally), so it needs no hero-id reference.
register_ally {
    id = "remixed_full_guard_pup",
    name = "护卫幼兽",
    hp = 25,
    ht = 25,
    attack = 9,
    defense = 4,
    sprite = "brute",

    -- Cleave: on every landed hit, deal 2 flat extra damage to the same enemy
    -- (routed through Char.damage so shields/buffs apply). Returning baseDamage
    -- unchanged keeps the normal hit; the extra is a side-effect.
    attackProc = function(selfId, enemyId, baseDamage)
        if RPD and RPD.damageChar then
            RPD.damageChar(enemyId, 2)
        end
        return baseDamage
    end,

    -- Advisory only (matches the test_ally PoC): Java has already applied the
    -- follow/defend/attack command; this just emits feedback.
    onCommand = function(selfId, cmd, targetId)
        if cmd == "follow" then
            RPD.GLog("护卫幼兽紧跟在你身后。")
        elseif cmd == "defend" then
            RPD.GLog("护卫幼兽开始守卫该位置。")
        elseif cmd == "attack" then
            RPD.GLog("护卫幼兽扑向目标！")
        end
    end,
}
