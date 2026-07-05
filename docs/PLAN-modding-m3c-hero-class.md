# PLAN — modding-m3c-hero-class

> 里程碑:**M3c Lua 新职业(M3 第三弹)**
> 路线图:`docs/MODDING-ROADMAP.md` §4 M3 | 前置:M0+M1+M2-Item+M3a+M3b 已合 master
> M3 决策:D1 消耗性 spell / **D2 复用现有天赋** / D3 做宠物(已完成 M3b)/ D4 保留硬编码

---

## Goal

让 Lua 定义新职业(`hp`/初始装备/绑现有职业天赋树/可选 sprite),玩家在 `HeroSelectScene` 可选并玩通一周目。**核心约束(D2)**:复用现有 4(+2)职业天赋,Lua 职业声明 `talentSource=宿主职业`。

## Context

- **M3a/M3b 已交付**:LuaMob/LuaAlly + Registry + RpdApi + LuaItemCallbacks(范式直接复用)。
- **M3c 调研关键发现**(worker 必读):
  1. **`HeroClass` 是 6 项枚举**(WARRIOR/MAGE/ROGUE/HUNTRESS/DUELIST/CLERIC),**枚举体无字段**,`hp`/`sprite`/`armorAbilities`/`isUnlocked` 等全靠 **switch 分发**(`initHero` 119-143,spritesheet/splashArt/armorAbilities/masteryBadge 也 switch)。**枚举是 switch 中心,不是数据容器**
  2. **改枚举的 C4 致命**:`HeroClass.X` 在 core 模块 **104 处直接引用**,40+ 文件,大量 switch 无 default。加枚举项要补遍所有 switch(否则 spritesheet/masteryBadge/armorAbilities/isUnlocked/Badges NPE 或默认走 WARRIOR)
  3. **`Talent.initClassTalents(HeroClass,...)`(967 行)** 是按职业填天赋的唯一入口,内部 4 段 switch(cls)。**D2 落地极简**:Lua 职业声明 `talentSource=宿主`,initHero 时 `Talent.initClassTalents(宿主, hero.talents)` → **Talent.java 零改动**
  4. **持久化陷阱**:`Hero.storeInBundle` 走 `bundle.put(CLASS, heroClass)`(枚举 name()),restore `bundle.getEnum(CLASS, HeroClass.class)` 找不到 name 时**静默回退 `WARRIOR`**(Bundle.java:166-174)。**Lua 职业绝不能走枚举通道**——存档会静默损坏
  5. **`HeroSelectScene`**(职业选择):`for (HeroClass cls : HeroClass.values())` 渲染按钮(825 行);`GamesInProgress.Info.heroClass` 是枚举字段(存档列表/排行榜)
  6. Remixed `initHeroes.json` 是**数据驱动填值**,但枚举仍写死 10 项(JSON 不加项)。SPD switch 密度比 Remixed 高,**不能照搬**
- **范围决策(基于调研,已定)**:
  - **独立 `LuaHeroRegistry`,不改 HeroClass 枚举**(C4 收敛)
  - **v1 只玩本体**:子职业(`HeroSubClass`)/护甲技能(`ArmorAbility`/`ClassArmor`)对 Lua 职业**全禁用**(避免触达更多 switch);这些留 M3c+ 或后期
  - **Sprite 缺省回落 WARRIOR**(M3c 不画新图,Lua 可声明现有 spritesheet 路径)

## 关键决策(worker 实施前确认;有疑问 `[BLOCKED]`)

### D1 独立 LuaHeroRegistry(不改枚举)

`LuaHeroRegistry`:`Map<String, LuaHeroClass>`。`LuaHeroClass` 是 metadata 类(`id`/`name`/`talentSource: HeroClass`/`startingItems`/`hp`/`defenseSkill`/`spriteKey`)。**不改 `HeroClass` 枚举**(避免 40+ switch 补丁)。

### D2 天赋复用(talentSource 路由)

