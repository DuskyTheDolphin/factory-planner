package com.forgeward.planner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.part.IMaterialItem;
import slimeknights.tconstruct.tools.stats.HeadMaterialStats;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * forgeward_wear is optional: when it is installed a drill needs a Tinkers pick head, which wears 1 per block up to the
 * head material's durability and scales break speed by miningSpeed/6 ({@code com.forgeward.wear.Heads}, read by
 * reflection so this mod loads without it). Without it drills have no heads and run at base speed.
 */
public final class WearBridge {
    private Method max, speed;
    private boolean tried;

    public boolean present() {
        if (!tried) {
            tried = true;
            if (ModList.get().isLoaded("forgeward_wear")) {
                try {
                    Class<?> heads = Class.forName("com.forgeward.wear.Heads");
                    max = heads.getMethod("max", ItemStack.class);
                    speed = heads.getMethod("speedFactor", ItemStack.class);
                } catch (Throwable t) {
                    Planner.LOG.warn("[forgeward_planner] forgeward_wear present but Heads API not found: {}", t.toString());
                    max = speed = null;
                }
            }
        }
        return max != null;
    }

    public ItemStack head(Settings s) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("tconstruct:pick_head"));
        MaterialVariantId mat = MaterialVariantId.tryParse(s.head);
        if (!(item instanceof IMaterialItem mi) || mat == null) return ItemStack.EMPTY;
        return mi.withMaterial(mat);
    }

    /** Blocks one head lasts, or 0 without forgeward_wear. */
    public int max(Settings s) {
        if (!present()) return 0;
        try {
            return (int) max.invoke(null, head(s));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Break-speed multiplier of the chosen head (1 without forgeward_wear). */
    public double speedFactor(Settings s) {
        if (!present()) return 1;
        try {
            return (float) speed.invoke(null, head(s));
        } catch (Throwable t) {
            return 1;
        }
    }

    /** forgeward_wear's own tier check: may this head break the block? True without forgeward_wear (drills have no heads then). */
    public boolean canMine(Settings s, String block) {
        if (!present()) return true;
        try {
            var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)).defaultBlockState();
            return (boolean) Class.forName("com.forgeward.wear.Heads").getMethod("canMine", ItemStack.class, net.minecraft.world.level.block.state.BlockState.class).invoke(null, head(s), state);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Materials that have head stats, for cycling on the screen, in tier then id order. */
    public static List<String> headMaterials() {
        List<IMaterial> mats = new ArrayList<>();
        try {
            for (IMaterial m : MaterialRegistry.getMaterials()) {
                if (m.isHidden()) continue;
                if (MaterialRegistry.getInstance().getMaterialStats(m.getIdentifier(), HeadMaterialStats.ID).isPresent()) mats.add(m);
            }
        } catch (Throwable t) {
            return List.of("tconstruct:copper");
        }
        mats.sort((a, b) -> a.getTier() != b.getTier() ? Integer.compare(a.getTier(), b.getTier())
                : a.getIdentifier().toString().compareTo(b.getIdentifier().toString()));
        List<String> ids = new ArrayList<>();
        for (IMaterial m : mats) ids.add(m.getIdentifier().toString());
        return ids;
    }
}
