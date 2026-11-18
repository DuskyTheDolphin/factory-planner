package com.forgeward.planner;

import com.forgeward.planner.Model.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Map;



/** How the screens and the log name things and amounts. The Build tool looks up {@link #name} too. */
public final class Export {
    private Export() {}

    public static String name(Key k) {
        try {
            if (k.fluid()) {
                var f = BuiltInRegistries.FLUID.get(k.id());
                return f.getFluidType().getDescription().getString() + " (fluid)";
            }
            var item = BuiltInRegistries.ITEM.get(k.id());
            String n = new ItemStack(item).getHoverName().getString();
            if (!k.variant().isEmpty()) n += " (" + k.variant().substring(k.variant().indexOf(':') + 1).replace('_', ' ') + ")";
            return n;
        } catch (Throwable t) {
            return k.toString();
        }
    }

    public static String amount(Key k, double perMin) {
        String v = perMin >= 100 ? String.valueOf(Math.round(perMin)) : String.format("%.1f", perMin);
        return k.fluid() ? v + " mB" : v;
    }

    public static String flowsText(Map<Key, Double> m) {
        StringBuilder sb = new StringBuilder("{");
        for (var e : m.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(amount(e.getKey(), e.getValue()));
        }
        return sb.append('}').toString();
    }
}
