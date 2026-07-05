# PLAN — modding-m2-item-api

> 里程碑:**M2 核心 API 暴露(第一弹:Item 行为 API)**
> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M2
> 前置:M0(Lua PoC)+ M1(沙箱 + Generator 双源)已合入 master
> 本 PLAN 是 feature_worker 的实施依据;codex_reviewer 评审本 PLAN + 实施。

---

## Goal

让 Lua 物品有**完整行为**(不只是 M0/M1 的数值 stub):Lua 能定义回调函数,Java 侧在 hook 点回调 Lua。具体:
- `attackProc(attacker, defender, damage)` — 攻击时触发,可改 damage / 加 buff(LuaItem extends MeleeWeapon,接管 `Weapon.proc`)
- `onEquip(hero)` / `onDeactivate(hero)` — 装备/卸下时触发(接管 `EquipableItem.activate/deactivate`)
- 注入窄 `RPD.*` 全局函数集(`affectBuff`/`damageChar`/`heal`/`GLog`),让 Lua 回调能操作游戏状态

## Context

- **M0/M1 已交付**:Lua 物品(数值 + Generator 双源 + 沙箱)。`LuaItem extends MeleeWeapon`,当前只 hydrate 数值(name/tier/image),**无行为回调**。
- **M2 调研关键发现**(决定设计,worker 必读):
  1. **`Weapon.java:131 proc(Char,Char,int)`** 是 attackProc 的天然接管点(LuaItem 已 extends MeleeWeapon)
  2. **`EquipableItem.activate(Char)/deactivate`** 是装备 hook(SPD 没有 `onEquip` 方法名,M2 把 Lua `onEquip` 映射到 `activate` override)
  3. **`Item.execute(Hero, String)`** 是使用效果 hook(Potion/Food/Scroll 模式)——M2-Item 不做使用效果(留 M2b)
  4. **Remixed 用 `luajava.bindClass` 拉 Java 类,但 M1 沙箱已 strip luajava**(`LuaSandbox.exposedGlobals()` 置 NIL)——M2 **必须改 Java 主动 `globals.set("RPD", proxyTable)` 注入窄 API**,不能让 Lua 直接拿 Java 类
  5. **`CoerceJavaToLua.coerce(char)` 暴露 Char 全部 public 方法,绕沙箱**——M2 先避免直接 coerce Char/Hero 给 Lua,改用窄 RPD 全局函数(Char 包装对象留 M2b)
- **范围决策(基于调研,已定)**:
  - M2-Item 聚焦**武器/装备行为回调** + **窄 RPD 全局函数**
  - **不做 onUse**(药水/卷轴使用效果,留 M2b,涉及 LuaPotion/LuaScroll 新类 + Bundle className)
  - **不做 Char/Buff 完整包装 API**(留 M2b)
  - **不做关卡 API**(Painter/Room/Trap,留 M2c)
  - `affectBuff` 限定 **buffClass 白名单**(标 `@LuaInterface` 的 Buff 子类),防 Lua 加任意 buff

## 关键决策(worker 实施前确认;有疑问 `[BLOCKED]`)

### D1 回调机制(Java→Lua)

`LuaItem` override `proc`/`activate`/`doUnequip`,从 LuaTable 取函数字段(`attackProc`/`onEquip`/`onDeactivate` 的 `LuaValue`),`call(args)` 回调 Lua。无函数字段时 fallback 上游行为(M0 已有 degraded 范式)。异常 catch 用 `Gdx.app.error`,不抛(回调失败不能崩游戏)。

