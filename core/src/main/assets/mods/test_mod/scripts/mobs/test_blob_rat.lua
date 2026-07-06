-- M6a PoC test mob: a FetidRat-style mob that emits toxic gas each tick and is
-- immune to its own gas. Demonstrates the M6a Remished-bridge subset:
--   * RPD.Blobs constant table (string id, no Java Class handle)
--   * RPD.placeBlob(blobId, pos, amount)  — GameScene.add(Blob.seed(...)) bridge
--   * RPD.addImmunity(charId, id)         — registers ToxicGas.class in immunities
--   * spawn(selfId) one-shot callback     — first-act initialization (M6a)
--
-- Behaviour:
--   * spawn  → once on first act, grant self toxic-gas immunity (else it would
--              poison itself to death on its own gas).
--   * act    → drop ToxicGas at self pos, then return false so the upstream
--              Mob AI still runs (paths/attacks the hero). Returning true would
--              take over the tick and skip the AI state machine.
--
-- Not in the vanilla spawn pool — only appears when a script calls
-- RPD.spawnMob("test_blob_rat", pos). luajava stays disabled (D5'-(a)).

register_mob {
    id = "test_blob_rat",
    name = "毒气鼠",
    hp = 20,
    ht = 20,
    attack = 8,
    defense = 3,
    sprite = "rat",

    spawn = function(selfId)
        RPD.addImmunity(selfId, RPD.Blobs.ToxicGas)
    end,

    act = function(selfId)
        RPD.placeBlob(RPD.Blobs.ToxicGas, RPD.charPos(selfId), 50)
        return false
    end,
}
