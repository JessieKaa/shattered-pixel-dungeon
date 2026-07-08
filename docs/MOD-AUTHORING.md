# Mod 创作指南(MOD-AUTHORING)

> **基线**:本文档基于 **M12a 代码基线**(commit `da6dae7f1`,versionCode **896**)核对撰写。
> **最后更新**:2026-07-08。
> API 签名 / schema / 校验规则全部逐行对照 `ModManifest.java` / `ModScanner.java` / `LuaEngine.java` / `RpdApi.java` 当前代码。**代码变更时本文档需同步更新**(见第 8 节「防 drift」)。
>
> 本 fork 的 modding 平台与上游 00-Evan 的 Shattered Pixel Dungeon **不兼容**(上游不支持 Lua mod)。与姐妹项目 Remished Dungeon(NYRDS)的脚本**也不直接兼容** —— 我们是自研的 luaj 子集 API(见第 5 节)。

---

## 目录

1. [Quick Start(5 分钟跑起来)](#1-quick-start)
2. [mod.json Schema](#2-modjson-schema)
3. [目录结构](#3-目录结构)
4. [register_* 注册 API 参考](#4-register_-注册-api-参考)
5. [RPD.* 运行期 API 参考](#5-rpd-运行期-api-参考)
6. [生命周期与回调](#6-生命周期与回调)
7. [安装与分发](#7-安装与分发)
8. [调试与常见错误](#8-调试与常见错误)
9. [完整示例:最小可玩 mod](#9-完整示例最小可玩-mod)

---

## 1. Quick Start

最小可用的 mod 只需两个文件:`mod.json`(清单)+ 一个调用 `register_*` 的 lua 脚本。

**`mods/hello/mod.json`**
```json
{
  "id": "hello",
  "name": "Hello Mod",
  "version": "0.1.0",
  "spd_version": 896,
  "default_enabled": true,
  "entry": "init.lua"
}
```

**`mods/hello/init.lua`**
```lua
register_item {
    id = "hello_sword",
    name = "问候之剑",
    desc = "我的第一个 Lua 物品。",
    image = 3,
    tier = 3,
}
```

把这俩文件放进 mod 目录(内置:`core/src/main/assets/mods/hello/`;外部玩家:`<可写目录>/mods_user/hello/`,见 [第 7 节](#7-安装与分发)),编译/重启 → 游戏菜单「模组管理」勾选 hello → 再重启 → `hello_sword` 即进入 Lua 物品池。

**三条铁律(违反则 mod 静默失效)**:
- `spd_version` 必须等于游戏 versionCode(当前 **896**),否则 mod 被跳过([§2](#2-modjson-schema))。
- `id` 只能是小写字母/数字/下划线,**且必须等于目录名**([§2](#2-modjson-schema))。
- Lua 沙箱**没有** `require` / `dofile` / `luajava` —— 不能跨文件加载,不能直接调 Java([§6](#6-生命周期与回调))。

---

## 2. mod.json Schema

来源:`ModManifest.fromJson`(`ModManifest.java:88`)。所有字段大小写敏感;类型不匹配(如把 `spd_version` 写成字符串)会被**严格拒绝**(不强制转换),整个 mod 被跳过。

### 字段一览

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | mod 唯一标识,正则 **`^[a-z0-9_]+$`**,且**必须等于所在目录名** |
| `name` | string | ✅ | — | 显示名(模组管理器里展示) |
| `version` | string | ✅ | — | mod 自己的版本号(任意字符串,如 `"0.1.0"`) |
| `spd_version` | integer | ✅ | — | **必须等于游戏的 `Game.versionCode`**(当前 **896**);取值范围 `[1, 2147483647]` |
| `author` | string | ❌ | `""` | 作者 |
| `default_enabled` | boolean | ❌ | `false` | 首次扫描时是否默认开启(**强烈建议 false**,让玩家主动开) |
| `description` | string | ❌ | `""` | 模组管理器里的描述文字 |
| `entry` | string | ❌ | `null` | 入口脚本相对路径,必须以 `.lua` 结尾;见约束 |
| `balance` | object | ❌ | `{}` | 平衡覆写,键→数字;见下 |

### `entry` 路径约束(`ModManifest.validateEntryPath`)

声明了 `entry` 就必须满足(否则整个 mod 被拒绝):
- 必须以 `.lua` 结尾。
- 必须是**相对路径**(不能以 `/` 开头)。
- **不能含反斜杠** `\`。
- **不能含 `..` 段**(防穿越到别的 mod 或内部资源)。

合法:`"init.lua"`、`"scripts/bootstrap.lua"`。非法:`"/init.lua"`、`"..\\x.lua"`、`"../other/init.lua"`、`"init.txt"`。

### `balance` 平衡覆写(M9c)

`balance` 是一个「键→数字」的对象,键是 `BalanceConfig` 的规范名,值为数字。只有 **enabled 的 mod** 的覆写会生效;`ModRegistry` 在扫描/开关时按扫描顺序合并(**后扫的 mod 同键覆盖先扫的**)。常见键(非穷举,完整列表见 `BalanceConfig.java`):`mana_regen_delay`、`shield_decay_per_turn` 等。

```json
"balance": { "mana_regen_delay": 8.0 }
```

> ⚠ `balance` 不在本指南深入范围 —— 它是平台级调参,不是 per-entity 注册。需要时直接读 `BalanceConfig.java` 的键名。

### 校验规则(`ModScanner.scanChildren`,`ModScanner.java:147`)

扫描时**逐 mod**校验,任一不过即**跳过该 mod 并记日志**(不会让其他 mod 或游戏启动失败):

1. 目录下必须有 `mod.json` 文件(不是目录)。
2. `mod.json` 能被 `ModManifest.fromJson` 解析(字段齐全 + 类型正确)。
3. **`manifest.id` 必须等于目录名**(目录叫 `hello` 就不能声明 `id=hi`)。
4. 同一扫描通道内 `id` 不重复(重复的后来的被跳过)。
5. **`manifest.spd_version == Game.versionCode`**(否则跳过,**无降级/迁移**)。
6. builtin(`assets/mods/`)与 external(`mods_user/`)合并时,**id 冲突 builtin 胜、external 被跳过**(`ModScanner.mergeById`)。

> 这些校验失败**只打日志**(SPD 默认 logLevel=2,见 [§8](#8-调试与常见错误)),不会弹窗。mod 不生效时第一步查日志。

---

## 3. 目录结构

一个 mod 的标准目录树(来源:`LuaEngine.loadXxxScripts`,`LuaEngine.java:212` 起):

```
mods/<id>/
├── mod.json              # 清单(必需)
├── init.lua              # 入口脚本(可选,对应 manifest.entry;名字可自定义)
└── scripts/
    ├── items/      *.lua   # register_item
    ├── mobs/       *.lua   # register_mob
    ├── allies/     *.lua   # register_ally
    ├── heroes/     *.lua   # register_hero
    ├── spells/     *.lua   # register_spell
    ├── npcs/       *.lua   # register_npc
    ├── shops/      *.lua   # register_shop
    ├── buffs/      *.lua   # register_buff + register_talent_override
    ├── talents/    *.lua   # register_talent / register_talent_override
    ├── painters/   *.lua   # register_painter
    └── traps/      *.lua   # register_trap
```

**子目录名是硬约定** —— `register_item` 调用必须出现在 `scripts/items/*.lua` 里,`register_mob` 在 `scripts/mobs/`,以此类推。放错目录 = 该脚本不被加载。

另有**全局** `scripts/init.lua`(注意:是 `assets/scripts/init.lua`,**不是** mod 内的),由 `LuaEngine` 在启动时跑一次(`INIT_SCRIPT = "scripts/init.lua"`)。一般 mod 不需要碰它。

### 脚本可以放在哪

每个 `scripts/<type>/` 目录下的脚本文件,文件名随意(按字典序加载),一个文件里可以调用多次 `register_*`。**没有模块系统** —— 因为沙箱剥离了 `require`/`dofile`,脚本之间不能互相 import,每个脚本独立编译。

### 内置 vs 外部

- **内置 mod**:打包进 APK/jar,位于 `core/src/main/assets/mods/<id>/`。走 classpath 双通道加载(`LuaEngine.listBuiltinScriptNames`)。
- **外部 mod**:玩家放进可写 `mods_user/<id>/` 目录,走纯 `FileHandle` 加载(`LuaEngine.listExternalScriptNames`)。结构完全一样。

两者的 `mod.json`/`scripts/` 结构**完全相同**,只是物理位置不同(见 [§7](#7-安装与分发))。

---

## 4. register_* 注册 API 参考

所有 `register_*` 都是 **Lua 全局函数**,接收一个 table。来源:`LuaEngine.initInternal`(`LuaEngine.java:105-156`)注册了 **13 个**:

| 函数 | 加载目录 | 一句话用途 |
|---|---|---|
| `register_item` | `scripts/items` | 武器/护甲/材料等可装备或消耗物品 |
| `register_mob` | `scripts/mobs` | 敌对怪物(不进原版刷怪池) |
| `register_ally` | `scripts/allies` | 友方宠物(继承 follow/defend/attack) |
| `register_hero` | `scripts/heroes` | 自定义职业 |
| `register_spell` | `scripts/spells` | 消耗性法术(Item 子类,使用即消耗) |
| `register_npc` | `scripts/npcs` | 被动/无敌 NPC(onInteract) |
| `register_shop` | `scripts/shops` | 商店 NPC(自定义商品池) |
| `register_level` | (不扫脚本) | 注册数据驱动关卡 id(几何在 `mods/levels/<id>.json`) |
| `register_buff` | `scripts/buffs` | 自定义 buff |
| `register_talent_override` | `scripts/talents` | 改调原版天赋(maxPoints 只下调 / desc) |
| `register_talent` | `scripts/talents` | 激活新的 `MOD_` 前缀天赋进 tier |
| `register_painter` | `scripts/painters` | 房间绘制器(按 Room 类名 overlay) |
| `register_trap` | `scripts/traps` | 自定义陷阱 |

### 通用约定

- **必填字段缺失或类型错** → 该注册被拒绝并记日志(`Gdx.app.error`),**不影响同文件其他注册或别的 mod**。
- **回调字段**(下面标「回调」的)全是可选的、best-effort:缺失 / 抛错 / 返回非数字 → 自动回退到宿主默认值,**永远不会崩游戏**。
- 所有回调收到的「角色引用」都是 **`int` charId**(通过 `Actor.findById` 解析),**绝不传 Java Char 对象**(M1 沙箱边界)。用 `RPD.*` 系列 API 操作角色(见 [§5](#5-rpd-运行期-api-参考))。

---

### 4.1 `register_item`

来源:`RegisterItemFunction`(`LuaEngine.java:575`)。

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | 物品 id |
| `name` | string | ✅ | — | 显示名 |
| `tier` | int | ⚠ | — | 等级;**除非 `type`/`kind == "material"` 否则必填** |
| `type` / `kind` | string | ❌ | `""` | 设为 `"material"` 时跳过 tier 检查(走 LuaMaterial 路径) |
| `image` | int | ❌ | `0` | 图片索引 |
| `desc` | string | ❌ | — | 描述 |
| `actions` / `defaultAction` | table/string | ❌ | — | 自定义右键/长按动作(M11c) |
| `glowing` | table | ❌ | — | 发光效果 |

**回调**(由 `LuaItemCallbacks` 分发):
- `onUse(heroId)` — 使用时
- `onEquip(heroId)` — 装备时
- `onDeactivate(heroId)` — 卸下时
- `attackProc(selfId, enemyId, baseDamage) → int` — 攻击过程;返回修改后的伤害(返回 baseDamage = 无操作)

```lua
register_item {
    id = "bone_saw",
    name = "骨锯",
    desc = "每次命中回 1 血。",
    image = 40,
    tier = 4,
    attackProc = function(selfId, enemyId, baseDamage)
        RPD.healChar(selfId, 1)   -- 见 §5
        return baseDamage
    end,
}
```

**材料变体**(M11b,可食用/使用):设 `type = "material"`,额外字段 `energy`/`price`/`quantity`/`stackable`/`onUse`/`onThrow`/`info`,由 `LuaMaterial` 读取。

---

### 4.2 `register_mob` / `register_ally`

来源:`RegisterMobFunction`(`LuaEngine.java:757`)/ `RegisterAllyFunction`(`LuaEngine.java:793`)。字段完全一样(ally 多一个 `onCommand` 回调)。

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | 怪物 id |
| `name` | string | ✅ | — | 显示名 |
| `hp` | int | ✅ | — | 当前血量 |
| `ht` | int | ❌ | `=hp` | 最大血量(不填则等于 hp) |
| `attack` | int | ✅ | — | 攻击力 |
| `defense` | int | ✅ | — | 防御力 |
| `sprite` | string | ❌ | `"crab"` | 精灵白名单键(见下) |

> **mob/ally 精灵白名单**(`LuaMob.SPRITES` / `LuaAlly.SPRITES`):`crab` / `rat` / `slime` / `gnoll` / `brute` / `skeleton` / `bat`。平台**不随包提供新美术**,未知键回退到 `crab`(降级但不崩)。要全新视觉需自己改 Java 加精灵类。

**回调**:`act(selfId)` / `attackProc(selfId, enemyId, baseDamage)→int` / `defenseProc(selfId, enemyId, baseDamage)→int` / `die(selfId, killerId)` / `spawn(selfId)`(仅 mob)。ally 另有 `onCommand(allyId, cmd, targetId)`。

> mob/ally **不进原版刷怪池**,只能通过 `RPD.spawnMob(id, pos)` / `RPD.spawnAlly(id, pos)` 主动召唤([§5](#5-rpd-运行期-api-参考))。省略 `act` → 回退到上游 AI(普通寻路敌人)。

```lua
register_mob {
    id = "test_mob",
    name = "测试 Lua 怪物",
    hp = 20, ht = 20, attack = 8, defense = 4,
    sprite = "crab",
    attackProc = function(selfId, enemyId, baseDamage)
        return baseDamage   -- 无修改
    end,
}
```

---

### 4.3 `register_hero`

来源:`RegisterHeroFunction`(`LuaEngine.java:829`),字段解析委托 `LuaHeroClass.hydrate`。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | 职业 id |
| `name` | string | ✅ | 显示名 |
| `talentSource` | string | ✅ | 宿主职业(`HeroClass` 枚举名,如 `"WARRIOR"`),借用其天赋树 |
| `hp` | int | ✅ | 初始血量 |
| `defenseSkill` | int | ❌ | 防御技能值 |
| `startingItems` | table | ❌ | 初始物品 id 列表 |
| `spriteKey` | string | ❌ | 精灵键 |

> Lua hero 会在选职业界面作为额外按钮出现(`HeroSelectScene`)。`talentSource` 决定借用哪个原版职业的天赋,`id` 是 sidecar 标记(D3 设计)。

---

### 4.4 `register_spell`

来源:`RegisterSpellFunction`(`LuaEngine.java:611`)。spell 是 `Item` 子类,**使用即消耗**。

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | 法术 id |
| `name` | string | ✅ | — | 显示名 |
| `image` | int | ❌ | `0` | 图片索引 |
| `desc` | string | ❌ | — | 描述 |
| `useMode` | string | ❌ | — | 使用模式(M7d mana 双轨相关) |
| `spellCost` | number | ❌ | — | 法力消耗 |
| `castTime` | number | ❌ | — | 施法时间 |
| `targeting` | string | ❌ | — | 瞄准模式 |

**回调**:`onUse(heroId)` / `onUseAt(heroId, cell)`。

> spell **不进原版战利品池**,需要通过控制台/作弊或 `RPD.giveItem` 发放入背包([§5](#5-rpd-运行期-api-参考))。

```lua
register_spell {
    id = "test_spell",
    name = "测试法术",
    image = 0,
    onUse = function(heroId)
        RPD.GLog("spell used on hero " .. tostring(heroId))
    end,
}
```

---

### 4.5 `register_npc` / `register_shop`

`register_npc`(`LuaEngine.java:640`):

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | NPC id |
| `name` | string | ✅ | — | 显示名 |
| `sprite` | string | ❌ | `"rat_king"` | 精灵键 |

回调:`onInteract(heroId)`。

`register_shop`(`LuaEngine.java:723`)继承 npc,额外:

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `items` | table | ✅ | — | 商品列表,每项 `{id=, price=, quantity=}` |
| `sprite` | string | ❌ | `"shopkeeper"` | 精灵键 |

> npc/shop **不进原版刷怪池**,只能通过数据驱动关卡(`DataDrivenLevel`)的 mob spec 里写 `lua_npc:<id>` / `lua_shop:<id>` 进入关卡。

---

### 4.6 `register_buff`

来源:`RegisterBuffFunction`(`LuaEngine.java:882`)。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | buff id |
| `name` | string | ✅ | 显示名 |
| `icon` | int | ❌ | 图标索引 |
| `info` | string | ❌ | 描述 |

**回调**(丰富,`LuaBuff` 分发):
- `act(selfId, targetId, state) → int`(返回下次 act 间隔)
- `attachTo(targetId, state) → bool` / `detach(targetId, state)`
- `attackProc(selfId, enemyId, damage)→int` / `defenseProc(selfId, enemyId, damage)→int`
- `damage(selfId, enemyId, damage)`(M11a:带 state 参数)
- `drBonus(...)` / `speed(...)` — 数值覆写
- `tintChar(...)` / `setGlowing(...)` / `charSpriteStatus(...)` — 视觉
- `onRestore(state)` — 读档恢复
- `immunities` — table,免疫的 buff 名

> Lua buff 只能通过 `RPD.affectBuff` / `RPD.permanentBuff` / `RPD.removeBuff`(传 Lua id 字符串)施加,或由 Lua item/mob 在回调里挂上。

---

### 4.7 `register_talent_override` / `register_talent`

**`register_talent_override`**(`LuaEngine.java:912`)—— **改调原版天赋**(不能新增):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | 原版 `Talent` 枚举名(如 `"HEARTY_MEAL"`),经 `Talent.valueOf` 解析,未知则跳过 |
| `maxPoints` | int | ❌ | **只能下调**(上调会破坏 `[0, vanilla]` 公式域) |
| `desc` | string | ❌ | 描述覆写 |

**`register_talent`**(`LuaEngine.java:956`)—— **激活新天赋**(D6(b)),约束严格:

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | 必须以 **`MOD_`** 开头,且是 `Talent.java` 里**已声明**的枚举常量(如 `MOD_EXAMPLE_TALENT`);原版名会被拒(改调原版用 override) |
| `tier` | int | ✅ | `1`–`4` |
| `name` 或 `title` | string | ⚠ | 至少一个(MOD_ 天赋没有 `.title` properties 键,不填会显示占位符) |
| `class` | string | ⚠ | tier 1/2 必填,值为 `HeroClass` 名(如 `"WARRIOR"`) |
| `subclass` | string | ⚠ | tier 3 必填,值为 `HeroSubClass` 名 |
| `armor_ability` | string | ⚠ | tier 4 必填,值为 `ArmorAbility` 类简单名 |
| `desc` / `maxPoints` | string/int | ❌ | 转发到 override 路径 |
| `on_upgrade` | function | ❌ | 升级时回调(M8d2) |

> **tier↔key 互斥**:tier 1/2 只要 `class`,tier 3 只要 `subclass`,tier 4 只要 `armor_ability`。三者必须**恰好出现一个**且匹配 tier,否则跳过。
>
> ⚠ 新天赋必须先在 `Talent.java` 里声明 `MOD_XXX` 枚举常量 —— 纯 Lua mod **无法**新增天赋槽,这是当前平台的硬限制(需要改 Java 代码 + 重新编译游戏)。

---

### 4.8 `register_level`

来源:`RegisterLevelFunction`(`LuaEngine.java:855`)。level 不扫脚本目录,几何在 JSON 文件里。

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | 关卡 id |
| `name` | string | ✅ | — | 显示名 |
| `path` | string | ❌ | `mods/levels/<id>.json` | 几何 JSON 路径 |

> level 是独立子系统(`DataDrivenLevel` + `LuaLevelService`),`path` 默认指向**共享** `mods/levels/`(非 per-mod)。外部 mod 的 level 定位属未完成范围(M12a 显式延后)。

---

### 4.9 `register_painter` / `register_trap`

`register_painter`(`LuaEngine.java:667`):

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | Room 类简单名(overlay 目标) |

回调:`paint(...)` / `decorate(...)`。

`register_trap`(`LuaEngine.java:693`):

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | ✅ | — | 陷阱 id |
| `name` | string | ❌ | `=id` | 显示名 |
| `color` | int | ❌ | `Trap.GREY` | 颜色常量 |
| `shape` | int | ❌ | `Trap.DOTS` | 形状常量 |

回调:`onActivate(...)`。

> painter 在上游 `RegularPainter` 管线后 overlay;trap 由 `LuaLevelService.injectLevelTraps` 放置。trap **不进原版陷阱池**。

---

## 5. RPD.* 运行期 API 参考

`RPD` 是注入 Lua 的一个 table(`globals.set("RPD", RpdApi.build())`,`LuaEngine.java:159`)。来源:`RpdApi.build()`(`RpdApi.java:142`)。下面每个签名都对照了对应函数类的 `call(...)` 参数。

### 通用约定

- 所有写路径**校验参数**,非法输入 → 记日志 + 返回 NIL/false,**不崩战斗/不绕过 buff 白名单**。
- 「角色引用」一律是 `int charId`,经 `Actor.findById` 解析;找不到/非 Char → NIL。
- 数量/时长参数有上限 `MAX_AMOUNT = 1000`,越界被拒。

### 5.1 日志

| 签名 | 说明 |
|---|---|
| `RPD.GLog(msg)` | 游戏内消息行(`GLog.i`) |
| `RPD.GLogW(msg)` | 警告色消息行(`GLog.w`) |

### 5.2 角色查询

| 签名 | 返回 | 说明 |
|---|---|---|
| `RPD.charHP(charId)` | int | 当前血量 |
| `RPD.charPos(charId)` | int | 所在格子 |
| `RPD.charName(charId)` | string | 名字 |
| `RPD.charAtCell(cell)` | int/nil | 该格子的角色 id |
| `RPD.enemyOf(mobId)` | int/nil | 该 mob 的当前敌人 id |
| `RPD.cellDistance(posA, posB)` | int | 两格距离 |
| `RPD.emptyCellNextTo(pos)` | int | 旁边的空格 |
| `RPD.levelWidth()` | int | 当前关卡宽度 |

### 5.3 伤害 / 治疗 / 移动

| 签名 | 说明 |
|---|---|
| `RPD.damageChar(charId, amount)` | 造成伤害(来源标记为 `LuaScript`) |
| `RPD.healChar(charId, amount)` | 治疗 |
| `RPD.blink(mobId, pos)` | 瞬移角色到 pos |
| `RPD.teleportChar(charId, pos)` | 传送 |
| `RPD.cellRay(fromCell, toCell)` | 返回 int 数组(射线上的格子) |
| `RPD.zapEffect(fromCell, toCell)` | 视觉电击效果 |

### 5.4 召唤 / AI

| 签名 | 说明 |
|---|---|
| `RPD.spawnMob(mobId, pos)` | 召唤敌对 mob(不进刷怪池) |
| `RPD.spawnMobNear(mobId, centerCell)` | 在 centerCell 附近召唤 |
| `RPD.spawnAlly(allyId, pos)` | 召唤友方 |
| `RPD.commandAlly(allyCharId, cmd, targetId)` | 指挥友方(follow/defend/attack) |
| `RPD.expelAlly(allyCharId)` | 驱散友方 |
| `RPD.setMobAi(mobId, aiTag)` | 切换 mob AI |

### 5.5 Buff

| 签名 | 说明 |
|---|---|
| `RPD.affectBuff(charId, buffName, amount)` | 施加 buff;`buffName` 是白名单 Java buff 名或 **Lua buff id**;amount 语义因 buff 而异(时长或层级) |
| `RPD.removeBuff(charId, buffId)` | 移除 buff(`RPD.detachBuff` 是别名,同实现) |
| `RPD.permanentBuff(charId, buffId, level)` | 永久 buff |
| `RPD.setBuffLevel(charId, buffId, level)` | 设层级 |
| `RPD.buffLevel(charId, buffId)` | 读层级 |
| `RPD.addImmunity(charId, idVal)` | 加免疫 |

> `RPD.Buffs` 是白名单 Java buff 名常量表(构建于 `loadBuffScripts` 之前,所以**只含 Java 白名单**,不含 Lua buff id —— Lua buff id 直接用字符串传)。

### 5.6 物品 / 背包

| 签名 | 返回 | 说明 |
|---|---|---|
| `RPD.giveItem(charId, itemId, qty)` | bool | 给角色物品;**每英雄每层累计上限 20 件**(防刷屏) |
| `RPD.randomBackpackItem(charId)` | int | 背包里随机一个物品的 **1-based** 索引 |
| `RPD.itemName(charId, index)` | string | 背包物品名 |
| `RPD.removeBackpackItem(charId, index, qty)` | — | 移除背包物品 |
| `RPD.setItemCursed(charId, index)` | bool | 诅咒背包物品(单向);true=新诅咒,false=已诅咒 |
| `RPD.stealRandomItem(mobId, targetHeroId)` | — | 偷物品(法术/小偷用) |
| `RPD.stolenLootName(mobId)` | string | 偷来的物品名 |

### 5.7 法力(M7d,仅 Hero)

| 签名 | 说明 |
|---|---|
| `RPD.heroMana(heroId)` | 当前法力 |
| `RPD.heroManaMax(heroId)` | 法力上限 |
| `RPD.spendMana(heroId, amount)` | 消耗法力 |
| `RPD.restoreMana(heroId, amount)` | 恢复法力 |

### 5.8 护盾(M8b)

| 签名 | 说明 |
|---|---|
| `RPD.addShield(charId, amount)` | 加护盾点(`ShieldTracker` 池) |
| `RPD.charShield(charId)` | 读护盾 |
| `RPD.absorbShield(charId, dmg)` | 吸收伤害,返回剩余伤害 |

### 5.9 对话 / 城镇

| 签名 | 说明 |
|---|---|
| `RPD.yell(charId, text)` | 角色 yell(GLog 行) |
| `RPD.npcYell(charId, text)` | NPC yell(NPC 专用) |
| `RPD.showDialog(charId, text)` | 弹 `WndMessage` 消息窗 |
| `RPD.encumbranceItemName(heroId)` | 负重物品名 |
| `RPD.enterTown(levelId)` | 进城(town-portal,isDebug 守卫) |
| `RPD.leaveTown(ignored)` | 离城 |

### 5.10 地形(M10b/M11c)

| 签名 | 说明 |
|---|---|
| `RPD.terrain(pos)` | 读地形 id |
| `RPD.setTerrain(pos, terrainId)` | 设地形( painter 受白名单约束,只允许装饰子集) |
| `RPD.isWall(pos)` | 是否墙 |
| `RPD.isSolid(pos)` | 是否实心 |
| `RPD.dig(pos)` | 挖(M11c 镐) |
| `RPD.dropItem(pos, itemId, qty)` | 在格子放物品 |
| `RPD.placeBlob(blobId, pos, amount)` | 放 blob(气体/火焰等) |

> `RPD.Terrain` / `RPD.Blobs` 是常量表(见下)。

### 5.11 常量表

| 表 | 内容 |
|---|---|
| `RPD.Terrain` | `EMPTY`/`EMPTY_DECO`/`EMPTY_SP`/`EMBERS`(可写子集)+ `WALL`/`WALL_DECO`/`DOOR`/`WATER`/`GRASS`/`HIGH_GRASS`/`FURROWED_GRASS`/`SECRET_DOOR`/`BARRICADE`/`TRAP`/`SECRET_TRAP`(只读参考) |
| `RPD.Blobs` | blob 名常量(白名单) |
| `RPD.Buffs` | Java buff 名常量(白名单,Lua buff id 不在此) |

---

## 6. 生命周期与回调

### 6.1 加载顺序(`LuaEngine.initInternal`,`LuaEngine.java:91`)

游戏启动时(`Game.create` → `LuaEngine.init()`)按这个顺序执行,**只对 enabled 的 mod**:

1. **构建沙箱 globals**:剥离危险库(见下),注入 13 个 `register_*` 全局 + `RPD` 表。
2. **跑全局 `scripts/init.lua`**(assets 级,不是 mod 级)—— 一般 mod 不用。
3. **逐 mod、逐子目录加载脚本**:items → mobs → allies → heroes → spells → npcs → shops → buffs → talents → painters → traps(每个子目录的 `*.lua` 按字典序编译)。**disabled 的 mod 贡献零 Lua 内容**(C3 回归基线)。
4. **跑每个 enabled mod 的 `entry` 脚本**(`mods/<id>/<manifest.entry>`),它通常调用 `register_*`。

> 所以 `register_*` 可以出现在两类地方:(a) `scripts/<type>/*.lua`(自动扫描),(b) mod 的 `entry` 脚本(显式跑)。两者等价,选其一即可。

### 6.2 沙箱边界(`LuaSandbox.exposedGlobals`)

**可用**:标准 Lua 基础库(`string`/`table`/`math`/`coroutine`/`pairs`/`ipairs`/`print`/`type`/`tonumber`/...)。

**被剥离**(设为 nil):
- `io` / `os` / `package` / `debug`
- **`luajava`** —— 不能 `bindClass`/`newInstance` 进 Java(关键安全边界)
- **`load` / `loadfile` / `dofile` / `loadstring` / `require`** —— 不能运行期编译/跨文件加载
- `getfenv` / `setfenv`

> 后果:**mod 不能 `require` 自己的别的 lua 文件**。每个脚本独立编译,公共逻辑只能复制或放进 `RPD` 暴露的 API。宿主侧用 `Globals.load(source, chunk)`(Java 方法)编译你的脚本,所以你不需要 `dofile`。

### 6.3 回调机制(`LuaItemCallbacks`)

宿主在合适时机(攻击/装备/act tick/...)通过 `table.get(fnName)` 取你的回调函数并 `invoke`:

- **缺失或非函数** → 跳过(回退宿主默认)。
- **抛 Lua 错** → 记日志,回退默认。
- **数值回调返回非数字/nil** → 回退 `fallback` 值。

所以回调**全部可选、全部 best-effort**,写错了最坏情况是该功能不生效,**不会崩游戏**。

### 6.4 enabled / disabled 切换(`ModRegistry`)

- 开关状态持久化在 `GameSettings` prefs,key = `mod_enabled_<id>`,默认值 = `manifest.default_enabled`。
- **切换后必须重启游戏才生效** —— 注册是一次性、不可逆的(`LuaEngine` 只 init 一次),**没有 hot-reload**。模组管理器底部提示「更改在重启游戏后生效」就是这意思。
- `balance` 覆写在开关时即时重新合并(但其他注册内容仍需重启)。

### 6.5 id 冲突规则

- 同一通道内重复 id:后来的被跳过。
- **builtin 与 external 冲突:builtin 胜**,external 被跳过 + 记日志(防止旧的外部副本盖掉打包版本)。
- 扫描顺序:builtin 先、external 后,各自按目录名字典序。

---

## 7. 安装与分发

> **当前代码实状(M12a)**:游戏**没有**一键 zip 导入 UI(无 `JFileChooser` / SAF / `ModInstaller`)。下面第 7.1 是**当前唯一**的安装方式;7.2 的 zip 导入属 **M12b/c 规划,尚未实现**,这里只描述规划方向,不能照抄为现有功能。

### 7.1 手动安装(当前可用)

外部 mod 放在可写目录 **`mods_user/`** 下,结构与内置 mod 完全一样:

```
<可写目录>/mods_user/<id>/
├── mod.json
├── init.lua            (可选)
└── scripts/...
```

`mods_user/` 由 `Gdx.files.local("mods_user/")` 解析(`ModScanner.externalModsRoot`):

- **Android** = app 内部 files 目录(`getFilesDir()`)—— **无需任何存储权限**,但用户不可见,需要 root 或 `adb push` 才能直接写。
- **Desktop** = 游戏工作目录下的 `mods_user/` 文件夹(可见,直接拖文件进去)。

安装步骤:
1. 把 `<id>/` 整个文件夹放进 `mods_user/`。
2. **重启游戏** → `ModScanner.scan()` 扫到它,标 `origin = EXTERNAL`。
3. 游戏菜单 → **模组管理** → 看到该 mod,标签 `[外部]`(英文 `[external]`)→ 勾选启用。
4. **再重启** → 该 mod 的脚本被加载,注册生效。

> 内置 mod(打包进 APK)显示 `[内建]`(英文 `[built-in]`)。两者结构、加载逻辑完全相同,只是物理位置和 origin 标签不同。

### 7.2 zip 分发(建议的打包方式)

虽然游戏暂无导入 UI,但**推荐创作者以 zip 分发**,玩家手动解压到 `mods_user/`。zip 结构建议(为未来 M12b/c 导入 UI 铺路):

**推荐 A — mod.json 在 zip 根**:
```
my_mod.zip
├── mod.json
├── init.lua
└── scripts/...
```
玩家解压时需先建 `mods_user/my_mod/` 再解压进去(保证目录名 == mod id)。

**推荐 B — 唯一顶层目录**:
```
my_mod.zip
└── my_mod/            ← 目录名必须 == mod.json 里的 id
    ├── mod.json
    ├── init.lua
    └── scripts/...
```
玩家直接解压到 `mods_user/`,得到 `mods_user/my_mod/...`。

> 关键:`mods_user/` 下**直接子目录的名字**必须等于 `mod.json` 的 `id`(校验规则 3,见 [§2](#2-modjson-schema))。所以 zip 顶层目录名必须和 id 一致,或 mod.json 在 zip 根由玩家手动放目录。

### 7.3 M12b/c 规划(尚未实现,仅作预告)

路线图上的后续里程碑(代码里**尚未实现**,不要假设可用):
- **M12b** — desktop 端 zip 一键导入(`JFileChooser` 选 zip → 解压到 `mods_user/`)。
- **M12c** — android 端 SAF 导入(`ContentResolver` + `openDocument`)。
- 导入时的 zip 校验(条目数上限、解压体积上限、路径穿越禁止、`mod.json` 必须在根或唯一顶层 dir)等契约,要等实现后才有定义。

当前(2026-07-08)分发 = **手动放 `mods_user/<id>/`**。

---

## 8. 调试与常见错误

### 8.1 日志怎么看

SPD 默认 libgdx `logLevel = 2`(LOG_ERROR),**`Gdx.app.log` / `System.err.println` 在 INFO 级会被过滤**。可靠调试手段:

- **Lua 脚本里**:用 `RPD.GLog(msg)` / `RPD.GLogW(msg)` —— 直接走游戏内消息行(`GLog.i`/`GLog.w`),玩家可见,不受 logLevel 影响。这是最可靠的 mod 调试输出。
- **看注册/扫描日志**:`ModScanner` / `LuaEngine` / 各 `register_*` 的拒绝信息走 `Gdx.app.error`。Android 用:
  ```bash
  adb -s 20210119085654 logcat -d | grep -iE "ModScanner|LuaEngine|RpdApi"
  ```
  Desktop:看 stderr / 启动终端输出。

### 8.2 「我的 mod 没生效」排查清单

按顺序查:

1. **`spd_version` == 896?** 不符 → mod 被静默跳过(日志:`spd_version=X != 896, skip`)。
2. **目录名 == `mod.json` 的 `id`?** 不符 → 跳过(日志:`mod dir X declares id Y, skip`)。
3. **`id` 符合 `^[a-z0-9_]+$`?** 含大写/连字符 → 拒绝。
4. **`entry` 路径合法?**(相对 / `.lua` 结尾 / 无 `\` / 无 `..`)。
5. **mod 启用了?** 模组管理器里勾选。
6. **重启了?** 开关 / 安装后都要重启才生效。
7. **id 与某个内置 mod 冲突?** builtin 胜,external 被跳过。
8. **脚本放对子目录了?**(`register_item` 必须在 `scripts/items/`,等等)。
9. **`register_*` 调用的必填字段齐全?**(如 `register_mob` 少了 `attack` → 该注册被拒,日志:`register_mob rejected a malformed definition`)。

### 8.3 常见 Lua 陷阱

- **没有 `require`** —— `require "mylib"` 会报错(`attempt to index a nil value`)。公共代码只能复制粘贴,或塞进 `RPD` 不行的话就重复写。
- **没有 `luajava`** —— `luajava.newInstance(...)` / `bindClass(...)` 不可用。要操作 Java 对象只能用 `RPD.*` 暴露的接口。
- **回调返回值类型要对** —— 数值回调(如 `attackProc`)必须 `return` 一个数字;返回字符串会被拒(日志:`returned non-number ...; using fallback`)。
- **charId 不是对象** —— `heroId`/`mobId` 是 `int`,要操作角色必须走 `RPD.*`(传 id 进去),不能 `heroId:attack(...)`。

### 8.4 防 drift(给后续维护者)

本文档基于 **M12a** 代码基线撰写。代码改了之后,API 参考**可能漂移**。更新检查清单:

| 改了什么 | 查哪里 |
|---|---|
| mod.json 字段 | `ModManifest.fromJson`(`ModManifest.java:88`) |
| 校验规则 | `ModScanner.scanChildren`(`ModScanner.java:147`) |
| register_* 新增/改签名 | `LuaEngine.initInternal`(`LuaEngine.java:105-156`)+ 各 `RegisterXxxFunction` |
| RPD.* 新增/改签名 | `RpdApi.build()`(`RpdApi.java:142`)+ 各函数类 `call(...)` |
| 脚本子目录 | `LuaEngine.loadXxxScripts`(`LuaEngine.java:212` 起) |
| 沙箱剥离项 | `LuaSandbox.exposedGlobals`(`LuaSandbox.java:95`) |

理想方案是从注解/javadoc 自动生成 API 参考(留作 `processor` 模块的后续扩展);在那之前,本文档顶部「最后更新」日期是 drift 的唯一防线。

---

## 9. 完整示例:最小可玩 mod

下面是一个端到端的最小 mod:`my_first_mod`,包含清单、入口、1 个物品、1 个 mob、1 个法术。所有 lua 语法对照 `core/src/main/assets/mods/test_mod/` 里的真实示例(非伪代码)。

### 目录结构
```
mods/my_first_mod/
├── mod.json
├── init.lua
└── scripts/
    ├── items/greeting_sword.lua
    ├── mobs/greeter_slime.lua
    └── spells/greet.lua
```

### `mod.json`
```json
{
  "id": "my_first_mod",
  "name": "我的第一个 Mod",
  "version": "0.1.0",
  "spd_version": 896,
  "author": "me",
  "default_enabled": false,
  "entry": "init.lua",
  "description": "最小可玩示例:一把会喊话的剑 + 一只友好的史莱姆 + 一个打招呼法术。"
}
```

### `init.lua`(入口,可留空或做一次性 bootstrap)
```lua
-- 入口脚本在所有 scripts/*.lua 之后跑。这里只打条日志证明它跑了。
if RPD and RPD.GLog then
    RPD.GLog("[my_first_mod] loaded.")
end
```

### `scripts/items/greeting_sword.lua`
```lua
-- 一把 tier 3 剑,每次命中敌人时在游戏内消息行问候。
register_item {
    id = "greeting_sword",
    name = "问候之剑",
    desc = "命中时向敌人打招呼。",
    image = 3,
    tier = 3,
    attackProc = function(selfId, enemyId, baseDamage)
        local name = RPD.charName(enemyId)
        RPD.GLog("你好, " .. tostring(name) .. "!")
        return baseDamage
    end,
}
```

### `scripts/mobs/greeter_slime.lua`
```lua
-- 一只被动型史莱姆:省略 act → 回退上游 AI;挨打时 yell。
register_mob {
    id = "greeter_slime",
    name = "友好史莱姆",
    hp = 15,
    ht = 15,
    attack = 5,
    defense = 2,
    sprite = "slime",

    defenseProc = function(selfId, enemyId, baseDamage)
        RPD.yell(selfId, "哎哟!")
        return baseDamage
    end,
}
```

> 注意:`greeter_slime` **不会**在关卡里自然出现(mob 不进刷怪池)。要让玩家遇到它,要么用 `RPD.spawnMob("greeter_slime", pos)`(可在某个 item/spell 回调里调),要么在数据驱动关卡的 mob spec 里引用。纯 demo 可以先用控制台/cheat 召唤验证。

### `scripts/spells/greet.lua`
```lua
-- 一个消耗性法术:使用时给英雄 +5 血并消耗。
register_spell {
    id = "greet_spell",
    name = "问候术",
    desc = "使用后治疗 5 点。",
    image = 0,
    onUse = function(heroId)
        RPD.healChar(heroId, 5)
        RPD.GLog("问候术!+5 HP")
    end,
}
```

### 验证步骤

1. 把 `my_first_mod/` 放进 `core/src/main/assets/mods/`(内置,开发用)或 `mods_user/`(外部)。
2. 编译运行(`./gradlew :desktop:debug`)。
3. 菜单 → 模组管理 → 勾选「我的第一个 Mod」→ 看到 `[内建]` 或 `[外部]` 角标。
4. **重启游戏**。
5. 进游戏后用控制台/cheat 把 `greeting_sword` 和 `greet_spell` 加进背包(或改 `default_enabled` + 在某个 item 回调里 `RPD.giveItem`),装备剑砍怪验证 `attackProc`;用法术验证 `onUse` + 治疗。

> 内置参考实现:`core/src/main/assets/mods/test_mod/`(M0–M11 综合测试包,含 item/mob/ally/hero/spell/npc/shop/buff/talent/painter/trap 全类型示例)和 `core/src/main/assets/mods/demo_m58/`(M5–M8 综合 demo)。**遇到不确定的写法,直接翻这两个包里的同名脚本**。

---

*文档结束。问题/PR 请走本 fork 自己的渠道,不回上游 00-Evan 仓库。*
