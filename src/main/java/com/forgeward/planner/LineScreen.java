package com.forgeward.planner;

import com.forgeward.planner.Line.Source;
import com.forgeward.planner.Line.SourceKind;
import com.forgeward.planner.Line.Step;
import com.forgeward.planner.Line.Transport;
import com.forgeward.planner.Model.Flow;
import com.forgeward.planner.Model.Key;
import com.forgeward.planner.Model.Recipe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The in-game calculator: "what speed and how many?" for one chain.
 * Top: the source. Middle: the machines chained from it in one straight path, each with count, RPM, what goes in and out and how busy it is. Bottom: what comes out,
 * and the stress. Everything recomputes from {@link Line#compute} on any change. Rows flow and wrap to the window, because GUI scale 4 is what Auto gives
 * at 1920x1080: widgets go through {@link #need}, and the top and bottom panels are measured with a dry pass, then drawn. Hit boxes are rebuilt every frame.
 */
public final class LineScreen extends Screen {
    private static final int PAD = 6, COLOR_OK = 0xFFFFFF, COLOR_DIM = 0xA0A0A0, COLOR_BAD = 0xFF7060, COLOR_WARN = 0xFFC060, COLOR_GOOD = 0x80E080, COLOR_INFO = 0x80C8FF;
    private static final int[] RPMS = {8, 16, 32, 48, 64, 96, 128, 192, 256};
    private static final int[] STACKS = {1, 4, 8, 16, 32, 48, 64};

    private record Hit(int x, int y, int w, int h, Runnable click, IntConsumer wheel, List<Component> tip, String label) {}

    private final Screen parent;
    private final Settings s = Settings.SESSION;
    private final List<Hit> hits = new ArrayList<>();
    /** What each row says: worked out once per change, since it searches RPMs and recomputes the line. */
    private final java.util.Map<Step, List<String>> said = new java.util.HashMap<>();
    private int scroll, contentHeight, topH = 48, panelH = 46;
    private EditBox editor;
    private IntConsumer editorApply;
    // the flow: where the next widget goes, the left edge a wrap returns to, the height of a line, the right limit
    private int fx, fy, fl, fh = 11, frMargin;
    private boolean dry;
    private GuiGraphics g;
    private int mouseX, mouseY;

    public LineScreen(Screen parent) {
        super(Component.literal("Factory calculator"));
        this.parent = parent;
    }

    private RecipeIndex idx() { return RecipeIndex.CURRENT; }
    private Line line() { return Lines.current(); }
    private void recompute() {
        // belts only here: a line saved by the old screen may name another transport
        line().forEach(st -> st.transport = Transport.BELT);
        line().compute(s, idx());
        said.clear();
        Lines.save();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - PAD - 42, 3, 42, 16).build());
        recompute();
    }

    // ------------------------------------------------------------------ the flow

    private void start(int x, int y, int lineHeight, int rightMargin) { fx = fl = x; fy = y; fh = lineHeight; frMargin = rightMargin; }

    private void brk() { fx = fl; fy += fh; }

    private void need(int w) { if (fx + w > width - PAD - frMargin && fx > fl) brk(); }

    private void tip(int x, int y, int w, int h, String... lines) {
        if (dry) return;
        List<Component> t = new ArrayList<>();
        for (String l : lines) t.add(Component.literal(l));
        hits.add(new Hit(x, y, w, h, null, null, t, ""));
    }

    private void text(String str, int color) {
        need(font.width(str));
        if (!dry) g.drawString(font, str, fx, fy, color);
        fx += font.width(str);
    }

    private void button(String label, int color, Runnable click, String tooltip) {
        int w = font.width(label) + 6;
        need(w);
        if (!dry) {
            boolean over = mouseX >= fx && mouseX < fx + w && mouseY >= fy - 1 && mouseY < fy + 9;
            g.fill(fx, fy - 1, fx + w, fy + 9, over ? 0x60FFFFFF : 0x30FFFFFF);
            g.drawString(font, label, fx + 3, fy, color);
            hits.add(new Hit(fx, fy - 1, w, 10, click, null, tooltip == null ? List.of() : List.of(Component.literal(tooltip)), label));
        }
        fx += w + 2;
    }

    /** "Label [-] 64 [+]": the wheel and the buttons step through a list, a click on the number types one. The cell fits four digits and the cursor. */
    private void number(String label, int value, int[] steps, int min, int max, IntConsumer set, String tooltip) {
        int cell = font.width("0000_") + 2;
        int whole = font.width(label + " ") + 2 * (font.width("-") + 8) + cell + 8;
        need(whole);
        text(label + " ", COLOR_DIM);
        IntConsumer clamp = v -> { set.accept(Math.max(min, Math.min(max, v))); recompute(); };
        button("-", COLOR_OK, () -> clamp.accept(stepped(value, steps, -1)), null);
        if (!dry) {
            boolean over = mouseX >= fx && mouseX < fx + cell && mouseY >= fy - 1 && mouseY < fy + 9;
            g.fill(fx, fy - 1, fx + cell, fy + 9, over ? 0x60FFFFFF : 0x18FFFFFF);
            if (editor == null || editor.getX() != fx + 2 || editor.getY() != fy) g.drawCenteredString(font, String.valueOf(value), fx + cell / 2, fy, COLOR_INFO);
            final int ex = fx, ey = fy;
            hits.add(new Hit(fx, fy - 1, cell, 10, () -> startEdit(ex, ey, cell, value, clamp), d -> clamp.accept(value + d * (shiftHeld() ? 8 : 1)),
                    tooltip == null ? List.of() : List.of(Component.literal(tooltip)), label + " value"));
        }
        fx += cell + 2;
        button("+", COLOR_OK, () -> clamp.accept(stepped(value, steps, 1)), null);
        fx += 4;
    }

    /** Shift held for {@link #wheelOver} driven from code: hasShiftDown reads the real key state, which a posted key may not reach. */
    private static boolean shiftForced;
    private static boolean shiftHeld() { return shiftForced || hasShiftDown(); }

    private static int stepped(int value, int[] steps, int dir) {
        if (steps == null) return value + dir;
        if (dir > 0) { for (int v : steps) if (v > value) return v; return steps[steps.length - 1]; }
        for (int i = steps.length - 1; i >= 0; i--) if (steps[i] < value) return steps[i];
        return steps[0];
    }

    private void startEdit(int x, int y, int w, int value, IntConsumer apply) {
        commitEdit();
        editor = new EditBox(font, x + 2, y, w - 2, 9, Component.literal("value"));
        editor.setBordered(false);
        editor.setMaxLength(4);
        editor.setValue(String.valueOf(value));
        editor.setFilter(t -> t.matches("[0-9]*"));
        editor.setFocused(true);
        editor.moveCursorToEnd(false);
        editor.setHighlightPos(0);    // all selected: typing replaces the number instead of going on the end of it (16 over 23 read 2316, so 256)
        editorApply = apply;
    }

    private void commitEdit() {
        if (editor == null) return;
        try { editorApply.accept(Integer.parseInt(editor.getValue())); } catch (NumberFormatException ignored) { }
        editor = null;
        editorApply = null;
    }

    private static int bandColor(double busy, boolean over) {
        if (over) return COLOR_BAD;
        if (busy >= 0.95) return COLOR_WARN;
        return COLOR_GOOD;
    }

    /** A sentence at the flow's left edge, wrapped to the window; the flow goes on under it. */
    private void message(String m, int color) {
        if (fx > fl) brk();
        for (var seq : font.split(Component.literal(m), Math.max(60, width - fl - PAD))) {
            if (!dry) g.drawString(font, seq, fl, fy, color);
            fy += 10;
        }
        fx = fl;
    }

    // ------------------------------------------------------------------ the screen

    /** A dark sheet over the world: text has to read over a bright shader sky and the hotbar, whatever the blur setting is. All three planner screens use it. */
    static ItemStack icon(Key k) {
        if (k.fluid()) {
            var f = BuiltInRegistries.FLUID.get(k.id());
            var bucket = f.getBucket();
            return bucket == net.minecraft.world.item.Items.AIR ? new ItemStack(net.minecraft.world.item.Items.BUCKET) : new ItemStack(bucket);
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(k.id()));
    }

    static void backing(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, 0xE8101216);
        g.flush();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mx, int my, float partial) {
        super.renderBackground(graphics, mx, my, partial);
        backing(graphics, width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float partial) {
        super.render(graphics, mx, my, partial);
        g = graphics;
        mouseX = mx;
        mouseY = my;
        hits.clear();
        Line line = line();
        // measure the two panels, then draw them
        dry = true;
        topH = drawSource(line.source);
        panelH = height - drawBottom(line, 0) + 0;
        dry = false;
        int panelTop = height - panelH;
        drawSource(line.source);

        int top = topH, bot = panelTop - 2;
        int fixedHits = hits.size();
        g.enableScissor(0, top, width, bot);
        int y = top - scroll + 2;
        y = drawChildren(line, line.source.output, line.roots, null, 0, y);
        contentHeight = y + scroll - top;
        g.disableScissor();
        // more than fits (GUI scale 4 is a small window): say so, or a row below the fold looks like it isn't there
        int view = bot - top, room = contentHeight - view;
        if (room > 0) {
            scroll = Math.min(scroll, room);
            int bar = Math.max(12, view * view / contentHeight), pos = top + (view - bar) * scroll / room;
            g.fill(width - 3, top, width - 1, bot, 0x40FFFFFF);
            g.fill(width - 3, pos, width - 1, pos + bar, 0xFFC0C0C0);
        } else {
            scroll = 0;
        }
        // what scrolled out of the list can't be clicked
        for (int i = hits.size() - 1; i >= fixedHits; i--) {
            Hit h = hits.get(i);
            if (h.y + h.h <= top || h.y >= bot) hits.remove(i);
        }
        drawBottom(line, panelTop);

        if (editor != null) {
            g.fill(editor.getX() - 2, editor.getY() - 1, editor.getX() + editor.getWidth(), editor.getY() + 9, 0xFF000000);
            editor.render(g, mx, my, partial);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.tip.isEmpty() && mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                g.renderComponentTooltip(font, h.tip, mx, my);
                break;
            }
        }
    }

    /** Returns the y under the source block. */
    private int drawSource(Source src) {
        int y = 22;
        if (!dry) g.renderItem(icon(src.output), PAD, y - 2);
        start(width - PAD - 12, y, 12, 0);
        button("x", COLOR_BAD, () -> { Lines.deleteCurrent(); scroll = 0; recompute(); }, "Clear this line");
        start(PAD + 18, y, 12, 14);
        button(Export.name(src.output), COLOR_OK, this::pickSource, "Change what starts the line");
        text(src.kind.label + "  ", COLOR_DIM);
        if (src.kind == SourceKind.SUPPLY) {
            number("per min", (int) Math.round(src.perMinute), new int[]{10, 30, 60, 120, 240, 480, 960}, 1, 9999, v -> src.perMinute = v, null);
            number("stack", src.stack, STACKS, 1, 64, v -> src.stack = v, "Items per stack as they arrive on the belt");
        } else {
            number(src.kind == SourceKind.EXTRUDER ? "extruders" : "drills", src.count, null, 1, 512, v -> src.count = v, null);
            number("RPM", (int) src.rpm, RPMS, 1, 256, v -> src.rpm = v, null);
            if (src.kind != SourceKind.EXTRUDER && idx().wear().present()) {
                String h = src.head.substring(src.head.indexOf(':') + 1).replace('_', ' ');
                button("head: " + h, COLOR_OK, () -> {
                    List<String> mats = WearBridge.headMaterials();
                    int i = mats.indexOf(src.head);
                    src.head = mats.get(Math.floorMod(i + (hasShiftDown() ? -1 : 1), mats.size()));
                    recompute();
                }, "Drill head material: speed and how long it lasts");
            }
        }
        brk();
        fy += 1;
        fh = 10;
        String rate = Export.amount(src.output, src.rate) + "/min";
        if (!dry) g.drawString(font, Component.literal(rate).withStyle(ChatFormatting.BOLD), fx, fy, COLOR_OK);
        fx += font.width(Component.literal(rate).withStyle(ChatFormatting.BOLD)) + 8;
        if (src.su > 0) text(Math.round(src.su) + " SU  ", COLOR_DIM);
        if (src.headsPerHour > 0) text(String.format("%.1f heads/h  ", src.headsPerHour), COLOR_DIM);
        message(src.note, src.headRefused ? COLOR_BAD : COLOR_DIM);
        return fy + 2;
    }

    private int drawChildren(Line line, Key key, List<Step> steps, Step parent, int depth, int y) {
        int x = PAD + depth * 10;
        for (Step st : List.copyOf(steps)) y = drawStep(line, st, depth, y);
        if (RecipeIndex.isFreeFluid(key)) return y;
        // one straight path: no branches on this screen
        if (!steps.isEmpty() || branched(parent)) return y;
        start(x, y, 11, 0);
        button("+ Add a machine for " + Export.name(key), COLOR_INFO, () -> pickMachine(key, steps, parent), "Choose what this goes into");
        return y + 12;
    }

    /** One output of this step already goes on. */
    private static boolean branched(Step parent) {
        if (parent == null) return false;
        for (List<Step> l : parent.next.values()) if (!l.isEmpty()) return true;
        return false;
    }

    private int drawStep(Line line, Step st, int depth, int y) {
        int base = PAD + depth * 10;
        Recipe r = st.recipe;
        g.renderItem(icon(st.input), base, y);

        // header: machine and what it makes; the outputs are icons, so its lines are icon height
        start(base + 18, y + 4, 17, 14);
        String heat = r.heat.isEmpty() ? "" : " (" + r.heat + ")";
        button(r.machine.label + heat, COLOR_OK, () -> pickRecipe(st), "Choose another recipe for " + Export.name(st.input));
        text(" > ", COLOR_DIM);
        for (Flow f : r.out) {
            String each = f.chance() < 1 ? percent(f.chance()) : Export.amount(f.key(), f.amount());
            int w = 17 + font.width(each) + 8;
            need(w);
            g.renderItem(icon(f.key()), fx, fy - 4);
            tip(fx, fy - 4, w, 16, Export.name(f.key()), f.chance() < 1 ? "A chance output: every rate here is the average" : "Per operation");
            fx += 17;
            text(each + "  ", f.chance() < 1 ? COLOR_WARN : COLOR_DIM);
        }
        int headerEnd = fy + 13;
        start(width - PAD - 12, y + 4, 11, 0);
        button("x", COLOR_BAD, () -> { line.remove(st); recompute(); }, "Remove this machine and everything after it");

        // what the player sets
        start(base + 18, headerEnd, 12, 0);
        number("count", st.count, null, 1, 512, v -> st.count = v, r.machine.unit);
        if (r.machine.kinetic) number("RPM", (int) st.rpm, RPMS, 1, 256, v -> st.rpm = v, null);
        boolean batch = switch (r.machine) { case CRUSHER, FAN_WASH, FAN_HAUNT, FAN_SMOKE, FAN_BLAST, FAN_OTHER -> true; default -> false; };
        number("stack", st.stackUsed, STACKS, 1, 64, v -> st.stack = v, batch
                ? "Items per stack arriving here. A big effect: wheels and fans work a whole stack at once" : "Items per stack arriving on the belt");
        boolean fan = Line.isFan(r.machine);
        if (fan) number("fans on this block", st.fans, null, 1, st.depot ? 5 : 6, v -> st.fans = v, "Time is the timer over the fans, rounded up. 5 fit a depot, 6 a belt block. Each costs its SU");
        if (!fan || !st.depot) number("belt RPM", (int) st.beltRpm, RPMS, 1, 256, v -> st.beltRpm = v, Line.transportNote(Transport.BELT));
        if (fan) button(st.depot ? "on a depot" : "on a belt", COLOR_OK, () -> { st.depot = !st.depot; if (st.depot && st.fans > 5) st.fans = 5; recompute(); }, "What the stack sits on in the stream");
        if (r.machine == Machine.PRESS || r.machine == Machine.SPOUT)
            button(st.depot ? "on a depot" : "on a belt", COLOR_OK, () -> { st.depot = !st.depot; recompute(); }, "What the machine works over");
        brk();

        // what it does
        fh = 10;
        int rowY = fy, rowX = fx;
        text("In " + Export.amount(st.input, st.offered) + "/min  ", st.overloaded ? COLOR_BAD : COLOR_OK);
        boolean first = true;
        for (var e : st.out.entrySet()) {
            String part = (first ? "Out " : "") + Export.amount(e.getKey(), e.getValue()) + "/min" + (st.out.size() > 1 ? ", " : "  ");
            int ix = fx, iy = fy;
            text(part, COLOR_OK);
            tip(ix, iy, fx - ix, 10, Export.name(e.getKey()));
            first = false;
        }
        boolean chance = false;
        for (Flow f : r.out) if (f.chance() < 1) chance = true;
        if (chance) text("(average)  ", COLOR_WARN);
        text(String.format("Busy %d%%  ", Math.round(st.busy * 100)), bandColor(st.busy, st.overloaded));
        if (st.su > 0) text(Math.round(st.su) + " SU", COLOR_DIM);
        tip(rowX, rowY, width - rowX - PAD, fy + 10 - rowY, timingTip(st));
        brk();

        for (String m : said.computeIfAbsent(st, k -> messages(line, k))) {
            int color = m.startsWith("!") ? COLOR_BAD : m.startsWith("~") ? COLOR_WARN : COLOR_DIM;
            message(m.replaceFirst("^[!~]", ""), color);
        }
        if (Line.isFan(r.machine) && !st.depot) drawFanBest(st);
        int ny = fy + 1;
        if (depth > 0) g.fill(base - 6, y, base - 5, ny - 2, 0x40FFFFFF);
        // the outputs go on to the next machine, or end here. Once one goes on, the others are byproducts, listed at the bottom
        boolean goesOn = branched(st);
        for (Flow f : r.out) {
            List<Step> kids = st.next.computeIfAbsent(f.key(), k -> new ArrayList<>());
            if (goesOn && kids.isEmpty()) continue;
            ny = drawChildren(line, f.key(), kids, st, depth + 1, ny);
        }
        return ny + 3;
    }

    /** The best this fan can do, with a button for each thing that would have to change: each sets only that. */
    private void drawFanBest(Step st) {
        double[] best = Line.fanBest(st, idx());
        if (best == null) { message("~No stack finishes in this fan's stream.".substring(1), COLOR_WARN); return; }
        int n = (int) best[0], belt = (int) best[1];
        if (fx > fl) brk();
        text(String.format("Best for this fan: stacks of %d at belt %d RPM, %.0f/min  ", n, belt, best[2]), COLOR_INFO);
        if (belt != (int) st.beltRpm) button("belt " + belt, COLOR_OK, () -> { st.beltRpm = belt; recompute(); }, "Sets the belt RPM only");
        if (n != st.stackUsed) button("stacks of " + n, COLOR_OK, () -> { st.stack = n; recompute(); }, "Sets the stack size only");
        brk();
    }

    private String timingTip(Step st) {
        String t = Double.isInfinite(st.ticks) ? "This machine does not run as set" : String.format("One machine: %.0f/min of %s, a cycle of %.0f ticks", st.capPerMachine, Export.name(st.input), st.ticks);
        return st.machineNote.isEmpty() ? t : t + ". " + st.machineNote;
    }

    /** What the row says. "!" = bad, "~" = careful, plain = information. */
    private List<String> messages(Line line, Step st) {
        List<String> out = new ArrayList<>();
        Machine m = st.recipe.machine;
        if (st.tooFast) {
            double blocks = idx().fanReach(st.rpm);
            int ok = (int) Math.floor(st.fanMaxBelt);
            out.add("!Belt too fast: " + String.format("%.1f", st.unprocessed) + "/min leave unprocessed and carry on as they came in. Stacks of " + st.stackUsed + " finish in " + String.format("%.1f", blocks) + " blocks of stream up to " + (ok >= 1 ? ok + " RPM" : "no belt speed") + ".");
            notMeasured(st, out);
            return out;
        }
        if (st.neverRuns && st.offered > 0) {
            out.add("!" + (st.machineNote.isEmpty() ? "This machine does not run as set." : st.machineNote));
            return out;
        }
        boolean fanOver = Line.isFan(m) && st.overloaded;
        if (fanOver && st.depot) out.add("!Overloaded: on a depot this takes " + String.format("%.1f", Math.min(st.cap, st.transportCap)) + "/min of the " + String.format("%.1f", st.offered) + " offered. More fans on this block or bigger stacks take more.");
        else if (fanOver) out.add("!Overloaded: this fan and belt take " + String.format("%.1f", Math.min(st.cap, st.transportCap)) + "/min of the " + String.format("%.1f", st.offered) + " offered. A faster belt leaves the stream unfinished. Bigger stacks take more, and a faster fan lengthens the stream so the belt can go faster.");
        if (st.transportLimits && !fanOver) {
            double per = st.transportCap;
            out.add("!" + st.transport.label + " is the limit: " + String.format("%.0f", per) + "/min here. " + Line.transportNote(st.transport) + ". More RPM or bigger stacks would help.");
        }
        if (!fanOver && st.offered > st.cap + 1e-6 && st.cap > 0) {
            int need = Line.neededAt(st, st.rpm, s, idx());
            int rpm = m.kinetic ? Line.rpmNeeded(st, st.count, 256, s, idx()) : -1;
            StringBuilder sb = new StringBuilder("!Overloaded: ");
            sb.append(need).append(" needed at ").append(m.kinetic ? (int) st.rpm + " RPM" : "this rate");
            if (m.kinetic) sb.append(rpm > 0 ? ", or " + rpm + " RPM with " + st.count : ", and no RPM is enough with " + st.count);
            double gain = line.gainOfOneMore(st, s, idx());
            sb.append(String.format(". One more adds %.1f/min.", gain));
            out.add(sb.toString());
        } else if (!fanOver && st.offered > 0 && !st.overloaded) {
            out.add("One more won't help: " + line.limitAbove(st) + " is the limit, at " + String.format("%.1f", st.offered) + "/min.");
        }
        if (!fanOver && st.offered > 0 && m.kinetic && st.capPerMachine > 0 && (st.overloaded || st.count > 1)) {
            StringBuilder sb = new StringBuilder("To match " + String.format("%.0f", st.offered) + "/min:");
            for (int r : new int[]{16, 32, 64, 128, 256}) {
                int n = Line.neededAt(st, r, s, idx());
                sb.append(' ').append(r).append(" RPM ").append(n > 0 ? "x" + n : "never").append(',');
            }
            sb.setLength(sb.length() - 1);
            out.add(sb.toString());
        }
        if (st.count > 1 && !st.depot && (m == Machine.PRESS || m == Machine.DEPLOYER || m == Machine.SPOUT))
            out.add("~Several on one belt don't share: the first takes every item it can. Split before them with a brass tunnel, one belt each.");
        if ((m == Machine.MIXER || m == Machine.FOUNDRY_MIXER) && !st.neverRuns)
            out.add("~Needs the next craft's inputs already in the basin; trickle fed, each craft takes " + (int) Rates.mixerStarved(st.recipe.time, st.rpm) + " ticks.");
        if ((Line.isFan(m) || m == Machine.FAN_OTHER) && !st.neverRuns && !st.depot)
            out.add("Fan RPM only sets reach; every stack in the stream runs its own timer.");
        // read from Create, not run: a belt doesn't merge what's on it, so a drill's items stay single until something inventories them
        if (st.parent == null && st.stackUsed > 1 && (line.source.kind == SourceKind.DRILL_GENERATOR || line.source.kind == SourceKind.DRILL_BLOCK))
            out.add("~A drill puts out single items. A belt doesn't merge them into stacks, so this rate needs a vault and a brass funnel between them (read from Create, not run).");
        notMeasured(st, out);
        if (st.count > 1 && m == Machine.CRUSHER) out.add("~Each pair works alone: this assumes a split upstream.");
        for (var e : st.supplied.entrySet()) out.add("You supply " + Export.amount(e.getKey(), e.getValue()) + "/min of " + Export.name(e.getKey()) + ".");
        return out;
    }

    /** Marks the timings that were read from a jar but not measured on a real machine. */
    private static void notMeasured(Step st, List<String> out) {
        Machine m = st.recipe.machine;
        if (!st.recipe.via.isEmpty()) {
            if (st.recipe.via.equals("milling")) out.add("Runs a milling recipe: the wheels fall back to it when an item has no crushing recipe. Measured.");
            else out.add("~Not measured: runs a " + st.recipe.via + " recipe, read from the jar.");
            return;
        }
        if (Line.isFan(m) && !st.depot && st.fans >= 3) out.add("~The belt limit with " + st.fans + " fans a block is on the safe side, not measured here.");
        switch (m) {
            case MELTING, ALLOY, TABLE, BASIN_CAST, SPOUT, SEQUENCED, DEPLOYER, MECH_CRAFTER, DRAIN, FAN_OTHER, BELT_GRINDER, GRINDSTONE,
                 FOUNDRY_MIXER, FOUNDRY_LID, METAL_CAST, TESLA_COIL, BIG_CANNON_FOUNDRY, BASIN_LID ->
                out.add("~Not measured on a real machine: " + st.machineNote);
            case FAN_HAUNT, FAN_BLAST -> { if (!st.depot) out.add("~Not measured free on a belt for this fan type. On a depot and held by a funnel it has been."); }
            default -> { }
        }
    }

    /** Returns the y it would start at to end on the window's bottom edge, in the dry pass (called with top 0 it returns the panel's height off the window). */
    private int drawBottom(Line line, int top) {
        int y = top + 2;
        if (!dry) g.fill(0, top, width, top + 1, 0x60FFFFFF);
        start(PAD, y, 10, 0);
        message("Out: " + flows(line.finished), COLOR_OK);
        if (!line.byproducts.isEmpty()) message("Byproducts (average): " + flows(line.byproducts), COLOR_DIM);
        if (!line.suppliedInputs.isEmpty()) message("You supply: " + flows(line.suppliedInputs), COLOR_DIM);
        if (!line.unprocessed.isEmpty()) message("Unprocessed: " + flows(line.unprocessed), COLOR_BAD);
        if (line.firstOverload != null) {
            message("First bottleneck: " + line.firstOverload.recipe.machine.label + ". Everything before it backs up; a generator drill loses what its belt won't take.", COLOR_BAD);
        } else {
            message(line.unprocessed.isEmpty() ? "No bottleneck: every step keeps up with what reaches it." : "Nothing backs up, but a fan's belt is too fast.", line.unprocessed.isEmpty() ? COLOR_GOOD : COLOR_WARN);
        }
        fh = 12;
        fy += 1;
        double su = line.totalSu;
        text("Stress " + Math.round(su) + " SU at these RPMs.", COLOR_OK);
        brk();
        return height - (fy - top) - 2;
    }

    private static String flows(java.util.Map<Key, Double> m) {
        if (m.isEmpty()) return "nothing yet";
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) sb.append(sb.length() > 0 ? ", " : "").append(Export.amount(e.getKey(), e.getValue())).append(' ').append(Export.name(e.getKey()));
        return sb.toString();
    }

    // ------------------------------------------------------------------ choosers

    private void pickSource() {
        List<ListPickerScreen.Row<Runnable>> rows = new ArrayList<>();
        for (Lines.Generator gen : Lines.generators()) {
            Key out = gen.output();
            rows.add(new ListPickerScreen.Row<>(() -> setSource(SourceKind.DRILL_GENERATOR, out, gen.hardness(), gen.block()), icon(out),
                    "Drill on a generator: " + Export.name(out), "Mining " + gen.label() + ", hardness " + gen.hardness() + "; the block never leaves when a belt, hopper or upward chute is under it", COLOR_OK, false));
        }
        for (Recipe ex : idx().extruders()) {
            Key out = ex.out.get(0).key();
            rows.add(new ListPickerScreen.Row<>(() -> setSource(SourceKind.EXTRUDER, out, 0, null), icon(out),
                    "Mechanical extruder: " + Export.name(out), "Makes it from the blocks either side; 200 cycle units per item", COLOR_OK, false));
        }
        Key cur = line().source.output;
        rows.add(new ListPickerScreen.Row<>(() -> setSource(SourceKind.SUPPLY, cur, 0, null), icon(cur), "A rate you set: " + Export.name(cur), "A chest, a farm, anything you already have", COLOR_OK, false));
        for (var item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem bi)) continue;
            float h = bi.getBlock().defaultDestroyTime();
            if (h < 0) continue;
            Key k = Key.item(BuiltInRegistries.ITEM.getKey(item));
            rows.add(new ListPickerScreen.Row<>(() -> setSource(SourceKind.DRILL_BLOCK, k, h, k.id().toString()), new ItemStack(item), "Drill on " + Export.name(k), "Hardness " + h + "; best case, the block has to come back by itself. Assumes it drops itself", COLOR_DIM, false));
        }
        for (var item : BuiltInRegistries.ITEM) {
            Key k = Key.item(BuiltInRegistries.ITEM.getKey(item));
            rows.add(new ListPickerScreen.Row<>(() -> setSource(SourceKind.SUPPLY, k, 0, null), new ItemStack(item), "Supply of " + Export.name(k), "At a rate you set", COLOR_DIM, false));
        }
        minecraft.setScreen(new ListPickerScreen<>(this, "What starts this line?", "Type to search every block and item.", rows, Runnable::run));
    }

    private void setSource(SourceKind kind, Key out, float hardness, String block) {
        Source src = line().source;
        src.kind = kind;
        src.output = out;
        src.hardness = hardness;
        if (block != null) src.block = block;
        line().roots.clear();
        recompute();
    }

    /** 12.5%, not 13%: the row's average is worked out from the real chance. */
    private static String percent(double chance) {
        double p = Math.round(chance * 1000) / 10.0;
        return (p == Math.floor(p) ? String.valueOf((int) p) : String.valueOf(p)) + "%";
    }

    private static String recipeLine(Recipe r, RecipeIndex idx, Key forOutput) {
        StringBuilder sb = new StringBuilder();
        if (!r.via.isEmpty()) sb.append("(a ").append(r.via).append(" recipe) ");
        for (Flow f : r.in) sb.append(sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ' ? " + " : "").append(Export.amount(f.key(), f.amount())).append(' ').append(Export.name(f.key()));
        sb.append(" > ");
        boolean first = true;
        for (Flow f : r.out) {
            sb.append(first ? "" : ", ").append(f.chance() < 1 ? percent(f.chance()) + " " : Export.amount(f.key(), f.amount()) + " ").append(Export.name(f.key()));
            first = false;
        }
        return sb.toString();
    }

    private void pickMachine(Key key, List<Step> into, Step parent) {
        List<ListPickerScreen.Row<Recipe>> rows = new ArrayList<>();
        for (Recipe r : idx().usesOf(key)) {
            Key out = r.out.isEmpty() ? key : r.out.get(0).key();
            String odd = idx().odd(r, out);
            rows.add(new ListPickerScreen.Row<>(r, icon(out), r.machine.label + (r.heat.isEmpty() ? "" : " (" + r.heat + ")") + (odd.isEmpty() ? "" : "  [" + odd + "]"),
                    recipeLine(r, idx(), out), odd.isEmpty() ? COLOR_OK : COLOR_DIM, false));
        }
        minecraft.setScreen(new ListPickerScreen<>(this, "What does " + Export.name(key) + " go into?", rows.isEmpty() ? "No machine in this pack takes this." : "Best routes first; odd routes last, marked.", rows, r -> {
            float rpm = parent != null ? parent.rpm : line().source.kind == SourceKind.SUPPLY ? 64 : line().source.rpm;
            into.add(new Step(r, key, rpm));
            recompute();
        }));
    }

    private void pickRecipe(Step st) {
        List<ListPickerScreen.Row<Recipe>> rows = new ArrayList<>();
        for (Recipe r : idx().usesOf(st.input)) {
            Key out = r.out.isEmpty() ? st.input : r.out.get(0).key();
            String odd = idx().odd(r, out);
            rows.add(new ListPickerScreen.Row<>(r, icon(out), r.machine.label + (odd.isEmpty() ? "" : "  [" + odd + "]"), recipeLine(r, idx(), out), odd.isEmpty() ? COLOR_OK : COLOR_DIM, r == st.recipe));
        }
        minecraft.setScreen(new ListPickerScreen<>(this, "Recipes that take " + Export.name(st.input), "Picking one replaces this machine and what follows it.", rows, r -> {
            if (r == st.recipe) return;
            List<Step> holder = st.parent == null ? line().roots : null;
            Step fresh = new Step(r, st.input, st.rpm);
            fresh.count = st.count;
            if (!Line.isFan(r.machine)) fresh.beltRpm = st.beltRpm;
            fresh.stack = st.stack;
            fresh.transport = st.transport;
            if (holder == null) {
                for (List<Step> l : st.parent.next.values()) if (l.contains(st)) holder = l;
            }
            if (holder != null) holder.set(holder.indexOf(st), fresh);
            recompute();
        }));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (editor != null) {
            if (key == 257 || key == 335) { commitEdit(); return true; }   // enter
            if (key == 256) { editor = null; return true; }                  // escape cancels the edit, not the screen
            return editor.keyPressed(key, scan, mods);
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (editor != null) return editor.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (editor != null) {
            if (editor.mouseClicked(mx, my, button)) return true;
            commitEdit();
        }
        if (super.mouseClicked(mx, my, button)) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.click != null && mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                h.click.run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.wheel != null && mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                h.wheel.accept(dy > 0 ? 1 : -1);
                return true;
            }
        }
        int max = Math.max(0, contentHeight - (height - panelH - 2 - topH));
        scroll = (int) Math.max(0, Math.min(max, scroll - dy * 14));
        return true;
    }

    // ------------------------------------------------------------------ driving the screen from code (automation, tests)
    // Each goes through the screen's own input handlers, so it does exactly what the same mouse or keyboard input would.

    /** Clicks the last-drawn row button whose label starts with this, through the normal mouse path. False when there is none. */
    public boolean clickLabel(String label) {
        for (Hit h : hits) if (h.label.startsWith(label) && h.click != null) return mouseClicked(h.x + 1, h.y + 1, 0);
        for (var w : children()) if (w instanceof Button b && b.getMessage().getString().startsWith(label)) { b.onPress(); return true; }
        return false;
    }

    /** Recomputes the line after its fields were changed from code. */
    public void refresh() { recompute(); }

    /** A click on the number with this label, these digits typed and Enter, through the normal paths. False when no such number is drawn. */
    public boolean typeNumber(String label, String digits) {
        for (Hit h : hits) if (h.label.equals(label) && h.click != null) {
            mouseClicked(h.x + 1, h.y + 1, 0);
            for (char c : digits.toCharArray()) charTyped(c, 0);
            keyPressed(257, 0, 0);
            return true;
        }
        return false;
    }

    /** The wheel, up, over the number with this label (Shift held when shift is true), through the normal path. False when no such number is drawn. */
    public boolean wheelOver(String label, boolean shift) {
        shiftForced = shift;
        try {
            for (Hit h : hits) if (h.label.equals(label) && h.wheel != null) return mouseScrolled(h.x + 1, h.y + 1, 0, 1);
            return false;
        } finally {
            shiftForced = false;
        }
    }

    /** The mouse wheel, down, as far as the list goes. */
    public void scrollToEnd() { mouseScrolled(width / 2.0, topH + 1, 0, -100); }

    @Override
    public void onClose() { commitEdit(); Lines.save(); minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
