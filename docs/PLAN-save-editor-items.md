# PLAN: Save Editor — Raw JSON Item Editing

**Slug**: `save-editor-items`
**Branch**: `feature/save-editor-items` (based on `feature/local-save-slots`)
**Date**: 2026-06-30

---

## Goal

在已合并的 save-editor 上增加**最小可用的物品编辑**:3 个 raw JSON textarea(`hero.armor` / `hero.weapon` / `hero.inventory`),用户能改任意字段,round-trip 通过 SPD `SaveSlotIO` 闭环。

不做表单式 schema 编辑器,不做模板库,不做 depth heaps 编辑,**不提供表单式 item 增删 UI**(raw JSON 仍可手动增删,仅提示风险不阻止)。**只做"已有装备/背包字段的 raw JSON 编辑"**。

## Context

- 现状:`save-editor` 暴露 12 个数值字段(meta/hero/gold/challenges/seed),物品完全不可编辑
- 物品数据位置(实测 `123.zip` 真实存档):
  - `game.hero.armor` — dict(`__className=ClothArmor`,13 字段:augment / level / cursed / glyph_hardened / etc)
  - `game.hero.weapon` — dict(`__className=MagesStaff`,15 字段,**嵌套 wand 字段** 也是 dict)
  - `game.hero.inventory` — list,3 个 item:
    - `[0]` Food(基础字段)
    - `[1]` Waterskin(有 `volume` / `quickslotpos`)
    - `[2]` VelvetPouch(**嵌套 `inventory` list**,容器递归)
- 难点:
  1. **多态** — 每个 item 的 `__className` 决定 Java 类型,改了导致 SPD `Bundle` 反序列化失败(ClassNotFoundException)
  2. **嵌套** — MagesStaff 嵌套 wand,VelvetPouch 嵌套 inventory,任意层级可能再嵌
  3. **引用关系** — `quickslotpos` 指向 quickslot,`hero.weapon.quickslotpos` 改了不同步游戏内 quickslot 会崩
- **关键约束**(从主仓 `SaveSlotIO.java` 推断):
  - `__className` 必须**保留**(可改值但必须是合法 Java 类全限定名,删除则反序列化崩)
  - 必填字段(如 `id`、`pos`、`quantity`)不能删除
  - JSON 必须能通过 `Bundle.readValue` 反序列化

## Architecture

```
现有 save-editor 字段表格(数值字段)
         +
新增"物品"折叠区(默认折叠,展开后显示 3 个 textarea):
  ┌─ hero.armor ────────────────────┐
  │ {                                │
  │   "__className": "...ClothArmor",│
  │   "level": 0,                    │
  │   "cursed": false,               │
  │   ...                            │
  │ }                                │
  │ [应用] [重置] [格式化]            │
  └──────────────────────────────────┘
  ┌─ hero.weapon ────────────────────┐
  │ ...                              │
  └──────────────────────────────────┘
  ┌─ hero.inventory ──────────────────┐
  │ [                                │
  │   {"__className":"...Food", ...},│
  │   {"__className":"...Waterskin"} │
  │ ]                                │
  │ [应用] [重置] [格式化]            │
  └──────────────────────────────────┘
```

- 前端 textarea 编辑,JSON.parse 校验,合法后覆盖到 game 字段
- 后端 `/api/pack` 不变,仍接受完整 `{meta, game, depths}` body
- 不新增后端端点(`/api/parse` 已经返回完整 `game` dict,前端直接读)

## Files

### 改动(`tools/save-editor/templates/index.html`)

加一段"高级:物品 raw JSON 编辑"折叠区(默认折叠,`<details>` 标签即可,不引入 JS 框架):

```html
<details id="items-raw-edit">
  <summary>高级:物品 raw JSON 编辑(谨慎)</summary>
  <p class="warn">
    警告:删除 <code>__className</code> 或必填字段(id/pos/quantity)会导致游戏加载崩溃。
    不要随意修改 <code>quickslotpos</code> / 引用关系字段,本编辑器不会同步游戏内 quickslot。
    修改数值或嵌套字段时务必先备份原 zip。
  </p>

  <fieldset>
    <legend>hero.armor <span class="type-hint">(当前: <code id="armor-class">...</code>)</code></legend>
    <textarea id="armor-json" rows="12" cols="80"></textarea>
    <div class="actions">
      <button data-target="armor" data-action="apply">应用</button>
      <button data-target="armor" data-action="reset">重置</button>
      <button data-target="armor" data-action="format">格式化</button>
    </div>
    <div class="msg" id="armor-msg"></div>
  </fieldset>

  <!-- 同样结构 for weapon / inventory -->
</details>
```

