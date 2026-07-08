-- M10a port of remished NatureAura.lua. Immune to Roots; spreads Regrowth
-- where it stands.
-- Degradations:
--   * no `stats` callback -> Roots immunity is added in the one-shot `spawn`
--     callback (fires on the mob's first act).
--   * no `move` callback -> Regrowth is placed each tick via `act`, which
--     returns false so the upstream Mob AI still runs (paths/attacks). The
--     cadence is therefore per-tick rather than per-step.
register_mob {
    id = "nature_aura",
    name = "Nature Aura",
    hp = 28,
    ht = 28,
    attack = 7,
    defense = 6,
    sprite = "slime",

    spawn = function(selfId)
        RPD.addImmunity(selfId, RPD.Buffs.Roots)
    end,

    act = function(selfId)
        RPD.placeBlob(RPD.Blobs.Regrowth, RPD.charPos(selfId), 10)
        return false
    end,
}