**hook 点确认(实测上游 + codex 第 1 轮 must-fix 修正)**:
- `proc(Char attacker, Char defender, int damage)` 返回 int。**先 `int base = super.proc(attacker, defender, damage)` 跑上游附魔/HolyWeapon/Smite/ID 链**,再把 `base` 传给 Lua;Lua 有 `attackProc` 函数字段时 `callOptInt(base | attackerId | defenderId | base)`,Lua 返回 number 则覆盖,否则返回 `base`。**回调存在与否不能决定是否保留上游 proc 链**(否则 Lua 武器与普通武器语义分叉)。
- `activate(Char ch)`(`EquipableItem:159`,MeleeWeapon 已 override 给 DUELIST 加 Charger)。**但 `Belongings.restoreFromBundle` 会在存档恢复时对已装备武器重调 `activate(owner)`**(Item.java:617 用 `Belongings.bundleRestoring` 守卫自己的逻辑)→ 必须在 `activate` 里 `if (Belongings.bundleRestoring) { super.activate(ch); return; }` 跳过 Lua 回调,否则读档/本 fork 本地存档加载会重复触发 Lua heal/buff/damage/GLog 等一次性副作用。非恢复路径:`super.activate(ch)` 后回调 Lua `onEquip(heroId)`。
- **SPD 无 `deactivate` 方法**。卸下路径是 `EquipableItem.doUnequip(Hero, boolean, boolean)`(line 124,**非 final**,2-arg 版才是 final)。override 3-arg `doUnequip`:`boolean ok = super.doUnequip(hero, collect, single);` 返回 true 后回调 Lua `onDeactivate(heroId)`(false = 被诅咒锁住,不触发)。

### D2 Lua→Java API 注入(窄 RPD 全局 + 参数校验)

`LuaEngine.init()` 里 `globals.set("RPD", RpdApi.build())`。**所有数值参数校验**:非法(负数/NaN/超上限)→ `Gdx.app.error` + return NIL,绝不抛。
- `RPD.affectBuff(charId, buffName, amount)` — 加 buff(buffName 必须在白名单;amount>0 且 ≤ 上限 1000)。详见 D4。
- `RPD.damageChar(charId, amount)` — amount>0,走 `char.damage(amount, RpdApi.LUA_SOURCE)`(**不直接改 HP**,保留上游护盾/死亡/免疫链)。`LUA_SOURCE` 是具名单例对象(`new Object(){ @Override public String toString(){ return "LuaScript"; } }`),让死亡日志/source 分类可识别(codex 第 2 轮 nice-to-have:不传 `RpdApi.class`,否则 `src.getClass()` 变成 `java.lang.Class`)。
- `RPD.healChar(charId, amount)` — amount>0,`char.HP = Math.min(char.HT, char.HP + amount)`(只接受正数,clamp 到 [0, HT];**负数走 damageChar**)。
- `RPD.GLog(msg)` / `RPD.GLogW(msg)` — msg 校验为 string(`optjstring`)。
- `RPD.charHP(charId)` / `RPD.charPos(charId)` / `RPD.charName(charId)` — 只读查询。

**不用 `luajava.bindClass`**(M1 已 strip,且不安全)。charId 经 `Actor.findById(int)` 解析,返回非 Char → log + NIL。

### D3 Char 传参策略(已评估 → 选 B:charId)

**决策:采用方案 B(charId + RPD 查询),不 coerce Char 给 Lua。**

评估结论(基于 M1 沙箱实测 + luaj 行为):
- `CoerceJavaToLua.coerce(char)` 会暴露 Char **全部 public**(die/destroy/damage/belongings/...)。Hero 经 belongings 可达 Dungeon/level/items 全局状态 —— 暴露面不可接受。
- M1 的 `@LuaInterface` 白名单在 **运行时不生效**(`LuaSandbox.java` 明确写 "M1 does not wire per-call interception into luaj")。即 coerce 出去的对象没有任何运行期拦截,Lua 可随意调任意 public 方法。这与 M1 strip luajava 的隔离意图正面冲突。
- 方案 B 让 Lua 只拿到 int id,所有写操作经 `RPD.*` 窄函数(白名单 + 参数校验),与 M1 "curated globals" 设计一致。

**id 机制**:Char 继承 Actor,`Actor.id()` 返回稳定 int,`Actor.findById(int)` 反查。M2 在 hook 点把 `char.id()` 传给 Lua,Lua 用 `RPD.charHP(id)` 等查询、`RPD.damageChar(id, amt)` 等修改。**不在 Char 上加任何 @LuaInterface**(C4:上游 Char 零改动)。

