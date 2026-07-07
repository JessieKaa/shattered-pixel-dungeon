-- M8d3 placeholder: tier 3/4 talents are deferred past this MVP. The
-- register_talent tier guard rejects tier outside [1,2] (LuaEngine logs via
-- Gdx.app.error and skips — never throws). This script exercises that reject
-- path so a load test can assert "rejected but did not crash".
--
-- The id MUST be a declared MOD_ enum constant (MOD_EXAMPLE_TALENT) — an unknown
-- MOD_ id would be rejected at a different guard (undeclared enum) and would
-- not exercise the tier check. MOD_EXAMPLE_TALENT is safe here because test_mod
-- (which registers it at tier 2) is disabled in the load test, and
-- new_talent_demo uses MOD_SECOND_TALENT, so there is no upsert collision.
--
-- TODO(M8d3): once tier 3/4 + subclass fields land, replace this with a real
-- tier=3 registration and delete this comment.
register_talent{
    id = "MOD_EXAMPLE_TALENT",
    tier = 3,
    class = "MAGE",
    name = "M58 tier3 placeholder",
}
