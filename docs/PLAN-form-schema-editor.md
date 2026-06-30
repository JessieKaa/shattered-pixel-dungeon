# PLAN: Save Editor — Form Schema Editor (Dynamic Field Forms)

**Slug**: `form-schema-editor`
**Branch**: `feature/form-schema-editor` (based on `feature/local-save-slots`)
**Date**: 2026-07-01

---

## Goal

在 `save-editor-items` 的 raw JSON textarea 之上,**新增一个表单式编辑器**:对 `hero.armor` / `hero.weapon` / `hero.inventory` 里的每个字段自动生成对应的表单控件(checkbox / number input / text input / 嵌套折叠),让不懂 JSON 的用户也能改物品。

**关键策略**:schema **动态推断**(从 bundle 字段类型自动生成),不维护静态 Item schema 表。

## Context

- 现状(`feature/local-save-slots` HEAD `b54b4f04`):
  - 12 个数值字段表(meta/hero/gold/challenges/seed)
  - `<details id="items-raw-edit">` raw JSON 折叠区,3 个 textarea(armor/weapon/inventory)
  - state 拆分:数值字段由主表管,`hero.{armor,weapon,inventory}` 由 raw 编辑器管
- 真实存档字段类型分布(实测 `/tmp/123.zip`):
  - **bool**: `cursed`, `cursedKnown`, `kept_lost`, `levelKnown`
  - **int**: `level`, `quantity`, `volume` (Waterskin), `quickslotpos` (Waterskin)
  - **dict**: `armor`(整个)、`weapon`(整个)、`weapon.wand`(MagesStaff 嵌套)
  - **list**: `inventory`(整个,3 项)、`inventory[2].inventory`(VelvetPouch 嵌套,可空)
  - **string**: `__className`(类型 ID,**不可改**)
- 物品子类约 100+,**不可能维护静态 schema 表**,所以选运行时类型推断
- 已有 raw JSON 编辑器兜底,表单能改的就改、改不了的提示用户切到 raw JSON

## Architecture

```
index.html 结构
├── 现有 12 数值字段表(不动)
├── <details id="items-form-edit"> ★新增(默认折叠,在 raw-edit 上方)
│     ├── <fieldset> hero.armor
│     │     ├── __className (只读)
│     │     ├── level        [number]
│     │     ├── cursed       [checkbox]
│     │     ├── cursedKnown  [checkbox]
│     │     ├── ...其他字段
│     │     ├── [+ 添加字段]
│     │     └── [应用] [重置]
│     ├── <fieldset> hero.weapon
│     │     ├── __className (只读)
│     │     ├── level        [number]
│     │     ├── <details> hero.weapon.wand(嵌套 dict,递归渲染)
│     │     │     └── 字段表单(同上结构)
│     │     └── [应用] [重置]
│     └── <fieldset> hero.inventory(list of Item)
│           ├── Item 卡片 #0 (Food) — 可折叠
│           │     ├── __className (只读)
│           │     ├── quantity [number]
│           │     ├── level    [number]
│           │     └── ...
│           ├── Item 卡片 #1 (Waterskin)
│           ├── Item 卡片 #2 (VelvetPouch)
│           │     └── <details> inventory(嵌套 list,空时显示提示)
│           └── [应用] [重置]
└── <details id="items-raw-edit">(已有,不动)
```

### 类型推断规则(运行时)

```javascript
function inferType(value) {
  if (typeof value === 'boolean') return 'bool';   // 必须先于 int
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'int' : 'float';
  }
  if (value === null) return 'null';
  if (typeof value === 'string') return 'str';
  if (Array.isArray(value)) return 'list';
  if (typeof value === 'object') return 'dict';
  return 'unknown';
}
```

### 控件映射

