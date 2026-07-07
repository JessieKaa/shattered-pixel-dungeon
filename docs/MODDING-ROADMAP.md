# SPD × Remixed Lua Modding 平台 — 里程碑计划

> 上层路线图。基于 SPD fork 移植 Remixed Dungeon 的 Lua modding 基础设施,
> 在保留 SPD 深度内容的前提下叠加 Remixed 式广度。
>
> 本文档是所有后续 feature_worker 的上层指引;每个里程碑(M)拆出的具体 feature 各自写
> `docs/PLAN-modding-<m>-<slug>.md`,引用本文档作为依据。

**状态图例**:`[ ]` 待启动 / `[~]` 进行中 / `[x]` 已完成 / `[!]` 阻塞

---

## 1. 背景与目标

### 1.1 两项目定位

- **Shattered Pixel Dungeon(SPD,本仓)**:libgdx + 单 `core` 模块 + Java 硬编码。深度 roguelike,巨型天赋树(`Talent.java` 44K)、深度程序化生成、平衡调优、permadeath 美学。
- **Remixed Dungeon(NYRDS,姐妹仓 `../remixed-dungeon/`)**:双包(`com.watabou.*` 原版 + `com.nyrds.*` 新增)+ LuaJ 嵌入 VM + `@LuaInterface` 注解沙箱 + TeaVM 多平台。可扩展内容平台:10 职业、~220 物品、~120 mob、法术/mana、宠物/召唤、城镇枢纽。

### 1.2 杂糅目标

**既深又广**:保留 SPD 的深度内容(天赋/子职业/abilities/深度生成/平衡)不动,在其上铺一层 Remixed 式 Lua modding 基础设施,用数据驱动方式叠加广度内容(新职业/法术/宠物/城镇)。

### 1.3 选 SPD 为底座的理由

1. **广度=平台能力,可移植**;**深度=工程沉淀,不可移植**。Remixed 的 `luaj`/`annotation`/`processor` 是独立 Gradle 模块,可以搬到 SPD;SPD 的 10 年平衡调参搬不走。
2. SPD 单模块干净,加 modding 层是**加法**;Remixed 双包老底座做深度是**减法 + 重写**。
3. 两边 `Item`/`Hero`/`Char` 同名异实现,直接合并冲突地狱。

### 1.4 基本策略

- 保留 SPD 全部深度内容不动
- 外置铺一层 Lua modding 基础设施
- 广度内容(法术/宠物/城镇/新职业)作为**默认关闭的可选 mod 包**,保 SPD 原版体验

---

## 2. 贯穿性约束(每阶段都要守)

| ID | 约束 | 说明 |
|---|---|---|
| **C1** | 版本对齐 | modding 层尽量外置(独立 Gradle 模块 + assets 加载),对 `core` 的侵入收敛到几个单点 hook——沿用本 fork save slot 的"4 个上游单点 hook"范式(见 `CLAUDE.md`) |
| **C2** | 包结构隔离 | 所有 fork 加的 modding 代码进 `core/.../modding/` 子包,不散回上游根包/windows 子包 |
| **C3** | 回归基线 | 每阶段结束保 SPD 原版一周目可玩通 + Rankings/Badges/GamesInProgress 缓存不被污染 |
| **C4** | 上游 merge 友好 | 不搬动上游 `Hero.die()` / `WndGame` 等大方法体(参考 save slot 的递归 re-entry 设计) |
| **C5** | proguard 安全 | release 走 `minifyEnabled true`,新增反射入口(Lua 桥接、注解处理)必须配 `android/proguard-rules.pro` keep 规则(见 `CLAUDE.md` proguard 段) |

---

## 3. 关键决策点(必须在 M3 启动前定稿)

| ID | 决策 | 选项 | 建议(初版) | 影响范围 |
|---|---|---|---|---|
| **D1** | 法术系统 | (a) 引入 mana 系(Remixed 风格)<br>(b) 改造为消耗性 spell(SPD 风格) | **(b)** 先消耗性 spell;mana 作为后期可选 mod 包 | M3 全局平衡,M4 NPC 经济 |
| **D2** | 天赋树开放程度 | (a) Lua 职业自定义天赋树<br>(b) 复用现有 4 职业天赋<br>(c) 给简化天赋 | **(b)/(c)**,不开放天赋树编辑 | M3 工作量量级(可能翻倍) |
| **D3** | 宠物骨架 | 基于 SPD Ally / 友好 mob 做 `PetInventoryManager` 等价物 | 沿用 | M3,M4 |
| **D4** | 原版内容是否重构成 mod | (a) SPD 原版 Java 内容重构成"内置 mod"<br>(b) 保留硬编码 | **(b)** 保留硬编码,只让新增内容走 Lua | M1/M2 注册管线设计 |
| **D5** ✅ | Remished 玩法移植范围 **已定:(a) B 全量 + D5'-(a) 禁 luajava**(2026-07-06) | (a) B 全量(mob+buff+item+spell 全移植,~1-2 人月)<br>(b) B-mini 后止(只验证 5-6 mob,不铺量)<br>(c) 转 C 路径(纯数据皮) | 选 (a):M6a PoC 证明 id-based 翻译可行(单 mob 4/5 surface),M6-fast 验证 C 仅武器换皮;luajava 禁守 M1 沙箱(D5' 同步定 (a)) | M6b/c/d/e 全量推进,M6c∥M6d 并行 |

