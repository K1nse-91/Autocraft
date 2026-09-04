package com.autocarpet.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 物品显示名 */
public class Names {
    public static String get(Item item) {
        return new ItemStack(item).getName().getString();
    }

    public static String get(ItemStack stack) {
        return stack.getName().getString();
    }
}
