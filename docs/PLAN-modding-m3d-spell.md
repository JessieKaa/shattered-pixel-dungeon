# PLAN — modding-m3d-spell

> 里程碑:**M3d 消耗性 LuaSpell(M3 第四弹,收尾)**
> 路线图:`docs/MODDING-ROADMAP.md` §4 M3 | 前置:M0+M1+M2-Item+M3a/M3b/M3c 已合 master
> M3 决策:**D1 消耗性 spell**(本里程碑)/ D2 复用天赋 / D3 做宠物(已完成)/ D4 保留硬编码

---

## Goal

`LuaSpell extends Item`(消耗品),`execute(Hero, action)` hook + Lua `onUse(heroId)` 回调 + `detach` 消耗。让 Lua 能定义可使用、可堆叠、使用即消耗的物品(药水/卷轴/法术的通用 Lua 化)。**沿用 M2-Item 的 execute/onUse 范式**(M2 做了 attackProc/onEquip,M3d 补 onUse)。

## Context

- **M2-Item 已交付**:`LuaItem extends MeleeWeapon`,`proc`/`activate`/`doUnequip` 回调(super-then-Lua,charId + LuaItemCallbacks.callOpt/callOptInt)。M3d 把同一范式用到消耗品 `execute`。
- **M3d 调研关键发现**(worker 必读):
  1. **`Item.execute(Hero, String action)`(Item.java:157)** 默认实现:设 `curUser`/`curItem` + 分发 `AC_DROP`/`AC_THROW`。子类 override **必须 `super.execute` 先行**(让基类设 curUser/curItem + 处理 drop/throw)
  2. **消耗品消耗机制**(`Item.detach(Bag)` Item.java:319):`quantity==1` → `detachAll`(整件移除);`quantity>1` → `split(1)` 减一。**代码写 `detach(hero.belongings.backpack)` 即可,数量内部处理——不要手写 `quantity--`**
  3. **`Food.java:62-95` / `Scroll.java:173` 标准范式**:`actions()` add 自定义 `AC_XXX` + `defaultAction=AC_XXX`;`execute` 内 `super.execute` → `if (action.equals(AC_XXX)) { detach(...); 施效; hero.spend/busy; }`
  4. **消耗品惯例**:`stackable=true`(背包自动 merge)+ `isUpgradable()=false` + `isIdentified()=true`(绕过 SoU/未鉴定 UI)
  5. **LuaSpell extends Item**(不是 Potion/Scroll/Food——这些硬绑 sprite/动作文案/catalog/鉴定机制,Lua 化会撞墙)。直接 `extends Item` 最干净,沿用 M2 LuaItem 的 hydrate + storeInBundle/restoreFromBundle 范式
- **范围决策(基于调研,已定)**:
  - M3d 聚焦**消耗性 onUse**(使用即消耗 + 回调)
  - **不做 throwing**(AC_THROW 走上游,不在 M3d 改造,Lua spell 不投掷)
  - **不做 stackable merge 的复杂场景**(stackable=true,但 M3d 测试单件 + 数量消耗即可)

## 关键决策(worker 实施前确认;有疑问 `[BLOCKED]`)

### D1 LuaSpell 基类(extends Item)

`LuaSpell extends Item`(不是 Potion/Scroll/Food)。`stackable=true`,`defaultAction=AC_USE`(自定义)。从 Lua table 读 `name`/`desc`/`image`/`onUse` 回调字段。`isUpgradable()=false`,`isIdentified()=true`(消耗品惯例)。

### D2 execute hook + onUse 回调(沿用 M2 范式)

```java
public static final String AC_USE = "USE"; // 实例字段或 static

@Override
public List<String> actions(Hero hero) {
    List<String> a = super.actions(hero);  // AC_DROP/AC_THROW
    a.add(AC_USE);
    return a;
}

@Override
public void execute(Hero hero, String action) {
    super.execute(hero, action);  // 必须:设 curUser/curItem + drop/throw 分发
    if (action.equals(AC_USE)) {
        detach(hero.belongings.backpack);  // 消耗(自动处理 quantity)
        // Lua onUse 回调(charId 范式)
        LuaItemCallbacks.callOpt(table, "onUse", LuaValue.valueOf(hero.id()));
        hero.spend(TIME_TO_USE);  // 占用回合(参考 Food.TIME_TO_USE)
        hero.busy();
    }
}
```

