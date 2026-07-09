# PLAN: M14b — C3 回归硬化 + 平衡调参(为 v3.4.0 default-on 守质量)

## Goal
为 v3.4.0 发布(default-enabled remished_lite)守质量:(1) C3 回归硬化 —— 扩展测试证 remished_lite default-on 不破 main game 完整性(Generator drop deck / spawn 池 / 徽章挑战链路);(2) 平衡调参 —— remished_lite showcase 内容数值公平可玩(不 OP / 不破)。**保守 scope**:调 showcase-local 内容(remished_lite scripts),**不动全局 BalanceConfig 默认**(降 C3 风险)。与 M14a 零文件冲突(测试/assets vs gradle/proguard)。

## Context
- **现有 C3 测试**:`ModToggleRegressionTest`(disabled→8 registry 空)、`RegressionDemoModTest`(M11e,enabled registry 填充 + disabled 空)、`RemishedLitePackTest`(M13c,含 `c3_enabledDoesNotPolluteMainGame`:LUA_ITEM prob=0 / spawn 不读 LuaMobRegistry / 不 register_trap)。
- **缺口**:RemisedLitePackTest 已证 main-game 集成路径不污染,但**未显式测**:
  - Generator drop deck 构建时 LUA_ITEM prob 仍 0(remished_lite on 不改 `Generator.Category` probs)
  - Level.createMob / MobSpawner 链路不读 LuaMobRegistry(remished_lite 的 marauder mob 永不刷)
  - 徽章/挑战链路不因 remished_lite 注册内容而错触发(register_buff/spell 不被 vanilla 徽章逻辑误读)
  - showcase 关卡 json(remished_lite_hub)合法可加载(DataDrivenLevel.fromAsset 不抛)
- **BalanceConfig**(M9c):MANA_REGEN_DELAY 10→8、SHIELD_DECAY_PER_TURN=0 等;mod.json `balance` 字段可覆盖。**全局默认已在 M9c 调过**,M14b **不动全局默认**(C3 风险 + 平行于 M14a)。
- **remished_lite showcase 内容**(M13c):lantern_blade(weapon,tier2,attackProc)、shop(potion_of_healing/small_ration/scroll_of_identify/berry)、guide NPC giveItem、marauder mob(inert)、spark spell(inert)。数值 M13c 定的是"可玩即可",M14b 精调为"公平不破"。

**设计决策**:
- **C3 硬化**(测试):扩展 `RemishedLitePackTest`(或新 `RemishedLiteC3Test`):
  - `generator_dropDeckUnchanged`:remished_lite on 时 `Generator.Category.LUA_ITEM` firstProb/secondProb 仍 0(显式断言,不只靠 inert 结论)。
  - `spawnPoolExcludesLuaMob`:remished_lite on 时 vanilla spawn 路径不产出 Lua mob(若可 headless 测 Level.createMob;否则断言 LuaMobRegistry 不被 spawn 链路引用,grep-证)。
  - `showcaseLevelJsonLoads`:`DataDrivenLevel.fromAsset("mods/levels/remished_lite_hub.json", "remished_lite_hub")` 不抛 + 几何合法(32×32,entrance/exit 在界)。
  - (可选)`badgeChainUntouched`:remished_lite 注册的 buff/spell 不被 vanilla 徽章逻辑误触发(若可测)。
- **平衡调参**(showcase-local,**不动全局**):
  - `lantern_blade.lua`:tier/攻击/attackProc 数值调为 tier2 公平(对标 vanilla tier2 武器,不 OP)。worker 参照 vanilla `Generator.Category.WEAPON` tier2 代表。
  - `remished_lite_shop.lua`:价格对标 vanilla shop(potion_of_healing 等 vanilla 物品的 shop 价格基线)。
  - guide NPC giveItem:量/时机合理(发 1 把展示武器,不刷分)。
  - **不动** BalanceConfig 全局默认 / mana / shield(regen 等 M9c 已调 + 全局影响大,本 feature 保守)。
- 若 worker 发现全局 BalanceConfig 某值需调(mana/shield 在 default-on 下明显不平),**文档记录 + defer**,不在本 feature 动(避免 C3 风险 + 平行冲突)。

## 已有覆盖 vs 新增缺口(worker Phase-1 探索结论)