JS 逻辑:
- **状态管理**:`ORIGINAL` 改造为两个对象,避免 reset 读到被改过的状态:
  - `initialState` — parse 后立即 `structuredClone(data)` 的 immutable 基线,用于 reset / `__className` 删除对照
  - `state` — 当前编辑生效对象,数值字段 / 物品字段都直接修改它;`/api/pack` 提交 `state`
  - 现有 12 字段的 `data-path` 机制从 `ORIGINAL` 切到 `state`(行为等价,但和物品编辑共享同一棵树)
- **加载**(parse 后):`const hero = state.game?.hero` 守卫。两种降级情形必须区分:
  - **hero 缺失/非 object**(极端损坏存档):3 个 textarea 置空、**disabled**(应用/重置/格式化按钮 disabled),类型 hint 显示 `(hero missing — items disabled)`。不抛 JS 异常,不影响 12 字段编辑和 pack
  - **hero 存在但 armor/weapon/inventory key 缺失**(常见:刚开局 hero 没装备):textarea 显示空字符串、**enabled**,允许用户从空写入新对象;类型 hint 显示 `(none — will create)`
  - 正常情况:`JSON.stringify(hero.<field>, null, 2)` 填 textarea,类型 hint 显示 `(__className||'').split('.').pop() || '?'`
- **应用**:
  1. `JSON.parse(textarea.value)` — 失败显示错误:优先从 `e.message` 抽 `position (\d+)`,按 textarea 文本计算 line/col 展示;抽不到就原样显示 `e.message`。不强求 line/col 验收点
  2. 基础类型校验:
     - armor 必须 `typeof === 'object' && !Array.isArray`(允许 null?不允许 —— null 走"删字段"分支)
     - weapon 必须 dict 或 null(允许 null = 卸下武器;dict 是常规情况)
     - inventory 必须 `Array.isArray`
     - 类型不符直接展示错误,不进入下一步
  3. **`__className` 删除检测(递归)**:
     - 实现:`collectClassNames(obj, basePath, out)` 深度遍历 dict / Array,对每个 dict 若含 `__className` 字符串,记录 `{path: basePath, value: className}`;Array 元素路径用 `basePath[i]`,dict 子 key 用 `basePath.key`
     - 比较 `initialState.game.hero.<field>` 与用户提交的新对象:`removed = originalPaths.filter(p => !newValue_hasClassNameAt(p))`
     - 若 `removed.length > 0`,弹 `confirm("你删除了以下 __className,这会导致游戏加载崩溃:\n  - " + removed.join("\n  - ") + "\n\n继续?")`;用户取消则不应用
     - **改值不 warn**(`ClothArmor` → `Armor` 是合法高级用法,前端无法校验 Java 类是否存在)
  4. 通过校验后,覆盖 `state.game.hero.armor` / `.weapon` / `.inventory`
  5. inventory 长度变化不阻止应用(详见 "Out of scope" 调整)
- **重置**:同时做两件事,避免"reset 后直接 pack 仍是错误改动"的直觉不一致:
  1. 把 `state.game.hero.<field>` 恢复为 `JSON.parse(JSON.stringify(initialState.game.hero.<field>))`(deep clone,避免引用泄露)
  2. 把 textarea 文本回填为 `JSON.stringify(initialState.game.hero.<field>, null, 2)`
  - 若 initialState 里 hero 不存在或字段不存在,直接清空 textarea + 清空 `state.game.hero.<field>`(`delete` 操作)
  - 重置后在 msg 区显示 "已重置到 parse 时原值"
