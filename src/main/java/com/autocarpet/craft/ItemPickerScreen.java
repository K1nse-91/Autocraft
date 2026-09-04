package com.autocarpet.craft;

import com.autocarpet.AutoCraft;
import com.autocarpet.module.Module;
import com.autocarpet.util.Names;
import com.autocarpet.util.PinyinUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成物品选择器: 列出所有可自动摆料的配方产物, 支持名称/注册 id 过滤, 滚轮翻页。
 */
public class ItemPickerScreen extends Screen {
    private final Screen parent;
    private final AutoCrafterModule module;
    private final List<Item> allItems = new ArrayList<>();
    private final List<ButtonWidget> rowButtons = new ArrayList<>();
    private String filter = "";
    private int scrollOffset;
    private TextFieldWidget searchBox;

    public ItemPickerScreen(Screen parent) {
        super(Text.literal("选择合成物品"));
        this.parent = parent;
        AutoCrafterModule found = null;
        for (Module m : AutoCraft.get().modules) {
            if (m instanceof AutoCrafterModule crafter) {
                found = crafter;
                break;
            }
        }
        this.module = found;
        this.allItems.addAll(AutoCrafterModule.listCraftableItems());
        this.allItems.sort((a, b) -> Names.get(a).compareToIgnoreCase(Names.get(b)));
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        this.searchBox = new TextFieldWidget(this.textRenderer, center - 150, 28, 300, 20, Text.literal("过滤"));
        this.searchBox.setText(this.filter);
        this.searchBox.setChangedListener(text -> {
            this.filter = text == null ? "" : text;
            this.scrollOffset = 0;
            this.rebuildRows();
        });
        this.addDrawableChild(this.searchBox);
        this.rebuildRows();
    }

    private void rebuildRows() {
        for (Element widget : this.rowButtons) {
            this.remove(widget);
        }
        this.rowButtons.clear();
        List<Item> visible = this.visibleItems();
        int top = 58;
        int bottom = this.height - 24;
        int rowHeight = 20;
        int maxRows = Math.max(1, (bottom - top) / rowHeight);
        int maxScroll = Math.max(0, visible.size() - maxRows);
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll);
        for (int i = 0; i < visible.size(); i++) {
            if (i < this.scrollOffset || i >= this.scrollOffset + maxRows) continue;
            Item item = visible.get(i);
            int y = top + (i - this.scrollOffset) * rowHeight;
            String label = truncate(Names.get(item), 36) + "  §7" + Registries.ITEM.getId(item);
            ButtonWidget row = ButtonWidget.builder(Text.literal(label), b -> {
                this.module.targetId.set(Registries.ITEM.getId(item).toString());
                this.module.qty.set(Math.max(1, this.module.qty.get()));
                this.close();
            }).dimensions(10, y, this.width - 20, 18).build();
            this.rowButtons.add(row);
            this.addDrawableChild(row);
        }
    }

    private List<Item> visibleItems() {
        if (this.filter.isEmpty()) return this.allItems;
        String f = this.filter.toLowerCase();
        ArrayList<Item> result = new ArrayList<>();
        for (Item item : this.allItems) {
            String id = Registries.ITEM.getId(item).toString();
            if (id.contains(f)) {
                result.add(item);
                continue;
            }
            String name = Names.get(item);
            if (name.toLowerCase().contains(f)) {
                result.add(item);
                continue;
            }
            // 拼音模糊: 全拼 (baihua) 或首字母缩写 (bh) 都能命中中文名
            String[] py = PinyinUtils.keys(name);
            if (py[0].contains(f) || py[1].contains(f)) result.add(item);
        }
        return result;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= 58 && mouseY <= this.height - 24) {
            int maxScroll = Math.max(0, this.visibleItems().size() - Math.max(1, (this.height - 82) / 20));
            this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + (vertical > 0 ? -1 : 1)));
            this.rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
        String info = "共 " + this.allItems.size() + " 种可合成物品 · 支持拼音搜索 (如 bh = 白桦) · 滚轮翻页 · 点击选择";
        context.drawCenteredTextWithShadow(this.textRenderer, info, this.width / 2, 54, 0x808080);
    }

    @Override
    public void close() {
        // 回到控制面板时重建一份, 保证目标/数量文本是新的
        if (this.parent instanceof CraftControlScreen control) {
            MinecraftClient.getInstance().setScreen(new CraftControlScreen(control.getBack()));
        } else {
            MinecraftClient.getInstance().setScreen(this.parent);
        }
    }
}
