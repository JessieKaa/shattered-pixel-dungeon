# PLAN: Save Slot Editor (minimal Python webapp)

**Slug**: `save-editor`
**Branch**: `feature/save-editor` (based on `feature/local-save-slots`)
**Date**: 2026-06-30 (rev3: 闭环 #8 真实 pack→JUnit、64MB 用实际解压字节、#11 文案修正)

---

## Goal

一个**最小可用**的 Python + Flask web 存档编辑器,放 `tools/save-editor/`。流程:用户在游戏里 export 一个 slot zip → 上传到本工具 → 浏览器里改字段 → 下载新 zip → 在游戏里 import 回去。

不做桌面 GUI,不做 APK 内联编辑(release 包不可访问),不做云同步。**只做最小可用的离线 web 编辑**。

## Context

- SPD Bundle 格式(已实测):**libgdx Json 文本**,大文件 GZIP 包一层,小文件(meta.bundle)直接 JSON
- 文件结构(一个 slot):
  - `meta.bundle`(~100B,未压缩 JSON):`{name, version, depth, level, hero_class, saved_at}`
  - `game.dat`(~3K,GZIP 压缩 JSON):seed / hero(HP/HT/pos/class/subClass/talents/STR/exp/attackSkill/defenseSkill)/ **gold**(顶层,不是 hero 内)/ challenges / daily 标志 / init_ver 等
  - `depth{N}.dat`(~2K,GZIP 压缩 JSON,可能多个):楼层状态
- 硬约束(已对照 `SaveSlotIO.java` 源码):
  - `meta.version` 必须 == `Game.versionCode`(当前 **896**),否则 `SaveSlotService.loadFromSlot` 拒绝(`SaveSlotService.java:135`)
  - zip entry 名必须 root-level、纯 `[A-Za-z0-9_.\-]+`,**且**不得是 `.` / `..` / 含 `/` `\` `:` / 重复(SaveSlotIO.java:447-456)
  - zip 内**禁止 directory entry**(SaveSlotIO.java:168-171)
  - entry 数 <= 64(SaveSlotIO.java:81 `MAX_ENTRY_COUNT`)
  - 解压后总 bytes <= 64MB(SaveSlotIO.java:82 `MAX_TOTAL_BYTES`)
  - `meta.bundle` 必须存在(SaveSlotIO.java:204-206)
  - `__className` 字段是反射用,**不能让用户改**
- fork 现状:`export/import` 已实现(SaveSlotIO 的 path-traversal / zip-bomb 防御已 lock)。本编辑器输出必须**通过 import 校验**,这是 acceptance 硬指标。

## Stack

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | Python 3.10+ | 项目其他工具也可用 Python,统一栈 |
| Web 框架 | **Flask 3.x** | 单依赖、dev server 够用、不需 async |
| 模板 | Jinja2(Flask 自带) | 单 HTML 页面够最小版本 |
| 前端 | vanilla HTML + JS,**无构建工具无 npm** | 单页足够,引入 vite/react 是过度设计 |
| gzip/json/zipfile | stdlib | 不引入额外依赖 |
| 测试 | **pytest** | 简单、覆盖 round-trip |
| 依赖管理 | `requirements.txt`(只 `flask>=3.0,<4.0` + `pytest>=8.0`) | 不引入 poetry/uv,保持最小 |

**禁用**:`pydantic`(数据形状太动态,手写 dict 验证更清楚)、`requests`(用不到)、`click`(Flask 已自带 CLI)、`python-magic`(用 stdlib `gzip` magic `\x1f\x8b` 检测)。

## Architecture

```
浏览器(vanilla JS)              Flask app.py                   spd_bundle.py(纯逻辑)
───────────────────              ─────────────                  ─────────────────────
[选择 zip]   ─────POST──▶        GET  /           ─────▶        read_slot_zip(bytes)
[选择 .dat]                      POST /api/parse                read_bundle(bytes) -> dict
[选择 .bundle]                       │                          write_bundle(dict) -> bytes
       │                            │                          pack_slot_zip(files) -> bytes
   [解析显示字段表格]  ◀──JSON──     │                          safe_entry_name(str) -> bool
       │                            │
[改 hero.HP=99999]               POST /api/pack
[勾选 "强制 meta.version=896"]         │
[下载 zip]   ◀────binary zip─────  ◀┘
```

注入方向:`spd_bundle.py` 不依赖 Flask,纯逻辑可单测。`app.py` 调用 `spd_bundle`,不反向。

## Files

### `tools/save-editor/spd_bundle.py` (核心纯逻辑)

公开 API:

```python
def read_bundle(raw: bytes) -> dict:
    """检测 gzip magic (0x1f 0x8b):是则 gzip.decompress,然后 utf-8 decode + json.loads。
    所有失败统一抛 BundleError,具体场景:
    - 空 bytes 或长度 < 2(无法判 magic)
    - 伪 gzip 头(前两字节匹配但 gzip.decompress 抛 OSError)
    - gzip 解压后字节流不是合法 UTF-8(UnicodeDecodeError)
    - JSON decode 失败(JSONDecodeError / ValueError),**包括 BOM 开头** —— JSON spec 不允许 BOM,直接报错,不做剥离
    - 解出来的值不是 dict(顶层必须是 object)
    """

def write_bundle(data: dict, *, compress: bool | None = None) -> bytes:
    """compress=None 时:未压缩 json bytes 长度 > 256 用 gzip,否则裸 json。
    json.dumps(separators=(',', ':'), ensure_ascii=False)。编码 utf-8,**无 BOM**。
    compress=True / False 显式控制。"""

def read_slot_zip(zip_bytes: bytes) -> dict[str, dict]:
    """解 zip,返回 {filename: parsed_dict}。
    与 SaveSlotIO.readSlotFromStream 等价校验:
    - directory entry (zipinfo.is_dir()) → BundleError('invalid_zip_entry')
    - entry name 不通过 safe_entry_name → BundleError('invalid_zip_entry')
    - duplicate entry name(seen set)→ BundleError('invalid_zip_entry')
    - entry count > 64 → BundleError('too_many_entries')
    - **实际解压累计 bytes > 64MB(rev3:不用 central directory 的 file_size,
      用 z.read(8192) 流式累计,与 SaveSlotIO 行 187 `totalBytes += n` 等价;
      异常 zip 即便 file_size 伪造小,实际解出大内容也会被截)** →
      BundleError('zip_too_large')
    - 没有 meta.bundle → BundleError('missing_meta')
    所有文件名通过 safe_entry_name 的都尝试 read_bundle;非 .bundle/.dat 后缀也不跳过。
    """

def pack_slot_zip(files: dict[str, dict], *, meta_version: int = 896) -> bytes:
    """打包 zip(与 SaveSlotIO.writeSlotToStream 等价 + 额外 safe-guard)。
    - 校验所有 entry 名通过 safe_entry_name,否则 BundleError
    - 校验 entry 数 <= 64
    - 校验累计 write_bundle 输出 bytes <= 64MB
    - 必须含 'meta.bundle',否则 BundleError('missing_meta')
    - meta.bundle 写第一个 entry
    - 其余 entry 按字典序排序(SaveSlotIO.writeSlotToStream:109 行为)
    - 若 meta_version 非 None:files['meta.bundle']['version'] = meta_version(强制覆盖)
    - 单个 entry 用 write_bundle(compress=None),按大小自动选 gzip/裸 JSON
    """

SAFE_ENTRY_NAME = re.compile(r"^[A-Za-z0-9_.\-]+$")
def safe_entry_name(name: str) -> bool:
    """与 SaveSlotIO.isSafeEntryName 一致:
    非 null、非空、不是 '.' / '..'、不含 '/' '\\' ':'、regex 全匹配。
    duplicate 检测由 read_slot_zip / pack_slot_zip 调用方处理(用 seen set)。"""

class BundleError(Exception):
    """所有 spd_bundle 内部错误。message 建议带原因(如 'invalid_zip_entry: ../evil')。"""
```

**关键设计**:read_slot_zip 与 pack_slot_zip **双向都做 import 等价校验**,这样 round-trip 自洽。任何违规在编辑器侧就报错,不会延后到游戏 import 才暴露。

### `tools/save-editor/app.py` (Flask 主入口)

- `GET /` → 渲染 `index.html`
- `POST /api/parse`(multipart):接收 1 个文件(`.zip` / `.dat` / `.bundle`),调 `spd_bundle` 解析,返回 JSON:
  ```json
  {"meta": {...}, "game": {...}, "depths": {"1": {...}, "5": {...}}, "warnings": ["..."]}
  ```
  - 单文件按类型分流:zip → read_slot_zip;单 .bundle/.dat → read_bundle 包成 `{'<filename>': dict}`
  - 输出分类:`meta` = files['meta.bundle'];`game` = files['game.dat'];`depths` = `{n: files[f'depth{n}.dat']}` 抽取层号
  - warnings 字段:若 `game.daily == true` 加警告"daily 修改会被 SaveSlotService.isSaveAllowed 拒绝";若 `meta.depth != max(depths key)` 加警告等
- `POST /api/pack`(JSON body):接收 `{meta, game, depths, force_meta_version?}`,调 `spd_bundle.pack_slot_zip`,返回 `Content-Disposition: attachment; filename=slot.zip` 的 zip
- 错误统一:`@app.errorhandler(BundleError)` 返回 400 + `{"error": str(e)}`;其他 Exception 返回 500(开发期 dev server 自带 traceback)
- 上传大小限制 **10MB**(`app.config['MAX_CONTENT_LENGTH']`),SPD slot 实测 < 100K,留 100x 余量
- 启动:`python app.py`(`if __name__ == '__main__': app.run(host='127.0.0.1', port=5000, debug=True)`)

### `tools/save-editor/templates/index.html`

单页面,3 块:
1. **上传区**:`<input type="file" accept=".zip,.dat,.bundle">` + 解析按钮
2. **字段表格**(只暴露白名单字段,**绝不暴露 `__className`**)
3. **操作区**:
   - 复选框 `force_meta_version=896`(默认勾选)
   - `下载 zip` 按钮 → POST /api/pack → 触发浏览器下载
- vanilla JS fetch,无第三方库

**字段白名单**(已核对源码位置):

| 字段路径 | 类型 | 说明 |
|---|---|---|
| `meta.name` | text | 槽位名 |
| `meta.depth` | number | 当前楼层 |
| `meta.level` | number | 英雄等级(冗余于 hero.lvl,但 meta 自己存) |
| `meta.hero_class` | select | WARRIOR / MAGE / ROGUE / HUNTRESS(SPD HeroClass 枚举) |
| `game.hero.HP` | number | 当前血量,允许 > HT 但 UI warn |
| `game.hero.HT` | number | 最大血量 |
| `game.hero.pos` | number | 英雄位置(cell index) |
| `game.hero.lvl` | number | 英雄等级 |
| `game.hero.STR` | number | 力量(Hero.java:229,序列化键 `STRENGTH` → JSON "STR") |
| `game.hero.exp` | number | 经验值(Hero.java:234,序列化键 `EXPERIENCE` → JSON "exp") |
| `game.gold` | number | **顶层 game.dat 字段**(Dungeon.java:199/641),不是 hero.gold |
| `game.challenges` | number | bitmask int |
| `game.seed` | number | long int,warn "改了不同步删 depth 会有怪现象" |
| `game.daily` | checkbox | **强警告:daily 修改会让 isSaveAllowed 拒绝 save/load,默认只读展示,如要改必须先确认** |

可选高级字段(放在 "Advanced" 折叠区,默认收起):
- `game.hero.attackSkill`(Hero.java:214 私有但序列化)
- `game.hero.defenseSkill`(Hero.java:215 私有但序列化)

**修订记录**:
- `gold` 从 `game.hero.gold` 改到 `game.gold`(rev2 修正:reviewer 指出顶层)
- 新增 `game.hero.STR` / `game.hero.exp`(rev2 补充常用字段)
- `game.daily` 标记为强警告(rev2:reviewer 指出风险)

`depths` 只读展示(每层 cell 数 / depth 值)。

### `tools/save-editor/requirements.txt`

```
flask>=3.0,<4.0
pytest>=8.0,<9.0
```

### `tools/save-editor/tests/test_spd_bundle.py`

pytest 用例(rev3,共 17 个,覆盖 reviewer 要求的所有边界):

1. `test_round_trip_meta_bundle_uncompressed` —— 小 dict(<256B)写出 + 读回等价
2. `test_round_trip_game_dat_gzipped` —— 大 dict(>256B)写出 + 读回等价,gzip magic 头部正确
3. `test_pack_slot_zip_meta_first` —— 打包出来的 zip 第一个 entry 是 `meta.bundle`
4. `test_pack_slot_zip_forces_version` —— `meta_version=896` 时,无论输入 meta.version 是什么,输出是 896
5. `test_safe_entry_name_rejects_traversal` —— `../evil` / `a/b` / `C:\x` / `a:b` 都拒绝,`.` / `..` 也拒绝
6. `test_read_slot_zip_rejects_too_many_entries` —— 65 个 entry 拒绝
7. `test_read_bundle_invalid_json_raises_bundle_error` —— 损坏 JSON 明确异常类型
8. `test_pack_imported_back_into_save_slot_io_white_list` —— 打包 zip 所有 entry 名通过 `safe_entry_name`
9. `test_read_bundle_empty_bytes_raises` —— 空 bytes / 1 字节 bytes 都抛 BundleError
10. `test_read_bundle_pseudo_gzip_header_raises` —— 前 2 字节 `\x1f\x8b` 但后续损坏,抛 BundleError
11. `test_read_bundle_bom_rejected` —— UTF-8 BOM 开头的 JSON 抛 BundleError(不做剥离)
12. `test_read_slot_zip_rejects_directory_entry` —— 包含目录 entry 的 zip 抛 BundleError
13. `test_read_slot_zip_rejects_duplicate_entry` —— 同名 entry 出现 2 次,抛 BundleError
14. `test_read_slot_zip_rejects_zip_bomb` —— 构造**真实解压 > 64MB** 的 entry(rev3:大量零字节压缩,`gzip.compress(b'\x00' * 70_000_000)` 嵌入 zip;不能只伪造 central directory file_size,流式累计会按实际解出字节判)
15. `test_read_slot_zip_missing_meta` —— 没有 meta.bundle 抛 BundleError
16. `test_pack_then_read_round_trip_mixed` —— 合成 mixed zip(meta 裸 + game.gz + depth1.gz),pack 出来再 read 回来字段等价
17. `test_pack_rejects_unsafe_entry_name` —— files 含 `'../evil'` key 抛 BundleError

### `tools/save-editor/tests/test_save_slot_io_cross_check.py` (rev3:真实闭环)

**Acceptance #8 改为真实闭环**:Python 侧 `pack_slot_zip` 生成真实 zip 落到临时文件,
JUnit fixture 通过 system property 拿到该文件路径,调 `SaveSlotIO.readSlotFromStream`
断言 `result.ok == true`、`result.meta.version == 896`、staging 目录已清理(无泄漏)。

流程:
1. Python `pack_slot_zip({...})` → 写 `/tmp/spd-test-<uuid>.zip`
2. Python `subprocess.run([str(GRADLEW), ':core:test', '--tests',
   '*SaveSlotIOPythonZipTest'], cwd=REPO_ROOT,
   env={**os.environ, 'SPD_ZIP_PATH': str(zip_path)})` —— **用环境变量,
   不用 `-D`**(gradle `test` task 默认不把任意 system property 透传给 fork JVM,
   需改 core/build.gradle;env var 方案零侵入)
3. JUnit fixture 读 `System.getenv("SPD_ZIP_PATH")`,new FileInputStream,调
   `SaveSlotIO.readSlotFromStream(in, "testslot")`
4. `try/finally`:成功路径断言 `result.ok && result.meta.version==896`,
   然后**显式** `SaveSlotIO.cleanupStaging(result.stagingRelPath)`(readSlotFromStream
   语义是 success 时保留 staging,调用方必须 cleanup)
5. finally 里再扫 `save_slots/` 目录,断言无 `.import-*` 残留(防泄漏)
6. Python 读 gradle exit code,断言 0

**repo_root 解析**(`tests/conftest.py`):
```python
REPO_ROOT = Path(__file__).resolve().parents[3]  # tools/save-editor/tests/→repo root
GRADLEW = REPO_ROOT / 'gradlew'
```

**备选**(若 gradle 跨工程跑太重 / 慢):JUnit 独立 stage 跑,Python 测试 skip + 标 `@pytest.mark.java_cross_check`,CI 文档说明。worker 实施时二选一,闭环优先。

### `tools/save-editor/README.md`

```bash
cd tools/save-editor
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python app.py  # 启动,默认 :5000
# 浏览器打开 http://127.0.0.1:5000
```

测试:`pytest -q`

### `tools/save-editor/.gitignore`

```
.venv/
__pycache__/
*.pyc
.pytest_cache/
```

## Steps

### Step 1: `spd_bundle.py` 纯逻辑 + 单测

先写模块 + tests。每写一个函数,补对应 pytest 用例。`pytest -q` 全绿才进 Step 2。

**关键决策**:
- compress 自动检测用 magic bytes,不依赖扩展名
- json 序列化用 `separators=(',', ':')` 紧凑格式
- `meta_version` 参数允许调用方关闭(`meta_version=None`),默认 896
- read_slot_zip / pack_slot_zip **都做 import 等价校验**(rev2)

### Step 2: Flask `app.py` + 路由

- `app.py` 里硬编码 `META_VERSION = 896`
- 上传大小限制:**10MB**
- 错误处理统一:`@app.errorhandler(BundleError)` 返回 400
- 启动:`flask run` 或 `python app.py`

### Step 3: `index.html` UI

vanilla JS,3 个区块按 Architecture 段描述。**字段表格用 `<table>` + input**,简单直白。

字段白名单见上面"字段白名单"表。`__className`、`buffs`、`items`、`talents` 等深度嵌套字段**不暴露**。`game.daily` 默认只读展示,如允许编辑加显著警告。

### Step 4: round-trip 集成测试

- 用 spd_bundle 合成一个完整 slot zip(meta + game + 1 depth),assert pytest 通过
- 手测:启动 `python app.py`,浏览器上传该 zip,改字段,下载新 zip
- 用 Python 解压下载的 zip,确认所有 entry 名通过 `safe_entry_name`,meta.version == 896
- **额外**(rev2):合成 zip 也通过 `SaveSlotIO.readSlotFromStream` 的 JUnit fixture 验证(若选了 JUnit 路径)

### Step 5: 文档

- README.md 写启动步骤
- 在 SPD 项目根 `CLAUDE.md` 末尾加一节 "Save Editor" 说明位置(可选,worker 决定)

## Acceptance

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | `pytest -q` 全绿,至少 17 个用例 | `cd tools/save-editor && pytest -q` |
| 2 | `python app.py` 启动不报错,curl `GET /` 返回 200 + HTML | `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:5000/` |
| 3 | 合成 slot zip 解析后字段正确 | `POST /api/parse` 返回 hero.HP == fixture 值 |
| 4 | 编辑后 pack 出来的 zip,所有 entry 名通过 `safe_entry_name` | Python 解 zip + regex 校验 |
| 5 | pack 出来的 zip `meta.bundle` 第一个 entry | `zipfile.ZipFile(...).infolist()[0].filename == 'meta.bundle'` |
| 6 | pack 出来的 zip `meta.version == 896`(默认 force) | 读第一个 entry,json 解析,断言 |
| 7 | 上传真机 export 的 zip 能解析(可读 game.dat 的 GZIP JSON) | 用真机 fixture 验证(可选,无设备则合成 zip 替代) |
| 8 | pack 出来的 zip 通过 fork SaveSlotIO 的 import 校验 | **rev3 真实闭环**:Python `pack_slot_zip` 生成真实 zip → 写临时文件 → JUnit fixture 通过 `-Dspd.zip.path=...` 读 → 调 `SaveSlotIO.readSlotFromStream` 断言 `ok==true`、`meta.version==896`、无 staging 泄漏;Python 用 `subprocess` 跑 `repo_root/gradlew`(显式绝对路径,cwd=repo_root) |
| 9 | 单文件上传支持 `.dat` / `.bundle` / `.zip`,正确自动检测 gzip | pytest 用例覆盖 |
| 10 | `__className` 字段**不**在可编辑字段里 | UI review,grep `__className` 不应在 HTML input name 出现 |
| 11 | **生产代码**全部在 `tools/save-editor/`;**测试 fixture**允许在 `core/src/test/java/`(rev3 文案修正);Android build 不受影响 | `./gradlew :android:assembleDebug` 仍通过;`core/src/main/` 无改动 |
| 12 | 不依赖任何 `requests` / `pydantic` / `click` 等三方包(除 flask/pytest) | `pip freeze` 输出检查 |
| 13 | rev3:read_slot_zip 拒绝 directory entry | `test_read_slot_zip_rejects_directory_entry` |
| 14 | rev3:read_slot_zip 拒绝 duplicate entry | `test_read_slot_zip_rejects_duplicate_entry` |
| 15 | rev3:read_slot_zip 拒绝 total **实际解压** bytes > 64MB(流式累计,非 file_size) | `test_read_slot_zip_rejects_zip_bomb` |
| 16 | rev3:read_slot_zip 拒绝 missing meta.bundle | `test_read_slot_zip_missing_meta` |
| 17 | rev3:pack 不丢失合法 depth 文件 | round-trip test 覆盖 depth1 + depth5 |

## 风险与备选

- **GZIP vs 裸 JSON 检测错误**:已用 magic bytes,这个风险缓解。伪 gzip 头(`\x1f\x8b` 后跟损坏数据)由 `gzip.decompress` 抛 OSError → BundleError 兜底。
- **用户改出非法值**:HP 改负数、seed 改字符串等。MVP 在 UI 层做最小校验(number input 强制类型),后端 `pack` 仍信任前端(本地工具,不算安全边界)。但 `meta.version` 强制 896,不允许用户改。
- **真机 export 出来的 zip 没有 meta.bundle 第一个**:fork 的 SaveSlotIO 强制 meta.bundle 第一个 entry,**编辑器 pack 时也要强制**(已在 Acceptance #5 验证)。
- **`depth{N}.dat` 不让编辑**:MVP 只读。后续迭代可加 raw JSON 编辑器。
- **依赖 flask dev server**:MVP 跑 dev server 够用,生产部署 gunicorn 不在范围。
- **JUnit fixture 跨工程跑(rev3 风险)**:Python test 通过 subprocess 调 `{repo_root}/gradlew` 速度慢(首次 30s+)且要求 cwd=repo_root(`tests/conftest.py` 里 `REPO_ROOT = parents[3]` 解析)。备选:JUnit 独立 CI stage,Python pytest 用 `@pytest.mark.java_cross_check` 标记跳过,文档说明。
- **64MB 检测绕过(rev3)**:用 central directory `file_size` 不够(可伪造),改为流式 `z.read(8192)` 累计,与 SaveSlotIO 行 187 完全等价。代价:必须读完每个 entry 才能判定,但 slot zip 平均 < 100K,可接受。

## Out of scope(明确不做)

- 桌面 GUI(tkinter / PyQt)
- APK 内联编辑(release 包不可访问)
- 完整 SPD Bundle schema 校验(字段太多,MVP 只暴露 ~14 个常用字段)
- 多 slot 批量编辑
- 自动 patch `init_ver` / `version`(只 force meta.version = 896)
- depth{N}.dat 字段编辑(只读展示)
- undo / redo / history
- 用户认证(本地工具,不需要)
- 部署到云 / Docker 化
- 国际化(UI 文案硬编码,中文为主,英文可后加)

## 后续可加(不在本 PR)

- depth editor(读 raw JSON + textarea 编辑)
- schema-based 表单(自动从 Bundle 字段生成 input)
- diff 视图(原始 vs 编辑后)
- 真机 import 一键验证(调 adb)

## Pending Issues (rev3,实施时必须遵守)

第 3 次 codex 评审给出的最后 3 点实施层要求,作为硬性 checklist:

1. **#8 gradle 参数传递用环境变量,不用 `-D`**:`core/build.gradle` 的 `test` task 默认不把任意 system property 透传给 fork JVM,需改 gradle 配置。零侵入方案是 Python subprocess 传 `env={'SPD_ZIP_PATH': path}`,JUnit 用 `System.getenv("SPD_ZIP_PATH")`。
2. **#8 JUnit fixture try/finally 清 staging**:`SaveSlotIO.readSlotFromStream` 成功时**保留** staging 调用方必须 commit/cancel/cleanup。fixture 在断言 `result.ok && version` 后,显式调 `SaveSlotIO.cleanupStaging(result.stagingRelPath)`,再扫 `save_slots/` 确认无 `.import-*` 残留。
3. **test_read_slot_zip_rejects_zip_bomb 用真实大解压 entry**:rev3 改流式累计后,测试要构造**实际解压 > 64MB** 的内容(如 `gzip.compress(b'\x00' * 70_000_000)` 嵌入 zip),不能只伪造 central directory `file_size`(流式累计会绕过 file_size 直接读真实字节)。

实施完成时,这 3 点必须全部验证通过。
