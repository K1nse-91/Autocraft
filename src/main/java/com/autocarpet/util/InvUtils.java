package com.autocarpet.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

/**
 * 物品栏操作工具 (1.21.11 版): 全部走 interactionManager.clickSlot,
 * 兼容 K1 客户端 (fabric 1.21.11)。
 */
public class InvUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** 主物品栏大小 (不含装备) */
    public static final int MAIN_SIZE = 36;

    // ------------------------------------------------------------------
    // 基础点击
    // ------------------------------------------------------------------

    public static void click(int slot, int button, SlotActionType action) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, button, action, mc.player);
    }

    public static void shiftClick(int windowSlot) {
        if (windowSlot < 0) return;
        click(windowSlot, 0, SlotActionType.QUICK_MOVE);
    }

    /** shift 点击背包索引对应的槽位 */
    public static void shiftClickInv(int invIndex) {
        if (invIndex < 0 || invIndex >= MAIN_SIZE) return;
        shiftClick(indexToId(invIndex));
    }

    public static void pickup(int slot) {
        if (slot < 0) return;
        click(slot, 0, SlotActionType.PICKUP);
    }

    /** 从 from 拿起放到 to (光标可能残留, 慎用) */
    public static void move(int windowFrom, int windowTo) {
        if (windowFrom < 0 || windowTo < 0) return;
        pickup(windowFrom);
        pickup(windowTo);
    }

    /** 三段式交换 (光标不残留) */
    public static void swapSlots(int windowA, int windowB) {
        if (windowA < 0 || windowB < 0) return;
        pickup(windowA);
        pickup(windowB);
        pickup(windowA);
    }

    /** SWAP: 把背包索引槽位的物品与当前手持交换 */
    public static void swapWithSelected(int invIndex) {
        int selected = selectedIndex();
        if (invIndex == selected) return;
        click(indexToId(invIndex), selected, SlotActionType.SWAP);
    }

    // ------------------------------------------------------------------
    // 槽位换算 (背包索引 0-35 -> 当前容器窗口槽位 id)
    // ------------------------------------------------------------------

    public static int indexToId(int index) {
        if (mc.player == null) return Math.max(0, index);
        ScreenHandler menu = mc.player.currentScreenHandler;
        int size = menu.slots.size();
        for (int i = 0; i < size; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == index) return i;
        }
        if (index >= 0) {
            if (menu instanceof PlayerScreenHandler && index < MAIN_SIZE) {
                return Math.min(index >= 9 ? index : index + MAIN_SIZE, size - 1);
            }
            if (size > MAIN_SIZE) {
                return Math.min(size - MAIN_SIZE + (index >= 9 ? index - 9 : 27 + index), size - 1);
            }
            return Math.min(index, size - 1);
        }
        return 0;
    }

    public static int selectedIndex() {
        return mc.player == null ? 0 : mc.player.getInventory().getSelectedSlot();
    }

    // ------------------------------------------------------------------
    // 查找
    // ------------------------------------------------------------------

    /** 在主物品栏 (0-35) 中按条件找物品 */
    public static ItemStack find(Predicate<ItemStack> filter) {
        if (mc.player == null) return ItemStack.EMPTY;
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = inventory.getMainStacks().get(i);
            if (!stack.isEmpty() && filter.test(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack find(Item item) {
        return find(stack -> stack.getItem() == item);
    }

    /** 主手物品 */
    public static ItemStack mainHand() {
        return mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
    }

    public static boolean testInMainHand(Item... items) {
        ItemStack hand = mainHand();
        for (Item item : items) {
            if (hand.getItem() == item) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 容器关闭
    // ------------------------------------------------------------------

    /** 关闭当前打开的容器 (若不是自身背包) */
    public static void closeContainer() {
        if (mc.player == null) return;
        if (!(mc.player.currentScreenHandler instanceof PlayerScreenHandler)) {
            mc.player.closeHandledScreen();
        }
    }

    // ------------------------------------------------------------------
    // 快捷栏
    // ------------------------------------------------------------------

    /** 选中快捷栏槽位 (0-8) */
    public static void selectSlot(int hotbarIndex) {
        if (mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() != hotbarIndex) {
            mc.player.getInventory().setSelectedSlot(hotbarIndex);
        }
    }
}
