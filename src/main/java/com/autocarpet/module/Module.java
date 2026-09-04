package com.autocarpet.module;

import com.autocarpet.AutoCraft;
import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    public final String name;
    public final String description;
    public final List<Setting<?>> settings = new ArrayList<>();
    public boolean enabled;

    protected Module(String name, String description) {
        this.name = name;
        this.description = description;
    }

    protected Setting.Bool bool(String name, String description, boolean def) {
        Setting.Bool s = new Setting.Bool(name, description, def);
        this.settings.add(s);
        return s;
    }

    protected Setting.Int intSetting(String name, String description, int def, int min, int max) {
        Setting.Int s = new Setting.Int(name, description, def, min, max);
        this.settings.add(s);
        return s;
    }

    protected Setting.Double doubleSetting(String name, String description, double def, double min, double max) {
        Setting.Double s = new Setting.Double(name, description, def, min, max);
        this.settings.add(s);
        return s;
    }

    protected Setting.Str str(String name, String description, String def) {
        Setting.Str s = new Setting.Str(name, description, def);
        this.settings.add(s);
        return s;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void toggle() {
        this.enabled = !this.enabled;
        if (this.enabled) {
            this.onActivate();
        } else {
            this.onDeactivate();
        }
        AutoCraft.get().config.save();
    }

    public void onTick() {
    }

    public void onActivate() {
    }

    public void onDeactivate() {
    }
}
