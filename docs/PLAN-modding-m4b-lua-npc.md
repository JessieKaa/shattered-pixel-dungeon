# PLAN: M4b — Lua NPC(可交互对话)

## Goal

让 Lua 定义可交互 NPC(对话),放在 m4a SafeZone 里,让 SafeZone 有功能内容(不只 RatKing+Gold)。MVP 不改 `InterlevelScene`,基于 m4a 的 DataDrivenLevel 落地。

## Context

m4a 完成(commit `27394d83e`,merge `ac5fa76f6`):JSON `DataDrivenLevel` + debug 入口 + `test_safezone.json`(RatKing + Gold)。但 RatKing 是被动硬编码 NPC,SafeZone 没有 Lua 驱动的交互内容。

M4b 加 **Lua 可交互 NPC**:Lua 定义 name/sprite/对话,玩家走近 interact 触发 Lua 回调。参考 Remixed `TownsfolkNPC`(被动对话)/`FortuneTellerNPC`(服务)。SPD NPC 抽象很轻:`NPC.java:28` extends Mob(HP=1/EXP=0/NEUTRAL/PASSIVE),`interact(Char)` 是交互入口 —— 见 `RatKing.java:117`(用 `yell()` + `GameScene.show(new WndOptions(...))` 弹对话)。

**为何这步在图结构之前**:m4a 已能让玩家进 SafeZone;先给 SafeZone 加可交互内容(NPC),价值立现且零流转风险。图结构(主线↔城镇往返,改 InterlevelScene)留 m4c,届时城镇已有功能内容,改流转只为"让玩家从主线进城镇"。

## Files

- `core/.../modding/LuaNpc.java`(新,extends NPC)— `interact` override 调 Lua + 无敌/不参战 override(参考 RatKing)
- `core/.../modding/LuaNpcRegistry.java`(新)— Registry 范式(参考 LuaMobRegistry)
- `core/.../modding/LuaEngine.java`(改)— 加 `register_npc` 全局 + 加载 `scripts/npcs/*.lua`
- `core/.../modding/RpdApi.java`(改)— 暴露 `npcYell(charId, text)` / `showDialog(charId, title, text)` 给 Lua(窄 API,沿用 M2 charId 范式)
- `core/.../modding/DataDrivenLevel.java`(改)— `createMobs` 的白名单支持 `lua_npc:<id>` type
- `assets/scripts/npcs/test_npc.lua`(新)— 测试 NPC(定义 name/sprite/onInteract)
- `assets/mods/levels/test_safezone.json`(改)— 加一个 Lua NPC
- **上游改动**:**0**(NPC 交互全走 LuaNpc override,不动 `NPC.java`/`Mob.java`)

## Steps

### 1. 调研(worker 先做,产出笔记)

- 读 `NPC.java:28`(基类)+ `RatKing.java:117`(interact 完整实现)+ `Mob.interact`(默认行为)
- 读 SPD 对话窗口:`WndOptions` / `WndMessage` / `WndDialogue`(若存在)—— 选最简的给 Lua 用
- 读 Remixed `TownsfolkNPC` / `FortuneTellerNPC`(`../remixed-dungeon/`)看 Lua NPC 模式
- 读 m4a 的 `DataDrivenLevel.java:createMobs`(看现有白名单怎么扩展)
- **产出**:LuaNpc 的 interact 路由方案 + 对话窗口选型

### 2. LuaNpc 实现

- extends `NPC`,`spriteClass` 复用现有(MVP,R1):Lua 定义 `spriteKey` 映射到 `RatKingSprite`/`MirrorImageSprite`/默认,不引入 Lua 自定义贴图(复杂)
- override 防战:参考 RatKing —— `defenseSkill`=INFINITE_EVASION / `damage()` no-op / `chooseEnemy()`=null / `add(Buff)`=false / `reset()`=true
- override `interact(Char c)`:`sprite.turnTo` → 调 `LuaItemCallbacks.callOpt("onInteract", heroId)`(沿用 M2 回调范式,charId)
- Lua `onInteract(heroId)` 回调:Lua 决定对话内容(返回字符串或调 `RpdApi.npcYell` / `RpdApi.showDialog`)
- Bundle:沿用 `lua_npc_id`(参考 LuaMob 的 lua_mob_id 范式)

