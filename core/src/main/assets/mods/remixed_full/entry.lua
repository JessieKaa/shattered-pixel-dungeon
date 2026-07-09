-- M16b remixed_full minimal playable alpha entry script.
-- default_enabled=false, so LuaEngine only loads this mod when the player toggles it on.
-- The mod registers an alpha hub level where players can inspect shop/mobs/items directly,
-- plus (M17c) a tavern and a chapel showcasing the m17a town NPCs.
RPD.GLog("[remixed_full] alpha content loaded — hub, tavern, chapel available via custom-level entry")

register_level{
    id = "remixed_full_alpha_hub",
    name = "Remixed Full Alpha Hub",
}

register_level{
    id = "remixed_full_tavern",
    name = "Remixed Full Tavern",
}

register_level{
    id = "remixed_full_chapel",
    name = "Remixed Full Chapel",
}
