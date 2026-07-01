# PLAN: Save Editor — Vue 3 SPA 完整重构

**Slug**: `vue-editor`
**Branch**: `feature/vue-editor` (based on `feature/local-save-slots`)
**Date**: 2026-07-01

---

## Goal

把现有 1300+ 行单文件 vanilla JS `templates/index.html` 重写为 **Vue 3 + Element Plus + Pinia** 的 SPA,完整覆盖现有功能 + 新增 modal 字段增删 / list item 增删拖拽 / 撤销重做 / 表单 ↔ raw JSON 双向同步 / Monaco JSON 高亮。后端 Flask 完全不动。

## Context

- 现状(`feature/local-save-slots` HEAD `e82e63b3`):
  - `templates/index.html` 46KB,三段编辑器(数值字段表 / raw JSON / 表单 schema)耦合在 vanilla JS 里
  - `app.py` 192 行,API 稳定(`POST /api/parse` / `POST /api/pack`)
  - `spd_bundle.py` 纯逻辑,40 个 pytest 全绿
  - Java 闭环测试 `SaveSlotIOPythonZipTest` 通过
- 工作流约束:
  - 用户选了"完整重构"(modal / 拖拽 / undo-redo / 双向同步)
  - 中文 UI,单用户工具,不需要 SSR/SEO
  - 跨平台(Linux 主开发,Windows 可运行)
- 现有痛点:
  - 1300 行 HTML 改一个字段类型要翻 3 个区块
  - 无 undo,误删字段只能重导
  - raw JSON 和表单切换前忘点"应用"就丢数据
  - list 顺序写死,无法重排

## Architecture

### 目录结构

```
tools/save-editor/
├── app.py                 [改] serve frontend/dist + API
├── spd_bundle.py          [不动]
├── tests/                 [不动]
├── templates/             [删]
│   └── index.html         [删除]
└── frontend/              ★新增 Vue 项目
    ├── package.json
    ├── vite.config.ts     (dev proxy /api → Flask)
    ├── tsconfig.json
    ├── index.html
    ├── README.md          (dev / build 指南)
    └── src/
        ├── main.ts        (Vue + EP + Pinia 初始化)
        ├── App.vue        (顶层布局)
        ├── api.ts         (fetch 封装)
        ├── types.ts       (SaveSlotBundle 等接口)
        ├── stores/
        │   ├── bundle.ts  (当前 parse 结果 + dirty 标志)
        │   ├── history.ts (undo/redo snapshot 栈)
        │   └── ui.ts      (loading / activeTab / collapsed)
        ├── composables/
        │   ├── useFieldType.ts   (inferType + 控件映射)
        │   └── useFieldOps.ts    (增删字段 + 校验)
        └── components/
            ├── FileLoader.vue        (拖拽上传 zip)
            ├── NumericFields.vue     (12 个数值字段表)
            ├── RawJsonEditor.vue     (Monaco × 3)
            ├── FormSchemaEditor.vue  (动态表单 + 嵌套递归)
            ├── FieldRow.vue          (单字段行)
            ├── NestedObject.vue      (dict 递归)
            ├── NestedList.vue        (list 递归 + vuedraggable)
            ├── AddFieldDialog.vue    (Element Plus modal)
            ├── AddItemDialog.vue     (list item 添加 modal)
            ├── ActionBar.vue         (下载/重置/撤销重做)
            └── SlotMetaPanel.vue     (meta 信息卡片)
```

### 数据流

```
[FileLoader.vue]
     │ POST /api/parse
     ▼
[Pinia bundle store] ──┐
     │                  │
     ▼                  ▼
[FormSchemaEditor]   [RawJsonEditor]
  v-model 双向          Monaco 显式"应用"
     │                  │
     └──► [Pinia history store] (snapshot 栈)
              │
              ▼
          [ActionBar]
              │ POST /api/pack
              ▼
          下载 zip
```

### 关键设计决策

1. **Composition API + `<script setup>`**(单文件组件)
2. **Element Plus 中文 locale + 暗色主题**(默认暗色,可切换)
3. **Pinia 三 store**:
   - `bundle`:meta + game + depths + originalBundle(deep clone 用于重置)
   - `history`:snapshot 栈(max 50),`pushSnapshot()` / `undo()` / `redo()`
   - `ui`:loading / activeTab(form/raw)/ collapsed states
4. **撤销/重做策略**:
   - Snapshot(deep clone `state.game`),不用 JSON patch(简单优先)
   - 触发时机:每次"应用"前 + 每次增删字段前
   - 栈上限 50,超出丢最老
   - 快捷键:Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y
