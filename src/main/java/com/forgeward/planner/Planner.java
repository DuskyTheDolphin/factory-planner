package com.forgeward.planner;

import com.forgeward.planner.Model.Key;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory Planner: client-only, an in-game production calculator for Create and Tinkers' Construct recipes.
 * Opened with its key over any item in a GUI (EMI's lists, the hovered slot otherwise; not EMI's recipe pages), an optional
 * in-game key for the held item, or /fwplan &lt;item&gt; [per minute].
 *
 * <p>Self-report: a "ready" line at mod construction (before the title screen), then every recipe sync logs one line with the index counts per source, the {@link SelfCheck} result, the
 * forgeward_wear head reading, and a sample plan per reference product, so a source that stops reading or a timing
 * that drifts is visible in the client log without opening the screen.</p>
 */
@Mod(value = Planner.MODID, dist = Dist.CLIENT)
public final class Planner {
    public static final String MODID = "forgeward_planner";
    public static final Logger LOG = LogUtils.getLogger();

    static final KeyMapping PLAN_HOVERED = new KeyMapping("key.forgeward_planner.plan", KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM, 80 /* GLFW_KEY_P */, "key.categories.forgeward_planner");
    static final KeyMapping PLAN_HELD = new KeyMapping("key.forgeward_planner.plan_held", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.forgeward_planner");

    /** Reference products for the log's sample plans (each is skipped when the pack has no recipe for it). */
    private static final String[] SAMPLES = {"create:iron_sheet", "create:andesite_alloy", "create:brass_ingot",
            "tconstruct:seared_brick", "create:precision_mechanism", "minecraft:iron_ingot", "tconstruct:pick_head#tconstruct:copper"};

    private static boolean materialsWereLoaded;

    public Planner(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> { e.register(PLAN_HOVERED); e.register(PLAN_HELD); });
        NeoForge.EVENT_BUS.addListener(RecipesUpdatedEvent.class, e -> rebuild(e.getRecipeManager(), "recipe sync"));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyPressed.Pre.class, Planner::onScreenKey);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            while (PLAN_HELD.consumeClick()) {
                var mc = Minecraft.getInstance();
                if (mc.player != null && !mc.player.getMainHandItem().isEmpty()) openForward(RecipeIndex.key(mc.player.getMainHandItem()), null);
            }
        });
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> e.getDispatcher().register(
                Commands.literal("fwplan")
                        .executes(c -> { Minecraft.getInstance().tell(() -> openForward(null, null)); return 1; })
                        .then(Commands.argument("item", ItemArgument.item(e.getBuildContext()))
                                .executes(c -> command(ItemArgument.getItem(c, "item").createItemStack(1, false), -1))
                                .then(Commands.argument("perMinute", DoubleArgumentType.doubleArg(0.01, 1e6))
                                        .executes(c -> command(ItemArgument.getItem(c, "item").createItemStack(1, false),
                                                DoubleArgumentType.getDouble(c, "perMinute")))))));
        LOG.info("[forgeward_planner] 2.0.1 ready: key 'Planner: hovered item' (default P) over an item in a GUI or EMI's lists, key 'Planner: held item' "
                        + "(unbound by default), /fwplan; forgeward_wear {}; the recipe index builds when a world's recipes arrive",
                ModList.get().isLoaded("forgeward_wear") ? "present (drill heads counted)" : "absent (drills without heads)");
    }

    /** /fwplan <item> [per minute]: the calculator with that item as its source. */
    private static int command(ItemStack stack, double rate) {
        // the chat screen closes after the command runs; open on the next tick
        Minecraft.getInstance().tell(() -> {
            ensureIndex();
            Key k = RecipeIndex.key(stack);
            Lines.startWith(k);
            // a rate typed is a rate you set, even for what a drill could make; set before the screen opens, or it draws the old one
            if (rate > 0) {
                Line.Source src = Lines.current().source;
                src.kind = Line.SourceKind.SUPPLY;
                src.output = k;
                src.perMinute = rate;
            }
            Minecraft.getInstance().setScreen(new LineScreen(null));
        });
        return 1;
    }

    static RegistryAccess registries() {
        var mc = Minecraft.getInstance();
        return mc.getConnection() != null ? mc.getConnection().registryAccess() : null;
    }

    /** Re-read every recipe. Also re-run on open when Tinkers' materials arrived after the recipes (part casting). */
    static void rebuild(RecipeManager rm, String why) {
        RegistryAccess ra = registries();
        if (rm == null || ra == null) return;
        long t0 = System.nanoTime();
        RecipeIndex idx = RecipeIndex.build(rm, ra);
        RecipeIndex.CURRENT = idx;
        materialsWereLoaded = MaterialRegistry.isFullyLoaded();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info("[forgeward_planner] index ({}, {} ms, tinkers materials loaded {}): {}", why, ms, materialsWereLoaded, idx.summary());
        LOG.info("[forgeward_planner] self-check {}", SelfCheck.run());
        LOG.info("[forgeward_planner] recipe types: {}", idx.typeCoverage());
        Settings probe = new Settings();
        WearBridge w = idx.wear();
        LOG.info("[forgeward_planner] forgeward_wear {}: head {} lasts {} blocks, speed x{}, fuels {}",
                w.present() ? "present" : "absent", probe.head, w.max(probe), String.format("%.2f", w.speedFactor(probe)),
                idx.fuels().stream().map(f -> f.fluid().getPath() + " rate " + f.rate() + " " + f.temperature() + "C").toList());
        List<String> samples = new ArrayList<>();
        for (String id : SAMPLES) {
            String[] iv = id.split("#");
            Key k = new Key(false, ResourceLocation.parse(iv[0]), iv.length > 1 ? iv[1] : "");
            if (idx.recipesFor(k).isEmpty()) { samples.add(id + " NO RECIPE"); continue; }
            Solver.Plan p = Solver.solve(k, 60, probe, idx);
            samples.add(id + " 60/min: " + p.steps.size() + " steps, " + p.machineCount() + " machines, "
                    + Math.round(p.su) + " SU, raw " + Export.flowsText(p.raw) + ", via " + p.steps.stream().map(st -> st.product.id().getPath() + "<" + st.recipe.machine.name().toLowerCase()).toList());
        }
        LOG.info("[forgeward_planner] sample plans @64 RPM: {}", String.join(" | ", samples));
        LOG.info("[forgeward_planner] calculator on: {} machine kinds, generators {}; hidden routes in its lists {}; sample: {}",
                Machine.values().length, Lines.generators().size(), idx.hiddenCounts(), sampleLine(idx, probe));
    }

    /** The complaint's line, computed on every sync so a timing that drifts shows in the log: a drill, then a millstone that can't keep up. */
    public static String sampleLine(RecipeIndex idx, Settings s) {
        Key cobble = Key.item(ResourceLocation.parse("minecraft:cobblestone"));
        Line l = new Line();
        l.source.rpm = 256;
        l.source.head = s.head;
        for (Model.Recipe r : idx.usesOf(cobble)) {
            if (r.machine == Machine.MILLSTONE) { l.roots.add(new Line.Step(r, cobble, 64)); break; }
        }
        l.compute(s, idx);
        return LineText.text(l, s, idx).replace('\n', '|');
    }

    static void ensureIndex() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (RecipeIndex.CURRENT.size() == 0 || (!materialsWereLoaded && MaterialRegistry.isFullyLoaded()))
            rebuild(mc.level.getRecipeManager(), "on open");
    }

    private static void onScreenKey(ScreenEvent.KeyPressed.Pre e) {
        if (!PLAN_HOVERED.isActiveAndMatches(InputConstants.getKey(e.getKeyCode(), e.getScanCode()))) return;
        Screen screen = e.getScreen();
        if (screen instanceof LineScreen || screen instanceof ListPickerScreen<?> || screen.getFocused() instanceof EditBox) return;
        ItemStack stack = EmiHook.hovered();
        if (stack.isEmpty() && screen instanceof AbstractContainerScreen<?> acs && acs.getSlotUnderMouse() != null)
            stack = acs.getSlotUnderMouse().getItem();
        if (stack.isEmpty()) return;
        openForward(RecipeIndex.key(stack), screen);
        e.setCanceled(true);
    }

    /** The forward line (what P opens): k, when given, starts the line or a new one. */
    public static void openForward(Key k, Screen parent) {
        ensureIndex();
        if (k != null) Lines.startWith(k);
        Minecraft.getInstance().setScreen(new LineScreen(parent));
    }

    /**
     * A stable entry point for other mods, which look it up by reflection together with {@link Export#name}: keep this exact signature.
     * It shows EMI's recipes for the item.
     */
    public static void open(ItemStack stack, Screen parent, Double rate) {
        EmiHook.showRecipes(stack);
    }

    static void chat(String msg) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
    }
}
