package com.forgeward.planner;

import com.forgeward.planner.Model.Flow;
import com.forgeward.planner.Model.Key;
import com.forgeward.planner.Model.Recipe;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import slimeknights.mantle.recipe.IMultiRecipe;
import slimeknights.tconstruct.common.config.Config;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes;
import slimeknights.tconstruct.library.recipe.alloying.AlloyRecipe;
import slimeknights.tconstruct.library.recipe.casting.IDisplayableCastingRecipe;
import slimeknights.tconstruct.library.recipe.fuel.MeltingFuel;
import slimeknights.tconstruct.library.recipe.melting.IMeltingContainer;
import slimeknights.tconstruct.library.recipe.melting.MeltingRecipe;
import slimeknights.tconstruct.library.tools.part.IMaterialItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every recipe the client knows, read once per recipe sync into {@link Model.Recipe}s and indexed by product.
 * Sources: Create processing / sequenced assembly / mechanical crafting, Tinkers melting / alloying / casting / fuel,
 * vanilla crafting and furnaces, plus planner-made "virtual" recipes (fan blasting and smoking of the furnace recipes,
 * drills on stone generators). Counts per source go to the log line, so a source that stops reading shows as 0.
 */
public final class RecipeIndex {
    public record Fuel(ResourceLocation fluid, String name, int amount, int duration, int temperature, int rate) {}

    public static volatile RecipeIndex CURRENT = new RecipeIndex();

    private final Map<Key, List<Recipe>> byProduct = new HashMap<>();
    private final Map<Key, List<Recipe>> byInput = new HashMap<>();
    private final Map<Key, List<Recipe>> fallbacks = new HashMap<>();
    private final Map<Key, List<Recipe>> extras = new HashMap<>();
    /** The calculator's lists only: nothing the solver or the Build tool's raw cost reads goes near these, so no cost can move. */
    private final Map<Key, List<Recipe>> listOnly = new HashMap<>();
    private final java.util.Set<Recipe> leftAlone = new java.util.HashSet<>();
    private final List<Recipe> extruders = new ArrayList<>();
    private final Map<ResourceLocation, Recipe> ids = new HashMap<>();
    /** Every recipe type the game has, and how many of each this index took in: a whole type going unread shows in the self-report on every sync. */
    private final Map<String, Integer> typesSeen = new TreeMap<>(), typesRead = new TreeMap<>();
    /** Recipe types with a ProcessingRecipe shape nothing here reads: named in the self-report, so a gap is visible rather than silent. */
    private final Map<String, Integer> unread = new TreeMap<>();
    private final Map<Key, Recipe> defaults = new HashMap<>();
    private final List<Fuel> fuels = new ArrayList<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private final WearBridge wear = new WearBridge();
    public int skipped;

    /** Fluids that are free in practice: the plan stops at them unless the player picks a recipe. */
    private static final List<ResourceLocation> FREE = List.of(
            ResourceLocation.parse("minecraft:water"), ResourceLocation.parse("minecraft:lava"));

