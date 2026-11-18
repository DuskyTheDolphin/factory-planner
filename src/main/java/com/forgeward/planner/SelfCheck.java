package com.forgeward.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-derives worked examples of the machine timings (each one read off Create 6.0.10's
 * bytecode) through {@link Rates}. Runs on every recipe sync; the log line says PASS n/n or names each FAIL.
 */
public final class SelfCheck {
    private SelfCheck() {}

    private record Case(String name, double got, double want) {
        boolean ok() { return Math.abs(got - want) < 1e-6 || got == want; }
    }

    public static String run() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("millstone time 250 @64", Rates.millstone(250, 64), 64));
        c.add(new Case("millstone time 250 @256", Rates.millstone(250, 256), 17));
        c.add(new Case("millstone time 250 @16", Rates.millstone(250, 16), 251));
        c.add(new Case("mixer time 100 @64", Rates.mixer(100, 64), 47));
        c.add(new Case("mixer time 100 @256", Rates.mixer(100, 256), 17));
        c.add(new Case("mixer time 100 @16 never", Rates.mixer(100, 16), Double.POSITIVE_INFINITY));
        c.add(new Case("mixer time 100 @64 starved", Rates.mixerStarved(100, 64), 86));
        c.add(new Case("press belt @64", Rates.pressBelt(64, 64), 39));
        c.add(new Case("press belt @256", Rates.pressBelt(256, 256), 11));
        c.add(new Case("press 256 on belt 32", Rates.pressBelt(256, 32), 24));
        c.add(new Case("press depot @64", Rates.pressDepot(64), 33));
        c.add(new Case("compacting @64", Rates.compacting(64), 32));
        c.add(new Case("crush time 250 x16 @64", Rates.crushing(250, 64, 16), 181));
        c.add(new Case("crush time 250 x16 @256", Rates.crushing(250, 256, 16), 46));
        c.add(new Case("crush time 250 x1 @64", Rates.crushing(250, 64, 1), 13));
        c.add(new Case("crush time 250 x64 @64", Rates.crushing(250, 64, 64), 271));
        c.add(new Case("saw time 50 @32", Rates.saw(50, 32), 50));
        c.add(new Case("saw time 50 @64", Rates.saw(50, 64), 25));
        c.add(new Case("saw time 50 @256", Rates.saw(50, 256), 7));
        c.add(new Case("fan 150 x16 belt", Rates.fanBelt(150, 16), 153));
        c.add(new Case("fan 150 x64 entity", Rates.fanEntity(150, 64), 601));
        c.add(new Case("fan 64 stream", Rates.fanStream(Rates.fanReach(64, 256, 20)), 7.5));
        c.add(new Case("deployer free @256", Rates.deployer(256), 8));
        c.add(new Case("deployer belt @64", Rates.deployerBelt(64, 64), 18));
        c.add(new Case("spout belt @64", Rates.spoutBelt(64), 24));
        c.add(new Case("drill cobble gen @64", Rates.drillGenerator(2, 64, 1), 44));
        c.add(new Case("drill cobble gen @256", Rates.drillGenerator(2, 256, 1), 11));
        c.add(new Case("drill cobble gen @128", Rates.drillGenerator(2, 128, 1), 22));
        StringBuilder fails = new StringBuilder();
        int pass = 0;
        for (Case k : c) {
            if (k.ok()) pass++;
            else fails.append("; FAIL ").append(k.name).append(" got ").append(String.format("%.2f", k.got))
                      .append(" want ").append(k.want);
        }
        return (pass == c.size() ? "PASS " : "FAIL ") + pass + "/" + c.size() + fails;
    }
}
