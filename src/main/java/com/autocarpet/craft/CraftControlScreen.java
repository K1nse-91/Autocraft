package com.autocarpet.craft;

import com.autocarpet.AutoCraft;
import com.autocarpet.module.Module;
import com.autocarpet.module.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成控制面板 (1.21.11): 开关 / 选物品 / 数量 ±64 ±1728 快捷按钮 + 精确输入。
 */
public class CraftControlScreen extends Screen {
    private final Screen back;
    private final AutoCrafterModule module;
    private final List<net.minecraft.client.gui.widget.ClickableWidget> created = new ArrayList<>();
    private final int qtyMin;
    private final int qtyMax;

    public CraftControlScreen(Screen back) {
        super(Text.literal("自动合成面板"));
        this.back = back;
        AutoCrafterModule found = null;
        for (Module m : AutoCraft.get().modules) {
            if (m instanceof AutoCrafterModule crafter) {
                found = crafter;
                break;
            }
        }
        this.module = found;
        Setting.Int qtySetting = found == null ? null : found.qty;
        this.qtyMin = qtySetting == null ? 0 : qtySetting.min;
        this.qtyMax = qtySetting == null ? 1000000 : qtySetting.max;
    }

    public static void open() {
        MinecraftClient.getInstance().setScreen(new CraftControlScreen(null));
    }

    public Screen getBack() {
        return this.back;
    }

    @Override
    protected void init() {
        this.rebuild();
    }

    /** 移除旧控件并重建 (按钮回调都走这里, 不会叠加控件) */
    private void rebuild() {
        for (var widget : this.created) {
            this.remove(widget);
        }
        this.created.clear();
        int center = this.width / 2;
        if (this.module == null) {
            this.addCreated(ButtonWidget.builder(Text.literal("模块未加载"), b -> {
            }).dimensions(center - 100, 40, 200, 20).build());
            this.addCreated(ButtonWidget.builder(Text.literal("关闭"), b -> this.close())
                    .dimensions(center - 45, this.height - 27, 90, 20).build());
            return;
        }
        // 开关
        this.addCreated(ButtonWidget.builder(Text.literal("模块: " + (this.module.enabled ? "§a开启" : "§c关闭")), b -> {
            this.module.toggle();
            this.rebuild();
        }).dimensions(center - 150, 24, 300, 20).build());
        // 成品去向模式: 丢出合成 / 存放背包 / 装箱合成 (点击循环切换)
        String[] modeNames = {"丢出合成", "存放背包", "装箱合成"};
        String[] modeColors = {"§c", "§7", "§a"};
        int mode = this.module.craftMode.get();
        String modeDesc = switch (mode) {
            case 0 -> "成品直接丢地上";
            case 1 -> "成品收进背包";
            default -> "成品存入黄玻璃盒";
        };
        this.addCreated(ButtonWidget.builder(Text.literal("模式: " + modeColors[mode] + modeNames[mode] + "§r (" + modeDesc + ")"), b -> {
            this.module.craftMode.set((this.module.craftMode.get() + 1) % 3);
            this.rebuild();
        }).dimensions(center - 150, 50, 300, 20).build());
        // 目标物品
        String targetText = "合成目标: " + this.module.targetLabel();
        this.addCreated(ButtonWidget.builder(Text.literal(targetText), b ->
                        MinecraftClient.getInstance().setScreen(new ItemPickerScreen(this)))
                .dimensions(center - 150, 74, 300, 20).build());
        // 数量标题
        this.addDrawableChild(new com.autocarpet.util.LabelWidget(center - 150, 102, 300, 12,
                "合成数量 (快捷: ±64 一组 / ±1728 一盒)"));
        // 大按钮: -1728 -64 | 数量 | +64 +1728
        int[][] big = {{-1728, center - 150}, {-64, center - 80}, {64, center + 50}, {1728, center + 120}};
        for (int[] entry : big) {
            int delta = entry[0];
            int x = entry[1];
            this.addCreated(ButtonWidget.builder(Text.literal(delta > 0 ? "+" + delta : String.valueOf(delta)), b -> {
                this.module.qty.set(Math.max(this.qtyMin, Math.min(this.qtyMax, this.module.qty.get() + delta)));
                this.rebuild();
            }).dimensions(x, 120, 30, 20).build());
        }
        this.addCreated(new com.autocarpet.util.LabelWidget(center - 40, 123, 80, 14,
                "§e" + this.module.qty.get()));
        // 微调 + 输入
        this.addCreated(ButtonWidget.builder(Text.literal("-1"), b -> {
            this.module.qty.set(Math.max(this.qtyMin, Math.min(this.qtyMax, this.module.qty.get() - 1)));
            this.rebuild();
        }).dimensions(center - 150, 144, 30, 20).build());
        this.addCreated(ButtonWidget.builder(Text.literal("+1"), b -> {
            this.module.qty.set(Math.max(this.qtyMin, Math.min(this.qtyMax, this.module.qty.get() + 1)));
            this.rebuild();
        }).dimensions(center + 120, 144, 30, 20).build());
        TextFieldWidget box = new TextFieldWidget(this.textRenderer, center - 110, 144, 220, 20, Text.literal("数量"));
        box.setText(String.valueOf(this.module.qty.get()));
        box.setChangedListener(text -> {
            try {
                int value = Integer.parseInt(text.trim());
                this.module.qty.set(Math.max(this.qtyMin, Math.min(this.qtyMax, value)));
            } catch (NumberFormatException ignored) {
            }
        });
        this.addCreated(box);
        // 状态提示
        String stateText = switch (this.module.getStateName()) {
            case "CRAFT" -> "合成中...";
            case "FETCH" -> "前往补给箱拿材料...";
            case "STORE" -> "前往存放盒存成品...";
            case "WALK" -> "寻路中...";
            case "BACK" -> "回工作台...";
            default -> "空闲: 打开工作台开始";
        };
        this.addCreated(new com.autocarpet.util.LabelWidget(center - 150, 176, 300, 12, stateText));
        String remain = this.module.getRemaining() > 0 ? String.valueOf(this.module.getRemaining()) : "-";
        this.addCreated(new com.autocarpet.util.LabelWidget(center - 150, 194, 300, 12, "还需合成: " + remain));
        this.addCreated(new com.autocarpet.util.LabelWidget(center - 150, 216, 300, 24,
                "缺料时自动去「展示框标记的补给箱」拿: 在箱子旁的展示框里放入对应材料即表示该箱专供此材料。"));
        this.addCreated(ButtonWidget.builder(Text.literal("关闭"), b -> this.close())
                .dimensions(center - 45, this.height - 27, 90, 20).build());
    }

    private void addCreated(net.minecraft.client.gui.widget.ClickableWidget widget) {
        this.created.add(widget);
        this.addDrawableChild(widget);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.back);
    }
}