### 3. RpdApi 对话接口

- `npcYell(charId, text)` → 找 NPC + `npc.yell(text)`
- `showDialog(charId, title, body)` → `GameScene.show(new WndMessage(...))`(MVP 单消息窗)
- 多选项对话树(WndOptions)留后续(在 Acceptance 注为可选)

### 4. 注册

`LuaEngine.register_npc(luaTable)` → `LuaNpcRegistry.register(id, def)`。沿用 M3 范式(Registry Map + hydrate + lua_npc_id Bundle)。`LuaEngine` 启动加载 `scripts/npcs/*.lua`(参考 items/spells loader)。

### 5. DataDrivenLevel.createMobs 扩展

m4a 现有白名单(`rat_king`→RatKing.class)。扩展:`lua_npc:<id>` → 从 `LuaNpcRegistry` 实例化 `LuaNpc`(set lua_npc_id + pos)。未知名跳过并 log。

### 6. test NPC + SafeZone

- `assets/scripts/npcs/test_npc.lua`:定义 id="test_npc" / name="Test NPC" / spriteKey="rat_king" / onInteract(heroId) → npcYell + showDialog
- `test_safezone.json`:mobs 数组加 `{"type":"lua_npc:test_npc","pos":<某格>}`

### 7. 回归验证

- debug 进 SafeZone,走近 Lua NPC interact,看到 Lua 定义的对话
- Lua NPC 无敌不参战(SafeZone 安全)
- 原版一周目不受影响(C3)
- 离开 SafeZone 回主线无污染(沿用 m4a isEphemeral 守卫)

## Acceptance

- ✅ SafeZone 里能和 Lua NPC 对话(走近 interact 触发)
- ✅ 对话内容(name/文本)由 Lua 定义
- ✅ Lua NPC 无敌不参战(defenseSkill INFINITE_EVASION / damage no-op)
- ✅ 原版一周目可正常开局(C3)
- ✅ 离开 SafeZone 回主线,无 depth/Rankings/GamesInProgress 污染(沿用 m4a 守卫)
- ✅ ≥1 单元测试:LuaNpc round-trip + interact 路由(用 mock/characterId 简化)
- ✅ modding/ 子包,C2 隔离
- ✅ **上游改动 0**(不动 NPC.java/Mob.java)
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: NPC sprite 系统**。SPD CharSprite 是 Java 子类,Lua 自定义贴图涉及 assets 加载 + TextureRegion 切割,复杂。**MVP 复用现有 sprite**(spriteKey 映射到 RatKingSprite/MirrorImageSprite 等),Lua 自定义贴图留后续。
- **R2: 对话窗口 Java/Lua 边界**。SPD WndOptions/WndMessage 是 Java。Lua 直接 new 不行(沙箱)。**走 RpdApi 窄接口**(npcYell/showDialog),沿用 M2 charId 范式(不 coerce Char)。
- **R3: interact 路由**。`Mob.interact` 默认行为(重新定向/交换位置)。LuaNpc override 要正确返回 boolean + 调用 `sprite.turnTo`(参考 RatKing.java:117-122)。
- **R4: Bundle 持久化**。沿用 `lua_npc_id`。SafeZone isEphemeral 不存档,bundle 路径主要给单元测试。
- **R5: C5 proguard**。LuaNpc 走反射 Bundle,确认 `android/proguard-rules.pro` 的 `modding.**` keep 覆盖(m4a 已加)。

## 参考

