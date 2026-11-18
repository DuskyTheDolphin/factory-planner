package com.forgeward.planner;

import com.forgeward.planner.Model.Flow;
import com.forgeward.planner.Model.Key;
import com.forgeward.planner.Model.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The forward line: a source, then machines chained from what it makes. Each output can feed several steps, so a line is a tree.
 * {@link #compute} walks it downstream: what arrives, what the transport and the machines let through, how busy each step is, and what stress it
 * costs. A machine's timing comes from {@link Machine#fill}, the same code the backward view uses. All rates are items (or mB) per minute at 20 TPS.
 */
public final class Line {
    public enum SourceKind {
        DRILL_GENERATOR("Drill on a generator"), DRILL_BLOCK("Drill on a block that comes back"), EXTRUDER("Mechanical extruder"), SUPPLY("A rate you set");
        public final String label;
        SourceKind(String label) { this.label = label; }
    }

    /** How the items reach a step: everything here has a limit of its own in items per minute. */
    public enum Transport {
        BELT("Belt"), BELT_JOINT("Belt into belt"), CHUTE("Chute"), CHUTE_SMART("Smart chute"), FUNNEL_ANDESITE("Andesite funnel"), FUNNEL_BRASS("Brass funnel"), HOPPER("Hopper"), ARM("Mechanical arm");
        public final String label;
        Transport(String label) { this.label = label; }
    }

    public final Source source = new Source();
    /** Steps fed straight by the source. */
    public final List<Step> roots = new ArrayList<>();

    // computed
    public double totalSu;
    /** The stress source chosen for this line (a {@link PowerSources.Source} label) and how many the player says they have; 0 = not checked yet. */
    public String powerLabel;
    public int powerHave;
    public final Map<Key, Double> byproducts = new LinkedHashMap<>();
    public final Map<Key, Double> finished = new LinkedHashMap<>();
    public final Map<Key, Double> suppliedInputs = new LinkedHashMap<>();
public final Map<Key, Double> unprocessed = new LinkedHashMap<>();
    public Step firstOverload;

    public static final class Source {
        public SourceKind kind = SourceKind.DRILL_GENERATOR;
        public Key output = Key.item(net.minecraft.resources.ResourceLocation.parse("minecraft:cobblestone"));
        public float hardness = 2f;
        /** The block being drilled (a stone generator hands on cobblestone); what the head has to be able to mine. */
        public String block = "minecraft:cobblestone";
        public boolean headRefused;
        public int count = 1;
        public float rpm = 64;
        /** SUPPLY only. */
        public double perMinute = 60;
        public int stack = 1;
        public String head = Settings.SESSION.head;
        // computed
        public double rate, ticks, su, headsPerHour;
        public String note = "";
    }

    public static final class Step {
        public final Recipe recipe;
        public final Key input;
        public int count = 1;
        public float rpm;
        public float beltRpm;
        /** Items per belt entry arriving here; 0 = what the step before hands on. */
        public int stack;
        public boolean depot;
        /** Fans on this block: a fan row only. */
        public int fans = 1;
        public Transport transport = Transport.BELT;
        public final Map<Key, List<Step>> next = new LinkedHashMap<>();

        // computed
        public int stackUsed;
        public double offered, transportCap, capPerMachine, cap, through, busy, su, ticks;
        public boolean transportLimits, overloaded, neverRuns;
        /** A fan whose belt is too fast: nothing waits, it all rides through, and this many a minute come out as they went in. */
        public boolean tooFast;
        public double unprocessed, fanMaxBelt;
        public String machineNote = "";
        public final Map<Key, Double> out = new LinkedHashMap<>();
        public final Map<Key, Double> supplied = new LinkedHashMap<>();
        public Step parent;

        public Step(Recipe recipe, Key input, float rpm) {
            this.recipe = recipe;
            this.input = input;
            this.rpm = rpm;
            this.beltRpm = rpm;
            // a belt as fast as the fan would carry everything through unfinished, so a fan starts on the fastest that finishes
            if (isFan(recipe.machine)) beltRpm = Math.max(1, (float) Math.floor(Rates.fanMaxBeltRpm(RecipeIndex.CURRENT.fanTime(), 1, RecipeIndex.CURRENT.fanReach(rpm))));
        }

        public Machine machine() { return recipe.machine; }
    }

    // ------------------------------------------------------------------ timing

    /** Settings for one step's machine, so {@link Machine#fill} answers for this step only. */
    private static Settings settingsFor(Step st, int stack, float rpm, Settings base) {
        Settings s = new Settings();
        s.rpm = rpm;
        s.stack = Math.max(1, stack);
        s.belt = st.beltRpm;
        s.depot = st.depot;
        s.fans = st.fans;
        s.fuel = base.fuel;
        s.head = base.head;
        return s;
    }

    private static double amountOf(Recipe r, Key input) {
        for (Flow f : r.in) {
            if (f.key().equals(input)) return f.amount();
            for (Key alt : f.alts()) if (alt.equals(input)) return f.amount();
        }
        return r.in.isEmpty() ? 1 : r.in.get(0).amount();
    }

    /** Items of the input one machine takes per minute at this RPM, or 0 when it would not run. */
    public static double capacityPerMachine(Step st, int stack, float rpm, Settings base, RecipeIndex idx) {
        Solver.Step probe = new Solver.Step(st.recipe, st.input);
        probe.ops = 1;
        Machine.fill(probe, settingsFor(st, stack, rpm, base), idx);
        return probe.perMachine * amountOf(st.recipe, st.input);
    }

    /** What one transport passes, items per minute, from Create's bytecode and checked on real machines. */
    public static double transportCap(Transport t, float rpm, int stack) {
        int n = Math.max(1, stack);
        float r = Math.abs(rpm);
        return switch (t) {
            // measured: 16, 8, 4, 3 ticks an entry at 32, 64, 128, 192 RPM; at 241-256 RPM the formula's 2 is really 2.17
            case BELT -> r <= 0 ? 0 : 1200.0 / (480.0 / r < 2 ? 2.17 : Math.floor(480.0 / r) + 1) * n;
            case BELT_JOINT -> r <= 0 ? 0 : 1200.0 / (Math.floor(604.8 / r) + 1) * n;
            case CHUTE -> 150.0 * Math.min(n, 16);        // a chain with no fan, 8 t per stack; a plain chute pulls 16 at most from an inventory
            case CHUTE_SMART -> 150.0 * Math.min(n, 64);  // same, up to 64
            case FUNNEL_ANDESITE -> r <= 0 ? 0 : 1200.0 / Math.max(9, Math.floor(480.0 / r) + 1);   // 1 item per extraction
            case FUNNEL_BRASS -> r <= 0 ? 0 : 1200.0 / Math.max(9, Math.floor(480.0 / r) + 1) * Math.min(n, 64);
            case HOPPER -> 150.0;
            case ARM -> r <= 0 ? 0 : 1200.0 / (4 * Math.ceil(1024.0 / r)) * Math.min(n, 64);
        };
    }

    /** The fastest belt the transport is on, as a plain sentence for the row. */
    public static String transportNote(Transport t) {
        return switch (t) {
            case BELT -> "one stack per block of belt";
            case BELT_JOINT -> "a straight belt-to-belt joint is the tightest belt limit";
            case CHUTE -> "one stack per chute; takes 16 at most out of an inventory";
            case CHUTE_SMART -> "one stack per chute; takes up to 64 out of an inventory";
            case FUNNEL_ANDESITE -> "one item per 9 ticks onto a belt";
            case FUNNEL_BRASS -> "a stack per 9 ticks onto a belt";
            case HOPPER -> "one item per 8 ticks";
            case ARM -> "ESTIMATE: 4 phases per trip";
        };
    }

    // ------------------------------------------------------------------ the walk

    private static void computeSource(Source src, Settings base, RecipeIndex idx) {
        float factor = idx.wear().present() ? (float) idx.wear().speedFactor(headSettings(src, base)) : 1f;
        src.su = 0;
        src.headsPerHour = 0;
        src.headRefused = false;
        if ((src.kind == SourceKind.DRILL_GENERATOR || src.kind == SourceKind.DRILL_BLOCK) && !idx.wear().canMine(headSettings(src, base), src.block)) {
            // forgeward_wear's tier check: the drill stands still, so a rate here would be a lie
            src.headRefused = true;
            src.ticks = Double.POSITIVE_INFINITY;
            src.rate = 0;
            src.su = src.count * idx.impact(Machine.DRILL) * Math.abs(src.rpm);
            String head = src.head.substring(src.head.indexOf(':') + 1).replace('_', ' ');
            src.note = "A " + head + " head can't mine this block: the drill does nothing. It needs a head of a higher tier.";
            return;
        }
        switch (src.kind) {
            case DRILL_GENERATOR -> {
                src.ticks = Rates.drillGenerator(src.hardness, src.rpm, factor);
                src.note = "needs a belt, hopper or upward chute under the block; what it won't take is lost";
            }
            case DRILL_BLOCK -> {
                src.ticks = Rates.drillBlock(src.hardness, src.rpm, factor);
                src.note = "best case: the time the block takes to come back is not counted";
            }
            case EXTRUDER -> {
                // two of its recipes cap the RPM (mechanicals:max_speed in the recipe): more does nothing, the machine stops short
                String path = src.output.id().getPath();
                float cap = path.equals("obsidian") ? 4 : path.equals("deepslate") ? 16 : 256;
                src.ticks = Rates.cycle(200, Math.min(src.rpm, cap));
                src.note = (src.rpm > cap ? "this recipe stops above " + (int) cap + " RPM; " : "") + "needs its two side blocks, and an extractor: its one slot stops it when full";
            }
            case SUPPLY -> {
                src.ticks = 0;
                src.note = "the rate you set";
            }
        }
        if (src.kind == SourceKind.SUPPLY) src.rate = src.perMinute;
        else if (src.kind == SourceKind.EXTRUDER) {
            src.rate = Double.isInfinite(src.ticks) ? 0 : src.count * 1200.0 / src.ticks;
            src.su = src.count * idx.impact(Machine.EXTRUDER) * Math.abs(src.rpm);
        } else {
            src.rate = Double.isInfinite(src.ticks) ? 0 : src.count * 1200.0 / src.ticks;
            src.su = src.count * idx.impact(Machine.DRILL) * Math.abs(src.rpm);
            int max = idx.wear().max(headSettings(src, base));
            if (max > 0) src.headsPerHour = src.rate * 60.0 / max;
        }
    }

    private static Settings headSettings(Source src, Settings base) {
        Settings s = new Settings();
        s.head = src.head;
        s.fuel = base.fuel;
        return s;
    }

    private static int stackOut(Step st, double amount) {
        return switch (st.recipe.machine) {
            case CRUSHER, FAN_WASH, FAN_HAUNT, FAN_SMOKE, FAN_BLAST -> Math.max(1, Math.min(64, (int) Math.round(st.stackUsed * Math.min(1, Math.max(amount, 0.01)))));
            default -> 1;
        };
    }

    public static boolean isFan(Machine m) {
        return m == Machine.FAN_WASH || m == Machine.FAN_HAUNT || m == Machine.FAN_SMOKE || m == Machine.FAN_BLAST;
    }

    /** The best a fan does at its own RPM: a stack size at the fastest whole belt RPM that finishes it. Items a minute in [2]. */
    public static double[] fanBest(Step st, RecipeIndex idx) {
        double[] best = null;
        for (int n : new int[]{1, 16, 32, 48, 64}) {
            int belt = (int) Math.floor(480.0 * idx.fanReach(st.rpm) / Math.ceil(Rates.fanBelt(idx.fanTime(), n) / (double) Math.max(1, st.fans)));
            if (belt < 1) continue;
            double rate = n * 1200.0 / Rates.fanBeltPeriod(belt);
            if (best == null || rate > best[2] + 1e-6) best = new double[]{n, belt, rate};
        }
        return best;
    }

    /** Fills every computed field for the current settings. */
    public void compute(Settings base, RecipeIndex idx) {
        byproducts.clear();
        finished.clear();
        suppliedInputs.clear();
        unprocessed.clear();
        firstOverload = null;
        computeSource(source, base, idx);
        totalSu = source.su;
        // the source's own output: the steps on it take it, and what is left over is a finished product
        walkOutput(source.output, source.rate, source.stack, roots, null, base, idx, true);
    }

    /** Hands rate of key to the steps feeding on it, evenly, with what one can't take going to the others (a brass tunnel set to split). */
    private void walkOutput(Key key, double rate, int stack, List<Step> steps, Step parent, Settings base, RecipeIndex idx, boolean primary) {
        if (steps == null || steps.isEmpty()) {
            if (rate > 0) (primary ? finished : byproducts).merge(key, rate, Double::sum);
            return;
        }
        for (Step st : steps) { st.parent = parent; prepare(st, stack, base, idx); }
        // water fill: equal shares, capped by what each step can take
        Map<Step, Double> share = new LinkedHashMap<>();
        for (Step st : steps) share.put(st, 0.0);
        double left = rate;
        List<Step> open = new ArrayList<>(steps);
        while (left > 1e-9 && !open.isEmpty()) {
            double each = left / open.size();
            List<Step> still = new ArrayList<>();
            double given = 0;
            for (Step st : open) {
                double room = Math.min(st.cap, st.transportCap) - share.get(st);
                double give = Math.min(each, room);
                share.merge(st, give, Double::sum);
                given += give;
                if (room > each + 1e-9) still.add(st);
            }
            left -= given;
            if (given <= 1e-9) break;
            open = still;
        }
        // what nobody can take is shown as offered to the steps, split evenly, so each flags the overload it is part of
        double perStepExtra = left / steps.size();
        for (Step st : steps) run(st, share.get(st), share.get(st) + perStepExtra, base, idx);
    }

    private void prepare(Step st, int stackIn, Settings base, RecipeIndex idx) {
        st.stackUsed = st.stack > 0 ? st.stack : Math.max(1, stackIn);
        st.capPerMachine = capacityPerMachine(st, st.stackUsed, st.rpm, base, idx);
        st.cap = st.capPerMachine * st.count;
        st.transportCap = transportCap(st.transport, st.beltRpm, st.stackUsed);
        Solver.Step probe = new Solver.Step(st.recipe, st.input);
        probe.ops = 1;
        Machine.fill(probe, settingsFor(st, st.stackUsed, st.rpm, base), idx);
        st.ticks = probe.ticks;
        st.machineNote = probe.note;
        st.neverRuns = st.capPerMachine <= 0 && st.recipe.machine != Machine.CRAFTING;
        st.fanMaxBelt = probe.fanMaxBelt;
        st.tooFast = isFan(st.recipe.machine) && st.neverRuns && st.beltRpm > probe.fanMaxBelt;
        if (st.tooFast) st.cap = Double.POSITIVE_INFINITY;   // it takes all the belt brings: nothing backs up behind it
    }

    private void run(Step st, double through, double offered, Settings base, RecipeIndex idx) {
        st.offered = offered;
        st.through = through;
        double room = Math.min(st.cap, st.transportCap);
        st.transportLimits = offered > st.transportCap + 1e-6 && st.transportCap < st.cap;
        st.overloaded = offered > room + 1e-6;
        st.busy = st.tooFast ? 1 : st.cap > 0 ? Math.min(offered, st.cap) / st.cap : 0;
        st.unprocessed = st.tooFast ? through : 0;
        if (st.tooFast && through > 0) unprocessed.merge(st.input, through, Double::sum);
        if (st.overloaded && firstOverload == null) firstOverload = st;

        Machine m = st.recipe.machine;
        Solver.Step probe = new Solver.Step(st.recipe, st.input);
        double a = amountOf(st.recipe, st.input);
        double ops = st.tooFast ? 0 : a > 0 ? through / a : 0;
        probe.ops = ops;
        Machine.fill(probe, settingsFor(st, st.stackUsed, st.rpm, base), idx);
        // stress: what is there, not what the flow needs; a machine turns whether it has work or not
        st.su = suFor(st, idx);
        totalSu += st.su;

        st.supplied.clear();
        for (Flow f : st.recipe.in) {
            boolean main = f.key().equals(st.input) || f.alts().contains(st.input);
            if (main) continue;
            if (RecipeIndex.isFreeFluid(f.key())) continue;
            st.supplied.put(f.key(), ops * f.amount());
            suppliedInputs.merge(f.key(), ops * f.amount(), Double::sum);
        }
        st.out.clear();
        for (Flow f : st.recipe.out) {
            double r = ops * f.amount();
            st.out.put(f.key(), r);
            List<Step> kids = st.next.get(f.key());
            boolean first = st.recipe.out.get(0) == f;
            walkOutput(f.key(), r, stackOut(st, f.amount()), kids, st, base, idx, first);
        }
    }

    public static double suFor(Step st, RecipeIndex idx) {
        Machine m = st.recipe.machine;
        if (m == Machine.MECH_CRAFTER) {
            int n = 0;
            for (Flow f : st.recipe.in) n += (int) Math.ceil(f.amount());
            return st.count * Math.max(1, n) * idx.impact(m) * Math.abs(st.rpm);
        }
        if (m == Machine.SEQUENCED) {
            double su = 0;
            for (Machine step : st.recipe.steps) if (step.blocks > 0) su += step.blocks * idx.impact(step) * Math.abs(st.rpm);
            return st.count * su;
        }
        return m.blocks > 0 ? st.count * Math.max(1, isFan(m) ? st.fans : 1) * m.blocks * idx.impact(m) * Math.abs(st.rpm) : 0;
    }

    // ------------------------------------------------------------------ answers for a step

    /** Machines of this kind needed at this RPM to take everything offered; -1 when no count will (it never runs at this RPM). */
    public static int neededAt(Step st, float rpm, Settings base, RecipeIndex idx) {
        double per = capacityPerMachine(st, st.stackUsed, rpm, base, idx);
        if (per <= 0) return -1;
        return (int) Math.ceil(st.offered / per - 1e-9);
    }

    /** The lowest whole RPM at which this many machines take everything offered, or -1 when none up to maxRpm does. */
    public static int rpmNeeded(Step st, int count, int maxRpm, Settings base, RecipeIndex idx) {
        for (int r = 1; r <= maxRpm; r++)
            if (capacityPerMachine(st, st.stackUsed, r, base, idx) * count >= st.offered - 1e-6) return r;
        return -1;
    }

    /** What one more machine on this step would add to the step's own output per minute. */
    public double gainOfOneMore(Step st, Settings base, RecipeIndex idx) {
        double before = st.through;
        st.count++;
        compute(base, idx);
        double after = st.through;
        st.count--;
        compute(base, idx);
        return after - before;
    }

    /** What limits this step when it is not the limit itself: the nearest flagged step above it, or the source. */
    public String limitAbove(Step st) {
        for (Step p = st.parent; p != null; p = p.parent)
            if (p.overloaded) return Export.name(p.input) + " into " + p.recipe.machine.label;
        return "the source";
    }

    public void forEach(java.util.function.Consumer<Step> c) {
        for (Step st : roots) visit(st, c);
    }

    private static void visit(Step st, java.util.function.Consumer<Step> c) {
        c.accept(st);
        for (List<Step> l : st.next.values()) for (Step k : l) visit(k, c);
    }

    public void remove(Step st) {
        if (roots.remove(st)) return;
        if (st.parent != null) for (List<Step> l : st.parent.next.values()) l.remove(st);
    }
}
