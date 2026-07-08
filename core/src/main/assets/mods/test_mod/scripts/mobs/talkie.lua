-- M10a port of remished Talkie.lua. Source greets on interact
-- (self:say("Hello!")). LuaMob is hostile and has no `interact` hook (that is
-- NPC-only via LuaNpc / M4b), so the greeting degrades to a yell fired from
-- the one-shot `spawn` callback (which runs on the mob's first act).
register_mob {
    id = "talkie",
    name = "Talkie",
    hp = 15,
    ht = 15,
    attack = 5,
    defense = 3,
    sprite = "skeleton",

    spawn = function(selfId)
        RPD.yell(selfId, "Hello!")
    end,
}
