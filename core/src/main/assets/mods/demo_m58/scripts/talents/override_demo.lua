-- M7e demo: register_talent_override retunes a VANILLA talent (not a new one).
-- Scope is lowering-only: maxPoints must be within [1, baseMaxPoints]. HEARTY_MEAL
-- is a tier-1 talent with baseMaxPoints=2, so maxPoints=1 is a valid lower. The
-- override also replaces the desc string wholesale (override-first in
-- Talent.desc). register_talent_override never sends name/title, so the vanilla
-- Messages title is preserved.
register_talent_override{
    id = "HEARTY_MEAL",
    maxPoints = 1,
    desc = "M58 override:饱餐的恢复量下调(demo_m58)",
}