### D4 buff 白名单(per-buff adapter,codex 第 1 轮 must-fix)

`RPD.affectBuff(charId, buffName, amount)`:`buffName`(simple name)必须在白名单。**白名单是 per-buff adapter,不是裸 Class** —— 因为各 buff 设置方式不同(实测):

| buff | 基类 | 应用方式 |
|------|------|---------|
| Roots/Slow/Cripple/Paralysis/Vertigo/Haste | FlavourBuff | `Buff.prolong(target, clazz, amount)` |
| Bleeding | Buff | `Bleeding b = Buff.affect(target, Bleeding.class); b.set(amount)` |
| Poison | Buff | `Poison b = Buff.affect(target, Poison.class); b.set(amount)` |
| Barkskin | Buff | `Barkskin b = Buff.affect(target, Barkskin.class); b.set((int)amount, (int)amount)` |

(注:`Actor.spend(float)` 是 protected,modding 包**不能**直接 `buff.spend(duration)` —— codex 指出的编译错误。改用上表的 public setter / `Buff.prolong`。)

第三参数语义随 buff 类型变化(FlavourBuff=duration,Bleeding=level,Poison=duration,Barkskin=value&time),文档里统一叫 `amount`,Lua 端注释说明。amount 校验:>0 且 ≤1000,否则 log + NIL。

白名单实现:`Map<String, BuffApplier>`,`BuffApplier` 是 `@FunctionalInterface`(`void apply(Char target, float amount)`)。未授权 buffName → 拒绝 + log(防 Lua 加 `HeroClone`/无敌 buff)。这样既守住安全边界,又给每个 buff 正确的应用语义。

## Files

`✚` 新增 / `✎` 修改(均在 `core/.../modding/` 子包,符合 C2)

- `✚ core/.../modding/LuaItemCallbacks.java` — 回调调度样板:`callOpt(table, fnName, LuaValue... args)`(无返回)与 `callOptInt(table, fnName, int fallback, LuaValue... args)`(返回值转 int)。取函数字段、isfunction 检查、call、异常 catch(`Gdx.app.error`,不抛)。纯静态工具类。
- `✎ core/.../modding/LuaItem.java` — override `proc`(**先 super.proc 跑上游链**,再 attackProc 回调,Lua 返回值覆盖)/ `activate`(**`Belongings.bundleRestoring` 守卫**,非恢复路径 super 后回调 onEquip)/ `doUnequip(Hero,boolean,boolean)`(super 返回 true 后回调 onDeactivate)。回调所需 table 经 `LuaItemRegistry.getTable(luaItemId)`。
- `✎ core/.../modding/LuaEngine.java` — `register_item` 放宽:`image` 改 `optint(默认 0)`、不强制校验函数字段(table 自带);`initInternal()` 在 `globals.set("register_item", ...)` 后加 `globals.set("RPD", RpdApi.build())`。
- `✎ core/.../modding/LuaItem.java`(**同步 image 可选**,codex 第 2 轮 must-fix)— `hydrate()` 把 `image = tbl.get("image").checkint()` 改 `optint(0)`(与 register_item 同步,避免无 image 字段时 create 崩)。override `proc`/`activate`/`doUnequip` 见 D1。
- `✚ core/.../modding/RpdApi.java` — 构建 RPD proxyTable(各 OneArg/TwoArg/VarArgFunction 内部类)+ **内置 buff 白名单** `Map<String, BuffApplier>`(adapter 模式,见 D4 表)+ `static final Object LUA_SOURCE`(具名伤害 source)。函数:`affectBuff`/`damageChar`(`char.damage(amt, LUA_SOURCE)`)/`healChar`(clamp [0,HT])/`GLog`/`GLogW`/`charHP`/`charPos`/`charName`。全部数值参数校验(>0,≤上限),非法 log + NIL。charId 经 `Actor.findById(int)`。
- `✚ core/.../modding/RpdApi.java` 内 `@FunctionalInterface BuffApplier { void apply(Char t, float amount); }` — 不单独建文件,内嵌于 RpdApi。
- `✚ core/src/main/assets/scripts/items/test_proc_weapon.lua` — attackProc 测试:tier=2 武器,`attackProc(atk, def, dmg)` 内 `RPD.affectBuff(def, "Bleeding", 3)`,return dmg+1(改伤害)。
- `✚ core/src/main/assets/scripts/items/test_equip_buff.lua` — onEquip 测试:tier=1 武器,`onEquip(hero)` 内 `RPD.affectBuff(hero, "Barkskin", 5)` + `RPD.GLog("equipped")`。
- `✚ core/src/test/java/.../modding/LuaItemCallbackTest.java` — 单测:(a) callOptInt 返回值转换 + 缺函数 fallback;(b) affectBuff 白名单:授权 buffName 通过、未授权被拒(返 NIL 且不抛)、amount≤0 被拒;(c) damageChar/healChar 参数校验(负数被拒);(d) Lua 不能 `luajava.bindClass`(M1 沙箱回归)。
- `✎ android/proguard-rules.pro` — keep 白名单 Buff 子类(RpdApi 用 Class 引用而非反射 Class.forName,理论上 R8 保留;但 keep 兜底 release)—— C5 加分项。

