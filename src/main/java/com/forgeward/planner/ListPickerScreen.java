package com.forgeward.planner;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A list to choose one thing from, with a search box: recipes for a product, machines for an output, sources, power. Nothing changes until a row is
 * clicked. Text wraps to the window (GUI scale 4 is Auto at 1920x1080), so row heights differ; they are measured when they scroll into view.
 */
public final class ListPickerScreen<T> extends Screen {
    public record Row<T>(T value, ItemStack icon, String line1, String line2, int color, boolean current) {}

    private static final int MIN_ROW = 24, PAD = 8, TOP = 40, TEXT_X = PAD + 20;

    private final Screen parent;
    private final String help;
    private final List<Row<T>> all;
    private final Consumer<T> onPick;
    private List<Row<T>> shown;
    private EditBox search;
    private int scroll;
    /** Row tops of the last frame, for the mouse. */
    private final List<int[]> frame = new ArrayList<>();
    private final List<Row<T>> frameRows = new ArrayList<>();
    private int helpLines = 1;

    public ListPickerScreen(Screen parent, String title, String help, List<Row<T>> rows, Consumer<T> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.help = help;
        this.all = rows;
        this.shown = rows;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        heights.clear();
        search = new EditBox(font, PAD, 20, Math.min(200, width - 2 * PAD - 60), 16, Component.literal("search"));
        search.setHint(Component.literal("search"));
        search.setResponder(this::filter);
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width - PAD - 50, 19, 50, 18).build());
    }

    private void filter(String q) {
        String s = q.toLowerCase(Locale.ROOT).trim();
        List<Row<T>> l = new ArrayList<>();
        for (Row<T> r : all) if (s.isEmpty() || (r.line1() + " " + r.line2()).toLowerCase(Locale.ROOT).contains(s)) l.add(r);
        shown = l;
        scroll = 0;
    }

    private int textWidth() { return Math.max(60, width - TEXT_X - PAD); }

    private final java.util.Map<Row<T>, Integer> heights = new java.util.IdentityHashMap<>();

    private int rowHeight(Row<T> r) {
        Integer known = heights.get(r);
        if (known != null) return known;
        int lines = font.split(Component.literal(r.line1() + (r.current() ? "  (in use)" : "")), textWidth()).size() + font.split(Component.literal(r.line2()), textWidth()).size();
        int h = Math.max(MIN_ROW, lines * 10 + 4);
        heights.put(r, h);
        return h;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.renderBackground(g, mouseX, mouseY, partial);
        LineScreen.backing(g, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, title, PAD, 6, 0xFFFFFF);
        List<FormattedCharSequence> helpText = font.split(Component.literal(help), width - 2 * PAD);
        helpLines = helpText.size();
        int bottom = height - 6 - 10 * helpLines;
        int hy = bottom + 3;
        for (FormattedCharSequence seq : helpText) { g.drawString(font, seq, PAD, hy, 0x808080); hy += 10; }
        frame.clear();
        frameRows.clear();
        if (shown.isEmpty()) { g.drawString(font, "Nothing matches.", PAD, TOP + 4, 0xA0A0A0); return; }
        g.enableScissor(0, TOP, width, bottom);
        int y = TOP - scroll;
        for (int i = 0; i < shown.size() && y < bottom; i++) {
            Row<T> r = shown.get(i);
            int h = rowHeight(r);
            if (y + h > TOP) {
                boolean over = mouseY >= Math.max(y, TOP) && mouseY < Math.min(y + h, bottom) && mouseX >= PAD && mouseX < width - PAD;
                if (over) g.fill(PAD - 2, y, width - PAD, y + h - 1, 0x30FFFFFF);
                if (r.current()) g.fill(PAD - 2, y, PAD, y + h - 1, 0xFF60D060);
                if (r.icon() != null && !r.icon().isEmpty()) g.renderItem(r.icon(), PAD, y + 3);
                int ty = y + 2;
                for (FormattedCharSequence seq : font.split(Component.literal(r.line1() + (r.current() ? "  (in use)" : "")), textWidth())) { g.drawString(font, seq, TEXT_X, ty, r.color()); ty += 10; }
                for (FormattedCharSequence seq : font.split(Component.literal(r.line2()), textWidth())) { g.drawString(font, seq, TEXT_X, ty, 0xA0A0A0); ty += 10; }
                frame.add(new int[]{y, y + h});
                frameRows.add(r);
            }
            y += h;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (my < TOP || my >= height - 6 - 10 * helpLines || mx < PAD || mx >= width - PAD) return false;
        for (int i = 0; i < frame.size(); i++) {
            if (my >= frame.get(i)[0] && my < frame.get(i)[1]) {
                T v = frameRows.get(i).value();
                minecraft.setScreen(parent);
                onPick.accept(v);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        // the list's true height is only known by measuring it all: bound the scroll by what the last rows drawn say
        int next = (int) Math.max(0, scroll - dy * MIN_ROW);
        boolean atEnd = !frameRows.isEmpty() && frameRows.get(frameRows.size() - 1) == shown.get(shown.size() - 1)
                && frame.get(frame.size() - 1)[1] <= height - 6 - 10 * helpLines;
        if (dy > 0 || !atEnd) scroll = next;
        return true;
    }

    /** Picks the first row whose text has this in it, as a click on it would (for automation and tests). False when no row has it. */
    public boolean pickRow(String contains) {
        for (Row<T> r : shown) {
            if (!r.line1().contains(contains)) continue;
            minecraft.setScreen(parent);
            onPick.accept(r.value());
            return true;
        }
        return false;
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
