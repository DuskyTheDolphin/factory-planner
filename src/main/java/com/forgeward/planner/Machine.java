package com.forgeward.planner;

import com.forgeward.planner.Model.Recipe;
import com.forgeward.planner.Solver.Step;

/**
 * Each kind of step: its display name, what one "machine" is, the Create block whose stress impact applies, and its
 * timing ({@link #fill}). Timings are {@link Rates}; units per machine follow the bytecode notes in that class.
 */
public enum Machine {
    MILLSTONE("Millstone", "millstones", "create:millstone", 1),
    CRUSHER("Crushing wheels", "wheel pairs", "create:crushing_wheel", 2),
    PRESS("Mechanical press", "presses", "create:mechanical_press", 1),
    MIXER("Mechanical mixer + basin", "mixers", "create:mechanical_mixer", 1),
    COMPACTOR("Press on a basin (compacting)", "presses", "create:mechanical_press", 1),
    SAW("Mechanical saw", "saws", "create:mechanical_saw", 1),
    FAN_WASH("Fan washing", "fan streams", "create:encased_fan", 1),
    FAN_HAUNT("Fan haunting", "fan streams", "create:encased_fan", 1),
    FAN_SMOKE("Fan smoking", "fan streams", "create:encased_fan", 1),
    FAN_BLAST("Fan blasting", "fan streams", "create:encased_fan", 1),
    SPOUT("Spout", "spouts", null, 0),
    DRAIN("Item drain", "drains", null, 0),
    DEPLOYER("Deployer", "deployers", "create:deployer", 1),
    SEQUENCED("Sequenced assembly", "lines", null, 0),
    MECH_CRAFTER("Mechanical crafters", "crafter grids", "create:mechanical_crafter", 1),
    MELTING("Tinkers melting", "melting slots", null, 0),
    ALLOY("Tinkers alloying", "smelteries", null, 0),
    TABLE("Casting table", "tables", null, 0),
    BASIN_CAST("Casting basin", "basins", null, 0),
    FURNACE("Furnace", "furnaces", null, 0),
    BLAST_FURNACE("Blast furnace", "blast furnaces", null, 0),
    SMOKER("Smoker", "smokers", null, 0),
    CRAFTING("Crafting", "crafting", null, 0),
    DRILL("Mechanical drill", "drills", "create:mechanical_drill", 1),
    // no rate of their own, or a hand step
    SMITHING("Smithing table", "smithing", null, 0),
    // add-on machines: timings read from their jars; the notes on each say which were measured on a real machine
    FAN_OTHER("Fan with an add-on catalyst", "fan streams", "create:encased_fan", 1),
    EXTRUDER("Mechanical extruder", "extruders", "create_mechanical_extruder:mechanical_extruder", 1),
    SIFTER("Sifter", "sifters", "createsifter:sifter", 1),
    ROLLING_MILL("Rolling mill", "rolling mills", "createaddition:rolling_mill", 1),
    BELT_GRINDER("Belt grinder", "belt grinders", "createmetallurgy:mechanical_belt_grinder", 1),
    GRINDSTONE("Mechanical grindstone", "grindstones", "create_enchantment_industry:mechanical_grindstone", 1),
    FOUNDRY_MIXER("Foundry mixer", "foundry mixers", "createmetallurgy:foundry_mixer", 1),
    FOUNDRY_LID("Foundry lid", "foundry lids", null, 0),
    METAL_CAST("Casting (Create Metallurgy)", "casting blocks", null, 0),
    TESLA_COIL("Tesla coil charging", "tesla coils", null, 0),
    BIG_CANNON_FOUNDRY("Basin foundry lid", "foundry lids", null, 0),
    BASIN_LID("Basin lid (fermenting)", "basin lids", null, 0);

    public final String label, unit, block;
    /** Blocks of {@link #block} per machine (a crusher is two wheels); 0 = not kinetic. */
    public final int blocks;
    public final boolean kinetic;

    Machine(String label, String unit, String block, int blocks) {
        this.label = label;
        this.unit = unit;
        this.block = block;
        this.blocks = blocks;
        this.kinetic = blocks > 0 || "lines".equals(unit);
    }

