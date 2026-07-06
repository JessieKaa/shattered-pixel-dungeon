-- M6c port of Remished scripts/buffs/GasesImmunity.lua
-- Remished: immunities = {ToxicGas, Paralysis, Vertigo}. M6c bridges Lua buff
-- immunities to whitelisted Blob/Buff classes, so this buff grants real
-- immunity to ToxicGas (blob) + Paralysis/Vertigo (buffs) while attached.
register_buff{
    id = "gases_immunity",
    name = "GasesImmunity",
    info = "GasesImmunity (M6c: immunities mapped to ToxicGas/Paralysis/Vertigo)",
    icon = 25,

    immunities = function(state)
        return { "ToxicGas", "Paralysis", "Vertigo" }
    end,

    attachTo = function(targetId, state)
        return true
    end,
}