Lua 职业声明 `talentSource = HeroClass.WARRIOR`(或 MAGE/ROGUE/HUNTRESS)。`Hero.initHero` 走 Lua 分支时 `Talent.initClassTalents(talentSource, hero.talents)`。**`Talent.java` 零改动**。Lua 职业的天赋树就是宿主职业的。

### D3 持久化(旁路 lua_class_id,关键陷阱)

`Hero.storeInBundle`:`super` + 如果 `luaClassId != null`,`bundle.put("lua_class_id", luaClassId)`。`restoreFromBundle`:**先读 `lua_class_id`**,非空则查 `LuaHeroRegistry.get(id)` 还原(不走 `bundle.getEnum(CLASS,...)` 枚举通道)。**绝不走枚举通道**(否则静默回退 WARRIOR + 存档损坏)。worker 必测:Lua 职业 store→restore 后仍为该 Lua 职业(非 WARRIOR)。

### D4 UI 接入(HeroSelectScene 最小 hook)

`HeroSelectScene` 加一段遍历 `LuaHeroRegistry.all()` 渲染额外职业按钮(类比 fork `saveslot` 的 `addMenuButtons` 注入范式)。点击 → 创建 `Hero` + 设 `luaClassId` + `initHero`。**改动收敛到一处 hook**。

### D5 初始装备(hydrate 模式)

`LuaHeroClass` 从 Lua table 读 `startingItems`(物品 id 列表,引用 `LuaItemRegistry`)。`initHero` 时把这些 Lua 物品放进 hero 背包(类比 M3a hydrate)。

### D6 子职业/护甲技能禁用(v1)

Lua 职业:`armorAbilities()` 返回空、`masteryBadge()` 缺省、子职业不可选。worker 确认这些路径对 Lua 职业不 NPE(因为 Lua 职业不进 HeroClass 枚举 switch,走 Lua 分支)。

## Phase-1 worker refinement notes(探索代码后确认,worker 补)

### R1. Hook 预算修正:需要 Dungeon.java 小 guard(PLAN 原「3 hook」略低估)

上游 hero 创建唯一入口在 `Dungeon.java:281-286`:
```java
hero = new Hero();
hero.live();
...
GamesInProgress.selectedClass.initHero( hero );   // ← 唯一 dispatch 点
```
`initHero` 是 `HeroClass` 枚举的实例方法,**不能改枚举**(C4)。要让 Lua 职业
走「Lua items + Lua hp + talentSource 天赋」而非宿主装备,必须在 `Dungeon.init`
把 dispatch 换成 Lua-aware。HeroSelectScene 只能在切场景前塞 static state,
真正 init 发生在 `Dungeon.init`(InterlevelScene 调),无法从 Scene 注入。

**修正后 hook 清单(收敛到 4 处,仍是单点 hook)**:
- `Hero.java` — `public static void initLuaHero(Hero, String id)`(Lua 分支:设 heroClass=host + `Talent.initClassTalents(host, talents)` + hp/defenseSkill + startingItems hydrate + 完整复刻 `HeroClass.initHero` 公共段,见 R6)+ store/restore `lua_class_id` 旁路(D3)。**pending 状态移出 Hero,进 `LuaHeroService`(R7)**
- `HeroSelectScene.java` — Lua 按钮渲染 + 选中时 `LuaHeroService.selectLuaHero(id); GamesInProgress.selectedClass = def.talentSource; setSelectedHero(def.talentSource);`;**所有原版选择入口**(vanilla `HeroBtn.onClick`/随机/日常)调 `LuaHeroService.clearSelectedLuaHero()`(R7)
- `Dungeon.java` — **1 处 guard**(local 捕获 + 清空,见 R7):`String p = LuaHeroService.consumePending(); if (p != null) Hero.initLuaHero(hero, p); else GamesInProgress.selectedClass.initHero(hero);`
- `GamesInProgress.java` — **v1 推迟**(见 R4)

