-- M20b remixed_full ally: Healing Wisp. A fragile floating support that trails
-- the hero (inherited DirectableAlly follow AI) and periodically mends nearby
-- wounds. Not in the vanilla spawn pool — only enters a level via
-- RPD.spawnAlly("remixed_full_healing_wisp", pos).
--
-- Design note: this is the ally that motivated RPD.heroId() (decision A). An
-- ally's act(selfId) previously had no way to reference the hero — every other
-- hero-id API *receives* the id as a parameter. Now act can fetch it directly.
--
-- act returns false every tick so the upstream DirectableAlly AI still runs
-- (keeps following/defending the hero); the heal is a side-effect, gated on
-- proximity so the wisp must stay close, and probabilistic (~25%/tick ≈ once
-- every 4 ticks) so the cadence is readable in combat. The roll is stateless
-- per call, so multiple wisps heal independently rather than in lockstep.
register_ally {
    id = "remixed_full_healing_wisp",
    name = "治愈光灵",
    hp = 12,
    ht = 12,
    attack = 3,
    defense = 2,
    sprite = "bat",

    act = function(selfId)
        local hero = RPD.heroId()
        if hero == nil then
            return false
        end
        local d = RPD.cellDistance(RPD.charPos(selfId), RPD.charPos(hero))
        if d ~= nil and d <= 2 and math.random() < 0.25 then
            RPD.healChar(hero, 4)
        end
        return false
    end,

    onCommand = function(selfId, cmd, targetId)
        if cmd == "follow" then
            RPD.GLog("治愈光灵轻盈地飘向你。")
        elseif cmd == "defend" then
            RPD.GLog("治愈光灵在原地缓缓盘旋。")
        elseif cmd == "attack" then
            RPD.GLog("治愈光灵不擅战斗,但顺从了你的指令。")
        end
    end,
}
