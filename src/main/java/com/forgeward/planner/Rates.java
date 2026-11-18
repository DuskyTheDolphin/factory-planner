package com.forgeward.planner;

/**
 * Machine timings, straight from the shipped bytecode (Create 6.0.10 and the add-ons named on each method) and checked against
 * real machines in a test world, predicted against measured. Every method returns TICKS for the unit named in its doc; 20 ticks = 1 s.
 * Floats are floats on purpose: the game's own float rounding moves some periods by a tick. {@link SelfCheck} re-derives
 * worked examples on every recipe sync, so a wrong edit here shows up as a FAIL line in the log.
 */
public final class Rates {
    private Rates() {}

    /** Batch size assumed by the backward view where a machine works on a whole stack at once (crushing wheels, fans). */
    public static final int STACK = 16;
    /** Create's mediumSpeed (server config, 30 by default): a mixer below this never starts. Set from the config on index build. */
    public static volatile float mixerMinRpm = 30f;

    private static float lerp(float t, float a, float b) { return a + t * (b - a); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }
    /** {@code Mth.log2}: floor(log2(v)) for v >= 1. */
    static int log2(int v) { return 31 - Integer.numberOfLeadingZeros(Math.max(1, v)); }

    /** Belt ticks to bring the next item to a machine on the belt: ceil((1 - 1/512) / (|RPM|/480)). */
    public static int beltGap(float beltRpm) {
        float v = Math.abs(beltRpm) / 480f;
        if (v <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil((1f - 1f / 512f) / v);
    }

    /** Millstone, per item: speed clamp((int)(|RPM|/16), 1, 512); ceil(T/speed) timer ticks + 1 tick to re-arm. */
    public static double millstone(int t, float rpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int speed = Math.max(1, Math.min(512, (int) Math.abs(rpm / 16f)));
        return Math.ceil((double) t / speed) + 1;
    }

    /**
     * Crushing wheels, for a batch of n fed by a belt or funnel: timer from T, step clamp(|RPM|/50 x 4 / log2(n), 0.25, 20) and 20 per tick for
     * n=1; the recipe applies once under 20 and the wheels are empty one tick later. Simulated, so float rounding is the game's. Measured: every
     * RPM and batch measured is k + 1, whatever the speed; the bytecode read had suggested a longer tail, and the real machines say no.
     */
    public static double crushing(int t, float rpm, int n) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        float speed4 = Math.abs(rpm / 50f) * 4f;
        float step = n <= 1 ? 20f : clamp(speed4 / ((float) Math.log(n) / (float) Math.log(2)), 0.25f, 20f);
        float rem = t;
        int ticks = 0;
        while (ticks < 1_000_000) {
            ticks++;
            rem -= step;
            if (rem < 20f) return ticks + 1;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Extra ticks a dropped item entity needs to reach the wheels (ESTIMATE: it creeps at x0.05 in the block). Not used when belt fed. */
    public static final int CRUSH_ENTITY_EXTRA = 29;

    /** Mechanical press cycle C: ceil(120/s) + floor(120/s) + 2 with s = (int) lerp(clamp(|RPM|/512), 1, 60). The recipe's time is ignored. */
    public static double pressCycle(float rpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int s = Math.max(1, (int) lerp(clamp(Math.abs(rpm) / 512f, 0f, 1f), 1f, 60f));
        return Math.ceil(120.0 / s) + Math.floor(120.0 / s) + 2;
    }

    /** Press on a belt, per item (bulkPressing off, one item per cycle): C + n - 1, n from the belt's own RPM. */
    public static double pressBelt(float rpm, float beltRpm) { return pressCycle(rpm) + beltGap(beltRpm) - 1; }

    /** Press over a depot or a dropped item, per item: C + 1. */
    public static double pressDepot(float rpm) { return pressCycle(rpm) + 1; }

    /** Press on a basin (compacting), per recipe: exactly C, restarts in the same tick; the recipe's time is ignored. */
    public static double compacting(float rpm) { return pressCycle(rpm); }

    private static int mixerN(int t, float rpm) {
        float speed = Math.abs(rpm);
        int factor = t <= 0 ? 15 : (int) Math.ceil(t / 100f * 15f);
        return Math.max(1, log2((int) (512f / speed)) * factor + 1);
    }

    /** Mixer + basin, per recipe, the next craft's inputs already in the basin: N + 1. Never starts below {@link #mixerMinRpm}. */
    public static double mixer(int t, float rpm) {
        if (Math.abs(rpm) < mixerMinRpm) return Double.POSITIVE_INFINITY;
        return mixerN(t, rpm) + 1;
    }

    /** Mixer + basin, per recipe, inputs missing at the apply tick (trickle fed): N + 40. */
    public static double mixerStarved(int t, float rpm) {
        if (Math.abs(rpm) < mixerMinRpm) return Double.POSITIVE_INFINITY;
        return mixerN(t, rpm) + 40;
    }

    /** Mechanical saw, per item (bulkCutting off): timer from T, step clamp(|RPM|/24, 1, 128), applies under 5, then a 20 timer at the same step. */
    public static double saw(int t, float rpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        float step = clamp(Math.abs(rpm) / 24f, 1f, 128f);
        float rem = t;
        int ticks = 0;
        boolean applied = false;
        while (ticks < 1_000_000) {
            ticks++;
            rem -= step;
            if (rem < 5f && !applied) { applied = true; rem = 20f; continue; }
            if (rem > 0) continue;
            return ticks;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Deployer with nothing on a belt: 2c + w + 3, c = ceil(1000/s), w = ceil(500/s), s = (int) clamp(|2 RPM|, 8, 512). */
    public static double deployer(float rpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int s = (int) clamp(Math.abs(rpm * 2), 8f, 512f);
        return 2 * Math.ceil(1000.0 / s) + Math.ceil(500.0 / s) + 3;
    }

    /** Deployer over a belt or depot, saturated (the 500 wait is skipped): c + 1 + max(c + 1, n). */
    public static double deployerBelt(float rpm, float beltRpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int s = (int) clamp(Math.abs(rpm * 2), 8f, 512f);
        double c = Math.ceil(1000.0 / s);
        return c + 1 + Math.max(c + 1, beltGap(beltRpm));
    }

    /**
     * Mechanical crafters, per craft (ESTIMATE, a lower bound: refill time is not modelled): 1 + h e + g, e = floor(1000/c)+1, g = floor(2000/c)+1,
     * c = clamp((int)|RPM|, 4, 250), h = the longest chain to the output crafter.
     */
    public static double crafter(float rpm, int hops) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int c = (int) clamp((int) Math.abs(rpm), 4, 250);
        return 1 + hops * (Math.floor(1000.0 / c) + 1) + Math.floor(2000.0 / c) + 1;
    }

    /** Tinkers melting, per item per slot: requiredTime = time x 10; + fuel rate once every 4 ticks (melter, smeltery, foundry). */
    public static double melting(int time, int fuelRate) {
        return 4 * Math.ceil(time * 10.0 / Math.max(1, fuelRate));
    }

    /** Tinkers casting, per cast: the faucet pours 10 mB/tick, then the table/basin timer counts 1/tick up to the cooling time. */
    public static double casting(int fluidMb, int coolingTime) {
        return Math.ceil(fluidMb / 10.0) + coolingTime;
    }

    /** Tinkers alloying: one alloy operation per 4 ticks per smeltery / alloyer, whatever the tank count. */
    public static final double ALLOY = 4;

    /** Encased fan: ticks in the stream a belt item of stack n needs: m F + 3, m = (n-1)/16 + 1. RPM only changes reach. */
    public static int fanBelt(int fanTime, int n) { return ((Math.max(1, n) - 1) / 16 + 1) * fanTime + 3; }

    /** Encased fan: ticks in the stream a dropped item entity of stack n needs: m F + 1. */
    public static int fanEntity(int fanTime, int n) { return ((Math.max(1, n) - 1) / 16 + 1) * fanTime + 1; }

    /** Fan reach in blocks: lerp(min(|RPM|/argmax, 1), 3, distance). Walls and the catalyst block cut it. */
    public static double fanReach(float rpm, int rotationArgmax, int distance) {
        if (rpm == 0) return 0;
        return lerp(Math.min(Math.abs(rpm) / rotationArgmax, 1f), 3f, distance);
    }

    /**
     * Blocks of stream an item on a belt gets for sure, with the catalyst right at the fan: the current rounds its reach up to whole blocks, and an item
     * starting mid-block loses half of one. Measured: fan 64 finishes stacks of 16 at belt 24, not 25; fan 32 at 16, not 18; stacks of 64 at
     * belt 6 only sometimes. So this errs low: 23.5, 17.3 and 6.0 RPM.
     */
    public static double fanStream(double reach) { return reach <= 0 ? 0 : Math.ceil(reach) - 0.5; }

    /** The fastest belt RPM on which a stack of n still finishes inside a stream of the given length in one pass. */
    public static double fanMaxBeltRpm(int fanTime, int n, double streamBlocks) {
        return 480.0 * streamBlocks / fanBelt(fanTime, n);
    }

    /**
     * A fan is not a queue: everything in the stream runs its own timer at once. Ticks per belt stack: one stack per belt block, so the belt's
     * own spacing at its RPM, as long as it is slow enough to finish in one pass; a faster belt lets items leave unfinished ({@link #fanMaxBeltRpm}).
     */
    public static double fanBeltPeriod(float beltRpm) { return beltRpm == 0 ? Double.POSITIVE_INFINITY : Math.ceil(480.0 / Math.abs(beltRpm)); }

    /** Spout over a depot: 21. */
    public static final double SPOUT_DEPOT = 21;

    /** Spout over a belt: max(21, 16 + n). */
    public static double spoutBelt(float beltRpm) { return Math.max(21, 16 + beltGap(beltRpm)); }

    /** Item drain from the side: 29 when a container is left over, 19 when nothing is. */
    public static double drain(boolean leftover) { return leftover ? 29 : 19; }

    /** Mechanicals family (extruder, sifter), per cycle of length c: ceil(c/step) + 2, step = (int) lerp(clamp(|RPM|/512), 1, 60). */
    public static double cycle(int c, float rpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int step = Math.max(1, (int) lerp(clamp(Math.abs(rpm) / 512f, 0f, 1f), 1f, 60f));
        return Math.ceil((double) Math.max(1, c) / step) + 2;
    }

    /** Create Addition rolling mill, per item: ceil(120 / clamp((int)(|RPM|/16), 1, 512)) + 1. The recipe's own duration is not used. */
    public static double rollingMill(float rpm) {
        if (rpm == 0) return Double.POSITIVE_INFINITY;
        int v = Math.max(1, Math.min(512, (int) (Math.abs(rpm) / 16f)));
        return Math.ceil(120.0 / v) + 1;
    }

    /** Create Metallurgy industrial crucible, per item per slot: (int)(T / (1 + 2 (1 - e^(0.14 (minHeat - heat))))). More heat approaches 3x. */
    public static double crucible(int t, int minHeat, int heat) {
        double f = 1 + 2 * (1 - Math.exp(0.14 * (minHeat - heat)));
        return Math.max(1, (int) (t / f));
    }

    /** Create Diesel Generators bulk fermenter, per run: the timer drops by ceil(sqrt(width x height)) a tick. */
    public static double fermenter(int t, int width, int height) {
        return Math.ceil((double) Math.max(1, t) / Math.ceil(Math.sqrt(width * height)));
    }

    /** Create Ore Excavation drilling machine, per run: progress adds floor(|RPM|/30) a tick up to the recipe's ticks. Below 30 RPM it never starts. */
    public static double oreDrill(int ticks, float rpm) {
        int step = (int) (Math.abs(rpm) / 30f);
        return step < 1 ? Double.POSITIVE_INFINITY : Math.ceil((double) Math.max(1, ticks) / step);
    }

    /** Lazy tick period of a Create block entity: rate 10 plus the tick it re-arms on. */
    public static final int DRILL_LAZY = 11;

    /** Mechanical drill, ticks from the first hit to the break: hits add clamp((int)(speed/hardness), 1, left), then wait (int)(hardness/speed). */
    public static int drillMining(float hardness, float rpm, float headFactor) {
        float speed = Math.abs(rpm / 100f) * headFactor;
        if (speed <= 0) return Integer.MAX_VALUE;
        if (hardness <= 0) return 0;
        int hits = 0, progress = 0;
        while (progress < 10) {
            progress += Math.max(1, Math.min((int) (speed / hardness), 10 - progress));
            hits++;
        }
        int wait = (int) (hardness / speed);
        return (hits - 1) * (wait + 1);
    }

    /**
     * Mechanical drill on a fluid generator with a belt, hopper or upward chute under the block (Create's shortcut, CobbleGenOptimisation):
     * the block is never removed, so nothing wakes the drill but its lazy tick. Per block: the first multiple of 11 past 1 + mining ticks.
     * Items the target will not take are lost, the drill does not back up.
     */
    public static double drillGenerator(float hardness, float rpm, float headFactor) {
        int mining = drillMining(hardness, rpm, headFactor);
        if (mining == Integer.MAX_VALUE) return Double.POSITIVE_INFINITY;
        return DRILL_LAZY * ((1 + mining) / DRILL_LAZY + 1);
    }

    /** Mechanical drill on one block that has to come back by itself (best case, re-flow or replacement time not included): 2 + mining. */
    public static double drillBlock(float hardness, float rpm, float headFactor) {
        int mining = drillMining(hardness, rpm, headFactor);
        return mining == Integer.MAX_VALUE ? Double.POSITIVE_INFINITY : 2 + mining;
    }
}