### R6. initLuaHero 完整复刻 HeroClass.initHero 公共段(codex must-fix #3)

`HeroClass.initHero` 的**公共段**(switch 外,所有职业都跑)必须在 `Hero.initLuaHero`
里完整复刻,**不能漏**(漏了会改 Lua 职业开局/掉落行为):

```java
// 1. ClothArmor(带 challenge block)
Item i = new ClothArmor().identify();
if (!Challenges.isItemBlocked(i)) hero.belongings.armor = (ClothArmor)i;
// 2. Food(带 challenge block)
i = new Food();
if (!Challenges.isItemBlocked(i)) i.collect();   // i.collect() 进 Dungeon.hero
// 3. VelvetPouch + LimitedDrops 标记(漏掉会破坏 limited-drop 状态)
new VelvetPouch().collect();
Dungeon.LimitedDrops.VELVET_POUCH.drop();
// 4. Waterskin
Waterskin waterskin = new Waterskin();
waterskin.collect();
// 5. ScrollOfIdentify identify
new ScrollOfIdentify().identify();
// 6. 天赋(D2)
Talent.initClassTalents(def.talentSource, hero.talents);
// 7. quickslot waterskin(漏掉 Lua 职业与原版开局不一致)
if (SPDSettings.quickslotWaterskin()) {
    for (int s = 0; s < QuickSlot.SIZE; s++) {
        if (Dungeon.quickslot.getItem(s) == null) {
            Dungeon.quickslot.setSlot(s, waterskin);
            break;
        }
    }
}
// 8. Lua-specific:hp/defenseSkill + startingItems hydrate
hero.HT = hero.HP = def.hp;
hero.defenseSkill = def.defenseSkill;
for (String itemId : def.startingItems) { /* LuaItemRegistry.create + collect */ }
```
T4 单测断言 hp/defenseSkill + talents;公共段物品(ClothArmor/Food/VelvetPouch/
Waterskin/ScrollOfIdentify/LimitedDrops/quickslot)在 desktop 一周目验证项里
逐条确认(单测起 Dungeon.hero/belongings 太重)。

### R7. pendingLuaClassId 状态集中到 LuaHeroService(codex must-fix #1)

pending 状态是 fork 代码,进 `modding/LuaHeroService`(C2),不污染 Hero 字段:

```java
// core/.../modding/LuaHeroService.java
public final class LuaHeroService {
    private static String pendingId = null;
    public static void selectLuaHero(String id) { pendingId = id; }
    public static void clearSelectedLuaHero() { pendingId = null; }
    /** 单次消费:取出并清空。Dungeon.init 调,保证即使 init 抛异常也不会残留。 */
    public static String consumePending() { String p = pendingId; pendingId = null; return p; }
    public static String peekPending() { return pendingId; }
    private LuaHeroService() {}
}
```

**清空时机(关键,防残留)**:
- `HeroSelectScene.HeroBtn.onClick`(原版职业按钮)→ clear
- 随机职业(`setSelectedHero(randomCls)` 前)→ clear
- 日常入口 → clear
- Lua 按钮点击 → `selectLuaHero(id)`(set,不清空)
- `Dungeon.init` → `consumePending()`(local 捕获 + 清空,单次消费)

**为什么不用 Hero 静态字段**:codex 指出 fork 状态进 modding/ 子包更干净(C2),
且 service 的 `consumePending` 语义(local 捕获 + 清空)比裸 static 字段更安全。

### R2. D3 设计澄清(关键正确性,worker 必读)

PLAN finding #4 描述的「getEnum 静默回退 WARRIOR」陷阱**只在存了非枚举 name 时触发**。本设计**永不存非枚举 name**:

- Lua 职业的 `hero.heroClass` **始终 = 宿主**(talentSource,如 WARRIOR)。
  `bundle.put(CLASS, heroClass)` 存 "WARRIOR",`bundle.getEnum(CLASS, HeroClass.class)`
  返回 WARRIOR —— 枚举通道**干净 round-trip,陷阱自然绕过**。
