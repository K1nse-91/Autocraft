package com.autocarpet.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/** 简单纯文本标签 (1.21.11 无 StringWidget, 自己画) */
public class LabelWidget extends ClickableWidget {
    public LabelWidget(int x, int y, int width, int height, String text) {
        super(x, y, width, height, Text.literal(text));
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(textRenderer, this.getMessage().getString(), this.getX(), this.getY(), 0xFFFFFF);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
    }
}
