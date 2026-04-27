# Peek a View

Project Zomboid mod — extends the wall, building, and tree cutaway range so zombies hiding in your line of sight stop being invisible.

In vanilla PZ, walls and roofs only fade out within roughly 5 tiles of your character — so the player behind the screen sees less than the character actually can. A zombie leaning against a house wall or hiding behind a tall fence stays completely invisible to you, even when your character is looking straight at it. Peek a View closes that gap: houses and view-blocking fences fade from further away, so what reaches your screen matches (mostly) what your character can see.

The same idea now applies to **trees** as well — see-around tree sprites the character can look past so zombies are no longer hidden behind a wall of leaves.

## Features

- **Extended wall cutaway range** — configurable slider from 5 (vanilla) to 20 tiles.
- **Tree fade** — trees in your character's view become translucent so zombies behind them are visible. Independent range slider (5–25 tiles, default 20) and its own driving-speed gate.
- **Independent driving-speed gates** — wall cutaway and tree fade each have their own km/h threshold; turn one off in the car while keeping the other on (e.g. tree fade on at higher speed for spotting roadside obstacles, wall cutaway off for less screen noise).
- **Optional nimble-stance-only mode** — restrict the whole mod to while you are aiming a weapon (right-click held).
- **No see-through walls, no line-of-sight bypass** — same fade mechanics as vanilla, just triggered from further away. You see what the character can see, not more.
- **B42 wall-hiding bug fix** — stops the engine from hiding upper-floor walls of vanilla buildings next to player-built stairs or floors. Toggleable.

### Also

- **F8 hotkey** with green/red halo indicator. Rebindable under `[PeekAView]` in PZ's keybind menu.
- **12 languages:** EN, DE, FR, ES, RU, PL, PT-BR, IT, TR, CN, KO, JP.
- **Multiplayer-safe** — client-side rendering only, no server changes, no save modifications.

## Requirements

- **Project Zomboid** Build 42.13 or newer
- **[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853)** — Java bytecode patching framework (required, one-time setup)

## Installation

1. Subscribe to **[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853)** on the Steam Workshop and follow its one-time setup instructions. This step is only needed once — all mods that depend on ZombieBuddy work automatically afterwards.
2. Subscribe to **[Peek a View](https://steamcommunity.com/sharedfiles/filedetails/?id=3710281407)**.
3. Enable both mods in the in-game mod list and launch the game.

Because Peek a View ships a Java JAR, the **first** time you launch the game after installing it, ZombieBuddy will show a native approval dialog with the mod id, JAR path, last-modified date, and SHA-256 fingerprint. Click `Yes` and optionally choose to persist the decision; subsequent launches load the JAR silently until the JAR changes on disk.

## Settings

Open `Options → Mods → Peek a View`. The screen is grouped into three sections.

**Global**

| Setting | Default | What it does |
|---|---|---|
| Enable | on | Master switch for all features |
| Active only in nimble stance | off | Mod only runs while aiming a weapon (right-click held) |

**Wall cutaway**

| Setting | Range / Default | What it does |
|---|---|---|
| Range | 5–20 tiles, default 15 | How far walls and buildings start turning transparent. `5` = pure vanilla (the patch falls through). |
| Active up to | 0–120 km/h, default 35 | Above this speed in a vehicle, wall cutaway turns off. `0` = always off in a vehicle. |
| Fix B42 wall-hiding bug | on | Workaround for a vanilla B42 engine bug (see FAQ). Runs regardless of `Active only in nimble stance`. |

**Tree fade**

| Setting | Range / Default | What it does |
|---|---|---|
| Enable | on | Toggle the tree-fade feature |
| Range | 5–25 tiles, default 20 | How far trees start turning transparent |
| Active up to | 0–120 km/h, default 100 | Above this speed in a vehicle, tree fade turns off. `0` = always off in a vehicle. Below ~30 km/h trees ease into translucency at vanilla's pace; at ~30 km/h and above they snap fully translucent in one frame so you don't drive into opaque crowns. |
| Stay on while driving | off | Override: when `Active only in nimble stance` is on, this keeps tree fade running in vehicles regardless of aim state. |
| Stay on while on foot | on | Override: when `Active only in nimble stance` is on, this keeps tree fade running on foot regardless of aim state. Default on so the typical user enabling nimble-stance for around-the-corner peeks still gets tree fade everywhere. |

**F8** toggles the master Enable switch in-game (green/red halo confirms). Rebindable under `[PeekAView]` in PZ's keybind menu.

## FAQ

**Does it work in multiplayer?** Yes. Peek a View only affects client-side rendering — no server impact, no save modifications. ZombieBuddy must be installed on every client that loads the mod.

**Save compatibility?** Safe to add or remove on existing saves. The mod does not touch world data. If you want to remove it cleanly, disable it in-game first, then quit and uninstall.

**Is tree fade X-ray?** No. It only fades tree sprites your character can already see past from their angle — exactly what the character sees. A zombie standing inside a bush stays hidden, just like in vanilla.

**What's the "B42 wall-hiding bug"?** In Build 42, placing player-built stairs or floors near a vanilla building can make the adjacent upper-floor walls of that vanilla building disappear entirely — not cutaway, just not rendered. Peek a View ships a workaround that's on by default. Turn it off under `Fix B42 wall-hiding bug` if you want to observe the vanilla behavior. Engine-side fallback without the fix: keep at least a 2-tile gap between player-built structures and vanilla walls, or place them on a different Z-level.

**Why ZombieBuddy?** Peek a View is a Java mod. Changing the behavior of a compiled PZ method isn't achievable with a standard Lua mod, and ZombieBuddy's bytecode patching keeps the mod working across minor PZ updates without editing the PZ jar.

**Does it conflict with other cutaway mods?** Peek a View patches seven specific engine methods via ZombieBuddy. Other ZombieBuddy-based mods patching the same methods may interact — test case by case. No known conflicts as of Build 42.13.

**Does it affect performance?** Several caches keep the runtime cost close to vanilla: a per-frame gate cache, frame-caches for the POI raster and the tree-fade location list, and a tile-filter that drops empty grass/road tiles from the obscuring-list. Standing still and walking on foot are effectively free. JFR-measured CPU footprint at default settings is around 2.8% of CPU samples in 120 s of driving with ~50 background mods active — the master `Enable` toggle to off measures at 0.1%. The driving-speed gates disable each feature above its configured km/h so highway travel can be made entirely free. See [`docs/PeekAViewMod.md`](docs/PeekAViewMod.md) for the raw breakdown.

## Building from Source

One-time setup:

1. Extract a [Zulu JDK 25](https://www.azul.com/downloads/) Windows x64 build into `tools/` (needs `tools/zulu*-win_x64/bin/javac.exe`).
2. Copy `build.local.example` to `build.local` and set `PZ_DIR` to your PZ install.
3. Ensure `ZombieBuddy.jar` sits next to `projectzomboid.jar` in your PZ install.

Then `./build.sh` compiles, packages `peekaview.jar`, and installs to `%USERPROFILE%/Zomboid/mods/PeekAView`.

Technical documentation for contributors is under [`docs/`](docs/).

## Links

- **GitHub:** https://github.com/armakupub/PeekAView
- **Steam Workshop:** https://steamcommunity.com/sharedfiles/filedetails/?id=3710281407
- **ZombieBuddy:** https://github.com/zed-0xff/ZombieBuddy

## License

[MIT](LICENSE) — feel free to fork, modify, and redistribute. Attribution appreciated.
