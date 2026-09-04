package com.autocarpet;

import com.autocarpet.craft.AutoCrafterModule;
import com.autocarpet.craft.CraftControlScreen;
import com.autocarpet.module.Module;
import com.autocarpet.module.Setting;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动合成 (AutoCraft) 1.21.11 独立版主入口。
 * 快捷键可在 选项-控制-按键绑定 中修改 (杂项), 改动会同步回模块设置并持久化。
 */
public class AutoCraft implements ClientModInitializer {
    private static AutoCraft instance;
    public final List<Module> modules = new ArrayList<>();
    public ConfigManager config;
    private AutoCrafterModule crafter;
    private KeyBinding toggleKey;
    private KeyBinding panelKey;

    public static AutoCraft get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        this.crafter = new AutoCrafterModule();
        this.modules.add(this.crafter);
        this.config = new ConfigManager();

        this.toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autocraft.toggle", GLFW.GLFW_KEY_K, KeyBinding.Category.MISC));
        this.panelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autocraft.panel", GLFW.GLFW_KEY_J, KeyBinding.Category.MISC));
        this.crafter.toggleKey.onChanged = () -> this.toggleKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(this.crafter.toggleKey.get()));
        this.crafter.panelKey.onChanged = () -> this.panelKey.setBoundKey(keyBinding(this.crafter.panelKey.get()));
        this.config.load(this.modules);
        this.toggleKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(this.crafter.toggleKey.get()));
        this.panelKey.setBoundKey(keyBinding(this.crafter.panelKey.get()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    /** 0 (无) 映射为未绑定, 其余按 KEYSYM 解析 */
    private static InputUtil.Key keyBinding(int code) {
        return code == 0 ? InputUtil.UNKNOWN_KEY : InputUtil.Type.KEYSYM.createFromCode(code);
    }

    /** 每 tick 把「选项-控制-按键绑定」里的改键同步回模块设置 (持久化) */
    private void syncKeysFromControls() {
        if (this.crafter == null) return;
        syncKey(this.crafter.toggleKey, this.toggleKey);
        syncKey(this.crafter.panelKey, this.panelKey);
    }

    private static void syncKey(Setting.Int setting, KeyBinding binding) {
        InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(binding);
        if (bound.getCategory() != InputUtil.Type.KEYSYM) return;   // 只同步键盘按键
        int code = bound.getCode();
        if (bound == InputUtil.UNKNOWN_KEY || code < 0) code = 0;
        if (setting.get() != code) setting.set(code);
    }

    private void onTick(MinecraftClient mc) {
        this.syncKeysFromControls();
        while (this.toggleKey.wasPressed()) {
            this.crafter.toggle();
        }
        while (this.panelKey.wasPressed()) {
            if (mc.currentScreen == null) CraftControlScreen.open();
        }
        if (mc.player == null || mc.world == null) {
            for (Module module : this.modules) {
                if (module.enabled) module.toggle();
            }
            return;
        }
        for (Module module : this.modules) {
            if (module.enabled) module.onTick();
        }
    }
}
