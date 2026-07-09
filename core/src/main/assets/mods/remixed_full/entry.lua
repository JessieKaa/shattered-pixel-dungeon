-- M16b remixed_full minimal playable alpha entry script.
-- default_enabled=false, so LuaEngine only loads this mod when the player toggles it on.
-- The mod registers an alpha hub level where players can inspect shop/mobs/items directly.
RPD.GLog("[remixed_full] alpha content loaded — hub available via custom-level entry")

register_level{
    id = "remixed_full_alpha_hub",
    name = "Remixed Full Alpha Hub",
}