## Steps

每步独立可验证。D3 已定方案 B(charId),codex 第 1 轮 4 个 must-fix 已并入。

1. **LuaItemCallbacks** — `callOpt`(无返回)+ `callOptInt`(返回值转 int,异常/无函数 fallback)。单测:mock table + 函数字段验证 call/返回值转换/缺函数 fallback。
2. **RpdApi.build()** — 返回 `LuaTable`,内置 `Map<String, BuffApplier>` 白名单(9 buff,见 D4 表)+ `static final Object LUA_SOURCE`(具名 damage source)。affectBuff(白名单 + amount 校验 + adapter.apply)/damageChar(`char.damage(amt, LUA_SOURCE)`)/healChar(clamp [0,HT])/GLog/GLogW/charHP/charPos/charName。charId 经 `Actor.findById` + instanceof Char 校验。**所有数值非法 → log + NIL**。
3. **LuaEngine 注入 RPD** — `initInternal()` 加 `globals.set("RPD", RpdApi.build())`;`register_item` 放宽 image 为 optint。**同步 `LuaItem.hydrate()` image 为 optint(0)**(codex 第 2 轮 must-fix)。
4. **LuaItem override proc** — `int base = super.proc(...)` 先跑上游链;attackProc 回调用 base,Lua 返回值覆盖;无函数字段返回 base。
5. **LuaItem override activate / doUnequip** — activate:`if (Belongings.bundleRestoring) { super.activate(ch); return; }` 守卫,否则 super 后 onEquip;doUnequip:super 返回 true 后 onDeactivate。
6. **测试 Lua 物品** — `test_proc_weapon.lua`(attackProc 加 Bleeding + dmg+1)+ `test_equip_buff.lua`(onEquip 加 Barkskin + GLog)。
7. **单测 LuaItemCallbackTest** — callOptInt 返回值转换、affectBuff 白名单(授权/未授权/amount≤0)、damageChar/healChar 参数校验、luajava.bindClass 回归。另在 LuaEngineTest 或新单测加一条:无 image 字段的 Lua 物品仍能 register + create(codex 第 2 轮 must-fix 覆盖)。
8. **desktop debug 实测** — 装备 test_proc_weapon 攻击怪,确认 Bleeding + damage 提升;装备 test_equip_buff 确认 Barkskin + GLog。无真实设备时降级为 headless 单测 + 说明。
9. **回归(C3)** — M0/M1 测试全过(LuaEngineTest/LuaSandboxTest/GeneratorLuaItemTest);原版平衡不动。
10. **(加分 C5)release proguard** keep 白名单 Buff 子类。