    public static RecipeIndex build(RecipeManager rm, RegistryAccess ra) {
        RecipeIndex idx = new RecipeIndex();
        for (RecipeHolder<?> h : rm.getRecipes()) {
            int before = idx.size();
            try {
                idx.read(h, ra);
            } catch (Throwable t) {
                idx.skipped++;
            }
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType());
            String name = type == null ? "unregistered:" + h.value().getClass().getSimpleName() : type.toString();
            idx.typesSeen.merge(name, 1, Integer::sum);
            if (idx.size() > before) idx.typesRead.merge(name, 1, Integer::sum);
        }
        idx.addHidden();
        idx.addDrill("cobblestone_generator", "minecraft:cobblestone", 2.0f);
        idx.addDrill("basalt_generator", "minecraft:basalt", 1.25f);
        idx.fuels.sort(Comparator.comparingInt(Fuel::rate).reversed());
        try {
            Rates.mixerMinRpm = AllConfigs.server().kinetics.mediumSpeed.get().floatValue();
        } catch (Throwable ignored) {
            // no world loaded yet: keep the last value
        }
        return idx;
    }

    // ------------------------------------------------------------------ reading

    private void read(RecipeHolder<?> h, RegistryAccess ra) {
        var r = h.value();
        ResourceLocation id = h.id();
        RecipeType<?> type = r.getType();
        try {
            if (r instanceof net.minecraft.world.item.crafting.StonecutterRecipe c) { readStonecutting(id, c, ra); return; }
            if (r instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe sm) { readSmithing(id, sm, ra); return; }
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            // some add-ons never register their recipe type, so those go by class
            if (!(r instanceof ProcessingRecipe<?, ?>)) readReflected(id, r, typeId != null ? typeId.toString() : r.getClass().getSimpleName(), ra);
        } catch (Exception e) {
            skipped++;
            return;
        }
        if (r instanceof SequencedAssemblyRecipe sa) { readSequenced(id, sa, ra); return; }
        if (r instanceof MechanicalCraftingRecipe mc) {
            Recipe p = new Recipe(id, Machine.MECH_CRAFTER, 0);
            ingredients(p, mc.getIngredients());
            out(p, mc.getResultItem(ra), 1);
            add(p, "create:mechanical_crafting");
            return;
        }
        if (r instanceof ProcessingRecipe<?, ?> pr) { readProcessing(id, pr); return; }
        if (type == TinkerRecipeTypes.MELTING.get()) {
            if (r instanceof MeltingRecipe m) readMelting(id, m);
            else if (r instanceof IMultiRecipe<?> multi) {
                int i = 0;
                for (Object o : multi.getRecipes(ra)) if (o instanceof MeltingRecipe m) readMelting(id.withSuffix("/" + i++), m);
            } else skipped++;
            return;
        }
        if (type == TinkerRecipeTypes.ALLOYING.get() && r instanceof AlloyRecipe a) { readAlloy(id, a); return; }
        if (type == TinkerRecipeTypes.CASTING_TABLE.get() || type == TinkerRecipeTypes.CASTING_BASIN.get()) {
            Machine m = type == TinkerRecipeTypes.CASTING_TABLE.get() ? Machine.TABLE : Machine.BASIN_CAST;
            if (r instanceof IDisplayableCastingRecipe d) readCasting(id, m, d);
            else if (r instanceof IMultiRecipe<?> multi) {
                int i = 0;
                for (Object o : multi.getRecipes(ra))
                    if (o instanceof IDisplayableCastingRecipe d) readCasting(id.withSuffix("/" + i++), m, d);
            } else skipped++;
            return;
        }
        if (type == TinkerRecipeTypes.FUEL.get() && r instanceof MeltingFuel f) { readFuel(f); return; }
        if (r instanceof AbstractCookingRecipe c) { readCooking(id, c, type, ra); return; }
        if (type == RecipeType.CRAFTING && r instanceof CraftingRecipe c) {
            ItemStack res = c.getResultItem(ra);
            if (res.isEmpty() || c.getIngredients().isEmpty()) return;    // special recipes (dyeing, repair, ...)
            Recipe p = new Recipe(id, Machine.CRAFTING, 0);
            p.shaped = c instanceof net.minecraft.world.item.crafting.ShapedRecipe;
            ingredients(p, c.getIngredients());
            out(p, res, 1);
            add(p, "vanilla:crafting");
        }
    }

    private static Machine createMachine(RecipeType<?> type) {
        if (type == AllRecipeTypes.CRUSHING.getType()) return Machine.CRUSHER;
        if (type == AllRecipeTypes.MILLING.getType()) return Machine.MILLSTONE;
        if (type == AllRecipeTypes.PRESSING.getType()) return Machine.PRESS;
        if (type == AllRecipeTypes.MIXING.getType()) return Machine.MIXER;
        if (type == AllRecipeTypes.COMPACTING.getType()) return Machine.COMPACTOR;
        if (type == AllRecipeTypes.CUTTING.getType()) return Machine.SAW;
        if (type == AllRecipeTypes.SPLASHING.getType()) return Machine.FAN_WASH;
        if (type == AllRecipeTypes.HAUNTING.getType()) return Machine.FAN_HAUNT;
        if (type == AllRecipeTypes.FILLING.getType()) return Machine.SPOUT;
        if (type == AllRecipeTypes.EMPTYING.getType()) return Machine.DRAIN;
        if (type == AllRecipeTypes.DEPLOYING.getType() || type == AllRecipeTypes.ITEM_APPLICATION.getType()
                || type == AllRecipeTypes.SANDPAPER_POLISHING.getType()) return Machine.DEPLOYER;
        return null;
    }

    /** Add-on machines, by recipe type id: their jars are not compile dependencies. Timing per machine in {@link Machine#fill}. */
    private static Machine addonMachine(String type) {
        return switch (type) {
            case "createmetallurgy:melting" -> Machine.FOUNDRY_LID;
            case "createmetallurgy:alloying" -> Machine.FOUNDRY_MIXER;
            case "createmetallurgy:grinding" -> Machine.BELT_GRINDER;
            case "create_enchantment_industry:grinding" -> Machine.GRINDSTONE;
            case "create_dragons_plus:ending", "create_dragons_plus:freezing", "createnuclear:enriched", "fluidlogistics:bulk_cooling", "fluidlogistics:inactive_bulk_cooling" -> Machine.FAN_OTHER;
            case "fluidlogistics:cooling_compacting", "createdieselgenerators:compression_molding" -> Machine.COMPACTOR;
            case "fluidlogistics:cooling_mixing" -> Machine.MIXER;
            case "createaddition:rolling" -> Machine.ROLLING_MILL;
            case "createaddition:charging" -> Machine.TESLA_COIL;
            case "createbigcannons:melting" -> Machine.BIG_CANNON_FOUNDRY;
            case "createdieselgenerators:basin_fermenting", "petrolpark:lidded_basin" -> Machine.BASIN_LID;
            default -> null;
        };
    }

    private void readProcessing(ResourceLocation id, ProcessingRecipe<?, ?> pr) {
        Machine m = createMachine(pr.getType());
        boolean addon = false;
        if (m == null) {
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(pr.getType());
            m = type == null ? null : addonMachine(type.toString());
            if (m == null) {
                if (type != null) unread.merge(type.toString(), 1, Integer::sum);
                return;
            }
            addon = true;
        }
        int time = pr.getProcessingDuration();
        Recipe charging = null;
        if (m == Machine.TESLA_COIL) {
            try {
                int energy = (int) pr.getClass().getMethod("getEnergy").invoke(pr);
                int rate = (int) pr.getClass().getMethod("getMaxChargeRate").invoke(pr);
                int perTick = Math.max(1, Math.min(2000, rate));   // Create Crafts & Additions' tesla_coil_recipe_charge_rate, at its default
                time = (int) Math.ceil((double) energy / perTick);
                charging = new Recipe(id, m, time);
                charging.fePerTick = perTick;
            } catch (Exception e) {
                unread.merge("createaddition:charging (no energy read)", 1, Integer::sum);
                return;
            }
        }
        Recipe p = charging != null ? charging : new Recipe(id, m, time);
        if (addon) { readProcessingFlows(p, pr); if (!p.out.isEmpty() && !p.broken) addExtra(p, "addon:" + m.name().toLowerCase()); return; }
        ingredients(p, pr.getIngredients());
        for (SizedFluidIngredient f : pr.getFluidIngredients()) fluidIn(p, f.getFluids(), f.amount());
        for (ProcessingOutput o : pr.getRollableResults()) out(p, o.getStack(), o.getChance());
        for (FluidStack f : pr.getFluidResults()) fluidOut(p, f);
        if (pr.getRequiredHeat() != HeatCondition.NONE) p.heat = pr.getRequiredHeat().getSerializedName();
        add(p, "create:" + m.name().toLowerCase());
    }

    private static void readProcessingFlows(Recipe p, ProcessingRecipe<?, ?> pr) {
        ingredients(p, pr.getIngredients());
        for (SizedFluidIngredient f : pr.getFluidIngredients()) fluidIn(p, f.getFluids(), f.amount());
        for (ProcessingOutput o : pr.getRollableResults()) out(p, o.getStack(), o.getChance());
        for (FluidStack f : pr.getFluidResults()) fluidOut(p, f);
        if (pr.getRequiredHeat() != HeatCondition.NONE) p.heat = pr.getRequiredHeat().getSerializedName();
    }

    /** Vanilla stonecutting, on the saw (allowStonecuttingOnSaw on, 50 ticks): a route the default uses only when k has no other. */
    private void readStonecutting(ResourceLocation id, net.minecraft.world.item.crafting.StonecutterRecipe c, RegistryAccess ra) {
        ItemStack res = c.getResultItem(ra);
        if (res.isEmpty() || c.getIngredients().isEmpty()) return;
        Recipe p = new Recipe(id, Machine.SAW, 50);
        ingredient(p, c.getIngredients().get(0), 1);
        out(p, res, 1);
        if (!p.broken) addFallback(p, "fallback:stonecutting");
    }

    /** Smithing table: template, base and addition all go in. A route the default uses only when k has no other. */
    private void readSmithing(ResourceLocation id, net.minecraft.world.item.crafting.SmithingTransformRecipe s, RegistryAccess ra) throws Exception {
        ItemStack res = s.getResultItem(ra);
        if (res.isEmpty()) return;
        Recipe p = new Recipe(id, Machine.SMITHING, 0);
        for (String field : List.of("template", "base", "addition")) {
            var f = net.minecraft.world.item.crafting.SmithingTransformRecipe.class.getDeclaredField(field);
            f.setAccessible(true);
            Ingredient in = (Ingredient) f.get(s);
            if (!in.isEmpty()) ingredient(p, in, 1);
        }
        out(p, res, 1);
        if (!p.broken) addFallback(p, "fallback:smithing");
    }

    /** Add-on recipe types that are not ProcessingRecipes, read by method name from their own jars. */
    private void readReflected(ResourceLocation id, net.minecraft.world.item.crafting.Recipe<?> r, String type, RegistryAccess ra) throws Exception {
        switch (type) {
            case "create_mechanical_extruder:extruding", "ExtrudingRecipe" -> {
                ProcessingOutput po = (ProcessingOutput) r.getClass().getMethod("getResult").invoke(r);
                Recipe p = new Recipe(id, Machine.EXTRUDER, 200);   // cycleTime, create_mechanical_extruder-server.toml
                out(p, po.getStack(), po.getChance());
                if (!p.out.isEmpty() && extruders.stream().noneMatch(e -> e.out.get(0).key().equals(p.out.get(0).key()))) extruders.add(p);
                counts.merge("addon:extruder", 1, Integer::sum);
            }
            case "createsifter:sifting", "SiftingRecipe" -> {
                int t = (int) r.getClass().getMethod("getProcessingTime").invoke(r);
                Recipe p = new Recipe(id, Machine.SIFTER, t);
                ingredient(p, (Ingredient) r.getClass().getMethod("getInput").invoke(r), 1);
                for (Object o : (List<?>) r.getClass().getMethod("getResults").invoke(r)) {
                    ProcessingOutput po = (ProcessingOutput) o;
                    out(p, po.getStack(), po.getChance());
                }
                if (!p.out.isEmpty() && !p.broken) addExtra(p, "addon:sifter");
            }
            case "createmetallurgy:casting_in_table", "createmetallurgy:casting_in_basin" -> {
                int t = (int) r.getClass().getMethod("getProcessingDuration").invoke(r);
                Recipe p = new Recipe(id, Machine.METAL_CAST, t);
                Ingredient cast = (Ingredient) r.getClass().getMethod("getIngredient").invoke(r);
                if (cast != null && !cast.isEmpty()) ingredient(p, cast, 1);
                SizedFluidIngredient f = (SizedFluidIngredient) r.getClass().getMethod("getFluidIngredient").invoke(r);
                if (f != null) fluidIn(p, f.getFluids(), f.amount());
                out(p, r.getResultItem(ra), 1);
                if (!p.out.isEmpty() && !p.broken) addExtra(p, "addon:metal_cast");
            }
            default -> { }
        }
    }

    private void readSequenced(ResourceLocation id, SequencedAssemblyRecipe sa, RegistryAccess ra) {
        Recipe p = new Recipe(id, Machine.SEQUENCED, 0);
        p.loops = Math.max(1, sa.getLoops());
        ingredient(p, sa.getIngredient(), 1);
        Key transitional = key(sa.getTransitionalItem());
        for (SequencedRecipe<?> step : sa.getSequence()) {
            ProcessingRecipe<?, ?> sr = step.getRecipe();
            Machine m = createMachine(sr.getType());
            p.steps.add(m == null ? Machine.DEPLOYER : m);
            p.stepTimes.add(sr.getProcessingDuration());
            for (Ingredient in : sr.getIngredients()) {
                ItemStack[] items = in.getItems();
                if (items.length == 0 || key(items[0]).equals(transitional)) continue;
                ingredient(p, in, p.loops);
            }
            for (SizedFluidIngredient f : sr.getFluidIngredients()) fluidIn(p, f.getFluids(), f.amount() * p.loops);
        }
        // the result pool is weighted: the main result's weight is getOutputChance(), the rest is scrap
        double total = 0;
        for (ProcessingOutput o : sa.resultPool) total += o.getChance();
        if (total <= 0) total = 1;
        boolean first = true;
        for (ProcessingOutput o : sa.resultPool) {
            out(p, o.getStack(), first ? o.getChance() / total : o.getChance() / total);
            first = false;
        }
        if (sa.resultPool.isEmpty()) out(p, sa.getResultItem(ra), sa.getOutputChance());
        add(p, "create:sequenced");
    }

    private void readMelting(ResourceLocation id, MeltingRecipe m) {
        ItemStack[] items = m.getInput().getItems();
        if (items.length == 0) return;
        ItemStack first = items[0];
        IMeltingContainer inv = new IMeltingContainer() {
            @Override public ItemStack getStack() { return first; }
            @Override public IOreRate getOreRate() { return Config.COMMON.smelteryOreRate; }
        };
        FluidStack outFluid = m.getOutput(inv);
        if (outFluid.isEmpty()) return;
        Recipe p = new Recipe(id, Machine.MELTING, m.getTime());
        if (m.getClass().getSimpleName().contains("Damageable")) p.oddTag = "melts down worn gear";
        p.temperature = m.getTemperature();
        p.heat = m.getTemperature() + " C";
        ingredient(p, m.getInput(), 1);
        fluidOut(p, outFluid);
        add(p, "tconstruct:melting");
    }

    private void readAlloy(ResourceLocation id, AlloyRecipe a) {
        Recipe p = new Recipe(id, Machine.ALLOY, 0);
        p.temperature = a.getTemperature();
        p.heat = a.getTemperature() + " C";
        for (AlloyRecipe.AlloyIngredient in : a.getInputs()) {
            List<FluidStack> fl = in.fluid().getFluids();
            if (fl.isEmpty() || in.catalyst()) continue;
            fluidIn(p, fl.toArray(new FluidStack[0]), in.fluid().getAmount(fl.get(0).getFluid()));
        }
        fluidOut(p, a.getOutput());
        add(p, "tconstruct:alloying");
    }

    private void readCasting(ResourceLocation id, Machine m, IDisplayableCastingRecipe d) {
        ItemStack res = d.getOutput();
        List<FluidStack> fl = d.getFluids();
        if (res.isEmpty() || fl.isEmpty()) return;
        Recipe p = new Recipe(id, m, d.getCoolingTime());
        fluidIn(p, fl.toArray(new FluidStack[0]), fl.get(0).getAmount());
        if (d.hasCast() && d.isConsumed() && !d.getCastItems().isEmpty()) {
            List<ItemStack> casts = d.getCastItems();
            p.in.add(new Flow(key(casts.get(0)), 1, 1, alts(casts.toArray(new ItemStack[0]))));
        }
        out(p, res, 1);
        add(p, "tconstruct:casting");
    }

    private void readFuel(MeltingFuel f) {
        List<FluidStack> in = f.getInputs();
        if (in.isEmpty()) return;
        Fluid fluid = source(in.get(0).getFluid());
        fuels.add(new Fuel(BuiltInRegistries.FLUID.getKey(fluid), in.get(0).getHoverName().getString(),
                f.getAmount(fluid), f.getDuration(), f.getTemperature(), f.getRate()));
        counts.merge("tconstruct:fuel", 1, Integer::sum);
    }

    private void readCooking(ResourceLocation id, AbstractCookingRecipe c, RecipeType<?> type, RegistryAccess ra) {
        ItemStack res = c.getResultItem(ra);
        if (res.isEmpty() || c.getIngredients().isEmpty()) return;
        Machine m = type == RecipeType.SMELTING ? Machine.FURNACE : type == RecipeType.BLASTING ? Machine.BLAST_FURNACE
                : type == RecipeType.SMOKING ? Machine.SMOKER : null;
        if (m == null) return;
        Recipe p = new Recipe(id, m, c.getCookingTime());
        ingredient(p, c.getIngredients().get(0), 1);
        out(p, res, 1);
        add(p, "vanilla:" + m.name().toLowerCase());
        // Create's fans process the same recipes in bulk: smelting -> blasting, smoking -> smoking
        if (m == Machine.FURNACE || m == Machine.SMOKER) {
            Machine fan = m == Machine.FURNACE ? Machine.FAN_BLAST : Machine.FAN_SMOKE;
            Recipe v = new Recipe(id.withPrefix("fan/"), fan, 0);
            v.virtual = true;
            v.in.addAll(p.in);
            v.out.addAll(p.out);
            add(v, "virtual:" + fan.name().toLowerCase());
        }
    }

    private void addDrill(String name, String block, float hardness) {
        Recipe p = new Recipe(ResourceLocation.fromNamespaceAndPath("forgeward_planner", "drill/" + name), Machine.DRILL, 0);
        p.virtual = true;
        p.hardness = hardness;
        p.out.add(new Flow(Key.item(ResourceLocation.parse(block)), 1));
        add(p, "virtual:drill");
    }

    // ------------------------------------------------------------------ helpers

    private void add(Recipe p, String source) {
        if (p.out.isEmpty()) return;
        if (p.broken) { counts.merge("skipped:empty tag", 1, Integer::sum); return; }
        counts.merge(source, 1, Integer::sum);
        for (Flow f : p.out) {
            List<Recipe> l = byProduct.computeIfAbsent(f.key(), k -> new ArrayList<>());
            if (!l.contains(p)) l.add(p);
        }
        for (Flow f : p.in) {
            byInput.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(p);
            for (Key alt : f.alts()) byInput.computeIfAbsent(alt, k -> new ArrayList<>()).add(p);
        }
    }

    /**
     * What machines run besides their own recipe type, read from Create 6.0.10 with its default config: crushing wheels fall back to milling when an
     * item has no crushing recipe (findRecipe, measured); a mixer takes shapeless crafting of more than one item (allowShapelessInMixer); a press on
     * a basin takes 4 or 9 of one ingredient (allowShapedSquareInPress); mechanical crafters take any crafting; fans blast blasting recipes, smelting first.
     * Ids ending _manual_only are never automated. A blasting fan leaves alone what smokes and smelts to the same thing, so those leave the lists too.
     */
    private void addHidden() {
        java.util.Set<Recipe> every = new java.util.LinkedHashSet<>();
        for (var m : List.of(byProduct, fallbacks, extras)) for (List<Recipe> l : m.values()) every.addAll(l);
        java.util.Set<net.minecraft.world.item.Item> crushable = new java.util.HashSet<>(), smeltable = new java.util.HashSet<>();
        Map<net.minecraft.world.item.Item, java.util.Set<Key>> smokes = new HashMap<>();
        for (Recipe r : every) for (var in : r.read) for (ItemStack it : in.getItems()) {
            if (r.machine == Machine.CRUSHER) crushable.add(it.getItem());
            if (r.machine == Machine.FURNACE) smeltable.add(it.getItem());
            if (r.machine == Machine.SMOKER && !r.out.isEmpty()) smokes.computeIfAbsent(it.getItem(), k -> new java.util.HashSet<>()).add(r.out.get(0).key());
        }
        for (Recipe r : every) {
            if (r.id.getPath().endsWith("_manual_only")) continue;
            if (r.machine == Machine.MILLSTONE) {
                boolean left = false;
                for (var in : r.read) for (ItemStack it : in.getItems()) if (!crushable.contains(it.getItem())) left = true;
                if (left) listed(r, Machine.CRUSHER, "milling", r.time);
            } else if (r.machine == Machine.CRAFTING) {
                double count = 0;
                for (Flow f : r.in) count += f.amount();
                listed(r, Machine.MECH_CRAFTER, "crafting", 0);
                if (!r.shaped && count > 1) listed(r, Machine.MIXER, "shapeless crafting", 0);
                if (r.in.size() == 1 && (count == 4 || count == 9)) listed(r, Machine.COMPACTOR, "crafting in a square", 0);
            } else if (r.machine == Machine.BLAST_FURNACE) {
                boolean only = false;
                for (var in : r.read) for (ItemStack it : in.getItems()) if (!smeltable.contains(it.getItem())) only = true;
                if (only) listed(r, Machine.FAN_BLAST, "blasting", 0);
            }
        }
        for (Recipe r : every) {
            if (r.machine != Machine.FAN_BLAST || !r.virtual || r.out.isEmpty()) continue;
            boolean anyLeft = false;
            // a virtual fan recipe shares its smelting recipe's flows, and only those: the first item stands for a plain input
            for (Flow f : r.in) {
                var item = BuiltInRegistries.ITEM.get(f.key().id());
                if (!smokes.getOrDefault(item, java.util.Set.of()).contains(r.out.get(0).key())) anyLeft = true;
            }
            if (!anyLeft) leftAlone.add(r);
        }
        for (Recipe r : every) r.read.clear();
    }

    private void listed(Recipe r, Machine m, String via, int time) {
        Recipe v = new Recipe(r.id.withPrefix("via/" + m.name().toLowerCase() + "/"), m, time);
        v.virtual = true;
        v.via = via;
        v.oddTag = r.oddTag;
        v.in.addAll(r.in);
        v.out.addAll(r.out);
        for (Flow f : v.in) {
            listOnly.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(v);
            for (Key alt : f.alts()) listOnly.computeIfAbsent(alt, k -> new ArrayList<>()).add(v);
        }
        counts.merge("hidden:" + via, 1, Integer::sum);
    }

    /**
     * A recipe the forward view and the backward chooser offer, but the default never picks: an add-on machine (adding them to the defaults would
     * move the Build tool's numbers with no bad recipe gone), unless k has no other route at all: see {@link #fallbackFor}.
     */
    private void addExtra(Recipe p, String source) {
        counts.merge(source, 1, Integer::sum);
        for (Flow f : p.out) extras.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(p);
        indexInputs(p);
    }

    /** As {@link #addExtra}, and the default uses it when k has no other real route: otherwise a thing a player plainly makes would count as raw. */
    private void addFallback(Recipe p, String source) {
        counts.merge(source, 1, Integer::sum);
        for (Flow f : p.out) fallbacks.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(p);
        indexInputs(p);
    }

    private void indexInputs(Recipe p) {
        for (Flow f : p.in) {
            byInput.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(p);
            for (Key alt : f.alts()) byInput.computeIfAbsent(alt, k -> new ArrayList<>()).add(p);
        }
    }

    /** A recipe by its id, for a saved line; a Tinkers multi-recipe's first variant when the id has been split up. */
    public Recipe byId(ResourceLocation id) {
        if (ids.isEmpty()) {
            List<Map<Key, List<Recipe>>> all = List.of(byProduct, fallbacks, extras, listOnly);
            for (var m : all) for (List<Recipe> l : m.values()) for (Recipe r : l) ids.putIfAbsent(r.id, r);
        }
        return ids.get(id);
    }

    /** One recipe per thing a mechanical extruder can make, to start a forward line with. */
    public List<Recipe> extruders() { return extruders; }

    /** Every recipe that makes k: the indexed ones, then fallback and add-on routes. Recipes the default may use come from {@link #recipesFor}. */
    public List<Recipe> all(Key k) {
        List<Recipe> l = new ArrayList<>(recipesFor(k));
        l.addAll(fallbacks.getOrDefault(k, List.of()));
        l.addAll(extras.getOrDefault(k, List.of()));
        return l;
    }

    /** Machine recipes that take k, for the forward view: real routes first, odd ones last, plain crafting left out (it has no rate). */
    public List<Recipe> usesOf(Key k) {
        List<Recipe> l = new ArrayList<>();
        for (Recipe r : byInput.getOrDefault(k, List.of())) if (r.machine != Machine.CRAFTING && !leftAlone.contains(r) && !l.contains(r)) l.add(r);
        for (Recipe r : listOnly.getOrDefault(k, List.of())) if (!l.contains(r)) l.add(r);
        l.sort(Comparator.<Recipe, Boolean>comparing(r -> !r.out.isEmpty() && !odd(r, r.out.get(0).key()).isEmpty())
                .thenComparingInt(r -> rank(r.machine)).thenComparingInt(RecipeIndex::namespaceRank).thenComparing(r -> r.id.toString()));
        return l;
    }

    public static boolean isFreeFluid(Key k) { return k.fluid() && FREE.contains(k.id()); }

    public static Key key(ItemStack s) {
        String variant = "";
        if (s.getItem() instanceof IMaterialItem) {
            MaterialVariantId v = IMaterialItem.getMaterialFromStack(s);
            if (v != null && !v.getId().equals(slimeknights.tconstruct.library.materials.definition.IMaterial.UNKNOWN_ID))
                variant = v.toString();
        }
        return new Key(false, BuiltInRegistries.ITEM.getKey(s.getItem()), variant);
    }

    public static Fluid source(Fluid f) {
        return f instanceof FlowingFluid ff ? ff.getSource() : f;
    }

    public static Key key(FluidStack f) {
        return Key.fluid(BuiltInRegistries.FLUID.getKey(source(f.getFluid())));
    }

    private static List<Key> alts(ItemStack[] items) {
        List<Key> l = new ArrayList<>();
        for (int i = 1; i < items.length && i < 12; i++) l.add(key(items[i]));
        return l;
    }

    private static void ingredient(Recipe p, Ingredient in, double amount) {
        ItemStack[] items = in.getItems();
        if (items.length == 0) return;
        // 1.21 shows an ingredient on an EMPTY tag as a barrier named after the tag: nothing can fill it
        if (items[0].is(Items.BARRIER)) { p.broken = true; return; }
        // a tag's first entry is arbitrary (#c:cobblestones lists infested cobblestone first): show the plain item
        ItemStack rep = items[0];
        for (ItemStack it : items) if (plainer(it, rep)) rep = it;
        p.read.add(in);
        merge(p.in, new Flow(key(rep), amount * Math.max(1, rep.getCount()), 1, alts(items)));
    }

    /** Vanilla before modded, then the shorter id: iron_ingot over a modded iron, cobblestone over infested_cobblestone. */
    private static boolean plainer(ItemStack a, ItemStack b) {
        ResourceLocation ia = BuiltInRegistries.ITEM.getKey(a.getItem()), ib = BuiltInRegistries.ITEM.getKey(b.getItem());
        boolean va = ia.getNamespace().equals("minecraft"), vb = ib.getNamespace().equals("minecraft");
        if (va != vb) return va;
        return ia.getPath().length() < ib.getPath().length();
    }

    private static void ingredients(Recipe p, List<Ingredient> list) {
        for (Ingredient in : list) if (!in.isEmpty()) ingredient(p, in, 1);
    }

    private static void fluidIn(Recipe p, FluidStack[] fluids, int amount) {
        if (fluids.length == 0) return;
        List<Key> alts = new ArrayList<>();
        for (int i = 1; i < fluids.length && i < 8; i++) alts.add(key(fluids[i]));
        merge(p.in, new Flow(key(fluids[0]), amount, 1, alts));
    }

    private static void out(Recipe p, ItemStack s, double chance) {
        if (s.isEmpty()) return;
        merge(p.out, new Flow(key(s), s.getCount() * chance, chance, List.of()));
    }

    private static void fluidOut(Recipe p, FluidStack f) {
        if (f.isEmpty()) return;
        merge(p.out, new Flow(key(f), f.getAmount(), 1, List.of()));
    }

    /** Same key twice in one recipe (two iron in a shaped grid) -> one flow with the summed amount. */
    private static void merge(List<Flow> l, Flow f) {
        for (int i = 0; i < l.size(); i++) {
            Flow g = l.get(i);
            if (g.key().equals(f.key())) {
                l.set(i, new Flow(g.key(), g.amount() + f.amount(), Math.max(g.chance(), f.chance()), g.alts()));
                return;
            }
        }
        l.add(f);
    }

    // ------------------------------------------------------------------ queries

    public List<Recipe> recipesFor(Key k) {
        return byProduct.getOrDefault(k, List.of());
    }

    /** Lower is preferred: the machine an automated line would use first. */
    private static int rank(Machine m) {
        return switch (m) {
            case DRILL -> 0;
            case PRESS, MELTING, ALLOY, TABLE, BASIN_CAST -> 1;
            case CRUSHER, MIXER, COMPACTOR, SAW, FAN_WASH, FAN_HAUNT, SPOUT, SEQUENCED -> 2;
            case MILLSTONE, DEPLOYER, MECH_CRAFTER, FAN_BLAST, FAN_SMOKE -> 3;
            case BLAST_FURNACE, DRAIN -> 4;
            case FURNACE, SMOKER -> 5;
            case CRAFTING, SMITHING -> 6;
            case FAN_OTHER, EXTRUDER, SIFTER, ROLLING_MILL, BELT_GRINDER, GRINDSTONE, FOUNDRY_MIXER, FOUNDRY_LID, METAL_CAST, TESLA_COIL, BIG_CANNON_FOUNDRY, BASIN_LID -> 2;
        };
    }

    private static final TagKey<Item> ORES = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ores"));
    private static final TagKey<Item> RAW = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:raw_materials"));
    private static final TagKey<Item> CRUSHED = TagKey.create(Registries.ITEM, ResourceLocation.parse("create:crushed_raw_materials"));
    private static final TagKey<Item> STONES = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:stones"));
    private static final TagKey<Item> LOGS = TagKey.create(Registries.ITEM, ResourceLocation.parse("minecraft:logs"));
    private static final java.util.Set<String> GATHERED = java.util.Set.of("minecraft:pointed_dripstone", "minecraft:dripstone_block");

    /**
     * Natural blocks a player mines or chops (ores, raw ore, logs, andesite / diorite / granite and the other stones,
     * dripstone): raw by default, so a plan does not craft andesite out of diorite out of quartz ore. Plain stone and
     * cobblestone stay planned (smelting, drills). One click on the raw input plans it anyway.
     */
    public static boolean gatheredByDefault(Key k) {
        if (k.fluid()) return false;
        String id = k.id().toString();
        if (id.equals("minecraft:stone") || id.equals("minecraft:cobblestone")) return false;
        if (GATHERED.contains(id)) return true;
        var h = BuiltInRegistries.ITEM.getHolder(k.id());
        return h.isPresent() && (h.get().is(ORES) || h.get().is(RAW) || h.get().is(STONES) || h.get().is(LOGS));
    }

    /** Mined / crushed ore: the input an automated melting line is fed with. */
    private static boolean oreLike(Key k) {
        if (k.fluid()) return false;
        var h = BuiltInRegistries.ITEM.getHolder(k.id());
        return h.isPresent() && (h.get().is(ORES) || h.get().is(RAW) || h.get().is(CRUSHED));
    }

    /** Recycling, salvage and reverting routes, by the words their recipe ids use. */
    private static final java.util.regex.Pattern ODD_ID = java.util.regex.Pattern.compile(
            "(^|[/_:])(recycling|salvage|salvaging|uncrafting|revert|deoxidi[sz]\\w*|dewax\\w*|scraping|undye|color_washing)($|[/_])");

    private static boolean isGear(Key k) {
        if (k.fluid()) return false;
        return new ItemStack(BuiltInRegistries.ITEM.get(k.id())).isDamageableItem();
    }

    /**
     * Why this route is odd for k ("" = it isn't). Odd routes are labelled and sorted last, never removed: recipes a modpack adds itself (the kubejs
     * and forgeward namespaces) are deliberate and never odd, and Create's crushing/*_recycling are ore-stone routes, not salvage.
     */
    public String odd(Recipe r, Key k) {
        String ns = r.id.getNamespace();
        if (ns.equals("kubejs") || ns.equals("forgeward")) return "";
        if (!r.oddTag.isEmpty()) return r.oddTag;
        String id = r.id.toString();
        boolean oreRoute = id.startsWith("create:crushing/") && id.endsWith("_recycling") || id.equals("create:haunting/lapis_recycling");
        if (!oreRoute && ODD_ID.matcher(r.id.getPath()).find()) return "recycling or reverting";
        // infested blocks come from no recipe and nobody mines them on purpose (crushing one was gravel's old default)
        for (Flow f : r.in) if (!f.key().fluid() && f.key().id().getPath().startsWith("infested_")) return "needs an infested block";
        switch (r.machine) {
            case CRUSHER, MILLSTONE, MELTING, FURNACE, BLAST_FURNACE, FAN_BLAST -> {
                for (Flow f : r.in) {
                    boolean gear = isGear(f.key());
                    for (Key alt : f.alts()) gear &= isGear(alt);
                    if (gear) return "breaks down gear";
                }
            }
            case SEQUENCED -> {
                if (!r.out.isEmpty() && !r.out.get(0).key().equals(k)) return "assembly scrap";
            }
            default -> { }
        }
        return "";
    }

    private static final int ODD_PENALTY = 500;

    /** Lower is preferred, for both the default a plan uses and the list a player chooses from, so the two cannot disagree. */
    private int score(Recipe r, Key k) {
        int s = rank(r.machine) * 10;
        // an input nothing makes and nobody gathers (infested cobblestone) is a dead end: prefer an input the plan can source
        for (Flow f : r.in)
            if (recipesFor(f.key()).isEmpty() && !gatheredByDefault(f.key()) && !(f.key().fluid() && FREE.contains(f.key().id()))) s += 3;
        if (!r.primary(k)) s += 100;
        for (Flow f : r.in) if (f.key().equals(k)) s += 1000;
        s += r.in.size();
        if (!odd(r, k).isEmpty()) s += ODD_PENALTY;
        if (r.machine == Machine.MELTING) {
            String path = r.id.getPath();
            // Tinkers melts mob heads into metal (a zombie head is molten iron): a drop, never a production line
            if (path.contains("entity_melting") || path.contains("/heads/")) s += 50;
            for (Flow f : r.in) if (oreLike(f.key())) { s -= 5; break; }
        }
        return s;
    }

    /** A modpack's own recipes first (as in {@link #odd}), then vanilla and Create, then the rest, then the id, so equal scores always fall the same way. */
    private static int namespaceRank(Recipe r) {
        String ns = r.id.getNamespace();
        if (ns.equals("kubejs") || ns.equals("forgeward")) return 0;
        if (ns.equals("minecraft") || ns.equals("create")) return 1;
        return 2;
    }

    /** Equal scores fall by id, as they always have: a new tie-break would move callers' numbers with no bad recipe gone. */
    private Comparator<Recipe> ranking(Key k) {
        return Comparator.<Recipe>comparingInt(r -> score(r, k)).thenComparing(r -> r.id.toString());
    }

    /** Every recipe for k, best first; the odd ones last. This is the order the backward view's chooser shows, and {@link #pick} walks. */
    public List<Recipe> ranked(Key k) {
        List<Recipe> all = all(k);
        all.sort(ranking(k));
        return all;
    }

    /**
     * True when an input of r is itself made from k: r then only converts k's own product back (a snowball from cooling a snow block, molten
     * nethersteel from melting the ingot). That is not where k comes from, so it can't stand in for a real route. Goes by each recipe's main input, not its
     * tag's alternatives: every stone type is in the others' tag, and andesite is not made from cut andesite.
     */
    private boolean comesFrom(Recipe r, Key k) {
        for (Flow in : r.in) {
            for (Recipe back : all(in.key())) {
                if (back == r) continue;
                for (Flow f : back.in) if (f.key().equals(k)) return true;
            }
        }
        return false;
    }

    /** The fallback the default uses when k has no real route; none when k makes itself (its only input is k). */
    private Recipe fallbackFor(Key k) {
        Recipe best = null;
        List<Recipe> routes = new ArrayList<>(fallbacks.getOrDefault(k, List.of()));
        routes.addAll(extras.getOrDefault(k, List.of()));
        for (Recipe r : routes) {
            // a machine's guaranteed main product only: a sifter's 4% kelp is not how a belt gets made (counting it once costed belts in dirt)
            if (!r.primary(k) || score(r, k) >= 1000 || !odd(r, k).isEmpty() || comesFrom(r, k)) continue;
            if (best == null || ranking(k).compare(r, best) < 0) best = r;
        }
        return best;
    }

    /** The best-scored recipe for k, with no loop check (the loop check walks these). */
    private final Map<Key, Recipe> naive = new HashMap<>();

    private Recipe naiveBest(Key k) {
        if (k.fluid() && FREE.contains(k.id())) return null;
        if (gatheredByDefault(k)) return null;
        if (naive.containsKey(k)) return naive.get(k);
        Recipe best = recipesFor(k).isEmpty() ? null : java.util.Collections.min(recipesFor(k), ranking(k));
        if (best != null && (score(best, k) >= 1000 || !odd(best, k).isEmpty())) best = null;
        if (best == null) best = fallbackFor(k);
        naive.put(k, best);
        return best;
    }

    /** True when following the naive choices from r's inputs leads back to k within a few steps (ingot -> molten -> ingot). */
    private boolean loopsBack(Recipe r, Key k) {
        java.util.ArrayDeque<Key> todo = new java.util.ArrayDeque<>();
        java.util.Map<Key, Integer> depth = new HashMap<>();
        for (Flow f : r.in) { todo.add(f.key()); depth.put(f.key(), 1); }
        while (!todo.isEmpty()) {
            Key cur = todo.poll();
            if (cur.equals(k)) return true;
            int d = depth.get(cur);
            if (d >= 4) continue;
            Recipe nr = naiveBest(cur);
            if (nr == null) continue;
            for (Flow f : nr.in) if (!depth.containsKey(f.key())) { depth.put(f.key(), d + 1); todo.add(f.key()); }
        }
        return false;
    }

    public Recipe defaultRecipe(Key k) {
        if (k.fluid() && FREE.contains(k.id())) return null;
        if (gatheredByDefault(k)) return null;
        return pick(k);
    }

    /** The default even for a gathered item: used when the player asks for a plan OF that item. */
    public Recipe pick(Key k) {
        if (defaults.containsKey(k)) return defaults.get(k);
        List<Recipe> ranked = new ArrayList<>(recipesFor(k));
        ranked.sort(ranking(k));
        ranked.removeIf(r -> score(r, k) >= 1000);
        Recipe pick = null;
        // a real route that does not loop; then one that does (the solver cuts it and notes it). With only odd routes k comes from somewhere no
        // recipe knows, a boss drop say: it is raw, and the player can still choose a route from the list.
        for (Recipe r : ranked) if (odd(r, k).isEmpty() && !loopsBack(r, k)) { pick = r; break; }
        if (pick == null) for (Recipe r : ranked) if (odd(r, k).isEmpty()) { pick = r; break; }
        if (pick == null) pick = fallbackFor(k);
        defaults.put(k, pick);
        return pick;
    }

    public List<Fuel> fuels() { return fuels; }

    public Fuel fuel(Settings s) {
        if (s.fuel != null) for (Fuel f : fuels) if (f.fluid().equals(s.fuel)) return f;
        return fuels.isEmpty() ? null : fuels.get(0);
    }

    /** Create's stress impact (SU per RPM) of the machine's block, from the synced server config. */
    public double impact(Machine m) {
        if (m.block == null) return 0;
        try {
            return BlockStressValues.getImpact(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(m.block)));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Create's fanProcessingTime (server config, synced on join); 150 when no world is loaded. */
    public int fanTime() {
        try {
            return AllConfigs.server().kinetics.fanProcessingTime.get();
        } catch (Throwable t) {
            return 150;
        }
    }

    /** Blocks of a fan's stream an item gets at this RPM, from Create's server config, with the catalyst right at the fan: {@link Rates#fanStream}. */
    public double fanReach(float rpm) {
        try {
            var k = AllConfigs.server().kinetics;
            return Rates.fanStream(Rates.fanReach(rpm, k.fanRotationArgmax.get(), k.fanPushDistance.get()));
        } catch (Throwable t) {
            return Rates.fanStream(Rates.fanReach(rpm, 256, 20));
        }
    }

    /** One line per recipe type: "type seen read", for the Build diff. */
    public List<String> typeTable() {
        List<String> l = new ArrayList<>();
        for (var e : typesSeen.entrySet()) l.add(e.getKey() + " " + e.getValue() + " " + typesRead.getOrDefault(e.getKey(), 0));
        return l;
    }

    /** The self-report's coverage: how many types are read, and every type with nothing read, by count, biggest first. */
    public String typeCoverage() {
        List<Map.Entry<String, Integer>> none = new ArrayList<>();
        int some = 0;
        for (var e : typesSeen.entrySet()) {
            if (typesRead.getOrDefault(e.getKey(), 0) > 0) some++;
            else none.add(e);
        }
        none.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        StringBuilder sb = new StringBuilder(some + " of " + typesSeen.size() + " recipe types read; not read:");
        for (var e : none) sb.append(' ').append(e.getKey()).append(' ').append(e.getValue()).append(',');
        if (none.isEmpty()) sb.append(" none");
        else sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public WearBridge wear() { return wear; }

    public int size() {
        int n = 0;
        for (int c : counts.values()) n += c;
        return n;
    }

    /** What the calculator's lists gained, by route, for the log. */
    public String hiddenCounts() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        counts.forEach((k, v) -> { if (k.startsWith("hidden:")) m.put(k.substring(7), v); });
        return m + ", fan foods left alone " + leftAlone.size();
    }

    public String summary() {
        Map<String, Integer> bySrc = new LinkedHashMap<>();
        for (var e : counts.entrySet()) bySrc.merge(e.getKey().substring(0, e.getKey().indexOf(':')), e.getValue(), Integer::sum);
        return "recipes " + size() + " " + bySrc + ", products " + byProduct.size() + ", fuels " + fuels.size() + ", types not read " + unread
                + ", skipped " + skipped + "; detail " + counts;
    }
}