读 `RemishedLitePackTest`(M13c)发现 dispatcher 写 PLAN 时低估了现有覆盖度。**已有断言**(保留,不动):
- `c3_enabledDoesNotPolluteMainGame`:已断言 `Generator.Category.LUA_ITEM.firstProb==0` + `secondProb==0`(静态字段)+ `LuaMobRegistry.contains(marauder)`(registered-but-inert)。
- `hubLevelAsset_isStructurallyValid`:已断言 `DataDrivenLevel.fromAsset(HUB_ASSET,...) != null` + 32×32/entrance/exit/tiles 几何 + mobs/items specs。**showcase json 可加载已覆盖,不重复**。
- `disabled_loadsNothing`:toggle 全控已覆盖。

**真正缺口**(本 feature 新增,均为**运行时行为断言**,非静态/非 grep 注释):
1. **drop deck 构建权重(LUA_ITEM)**:现有只查静态 `firstProb/secondProb` 字段,**未查 `Generator.generalReset()` 构建出的 `categoryProbs` map 给 LUA_ITEM 的实际权重**。新增:反射读 `private static categoryProbs`,断言 `get(LUA_ITEM)==0f` —— 证"built deck 也给 0 权重"(反射写法复用 `GeneratorLuaItemTest.deckRandomEmitsLuaItemWhenWeighted` 同款,有先例)。
2. **spawn rotation 不含 Lua mob**:现有只靠注释 grep-证"Level.createMob/MobSpawner 不读 LuaMobRegistry"。新增:**驱动真 spawn selector** `MobSpawner.getMobRotation(depth)`(public static,纯数据,headless 安全),对 depth 1..26 断言 rotation 内无 `LuaMob.class.isAssignableFrom(mc)` 的类 —— 真行为断言,若有人把 LuaMobRegistry 接进 MobSpawner 立刻红。
   - **不测** `Generator.random()` 全 deck 端到端(会实例化 vanilla 物品,headless 易 NPE);deck 权重断言(#1)已等价覆盖该契约。
   - **不测** badgeChain(无 headless 钩子,badge 逻辑不读 Lua registry,grep 级事实,不造脆断言)。

## 平衡调参决策(worker Phase-1 已核对 vanilla 基线)

**lantern_blade.lua**(codex SUG#2 修正措辞):`LuaItem extends MeleeWeapon`(未 override min/max/damageRoll),`tier=2` ⇒ base **min=tier=2, max=5×(tier+1)=15**,即与 vanilla **Shortsword(tier2)2–15 完全一致**。现有 `attackProc` 返回 `baseDamage+2` ⇒ 每击 4–17,**严格高于 Shortsword / 默认 MeleeWeapon tier2 damage baseline(2–15),无代价 → 偏 OP**(注:vanilla tier2 还含 Spear/Dirk/Quarterstaff 等带速度/防御/偷袭权衡的武器,纯 +dmg 非全面碾压,故措辞用"偏 OP"非"碾压所有")。
- **决定**:`attackProc` `+2 → +1`。base 仍 2–15(Shortsword parity),+1 ⇒ 均值 ~9.5 vs Shortsword ~8.5(~12% 优势),保留"functional weapon 非 stub"的可观测证明,不再无代价偏 OP。script 注释写明 vanilla Shortsword 2–15 基线 + parity 推理。
- **不动 tier**(tier 驱动 min/max + STR 需求,降 tier 会误伤)。
- 注:`LuaItem` 继承 `Item.isUpgradable()==true`(可升级),故不写"不可升级"——仅靠 attackProc +1 + 单件发放(one-shot guide)控强度。

**remished_lite_guide.lua**(codex MUST-FIX):现有 `onInteract` 每次交互都 `RPD.giveItem(heroId, "remished_lite_lantern_blade", 1)`,注释自承"repeated interacts just top up the backpack" —— 玩家可 spam 交互无限领武器(仅 RpdApi per-hero/depth quota=20 兜底),违反 PLAN "不刷分"。
- **决定**:加 **one-shot 守卫** —— script 顶层 `local granted = {}`;`onInteract` 内 `if not granted[heroId] then if RPD.giveItem(heroId, "remished_lite_lantern_blade", 1) then granted[heroId]=true end end`。**giveItem 返回 bool,成功后才标记**(背包满等失败不误标已领)。首次交互发武器 + 标记,后续交互只对话不再发。session 级持久(LuaEngine 整局持有);跨 save/load engine 重 init 标记重置,但 hub 重入再领 1 把非"刷分"循环,可接受。无需改 Java(RPD 无 hasItem API,script 级 table 最简)。注释更新:删"top up the backpack",写明 one-shot。

**remished_lite_shop.lua**(codex SUG#3 补 bounded-stock):vanilla shop 买价 = `Shopkeeper.sellPrice = value() × 5 × (depth/5+1)`(按 depth 缩放,hub 无 depth 不适用)。现有展示价(potion 50 / ration 15 / scroll 30 / berry 10)对 `value()`(potion 30 / ration 10 / scroll 30 / berry 5)的倍率不一(potion 1.67×,scroll 1.0×——**两个 value-30 消耗品定价不同,真 bug**)。另:ration/berry **未设 quantity ⇒ 无限库存**(`LuaShopNpc`:omitted=infinite),与"防刷"矛盾。
- **决定**:① 统一 **showcase 价 = round(2 × vanilla value())**(potion 60 / scroll 60 / ration 20 / berry 10),2× 远低于 vanilla depth-shop(5×+ 起),展示友好但不免费,统一倍率消 inconsistency;② **全项设 finite quantity**(potion qty2 / scroll qty3 / ration qty3 / berry qty4),per-visit bounded-stock。script 注释写明每项 vanilla `value()` 基线 + 2× 规则 + quantity 语义。
- **per-visit 而非跨重入**(codex Phase-2 MUST-FIX 修正):hub 是 ephemeral `DataDrivenLevel`,`LuaShopNpc` 不 bundle runtime stock(`LuaShopNpc` javadoc 自述 stock resets on every visit)—— 故 quantity 限定的是**单次到访**,hub 重入会补货。这对 showcase 可接受(物品仍需花金币 2× value,非免费);跨重入持久化需改 Java(给 LuaShopNpc 加 stock bundle),刻意 out-of-scope(保守 + LuaShopNpc 现有 ephemeral 设计是有意为之)。注释已据此改正,不 overclaim "防 re-entry 刷"。

**不改**:
- sage(纯对话)、marauder(registered-but-inert 永不刷)、spark(registered-but-inert 无获取路径)—— 均无平衡影响。
- 全局 `BalanceConfig` 默认(M9c 已调;default-on 再调留独立 feature,降 C3 风险)—— 仅在 `## Deferred 全局调参建议` 文档记录。

## Files (worker)
- **`core/src/test/java/.../modding/RemishedLitePackTest.java`**(扩,非新文件):新增 `c3_dropDeckWeightAndSpawnRotationUntouched` —— 两条新断言(先 `Generator.generalReset()` 再反射 `categoryProbs.get(LUA_ITEM)==0f` + `MobSpawner.getMobRotation(1..26)` 无 LuaMob,失败消息带 depth+class)。保留全部现有断言不动。
- **`core/src/main/assets/mods/remished_lite/scripts/items/remished_lite_lantern_blade.lua`**:`attackProc` `+2→+1` + 注释(vanilla Shortsword 2–15 基线)。
- **`core/src/main/assets/mods/remished_lite/scripts/shops/remished_lite_shop.lua`**:四价统一 2× vanilla value()(60/60/20/10)+ 全项 finite quantity(potion2/scroll3/ration3/berry4)+ 注释。
- **`core/src/main/assets/mods/remished_lite/scripts/npcs/remished_lite_guide.lua`**:加 one-shot 守卫(`granted[heroId]` table)+ 注释更新(删"top up",写明 one-shot)。
- **不动**:`BalanceConfig.java` / `mod.json` balance 段 / sage / marauder / spark。

### 显式延后
- **全局 BalanceConfig 调参**(mana regen / shield cap/decay 等):M9c 已调基线;default-on 下若需再调,独立 feature(避开 C3 风险 + 本 feature 保守)。
- **徽章/挑战全通关自动化测试**:unit 测覆盖集成路径;全通关 C3 留 device/手动(M14c 未选)。
- **showcase 内容大规模扩展**:本 feature 是精调现有 M13c 内容,非加新实体。

## Steps
1. **C3 测试扩展**(`RemishedLitePackTest.java`):新增方法 `c3_dropDeckWeightAndSpawnRotationUntouched`(scanRealMods + LuaEngine.init 后):
   - **先** `Generator.generalReset()`(把 LUA_ITEM(0,0) 写进 categoryProbs),**再** 反射 `categoryProbs`(`GeneratorLuaItemTest` 同款)→ `assertEquals(0f, probs.get(Generator.Category.LUA_ITEM), 0f)`。
   - `for depth 1..26: for mc in MobSpawner.getMobRotation(depth): assertFalse("...depth="+depth+" class="+mc.getSimpleName(), LuaMob.class.isAssignableFrom(mc))`(失败消息带 depth+class,codex SUG#5)。
   - 复用 `@Before resetState`(fresh prefs + resetLuaState),不引新依赖。
2. **lantern_blade.lua**:`attackProc` 返回 `baseDamage + 2` → `baseDamage + 1`;注释加 vanilla Shortsword tier2 = 2–15 基线 + parity 推理(措辞"偏 OP"非"碾压所有")。
3. **shop.lua**:四价 → potion 60 / scroll 60 / ration 20 / berry 10(= 2 × vanilla value:30/30/10/5)+ 全项 finite quantity(potion2/scroll3/ration3/berry4);注释加 value 基线 + 2× 规则 + quantity 理由。
4. **guide.lua**:加 `local granted = {}` + `onInteract` 内 `if not granted[heroId] then if RPD.giveItem(...) then granted[heroId]=true end end`(giveItem 返回 bool,成功后才标记,防背包满时误标已领);注释更新为 one-shot。
5. **`./gradlew :core:test`** 全绿(现有用例不删不改 + 本 feature 新增断言)。
6. **codex 评审**(Phase 1/2,codex exec):重点 —— 新断言真覆盖(deck 权重反射真读 map / MobSpawner 真驱动 spawn selector,非空泛)、平衡对标 vanilla(Shortsword 2–15 / value() 基线)、guide one-shot 真防刷、shop 全项 finite quantity(per-visit,注释不 overclaim)、不动全局 BalanceConfig、与 M14a 零文件冲突。infra 503 则政策 B(:core:test 硬验收)。
7. send_message 回报:C3 新断言(2 条)+ 平衡调参明细(lantern_blade +1 / shop 2×value+qty / guide one-shot)+ 测试数 + defer 的全局建议。

## Deferred 全局调参建议
(Phase-2 若发现 default-on 下 mana/shield 明显不平,在此记录 + 不动代码,留独立 feature。Phase-1 暂无。)

## Acceptance
- [ ] 新增 C3 运行时断言:① 先 generalReset() 再反射 deck `categoryProbs.get(LUA_ITEM)==0f` ② `MobSpawner.getMobRotation(1..26)` 无 LuaMob(失败消息带 depth+class)—— remished_lite on 时
- [ ] 保留现有断言不动(firstProb/secondProb==0 / trap size==0 / fromAsset / disabled toggle)
- [ ] lantern_blade `attackProc` +2→+1(base tier2 = 2–15 = Shortsword parity,措辞"偏 OP"非"碾压所有")
- [ ] guide.lua one-shot 守卫(`granted[heroId]`)—— 首次交互才发武器,防 spam 刷分
- [ ] shop 四价统一 2× vanilla value()(potion 60 / scroll 60 / ration 20 / berry 10)+ 全项 finite quantity(potion2/scroll3/ration3/berry4,per-visit bounded;注释不 overclaim 防 re-entry),消 inconsistency
- [ ] **不动全局 BalanceConfig 默认**
- [ ] `./gradlew :core:test` 全绿(零回归)
- [ ] 与 M14a(gradle/proguard)零文件冲突(本 feature 只 touch core 测试 + remished_lite assets)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时;503 则政策 B :core:test 硬验收)
- **保守**:不动全局 BalanceConfig(mana/shield/regen M9c 已调;default-on 再调留独立 feature);只调 showcase-local remished_lite 内容
- C3 断言要**真测**(断言 Generator probs / spawn 路径,非空泛 "should not pollute")
- 平衡对标 vanilla(tier2 武器 / shop 价格基线),不凭感觉
- 与 M14a(gradle 版本/proguard)零重叠
