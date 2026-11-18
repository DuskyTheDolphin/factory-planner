package com.forgeward.planner;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.item.ItemStack;


/** EMI side: the hovered stack for the planner's key. */
@EmiEntrypoint
public final class EmiHook implements EmiPlugin {
    /** Nothing to register: the planner reads EMI's hovered stack. A button on recipes never showed: EMI only draws recipe decorators in a development environment. */
    @Override
    public void register(EmiRegistry registry) {
    }

    /** The stack under the mouse in an EMI panel, or EMPTY. Not on a recipe page: this API doesn't see them. */
    static ItemStack hovered() {
        try {
            var hover = EmiApi.getHoveredStack(false);
            if (hover == null || hover.isEmpty()) return ItemStack.EMPTY;
            for (EmiStack s : hover.getStack().getEmiStacks()) {
                ItemStack is = s.getItemStack();
                if (!is.isEmpty()) return is;
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    static void showRecipes(net.minecraft.world.item.ItemStack stack) {
        EmiApi.displayRecipes(EmiStack.of(stack));
    }
}
