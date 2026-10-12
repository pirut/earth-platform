# Survival direction and acceptance gates

Updated 2026-10-11. Candidate 039 remains work in progress; delivered 037 remains separate.

## Intended game

Earth is a continuous player-traversable Overworld. A rendered scene is the current player-centered terrain window, not a disconnected destination. The bounded Marin patch is an integration test milestone. Wider source coverage, moving native/DH windows, edit persistence after unloading and revisiting, and render-distance changes require separate execution evidence.

Native Nether and End gameplay are required. Their ordinary native generation and height semantics are the initial target; cubic expansion is required for Earth, not automatically for those dimensions. Normal portals, portal placement and return locations, progression, inventory continuity, and cross-dimension save/restart must be tested. Existing dimension creation/save lines do not establish any of those gameplay gates.

## First bounded playable slice

1. Real client entry with empty Earth inventory and explicit prototype ordinary-world escrow.
2. Gather natural logs with genuine native mining, loot, item motion and pickup.
3. Use ordinary per-click native inventory and crafting-table menus to make planks, sticks, table and wooden pick.
4. Excavate successive one-block soil layers with genuine native movement/landing and pickups, then mine actual stone and make a stone pick.
5. Place admitted building blocks to make a small shelter.
6. Preserve accepted world, inventory, menus, items, scheduled effects and player state through clean leave and literal restart.
7. Draw full selected-distance native terrain with current edit identity, shader resources and matching DH complement. Do not shrink the selected setting to fit a fixture.

Current isolated server evidence passes the earned three-log/table/wooden-pick action and native file-readback route. It is a scripted native semantic test, not ordinary interactive controls or a new-process reload. Per-click menu ownership and true movement packet/step ownership are being integrated. The registered held enter/return route has executed, but carrier shutdown serialization still fails; it is not a clean lifecycle gate.

The first gameplay patch uses real Marin elevation, coarse NOAA macro height, WorldCover classification and disclosed procedural resources. It does not qualify high-elevation packed-coordinate networking. No ordinary-height mirror or coordinate rebasing is allowed.

## Dimension architecture constraints

The existing /earth entry/exit escrow is a prototype boundary between the user's ordinary test world and the bounded Earth session. It must not become a per-dimension inventory swap. Normal Earth↔Nether↔End travel must retain one survival inventory and coherent progression; crossing a portal must not invoke ordinary escrow restoration.

Current transfer admission deliberately refuses Nether/End and has no portal capability. Native portals normally return to minecraft:overworld, not the current survival_carrier. Future integration therefore needs an explicit logical-overworld routing policy or the real Overworld facade, with no silent escrow exit. Future portal integration must use the original native portal rules with explicit coordinate/height safety, exact source/destination authority and supported full-XYZ data. No teleport demonstration substitutes for native portal gameplay. Round trips, native portal scaling/placement at actual Earth coordinates, death/respawn and restart in each dimension remain required. Clean save/restart and crash conservation remain distinct claims.

## Still outside a regular-survival claim

Continuous player physics, health/hunger/death, ordinary item cohorts/merge/expiry, interactive menu/save edge cases, native scheduled resource effects, fluids/light/weather/random ticks, mobs, caves/ores/food and wider world generation each need explicit qualification. Unsupported first-slice actions must fail safely and visibly; staged fixture success must not be presented as complete regular survival.