- SPD `NPC.java:28`(NPC 基类)
- SPD `RatKing.java:117`(interact 完整实现 + 无敌 override 模式)
- SPD `WndMessage` / `WndOptions`(对话窗口)
- Remixed `TownsfolkNPC` / `FortuneTellerNPC`(`../remixed-dungeon/.../mobs/npc/`)
- m4a `DataDrivenLevel.java:createMobs`(白名单扩展点)+ `ac5fa76f6` merge
- modding 范式:`LuaMob.java` + `LuaMobRegistry.java`(Registry + hydrate + lua_<type>_id)+ `LuaItemCallbacks.callOpt`(M2 回调)+ `RpdApi`(M2 窄 API + charId)
- 约束 C1-C5 + CLAUDE.md(modding 子包 + 上游最小 hook + proguard)

---

## Worker Refinement(阶段1,执行粒度)

### 调研结论(已读源码确认)

- **`NPC.java:28`** extends Mob,设 `HP=HT=1`/`EXP=0`/`alignment=NEUTRAL`/`state=PASSIVE`;override `act()`(Bestiary 追踪)+ `beckon()`(no-op)。**不 override `interact`** → 默认走 `Char.interact:245`(swap-places)。LuaNpc 必须 override `interact` 取消 swap。
- **`RatKing.java:117`** = 完整无敌模板:`defenseSkill→INFINITE_EVASION` / `chooseEnemy→null` / `damage→no-op` / `add(Buff)→false` / `reset→true`。`interact` 范式 = `sprite.turnTo(pos,c.pos)` → 分支 yell/`Game.runOnRenderThread(()->GameScene.show(new WndOptions(...)))` → `return true`。
- **`Mob.yell(String)` = `Mob.java:1058`**:仅 `GLog.n("%s: \"%s\"", name(), str)`(写消息日志,无 sprite 文本——SPD 范式)。`npcYell` 走它即可,无需 render-thread 包裹。
- **`Char.interact:245`** 默认 swap-places(要被 LuaNpc 取消)。
- **`WndMessage(String text)`** = 最简窗口,只接受单字符串(无 title 槽)。`GameScene.show(Window)` 在 `GameScene.java:1352`。
- **`DataDrivenLevel.createMobs:200-215`** 现有逻辑:`MOB_TYPES.get(spec.type)` → `Reflection.newInstance(cls)` → set pos → `mobs.add`。`passable[spec.pos]` 守卫已存在。
- **Remixed `TownsfolkNPC`** 确认同范式:`interact → turnTo + GameScene.show(dialogWnd) → return true`(继承 ImmortalNPC 做无敌)。本 PLAN 不引入 ImmortalNPC,直接 clone RatKing 的 5 个 override。
- **现有 modding 范式**:`LuaMob`/`LuaAlly`/`LuaMobRegistry`/`LuaAllyRegistry` = 1:1 模板;`LuaItemCallbacks.callOpt` 火后忘回调;`RpdApi.build()` = `RPD.*` LuaTable,每个函数是 `OneArgFunction`/`TwoArgFunction` 子类 + `resolveChar(charId)` 校验 `Actor.findById`。
- **proguard** `android/proguard-rules.pro:2` `-keepnames class com.shatteredpixel.shatteredpixeldungeon.** { *; }` 已覆盖 `modding.**`(line 37-38 注释确认),且 line 8 `-keep class * implements Bundlable` 双保险。R5:无需 proguard 改动。

### 决策(细化 + 偏离说明)

