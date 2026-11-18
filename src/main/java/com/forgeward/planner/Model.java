package com.forgeward.planner;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** The planner's own recipe shape: every source (Create, Tinkers, vanilla) is read into this once per recipe sync. */
public final class Model {
    private Model() {}

    /**
     * An item or a fluid, by registry id. Fluid amounts are mB; item amounts are items. {@code variant} is the Tinkers
     * material of a part ("tconstruct:cobalt" on a pick head), else "".
     */
    public record Key(boolean fluid, ResourceLocation id, String variant) {
        public static Key item(ResourceLocation id) { return new Key(false, id, ""); }
        public static Key fluid(ResourceLocation id) { return new Key(true, id, ""); }
        @Override public String toString() { return (fluid ? "fluid:" : "") + id + (variant.isEmpty() ? "" : "#" + variant); }
    }

    /** One input or output per operation. {@code amount} is already the expected value for a chance output. */
    public record Flow(Key key, double amount, double chance, List<Key> alts) {
        public Flow(Key key, double amount) { this(key, amount, 1, List.of()); }
    }

    /** One way to make something, as the planner sees it. */
    public static final class Recipe {
        public final ResourceLocation id;
        public final Machine machine;
        /** The recipe's own duration field (Create processing time, Tinkers melting time, furnace cook time, cooling time). */
        public final int time;
        public final List<Flow> in = new ArrayList<>();
        public final List<Flow> out = new ArrayList<>();
        /** Create basin heat ("", "heated", "superheated") or the Tinkers temperature, for display. */
        public String heat = "";
        public int temperature;
        /** Sequenced assembly: loops and the machine of each step. */
        public int loops = 1;
        public final List<Machine> steps = new ArrayList<>();
        public final List<Integer> stepTimes = new ArrayList<>();
        /** Drill source: the block's hardness. */
        public float hardness;
        /** Virtual recipes (fan bulk blasting of a smelting recipe, a drill source) are made by the planner, not the pack. */
        public boolean virtual;
        /** An input is an empty tag: the recipe cannot be made and is not indexed. */
        public boolean broken;
        /** Set at read time when the recipe's own class says it is a salvage route; else {@link RecipeIndex#odd} decides. */
        public String oddTag = "";
        /** Add-on stress or FE cost not in Create's table: SU per RPM of one machine ({@link Machine#block} when 0), FE per tick. */
        public double fePerTick;
        /** Crafting read from a shaped recipe: a mixer only takes the shapeless ones. */
        public boolean shaped;
        /** Another type's recipe this machine really runs, by name; listed in the calculator, never a default. See {@link RecipeIndex#usesOf}. */
        public String via = "";
        /** The ingredients as read, whole tags, for working out those routes once every recipe is in. Emptied after that. */
        public final List<net.minecraft.world.item.crafting.Ingredient> read = new ArrayList<>();

        public Recipe(ResourceLocation id, Machine machine, int time) {
            this.id = id;
            this.machine = machine;
            this.time = time;
        }

        public double outAmount(Key k) {
            double a = 0;
            for (Flow f : out) if (f.key().equals(k)) a += f.amount();
            return a;
        }

        /** True when k is this recipe's guaranteed, first-listed product (not a chance byproduct). */
        public boolean primary(Key k) {
            return !out.isEmpty() && out.get(0).key().equals(k) && out.get(0).chance() >= 1;
        }
    }
}
