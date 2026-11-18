package com.forgeward.planner;

import com.forgeward.planner.Line.Step;

/** A forward line as plain text, for Copy and for the log's self-report. */
public final class LineText {
    private LineText() {}

    public static String text(Line l, Settings s, RecipeIndex idx) {
        StringBuilder sb = new StringBuilder();
        Line.Source src = l.source;
        sb.append(src.kind.label).append(": ").append(Export.name(src.output));
        if (src.kind != Line.SourceKind.SUPPLY) sb.append(", ").append(src.count).append(" at ").append((int) src.rpm).append(" RPM");
        sb.append(": ").append(Export.amount(src.output, src.rate)).append("/min\n");
        l.forEach(st -> {
            int d = 0;
            for (Step p = st.parent; p != null; p = p.parent) d++;
            sb.append("  ".repeat(d + 1)).append(st.count).append(" x ").append(st.recipe.machine.label);
            if (st.recipe.machine.kinetic) sb.append(" at ").append((int) st.rpm).append(" RPM");
            sb.append(", stack ").append(st.stackUsed).append(", in ").append(String.format("%.1f", st.offered)).append("/min, out");
            for (var e : st.out.entrySet()) sb.append(' ').append(String.format("%.1f", e.getValue())).append(' ').append(Export.name(e.getKey()));
            sb.append(String.format(", busy %d%%, %d SU", Math.round(st.busy * 100), Math.round(st.su)));
            if (st.overloaded) {
                int rpm = Line.rpmNeeded(st, st.count, 256, s, idx);
                sb.append(", OVERLOADED: ").append(Line.neededAt(st, st.rpm, s, idx)).append(" needed at this RPM, ").append(rpm > 0 ? "or " + rpm + " RPM with this count" : "and no RPM is enough with this count");
            }
            sb.append('\n');
        });
        sb.append("Stress ").append(Math.round(l.totalSu)).append(" SU; out ").append(Export.flowsText(l.finished));
        if (!l.byproducts.isEmpty()) sb.append("; byproducts (average) ").append(Export.flowsText(l.byproducts));
        return sb.toString();
    }
}