| 类型 | 控件 | 备注 |
|---|---|---|
| `bool` | `<input type="checkbox">` | 直接绑定 checked |
| `int` | `<input type="number" step="1">` | parse 时 `parseInt(radix=10)` |
| `float` | `<input type="number" step="0.01">` | parse 时 `parseFloat` |
| `str` | `<input type="text">` | 直接 value |
| `null` | text input + "(null)" 占位 | 提示用户填值;空就保持 null |
| `list` | `<details>` 子折叠区,显示 `length` | 每项递归渲染(包括 dict 项) |
| `dict` | `<details>` 子折叠区 | 字段表单递归 |
| `__className`(任何类型) | `<input readonly>` 灰底 | **强制只读**,不可编辑 |

### 状态同步

- 表单和 raw JSON 共享 `state.game.hero.{armor, weapon, inventory}`
- 打开表单折叠区(`<details>` toggle open)→ 从 state 重新渲染
- 打开 raw JSON 折叠区 → 从 state 重新填
- 用户责任:切换前先点"应用"(表单)/已自动同步(raw JSON 的"应用"是显式的)
- **不**做实时双向同步(避免循环和复杂度)

### 字段操作

- **删除字段**:每个字段行右侧"删除"按钮(除 `__className`)
- **添加字段**:`<fieldset>` 底部"+ 添加字段"按钮 → `prompt("字段名")` + `prompt("类型: int/bool/str/float/null")`(MVP 用 prompt,不引入 modal)
  - 字段名重复 → 拒绝
  - 类型非法 → 拒绝
  - 添加后默认值:int=0 / bool=false / str="" / float=0.0 / null=null
- 字段排序按字母序(`__className` 始终第一)

## Files

### 改 `tools/save-editor/templates/index.html`

1. 在 `items-raw-edit` 上方加 `<details id="items-form-edit">`(默认折叠)
2. JS 新增函数:
   - `inferType(value)` — 类型推断
   - `renderField(key, value, path)` — 单字段控件渲染器(返回 DOM 节点 + delete button)
   - `renderDictFields(obj, path, container)` — dict 字段集渲染(递归)
   - `renderListItem(item, idx, path, container)` — list item 卡片渲染
   - `renderItemsForm()` — 从 `state.game.hero` 渲染三个 `<fieldset>`
   - `collectFormToState()` — 反向收集,把表单值写回 state
   - `addFieldPrompt(parentObj, path)` — 添加字段交互
   - `deleteField(parentObj, key, row)` — 删除字段交互
3. 表单 `<details>` 的 `toggle` 事件 → open 时调 `renderItemsForm()`
4. "应用"按钮 → `collectFormToState()` + 标记 dirty
5. "重置"按钮 → 从 `originalState.game.hero` 重渲染
6. 现有 raw JSON `<details>` 的 toggle open 行为同步:重新从 state 填

### 改 `tools/save-editor/tests/test_spd_bundle.py`

新增 2 个用例(挂在文件末尾,与现有 `test_round_trip_hero_inventory_with_nested_container` 同章节):

- `test_round_trip_nested_dict_preserves_field_set` —
  构造 `hero.weapon` 含 `wand` 嵌套 dict(含 `partialCharge` float 字段、`curriedCharges` int 字段、`zapped` bool 字段),
  pack → parse → 断言外层 dict keys 集合相同 + 嵌套 wand keys 集合相同 + 每个字段类型一致(`type(v) is ...`)
- `test_round_trip_list_with_mixed_typed_items_preserves_each_item` —
  构造 `hero.inventory` 含 3 个不同 Item:
  - Food:只有 `quantity` int
  - Waterskin:含 `volume` float(模拟 1.5)、`quickslotpos` int
  - VelvetPouch:含 `inventory` 嵌套 list,内放 1 个 Seed
  pack → parse → 断言每个 item 的 keys 集合相同 + 数值字段类型一致

### **不改动**

- `spd_bundle.py`(纯逻辑,无 schema 概念)
- `app.py`(`/api/parse` 已返回完整 game dict,`/api/pack` 已接受完整 game dict)
- 现有 36 + 2 = 38 个 pytest 用例不回归

## Steps