- **D1:`showDialog` 改 2-arg** `showDialog(charId, text)`,不取 title。原因:`WndMessage` 只接受单 string,无 title 槽;PLAN 原文 `showDialog(charId,title,body)` 的 title 在 MVP 无处安放,3-arg 误导。**保留 spirit**(窄 MVP 对话 API),偏离 letter。如需 title+多段,后续扩展或自定义 window。
- **D2:`npcYell` 目标类型 = `NPC`**(非 Mob、非 LuaNpc)。原因:API 名是 `npcYell`,意图窄;resolve 到 `instanceof NPC` 允许 RatKing/LuaNpc 等任意 NPC,拒绝 hostile mob/hero(防止滥用)。`yell` 实际定义在 `Mob`,NPC 继承之,cast 安全。
- **D3:不 override `act()`**。NPC 继承 `NPC.act`(Bestiary 追踪)+ Mob PASSIVE AI(不攻击)。M4b 范围是"可交互对话",不含 Lua NPC 自主移动/AI;Lua-driven NPC AI 留后续。surface 最小。
- **D4:`add(Buff)→false`** = 连正面 buff 也挂不上。SafeZone NPC 不需要 buff,可接受。
- **D5:sprite 白名单(NPC 主题)**:`rat_king`(RatKingSprite,默认 fallback)、`shopkeeper`、`mirror`(MirrorSprite)、`ghost`、`wandmaker`、`blacksmith`、`imp`。未知 key → RatKingSprite(主题契合 SafeZone 已有 RatKing)。
- **D6:`lua_npc:<id>` 在 createMobs 单独分支**,不入 `MOB_TYPES` map(prefix 含动态 id)。在 `MOB_TYPES.get` 之前判断 prefix。

### 实施清单(Files + 具体签名)

1. **`core/.../modding/LuaNpc.java`(新)** —
   ```java
   public class LuaNpc extends NPC {
       private static final String LUA_NPC_ID = "lua_npc_id";
       private String luaNpcId;
       private String nameStr = "???";
       public LuaNpc() { super(); }                          // Bundle restore
       public LuaNpc(LuaTable tbl) { super(); hydrate(tbl); }
       private void hydrate(LuaTable tbl) {                  // 不碰 HP/HT(NPC=1)
           luaNpcId = tbl.get("id").checkjstring();
           nameStr = tbl.get("name").checkjstring();
           spriteClass = resolveSprite(tbl.get("sprite").optjstring("rat_king"));
       }
       // 无敌 override(RatKing clone):
       @Override public int defenseSkill(Char e) { return INFINITE_EVASION; }
       @Override protected Char chooseEnemy() { return null; }
       @Override public void damage(int dmg, Object src) { /* no-op */ }
       @Override public boolean add(Buff b) { return false; }
       @Override public boolean reset() { return true; }
       // interact 路由(codex must-fix: 限定 hero,非 hero 不触发 Lua):
       @Override public boolean interact(Char c) {
           sprite.turnTo(pos, c.pos);
           if (c != Dungeon.hero) return true;  // RatKing 守卫:ally/clone 等 non-hero 不弹 Lua 对话
           LuaTable tbl = luaTable();
           if (tbl != null) LuaItemCallbacks.callOpt(tbl, "onInteract",
               LuaValue.valueOf(id()), LuaValue.valueOf(c.id()));
           return true;  // NPC handled, no swap
       }
       @Override public String name() { return nameStr; }
       @Override public String description() { return nameStr; }
       // Bundle:store/restore LUA_NPC_ID + re-hydrate(同 LuaMob 范式)
       // resolveSprite:SPRITES map(D5),fallback RatKingSprite
   }
   ```

2. **`core/.../modding/LuaNpcRegistry.java`(新)** — clone `LuaMobRegistry`:register/getTable/create/ids/contains/size/clear。`create(id)` → `new LuaNpc(tbl)`(未知返回 null)。

3. **`core/.../modding/LuaEngine.java`(改)** —
   - 加 `NPCS_DIR = "scripts/npcs"` 常量
   - `initInternal()`:`globals.set("register_npc", new RegisterNpcFunction());` + `loadNpcScripts();`
   - `loadNpcScripts()` → `loadScriptsFrom(NPCS_DIR, "Lua npcs", LuaNpcRegistry::size);`
   - `RegisterNpcFunction`(OneArgFunction):校验 `id`/`name`(必填),`sprite` 可选(optjstring),其余字段(onInteract)懒验证 → `LuaNpcRegistry.register`。

