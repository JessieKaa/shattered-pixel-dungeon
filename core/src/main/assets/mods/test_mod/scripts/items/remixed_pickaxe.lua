-- M11c weapon item: Remixed RemixedPickaxe(任务鹤嘴锄)。走 LuaItem(weapon wrapper)。
-- 实现: MINE action + RPD.dig/RPD.dropItem + 血染 self.state.bloodStained + glowing 血染发光。
-- 原件: ../remixed-dungeon/scripts/items/RemixedPickaxe.lua

local function findDigTarget(heroPos)
    -- 先沿英雄正东方(同行)扫 1..3 格找最近 solid(与 level 宽度无关)
    for dist = 1, 3 do
        local c = heroPos + dist
        if RPD.isSolid(c) then return c end
    end
    -- 再扫周围 8 邻居;行偏移用 RPD.levelWidth() 计算,避免写死宽度
    local w = RPD.levelWidth()
    if w == nil then return -1 end
    local dirs = {-(w + 1), -w, -(w - 1), -1, 1, w - 1, w, w + 1}
    for _, d in ipairs(dirs) do
        local c = heroPos + d
        if RPD.isSolid(c) then return c end
    end
    return -1
end

register_item {
    id = "remixed_pickaxe",
    name = "鹤嘴锄",
    desc = "矿工的鹤嘴锄。装备后可挖掘可破坏墙壁、门与路障；击杀蝙蝠后会血染并发红光。",
    image = 75,
    tier = 3,

    -- 动作列表(只声明 Lua 新增动作;上游 EQUIP/UNEQUIP/ABILITY/DROP/THROW 自动保留)
    actions = {"MINE"},
    actionNames = {
        MINE = "挖矿",
    },

    -- 装备后默认动作变为挖矿,未装备则优先 EQUIP
    defaultAction = function(state)
        return state.equipped and "MINE" or "EQUIP"
    end,

    onEquip = function(heroId, state)
        state.equipped = true
    end,

    onDeactivate = function(heroId, state)
        state.equipped = false
    end,

    -- MINE action: 在英雄前方/相邻 solid 单元格挖一格,并概率掉落 dark_gold / mystery_meat
    execute = function(heroId, action, state)
        if action ~= "MINE" then return end

        local heroPos = RPD.charPos(heroId)
        if heroPos == nil then return end

        -- 优先取英雄正前方最近的 solid cell;没有则扫描周围 8 格
        local target = findDigTarget(heroPos)
        if target == nil or target == -1 then
            RPD.GLogW("这里没什么可挖的。")
            return
        end

        local ok = RPD.dig(target)
        if ok then
            -- 50% 掉落 dark_gold,25% 掉落 mystery_meat(两者也可同时)
            if math.random() <= 0.5 then
                RPD.dropItem(target, "dark_gold", 1)
            end
            if math.random() <= 0.25 then
                RPD.dropItem(target, "mystery_meat", 1)
            end
            RPD.GLog("你挖开了这面墙。")
        else
            RPD.GLogW("这里无法挖掘。")
        end
    end,

    -- 击杀 Bat 后血染;血染后 item 发红光
    attackProc = function(attacker, defender, baseDamage, state)
        -- defender 是 char id;需要知道它是不是 Bat 类。当前 RPD 没暴露 className,
        -- 我们通过 RPD.charName(defender) 简单匹配(测试环境 Bat 的 name 为 "蝙蝠" / "bat")
        local name = RPD.charName(defender)
        if name ~= nil and (name:lower():find("bat") or name:find("蝙蝠")) then
            if not state.bloodStained then
                state.bloodStained = true
                RPD.GLog("鹤嘴锄被蝙蝠的血染红了!")
            end
        end
        return baseDamage
    end,

    glowing = function(state)
        if state.bloodStained then
            return { color = 0xAA0000, period = 0.3 }
        end
        return nil
    end,
}