> 状态模型说明(贯穿所有 step):
> - `state.game.hero.{armor,weapon,inventory}` 是表单与 raw JSON 编辑器共享的单一事实源
> - 表单是**视图**:`renderItemsForm()` 总是从 state 读 → 构建 DOM;`collectFormToState()` 总是从 DOM 读 → 写回 state
> - 表单不维护自己的 model,只维护 DOM;每个 row 元素上挂 `row._getValue = getValue` 闭包(`renderField` 内绑定),`collectContainer` 通过 `child._getValue` 取回 getter,避免依赖临时返回值被调用方记住
> - 表单标量输入"应用"= `collectFormToState(field)`;"重置"= 从 `initialState.game.hero` 重渲
> - **add/delete 字段是结构变更,会重渲整个 items-form**。为避免丢失同一 fieldset 内尚未应用的标量输入,`addFieldPromptFromButton` / `deleteFieldFromButton` 在 mutate state 前必须先对该 fieldset 调一次 `collectFormToState(field)`,失败则显示错误并中止;成功后才修改 state + 重渲(msg 文案带"已自动应用")
> - raw JSON"应用"已经把整段 JSON 写入 state,所以切换到表单(toggle open)能看到 raw JSON 应用后的最新值
>
> 安全约束(所有动态内容):
> - 字段名 / `__className` / 字符串字段值 / 错误消息 / summary 文本全部来自用户提供的 zip,视为不可信输入
> - 所有动态字符串**必须**通过 DOM API 写入:`textContent`(label / hint / summary / msg)、`input.value`(控件值)、`input.checked`(checkbox)、`setAttribute`(dataset,但 path 已是受信内部数据)
> - **禁止**用模板字符串拼 `innerHTML`(任何来自 state 的内容)。`innerHTML = ""` 清空容器是安全的,允许
> - 现有 `escapeHtml` 仅用于 `renderWarnings`(legacy code path),form 编辑器代码不走那条路径

### Step 1: 表单折叠区骨架

- 在 `<details id="items-raw-edit">` 上方加 `<details id="items-form-edit">`
- summary 文案:"高级:物品表单编辑器(推荐)"
- 内置提示文案:"修改后请点'应用'再切换到 raw JSON 或下载 zip。`__className` 字段不可改、不可删"
- 三个空 `<fieldset>`(`#fs-armor-form` / `#fs-weapon-form` / `#fs-inventory-form`),每个含 legend、容器 div(`#form-armor-body` / 等)、actions(应用/重置按钮,挂 `data-target` + `data-action="form-apply"`/`"form-reset"`)、msg(`#form-armor-msg` 等)
- 默认 `closed`,DOM contentLoaded 时不渲染(parse 成功后渲染)
- CSS 复用现有 `.item-fieldset` 风格,新增 `.form-field` / `.form-row` / `.form-type-hint` / `.form-nested` 几个 class

### Step 2: 类型推断 + 单字段控件渲染

`inferType(value)` 顺序(必须严格):
1. `value === null` → `'null'`
2. `typeof value === 'boolean'` → `'bool'`
3. `typeof value === 'number'` → `Number.isInteger(value) ? 'int' : 'float'`
4. `typeof value === 'string'` → `'str'`
5. `Array.isArray(value)` → `'list'`
6. `typeof value === 'object'` → `'dict'`
7. else → `'unknown'`

