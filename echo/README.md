# ECHO

**A local, offline AI assistant that lives in your Minecraft world.**

ECHO answers in chat, actually *acts* on the world through real function calls,
and tunes Minecraft's own settings to match your hardware, your mod list and the
server you are playing on.

Everything runs on your machine. There is no account, no API key, and nothing
leaves your computer.

- Minecraft **26.1.2** · Fabric Loader **0.18.4** · Fabric API **0.152.1** · Java **25**
- Requires [GeckoLib](https://www.curseforge.com/minecraft/mc-mods/geckolib) for the companion orb.

---

## Talking to ECHO

Type in chat:

```
hey echo, where's the nearest diamond?
hey echo, my game is lagging
hey echo, build me a stone room
hey echo, remember my base is at 120 64 -300
hey echo, what should I enchant this pickaxe with?
```

ECHO's replies are private — only you see them, even on a busy server. The
triggering message never reaches public chat either.

### Fixed commands

These answer instantly and never touch the language model:

| Command | What it does |
| --- | --- |
| `echo help` | Everything ECHO can do |
| `echo status` | Which local backend and model are in use |
| `echo tools` | List every registered tool |
| `echo models` | Models installed locally |
| `echo tune` | Re-tune your Minecraft settings now |
| `echo preview` | Show the settings ECHO *would* apply |
| `echo fps` | Hardware, frame rate, mods, server |
| `echo config` | Show `config/echo.json` |
| `echo set <key> <value>` | Change one setting |
| `echo personality` | How ECHO is currently talking |
| `echo waypoints` | Your saved places |
| `echo reset` | Forget the current conversation |

---

## The settings tuner

This is the part that does something no other assistant mod does: ECHO reads
your actual situation and rewrites Minecraft's video options to match it.

It looks at:

- **Hardware** — CPU cores, system RAM, the heap ceiling your launcher gave the
  game, and the frame rate you are getting right now.
- **Mods** — how many are loaded, whether a rendering optimiser (Sodium,
  Embeddium, VulkanMod…) is present, whether a shader loader is present, whether
  Distant Horizons is drawing your far terrain, and whether any known-heavy
  content mods are installed.
- **Session** — singleplayer or a remote server, and which server.

Then it picks a profile (`performance`, `balanced`, `quality`, `multiplayer`,
`modpack`) and writes render distance, simulation distance, framerate limit,
vsync, graphics preset, clouds, particles, entity shadows, entity distance,
biome blend, mipmaps, smooth lighting, chunk-update priority, leaf detail,
weather radius, cloud range, view bobbing and screen-effect scale — through
Minecraft's real option objects, so the values persist to `options.txt`.

It also tells you the things it *cannot* fix itself: too little allocated heap,
a modpack with no rendering optimiser, shaders that are too heavy for the
machine.

```
hey echo, my game is stuttering
echo tune
echo preview          # see the plan without applying it
```

Say `hey echo, set my render distance to 12` for a single option instead.

---

## The AI backend

ECHO speaks to whatever local inference server you are already running, and
finds it automatically:

| Backend | Port | Dialect |
| --- | --- | --- |
| Ollama | 11434 | native `/api/chat` |
| LM Studio | 1234 | OpenAI-compatible |
| llama.cpp server | 8080 | OpenAI-compatible |
| Jan | 1337 | OpenAI-compatible |
| KoboldCpp | 5001 | OpenAI-compatible |

Both paths send a real JSON tool schema and read structured `tool_calls` back,
so ECHO's actions come from the model's function calling rather than from
pattern-matching its prose.

### Getting started

```bash
# 1. Install Ollama:  https://ollama.com/download
# 2. Pull a model that calls tools well:
ollama pull qwen3:8b
```

ECHO starts Ollama itself if it finds the binary, downloads the recommended
model when nothing suitable is installed, and picks the strongest model that
fits your RAM. Its ranked catalogue:

| Model | RAM | Notes |
| --- | --- | --- |
| `qwen3:30b-a3b` | 32 GB | Strongest local tool caller; MoE, only 3B active |
| `qwen3:14b` | 16 GB | Excellent tool calling, great quality/size balance |
| `qwen3:8b` | 10 GB | The sweet spot for most gaming PCs |
| `mistral-nemo:12b` | 12 GB | Strong function calling, 128k context |
| `llama3.1:8b` | 10 GB | Solid, widely available |
| `qwen3:4b` | 6 GB | Still calls tools correctly on low-RAM machines |

Switch at any time with `hey echo, use llama3.1:8b`.

---

## Works on servers that don't have ECHO

If the server has ECHO installed, the server answers and the full tool set is
available — building, giving items, teleporting, running commands.

If it doesn't, ECHO answers on your client instead: it reads your inventory,
your surroundings, the mobs near you, your gear durability and your settings,
and it can send commands you already have permission to run. The handover is
automatic; you never pick a mode.

---

## What ECHO can do

**61 server-side tools** and **39 client-side tools**, including:

**Look around** — find ore, scan every ore at once, find any block, locate
structures, list hostile mobs, list all entities, audit light levels, full base
safety audit, search nearby chests, world snapshot, biome, time until nightfall.

**Read you** — health and hunger, full inventory, item counts, gear durability
with break warnings.

**Change the world** — give items, heal, grant XP, teleport (to coordinates or a
saved waypoint), set spawn, set time, weather, difficulty and game rules, summon
entities, place blocks, apply and clear effects, run any command.

**Build** — walls, floors, ceilings, pillars, paths, roofs, rooms, emergency
shelters, bridges, stairs, towers, platforms, domes and fences in any material;
light an area mob-proof; dig a corridor that stops safely at lava.

**Farm** — plant every empty piece of farmland, harvest and replant everything
that is ripe.

**Know things** — crafting recipes, best enchantments, potion brewing, per-mob
tactics, mining depths, Nether coordinate conversion, XP level maths, smelting
fuel planning.

**Remember** — notes, waypoints, preferences and where you last died, all
persisted across sessions in `config/echo-memory.json`.

**Manage itself** — report backend status, list and switch models, change
personality, edit its own config, report server TPS and memory, list your mods.

---

## Configuration

`config/echo.json` is written on first run:

```jsonc
{
  "aiBaseUrl": "",              // empty = auto-detect
  "aiModel": "",                // empty = best installed model that fits
  "aiTemperature": 0.4,
  "aiMaxToolRounds": 6,
  "aiAutoStartBackend": true,
  "aiAutoPullModel": true,

  "wakeWords": "hey echo,ei echo,echo",
  "language": "auto",           // auto | en | pt
  "personality": "friendly",    // friendly | concise | teacher | pro
  "allowWorldTools": true,      // building, giving, teleporting
  "allowRawCommands": false,    // arbitrary /commands — off by default
  "showHud": true,
  "voiceEnabled": true,
  "companionEnabled": true,

  "settingsTunerEnabled": true,
  "settingsTunerAutoOnJoin": false,
  "settingsTunerTargetFps": 60
}
```

Change anything in game: `hey echo, set target_fps 144`.

---

## Voice (optional)

ECHO listens offline through [Vosk](https://alphacephei.com/vosk/models) if it
is present, and does nothing at all if it is not:

1. Put `vosk` and `jna` in your `mods/` folder.
2. Extract a Vosk model to `.minecraft/vosk-model/`.
3. Say "hey echo" out loud.

Recognition happens on your machine; no audio is ever sent anywhere.

---

## The companion orb

Craft nothing — the **ECHO Core** item calls a small blue orb that hovers beside
you. It is invulnerable, harmless, unpushable and cannot wander off; it pulses
while ECHO is answering. Right-click the core again to dismiss it.

Disable it entirely with `companionEnabled: false`.

---

## Building

```bash
cd echo
./gradlew build          # Linux / macOS
.\gradlew.bat build      # Windows
```

The jar lands in `echo/build/libs/`.

Minecraft 26.1 is the first unobfuscated release, so **there is no `mappings`
line in `build.gradle`** — adding one makes Loom try to remap an already-named
jar and breaks the entire classpath.

---

## Privacy

ECHO makes no outbound connections except to `127.0.0.1`. The model is local,
the speech recognition is local, and the memory file lives in your own config
folder.

## License

MIT.