5. **表单 ↔ raw JSON 双向同步**:
   - 表单字段 `v-model` 绑 store 的 reactive 路径,**实时写 store**
   - raw JSON 用 Monaco,显式"应用"按钮触发反序列化 → 写 store
   - 监听 store.game 变化时,自动刷新 raw JSON 文本(若 Monaco 未在编辑)
   - 双方编辑冲突:**后写胜出**,无 lock,用户责任
6. **Monaco editor**:
   - `@guolao/vue-monaco-editor` wrapper + `monaco-editor` 0.52
   - JSON 语法高亮 + 智能缩进 + 折叠
   - **lazy load**(减小初始 bundle)
7. **list item 拖拽**:
   - `vuedraggable-next`(基于 SortableJS)
   - 每个 item 卡片有 drag handle + 删除按钮
   - 添加按钮弹 `<el-dialog>` modal,选 `__className` + 默认值模板
8. **字段增删 modal**:
   - 添加字段:`<el-dialog>` + `<el-form>` 输入字段名 + `<el-select>` 类型(int/bool/str/float/null/list/dict)
   - 重名字段立即校验拒绝
   - 删除字段:`<el-popconfirm>` 二次确认
9. **构建工具**:
   - dev:`npm run dev`(Vite :5173)+ `PORT=5001 python app.py`(Flask)
   - Vite proxy:`/api` → `http://127.0.0.1:5001`
   - prod:`npm run build` → `frontend/dist/`
10. **Flask 适配 prod**:
    - `GET /` → `send_from_directory('frontend/dist', 'index.html')`
    - `GET /assets/<path:path>` → `frontend/dist/assets/<path>`
    - SPA fallback:任何非 `/api`、非静态资源路径都 fallback 到 `index.html`(本工具是单页,只有根路径)
    - 检测 `frontend/dist/` 不存在时返回 503 + 提示运行 `npm run build && python app.py`

## Files

### 新增 `tools/save-editor/frontend/package.json`

依赖:
- `vue` ^3.5
- `pinia` ^2.2
- `element-plus` ^2.8
- `@element-plus/icons-vue` ^2.3
- `@guolao/vue-monaco-editor` ^1.5
- `monaco-editor` ^0.52
- `vuedraggable-next` ^2.0

devDependencies:
- `vite` ^5.4
- `@vitejs/plugin-vue` ^5.1
- `typescript` ^5.6
- `vue-tsc` ^2.5
- `@types/node` ^22

scripts:
- `dev`: `vite`
- `build`: `vue-tsc -b && vite build`
- `preview`: `vite preview`

### 新增 `tools/save-editor/frontend/vite.config.ts`

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:5001',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,  // Monaco 单 chunk 较大
  },
})
```

### 新增 `tools/save-editor/frontend/src/types.ts`

```typescript
export interface SaveSlotBundle {
  meta: SlotMeta;
  game: GameBundle;
  depths: Record<string, unknown>;
  warnings?: string[];
  __raw_files?: string[];
}

export interface SlotMeta {
  name: string;
  depth: number;
  level: number;
  version: number;
  [k: string]: unknown;
}

export interface GameBundle {
  version: number;
  hero: HeroBundle;
  gold: number;
  depth: number;
  seed: string;
  challenges: number;
  [k: string]: unknown;
}

export interface HeroBundle {
  __className: string;
  HP: number;
  HT: number;
  STR: number;
  exp: number;
  level: number;
  armor: Item | null;
  weapon: Item | null;
  inventory: Item[];
  [k: string]: unknown;
}

export type Item = {
  __className: string;
  [k: string]: unknown;
};