`renderField(key, value, path)` 返回 `{ row, getValue }`,并把 `getValue` 挂到 `row._getValue`(供 `collectContainer` 反向取回):
- `row` 是 `<div class="form-row">`,内含:
  - `<label>` 用 `textContent = key`(若 key === `'__className'` 加 CSS `::after` 标"(只读)",不走 innerHTML)
  - 控件(按类型,均通过 `createElement` + 属性设置,**禁止**模板字符串拼 innerHTML):
    - `bool` → `<input type="checkbox">`,getValue 返回 `checkbox.checked`
    - `int` → `<input type="number" step="1">`,getValue 严格校验:`/^-?\d+$/.test(raw)` + `Number(raw)` + `Number.isInteger(n)`,任何一项失败抛 ParseError(原 parseInt 静默截断 "1.5"/"1e2" 是 bug)
    - `float` → `<input type="number" step="0.01">`,getValue 用 `Number(raw)` + `Number.isFinite(n)`(原 parseFloat 也会截断尾部垃圾,改 Number 更严格)
    - `str` → `<input type="text">`,getValue 返回字符串
    - `null` → `<input type="text" placeholder="(null — 留空保持 null)">`,getValue 返回 null(空)或字符串(有值)
    - `list` / `dict` → 调 `renderNested(key, value, type, path)` 生成 `<details class="form-nested">`,getValue = `det._collect`(嵌套递归收集)
    - `unknown` → `<input type="text" disabled>`,getValue 返回原值(只读)
  - `<span class="form-type-hint">` 用 `textContent = \`(${type})\``
  - 删除按钮:若 key !== `'__className'`,挂 `<button class="form-del" data-action="form-del" data-path=JSON.stringify(path) data-key=key>`,**不**用 innerHTML
- `__className` 字段:控件 `readonly` + 灰底 + 不挂删除按钮

### Step 3: dict 字段集渲染(递归)

`renderDictFields(obj, path, container)`:
- 清空 `container.innerHTML = ''`
- 排序:`__className` 永远第一,其余按 `Object.keys(obj).sort()` 字母序
- 对每个 (key, value):`const { row } = renderField(key, value, path.concat([key])); container.appendChild(row);`
- 在 container 底部追加一个"+ 添加字段"按钮(`data-action="form-add-field"` + `data-path="<JSON of path>"`),点击触发 `addFieldPrompt(obj, path)`

### Step 4: list 渲染

`renderListItems(arr, path, container)`:
- 清空 container
- 若 `arr.length === 0`:显示 `<div class="form-empty">(空 list)</div>`,返回
- 对每个 (idx, item):
  - 外层 `<details class="form-nested" open>`(顶层 item 默认展开,深层默认折叠,见下)
  - `<summary>` 文字:`#${idx} ${item.__className ? formatClassName(item.__className) : typeof item}`
  - 内部根据 `inferType(item)`:
    - dict → 调 `renderDictFields(item, path.concat([idx]), inner)`,递归把字段挂进去
    - list → 调 `renderListItems(item, path.concat([idx]), inner)`(可空)
    - 标量 → 单 input 控件(罕见但兜底)
  - **不支持** list item 增/删/排序(Out of scope,文案:"如需增删请用 raw JSON 编辑器")

### Step 5: 顶层渲染 + 收集

`renderItemsForm()`:
- 对每个 f in `['armor', 'weapon', 'inventory']`:
  - 取 `value = state.game.hero[f]`
  - 若 `value === undefined` 或 `value === null`:
    - 在 body 显示 `<div class="form-empty">(${f} 为 ${value === null ? 'null(unequipped)' : '未设置'})</div>`,按钮禁用
    - 继续下一个
  - armor / weapon:`renderDictFields(value, [f], bodyEl)`
  - inventory:`renderListItems(value, [f], bodyEl)`
- 三个 fieldset 的"应用"按钮挂同一回调 `applyForm(field)`,通过 `data-target` 区分

`collectFormToState(field)`:
- 取当前 field 的 DOM 根,遍历顶层 rows 收集 getValue()
- 任何 getValue() 抛 ParseError → 在 `#form-${field}-msg` 显示具体哪个字段、什么值,中止收集(state 不变)
- 全部成功 → `state.game.hero[field] = collectedValue`
- 深拷贝 collectedValue(避免 DOM 上 getValue 闭包后续被改动),detach 后写 state

`applyForm(field)`:
- 调 `collectFormToState(field)`
- 成功:`#form-${field}-msg` 显示"已应用(下次点'下载 zip'生效)",`ok` 色
- 失败:msg 显示具体错误,`error` 色