**复用 `LuaItemCallbacks.callOpt`**(fire-and-forget,施效副作用)。无 `onUse` 字段时,消耗仍发生但无 Lua 副作用(M0 degraded 范式)。

### D3 消耗机制(detach,不手写 quantity)

`detach(hero.belongings.backpack)` 自动处理:`quantity==1` 移除整件,`quantity>1` `split(1)` 减一。**不要手写 `quantity--`**。worker 测:quantity=3 execute 一次后 quantity=2;quantity=1 execute 后物品从背包移除。

### D4 注册(LuaSpellRegistry + register_spell)

`LuaSpellRegistry` 镜像 `LuaItemRegistry`(`Map<String, LuaTable>` + register/getTable/create/ids)。`LuaEngine` 加 `register_spell(table)` global + `scripts/spells/*.lua` loader(沿用 M3a/M3b/M3c 共用的 `loadScriptsFrom` 枚举)。`create(id)` 返回 `new LuaSpell(table)`。

### D5 持久化(沿用 LuaItem 范式)

`LuaSpell` 固定 Bundle className `LuaSpell` + `lua_spell_id` 字段。`storeInBundle`/`restoreFromBundle` 镜像 `LuaItem`:`super`(Item 存 quantity/level/cursed)+ `lua_spell_id`,restore 时 `LuaSpellRegistry.getTable(id)` re-hydrate name/desc/image/onUse。**HP/HT 类比不适用(LuaSpell 无 hp),但 quantity 走 super 不被 hydrate 覆盖**(M3a/m3b HP/HT 教训的对应:hydrate 不碰 super 管的字段)。

## Files

`✚` 新增 / `✎` 修改(均在 `core/.../modding/`,C2)

- `✚ core/.../modding/LuaSpell.java` — `extends Item`,`stackable=true`,`AC_USE`,`actions`/`execute` override(D2),hydrate(name/desc/image + onUse 回调字段缓存),持久化 `lua_spell_id`(D5)
- `✚ core/.../modding/LuaSpellRegistry.java` — Map<id,LuaTable> + register/getTable/create/ids/contains/size/clear(1:1 镜像 `LuaItemRegistry`)
- `✎ core/.../modding/LuaEngine.java` — `register_spell(table)` global(校验 `id`/`name` 必填,`onUse` 可选)+ `scripts/spells/*.lua` loader(共用 `loadScriptsFrom`)
- `✚ core/src/main/assets/scripts/spells/test_spell.lua` — 测试法术:id/name + `onUse(heroId)` 回调(调 `RPD.GLog("spell used")` 或 `RPD.affectBuff(heroId, "Heal", 5)` 等,验证 RPD API 仍工作)
- `✚ core/src/test/java/.../modding/LuaSpellTest.java` — register + execute(AC_USE)+ onUse 回调 + detach 消耗(quantity=3→2,quantity=1→移除)+ Bundle round-trip + 沙箱回归
- `? android/proguard-rules.pro` — keep LuaSpell(M3a `modding.**` 规则可能已覆盖)

## Steps

1. **写 LuaSpellRegistry** — 1:1 镜像 LuaItemRegistry。单测 register + create 返回 LuaSpell。
2. **写 LuaSpell(extends Item)** — `stackable=true`/`AC_USE`/`isUpgradable=false`/`isIdentified=true`;hydrate(name/desc/image + onUse 字段缓存);`actions`/`execute` override(D2);持久化 `lua_spell_id`(D5)。
3. **register_spell global + loader** — `LuaEngine` 加 `globals.set("register_spell", ...)`,校验后 `LuaSpellRegistry.register`;`scripts/spells/*.lua` 枚举。
4. **单测(D3 消耗 + D2 onUse)** — register → create → execute(AC_USE):断言 onUse 回调触发(callOpt 被调)+ quantity 正确消耗(=3→2,=1→移除);无 onUse 字段时仍消耗(M0 degraded)。
5. **Bundle round-trip(D5)** — LuaSpell store→restore:quantity 保持 + lua_spell_id re-hydrate。
6. **沙箱回归** — Lua 不能 luajava.bindClass(M1 沙箱);charId 范式(Lua 不碰 Hero 对象,onUse 只收 heroId)。
7. **回归(C3)** — M0-M3c 测试全过;原版玩法不变(LuaSpell 是新 Item 子类,**`Item.java` 零改动**)。
8. **(加分)release proguard**(C5)。