4. **`core/.../modding/RpdApi.java`(改)** — `build()` 加两个 TwoArgFunction:
   - `rpd.set("npcYell", new NpcYell());` → `call(charId, text)`:resolveChar → `instanceof NPC` 否则 log+NIL;`((NPC)c).yell(text)`(直接调,无需 render-thread,yell 仅写 GLog)。
   - `rpd.set("showDialog", new ShowDialog());` → `call(charId, text)`:resolveChar(any live Char,仅校验);`Game.runOnRenderThread(() -> GameScene.show(new WndMessage(text)))`(render-thread 包裹,因 onInteract 从 actor 线程触发)。

5. **`core/.../modding/DataDrivenLevel.java`(改)** — `createMobs()` 在 `MOB_TYPES.get(spec.type)` 之前加 prefix 分支:
   ```java
   if (spec.type.startsWith("lua_npc:")) {
       String npcId = spec.type.substring("lua_npc:".length());
       LuaNpc npc = LuaNpcRegistry.create(npcId);
       if (npc == null) { Gdx.app.error(TAG, "unknown lua_npc id: " + npcId + " — skipping"); continue; }
       if (spec.pos < 0 || spec.pos >= length() || !passable[spec.pos]) { /* log + skip */ continue; }
       npc.pos = spec.pos;
       mobs.add(npc);
       continue;
   }
   // existing MOB_TYPES.get path unchanged
   ```

6. **`core/src/main/assets/scripts/npcs/test_npc.lua`(新)** —
   ```lua
   register_npc {
       id = "test_npc",
       name = "测试 Lua NPC",
       sprite = "rat_king",
       onInteract = function(selfId, heroId)
           RPD.npcYell(selfId, "你好,冒险者!我是 Lua 定义的 NPC。")
           RPD.showDialog(selfId, "Welcome to the SafeZone.\nThis dialog is driven by Lua.")
       end,
   }
   ```

7. **`core/src/main/assets/mods/levels/test_safezone.json`(改)** — mobs 数组追加 `{"type":"lua_npc:test_npc","pos":102}`(pos 102 = x6/y6 内部 floor,passable ✓,在 entrance→rat_king 路径上)。

8. **测试 `core/src/test/.../modding/LuaNpcTest.java`(新)** — 镜像 `LuaMobTest`,覆盖:
   - Registry register/getTable/create/ids/contains/size/clear + unknown→null
   - LuaNpc hydrate:name/spriteClass(默认 RatKingSprite、白名单 rat_king/shopkeeper、未知 fallback)
   - 无敌:defenseSkill=INFINITE_EVASION、damage no-op(HP 不变)、add(Buff)=false、reset=true、chooseEnemy=null(**codex nice-to-have:chooseEnemy 是 Mob protected,modding 测试包不可直调,改用反射 `Mob.class.getDeclaredMethod("chooseEnemy").invoke(...)` 验证返回 null,或干脆只覆盖公开可观察的 defenseSkill/damage/add/reset 四件套**)
   - Bundle round-trip:lua_npc_id 存活 + name re-hydrate
   - register_npc 全局:必填校验(id/name)+ 船运 test_npc 经 LuaEngine.init 注册
   - **interact 路由**:用 OneArgFunction/TwoArgFunction mock 记录 onInteract(selfId, heroId) 参数被正确传入;**非 hero Char 传入不触发 onInteract**(codex must-fix 验收)
   - RPD.npcYell/showDialog 接线 + 坏参数(nil/non-int charId)拒绝
   - sandbox 回归(luajava 仍 stripped)
   - DataDrivenLevel.createMobs 的 `lua_npc:<id>` 分支:扩展 `DataDrivenLevelTest`(或新 test)—— 构造 sample level(含 lua_npc spec)+ 初始化 actor 集合(镜像 bundleRoundTripsEntranceAndId 的 setup)+ createMobs → 断言 mobs 含一个 LuaNpc 实例;未注册 id → skip 不抛

### 上游改动

**0**(确认:不动 NPC.java/Mob.java/Char.java/InterlevelScene;NPC 交互全走 LuaNpc override,createMobs 白名单扩展点在 m4a 已有的 DataDrivenLevel 内)。
