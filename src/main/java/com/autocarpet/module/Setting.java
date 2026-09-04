package com.autocarpet.module;

import com.autocarpet.AutoCraft;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public abstract class Setting<T> {
    public final String name;
    public final String description;
    protected T value;
    public final T defaultValue;
    /** 可选的变更回调 (在 set() 时触发) */
    public Runnable onChanged;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public T get() {
        return this.value;
    }

    public void set(T value) {
        this.value = value;
        AutoCraft.get().config.save();
        if (this.onChanged != null) this.onChanged.run();
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement element);

    public static class Bool extends Setting<Boolean> {
        public Bool(String name, String description, boolean def) {
            super(name, description, def);
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(this.value);
        }

        @Override
        public void fromJson(JsonElement element) {
            if (element != null && element.isJsonPrimitive()) this.value = element.getAsBoolean();
        }
    }

    public static class Int extends Setting<Integer> {
        public final int min;
        public final int max;

        public Int(String name, String description, int def, int min, int max) {
            super(name, description, def);
            this.min = min;
            this.max = max;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(this.value);
        }

        @Override
        public void fromJson(JsonElement element) {
            if (element != null && element.isJsonPrimitive()) {
                this.value = java.lang.Math.clamp(element.getAsInt(), this.min, this.max);
            }
        }
    }

    public static class Double extends Setting<java.lang.Double> {
        public final double min;
        public final double max;

        public Double(String name, String description, double def, double min, double max) {
            super(name, description, def);
            this.min = min;
            this.max = max;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(this.value);
        }

        @Override
        public void fromJson(JsonElement element) {
            if (element != null && element.isJsonPrimitive()) {
                this.value = java.lang.Math.clamp(element.getAsDouble(), this.min, this.max);
            }
        }
    }

    public static class Str extends Setting<String> {
        public Str(String name, String description, String def) {
            super(name, description, def);
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(this.value);
        }

        @Override
        public void fromJson(JsonElement element) {
            if (element != null && element.isJsonPrimitive()) this.value = element.getAsString();
        }
    }
}
