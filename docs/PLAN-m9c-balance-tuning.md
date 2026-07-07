# PLAN: M9c — 平衡调参(BalanceConfig 抽取 + shield decay 旋钮)

## Goal
把散落在 Java 私有常量里的 modding 平衡数值(mana regen、mana 上限公式、shield 软上限)抽取到集中配置类 `BalanceConfig`,给一组保守默认微调,并为 mod.json 预留可选 `balance` 覆盖钩子(数据层 + 读取链路,UI 暂不做)。**默认行为除 MANA_DELAY(10→8)外零变化**,shield decay 默认关闭(向后兼容),修「shield 永久驻留」设计缺陷的旋钮就位。

## Context
M9c 调研确认当前数值散落各处、无集中配置:

- **Mana**:
  - `Hero.java:272-273,286`:`MP = 10`、`MPMax = 10`(初始)
  - `Hero.java:314-316`(`updateMPMax`):`MPMax = 10 + 2*(lvl-1)`(等级公式)
  - `ManaRegen.java:31`:`MANA_DELAY = 10f`(私有 static final,1 MP / 10 回合 = 0.1/回合)
  - `LuaSpell.java:62,96`:`spellCost` 默认 0,从 Lua 表读(Lua 可调)
  - `RpdApi.java:90`:`MAX_AMOUNT = 1000f`(restoreMana 单次上限)
- **Shield**:
  - `ShieldTracker.java:40`:`MAX_AMOUNT = 1000`(private static final,软上限)
  - **无 decay、无 TTL**:M8b 设计「打掉就掉,不打就留」,ShieldTracker 是 Ephemeral(不存 bundle)。**这是设计缺陷** —— 永久护盾破坏消耗经济。
  - per-buff `shieldAmount` 在 Lua(shield_demo=20 / mana_shield=10 / shield_left=8 / chaos_shield_left=6,Lua 可调)
- **Talent on_upgrade**:全 Lua(`demo_m58/scripts/talents/*.lua`、`test_mod/scripts/talents/mod_example.lua`),无 Java 硬编码数值。**M9c 不碰**。
- **无集中配置**:无 `BalanceConfig.java`、无 `mod.json` balance 字段。

**设计决策**:
1. **抽 BalanceConfig.java**:集中 mana/shield 数值(public static 运行时可调,非 final —— mod 覆盖需运行时写入)。原 Java 私有常量改引用 BalanceConfig。
2. **保守默认微调**(无 playtest 依据,只改明显值):
   - `MANA_REGEN_DELAY` 10f → **8f**(0.125/回合,略加快早期节奏;唯一行为变化)
   - `MANA_BASE=10` / `MANA_PER_LEVEL=2`(公式不变,只抽常量)
   - `SHIELD_MAX=1000`(软上限不变)
   - `spellCost` 默认 0(Lua 各 spell 自定,不动)
3. **Shield decay 旋钮**:`BalanceConfig.SHIELD_DECAY_PER_TURN`(默认 **0 = 不衰减**,完全保持 M8b 行为);ShieldTracker `act()` 读取,>0 时每 turn 衰减 pool。**默认 0 = 零行为变化,向后兼容**。
4. **mod.json balance 覆盖(数据层 only)**:`ModManifest` 加可选 `balance` map,`ModRegistry` 启动时合并 enabled mod 的覆盖到 BalanceConfig。UI(M9c 不做):mod 管理器显示 balance 差异。先跑通读取合并链路。

## Files
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/BalanceConfig.java`(新)— 集中常量 + `applyModOverrides(Map<String,Number>)`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java:272-316,425` — MP/MPMax 引用 `BalanceConfig.MANA_BASE` / `MANA_PER_LEVEL`(初始值、updateMPMax 公式、restoreFromBundle missing-save fallback 三处)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ManaRegen.java:31` — `MANA_DELAY` 引用 `BalanceConfig.MANA_REGEN_DELAY`(去 final,运行时读)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ShieldTracker.java:40` — `MAX_AMOUNT` 引用 `BalanceConfig.SHIELD_MAX`;POOL 值改 `Entry`;lazy reconcile against `Actor.now()`;`setClockForTest` 测试 seam。类保持 package-private
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModManifest.java` — 加可选 `balance` 字段(`Map<String,Number>`)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModRegistry.java` — init 完各 manifest 后,合并 enabled mod 的 balance 到 BalanceConfig
- 测试:`core/src/test/java/.../modding/BalanceConfigTest.java`(默认值 + mod 覆盖合并 + decay=0 零变化)