## Acceptance

- [ ] LuaItem override `proc`/`activate`/`deactivate` 工作(无函数字段时 fallback 上游行为)
- [ ] `attackProc` 能改 damage / 加 buff(test_proc_weapon + desktop 实测)
- [ ] `onEquip` 能加 buff(test_equip_buff + desktop 实测)
- [ ] `RPD.*` 全局函数工作(affectBuff/damageChar/GLog)
- [ ] `affectBuff` 白名单:授权 buff 通过,未授权 buff 被拒(测试)
- [ ] **沙箱不破**:Lua 不能 `luajava.bindClass` 绕过(M1 沙箱回归测试通过)
- [ ] M0/M1 测试无回归(LuaEngineTest/LuaSandboxTest/GeneratorLuaItemTest)
- [ ] 原版平衡不变(C3:LUA_ITEM 默认低概率,原版 Category 不动)
- [ ] fork 代码在 `modding/` 子包(C2);上游改动收敛到最小(标注 @LuaInterface 在 Buff/Char 是新增注解,不改方法体;C4)
- [ ] `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug` 通过
- [ ] codex 评审通过(PLAN + 实施)

## 风险

- ~~**D3 Char 暴露面**~~ → **已解决(选方案 B charId)**:coerce Char 会绕过 M1 沙箱(@LuaInterface 运行期无拦截),改用 charId + Actor.findById + 窄 RPD 函数。Char 零上游改动。
- **doUnequip override 时序**:super.doUnequip 已把 item 移出 belongings。onDeactivate 回调里若 Lua 通过 RPD 查 hero 状态,读到的是卸下后的状态(语义正确:"卸下后通知")。回调内禁止假设 item 仍 equipped。
- **buff 白名单维护**:每个可暴露 Buff 在 RpdApi 静态 map 里登记 simple name → class。M2 先放 9 个常用(7 debuff + 2 buff),后续里程碑扩展。
- **proc 性能**:每次攻击调 Lua,但 SPD 战斗循环非热路径(每帧最多几次攻击),可接受。回调失败 catch 不抛。
- **Actor.findById 跨场景**:id 在 Actor 生命周期内有效;回调发生在战斗/装备动作内,actor 一定在 currentProcessor 里,findById 必中。M2 不处理跨关卡 id(留 M2b)。
- **proguard(C5)**:RpdApi 反射加载白名单 Buff 类,release R8 可能裁;keep 规则兜底。@LuaInterface 是 SOURCE 保留,不参与 R8,无需 keep。

## Pending Issues

(阶段 1/2 评审若第 3 轮仍未收敛,未决项追加到这里。)

## 参考

- `docs/MODDING-ROADMAP.md` §4 M2
- `docs/PLAN-modding-m1-sandbox.md`(M1 上下文)
- `core/.../items/weapon/Weapon.java:131`(`proc` hook)
- `core/.../items/EquipableItem.java`(`activate`/`deactivate`)
- `core/.../actors/Char.java`(Char API,D3 暴露对象)
- `core/.../actors/buffs/`(Buff 子类,D4 白名单)
- `../remixed-dungeon/scripts/lib/item.lua`(工厂范式,借鉴回调签名,不照搬 luajava)
- `../remixed-dungeon/scripts/items/HookedDagger.lua`(attackProc 示例)
- M0/M1 已有:`core/.../modding/{LuaItem,LuaEngine,LuaSandbox,LuaItemRegistry,LuaItemPool}.java`

## 范围决策记录

- M2-Item 聚焦**武器/装备行为回调 + 窄 RPD 全局**。
- **onUse(药水/卷轴使用效果)留 M2b**(涉及 LuaPotion/LuaScroll 新类 + Bundle className)。
- **Char/Buff 完整包装 API 留 M2b**(M2-Item 只做窄 RPD 函数)。
- **关卡 API(Painter/Room/Trap)留 M2c**。
- **mob 双源**(M1 砍的)推到 M3(机制骨架时一并)。