## Acceptance

- [ ] LuaSpellRegistry 工作(register/getTable/create/ids)
- [ ] `LuaSpell extends Item`:`stackable=true`/`AC_USE`/`isUpgradable=false`/`isIdentified=true`
- [ ] `actions`/`execute` override:`execute(AC_USE)` 触发 `detach` + `onUse` 回调
- [ ] **消耗(D3)**:`detach(backpack)` 正确处理 quantity(=3→2,=1→移除,**不手写 quantity--**)
- [ ] `onUse(heroId)` 回调触发(有字段);无字段时仍消耗(fallback)
- [ ] Bundle round-trip:quantity + lua_spell_id 保持
- [ ] `register_spell` global 工作 + `scripts/spells/` loader
- [ ] 沙箱不破(luajava.bindClass 回归测试)
- [ ] M0-M3c 测试无回归;原版玩法不变
- [ ] fork 代码在 `modding/` 子包(C2);**上游零改动**(C4:`Item.java` 不动,LuaSpell 是新子类)
- [ ] `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug` 通过
- [ ] codex 评审通过

## 风险

- **`detach` 调用时机**:必须在 onUse 副作用**之前**或**之后**?参考 Food.java(先 detach 再施效——即使施效抛异常也消耗了)。worker 跟 Food 一致(`detach` 在 `onUse` 前)。
- **`hero.spend`/`hero.busy`**:execute 后占用回合(worker 核对 Food/Scroll 的 spend 时间常量,如 `TIME_TO_USE`/`TIME_TO_READ`)。headless 测可能不便验证 spend,降级为代码审查 + desktop 实测。
- **Bundle className 冲突**:LuaSpell 是新类,不冲突。
- **stackable merge**:多个同 id LuaSpell 会 merge(stackable=true)。worker 验证 merge 后 quantity 正确。
- **proguard**(C5):release keep LuaSpell。

## 参考

- `docs/MODDING-ROADMAP.md` §4 M3
- `docs/PLAN-modding-m2-item-api.md`(M2-Item 上下文,execute/onUse 范式直接复用)
- `core/.../items/Item.java:81,157,319`(quantity/execute/detach)
- `core/.../items/food/Food.java:62-95`(消耗品 execute 标准范式)
- `core/.../modding/{LuaItem,LuaItemCallbacks,LuaItemRegistry,LuaEngine}.java`(范式 1:1 映射)

## 范围决策记录

- **LuaSpell extends Item**(不是 Potion/Scroll/Food,避免硬绑定)。
- **消耗性 onUse**(使用即消耗 + 回调)。
- **不做 throwing**(AC_THROW 走上游)。
- **detach 自动消耗**(不手写 quantity--)。
- **沿用 M2 范式**(charId + LuaItemCallbacks + Registry + lua_spell_id 持久化)。

---

## 实施细化(worker 阶段 1 补充,核对源码后)

### D6 isSimilar override(防 merge 数据损坏,**新增**)

`Item.isSimilar` 默认只比 `getClass()`(Item.java:367)。两个不同 id 的 LuaSpell(如 heal potion + fireball scroll)都属 `LuaSpell` class → 默认会 merge 成一个 stack,数量相加 —— **静默损坏两类物品**。

override 加 `luaItemId` 一致性:
```java
@Override
public boolean isSimilar(Item item) {
    if (super.isSimilar(item) && item instanceof LuaSpell) {
        return luaItemId != null && luaItemId.equals(((LuaSpell) item).luaItemId);
    }
    return false;
}
```
PLAN 原说"不做 stackable merge 的复杂场景",指的是不测多 id merge 逻辑;但**默认 merge 跨 id 是数据损坏 bug**,这 3 行修复属"防止明显错误",不算范围扩张。codex 重点审。

### 实现要点(核对源码确认)