## Steps
1. **BalanceConfig.java**:`public static` 字段(非 final,运行时可被 mod 覆盖写入)—— `MANA_BASE=10`, `MANA_PER_LEVEL=2`, `MANA_REGEN_DELAY=8f`, `SHIELD_MAX=1000`, `SHIELD_DECAY_PER_TURN=0f`。`applyModOverrides(Map<String,? extends Number>)` 按 key 名(大小写不敏感)合并到对应字段,key 映射:`mana_base`→MANA_BASE(int)、`mana_per_level`→MANA_PER_LEVEL(int)、`mana_regen_delay`→MANA_REGEN_DELAY(float)、`shield_max`→SHIELD_MAX(int)、`shield_decay_per_turn`→SHIELD_DECAY_PER_TURN(float);未知 key 忽略(不抛,前向兼容)。**范围校验(codex must-fix #2)**:已知 key 但值非法时跳过该 key + `Gdx.app.error` 日志(不抛、不 crash 启动):`mana_base`/`mana_per_level`/`shield_max`/`mana_regen_delay` 必须 >0(`mana_per_level` 允许 =0);`shield_decay_per_turn` 必须 ≥0;int 字段 fractional 值用 `(int)Math.floor` 取整。提供 `resetToDefaults()`(测试 + ModRegistry 用)。
2. **Hero.java MP/MPMax**:
   - `MP = BalanceConfig.MANA_BASE`、`MPMax = BalanceConfig.MANA_BASE`(初始)
   - `updateMPMax`:`MPMax = BalanceConfig.MANA_BASE + BalanceConfig.MANA_PER_LEVEL * (lvl - 1)`
3. **ManaRegen.java**:`MANA_DELAY` 去 final,改读 `BalanceConfig.MANA_REGEN_DELAY`(原 `private static final float MANA_DELAY = 10f` → 引用 BalanceConfig)。
4. **ShieldTracker.java**(核对发现:ShieldTracker 是 `final class` 静态工具,**不是 Buff,没有 `act()`**;原 PLAN 「act() 开头」表述不准。codex 3 轮评审进一步确认 SPD turn 模型无干净「一次 Java 调用 = 一 hero turn」choke point:`spend(-TICK)` 退款、mining 一 turn 多次 spend、`spendConstant` 作 multi-tick ability timer(MonkEnergy)。最终采用 **lazy reconcile** 方案):
   - `MAX_AMOUNT` 引用 `BalanceConfig.SHIELD_MAX`
   - POOL 值类型从 `Integer` 改为 `Entry { int amount; float lastDecay; }`(记录上次 reconcile 时的 `Actor.now()` 时钟)。
   - **lazy reconcile**:每次 `addShield`/`absorb`/`getShield` 访问 entry 时,`reconcile(e)` 计算 `elapsed = Actor.now() - e.lastDecay`,`turns = (int)elapsed`(整 turn,小数 carry),若 `SHIELD_DECAY_PER_TURN > 0` 则 `amount = max(0, amount - ceil(turns * decay))`,`lastDecay += turns`(decay=0 时也推进 lastDecay,防 mid-run toggle 追溯衰减)。
   - **为什么用 Actor.now() 而非 tick() hook**:`Actor.now()` 是全局单调时钟,`spend(-TICK)` 退款只移动单 actor 调度、不逆转 `now`;mining 一 turn 多次 spend 也只按实际流逝时间衰减 1 次;MonkEnergy 5×spendConstant = 5 tick = 5 turn 衰减(语义正确)。codex 三轮 must-fix 全部消解。
   - 默认 `SHIELD_DECAY_PER_TURN == 0` → reconcile 短路 → M8b 行为字节级一致。
   - 测试 seam:`package-private static setClockForTest(float)` override `Actor.now()`,让测试确定性推进时间(无需驱动真实 actor process)。
   - 类保持 package-private(原设计),无 Hero.java tick wiring —— Hero.java 只改 MP/MPMax。
5. **ModManifest balance 字段**:`fromJson` 读可选 `balance` object(`Map<String, Number>`),缺省空 map。示例 `mod.json`:`{"balance": {"mana_regen_delay": 5.0, "shield_decay_per_turn": 1.0}}`。
6. **ModRegistry 启动合并**(codex must-fix #1:enable 切换也要重合并):抽 `private static synchronized void applyEnabledBalanceOverrides()` —— 先 `BalanceConfig.resetToDefaults()`,再遍历 `scanned`,对 `isEnabled(m.id)` 的 mod 调 `BalanceConfig.applyModOverrides(m.balance)`(扫描顺序合并,后扫覆盖先扫,key 级)。从两处调用:`scanFrom` 末尾(scan 完成后)、`setEnabled` 末尾(切换 enable 后)。这样 `enableTestMod` 的 `scanDir → setEnabled` 序列也能正确应用 balance 覆盖,disable 一个 balance mod 也能清除其覆盖(reset 后重合并)。现有 test mod 无 balance 字段 → 空 map → no-op,不影响现有测试。
7. **测试**:
   - `BalanceConfig` 默认值断言(MANA_REGEN_DELAY=8f, SHIELD_DECAY=0, SHIELD_MAX=1000, MANA_BASE=10, MANA_PER_LEVEL=2)
   - `applyModOverrides({"mana_regen_delay": 5.0})` → MANA_REGEN_DELAY==5f;`{"shield_max": 1500}` → SHIELD_MAX==1500;未知 key 忽略;`resetToDefaults()` 复位
   - SHIELD_DECAY_PER_TURN=0 时 reconcile 短路(pool 不减,行为与 M8b 一致)
   - SHIELD_DECAY_PER_TURN=1 时 `addShield(c,5)`@clock0 → clock3 → pool==2;clock10 → pool==0(不 negative);同 clock 多次读不重复衰减
   - `ModManifest.fromJson` 解析 balance 字段(有/无,缺省空 map;非数字值抛 IllegalArgumentException)

## Acceptance
- [x] BalanceConfig 默认值生效(mana regen 0.125/回合,shield cap 1000,decay 0)
- [x] 原有 Java 常量引用切到 BalanceConfig,行为除 MANA_DELAY(10→8)外不变
- [x] `SHIELD_DECAY_PER_TURN` 默认 0 → ShieldTracker 行为与 M8b 完全一致(reconcile 短路,向后兼容)
- [x] `SHIELD_DECAY_PER_TURN>0` → shield pool 按 elapsed game-time 衰减(lazy reconcile against Actor.now;不 negative;同 turn 多 spend 不重复衰减;退款不衰减)
- [x] `ModManifest` 解析 mod.json 的 balance 字段(可选,缺省不影响 manifest 加载)
- [x] `BalanceConfig.applyModOverrides` 合并 mod 覆盖正确(含 finite/range 校验)
- [x] `./gradlew :core:test` 全绿(419 现有 + 21 新增 = 440)
- [x] C3 不破(不碰 vanilla loot/spawn)

## 注意
- **绝不 `git add -A`**:`.claude/` 是 CAO memory artifact,绝不进 commit。只 `git add` 你改动的具体文件。
- **codex 评审用 codex exec workaround**:不要 `assign("codex_reviewer", ...)`(必超时失败 + 造孤儿终端烧配额)。直接 shell 跑 `codex exec --sandbox read-only "<评审本 PLAN 的指令>"`,smoke 通过即采用。
- BalanceConfig 放 `modding/` 子包(fork 代码集中点,见 CLAUDE.md「新增功能加到 modding/ 子包内」)。不要散到 actors/ 或根包。
