package immersive_aircraft.resources;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import immersive_aircraft.Main;
import immersive_aircraft.resources.bbmodel.BBModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class BBModelLoader extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {
    protected static final int PATH_SUFFIX_LENGTH = 8;
    protected static final int PATH_PREFIX_LENGTH = 8;

    public static final Map<ResourceLocation, BBModel> MODELS = new HashMap<>();
    private final Gson gson;

    public BBModelLoader() {
        gson = new Gson();
    }

    @Override
    protected @NotNull Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        HashMap<ResourceLocation, JsonElement> map = Maps.newHashMap();
        for (Map.Entry<ResourceLocation, Resource> entry : resourceManager.listResources("objects", n -> n.getPath().endsWith(".bbmodel")).entrySet()) {
            ResourceLocation location = entry.getKey();
            String name = location.getPath();
            ResourceLocation id = new ResourceLocation(location.getNamespace(), name.substring(PATH_PREFIX_LENGTH, name.length() - PATH_SUFFIX_LENGTH));
            try {
                BufferedReader reader = entry.getValue().openAsReader();
                try {
                    JsonElement jsonElement = GsonHelper.fromJson(this.gson, reader, JsonElement.class);

                    // --- FIX START: Sanitize JSON for Blockbench 5 compatibility ---
                    jsonElement = fixBlockbench5(jsonElement);
                    // --- FIX END ---

                    map.put(id, jsonElement);
                } finally {
                    ((Reader) reader).close();
                }
            } catch (JsonParseException | IOException | IllegalArgumentException exception) {
                Main.LOGGER.error("Couldn't parse data file {} from {}", id, location, exception);
            }
        }
        return map;
    }

    /**
     * Sanitizes the BBModel JSON to ensure compatibility with Blockbench 5 files.
     * It removes non-cube elements (which cause NPEs) and ensures resolution exists.
     */
    private JsonElement fixBlockbench5(JsonElement element) {
        if (!element.isJsonObject()) return element;
        JsonObject json = element.getAsJsonObject();

        // 1. Handle missing resolution (Defaults to 16x16 if missing in BB5)
        if (!json.has("resolution")) {
            JsonObject res = new JsonObject();
            res.addProperty("width", 16);
            res.addProperty("height", 16);
            json.add("resolution", res);
        }

        // 2. Filter invalid elements
        // BB5 may include non-cubes (like null objects for groups) in the 'elements' list
        // that lack 'from'/'to' vectors, causing crashes in the renderer.
        if (json.has("elements")) {
            JsonArray elements = json.getAsJsonArray("elements");
            Iterator<JsonElement> iterator = elements.iterator();
            while (iterator.hasNext()) {
                JsonElement e = iterator.next();
                if (!e.isJsonObject()) {
                    iterator.remove();
                    continue;
                }
                JsonObject obj = e.getAsJsonObject();

                // If it doesn't have coordinates, it's not a renderable cube -> Remove it
                if (!obj.has("from") || !obj.has("to")) {
                    iterator.remove();
                    continue;
                }

                // Optional: Explicitly remove items with type != cube if "type" exists
                if (obj.has("type") && !obj.get("type").getAsString().equals("cube")) {
                    iterator.remove();
                }
            }
        }
        return json;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsonMap, ResourceManager resourceManager, ProfilerFiller profiler) {
        MODELS.clear();
        jsonMap.forEach((identifier, jsonElement) -> MODELS.put(identifier, new BBModel(jsonElement.getAsJsonObject(), identifier)));
    }
}