> **D2 是工作量杠杆最大的决策**。SPD `Talent.java`(44K)是硬编码 enum + 互锁逻辑,数据驱动化是独立大工程,建议第一版不碰。若选 (a),M3 拆成 M3a/M3b。

---

## 4. 里程碑

### M0 — 最小 Lua 闭环(可行性 PoC)

- **状态**: `[x]` 已完成(commit a304d2e71)
- **目标**: 证明 luaj 能嵌进 SPD,能 new 出一个 Lua 定义的物品
- **范围**:
  - 引入 `luaj` 作为独立 Gradle 模块(与 `SPD-classes` 同级)
  - 最小 `LuaEngine`:从 assets 读 `.lua`,用 luaj 执行
  - 新建 `core/.../modding/` 子包(C2)
  - **不做沙箱、不做注解处理器**——只为打通链路
- **验收**: 游戏里能刷出一把 Lua 定义的"测试剑",有名字/贴图/攻击力;原版一周目不受影响(C3)
- **依赖**: 无
- **风险**: luaj 与 libgdx 1.14.0 / Java 11 / R8 minify 的兼容性(C5)
- **难度**: 中
- **预估 feature 数**: 2-3

### M1 — 沙箱 + 内容注册管线

- **状态**: `[x]` 已完成(commit 84f0477f7)
- **目标**: 把"Lua 定义 → 注册进 SPD 工厂"做扎实,加边界
- **范围**:
  - 移植 `annotation` + `processor` 模块
  - 设计 `@LuaInterface` 标注 SPD 暴露的类(`Item`/`Hero`/`Mob`/`Char`/`Buff`/`Level`)
  - 编译期生成可达闭包白名单(`LuaInterfaceProcessor`)
  - `LuaSandbox` 运行期校验
  - `Generator` / mob 工厂改造成"Java 注册 + Lua 注册"双源
- **验收**: mod 文件夹(`version.json` + `scripts/`)能加新物品和新怪;Lua 访问未暴露 Java 内部被拦截并报错
- **依赖**: M0
- **风险**: SPD 类层次比原版 PD 重,白名单闭包计算膨胀;proguard keep 规则要跟上(C5)
- **难度**: 中高
- **预估 feature 数**: 3-4

### M2 — 核心 API 暴露

- **状态**: `[x]` 已完成(commit a25105fa2;**仅 Item API,关卡 API Painter/Room/Trap 移至 M4**)
- **目标**: Lua 能写**完整行为**的内容(不止数值 stub)
- **范围**:
  - `scripts/lib/*.lua` 等价物:暴露 `actionsProc` / `Buff.onAttach` / `Char` 属性 / `Hero` 背包接口
  - `entities/*.txt` 风格名册(Lua 内容注册表)
  - 关卡侧:暴露 `Painter`/`Room`/`Trap` 给 Lua
- **验收**: Lua 实现一把有攻击 proc + 装备效果 + 完整描述的武器;一个有简单 AI 的怪;一个自定义陷阱房
- **依赖**: M1
- **风险**: API 定型后再改成本高——**这是项目工作量主峰**,设计要好
- **难度**: 高
- **预估 feature 数**: 4-6

### M3 — 深度系统边界 + 机制骨架(关键分叉) ⭐ 决策门

- **状态**: `[x]` 已完成(D1-D4 定稿:D1 消耗性 spell / D2 复用 4 职业天赋 / D3 ally 宠物 / D4 保留硬编码。4 feature 合 master:m3a-mob 634da866d / m3b-pet 83f946057 / m3c-hero 015eb97ea / m3d-spell 5bb3d7ce9)
- **目标**: 新职业能玩通一周目;法术/宠物骨架定型
- **范围**:
  - 按 D1 实现法术(mana 或消耗性 spell)
  - 按 D2 处理新职业天赋
  - 按 D3 实现 `PetInventoryManager` 等价物
  - Lua 定义的完整新职业(子职业可暂略)
- **验收**: 一个 Lua 定义的完整新职业能从 1 层玩到通关;Rankings 正常提交(C3)
- **依赖**: M2 + D1-D4 决策
- **风险**: 最大风险集中点。若 D2 选 (a),拆成 M3a/M3b
- **难度**: 极高
- **预估 feature 数**: 5-8

### M4 — 关卡 / 城镇 / 广度内容

- **状态**: `[x]` 已完成(M4a Level `ac5fa76f6` / M4b NPC `68ff41f25` / M4c shop `873753f92` / M4d 主线可达+R4状态保留 `5db7b23ce`,4 feature 合 master `5db7b23ce`,185 tests 全绿)。**M4e SafeZone 稳定化(热修复,M5 后做)** `23e6c6f56`:进入 SafeZone 闪退链修复(`Actor.clear` before switchLevel + `test_safezone.json` 16×16→32×32 padding),218 tests 全绿。M4d:isDebug 守卫的主线 NPC 注入(`RegularLevel.createMobs` +1 hook)+ Option C 同步切 leaveLevel(保留 live hero/gold/quickslot/nextID,死亡分支仍走 CONTINUE)。M5 在此基础上做玩家开关 + mod 治理
- **目标**: 加广度容量(基础设施已铺好,本阶段主要是消费 API)
- **范围**:
  - `levelsDesc/*.json` 图结构(线性 + 枢纽 + 副本)
  - Tiled `.tmx` 加载(引入 Tiled map 解析库)
  - 城镇枢纽房 + NPC + 多店经济
- **验收**: 游戏里有可达城镇房,与 NPC 交互能买卖/储物
- **依赖**: M3
- **风险**: Tiled 库依赖体积;关卡图改动与上游 merge 的冲突面(C1/C4)
- **难度**: 中
- **预估 feature 数**: 4-5

