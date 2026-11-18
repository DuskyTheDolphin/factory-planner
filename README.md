# Factory Planner

A client-side NeoForge mod for Minecraft 1.21.1: an **in-game production calculator for Create and Tinkers' Construct**.
Hover any item, in EMI's lists or in an inventory, press **P**, and build one production chain from it: what speed each
machine needs, how many of them, where the bottleneck is, and how much stress the line costs.

It adds no blocks, items or network packets, and changes nothing in the world. It reads the recipes the game actually
loaded, so recipes changed by datapacks or scripts are included.

Designed by DuskyTheDolphin, written with AI assistance.

## What it does

- **One chain, start to finish.** A line starts from a source: a drill on a cobblestone (or stone, basalt, limestone,
  scoria) generator, a drill on a block that comes back, a mechanical extruder when that add-on is installed, or a rate
  you set. Each step then picks what the output goes into, from every machine that takes it.
- **Create's own timings.** Millstone, crushing wheels, press (belt, depot, compacting), mixer, saw, the four fan types,
  spout, item drain, deployer, sequenced assembly, mechanical crafters and drills, each with the timing read from Create
  6.0.10's code and checked against real machines. Fan timing, reach and processing time come from Create's server config.
- **Tinkers' Construct.** Melting (with the fuel's rate and temperature, and fuel use), alloying, and casting on tables
  and basins, including part casting per material.
- **Per step:** machine count, RPM, stack size, belt RPM, depot or belt, fans on one block; how busy it is, what it lets
  through, and what limits it (the machine, the belt, a chute or funnel, a fan's belt being too fast). When a step is
  overloaded it says what would fix it: the RPM that would be enough, or one more machine and what it adds.
- **The line's totals:** what comes out per minute, anything left unprocessed, the first bottleneck, and the stress in SU.
- **Recipes it ranks for you.** Recycling, salvage and reverting routes, scrap from sequenced assembly, and recipes
  that break down gear are still listed, but marked and sorted last.
- **Add-on machines and fan catalysts** are read when those mods are installed, among them Create Crafts & Additions,
  Create Metallurgy, Create Enchantment Industry, Create Big Cannons, Create Diesel Generators, Create Mechanical Extruder
  and Create Sifter. Timings that were read from a jar but not measured on a real machine are marked "~Not measured" on
  screen. The rolling mill assumes Create Crafts & Additions' default duration of 120 ticks.
- **Drill heads (optional).** With Machine Wear (`forgeward_wear`) installed, drills need a Tinkers pick head: the
  planner counts heads used per hour, applies the head material's speed, and says when a head can't mine the block at all.
  Without it, drills run at base speed with no heads.

## Using it

- **P** over an item in any GUI (EMI's item list and favourites, or an inventory slot) opens the calculator with a new
  line from that item. EMI's recipe pages don't pass their hovered item on, so use the list or a slot.
- A second key, **Planner: held item**, opens it for the item in your hand. It is unbound by default (Controls, under
  "Factory Planner").
- `/fwplan` opens the calculator; `/fwplan <item>` starts a line from that item, and `/fwplan <item> <per minute>`
  starts it at that rate. The command is client-side: it works on any server.
- On the screen, click a value to type it, or use the mouse wheel over it (Shift steps faster). "+ Add a machine for"
  picks the next step; clicking a machine's name picks another recipe; clicking the source picks what starts the line.
  **x** on a step removes it and what follows; **x** at the top clears the line. Pressing P on an item reuses the current
  line only when nothing has been done with it yet; otherwise it starts a new one.

## Compared with Cogwheel and similar planners

Cogwheel (NeoForge 1.21.1) is a node-based factory planner with Create stress and throughput, works through JEI, and is
opened on a keybind. Create: Kinetic Planner and Satisfactory Planner exist too. Factory Planner works through EMI, includes
Tinkers' Construct recipes (melting, alloying, casting), and opens over any item you hover. The name is shared with a
well-known Factorio mod; this mod is unrelated to it. If one of those mods already works for you, you don't need this one.

## Install

Client only. Put the jar in the client's `mods/` folder; servers do not need it.

- Minecraft 1.21.1
- NeoForge 21.1 or newer (built against 21.1.250)
- Create 6.0 or newer, required (built against Create 6.0.10)
- Tinkers' Construct with Mantle, required (built against NeoTinkers 1.21.1-3.11.2-v1.29 and NeoMantle 1.21.1-1.21.0-v1.25)
- EMI 1.1 or newer, required (built against EMI 1.1.24)
- Machine Wear (`forgeward_wear`) 1.0 or newer, optional

Release test for 2.0.1: a NeoForge 21.1.250 client with only Create 6.0.10, Neo Tinkers 3.11.2 + Neo Mantle, EMI 1.1.24 and
this mod, joined to a server with Create and Tinkers; its self-check passed and the calculator loaded.

## Config

There is no config file. The choices on the screen last for the session. Lines are saved on your computer only, in
`forgeward-plans/lines.json` in the game folder (up to 50); a saved step whose recipe no longer exists is dropped when the
lines load, with a log line.

## Log

```
[forgeward_planner] 2.0.1 ready: key 'Planner: hovered item' (default P) over an item in a GUI or EMI's lists, key 'Planner: held item' (unbound by default), /fwplan; forgeward_wear absent (drills without heads); the recipe index builds when a world's recipes arrive
[forgeward_planner] index (recipe sync, <ms> ms, tinkers materials loaded true): recipes <N> {...}, products <N>, fuels <N>, ...
[forgeward_planner] self-check PASS 28/28
[forgeward_planner] recipe types: <N> of <M> recipe types read; not read: ...
[forgeward_planner] calculator on: 37 machine kinds, generators 5; ...
```

The first line comes during start-up, before the title screen. The others come each time the recipes reach the client
(joining a world or server, `/reload`), with sample plans; their counts depend on the recipes loaded. The self-check
re-derives worked examples of the machine timings, so a timing that drifts shows as FAIL with its name.

## Building

`build.ps1` compiles with `javac` against a NeoForge 21.1.250 server's `libraries/` (set `SERVER` and `JDK`) and the
Create, Tinkers' Construct, Mantle and EMI jars (`CREATE`, `TINKERS`, `MANTLE`, `EMI`, or the newest matching jar in the
server's `mods/`, then in `MODS`). The named client jars go in `clientlibs/`; when they are missing, the script makes
them with the server's NeoForge installer in client mode. Mod id `forgeward_planner`.

## License

MIT, see [LICENSE](LICENSE).
