-- M17b port of remished RemixedFetidRat.lua (../remixed-dungeon/scripts/mobs/
-- RemixedFetidRat.lua, 46 lines). Three variants of a gas-emitting rat:
--   kind 1 = ParalyticGas  / immune Paralysis
--   kind 2 = ConfusionGas  / immune Vertigo
--   kind 3 = ToxicGas      / immune ToxicGas
-- Each tick the rat seeds its gas on its own cell (amount 50, matching source);
-- it is immune to that gas so it does not poison itself.
--
-- Adaptations vs source:
--   * KIND selection (M19b): source picks `math.random(1,3)` in stats() and
--     persists it via mob.storeData/restoreData. The fork gained that exact
--     capability in M19b (`RPD.mobStoreData`/`mobRestoreData` + LuaMob bundle
--     persistence), so this is now faithful to source — random kind on first
--     spawn, persisted across save/load. The M17b workaround derived kind
--     deterministically from the actor id (`(selfId % 3) + 1`) because the
--     fork had no Lua-data persistence; that workaround is now removed.
--   * Immunity: added in spawn(selfId) via RPD.addImmunity. LuaMob persists the
--     FQCN (luaImmunityClassNames) and rebuilds immunities on restore, and the
--     `spawned` latch is itself persisted so spawn does not re-fire after a
--     load — yet the immunity survives. addImmunity resolves kind1/kind2 via
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

register_mob {
    id = "remixed_fetid_rat",
    name = "腐臭鼠",
    hp = 20,
    ht = 20,
    attack = 8,
    defense = 3,
    sprite = "rat",

    spawn = function(selfId)
        -- M19b: random kind on first spawn, persisted in the mob's Lua data so
        -- it survives save/load (replaces the M17b id-derived kind). mobRestoreData
        -- returns the live table by reference, so in-place mutation + mobStoreData
        -- keeps the chosen kind stable across the mob's life.
        local data = RPD.mobRestoreData(selfId)
        if not data.kind then
            data.kind = math.random(1, 3)
            RPD.mobStoreData(selfId, data)
        end
        RPD.addImmunity(selfId, KINDS[data.kind].immunity)
    end,

    act = function(selfId)
        local data = RPD.mobRestoreData(selfId)
        RPD.placeBlob(KINDS[data.kind or 1].blob, RPD.charPos(selfId), 50)
        return false
    end,
}
