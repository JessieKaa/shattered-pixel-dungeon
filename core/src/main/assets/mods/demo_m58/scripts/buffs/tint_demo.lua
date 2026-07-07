-- M8c demo: all three tintChar return shapes, switched by buff level so one
-- buff exercises every computeTint branch:
--   level 1 → number       (aura, DEFAULT_AURA_RAYS=6)   color 0x3399FF=3381759
--   level 2 → {color,rays} (aura, custom rays)           color 0xFFD700=16753920
--   level 3 → {r,g,b,a}    (multiplicative tint)         gold
-- computeTint parses: nil→none / number→aura(color,6) / {color,rays}→aura /
-- {r,g,b[,a]}→tint (color takes priority over r/g/b). A tester re-applies the
-- buff at levels 1/2/3 (RPD.affectBuff stacking refreshes level in place) to
-- see each cosmetic. Default apply (m58_test_weapon onEquip) is level 1.
register_buff{
    id = "tint_demo",
    name = "TintDemo",
    info = "TintDemo (M8c: number/{color,rays}/{r,g,b} tint variants by level)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    tintChar = function(selfId, state)
        local lvl = RPD.buffLevel(selfId, "tint_demo") or 1
        if lvl >= 3 then
            return { r = 1.0, g = 0.84, b = 0.0, a = 0.5 }
        elseif lvl == 2 then
            return { color = 16753920, rays = 4 }
        end
        return 3381759
    end,
}