- `lua_class_id` 是**旁路 marker**(sidecar),不是替代 CLASS。storeInBundle 在
  `super` 之后 `bundle.put("lua_class_id", luaClassId)`;restoreFromBundle 读到
  非空 `lua_class_id` 就设 `this.luaClassId`(并据此判 Lua 职业)。
- **不需要从 registry re-hydrate**:HP/HT/attackSkill/defenseSkill/STR/talents/
  belongings 全部走 Hero 现有 bundle key,正常 round-trip。Lua-only 字段只有
  `luaClassId` 这一个 marker(类比 LuaMob 存 `lua_mob_id`,但 Hero 不需要
  re-hydrate definitional 字段,因为它们已持久化)。

**D3 关键测试**:Lua 职业 store→restore 后 `luaClassId` 仍 == 原 id(非 null),
且 `heroClass` == 宿主(不是「静默回退 WARRIOR」——是设计上的宿主)。测试
断言两者。codex 重点审:`restoreFromBundle` 必须先读 `lua_class_id` 再走 super,
且 `luaClassId` 字段必须 `transient`(不参与枚举序列化,但通过 sidecar key 持久化)。

### R3. D6 NPE 排查结论(已 grep,worker 确认)

- `masteryBadge()`:**core 模块无任何外部 callsite**(仅声明),零风险。
- `armorAbilities()`:仅 `WndHeroInfo`(信息展示,harmless) + `WndChooseAbility`
  (ClassArmor 使用时触发)调用。Lua 职业 `heroClass=宿主` → 返回宿主 abilities,
  **不 NPE**。
- `spritesheet()`/`splashArt()`/`isUnlocked()`:同上,宿主值,不 NPE。
- `HeroSubClass` switch(`MirrorImage`/`Mob`/`Skeleton` 等):走 `hero.subClass`,
  Lua 职业 `subClass = NONE`(默认),进 `case NONE` 或 default,**不 NPE**。

**v1 范围决策(D6)**:Lua 职业**继承宿主的**子职业/护甲技能路径(不 NPE,
但等于能用宿主能力)。**严格禁用**(屏蔽子职业选择窗口/ClassArmor UI)留 M3c+,
本里程碑只保证「不崩 + 原版 6 职业不受影响」。worker 在 PLAN `## Pending Issues`
或回报里标注此 v1 取舍。

### R4. GamesInProgress 推迟

`GamesInProgress.Info.heroClass` 是枚举字段(存档列表/排行榜展示)。Lua 职业
`heroClass=宿主`,存档列表会显示宿主名(如 "Warrior")而非 Lua 职业名。要正确
显示需加 `Info.luaClassId` + 改 `checkAll`/`preview`/UI —— **v1 推迟**(存档列表
显示宿主名是 cosmetic 降级,不影响玩法)。worker 不动 GamesInProgress,在回报
里标注此降级。

### R5. LuaItem startingItems hydrate

`LuaHeroClass.startingItems` 是 `List<String>`(LuaItem id)。`Hero.initLuaHero`
遍历调 `LuaItemRegistry.create(id)`,非 null 则 `.collect()`(进 hero.belongings,
等价 `HeroClass.initHero` 里的 `i.collect()`)。Lua id 找不到 → 跳过 + log(降级,
不崩)。测试用现有 `test_sword`(M2 已注册)。

## Files(Phase-1 refinement 后)

`✚` 新增 / `✎` 修改

