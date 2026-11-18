# Changelog

## 2.0.1
- First public release.
- Display name "Factory Planner" (also the controls category); MIT license.
- A start-up log line says the mod is ready, its keys and command, and whether Machine Wear is installed.
- On-screen notes no longer point to an outside website, and the rolling mill note names Create Crafts & Additions'
  default duration.
- Test-only hooks were removed from the mod. The build script no longer assumes a fixed folder layout (`SERVER`, `JDK`,
  `CREATE`, `TINKERS`, `MANTLE`, `EMI`, `MODS`).

## 2.0.0
- Unreleased internal version: the in-game calculator. One straight line from a source, with a source picker, a picker
  for what each output goes into, saved lines, and the transport into each step. Fans start their belt where they
  finish, typing over a number replaces it, depot or belt per step, fans on one block, and `/fwplan <item> <per minute>`
  starts at that rate. The backward recipe screen is gone; the old entry point shows EMI's recipes instead.

## 1.1.0
- Unreleased internal version: the forward line view with machine rates measured on real machines, and a recipe chooser.
  Add-on machines and the mechanical extruder as a source; stonecutting and smithing as fallback routes; a head that can't
  mine the block says so. Screens wrap at any GUI scale, number cells fit, lines are kept between sessions, and all
  screens get a dark backing and a scrollbar. The log lists every recipe type and whether it is read.

## 1.0.0
- Unreleased internal version: a client-only planner over the live recipe list, using Create's machine timings and
  Tinkers' melting and casting. Loop-aware default recipes, natural blocks (ores, raw ore, stones, logs) treated as
  gathered, tag ingredients shown as a plain item, and dead-end inputs scored lower.
