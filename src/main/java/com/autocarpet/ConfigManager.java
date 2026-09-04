package com.autocarpet;

import com.autocarpet.module.Module;
import com.autocarpet.module.Setting;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ConfigManager {
    private static final Gson GSON = new Gson();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("autocarpet.json");
    public final JsonObject root = new JsonObject();

    public void load(List<Module> modules) {
        try {
            if (Files.exists(this.file)) {
                JsonObject root = GSON.fromJson(Files.readString(this.file), JsonObject.class);
                if (root == null) return;
                for (Module module : modules) {
                    JsonObject mo = root.getAsJsonObject(module.name);
                    if (mo == null) continue;
                    if (mo.has("enabled")) {
                        boolean want = mo.get("enabled").getAsBoolean();
                        if (want && !module.enabled) {
                            module.enabled = true;
                            module.onActivate();
                        } else if (!want && module.enabled) {
                            module.enabled = false;
                            module.onDeactivate();
                        }
                    }
                    JsonObject so = mo.getAsJsonObject("settings");
                    if (so == null) continue;
                    for (Setting<?> s : module.settings) {
                        s.fromJson(so.get(s.name));
                    }
                }
            }
        }
        catch (IOException | RuntimeException exception) {
            exception.printStackTrace();
        }
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            for (Module module : AutoCraft.get().modules) {
                JsonObject mo = new JsonObject();
                mo.addProperty("enabled", module.enabled);
                JsonObject so = new JsonObject();
                for (Setting<?> s : module.settings) {
                    so.add(s.name, s.toJson());
                }
                mo.add("settings", so);
                root.add(module.name, mo);
            }
            Files.writeString(this.file, GSON.toJson(root));
        }
        catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}