`resetForm(field)`:
- `state.game.hero[field] = deepClone(initialState.game.hero[field])`
- 调 `renderItemsForm()` 重渲

### Step 6: 字段增删(结构变更前先 collect,**保护 sibling 输入**)

`addFieldPrompt(parentObj, path)`(`addFieldPromptFromButton` 入口):
1. `field = path[0]`(armor / weapon / inventory)
2. **先 `collectFormToState(field)`**:把当前 DOM 的所有 sibling 输入暂存进 state。失败 → 显示 `应用失败: <错误>`,**中止**(不允许用户绕过 sibling 的 parse 错误来 add 字段)
3. 重新 `resolveStateByPath(path)` 拿到最新 parentObj(collect 后 state 可能已深拷贝替换,reference 会失效)
4. `const key = prompt("新字段名(字母开头,允许字母数字下划线):")`
5. 校验:`/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(key)`,不通过 alert 并退出
6. 已存在 → alert `"字段已存在: ${key}"`,退出
7. `const type = prompt("类型 (int/bool/str/float/null):")`(小写、trim)
8. 校验枚举,不在 `['int','bool','str','float','null']` alert 退出
9. 默认值:`int → 0`、`bool → false`、`str → ''`、`float → 0.0`、`null → null`
10. `parentObj[key] = defaultValue`,然后调 `renderItemsForm()`(整体重渲)
11. setFormMsg 显示 "已添加字段 X (T),**已自动应用**。记得点下载 zip 生效"

`deleteField(parentObj, key, row)`(`deleteFieldFromButton` 入口):
1. 若 key === `__className` → alert 拒绝
2. `confirm(\`删除字段 \"${key}\"?\`)`,取消退出
3. **先 `collectFormToState(field)`**:同 add,保护 sibling。失败 → 显示错误,中止
4. 重新 resolve parentObj
5. `delete parentObj[key]` + `renderItemsForm()`(整体重渲;原 PLAN "只删 row" 在结构变更下不安全,因为 sibling 已 collect 进 state,DOM 需重建以反映 state)
6. setFormMsg 显示 "已删除字段 X,**已自动应用**。记得点下载 zip 生效"

### Step 7: 表单 ↔ raw JSON 一致性

- 表单 `<details>` toggle open → `renderItemsForm()`(从 state)
- raw JSON `<details>` toggle open → 重新从 state `JSON.stringify` 填 textarea(复用 `loadItemField`)
- 用户切到对面之前必须先点对面折叠区里的"应用"(MVP 接受这个约束,文案里说明)
- toggle close 不清空(只读一遍,下次 open 重读)

### Step 8: 测试 + 验证

