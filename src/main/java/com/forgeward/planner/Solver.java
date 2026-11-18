package com.forgeward.planner;

import com.forgeward.planner.Model.Flow;
import com.forgeward.planner.Model.Key;
import com.forgeward.planner.Model.Recipe;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns "N of key per minute" into steps. The chosen recipes form a graph from the target back to raw inputs; keys are
 * processed in reverse post-order of a DFS, so a shared intermediate (iron feeding both a press and a mixer) gets its
 * whole demand before it is expanded, and appears once. An edge back onto the current path (a loop such as
 * ingot -> block -> ingot) is cut there and its input counted as raw, with a note.
 */
public final class Solver {
    private Solver() {}

    public static final int MAX_DEPTH = 16;

    public static final class Step {
        public final Recipe recipe;
        public final Key product;
        public double ops;          // recipe operations per minute
        public float rpm;
        public double ticks;        // ticks per timer run of one machine
        public int batch;           // operations per timer run
        public double perMachine;   // operations per minute one machine manages
        public double machines;     // exact; display ceil
        public double su;           // stress at this rpm for ceil(machines)
        public double fuelMb;       // Tinkers fuel per minute (melter/smeltery/alloy steps)
        public double headsPerHour; // drill steps with forgeward_wear
        public int crafters;        // mechanical crafting: crafters in one grid
    public double fanMaxBelt;   // fans: the fastest belt RPM that still finishes in one pass
        public String note = "";

        Step(Recipe r, Key product) { this.recipe = r; this.product = product; }

        public int count() { return (int) Math.ceil(machines - 1e-9); }
        public double utilisation() { return count() == 0 ? 0 : machines / count(); }
    }

    public static final class Plan {
        public final Key target;
        public final double rate;
        public final List<Step> steps = new ArrayList<>();
        public final Map<Key, Double> raw = new LinkedHashMap<>();
        public final Map<Key, Double> byproducts = new LinkedHashMap<>();
        public final List<String> notes = new ArrayList<>();
        public double su;
        public Step bottleneck;

        Plan(Key target, double rate) { this.target = target; this.rate = rate; }

        public int machineCount() {
            int n = 0;
            for (Step s : steps) n += s.count();
            return n;
        }
    }

    public static Plan solve(Key target, double rate, Settings s, RecipeIndex idx) {
        // asking for a plan OF a gathered item (logs, stones) means: plan it, even though it is raw by default elsewhere
        if (!s.choice.containsKey(target) && !s.stop.contains(target) && RecipeIndex.gatheredByDefault(target)) {
            Recipe r = idx.pick(target);
            if (r != null) s.choice.put(target, r.id);
        }
        Plan plan = new Plan(target, rate);
        // 1. order the keys: reverse post-order over the chosen recipes, cutting back edges
        List<Key> post = new ArrayList<>();
        Set<Key> done = new HashSet<>(), path = new HashSet<>();
        Set<String> cut = new HashSet<>();
        visit(target, 0, s, idx, post, done, path, cut, plan);
        // 2. push demand down in that order
        Map<Key, Double> demand = new HashMap<>();
        demand.put(target, rate);
        for (int i = post.size() - 1; i >= 0; i--) {
            Key k = post.get(i);
            double need = demand.getOrDefault(k, 0.0);
            if (need <= 1e-12) continue;
            Recipe r = chosen(k, s, idx);
            if (r == null) { plan.raw.merge(k, need, Double::sum); continue; }
            double per = r.outAmount(k);
            Step st = new Step(r, k);
            st.ops = need / per;
            Machine.fill(st, s, idx);
            plan.steps.add(st);
            for (Flow f : r.in) {
                double amt = f.amount() * st.ops;
                if (cut.contains(k + ">" + f.key())) plan.raw.merge(f.key(), amt, Double::sum);
                else demand.merge(f.key(), amt, Double::sum);
            }
            for (Flow f : r.out) if (!f.key().equals(k)) plan.byproducts.merge(f.key(), f.amount() * st.ops, Double::sum);
        }
        double worst = -1;
        for (Step st : plan.steps) {
            plan.su += st.su;
            // the bottleneck: the step that needs the most machines for this rate (the one to speed up or duplicate)
            if (st.machines > worst && st.perMachine > 0 && st.recipe.machine != Machine.CRAFTING) {
                worst = st.machines;
                plan.bottleneck = st;
            }
        }
        return plan;
    }

    /** The recipe the plan uses for k, or null when k is raw (no recipe, or the player stops there). */
    public static Recipe chosen(Key k, Settings s, RecipeIndex idx) {
        if (s.stop.contains(k)) return null;
        ResourceLocation id = s.choice.get(k);
        if (id != null) {
            for (Recipe r : idx.all(k)) if (r.id.equals(id)) return r;
            // a Tinkers multi-recipe (one pack recipe -> a recipe per material) is indexed as id/0, id/1, ...
            String prefix = id + "/";
            for (Recipe r : idx.all(k)) if (r.id.toString().startsWith(prefix)) return r;
        }
        return idx.defaultRecipe(k);
    }

    private static void visit(Key k, int depth, Settings s, RecipeIndex idx, List<Key> post, Set<Key> done,
                              Set<Key> path, Set<String> cut, Plan plan) {
        if (done.contains(k)) return;
        path.add(k);
        Recipe r = depth >= MAX_DEPTH ? null : chosen(k, s, idx);
        if (depth >= MAX_DEPTH) plan.notes.add("depth limit at " + k + ": counted as raw");
        if (r != null) {
            for (Flow f : r.in) {
                if (path.contains(f.key())) {
                    cut.add(k + ">" + f.key());
                    plan.notes.add("loop " + f.key() + " -> ... -> " + k + ": cut, " + f.key() + " counted as raw there");
                    continue;
                }
                visit(f.key(), depth + 1, s, idx, post, done, path, cut, plan);
            }
        }
        path.remove(k);
        done.add(k);
        post.add(k);
    }
}
