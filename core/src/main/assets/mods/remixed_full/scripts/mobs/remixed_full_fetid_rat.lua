-- M16b alpha mob + M19b persistence: Fetid Rat. Port of remished
-- RemixedFetidRat.lua (../remixed-dungeon/scripts/mobs/RemixedFetidRat.lua).
-- A diseased rat that emits one of three gases, picked at random on first
-- spawn and persisted across save/load (M19b RPD.mobStoreData/restoreData):
--   kind 1 = ParalyticGas  / immune Paralysis
--   kind 2 = ConfusionGas  / immune Vertigo
--   kind 3 = ToxicGas      / immune ToxicGas
-- Each tick it seeds its gas on its own cell (amount 50, matching source); it
-- is immune to that gas so it does not poison itself.
--
-- History: M16b shipped this as a minimal poison-on-bite stub. M19b brings it
-- to the real remished behaviour (3 gas kinds) now that LuaMob can persist
-- arbitrary Lua data — kind is random per spawn and stable across save/load.
local KINDS = {
    { blob = RPD.Blobs.ParalyticGas, immunity = RPD.Buffs.Paralysis },
    { blob = RPD.Blobs.ConfusionGas, immunity = RPD.Buffs.Vertigo },
    { blob = RPD.Blobs.ToxicGas,    immunity = RPD.Blobs.ToxicGas },
}

register_mob {
    id = "remixed_full_fetid_rat",
    name = "腐臭老鼠",
    hp = 14,
    ht = 14,
    attack = 6,
    defense = 2,
    sprite = "rat",
    spriteFile = "mods/remixed_full/sprites/mobs/mob_FetidRat.png",
    maxLvl = 10,

    spawn = function(selfId)
        -- M19b: random kind on first spawn, persisted in the mob's Lua data so
        -- it survives save/load. mobRestoreData returns the live table by
        -- reference, so in-place mutation + mobStoreData keeps the chosen kind
        -- stable across the mob's life.
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