- `pytest -q`:38/38 全绿 + 2 新增 = 40/40
- 手测 acceptance 17 点(其中 #9 / #11 / #13 / #17 走 round-trip + 重 parse 验证)
- **Selenium E2E 11 个 case**(脚本不在 repo,作为开发期回归手测;每次大改 form 代码必须跑):
  1. armor.level (int) round-trip 5
  2. inv[0].quantity (int) round-trip 99,长度保留
  3. armor.cursed (bool) false→true
  4. add 字段 custom_tag (str) value="my_tag_value" round-trip
  5. delete 字段 augment round-trip
  6. int 字段填 "abc" → 拒绝,state.level 不变
  7. `__className` readonly + 无删除按钮
  8. 表单 apply 后切到 raw JSON → 看到最新 state
  9. **must-fix #1**:改 level=5 不应用 → add 字段 → level=5 + temp_field 都进 state
  10. **must-fix #1**:改 level=9 不应用 → delete augment → level=9 + augment 删除都进 state
  11. **must-fix #2**:int 字段填 "1.5" / "1e2" → 拒绝(parseInt 截断 bug)
- 真实 `/tmp/123.zip` round-trip:armor 13 字段 + inv 3 项 + weapon.wand 嵌套 字段集保留
- Java 闭环:`SPD_ZIP_PATH=/tmp/edited.zip ./gradlew :core:test --tests '*SaveSlotIOPythonZipTest'` 1/1 pass

## Acceptance

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | 现有 12 数值字段 + raw JSON 编辑器 + 38 pytest 用例不回归 | 手测 + `pytest -q` |
| 2 | `<details id="items-form-edit">` 默认折叠,展开后看到 3 个 fieldset | 手测 |
| 3 | parse `/tmp/123.zip` 后,armor fieldset 显示 ClothArmor 的所有字段(level/cursed/cursedKnown/...),类型提示正确 | 手测 |
| 4 | bool 字段是 checkbox,int 字段是 number input,string 字段是 text input | 手测 |
| 5 | `__className` 字段是只读灰底,无"删除"按钮 | 手测 |
| 6 | weapon.wand(MagesStaff 嵌套 dict)在 weapon fieldset 里以子折叠区展示,展开后是 WandOfMagicMissile 字段表单 | 手测 |
| 7 | inventory 是 list,每个 item 一张卡片,展开后是该 item 的字段表单 | 手测 |
| 8 | VelvetPouch.inventory 是嵌套 list,空时显示"(空)",非空时折叠展开 | 手测(本例为空,代码路径靠 pytest 覆盖) |
| 9 | 改 `inv[0].quantity` 1→99 + `weapon.level` 0→5,点"应用",下载 zip,重新 parse 字段值正确 | round-trip |
| 10 | 数字字段填非数字("abc")时显示错误,不应用 | 手测 |
| 11 | 添加新字段(如 `custom_tag` 类型 str)成功,pack 后重新 parse 保留 | round-trip |
| 12 | 添加已存在字段名 → 拒绝 + 错误提示 | 手测 |
| 13 | 删除非 `__className` 字段成功,pack 后该字段消失 | round-trip |
| 14 | 删除 `__className` 字段被拒绝(按钮不显示或灰禁) | 手测 |
| 15 | 切到 raw JSON 折叠区(toggle open)显示的是表单应用后的最新 state | 手测 |
| 16 | 不引入新 Python 依赖 | `pip freeze` 对比 |
| 17 | 嵌套结构 round-trip(MagesStaff.wand / VelvetPouch.inventory 非空)通过 pytest | 2 个新增用例 |

## 风险与备选

- **类型推断错误**(int/float 误判):`Number.isInteger` 严格判断;`__className` 强制 string
- **用户切折叠区前忘点"应用"**:折叠区头部加文案"修改后请点应用再切换";展开时从 state 重新渲染(用户失去未应用的输入,但有提示)
- **嵌套深度爆炸**:UI 默认折叠到第 2 层(`<details open>` 仅顶层);SPD 实际嵌套 ≤ 3 层
- **prompt UX 差**:MVP 用 `window.prompt`,后续可换成 modal;不阻塞发布
- **list 内字段 reorder / add / remove**:MVP 不支持,引导用户切到 raw JSON
- **表单和 raw JSON 同时打开冲突**:文案说明 + 折叠区互斥建议(但不强制禁用)
- **回归现有功能**:38 个原 pytest 用例 + 新增 2 个,确保不破坏

## Out of scope(明确不做)

- 静态 Item schema 表(每个子类的字段元数据)
- list item 增 / 删 / 排序
- 字段类型转换(int 改 string 等,引导用户切 raw JSON)
- `__className` 修改(物品类型转换)
- schema 校验(必填字段检查、引用完整性等)
- 撤销 / 重做
- 多语言
- 模板字段(快捷字段库)
- 表单 ↔ raw JSON 实时双向同步
- modal 替代 prompt(后续 PR)
- 字段重命名

## 后续可加(不在本 PR)

- modal 替代 `window.prompt`
- 字段重命名(改名 + 保留值)
- list item 增删
- 模板字段库(常用字段一键添加)
- 静态 schema(常用 Item 子类的字段元数据,显示中文名 + 描述)
- 表单和 raw JSON 双向实时同步(基于状态机)
