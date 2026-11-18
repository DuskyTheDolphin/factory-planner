package com.forgeward.planner;

import com.forgeward.planner.Line.Step;
import com.forgeward.planner.Model.Key;
import com.forgeward.planner.Model.Recipe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The player's forward lines, kept between sessions in forgeward-plans/lines.json. P on an item always starts from that item: a new line, unless the
 * current one is still untouched; up to 50 lines are kept, the oldest dropped first. A saved step whose recipe is gone is dropped with a log line.
 */
public final class Lines {
    private Lines() {}

    private static final List<Line> ALL = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX = 50;
    private static int at;
    private static boolean loaded;

    public record Generator(Key output, String block, String label, float hardness) {}

    /** Blocks Create's generator shortcut applies to: what fluids make, what the drill then hands on (stone drops cobblestone without silk touch). */
    public static List<Generator> generators() {
        List<Generator> l = new ArrayList<>();
        add(l, "minecraft:cobblestone", "minecraft:cobblestone", "cobblestone");
        add(l, "minecraft:stone", "minecraft:cobblestone", "stone");
        add(l, "minecraft:basalt", "minecraft:basalt", "basalt");
        add(l, "create:limestone", "create:limestone", "limestone");
        add(l, "create:scoria", "create:scoria", "scoria");
        return l;
    }

