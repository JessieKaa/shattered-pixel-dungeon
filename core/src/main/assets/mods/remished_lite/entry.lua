-- remished_lite entry script. default_enabled=true, so LuaEngine.loadModEntryScripts
-- runs this on every game start (no manual mod toggle needed).
--
-- This file carries ONLY what directory auto-scan CANNOT: a load banner + register_level.
-- Per-type scripts under scripts/{items,mobs,spells,npcs,shops}/ auto-load by directory
-- scan (LuaEngine.loadScriptsFrom) for enabled mods, each calling its own register_*.
-- Levels have NO directory auto-scan — LuaLevelRegistry is populated ONLY via
-- register_level here. The hub geometry lives at mods/levels/remished_lite_hub.json
-- (consumed by DataDrivenLevel.fromAsset via LuaLevelService).
--
-- C3: remished_lite registers item/spell/mob but they are registered-but-inert in the
-- main game (Generator.LUA_ITEM prob=0 / vanilla spawn never reads LuaMobRegistry / no
-- spell acquisition path). Content is reached only inside the showcase level via NPC
-- giveItem + placed shop/gold. No register_trap (would auto-manifest in main game).
RPD.GLog("[remished_lite] loaded — showcase hub available via custom-level entry")

register_level{
    id = "remished_lite_hub",
    name = "Remished Lite Hub",
}
