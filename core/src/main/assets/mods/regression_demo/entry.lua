-- regression_demo entry script. Loaded by LuaEngine.loadModEntryScripts only when
-- this mod is enabled (default_enabled=false). Per-type scripts under
-- scripts/{items,mobs,buffs,spells,talents,traps,painters}/ auto-load by directory
-- scan regardless of entry, so this file carries only what directory-scan CANNOT:
-- (a) a load banner, and (b) register_level — levels have NO directory auto-scan
-- (LuaLevelRegistry is populated ONLY via register_level; see
-- LuaEngine.RegisterLevelFunction + LuaLevelService which builds from the json at
-- mods/levels/<id>.json).
RPD.GLog("[regression_demo] loaded — M6-M11 API regression active")

register_level{
    id = "regression_demo_level",
    name = "Regression Demo Level",
}