    private static void add(List<Generator> l, String block, String out, String label) {
        ResourceLocation id = ResourceLocation.parse(block);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) return;
        l.add(new Generator(Key.item(ResourceLocation.parse(out)), block, label, BuiltInRegistries.BLOCK.get(id).defaultDestroyTime()));
    }

    public static Line current() {
        if (!loaded) load();
        if (ALL.isEmpty()) ALL.add(new Line());
        at = Math.floorMod(at, ALL.size());
        return ALL.get(at);
    }

    public static void addNew() {
        current();
        ALL.add(new Line());
        at = ALL.size() - 1;
        while (ALL.size() > MAX) { ALL.remove(0); at--; }
    }

    public static void deleteCurrent() {
        current();
        ALL.remove(at);
        if (at >= ALL.size()) at = ALL.size() - 1;
        current();
    }

    public static void step(int dir) {
        current();
        at = Math.floorMod(at + dir, ALL.size());
    }

    public static String position() {
        current();
        return (at + 1) + "/" + ALL.size();
    }

    /** P over an item: the line starts from it. The current line is reused only when nothing has been done with it yet. */
    public static void startWith(Key k) {
        Line l = current();
        if (!l.roots.isEmpty()) { addNew(); l = current(); }
        // a line reused for a new item starts like a new line: the last item's rate, stack and count aren't this one's. The head stays, it's what the player owns
        l.source.perMinute = 60;
        l.source.stack = 1;
        l.source.count = 1;
        l.source.rpm = 64;
        for (Generator g : generators()) {
            if (g.output().equals(k)) {
                l.source.kind = Line.SourceKind.DRILL_GENERATOR;
                l.source.output = k;
                l.source.hardness = g.hardness();
                l.source.block = g.block();
                return;
            }
        }
        l.source.kind = Line.SourceKind.SUPPLY;
        l.source.output = k;
    }

    // ------------------------------------------------------------------ saved between sessions

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("forgeward-plans").resolve("lines.json");
    }

    public static void save() {
        if (!loaded) return;
        try {
            JsonArray lines = new JsonArray();
            for (Line l : ALL) lines.add(write(l));
            JsonObject root = new JsonObject();
            root.addProperty("at", at);
            root.add("lines", lines);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root));
        } catch (Exception e) {
            Planner.LOG.warn("[forgeward_planner] could not save the lines: {}", e.toString());
        }
    }

    private static void load() {
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            RecipeIndex idx = RecipeIndex.CURRENT;
            int dropped = 0;
            for (var e : root.getAsJsonArray("lines")) dropped += read(e.getAsJsonObject(), idx);
            at = root.has("at") ? root.get("at").getAsInt() : 0;
            Planner.LOG.info("[forgeward_planner] lines loaded: {}, steps dropped because their recipe is gone: {}", ALL.size(), dropped);
        } catch (Exception e) {
            Planner.LOG.warn("[forgeward_planner] could not read the saved lines: {}", e.toString());
        }
    }

    private static String keyString(Key k) { return (k.fluid() ? "fluid:" : "") + k.id() + (k.variant().isEmpty() ? "" : "#" + k.variant()); }

    private static Key keyOf(String s) {
        boolean fluid = s.startsWith("fluid:");
        if (fluid) s = s.substring(6);
        String[] iv = s.split("#", 2);
        return new Key(fluid, ResourceLocation.parse(iv[0]), iv.length > 1 ? iv[1] : "");
    }

    private static JsonObject write(Line l) {
        JsonObject o = new JsonObject();
        Line.Source s = l.source;
        o.addProperty("kind", s.kind.name());
        o.addProperty("output", keyString(s.output));
        o.addProperty("hardness", s.hardness);
        o.addProperty("block", s.block);
        o.addProperty("count", s.count);
        o.addProperty("rpm", s.rpm);
        o.addProperty("perMinute", s.perMinute);
        o.addProperty("stack", s.stack);
        o.addProperty("head", s.head);
        if (l.powerLabel != null) o.addProperty("power", l.powerLabel);
        o.addProperty("powerHave", l.powerHave);
        o.add("roots", writeSteps(l.roots));
        return o;
    }

    private static JsonArray writeSteps(List<Step> steps) {
        JsonArray a = new JsonArray();
        for (Step st : steps) {
            JsonObject o = new JsonObject();
            o.addProperty("recipe", st.recipe.id.toString());
            o.addProperty("input", keyString(st.input));
            o.addProperty("count", st.count);
            o.addProperty("rpm", st.rpm);
            o.addProperty("belt", st.beltRpm);
            o.addProperty("stack", st.stack);
            o.addProperty("depot", st.depot);
            o.addProperty("fans", st.fans);
            o.addProperty("transport", st.transport.name());
            JsonObject next = new JsonObject();
            for (Map.Entry<Key, List<Step>> e : st.next.entrySet()) if (!e.getValue().isEmpty()) next.add(keyString(e.getKey()), writeSteps(e.getValue()));
            o.add("next", next);
            a.add(o);
        }
        return a;
    }

    private static int read(JsonObject o, RecipeIndex idx) {
        Line l = new Line();
        Line.Source s = l.source;
        s.kind = Line.SourceKind.valueOf(o.get("kind").getAsString());
        s.output = keyOf(o.get("output").getAsString());
        s.hardness = o.get("hardness").getAsFloat();
        if (o.has("block")) s.block = o.get("block").getAsString();
        s.count = o.get("count").getAsInt();
        s.rpm = o.get("rpm").getAsFloat();
        s.perMinute = o.get("perMinute").getAsDouble();
        s.stack = o.get("stack").getAsInt();
        s.head = o.get("head").getAsString();
        if (o.has("power")) l.powerLabel = o.get("power").getAsString();
        l.powerHave = o.has("powerHave") ? o.get("powerHave").getAsInt() : 0;
        int dropped = readSteps(o.getAsJsonArray("roots"), l.roots, idx);
        ALL.add(l);
        return dropped;
    }

    private static int readSteps(JsonArray a, List<Step> into, RecipeIndex idx) {
        int dropped = 0;
        for (var e : a) {
            JsonObject o = e.getAsJsonObject();
            Recipe r = idx.byId(ResourceLocation.parse(o.get("recipe").getAsString()));
            if (r == null) { dropped++; continue; }
            Step st = new Step(r, keyOf(o.get("input").getAsString()), o.get("rpm").getAsFloat());
            st.count = o.get("count").getAsInt();
            st.beltRpm = o.get("belt").getAsFloat();
            st.stack = o.get("stack").getAsInt();
            st.depot = o.get("depot").getAsBoolean();
            if (o.has("fans")) st.fans = o.get("fans").getAsInt();
            st.transport = Line.Transport.valueOf(o.get("transport").getAsString());
            JsonObject next = o.getAsJsonObject("next");
            for (String k : next.keySet()) {
                List<Step> kids = new ArrayList<>();
                dropped += readSteps(next.getAsJsonArray(k), kids, idx);
                if (!kids.isEmpty()) st.next.put(keyOf(k), kids);
            }
            into.add(st);
        }
        return dropped;
    }
}