- **格式化**:`JSON.stringify(JSON.parse(textarea.value), null, 2)` 重排,失败显示 `e.message`
- **下载 zip** 按钮逻辑不变,提交 `state`(数值字段 + 物品字段)
- **inventory 增删策略**:Out of scope 调整为"raw JSON 仍可手动增删 item,风险自担"。textarea 是纯文本编辑器,技术上无法阻止 length 变化,且高级用户可能确实想加 item。**但**:warning 文案 + 应用按钮提示"原 N items,现 M items" 不阻止,仅提示

### 改动(`tools/save-editor/tests/test_spd_bundle.py`)

新增 2 个用例(其余不动):
- `test_round_trip_hero_inventory_with_nested_container` — 构造一个 hero.inventory 含 VelvetPouch(嵌套 inventory)的 game.dat,pack → parse → 字段一致
- `test_round_trip_hero_weapon_with_nested_wand` — 构造一个 MagesStaff 嵌套 WandOfMagicMissile,round-trip

这两个用例不需要新代码,只是验证 spd_bundle 的现有 round-trip 在嵌套结构上仍工作。

### **不改动**

- `spd_bundle.py`(纯逻辑,已经能处理任意 dict/list)
- `app.py`(`/api/parse` 已经返回完整 game dict,`/api/pack` 已经接受完整 game dict)
- 不引入任何新依赖

## Steps

### Step 1: UI 折叠区 + 字段加载

修改 `templates/index.html`:

1. 在现有字段表格下方加 `<details>` 折叠区
2. parse 成功后,JS 把 `state.game.hero.armor/weapon/inventory` 序列化填到对应 textarea
3. 显示 `__className` 类名作为 hint
4. **字段缺失策略(区分两类,与 JS 逻辑一致)**:
   - hero 缺失/非 object:3 个 textarea 全部置空 + disabled,提示 `(hero missing — items disabled)`
   - hero 存在但 armor/weapon/inventory key 缺失:textarea 空 + **enabled**,hint 显示 `(none — will create)`,允许用户写入新 JSON 创建该字段

### Step 2: 应用 / 重置 / 格式化

- 应用:`JSON.parse` + 类型校验 + 递归 `__className` 警告 + 覆盖到 state
- 重置:同时 (a) 把 state 字段恢复为 initialState deep clone,(b) textarea 回填为 initialState 序列化;msg 显示 "已重置到 parse 时原值"
- 格式化:`JSON.stringify(JSON.parse(...), null, 2)`,失败显示错误

### Step 3: 错误展示

- JSON 语法错误:展示 `e.message`;**优先**尝试从 `position (\d+)` 正则抽位置,按 textarea 文本计算 `line X col Y` 展示在错误信息前缀;抽不到就只展示 `e.message`。**不强求** line/col 必须显示
- 类型错误(armor 不是 dict / weapon 不是 dict|null / inventory 不是 Array):明确文案 + 期望类型
- `__className` 缺失(递归检测到任一嵌套层级):`window.confirm` 列出被删除的所有路径,用户取消则不应用

### Step 4: 测试

新增 2 个 spd_bundle round-trip 用例(嵌套结构)。手动跑 `pytest -q` 验证全绿。

### Step 5: 端到端 round-trip 手测

1. `python app.py` 启动
2. `curl -F file=@/tmp/123.zip http://127.0.0.1:5001/api/parse` 拉取真实存档
3. 改 hero.inventory 第一个 item 的 quantity 从 1 → 99
4. pack → 重新 parse → quantity=99
5. Java 闭环 `SPD_ZIP_PATH=/tmp/edited.zip ./gradlew :core:test --tests '*SaveSlotIOPythonZipTest'` 通过

