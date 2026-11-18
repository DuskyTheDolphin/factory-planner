package com.forgeward.planner;

import com.forgeward.planner.Model.Key;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** What the player set on the planner screen. Kept for the session, so reopening the screen keeps the choices. */
public final class Settings {
    public static final Settings SESSION = new Settings();

    /** Default RPM for every kinetic step; 64 is what one large cog off a 32 RPM shaft gives. */
    public float rpm = 64;
    public final Map<ResourceLocation, Float> rpmFor = new HashMap<>();
    /** Recipe chosen per product; absent = the index's default. */
    public final Map<Key, ResourceLocation> choice = new HashMap<>();
    /** Items the player supplies themselves (ores, a farm's output): the plan stops there. */
    public final Set<Key> stop = new LinkedHashSet<>();
    /** Tinkers fuel fluid; null = the hottest fuel the index knows. */
    public ResourceLocation fuel;
    /** Tinkers material of the drill heads (forgeward_wear). */
    public String head = "tconstruct:copper";
    /** Stack size assumed on belts into crushing wheels and through fans. */
    public int stack = Rates.STACK;
    /** Belt RPM under a machine; null = the machine's own RPM. Set by the forward view, per step. */
    public Float belt;
    /** Machine over a depot, not a belt. Set by the forward view, per step. */
    public boolean depot;
    /** Fans on this block. Set by the calculator, per step; everything else leaves it at 1. */
    public int fans = 1;

    public float rpm(ResourceLocation recipe) {
        Float r = rpmFor.get(recipe);
        return r == null ? rpm : r;
    }
}