### M5 — mod 治理 + 平衡收尾

- **状态**: `[x]` 已完成(M5a 清单+版本门 `51db427ba` / M5b UI+entry `05e700e04` / M5c 目录化+默认关闭+C3回归 `13b0d56a8`,3 feature 合 master `13b0d56a8`,216 tests 全绿)。test_mod `default_enabled=false` → 零 Lua 内容(C3 回归守住);`WndModManager` 开关(isDebug 守卫)+ LuaEngine entry 加载 + 脚本目录化(`mods/<id>/scripts/<type>/`)按 enabled mod 扫描
- **目标**: 体系对玩家可控、对平衡可守
- **范围**:
  - mod 目录扫描 + 版本兼容检查(`version.json` 的 `rpd_version` 等价物)
  - mod 切换 UI
  - 所有 Remixed 式内容作为默认关闭的可选包
  - 平衡回归:开/关扩展包各跑一轮徽章/挑战基线
- **验收**: 玩家在设置里可开关扩展内容包;原版体验不变
- **依赖**: M4
- **风险**: 平衡调参周期长
- **难度**: 中
- **预估 feature 数**: 3-4

### M6 — Remished 玩法移植(Lua API 面扩容 + 脚本改写)

- **状态**: `[x]` 已完成(2026-07-06 M6a/M6-fast/M6b/M6c/M6d/M6e 全合并 master `6b07c32e5`+;B 路径可行性已论证 + 样本解剖见 §9 评估附录;**D5 = (a) B 全量 + D5'-(a) 禁 luajava 全程守住**)
- **目标**: 把 Remished 的 Lua 定义玩法(mob/buff/item/spell)移植到我们的 Lua wrapper 平台,**验证"嵌 Remished 玩法"真实可行 + 量化边际成本**(D5 决策依据)
- **决策门 D5**(M6b PoC 后定):B 路径是否全量铺开
- **范围**:
  - **M6a 桥子集**(平台扩容):LuaMob 补 `act`/`spawn` 回调槽 + LuaSandbox 建 Blob/Buff 常量表层(`RPD.Blobs.*`/`RPD.Buffs.*` 等价)+ 暴露核心 API(`placeBlob`/`addImmunity`/`getPos`/`glog` 等 10-15 个)。**平台级设计工作,非逐脚本改**
  - **M6b mob 改写 PoC**:`RemishedFetidRat`/`ShamanElder`/`SpiderElite`/`Hydra`/`ScriptedThief` 5-6 个 → 走 LuaMob 回调改写,**实测单 mob 改写工时 + 倒逼的平台 API 数** → 触发 D5
  - **M6c buff 移植**(D5 通过后):补 buff 生命周期 API(`onAttach`/`detach`/`act`)+ 移植 16 个 Remished buff 脚本
  - **M6d item/spell 扩容**(D5 通过后):补 Item/Spell/Hero 背包 API 面 + 移植 Remished Lua item/spell
  - **M6e 平衡 + C3 回归**:所有移植内容 `default_enabled=false`,开/关各跑一轮徽章/挑战基线
  - **可选并行 M6-fast**(C 路径):数据驱动 item/mob 皮套现有 wrapper,快速铺量,不阻塞主线
- **验收**: 5-6 个 Remished mob 在 SPD 内生成 + 行为正确(放毒气/加 buff/特殊 AI);默认关闭,原版一周目不受影响(C3);M6b 产出实测成本数据驱动 D5
- **依赖**: M5
- **风险**:
  - 桥设计是平台级工作(常量表层),非逐脚本改 —— M6a 主工作量
  - **D5 决策门**:M6b 可能暴露桥根本性不够(per-tick `act` 性能 / 常量表膨胀 / 序列化兼容)→ 回评估降级到 C 路径
  - assets 版权(Remished 贴图/音效 license 需单独确认,非 MIT/类 MIT 部分要替换或重绘)
  - C5 proguard:每个新暴露 Java API 都要 keep 规则
- **难度**: 中高(平台扩容 + 批量改写)
- **预估 feature 数**: 4-5(主线 M6a/b/c/d/e)+ 1-2(可选 M6-fast)

### M7 — 核心机制层 + M6 降级补救(2026-07-07 立项)

- **状态**: `[x]` 已完成(2026-07-07 立项 → 2026-07-08 5 feature 全合 master `5d1f0fd37`,331 tests 绿;buff 16/16,mana 双轨,天赋数值覆写,D6=(a)/D7=双轨/D5' 禁 luajava 全守住)
- **目标**: (1) 补完 M6 降级项(combat hook / stolen loot / targeting);(2) D7 引入 mana 双轨(M3d 预留 spellCost);(3) D6 (a) 天赋数值覆写(不动 enum)
- **决策门**(2026-07-07 定):
  - **D6 = (a) 天赋数值覆写**:(b) 加新天赋 / (c) Lua 职业天赋树风险过高(581 调用点 + save 兼容),仅做 (a) modding 子包隔离
  - **D7 = 双轨 mana**:`useMode` 字段,新 spell 走 mana / 旧保留消耗,**不单轨反转 D1**(破坏消耗经济 + save-scum 叠加);Remished 实为 SP 非 mana,双轨保留消耗路径
- **范围**(5 feature + 永久降级):
  - **M7a combat-hook-core**(~1.5 天):LuaBuff 加 4 数值回调槽(attackProc/defenseProc/drRoll/speed)+ Char/Mob proc 点单点 dispatch + stolen loot 持久化 + Charm/Terror whitelist → 解锁 8/12 降级 buff
  - **M7b combat-hook-rest**(~2 天):剩余 hook(stealth/regen/haste/glow/source-shield)→ buff 16/16(依赖 M7a)
  - **M7c spell-targeting**(~2-3 天):LuaSpell selectCell targeting + `onUseAt(heroId,cellId)`,复用 Wand 模板
  - **M7d mana-dual-track**(~4.5 天):Hero MP/MPMax + Bundle + StatusPane MP 条 + LuaSpell `useMode` + RpdApi mana API + regen(M7c 合后做,都碰 LuaSpell)
  - **M7e talent-override**(5-7 天):`LuaTalentOverride` 注册表 + `Talent.maxPoints/desc` fallback + `pointsInTalent` 乘数 + RpdApi talent API(不动 enum/Bundle)
  - **永久降级**:summon/raise_dead 专属 sprite(资产问题,非代码,M8+ 或美术补)
- **验收**: buff 完成度 4→16/16;spell targeting 0→真实 UI;mana 双轨可玩不破消耗经济;天赋数值可 Lua 覆写;C3 全程守住
- **依赖**: M6(已完成)
- **风险**:
  - combat hook dispatch 顺序(多 LuaBuff 叠加)
  - mana 平衡(save-scum 叠加放大,需 cooldown/regen 调参)
  - 天赋数值覆写 save 兼容(只覆写运行期 maxPoints/desc,不碰 Bundle)
  - D6 (b)/(c) 不做,真开放天赋留 M8+(save-schema 设计独立决策)
- **难度**: 中高(combat hook + mana UI + 天赋 hook,各自独立但都碰核心类)
- **预估 feature 数**: 5(M7a/b/c/d/e)+ 永久降级 sprite
- **评估依据**: §10 M7 立项评估(三线工作量/风险/MVP)

#### 超出 M7 范围(留 M8+)

- **A 路径**(整套搬 Remished modding 层 + 713 标注铺到 SPD 原版类)—— 违背 C1/C4(侵入面爆炸),**不做**
- **D6 (b)/(c)**(加新天赋 / Lua 职业天赋树)—— 581 调用点 + save-schema 重设计,M8+ 独立工程
- **D7 单轨反转**—— 破坏消耗经济,M7 只做双轨
- **summon/raise_dead 专属 sprite**—— 资产授权/制作,M8+

---

## 5. 里程碑依赖图

```
M0 → M1 → M2 → [D1-D4 决策门] → M3 → M4 → M5 → [D5 决策门] → M6
                                ⭐                       ⭐(M6b PoC 后)
```

- M3 是关键门,**D1-D4 必须先答**。
- M3 若 D2 选 (a),拆 M3a(机制骨架)/ M3b(天赋数据驱动)。
- M6 是 Remished 玩法移植门,**D5 在 M6b PoC 后答**(实测边际成本驱动)。

---

## 6. 状态总表

| 里程碑 | 状态 | 依赖 | 难度 | 预估 feature 数 | 备注 |
|---|---|---|---|---|---|
| M0 最小 Lua 闭环 | `[x]` | — | 中 | 1(m0-lua-poc) | luaj 嵌入 + 测试剑 |
| M1 沙箱 + 注册管线 | `[x]` | M0 | 中高 | 1(m1-sandbox) | @LuaInterface + processor + Generator 双源 |
| M2 核心 API 暴露 | `[x]` | M1 | 高 | 1(m2-item-api) | 仅 Item API;关卡 API(Painter/Room)移到 M4 |
| M3 深度系统 + 机制骨架 | `[x]` | M2 + D1-D4 | 极高 | 4(m3a-mob/m3b-pet/m3c-hero/m3d-spell) | D1 消耗性/D2 复用天赋/D3 ally/D4 保留硬编码 |
| M4 关卡 / 城镇 / 广度 | `[x]` | M3 | 中 | 5(M4a Level/M4b NPC/M4c shop/M4d 主线可达/M4e SafeZone 稳定化) | 核心:数据驱动 Level + Lua NPC + 商店 + 主线注入(isDebug 守卫)+ R4 leaveLevel 状态保留 + SafeZone 进入崩溃修复 |
| M5 mod 治理 + 平衡 | `[x]` | M4 | 中 | 3(M5a 清单+版本门/M5b UI+entry/M5c 目录化+默认关闭) | 清单+版本门 / 开关UI+entry加载 / 脚本目录化+default_enabled=false → C3 守住 |
| M6 Remished 玩法移植 | `[x]` | M5 + D5 | 中高 | 6(M6a 桥/M6b mob PoC/M6c buff/M6d item+spell/M6e 平衡收尾 + M6-fast C 数据皮) | B 路径:扩 Lua API 面 + 改写 Remished 脚本;**D5=(a) B 全量 + D5'-(a) 禁 luajava 守住**;mob 7/buff 16(4 高保真+12 降级)/item 5/spell 8;核心机制层(职业/天赋/mana)出 M6 范围;C3 全程守住 |
| M7 核心机制层 + M6 降级补救 | `[x]` | M6 | 中高 | 5(M7a/M7b/M7c/M7d/M7e 全合 `5d1f0fd37`) | D6=(a) 天赋数值覆写 / D7=双轨 mana / M6 降级补救全做;buff 4→16/16(anesthesia sleep-lock 留 M8);mana 双轨不破消耗经济;天赋数值只下调;C3 全程守住;331 tests |
| **合计(M0-M7 全部完成)** | | | | **26 feature** | M0-M6(21) + M7(5: a/b/c/d/e) |

---

## 7. feature 拆分原则

- 每个 M 拆成 N 个独立 feature,各自写 `docs/PLAN-modding-<m>-<slug>.md`
- slug 命名建议:`modding-m0-luaj-embed` / `modding-m1-sandbox` / `modding-m2-item-api` / `modding-m3-spell-system` ...
- 每个 feature 走标准 Feature Dispatcher 流程(worktree + PLAN + feature_worker + codex_reviewer)
- 每个 feature 必须保 C3 回归基线
- 合并后 PLAN 文档跟随代码进 main,作为审计记录

---

## 8. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-07-05 | 初版路线图落盘 | dispatcher |
| 2026-07-05 | M0-M3 完成(7 feature 合 master);M2 关卡 API(Painter/Room/Trap)移至 M4;M3 按 D1-D4 拆 m3a-mob/m3b-pet/m3c-hero/m3d-spell;M4 启动(m4a-data-driven-level 进行中) | dispatcher |
| 2026-07-05 | M4 核心三件套完成(M4a Level `ac5fa76f6`/M4b NPC `68ff41f25`/M4c shop `873753f92`,3 feature 合 master `873753f92`,174 tests 全绿);**M4d 主线可达推迟到 M5**(玩家开关+接入一起做);M4 累计 10 feature(M0-M3 7 + M4 3) | dispatcher |
| 2026-07-06 | M4 全部完成(M4d 主线可达+R4状态保留 `5db7b23ce` 合 master,185 tests 全绿);M0-M4 累计 11 feature;启动 M5 mod 治理 + 平衡收尾 | dispatcher |
| 2026-07-06 | M5 全部完成(M5a `51db427ba`/M5b `05e700e04`/M5c `13b0d56a8` 合 master,216 tests 全绿);**M0-M5 全部完成,累计 14 feature**;SPD×Remixed Lua modding 平台闭环(清单+版本门+开关UI+entry+目录化+默认关闭+C3 回归) | dispatcher |
| 2026-07-06 | **M4e SafeZone 稳定化**(热修复,`23e6c6f56` 合 master,218 tests 全绿):进入 SafeZone 闪退链修复 — Bug 0 `Actor.clear` before switchLevel(主线 mob 泄漏到小地图 overflow)+ X1 `test_safezone.json` 16×16→32×32 padding(SPD observe/tilemap 假定 ≥32×32 + 边缘 padding);M0-M5 累计 **15 feature**;fork master 已 push 到 JessieKaa origin | dispatcher |
| 2026-07-06 | **M6 路线图立项**:Remished 玩法移植评估完成 —— 平台 API 面缺口量化(Remished 713 `@LuaInterface` 标注 / ~40 类 vs 我们 6 个 Lua wrapper,~10x);样本解剖(`RemishedFetidRat` 46 行)显示 B 路径真实成本在平台 API 暴露(80-120 API × 0.5 天 ≈ 2-3 人月 = M2-bis 级),Lua 脚本改写本身轻量(1-2 小时/mob);立项 M6(B 路径)+ D5 决策门(M6b PoC 后定);A 路径/核心机制层(职业/天赋/mana)出范围;评估依据见 §9 | dispatcher |
| 2026-07-06 | **M6a + M6-fast 完成合并 + D5 定案**:M6a 桥子集(merge `fb50ddc31`,5 surface:`placeBlob`/`addImmunity`/Blob-Buff 常量表/LuaMob `spawn` 回调,守 luajava 禁;229 tests)+ M6-fast C 数据皮(3 Remished 武器 reskin,验证 C 仅适合武器换皮,非武器类型需新 wrapper=B 量级);**D5 = (a) B 全量 + D5'-(a) 禁 luajava**(用户据 M6a PoC id-based 翻译可行 + M6-fast C 作武器补充定);M6b(扩桥 3-5 id primitive + 改写 5-6 mob)启动,M6c∥M6d 待 M6b 后并行 | dispatcher |
| 2026-07-06 | **M6b mob PoC 完成合并**(merge `4e3130104`,6 Remished mob:`shaman_elder`/`spider_elite`/`deep_snail`/`hydra`/`maze_shadow`/`buffer` + 5 primitive:`setMobAi`/`enemyOf`/`cellDistance`/`emptyCellNextTo`/`blink` + `Ooze` buff;默认不进生成池,C3 守住;PoC 证明 B 路径 mob 改写可行,单 mob 倒逼 primitive 边际递减) | dispatcher |
| 2026-07-06 | **M6c buff 移植完成合并**(merge `bfbeb8a79`):`LuaBuff`/`LuaBuffRegistry`/`register_buff` + 16 Remished buff 脚本 + `affectBuff`(透传 Lua id)/`removeBuff`/`detachBuff`/`permanentBuff`/`setBuffLevel`/`buffLevel`;**4/16 高保真**(`GasesImmunity`/`Counter`/`Cloak`/`ChampionOfEarth`),**12/16 降级**(combat 强度 hook 未接 SPD `Char`/`Mob` 核心,留 M7 source-aware hook);每实例 state + Bundle round-trip | dispatcher |
| 2026-07-06 | **M6d item/spell 扩容完成合并**(merge `7123d75b5`):`LuaMaterial` wrapper + 2 weapon 代表(`hooked_dagger`/`kunai`)+ 8 spell(heal/haste/charm/lightning_bolt/town_portal/summon_beast/raise_dead/sprout)+ 11 item/spell API(`giveItem`/`randomBackpackItem`/`itemName`/`removeBackpackItem`/`stealRandomItem`/`stolenLootName`/`teleportChar`/`charAtCell`/`cellRay`/`zapEffect`/`spawnMobNear`);item 5/22,spell 8/32;不引入 `scripts/lib/`,不开 luajava | dispatcher |
| 2026-07-06 | **M6e 平衡收尾 + C3 全量回归 + M6 闭合**(本 feature):2 项轻修(`giveItem` per-hero-per-depth 配额 #1 / `LuaItemPool.random()` 默认 weapons-only #5)+ 4 项接受降级留 M7(stolen loot 持久化 / summon sprite / targeting UI / M6c combat hook 12/16);C3 全量绿(8 registry disabled=0/enabled=full + `mod.json` `default_enabled=false`);273 tests 绿(271 baseline + 2 新);**M6 全部完成,累计 21 feature**;完成度与摩擦点见 §9.6 | feature_worker |
| 2026-07-07 | **M7 立项**:三线评估(D6 天赋 / D7 mana / M6 降级补救)— D6=(a) 数值覆写(Remished 无 Talent 可抄,(b)/(c) 581 调用点风险过高);D7=双轨 mana(Remished 实为 SP 非 mana,M3d 已预留 spellCost,单轨反转破坏消耗经济);M6 降级补救(combat hook 实为 Char/Mob 单点 dispatch 非 source-aware 全局 hook,~6 天)。用户定"全做"~17 天,5 feature(M7a-e);fork master 已 push origin(16 commits,M6 全量);评估依据 §10 | dispatcher |
| 2026-07-08 | **M7 全部完成**:5 feature 合 master(`5d1f0fd37`,331 tests 绿):M7a combat-hook-core(LuaBuff 4 数值回调槽+Char dispatch+stolen loot+Charm/Terror,buff 11/16)+ M7b combat-hook-rest(defenseSkill/attackSkill/charAct+belongings/yell API,buff 16/16)+ M7c targeting(LuaSpell selectCell+onUseAt)+ M7d mana 双轨(Hero MP+ManaRegen+useMode+RpdApi+StatusPane)+ M7e talent-override(LuaTalentOverride desc+maxPoints 只下调,D6=(a));D6=(a)/D7=双轨/D5' 禁 luajava 全守住;anesthesia sleep-lock + shields lib + sprite tint 留 M8;完成度见 §10.5 | dispatcher |

---

## 9. 附录:M6 评估依据(2026-07-06)

> Remished 玩法移植可行性评估的数据沉淀。M6a PLAN 在此基础上展开桥子集设计。

### 9.1 平台 API 面缺口量化

| 维度 | Remished | 我们(SPD fork) | 差距 |
|---|---|---|---|
| `@LuaInterface` 标注 | 713 处 / ~40 类 | 6 个 Lua wrapper | ~10x |
| Lua 脚本 | 162(scripts/)+ 20 lib/*.lua | test_mod 几个示例 | 全缺 |
| 暴露 Java 包 | items/ levels/objects/ mechanics/{buffs,spells} mobs/npc/ + 平台层 | 仅 `modding/Lua*.java` | 全缺 |
| Lua 实现 mob / buff | 18 mob(scripts/mobs/)/ 16 buff(scripts/buffs/) | 0(LuaMob 框架空载) | 全缺 |

> 注:Remished `~120 mob / ~220 item` 大头是 **Java 硬编码**(`entities/mobs.txt` 名册),Lua 只覆盖 18 mob + 16 buff + 部分 item/spell。真正"可移植的 Remished Lua 玩法"是这几十个脚本,不是整个 220/120。

### 9.2 架构模式差异(根因)

- **Remished** = `@LuaInterface` 标在**原版 + 扩展 Java 类**,Lua 通过 `scripts/lib/commonClasses.lua`(576 行 RPD 桥)**主动调 Java API**。工厂注册(ItemFactory/SpellFactory/BuffFactory)让 Lua 实体进 SPD 工厂链
- **我们** = `@LuaInterface` 只标在 **6 个 Lua wrapper**,Lua table 仅作**行为回调注入**(proc/damageRoll/activate...)。Lua **不能主动调 SPD Java API**,只能在 wrapper 预设回调槽返回数值

### 9.3 样本解剖:RemishedFetidRat(46 行)

| Remished 用到的 API | 我们 LuaMob | 改写处理 |
|---|---|---|
| `attackProc`/`defenseProc`/`die` | ✅ 有 | 直接映射 |
| `storeData/restoreData`(serpent) | ✅ storeInBundle/restoreInBundle | Bundle 代 serpent |
| `stats` 回调(选 kind + 加免疫) | ❌ 无初始化槽 | 扩 LuaMob 加 `spawn` |
| `act` 回调(每 tick `placeBlob`) | ❌ **关键缺口** | LuaMob 没暴露 `act()`,要扩 |
| `self:addImmunity(Buff)` / `getPos()` | ❌ 未暴露 | LuaMob 加方法 |
| `RPD.placeBlob(blob,pos,amt)` | ❌ 完全无 | 桥层新增 |
| `RPD.Blobs.*` / `RPD.Buffs.*` 常量 | ❌ 完全无 | 建常量表(平台级) |
| `RPD.glog` / `RPD.Sfx.Speck.*` | ❌ 无 | 日志可代,Speck 可丢 |

**单 mob 倒逼 6-8 个平台 API**。

### 9.4 三路径成本

| 路径 | 做法 | 工作量 | 决策 |
|---|---|---|---|
| **A** | 整套搬 Remished modding 层 + 713 标注铺 SPD 原版类 | M7 级+(≈ 重做 M1+M2 且更难) | **不做**(违 C1/C4) |
| **B** | 改写 Remished 脚本到我们 wrapper 回调风格 | 80-120 API × 0.5 天 ≈ **2-3 人月**(M2-bis 级) | **M6 采纳,B-mini 先行** |
| **C** | 只搬数据(数值/贴图),行为套现有 wrapper | 几 feature | **M6-fast 可选并行** |

### 9.5 B-mini 量化(单 mob 边际成本)

- Lua 侧改写:**1-2 小时/mob**
- 倒逼平台 API:6-8 个 × 0.5 天(边际递减:前几个建常量表框架,后续 mob 复用)
- 18 mob + 16 buff 全量 B ≈ **40-60 工作日**

### 9.6 M6 全量完成度(2026-07-06 闭合回填)

M6 全程守住 **C3**(test_mod `default_enabled=false`;8 registry disabled=0/enabled=full;原版一周目零污染)+ **D5' (a) 禁 luajava**(无任何 `scripts/lib/` / luajava 引入)。累计 **6 feature**(M6a/M6-fast/M6b/M6c/M6d/M6e)。

| 维度 | 完成度 | 说明 |
|---|---|---|
| Lua-facing surface(API + 回调槽) | ~45 | M6a 5 surface + M6b 5 primitive + M6c 6 buff API + M6d 11 item/spell API + LuaMaterial/LuaBuff wrapper + spawn/act 回调槽 |
| mob | 7 / ~18(test_mob + M6a PoC + 6 M6b PoC) | 默认不进生成池;PoC 证明 B 路径可行 |
| buff | 16(4 高保真 + 12 降级)/ 16 | 高保真:`GasesImmunity`/`Counter`/`Cloak`/`ChampionOfEarth`;12 降级 = combat 强度 hook 未接(留 M7) |
| item | 5 / ~22(3 材料 LuaMaterial + 2 weapon 代表) | shield/armor/fish/pickaxe 需新 wrapper 或美术(M7) |
| spell | 8 / ~32 | 覆盖 heal/buff/projectile/teleport/summon 五类;深度依赖 `scripts/lib/` + skillLevel + targeting UI(M7) |

**降级接受项(留 M7)**:
- M6c combat hook 12/16(`defenceProc`/`attackProc`/`drBonus`/`speedMultiplier`/`stealthBonus`/`charAct`/`regenerationBonus`/`hasteLevel`/`sprite glow`/`source-item shield`)→ 需 source-aware `Char`/`Mob` 全局 hook,M7 级
- M6d `stolen loot` 持久化(`Mob.storeInBundle` 不存 generic Item loot;LuaMob 已 override bundle,M7 廉价补 + thief 返回 UI)
- M6d summon/raise_dead 专属 sprite(skeleton/zombie 资产缺口,复用 test_mob 占位)
- M6d spell targeting UI(cast 全 self 占位,真实目标选择需新 UI)
- `Charm`/`Terror` buff 未进 Java whitelist(source id 语义,M7)

**M6e 处置的 2 项平衡风险(修)**:
- `giveItem` 刷分 → per-hero-per-depth 配额(`GIVE_ITEM_CAP_PER_DEPTH = 20`),fork-local,不触 Hero/bundle
- `LuaItemPool` 武器/材料混掉 → `random()` 默认 weapons-only,`randomMaterial()` 独立入口

**luajava 禁下摩擦点总结**:Remished 原件(item/spell/mob/buff)全部 `require "scripts/lib/*"`,M6 全部改写为窄 `RPD.*` id/index/cell API;Lua 永远不持有 Java 对象句柄(所有返回 bool/int/string/nil/table)。摩擦点:targeting/projectile VFX、source-aware buff、sprite 资产 —— 均为 UI/资产工作,非 luajava 解锁可解,故留 M7。

**M6 实测边际成本**(回填 §9.5 预测):单 mob 改写 0.25-0.35 天(基础设施摊销后);单 buff 0.25-0.35 天(多为 metadata/lifecycle 降级);B 路径平台 API 暴露是主成本,Lua 脚本改写轻量 —— 与立项预测一致。

---

## 10. 附录:M7 立项评估依据(2026-07-07)

> M7 三线(D6 天赋 / D7 mana / M6 降级补救)评估数据沉淀。M7a/b/c/d/e PLAN 在此基础上展开。

### 10.1 D6 天赋数据驱动化评估

- **Talent.java 结构**(1219 行):~120 enum 项(6 HeroClass × 4 tier + HEROIC_ENERGY + 彩蛋);"互锁"实为 `onTalentUpgraded` 升级回调(~15 if 分支,L497-581)非前置/排斥图;**581 处** `hasTalent/pointsInTalent` 调用散落全仓(Char/Hero/Mob/buffs/abilities)
- **Remished 无 Talent.java 可抄**(NYRDS 走 `@LuaInterface` 反射,本 fork 禁用)
- **三档 MVP**:(a) 数值覆写 5-7 天 中风险(不动 enum);(b) 加新天赋 12-18 天 高(581 漏改);(c) Lua 职业天赋树 25-40 天 极高(save 全链路 + UI 重写)
- **决策:D6=(a)** — `modding/LuaTalentOverride.java` 注册表,`Talent.maxPoints()/desc()` fallback,`Hero.pointsInTalent` 乘数;零 enum/Bundle 改动;(b)/(c) 留 M8+
- 关键 file:line:`Talent.java:96-201`(enum)/ `:436`(tier 阈值)/ `:497-581`(onTalentUpgraded)/ `:959-1140`(initClassTalents)/ `:1142-1217`(Bundle `Talent.valueOf`);`Hero.java:476-480`(pointsInTalent 乘数门)

### 10.2 D7 mana 系统评估

- **关键反转:Remished 没有真 mana**,是 `skillPoints(SP)` 旧 RPG 系统(`Hero.getSkillPoints/spendSkillPoints`,战斗驱动 regen);`WndSpellInfo` 的 "Mana Cost" 只是 UI label
- **M3d LuaSpell 已预留 `spellCost` 字段**(L84,只 store 不 query,注释"future UI")— D7 反转的既定预留点
- **SPD 无现成 mana 槽**(Char 只有 HP;资源都是 per-item:artifact charge / wand charge)→ 必须新加 Hero MP 字段 + Bundle + StatusPane UI
- **工作量**:单轨 ~3 天 / 双轨 ~4.5 天(Hero/UI/Spell/RpdApi/regen)
- **决策:D7=双轨** — `useMode="consumable"|"mana"` 字段,新 spell 走 mana / 旧保留消耗,0 重构 M6d;单轨反转破坏消耗经济 + 与 save-scum 叠加放大,**不做**
- 关键 file:line:`LuaSpell.java:43,56,80-86,100-115`(改造点);`Char.java:167-190,332`(字段插入参考 HP);`Hero.java`(MP/MPMax + Bundle + regen);`ui/StatusPane.java:60,136-138,207-252`(MP 条)

### 10.3 M6 降级补救评估

- **combat hook 接入**:Char/Mob 的 `attackProc/defenseProc/drRoll/speed` 都是 public,末尾 `for LuaBuff dispatch` 是**单点 hook**(符合 C1/C4),非"source-aware 全局 hook"(只需传 `target.id()`/`enemy.id()`,LuaBuff 已是 id-only 沙箱)
- **5 项工作量与处置**:

| # | 项 | 人天 | M7 处置 | 关键 file:line |
|---|---|---|---|---|
| 1 | combat hook 12/16 | 3-5 | M7a(4 数值 hook 1.5 天)+ M7b(其余 2 天) | `Char.java:706,721,728,775`;`Mob.java:708`;`LuaBuff.java:137-218` |
| 2 | stolen loot 持久化 | 0.5-1 | M7a(LuaMob bundle 加 STOLEN_LOOT key) | `LuaMob.java:330-337,342-349`;`Mob.java:989-1011` |
| 3 | summon/raise_dead sprite | 2-4 | **永久降级**(资产缺口) | `LuaMob.java:139-153`(SPRITES map) |
| 4 | spell targeting UI | 2-3 | M7c(Wand selectCell 模板) | `LuaSpell.java:99-115`;`Wand.java:101,124` |
| 5 | Charm/Terror whitelist | 0.5 | M7a(putFlavour 2 行) | `RpdApi.java:998-1057` |

### 10.4 M7 拆分与并行计划

- **第一批并行(3 worker)**:M7a(Char/Mob/LuaBuff/RpdApi combat 段)/ M7c(LuaSpell targeting / GameScene)/ M7e(Talent/Hero/modding LuaTalentOverride)— 文件域不重叠
- **第二批**:M7b(依赖 M7a combat hook 框架)/ M7d(和 M7c 都碰 LuaSpell,M7c 合后做)
- **冲突点**:`RpdApi.build()` 注册区(约定 M7a/M7c/M7e 各自分块注释);`ModToggleRegressionTest` exact counts(取并集)

### 10.5 M7 全量完成度(2026-07-08 闭合回填)

M7 全程守住 **C3**(test_mod disabled 8 registry 全空,原版零污染)+ **D5' (a) 禁 luajava**(只传 id/int/string,无 Java 对象过边界)。累计 **5 feature**(M7a/b/c/d/e),331 tests(273 baseline + 58 新)。

| 维度 | 完成度 | 说明 |
|---|---|---|
| LuaBuff combat hook | 8 回调槽(attackProc/defenseProc/drRoll/speed/attackSkill/defenseSkill/charAct + belongings/yell id API) | M7a 4 数值 + M7b 4 剩余;Char.hit/Stone.proc/Actor.process 单点 dispatch |
| buff | 16/16(15 高保真 + 1 行为降级) | anesthesia sleep-lock 留 M8;其余全接 hook |
| spell targeting | 8/8(cell/enemy/self 三模式) | M7c selectCell + onUseAt,Wand 模板 |
| mana 双轨 | useMode(consumable\|mana) + Hero MP/MPMax + ManaRegen + RpdApi + StatusPane | D7 双轨,不破消耗经济,旧 spell 0 改动 |
| 天赋覆写 | desc + maxPoints 只下调(≤ vanilla) | D6=(a),不动 enum/Bundle/onTalentUpgraded |

**留 M8 项**:
- `anesthesia` sleep-lock(SPD 无单点 Sleep 唤醒 hook,需 Char.damage pre-hook)
- `shields lib`(独立抽象层 ~1-2d)
- sprite tint/glow(cosmetic)
- D6 (b)加新天赋/(c)Lua 职业天赋树(581 调用点 + save-schema 重设计)
- D7 单轨反转(破坏消耗经济,M7 只做双轨)
- summon/raise_dead 专属 sprite(资产问题)