export type FieldType = 'bool' | 'int' | 'float' | 'str' | 'null' | 'list' | 'dict' | 'unknown';
```

### 新增 `tools/save-editor/frontend/src/stores/bundle.ts`

Pinia store:
- `state`: `{ meta, game, depths, originalBundle, dirty }`
- `actions`:
  - `setBundle(b: SaveSlotBundle)` — 写入 + deep clone originalBundle
  - `resetToOriginal()` — 从 originalBundle 恢复
  - `clearDirty()` / `markDirty()`

### 新增 `tools/save-editor/frontend/src/stores/history.ts`

- `state`: `{ undoStack: GameBundle[], redoStack: GameBundle[] }`
- `actions`:
  - `pushSnapshot()` — 复制当前 bundle.game 入 undoStack,清空 redoStack
  - `undo()` — push 当前到 redoStack,pop undoStack 写回 bundle
  - `redo()` — 反向
  - `canUndo` / `canRedo` getters

### 新增 `tools/save-editor/frontend/src/components/*.vue`

10 个 Vue SFC,见 Architecture 目录树。每个组件职责单一,Props/Emits 严格类型化。

### 改 `tools/save-editor/app.py`

```python
# 新增 import
from flask import send_from_directory
import os

FRONTEND_DIST = os.path.join(os.path.dirname(__file__), 'frontend', 'dist')

@app.route('/')
def index():
    if not os.path.exists(FRONTEND_DIST):
        return ('Frontend not built. Run: cd frontend && npm install && npm run build', 503)
    return send_from_directory(FRONTEND_DIST, 'index.html')

@app.route('/assets/<path:path>')
def assets(path):
    return send_from_directory(os.path.join(FRONTEND_DIST, 'assets'), path)

# /api/parse, /api/pack 保持不变
```

### 删 `tools/save-editor/templates/index.html`

被 `frontend/dist/index.html` 取代。

### 不改

- `spd_bundle.py`(纯逻辑)
- `tests/test_spd_bundle.py`(40 用例)
- `tests/test_save_slot_io_cross_check.py`
- `requirements.txt`(后端无新依赖)

## Steps

### Step 1: 项目骨架 + 依赖安装
- `cd tools/save-editor && npm create vite@latest frontend -- --template vue-ts`
- 安装生产 + 开发依赖
- 配置 `vite.config.ts` `/api` 反代
- `npm run dev` 启动,验证空白页能跑

### Step 2: 引入 Element Plus + 中文 locale + 暗色主题
- `main.ts` 注册 Element Plus + zh-cn locale + 图标
- 引入 `element-plus/theme-chalk/dark/css-vars.css`
- `<el-config-provider :locale="zhCn">` 包裹根组件
- `<html class="dark">` 默认暗色

### Step 3: 类型定义 + Pinia 三 store
- `types.ts`(见 Files)
- `stores/bundle.ts`(state + actions)
- `stores/history.ts`(undo/redo 栈 + 上限 50)
- `stores/ui.ts`(loading / activeTab / collapsed)

### Step 4: 文件加载组件
- `FileLoader.vue`:`<el-upload drag>` + 进度条
- 拖拽 + 点击选择
- 调 `POST /api/parse`
- 成功写入 bundle store + push history snapshot
- 失败 `<el-message>` 提示

### Step 5: 数值字段表
- `NumericFields.vue`:`<el-form label-width="120px">`
- 12 个 `<el-form-item>`:
  - meta.name(text)/ meta.depth / meta.level / meta.version(只读)
  - hero.HP / hero.HT / hero.STR / hero.exp / hero.level
  - gold / depth / challenges / seed(text)
- `<el-input-number>` 控件 + min/max 校验
- v-model 双向绑 bundle store
- 字段分组(meta / hero / game 三 `<el-collapse-item>`)

### Step 6: 类型推断 + 字段行组件
- `composables/useFieldType.ts`:
  ```typescript
  export function inferType(v: unknown): FieldType {
    if (v === null) return 'null';
    if (typeof v === 'boolean') return 'bool';
    if (typeof v === 'number') return Number.isInteger(v) ? 'int' : 'float';
    if (typeof v === 'string') return 'str';
    if (Array.isArray(v)) return 'list';
    if (typeof v === 'object') return 'dict';
    return 'unknown';
  }
  ```
- `FieldRow.vue`:单字段渲染器,props:`{ key, value, path }`,emits:`{ update, delete }`
  - bool → `<el-switch>`
  - int → `<el-input-number :step="1">`
  - float → `<el-input-number :step="0.01">`
  - str → `<el-input>`
  - null → `<el-input placeholder="(null — 留空保持 null)">`
  - list/dict → 不在 FieldRow 渲染(由父组件 NestedObject / NestedList 处理)
  - `__className` 字段:`<el-input disabled>` + 灰底
  - 右侧"删除"按钮(`<el-popconfirm>`)

### Step 7: dict 递归 + list 递归
- `NestedObject.vue`:`<el-collapse>` + 内部 `v-for` 调 `FieldRow`
  - 字段按字母序(`__className` 永远第一)
  - 嵌套 dict/list 用 `<el-collapse-item>` 递归
- `NestedList.vue`:list of Item
  - 用 `vuedraggable-next` 支持拖拽排序
  - 每个 item `<el-card>` + drag handle + 删除按钮
  - 添加按钮 → `AddItemDialog.vue`

### Step 8: 表单 schema 编辑器
- `FormSchemaEditor.vue`:顶层 `<el-tabs>`:
  - Tab "护甲": NestedObject(game.hero.armor)
  - Tab "武器": NestedObject(game.hero.weapon)
  - Tab "背包": NestedList(game.hero.inventory)
- 空字段(hero 无武器等)显示 `<el-empty>` + "添加"按钮

### Step 9: 添加字段 / 添加 item modal
- `AddFieldDialog.vue`:`<el-dialog>` + `<el-form>`:
  - 字段名:`<el-input>` + `/^[a-zA-Z_][a-zA-Z0-9_]*$/` 校验
  - 类型:`<el-select>` (int/bool/str/float/null/list/dict)
  - 默认值:按类型生成
- `AddItemDialog.vue`:`<el-dialog>` + `<el-select>` 选 `__className`:
  - 常用 Item 模板:Food / Waterskin / VelvetPouch / ClothArmor / MagesStaff 等
  - 选中后填入默认模板 dict

### Step 10: raw JSON 编辑器(Monaco)
- `RawJsonEditor.vue`:
  - lazy import `@guolao/vue-monaco-editor`
  - 三个 editor:armor / weapon / inventory
  - 顶部工具栏:[应用] [格式化] [校验]
  - 应用:`JSON.parse` + 类型校验 + `__className` 检查 + 写 store + push history
  - 格式化:`JSON.stringify(JSON.parse(v), null, 2)`
  - 校验:仅显示语法错误位置
- 监听 bundle.game 变化,Monaco 未在编辑时自动重填

### Step 11: 撤销/重做 + 双向同步
- 顶部 `ActionBar.vue`:撤销/重做按钮(灰禁 when !canUndo/Redo)
- Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y 快捷键
- 触发 snapshot 时机:
  - 加载文件后(initial baseline)
  - 表单字段变更后 debounce 1s
  - raw JSON "应用" 前
  - 字段增删 / item 增删 前

### Step 12: 下载 + 重置
- `ActionBar.vue` 下载按钮:
  - POST /api/pack 拿 Blob
  - FileSaver / `<a download>`
  - 文件名:`{meta.name}-edited.zip`
- 重置按钮:`<el-popconfirm>` 二次确认 → bundle.resetToOriginal() + history.clear()

### Step 13: Flask prod 适配
- 改 `app.py`:加 `/` 和 `/assets/<path>` 路由
- 检测 `frontend/dist` 缺失返回 503 + 提示
- 删除 `templates/index.html`
- 更新 `tools/save-editor/README.md`:dev / prod 工作流

### Step 14: 构建 + 验证
- `cd frontend && npm run build`
- 检查 `dist/index.html` + `dist/assets/*` 生成
- `PORT=5001 python app.py` 启动,curl `http://127.0.0.1:5001/` 拿到 SPA HTML
- dist 大小 < 2MB(Monaco 单 chunk 较大,放宽到 1.5MB warning)

## Acceptance

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | 现有 spd_bundle.py 40 个 pytest 用例全绿 | `pytest -q` |
| 2 | Java 闭环 `SaveSlotIOPythonZipTest` 通过 | `SPD_ZIP_PATH=... ./gradlew :core:test` |
| 3 | `frontend/dist/` 不存在时,Flask `GET /` 返回 503 + 提示 | 手测 |
| 4 | `npm run build` 生成 dist/index.html + assets | 检查文件 |
| 5 | dev 模式 `npm run dev`(:5173)+ Flask(:5001)启动,Vite proxy `/api` 通 | curl `http://127.0.0.1:5173/api/parse` |
| 6 | prod 模式 `python app.py`(:5001)serve SPA,curl `/` 返回 SPA HTML | curl |
| 7 | 拖拽 / 点击上传 `/tmp/123.zip`,UI 显示 meta + hero + gold + depth 字段表 | 手测 |
| 8 | 数值字段改 hero.HP=999 + gold=99999,下载 zip,Java 闭环通过 | round-trip |
| 9 | 表单 schema 编辑器正确推断所有字段类型(bool×32 / int×24 / str×8) | 手测对照 PLAN 期望 |
| 10 | 表单改 inv[0].quantity=99 + armor.level=5,下载 zip,回读字段值正确 | round-trip |
| 11 | 表单字段用 `<el-switch>`(bool) / `<el-input-number>`(int/float) / `<el-input>`(str)正确渲染 | 手测 |
| 12 | `__className` 字段强制只读灰底,无删除按钮 | 手测 |
| 13 | 嵌套结构(MagesStaff.wand / VelvetPouch.inventory)正确递归渲染 | 手测 |
| 14 | 添加字段 modal:输入字段名 + 选类型,确认后字段出现 + pack 回读保留 | round-trip |
| 15 | 添加字段重名 → `<el-form>` 校验拒绝 | 手测 |
| 16 | 删除字段:`<el-popconfirm>` 二次确认,确认后字段消失 | 手测 |
| 17 | list item 拖拽排序:拖拽后顺序保留到 pack | round-trip |
| 18 | list item 增删:`AddItemDialog` 添加 / 卡片"删除"按钮 | round-trip |
| 19 | raw JSON Monaco 编辑器:语法高亮 + 折叠 + 智能缩进 | 手测 |
| 20 | raw JSON"应用"按钮:JSON 语法错误时 `<el-message.error>` 显示,不写 store | 手测 |
| 21 | 表单 ↔ raw JSON 双向同步:表单改 → Monaco 自动更新;raw JSON 应用 → 表单刷新 | 手测 |
| 22 | 撤销/重做:Ctrl+Z 撤销字段编辑,Ctrl+Shift+Z 重做 | 手测 |
| 23 | 撤销栈上限 50,超出丢最老 | 单测或代码路径验证 |
| 24 | 重置按钮:`<el-popconfirm>` 确认后回到 originalBundle,history 清空 | 手测 |
| 25 | 中文 locale 全 UI 中文化(确认弹窗 / placeholder / 按钮) | 手测 |
| 26 | 暗色主题默认开启,可切换 | 手测 |
| 27 | dist 总大小 < 2MB | `du -sh frontend/dist` |
| 28 | 真实 `/tmp/123.zip` 走完整 dev 流程,所有字段可编辑 + 下载 zip 通过 Java 闭环 | e2e |
| 29 | `templates/index.html` 已删除 | `ls tools/save-editor/templates/` 不存在 |
| 30 | `requirements.txt` 不变(后端无新依赖)| `git diff requirements.txt` 空 |

## 风险与备选

- **Monaco editor 大**(单独 ~3MB):
  - lazy load(路由级 / 组件级)
  - 备选:CodeMirror 6(~200KB),但 Vue 生态 wrapper 弱
  - **决策:lazy load + 调高 chunkSizeWarningLimit**
- **vuedraggable-next 维护状态**(社区分叉):
  - 备选:直接调 SortableJS
  - **决策:用 vuedraggable-next,失败再切**
- **TypeScript strict 遇到 GameBundle 任意字段**:
  - `GameBundle` 加 `[k: string]: unknown` 索引签名兜底
  - 字段访问统一 type narrow
  - **决策:`strict: true` + 索引签名**
- **表单 ↔ raw JSON 双向同步冲突**:
  - 不上 lock,后写胜出
  - raw JSON Monaco 在编辑时(onFocus)暂停自动刷新
  - **决策:用户责任,UI 提示**
- **Element Plus 全量包大**:
  - 用 `unplugin-vue-components` + `unplugin-auto-import` 按需加载
  - 备选:全量(简单)
  - **决策:MVP 全量,后续优化**
- **dev CORS 问题**:
  - Vite proxy `/api` 解决
  - 不开 Flask CORS
- **Flask prod SPA fallback**:
  - 本工具单页根路径,无需 fallback
  - 备选:加 `@app.errorhandler(404)` 兜底回 index.html
  - **决策:不加,简单**
- **Pinia history snapshot 内存**:
  - 上限 50 + deep clone `state.game`(典型 < 50KB)
  - 50 × 50KB = 2.5MB,可接受

## Out of scope(明确不做)

- 多语言 i18n(只中文)
- 后端 API 改动(保留 /api/parse + /api/pack)
- 桌面版独立打包(Electron / Tauri)
- 表单 schema 静态字段描述库(每个 Item 类的字段元数据)
- 跨设备同步 / 云端存档 / 用户账户
- JSON patch 优化(snapshot 已够)
- 实时协作编辑
- SSR / SEO
- 单元测试 / e2e 自动化(后续 PR 可加 Vitest + Playwright)
- 光暗主题切换持久化(localStorage 留给后续)
- 字段重命名(改名 + 保留值)
- 物品图标 / 预览
- 模板字段库(常用字段一键添加)

## 后续可加(不在本 PR)

- Vitest 单元测试(useFieldType / useFieldOps)
- Playwright e2e(全流程自动化)
- localStorage 持久化(last opened zip / theme / collapsed state)
- 字段重命名
- 物品模板库(常用 Item 一键添加)
- 表单 schema 字段元数据(中文名 + 描述 + 必填校验)
- CodeMirror 6 替代 Monaco(减小 bundle)
- shadcn-vue 替代 Element Plus(设计统一)

---

## Refinements (worker phase 1 — 校正与可执行细节)

> 本节是 feature_worker 在阶段 1 探索代码后追加,用于校正 dispatcher 初稿中
> 与现状不符的小点,并把关键步骤补到可执行粒度。Goal/Context/Architecture
> 框架不变。

### R1. 字段清单校正(基于 app.py HERO_FIELDS + 现有 templates/index.html)

dispatcher 初稿把 `hero.level` 当作字段名,但 SPD 实际命名是 `hero.lvl`,
而且漏了几个字段。**NumericFields.vue 实际渲染 14 个字段**(不是 PLAN
Acceptance #11 写的 12 个),分组如下:

**meta(5)**:
- `meta.name` — text (str)
- `meta.depth` — number (int)
- `meta.level` — number (int)
- `meta.hero_class` — **select**(WARRIOR / MAGE / ROGUE / HUNTRESS / CLERIC / DUELIST)
- `meta.version` — **只读**(int,force 到 896)

**game.hero(6)**:
- `hero.HP` — number (int)
- `hero.HT` — number (int)
- `hero.pos` — number (int)【PLAN 初稿漏】
- `hero.lvl` — number (int)【初稿写 `hero.level`,错】
- `hero.STR` — number (int)
- `hero.exp` — number (int)

**game top(3)**:
- `game.gold` — number (int)
- `game.challenges` — number (int,bitmask)
- `game.seed` — number (int,标注"改 seed 不删 depth 会出怪")
- `game.daily` — **switch**(bool)【PLAN 初稿漏,标注"daily=true 会被 isSaveAllowed 拒绝"】

合计 5 + 6 + 4 = **15 个字段**(game seed + daily 是 4 项,所以 5+6+4=15),
以代码 `HERO_FIELDS / META_FIELDS / GAME_TOP_FIELDS` 为单一事实源。
原 PLAN 说的 "12" 是简化措辞,实施时按本节 15 项为准。

### R2. types.ts 校正

```typescript
// JSON object type alias — 替代伪类型 `dict`(`dict` 不是 TS 关键字)
export type JsonObject = Record<string, unknown>

// __raw_files 是后端 /api/parse 返回的"完整 files dict",前端 opaque 透传
// 给 /api/pack。**不是字符串数组**,PLAN 初稿写错了。
export interface SaveSlotBundle {
  meta: SlotMeta;
  game: GameBundle;
  depths: Record<string, JsonObject>;  // key 是十进制 depth 数字字符串
  warnings?: string[];
  __raw_files: Record<string, JsonObject>;  // ← 校正:dict,不是 string[]
}

export interface HeroBundle {
  __className: string;
  HP: number;
  HT: number;
  pos: number;
  lvl: number;          // ← 校正:lvl 不是 level
  STR: number;
  exp: number;
  armor: Item | null;
  weapon: Item | null;
  inventory: Item[];
  [k: string]: unknown;
}
```

### R3. /api/parse + /api/pack 契约(单一事实源 app.py)

- `POST /api/parse` multipart `file` 字段 → `{ meta, game, depths, warnings, __raw_files }`
  - `__raw_files` 包含 raw_files(完整 files dict,含 depthN-branchM.dat 等不暴露字段)
  - 前端 opaque 透传,不可视化
- `POST /api/pack` JSON `{ meta, game, depths, __raw_files, force_meta_version? }` → zip bytes
  - `force_meta_version` 默认 896,传 `null` 或 `false` 跳过 force
  - Flask 返回 `send_file` `application/zip` + `slot.zip` filename

### R4. FormSchemaEditor 字段 schema(对照现有 vanilla JS renderField)

`inferType` 已在 composable 设计中,完全对齐现有 `templates/index.html:622`:

```typescript
// 与 vanilla 完全等价,迁移期间保证行为不变
export function inferType(v: unknown): FieldType {
  if (v === null) return 'null';
  if (typeof v === 'boolean') return 'bool';
  if (typeof v === 'number') return Number.isInteger(v) ? 'int' : 'float';
  if (typeof v === 'string') return 'str';
  if (Array.isArray(v)) return 'list';
  if (typeof v === 'object') return 'dict';
  return 'unknown';
}

export const FORM_TYPE_DEFAULTS: Record<Exclude<FieldType, 'unknown' | 'list' | 'dict'>, unknown> = {
  int: 0,
  bool: false,
  str: '',
  float: 0.0,
  null: null,
};
```

`FieldRow.vue` 行为(逐条对照 `renderField` line 646-763):
- bool → `<el-switch>`(对照 `input.type = "checkbox"; input.checked`)
- int → `<el-input-number :step="1" :precision="0">`,**严格整数校验**
  (`/^-?\d+$/.test(raw)` + `Number.isInteger(n)`,拒绝 "1.5"/"0x10"/"abc")
- float → `<el-input-number :step="0.01">`,`Number.isFinite` 校验
- str → `<el-input>`
- null → `<el-input placeholder="(null — 留空保持 null)">`,空值=保留 null
- unknown → `<el-input disabled>` 显示 `String(value)`(适配 BigInt / undefined 兜底)
- `__className` → `<el-input readonly>` + `String(value)` + 灰底 + **无删除按钮**
- list/dict → FieldRow 不渲染,父组件 NestedObject/NestedList 接管
- 所有非 `__className` 行右侧带 `<el-popconfirm>` 删除按钮

### R5. NestedObject / NestedList 渲染细节

- NestedObject(dict):`<el-collapse>` 包,内部 v-for 按 key 字母序排,但 `__className` 永远第一
- NestedList(list):`vuedraggable-next` + drag handle + 每项 `<el-card>` shadow + 删除按钮
- 嵌套深度无限制(MagesStaff.wand / VelvetPouch.inventory 都靠递归)

### R6. AddItemDialog __className 模板库

list 添加 item 时,`AddItemDialog.vue` 默认选项是常见 Item(无后端元数据):

```typescript
const ITEM_TEMPLATES: Record<string, () => Item> = {
  Food: () => ({ __className: 'com.shatteredpixel.shatteredpixeldungeon.items.food.Food', quantity: 1 }),
  Waterskin: () => ({ __className: '...Waterskin', quantity: 0, drops: [] }),
  VelvetPouch: () => ({ __className: '...VelvetPouch', items: [] }),
  ClothArmor: () => ({ __className: '...armor.ClothArmor', tier: 1, level: 0, cursed: false, glyph: null }),
  MagesStaff: () => ({ __className: '...MagesStaff', tier: 1, level: 0, wand: null }),
  // 完整模板在 AddItemDialog.vue 内,可后续扩展
};
// 用户也可手填任意 `__className` 字符串,默认模板用 `{ __className: '...' }`
```

### R7. 撤销/重做实现细节(避免 Pinia watch 死循环)

- snapshot 的对象是 `{ meta, game }`(**不包含 depths**,depths 当前 round-trip 不暴露编辑),
  覆盖 NumericFields 表单 + 表单 schema 所有可编辑路径
- `pushSnapshot()` 实现用 `structuredClone`(Node 17+/Chrome 98+ 支持)或
  `JSON.parse(JSON.stringify(...))` 兜底
- **Transaction 时机(关键)**:用 `beginEdit()` 模式 — 在每次写入 store **之前**
  调用,内部维护 `inTransaction` flag:
  - 首次调用:`pushSnapshot()` capture pre-change 入 undoStack,设 flag,
    启动 1s 定时器到时清 flag
  - 1s 内的后续调用:flag 已设,跳过 push(合并 transaction)
  - 1s 后定时器触发清 flag,下次 `beginEdit()` 起始新 transaction
- 显式触发 `pushSnapshot()` 的场景(无 debounce,直接 capture pre-change):
  - FileLoader 加载成功后(baseline)
  - raw JSON "应用"前
  - 字段增删 / item 增删前
- undo/redo 写回时,设置 `isApplying = true` flag,写完后 `setTimeout(0)` 清零,
  避免 watch 重新触发 beginEdit;同时把 `meta` + `game` 一并写回
- 上限 50,超出 shift 最老

### R8. 双向同步(Monaco ↔ Form)实现策略

- Monaco 三 editor(armor / weapon / inventory)各自维护本地 `text` ref
- `isEditing[key]` 状态:
  - **mount 时只 store editor instance,不设 isEditing**(否则 watcher 永远跳过刷新)
  - `onDidFocusEditorText` → `isEditing[key] = true`
  - `onDidBlurEditorText` → `isEditing[key] = false`
  - 用户在 Monaco 内编辑(`@update:value` 触发)时,也置 true 直到 blur
- watch `bundle.game.hero.{armor,weapon,inventory}`(deep)若 `!isEditing[key]`,
  把 JSON.stringify 后写回 Monaco text(覆盖)
- "应用"按钮:`JSON.parse(text)` + 严格类型校验(`hero.armor` 必须 dict、
  `weapon` 必须 dict|null、`inventory` 必须 Array),失败 `<el-message.error>` 显示
  具体行号 + 不写 store;成功 `pushSnapshot()` 后写 store,**然后** `isEditing[key] = false`
  让 watch 能在下一次外部修改时刷新文本

### R9. 关键不可动约束(从 README + spd_bundle.py 摘出)

实施时**必须保留**:
- `meta.bundle` 在 zip 内永远是第一 entry
- `meta.version` 默认 force 到 896
- `__className` 字段 round-trip 必须保留
- `__raw_files` 不透明,前端不解释
- entry name 校验 `^[A-Za-z0-9_.\-]+$`,不可含 `/ \ :`
- backend 40 个 pytest 必须绿;`SaveSlotIOPythonZipTest` 必须绿

### R10. Acceptance 校正

- #9 "表单 schema 编辑器正确推断所有字段类型(bool×32 / int×24 / str×8)":
  计数依赖具体样本 zip。改为**"对样本 zip(`/tmp/123.zip`)中每个 JSON 字段,
  inferType 返回的 type 都能在 FieldRow 中渲染对应控件"** — 不再硬编码计数
- #11 "12 个数值字段":实际 15(见 R1),验收按 15 计
- #14 "添加字段重名拒绝":校验时机是 modal 关闭前,字段名 trim 后 case-sensitive 比较
- #15 "删除字段 popconfirm":`__className` 字段不显示删除按钮(自动满足)
- #22 "撤销/重做":snapshot 覆盖 `{ meta, game }`,改 meta.name / hero.HP 都能 Ctrl+Z 撤回
- #27 "dist < 2MB":Monaco editor 单 chunk ~3MB,**放宽到 < 5MB**(lazy load 后
  首屏 chunk < 800KB),如实测超 5MB 再考虑 CodeMirror 6
- #5 "Vite proxy /api":dev 模式访问 `http://127.0.0.1:5173/api/parse` 应反代到 5001

### R11. 启动验证流程(本地手动)

工作流分两路并行(双终端):

**Terminal A — 后端 Flask**:
```bash
cd tools/save-editor
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
PORT=5001 python app.py
```

**Terminal B — 前端 Vite dev**:
```bash
cd tools/save-editor/frontend
npm install
npm run dev    # 启动 :5173
```

浏览器访问 `http://127.0.0.1:5173`,上传 `/tmp/123.zip`,逐字段编辑 → 下载。

prod 验证:
```bash
cd tools/save-editor/frontend && npm run build
cd .. && PORT=5001 python app.py
# 访问 http://127.0.0.1:5001/ 应返回 SPA HTML
```

### R12. PLAN 落盘分块清单(可执行粒度)

| Step | 关键产出 | 验证 |
|------|---------|------|
| 1 | frontend/package.json + vite.config.ts + tsconfig.json | `npm run dev` 启 :5173 |
| 2 | main.ts 注册 EP + zh-cn + dark css-vars | 空白页暗色 |
| 3 | types.ts + 3 stores(bundle/history/ui) | TS 编译过 |
| 4 | FileLoader.vue + api.ts | 上传 zip 拿到 meta+game |
| 5 | NumericFields.vue 15 字段 | 字段显示正确 |
| 6 | useFieldType.ts + FieldRow.vue | 单字段编辑 |
| 7 | NestedObject.vue + NestedList.vue + vuedraggable | 嵌套递归 OK |
| 8 | FormSchemaEditor.vue 3 tabs(armor/weapon/inventory) | tabs 切换 OK |
| 9 | AddFieldDialog.vue + AddItemDialog.vue | 添加 modal OK |
| 10 | RawJsonEditor.vue + Monaco lazy | raw 编辑 + 应用 |
| 11 | ActionBar.vue + undo/redo + Ctrl+Z/Y | 撤销重做 OK |
| 12 | 下载 zip + 重置 popconfirm | round-trip |
| 13 | app.py 加 / + /assets + 删 templates/index.html | prod serve OK |
| 14 | npm run build + 真实 /tmp/123.zip e2e × 2(dev/prod) | Acceptance 30/30 |
