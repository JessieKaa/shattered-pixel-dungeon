-- M17b port of remished RemixedFetidRat.lua (../remixed-dungeon/scripts/mobs/
-- RemixedFetidRat.lua, 46 lines). Three variants of a gas-emitting rat:
--   kind 1 = ParalyticGas  / immune Paralysis
--   kind 2 = ConfusionGas  / immune Vertigo
--   kind 3 = ToxicGas      / immune ToxicGas
-- Each tick the rat seeds its gas on its own cell (amount 50, matching source);
-- it is immune to that gas so it does not poison itself.
--
-- Adaptations vs source (fork register_mob subset, no Java changes):
--   * KIND selection: source picks `math.random(1,3)` in stats() and persists
--     it via mob.storeData/restoreData. fork register_mob exposes no arbitrary
--     Lua-data persistence (LuaMob.storeInBundle only persists lua_mob_id /
--     lua_spawned / lua_immunity_classes / stolen_loot), so kind is derived
--     deterministically from the actor id: `(selfId % 3) + 1`. selfId is
--     stable across save/load (Actor.storeInBundle persists `id`; restore keeps
--     incomingID, only falling back to nextID++ on an id collision, which is
--     itself an upstream error state). So the kind re-derived every act() stays
--     consistent with the immunity persisted at spawn — the rat can never emit
--     gas X while immune to gas Y. Behaviour is close to source (deterministic
--     kind per spawn rather than random); inter-spawn distribution still cycles
--     through all three variants across many rats.
--   * Immunity: added in spawn(selfId) via RPD.addImmunity. LuaMob persists the
--     FQCN (luaImmunityClassNames) and rebuilds immunities on restore, and the
--     `spawned` latch is itself persisted so spawn does not re-fire after a
--     load — yet the immunity survives. AddImmunity resolves kind1/kind2 via
--     BuffWhitelist (Paralysis/Vertigo) and kind3 via BlobRegistry (ToxicGas).
--   * RPD.Sfx (source kinds[].speck): dropped — source only stored the field,
--     never read it, and fork has no Sfx bridge.
--   * Source's `RPD.glog("rat kind %s", ...)` debug line: dropped (debug noise,
--     would spam GLog once per spawned rat).
--   * fork register_mob has no `desc` field (LuaMob.description() returns the
--     name), so only `name` is localised.
local KINDS = {
    { blob = RPD.Blobs.ParalyticGas, immunity = RPD.Buffs.Paralysis },
    { blob = RPD.Blobs.ConfusionGas, immunity = RPD.Buffs.Vertigo },
    { blob = RPD.Blobs.ToxicGas,    immunity = RPD.Blobs.ToxicGas },
}

-- Deterministic variant index in 1..3 from the (stable) actor id. See header.
local function kindOf(selfId)
    return (selfId % 3) + 1
end

register_mob {
    id = "remixed_fetid_rat",
    name = "腐臭鼠",
    hp = 20,
    ht = 20,
    attack = 8,
    defense = 3,
    sprite = "rat",

    spawn = function(selfId)
        RPD.addImmunity(selfId, KINDS[kindOf(selfId)].immunity)
    end,

    act = function(selfId)
        RPD.placeBlob(KINDS[kindOf(selfId)].blob, RPD.charPos(selfId), 50)
        return false
    end,
}
