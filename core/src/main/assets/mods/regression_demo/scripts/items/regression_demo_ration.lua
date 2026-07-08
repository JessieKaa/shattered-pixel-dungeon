-- M11b LuaMaterial representative: stackable food with EAT energy + onEat callback,
-- onThrow callback, and a declarative burnTransform (Java-side transformTo dispatch).
-- register_item with type="material" routes through LuaMaterial, NOT LuaItem.
-- energy drives the Java-side satiety effect; onEat only proves the (heroId,itemId)
-- signature fires without depending on a vanilla buff class name.
register_item {
    id = "regression_demo_ration",
    type = "material",
    name = "回归口粮",
    desc = "M11b material 压测:EAT 恢复饱食 + onEat 回调 + onThrow 日志 + burnTransform 转灰烬。",
    image = 11,
    price = 10,
    stackable = true,
    defaultAction = "EAT",
    energy = 150,
    burnTransform = "regression_demo_ash",

    onEat = function(heroId, itemId)
        RPD.GLog("[regression_demo] 口粮被 hero " .. heroId .. " 吃掉")
    end,

    onThrow = function(cell, itemId)
        RPD.GLog("[regression_demo] 口粮被扔到了 cell " .. cell)
    end,
}