- `✚ core/.../modding/LuaHeroClass.java` — metadata 类:`id`/`name`/`talentSource: HeroClass`/`hp`/`defenseSkill`/`startingItems: List<String>`/`spriteKey`。从 LuaTable 构造(`hydrate`),校验必填。
- `✚ core/.../modding/LuaHeroRegistry.java` — `Map<String, LuaHeroClass>` + `register(LuaHeroClass)`/`get(String)`/`all()`/`contains`/`size`/`clear`/`getTable`(类比 LuaMobRegistry)
- `✚ core/.../modding/LuaHeroService.java` — **R7**:fork 状态集中。`selectLuaHero(id)`/`clearSelectedLuaHero()`/`consumePending()`(local 捕获+清空)/`peekPending()`
- `✎ core/.../modding/LuaEngine.java` — `globals.set("register_hero", new RegisterHeroFunction())`;加 `HEROES_DIR = "scripts/heroes"` + `loadHeroScripts()`(类比 `loadMobScripts/loadAllyScripts`)。`RegisterHeroFunction` 校验 `id/name/talentSource/hp` 必填(`talentSource` 必须是 6 个枚举 name 之一),`defenseSkill/startingItems/spriteKey` 可选。
- `✎ core/.../actors/hero/Hero.java` — **上游 hook 1**(见 R1/R2/R6):
  - `public String luaClassId`(实例字段;通过 sidecar key `lua_class_id` 持久化,**不**走 CLASS 枚举通道)
  - `public static void initLuaHero(Hero hero, String id)`:查 `LuaHeroRegistry.get(id)` → 设 `hero.luaClassId=id` + `hero.heroClass=def.talentSource` + **完整复刻 `HeroClass.initHero` 公共段(R6)** + `Talent.initClassTalents(def.talentSource, hero.talents)`(D2)+ `hero.HT=hero.HP=def.hp` + `hero.defenseSkill=def.defenseSkill` + startingItems hydrate(D5)
  - `storeInBundle`:`super` 后 `if (luaClassId != null) bundle.put("lua_class_id", luaClassId);`
  - `restoreFromBundle`:`super` 后读 `lua_class_id`(sidecar),非空则设 `this.luaClassId`(D3 关键)
  - **注意**:`pendingLuaClassId` 不再放 Hero,移到 `LuaHeroService`(R7)
- `✎ core/.../scenes/HeroSelectScene.java` — **上游 hook 2**:遍历 `LuaHeroRegistry.all()` 渲染额外 `StyledButton`;Lua 按钮 onClick → `LuaHeroService.selectLuaHero(id); GamesInProgress.selectedClass = def.talentSource; setSelectedHero(def.talentSource);`;**原版 `HeroBtn.onClick`/随机/日常入口**调 `LuaHeroService.clearSelectedLuaHero()`(R7 防残留)
- `✎ core/.../Dungeon.java` — **上游 hook 3(R1)**:`init()` 里 `GamesInProgress.selectedClass.initHero(hero)` 改为 `String p = LuaHeroService.consumePending(); if (p != null) Hero.initLuaHero(hero, p); else GamesInProgress.selectedClass.initHero(hero);`
- `~~GamesInProgress.java~~` — **v1 推迟**(R4,不动)
- `✚ core/src/main/assets/scripts/heroes/test_hero.lua` — `register_hero{ id='test_hero', name='测试 Lua 职业', talentSource='WARRIOR', hp=25, defenseSkill=4, startingItems={'test_sword'} }`(shipped demo;**测试 T3 用 MAGE 宿主**,见 R8)
- `✚ core/src/test/java/.../modding/LuaHeroTest.java` — 见 Steps T1-T6
- `✎ android/proguard-rules.pro` — 现有 `modding.**` keep 已覆盖(M3a/M3b),确认即可

## Steps(Phase-2 实施,worker 执行顺序)