## Acceptance

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | 现有 12 个数值字段编辑功能不受影响(回归) | 手测 + 自动测试全绿 |
| 2 | 物品折叠区默认折叠,parse 成功后能展开看到 3 个 textarea | 手测 |
| 3 | textarea 初始内容是 `JSON.stringify(field, null, 2)`,类型 hint 正确 | 手测(用 123.zip 验证 hint 显示 ClothArmor/MagesStaff/[Food, Waterskin, VelvetPouch]) |
| 4 | 编辑后点"应用",合法 JSON 覆盖到 state,下载 zip 包含改动 | round-trip 测试 |
| 5 | 非法 JSON(语法错误)显示 `e.message`,若能抽到 position 则展示 line/col | 手测:故意删一个 `{` 看错误 |
| 6 | 类型错误(`hero.armor` 改成 list)显示明确错误,不应用 | 手测 |
| 7 | 顶层 `__className` 删除弹 confirm;**嵌套**(weapon.wand / VelvetPouch.inventory[0])删除也弹 confirm,confirm 文案列出所有路径 | 手测 |
| 8 | 嵌套结构(MagesStaff.wand / VelvetPouch.inventory)round-trip 正确 | pytest 2 个新用例 |
| 9 | 真实 123.zip 编辑 → pack → 通过 `SaveSlotIO.readSlotFromStream` | Java 闭环测试 |
| 10 | 不引入新 Python 依赖 | `pip freeze` 与之前一致 |
| 11 | 代码全在 `tools/save-editor/`,Android 构建仍通过 | `./gradlew :android:assembleDebug` SUCCESSFUL |
| 12 | 已有 36 个 pytest 用例不回归 | `pytest -q` 全绿 |
| 13 | warning 区块可见,**明确提到 quickslotpos 不要随意改** | 手测:展开折叠区看到文案 |
| 14 | `state.game.hero` 缺失/不是 object 时,3 个 textarea 都置空 disabled,不抛 JS 异常,12 字段编辑和 pack 仍工作 | 手测:用 game.dat 里 hero=null 的合成 zip 上传 |
| 15 | "重置"同时恢复 `state.game.hero.<field>` 为 initialState deep clone **并**回填 textarea,reset 后直接 pack 不含错误改动 | 手测:应用 → 改 textarea → 点重置 → 直接下载 zip,parse 验证字段值是 parse 时原值 |
| 16 | inventory length 变化不阻止应用(只提示"原 N 现 M"),不报错 | 手测 |
| 17 | `state.game.hero` 存在但 armor/weapon/inventory key 缺失时,对应 textarea **enabled**、hint 显示 `(none — will create)`,允许用户写入新 JSON 创建该字段 | 手测:用 game.dat hero={} (无 armor key) 合成 zip,验证 armor textarea 可输入可应用 |

## 风险与备选

- **用户改出非法物品结构导致 SPD 崩溃** — MVP 只递归 warn `__className` 删除,其他不做 schema 校验(quickslotpos / id / pos / quantity 等关键字段不动)。用户用存档编辑器通常能接受风险
- **大背包 textarea 慢** — 实际背包 10-20 个 item,JSON 5-10KB,textarea 性能没问题;VelvetPouch 嵌套 inventory 实际深度 ≤ 3 层
- **嵌套深度** — SPD 实际嵌套 ≤ 3 层(VelvetPouch.inventory 是种子,PotionBelt 嵌套药水等),textarea 不需要折叠
- **类型 hint 不准** — `(__className||'').split('.').pop() || '?'` 取最后一段,准确
- **JSON 错误信息跨浏览器** — Chrome 给 `position`,Firefox 给 `line X column Y`,Safari 行为不同。本 MVP 优先抽 `position`,抽不到就原样展示,不强求
- **回归现有功能** — 测试覆盖现有 36 用例 + 新增 2 用例,确保不破坏

## Out of scope(明确不做)

- 表单式 schema 编辑器(每个 Item 子类的字段表单)
- 模板库("一键加满血药")
- depth heaps 编辑(地上的物品堆)
- **提供"添加 / 删除 inventory item"的表单 UI**(raw JSON 仍允许手动增删,风险自担,前端只提示长度变化不阻止)
- 实时 JSON syntax highlight / 折叠 / linter(纯 textarea)
- 物品类型 dropdown(不能换物品类型,改 `__className` 不在 UI 引导,但 raw JSON 里允许)
- 物品图标 / 预览
- **quickslot 同步**(改 hero.weapon.quickslotpos 不会同步游戏内 quickslot 数据,只警告)
- 多语言(只中文 UI)
- 撤销 / 重做

## 后续可加(不在本 PR)

- depth heaps 编辑(类似 inventory 的 raw JSON textarea,但 per-depth)
- 模板库(选预设 → 一键填一个新 item 到 inventory 末尾)
- 表单 schema(枚举 Item 子类 + 字段表单)
- diff 视图