    /** Fill in ticks, batch, per-machine rate, machine count, stress, fuel and heads for a step whose ops/min is set. */
    public static void fill(Step st, Settings s, RecipeIndex idx) {
        Recipe r = st.recipe;
        Machine m = r.machine;
        float rpm = m.kinetic ? s.rpm(r.id) : 0;
        float belt = s.belt != null ? s.belt : s.rpm(r.id);
        st.rpm = rpm;
        st.batch = 1;
        switch (m) {
            case MILLSTONE -> st.ticks = Rates.millstone(r.time, rpm);
            case CRUSHER -> { st.batch = s.stack; st.ticks = Rates.crushing(r.time, rpm, s.stack); st.note = "stacks of " + s.stack + " off a belt or funnel"; }
            case PRESS -> {
                st.ticks = s.depot ? Rates.pressDepot(rpm) : Rates.pressBelt(rpm, belt);
                st.note = s.depot ? "one item per cycle, on a depot" : "one item per cycle, on a belt at " + (int) belt + " RPM";
            }
            case MIXER -> {
                st.ticks = Rates.mixer(r.time, rpm);
                st.note = Double.isInfinite(st.ticks) ? "a mixer never starts below " + (int) Rates.mixerMinRpm + " RPM"
                        : "with the next craft's inputs already in the basin; trickle fed it takes " + (int) Rates.mixerStarved(r.time, rpm) + " t";
            }
            case COMPACTOR -> st.ticks = Rates.compacting(rpm);
            case SAW -> st.ticks = Rates.saw(r.time, rpm);
            case FAN_WASH, FAN_HAUNT, FAN_SMOKE, FAN_BLAST -> {
                // not a queue: everything in the stream runs its own timer, so the belt's spacing is the rate, up to the belt speed that still finishes in one pass
                st.batch = s.stack;
                double reach = idx.fanReach(rpm);
                // fans on one block add up, measured: the timer over the fan count, rounded up. 5 reach a depot, 6 a belt block
                double timer = Math.ceil(Rates.fanBelt(idx.fanTime(), s.stack) / (double) Math.max(1, s.fans));
                double maxBelt = 480.0 * reach / timer;
                st.fanMaxBelt = maxBelt;
                if (s.depot) {
                    // it sits there: the belt and the stream don't come into it. Measured for all four types
                    st.ticks = timer;
                    st.note = String.format("%d at a time on a depot, %d fan(s)", s.stack, Math.max(1, s.fans));
                    break;
                }
                st.ticks = s.belt != null && s.belt > maxBelt ? Double.POSITIVE_INFINITY : Rates.fanBeltPeriod((float) Math.min(belt, maxBelt));
                st.note = String.format("stacks of %d on a belt up to about %.0f RPM (a little faster may still finish, much faster leaves items unfinished), through %.1f blocks of stream; fan RPM sets reach", s.stack, Math.min(belt, maxBelt), reach);
            }
            case SPOUT -> {
                st.ticks = s.depot ? Rates.SPOUT_DEPOT : Rates.spoutBelt(belt);
                st.note = s.depot ? "on a depot" : "on a belt at " + (int) belt + " RPM; 21 t on a depot";
            }
            case DRAIN -> {
                boolean leftover = false;
                for (var f : r.out) if (!f.key().fluid()) leftover = true;
                st.ticks = Rates.drain(leftover);
                st.note = "fed from the side";
            }
            case DEPLOYER -> { st.ticks = Rates.deployerBelt(rpm, belt); st.note = "on a belt or depot"; }
            case SEQUENCED -> {
                double worst = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.steps.size(); i++) {
                    Machine step = r.steps.get(i);
                    double t = stepTicks(step, r.stepTimes.get(i), rpm);
                    worst = Math.max(worst, t);
                    if (sb.length() > 0) sb.append(" > ");
                    sb.append(step.label);
                }
                st.ticks = Math.max(1, r.loops) * worst;
                st.note = r.loops + " loop(s): " + sb + "; one of each per line";
            }
            case MECH_CRAFTER -> {
                int n = 0;
                for (var f : r.in) n += (int) Math.ceil(f.amount());
                int side = (int) Math.ceil(Math.sqrt(Math.max(1, n)));
                st.ticks = Rates.crafter(rpm, Math.max(1, 2 * side - 2));
                st.note = "ESTIMATE, at best: crafter timing (hop chain " + Math.max(1, 2 * side - 2) + "), refill time not counted";
                st.crafters = Math.max(1, n);
            }
            case MELTING -> {
                RecipeIndex.Fuel fuel = idx.fuel(s);
                if (fuel == null) { st.ticks = Double.POSITIVE_INFINITY; st.note = "no Tinkers fuel known"; break; }
                st.ticks = Rates.melting(r.time, fuel.rate());
                st.note = fuel.name() + (fuel.temperature() < r.temperature ? " is TOO COLD (" + fuel.temperature() + " < "
                        + r.temperature + ")" : "") + "; a melter has 3 slots";
            }
            case ALLOY -> { st.ticks = Rates.ALLOY; st.note = "one alloy op per 4 ticks per smeltery"; }
            case TABLE, BASIN_CAST -> {
                int mb = 0;
                for (var f : r.in) if (f.key().fluid()) mb += (int) f.amount();
                st.ticks = Rates.casting(mb, r.time);
                st.note = "faucet 10 mB/t + cooling " + r.time + " t";
            }
            case FURNACE, BLAST_FURNACE, SMOKER -> st.ticks = Math.max(1, r.time);
            case CRAFTING -> { st.ticks = 0; st.note = "by hand, a Crafter, or mechanical crafters"; }
            case SMITHING -> { st.ticks = 0; st.note = "by hand at a smithing table"; }
            case FAN_OTHER -> {
                st.batch = s.stack;
                double reach = idx.fanReach(rpm);
                // fans on one block add up, measured: the timer over the fan count, rounded up. 5 reach a depot, 6 a belt block
                double timer = Math.ceil(Rates.fanBelt(idx.fanTime(), s.stack) / (double) Math.max(1, s.fans));
                double maxBelt = 480.0 * reach / timer;
                st.fanMaxBelt = maxBelt;
                if (s.depot) {
                    // it sits there: the belt and the stream don't come into it. Measured for all four types
                    st.ticks = timer;
                    st.note = String.format("%d at a time on a depot, %d fan(s)", s.stack, Math.max(1, s.fans));
                    break;
                }
                st.ticks = s.belt != null && s.belt > maxBelt ? Double.POSITIVE_INFINITY : Rates.fanBeltPeriod((float) Math.min(belt, maxBelt));
                st.note = String.format("same timer as Create's fans, not run with this catalyst. Stacks of %d on a belt up to about %.0f RPM (a little faster may still finish)", s.stack, Math.min(belt, maxBelt));
            }
            case EXTRUDER -> { st.ticks = Rates.cycle(r.time, rpm); st.note = "needs its two side blocks, and an extractor: its one output slot stops it when full"; }
            case SIFTER -> { st.ticks = Rates.cycle(r.time, rpm); st.note = "1 item per cycle with a mesh; a brass sifter does more (read from the jar, not measured)"; }
            case ROLLING_MILL -> { st.ticks = Rates.rollingMill(rpm); st.note = "Create Crafts & Additions' default 120 ticks; the recipe's own time is not used"; }
            case BELT_GRINDER, GRINDSTONE -> { st.ticks = Rates.saw(r.time <= 0 ? 50 : r.time, rpm); st.note = "read from the jar, not measured; same shape as the saw"; }
            case FOUNDRY_MIXER -> {
                st.ticks = Rates.mixer(r.time, rpm);
                st.note = "read from the jar, not measured; a mixer's timing";
            }
            case FOUNDRY_LID, BIG_CANNON_FOUNDRY -> { st.ticks = r.time + 2; st.note = "read from the jar, not measured; no RPM, heat from below"; }
            case METAL_CAST -> { st.ticks = Math.max(1, r.time); st.note = "read from the jar, not measured; no RPM, a fan on it cools it 3x as fast"; }
            case BASIN_LID -> { st.ticks = Math.max(1, r.time); st.note = "read from the jar, not measured; no RPM"; }
            case TESLA_COIL -> {
                st.ticks = Math.max(1, r.time);
                st.note = String.format("read from the jar, not measured; %.0f FE/t while charging", r.fePerTick);
            }
            case DRILL -> {
                st.ticks = Rates.drillGenerator(r.hardness, rpm, (float) idx.wear().speedFactor(s));
                st.note = "on a generator with a belt, hopper or upward chute under the block";
            }
        }
        st.perMachine = st.ticks <= 0 || Double.isInfinite(st.ticks) ? 0 : 1200.0 * st.batch / st.ticks;
        st.machines = st.perMachine > 0 ? st.ops / st.perMachine : 0;
        if (m == MECH_CRAFTER) st.su = st.count() * st.crafters * idx.impact(m) * Math.abs(rpm);
        else if (m.blocks > 0) st.su = st.count() * m.blocks * idx.impact(m) * Math.abs(rpm);
        else if (m == SEQUENCED) {
            double su = 0;
            for (Machine step : r.steps) if (step.blocks > 0) su += step.blocks * idx.impact(step) * Math.abs(rpm);
            st.su = st.count() * su;
        }
        if (m == MELTING) {
            RecipeIndex.Fuel fuel = idx.fuel(s);
            // fuel burns once per heat cycle (every 4 ticks) per structure; count melters of 3 slots
            if (fuel != null && fuel.duration() > 0)
                st.fuelMb = Math.ceil(st.machines / 3.0) * fuel.amount() * 300.0 / fuel.duration();
        }
        if (m == DRILL) {
            int max = idx.wear().max(s);
            if (max > 0) st.headsPerHour = st.ops * 60.0 / max;
        }
    }

    /** One sequenced-assembly step's ticks per item at rpm. */
    static double stepTicks(Machine step, int time, float rpm) {
        return switch (step) {
            case PRESS -> Rates.pressBelt(rpm, rpm);
            case DEPLOYER -> Rates.deployerBelt(rpm, rpm);
            case SAW -> Rates.saw(time, rpm);
            default -> Rates.spoutBelt(rpm);
        };
    }
}