1. **写 LuaHeroClass + LuaHeroRegistry** — T1 单测:register/get/all/contains/size/clear + LuaHeroClass 从 LuaTable hydrate 校验必填字段
2. **register_hero global + loadHeroScripts** — T2 单测:valid table 注册 / 缺 `talentSource`|`hp`|`name`|`id` 被拒 / `talentSource` 非 6 枚举之一被拒 / `test_hero.lua` 经 `LuaEngine.init` 注册
3. **Hero.java hook(D1/D2/D3)** — `luaClassId` 字段 + `initLuaHero` static + store/restore 旁路。**T3(D3 关键,codex must-fix #2)**:在测试里注册一个 **MAGE 宿主**的 Lua 职业(`talentSource='MAGE'`,MAGE != WARRIOR 回退值)→ 设 `heroClass=MAGE` + `luaClassId=mage_hero` → `storeInBundle` → **restore 前直接断言 raw bundle**:`bundle.getString("class") == "MAGE"`(证明 class key 存的是合法宿主名,**不是** Lua id 也不是回退) + `bundle.getString("lua_class_id") == "mage_hero"` → restore 后断言 `heroClass == MAGE` + `luaClassId == "mage_hero"`。**T4**:`initLuaHero` 设 hp/defenseSkill + `Talent.initClassTalents(host,...)` 调用(反射验 `hero.talents` 非空 + 含宿主 tier1 天赋,MAGE → EMPOWERING_MEAL 等)
4. **Dungeon.java guard(R1/R7)** — `String p = LuaHeroService.consumePending(); if (p != null) Hero.initLuaHero(hero, p); else ...`。无单测,desktop 验证
5. **HeroSelectScene hook(D4/R7)** — Lua 按钮渲染 + Lua 按钮 `selectLuaHero(id)`;原版按钮/随机/日常入口 `clearSelectedLuaHero()`。无单测,desktop 验证
6. **D6 NPE 排查**(R3 已完成) — grep 确认结论写进回报
7. **沙箱回归**(T5) — `register_hero` 注入后 `luajava.bindClass` 仍不可达
8. **回归(C3)** — `:core:test` M0-M3b 全过;HeroClass 枚举零改动确认
9. **构建 + desktop 一周目验证**(R6) — `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug`;desktop 跑 Lua 职业一周目,逐条确认公共开局段(ClothArmor/Food/VelvetPouch+LimitedDrops/Waterskin+quickslot/ScrollOfIdentify)

## Acceptance

- [ ] LuaHeroRegistry + LuaHeroClass 工作(register/get/all/contains/size/clear)
- [ ] `register_hero` global 工作(valid 注册 / 缺必填被拒 / talentSource 非枚举被拒)
- [ ] **`Hero.initLuaHero` Lua 分支**:`Talent.initClassTalents(talentSource,...)` 绑宿主天赋 + startingItems hydrate + hp/defenseSkill
- [ ] **Bundle round-trip(D3 关键)**:MAGE 宿主 Lua 职业 store→restore,restore 前断言 raw bundle `class=="MAGE"` + `lua_class_id==id`,restore 后 `heroClass==MAGE` + `luaClassId==id`(非「静默回退 WARRIOR」)
- [ ] `HeroSelectScene` 显示 Lua 职业按钮,点击可开始新游戏;**原版/随机/日常入口 clearSelectedLuaHero**(R7 防残留)
- [ ] **`initLuaHero` 完整复刻公共开局段(R6)**:ClothArmor/Food/VelvetPouch+LimitedDrops/Waterskin+quickslot/ScrollOfIdentify(desktop 一周目逐条确认)
- [ ] 子职业/护甲技能对 Lua 职业**不 NPE**(D6:继承宿主路径,v1 不严格禁用 —— 见 Pending Issues)
- [ ] 沙箱不破(luajava.bindClass 回归测试)
- [ ] M0-M3b 测试无回归;**原版 6 职业玩法不受影响**(HeroClass 枚举零改动)
- [ ] fork 代码主体在 `modding/` 子包(C2);**上游改动收敛到 3 hook**(Hero.java / HeroSelectScene.java / Dungeon.java guard,见 R1;GamesInProgress 推迟)
- [ ] `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug` 通过
- [ ] codex 评审通过

## Codex round-1 评审 issues(resolved by worker)

codex round-1 返回 3 条 must-fix,worker 已全部并入 PLAN:
1. **pendingLuaClassId 残留** → R7:状态移到 `modding/LuaHeroService`,原版/随机/日常入口 `clearSelectedLuaHero()`,Dungeon `consumePending()` local 捕获+清空
2. **T3 用 WARRIOR 宿主无法证明枚举通道干净 round-trip** → Steps #3 / R6:T3 改用 **MAGE 宿主**,restore 前直接断言 raw bundle `class == "MAGE"` + `lua_class_id == id`(MAGE != WARRIOR 回退值,能区分干净 round-trip 与静默回退)
3. **initLuaHero 公共开局段未列全** → R6:完整复刻 ClothArmor/Food(challenge block)/VelvetPouch+LimitedDrops/Waterskin+quickslot/ScrollOfIdentify;desktop 一周目逐条验证

## Pending Issues(v1 scope 取舍,codex 确认)

- **D6 继承而非严格禁用**:Lua 职业(`heroClass=宿主`、`subClass=NONE`)**继承宿主的**子职业/护甲技能路径(能用宿主 ClassArmor/子职业),而非严格屏蔽。理由:严格禁用要改子职业选择窗口 + ClassArmor UI 多处,触达面远超 M3c 单里程碑;`heroClass=宿主` 让所有 switch 返回有效值,**零 NPE 风险**。严格禁用留 M3c+。
- **GamesInProgress/Rankings 展示降级**:存档列表/排行榜显示宿主名(如 "Warrior")而非 Lua 职业名(cosmetic)。理由:加 `Info.luaClassId` 要改 `checkAll`/`preview`/UI 多处,v1 推迟。
- **Dungeon.java guard(R1)**:PLAN 原「3 hook」低估,实际需 4 处(Hero/HeroSelectScene/Dungeon guard + GamesInProgress 推迟)。Dungeon 改动是 3 行 if/else 单点 guard,仍满足 C4「上游改动收敛」精神。

## 风险

- **D3 Bundle 旁路是关键正确性**:worker 必测 Lua 职业 store→restore 不回退 WARRIOR。这是 M3c 最大陷阱(codex 重点审)。
- **HeroSelectScene UI 改动**:加 Lua 按钮要保视觉一致(worker 调研现有按钮布局,最小接入)。
- **子职业/护甲技能 switch 触达**:Lua 职业 `armorAbilities()`/`masteryBadge()` 等若被某处 switch 调用,Lua 分支要返回安全默认值。worker Grep 这些 switch 调用点,确保 Lua 职业路径不触达。
- **talentSource 限制**:Lua 职业只能用现有职业天赋(D2 决策)。若用户后期想要自定义天赋,那是另一大工程(M3c 不做)。
- **GamesInProgress/Rankings 展示**:Lua 职业在存档列表/排行榜显示,可能需要 `luaClassId` 字段(worker 评估)。
- **proguard**(C5):release keep。

## 参考

- `docs/MODDING-ROADMAP.md` §4 M3
- `docs/PLAN-modding-m3a-mob-spawn.md`(M3a 范式)
- `core/.../actors/hero/HeroClass.java`(枚举 + switch 中心,**不改**)
- `core/.../actors/hero/Hero.java`(`initHero`/`storeInBundle`/`restoreFromBundle`,hook 点)
- `core/.../actors/hero/Talent.java:967`(`initClassTalents`,D2 入口)
- `core/.../scenes/HeroSelectScene.java`(UI hook)
- `SPD-classes/.../utils/Bundle.java:166-174`(`getEnum` 静默回退陷阱)
- M3a/M3b 已有:`core/.../modding/{LuaMob,LuaAlly,LuaItemCallbacks,RpdApi,LuaEngine}.java`

## 范围决策记录

- **独立 LuaHeroRegistry,不改 HeroClass 枚举**(C4:改枚举要补 40+ switch,致命)。
- **D2 复用现有天赋**(`talentSource` 路由,Talent.java 零改动)。
- **v1 禁子职业/护甲技能**(避免触达更多 switch;留 M3c+)。
- **D3 Bundle 旁路 lua_class_id**(不走枚举通道,防静默回退 WARRIOR)。
