<!-- cao-memory:begin -->
<cao-memory>
## Context from CAO Memory
- [project] codex-reviewer-timeout-workaround: codex_reviewer CAO terminal 创建超时(MCP 客户端 30s 读超时 < codex CLI v0.142.0 冷启动 ~60s+),`assign("codex_reviewer", ...)` 必然 `success=false`(HTTP read timeout 30s)。

**Workaround**:直接 shell 跑 `codex exec --sandbox read-only "<评审 PLAN 指令>"`,smoke 通过即采用,不走 codex_reviewer terminal。M5a/b/c/M4e/M6-fast 全用此路径。

**派 worker 时主动告知**:新 feature_worker 派发后,`send_message` 发 [HEADS-UP] 让它用 codex exec workaround,别 assign codex_reviewer(必失败 + 造孤儿终端烧配额)。worker 协议要求 codex 评审 PLAN,但 codex_reviewer terminal mode 在当前 codex 版本下不可用。

Why: codex CLI 冷启动 >30s,CAO MCP 客户端硬读超时 30s,根因超时配置不匹配。调超时理论上可(选项 A),但 codex exec workaround 更稳且已验证。
How to apply: worker 报 `[BLOCKED] codex_reviewer 超时` → 回 `[RESOLVED]` 给 codex exec 指令;或新派 worker 前主动发 [HEADS-UP]。相关:[[m6-bridge-pipeline]]
</cao-memory>
<!-- cao-memory:end -->
