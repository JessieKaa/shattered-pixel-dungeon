-- M11b burn-transform target for regression_demo_ration. A minimal material so the
-- ration's declarative burnTransform resolves to a real item at runtime.
-- LuaMaterial.transformTo is best-effort null on a missing id (no crash), but a real
-- target keeps the demo's material transform chain honest end-to-end.
register_item {
    id = "regression_demo_ash",
    type = "material",
    name = "回归灰烬",
    desc = "口粮烧剩的灰烬(regression_demo_ration 的 burnTransform 产物)。",
    image = 11,
    price = 1,
    stackable = true,
}