1. **LuaSpell 字段**:
   - `public static final String AC_USE = "USE"`(沿用 Food.AC_EAT 静态常量惯例)
   - 实例初始化块:`stackable = true; defaultAction = AC_USE;`(Food:54-57 同款)
   - `public LuaSpell()` 无参 ctor(`Reflection.newInstance` 在 `split(1)` 里需要,Item.java:292)
   - `public LuaSpell(LuaTable tbl)` → `hydrate(tbl)`
   - `private String luaItemId / nameStr / descStr`(同 LuaItem)
   - **必须 override** `isUpgradable()`→`false` + `isIdentified()`→`true`(Item 默认 isUpgradable=true/isIdentified=(levelKnown&&cursedKnown)=false;Food:130-137 显式 override,沿用)
2. **hydrate(tbl)**:`id`/`name` 必填(`checkjstring`),`desc`/`image` 可选(`optjstring("")`/`optint(0)`)。onUse 字段不缓存(运行时从 registry 取 table 调 callOpt,同 LuaItem.luaTable() 模式)。
3. **execute override**(D2):
   - `super.execute(hero, action)` 首行(设 curUser/curItem + drop/throw)
   - `if (action.equals(AC_USE))`:`detach(hero.belongings.backpack)` → `LuaItemCallbacks.callOpt(luaTable(), "onUse", LuaValue.valueOf(hero.id()))` → `hero.busy()` + `hero.spend(TIME_TO_USE)`
   - `TIME_TO_USE = 1f`(参考 Scroll.TIME_TO_READ=1f,Food.TIME_TO_EAT=3f;消耗法术 1f 合理)
   - **detach 在 callOpt 前**(Food:76-79 范式:先消耗再施效,施效抛异常也消耗了)
4. **actions override**:`ArrayList<String> a = super.actions(hero); a.add(AC_USE); return a;`(Food:62-67 同款;注意返回类型 `ArrayList<String>` 非 `List`)
5. **持久化(D5)**:`storeInBundle` super + `lua_spell_id`;`restoreFromBundle` super + 从 `LuaSpellRegistry.getTable(id)` re-hydrate(同 LuaItem)。quantity 走 super,不被 hydrate 覆盖。

### 测试策略(headless 限制,沿用 M2 降级)

**`GameScene.cancel()` 在 headless 下 NPE**(`cellSelector` 静态 null)→ `super.execute` 不可直接调用。沿用 LuaItemCallbackTest(M2)的降级:测原语 + 直接调 detach + desktop 实测集成路径。

`LuaSpellTest` 覆盖:
1. **Registry 契约**:register/getTable/create/ids/contains/size/clear
2. **hydrate**:`LuaSpellRegistry.create(id)` 返回的实例 name/desc/image 正确
3. **register_spell 校验**:id+name 必填(缺则不注册),onUse 可选
4. **D3 detach(核心)**:`new Belongings.Backpack()` + `backpack.items.add(spell)`:
   - quantity=3 → `spell.detach(backpack)` → backpack 内原物 quantity()==2
   - quantity=1 → `spell.detach(backpack)` → backpack.items 不再含此物
5. **D2 onUse 回调**:注册带 onUse 的 spell(回调里 `tbl.flag = true`),`LuaItemCallbacks.callOpt(tbl,"onUse",heroId)` 触发,断言 flag(execute 走的同 callOpt)
6. **D6 isSimilar**:同 id 两件 isSimilar=true;不同 id isSimilar=false(防 merge 损坏)
7. **D5 Bundle round-trip**:quantity=3 store→restore → quantity=3 + lua_spell_id 保持 + name re-hydrate
8. **消耗品惯例**:stackable=true / defaultAction=AC_USE / actions(hero) 含 AC_USE
9. **沙箱回归**:luajava.bindClass 仍不可用(M1)

**不 headless 测**(PLAN 风险 #2 降级):`execute(AC_USE)` 全路径(GameScene.cancel + hero.spend + hero.busy)→ 代码审查 + desktop debug 实测。

### 范围确认(不扩)

- AC_THROW 不改(上游 Item.execute 分发 doThrow)
- 不做 quantity>1 的多件 merge 场景测试(D6 isSimilar override 是防御性修复,不展开测)
- 不加 sprite/catal‌og/鉴定机制(LuaSpell extends Item 而非 Potion/Scroll)
