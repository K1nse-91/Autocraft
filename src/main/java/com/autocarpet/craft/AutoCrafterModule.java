package com.autocarpet.craft;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import com.autocarpet.module.Module;
import com.autocarpet.module.Setting;
import com.autocarpet.util.InvUtils;
import com.autocarpet.util.Names;
import com.autocarpet.util.PlaceUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动合成 (1.21.11 独立版): 选取目标物品与数量 -> 打开工作台后自动循环合成。
 * 缺材料自动前往「展示框标记的补给箱」(箱子旁展示框里放什么, 箱子就专供什么) 就近拿取,
 * 拿完回工作台继续, 直到数量达成。面板提供 ±64 / ±1728 数量快捷按钮。
 */
public class AutoCrafterModule extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MAX_PATH_DISTANCE = 1000;
    private static final int MAIN_SIZE = 36;
    private static final int RESULT_WAIT_TICKS = 20;
    /** 装箱模式: 累计产出每满这么多就装盒一次, 防止成品占满背包导致无法取材料 */
    private static final int BOX_BATCH = 320;

    private enum State {
        IDLE,      // 等待玩家打开工作台
        CRAFT,     // 工作台合成中
        FETCH,     // 去补给箱补料 / 开箱取物
        STORE,     // 去存放盒存成品 (黄玻璃展示框标记)
        WALK,      // baritone 寻路中
        BACK       // 材料齐了, 回工作台并重新打开
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------
    public final Setting.Str targetId;        // 目标物品 registry id (minecraft:xxx)
    public final Setting.Int qty;             // 合成数量 (成品个数)
    public final Setting.Int supplyRange;     // 补给箱扫描半径
    public final Setting.Int actionDelay;     // 操作间隔 tick
    public final Setting.Bool closeWhenDone;  // 完成后关闭界面
    public final Setting.Int craftMode;      // 0=丢出合成 1=存放背包 2=装箱合成
    public static final int MODE_DROP = 0;
    public static final int MODE_PACK = 1;
    public static final int MODE_BOX = 2;
    public final Setting.Int toggleKey;       // 开关快捷键 (GLFW)
    public final Setting.Int panelKey;        // 面板快捷键 (GLFW)

    // ------------------------------------------------------------------
    // 运行快照
    // ------------------------------------------------------------------
    private Item target;
    private RecipeDisplayEntry recipeEntry;
    private int resultPerCraft = 1;
    private final Map<Item, Integer> perCraft = new HashMap<>();

    private int remaining;
    private int craftedTotal;
    private int nextStoreAt = BOX_BATCH;   // 装箱模式: 下一次装盒的累计产出阈值
    private int lastProgressLog;   // 进度日志节流 (每 +16 报一次)
    private boolean takePending;   // shift 取成品后等待背包实物结算
    private int takeWaitTicks;
    private int takeBaseCount;     // shift 前背包内成品数 (结算基准)
    private int gridWaitTicks;     // 摆料连续失败计数 (材料不足快速检测)
    private boolean batchDone;
    private int doneQty;

    private BlockPos tablePos;
    private BlockPos anchor;

    // ------------------------------------------------------------------
    // 状态机运行字段
    // ------------------------------------------------------------------
    private State state = State.IDLE;
    private int delayTicks;
    private int resultEmptyTicks;
    private int openTries;
    private long lastOpenTime;
    private boolean openSent;
    private BlockPos lastOpenPos;

    // 补货进度
    private Item fetchItem;
    private int fetchNeed;
    private List<BlockPos> fetchChests;
    private int fetchChestIndex;
    private int containerCursor;
    private boolean fetchScanned;
    private boolean fullWarned;
    private int fetchNoProgress;   // 连续空转趟数(拿不到东西), 防 fetch<->BACK 死循环
    private int refillWaitTicks;   // 补给箱拿空后的补货等待冷却 (0=不在等待, 每 5 秒自动重试)
    private boolean selfClosed;    // 界面是否由模块主动关闭(区别于用户手动关)
    private Map<Item, Integer> fetchPlan;   // (已移到上方注释处)
    private boolean batchDoneByFinish;  // 锁由正常完成设置(true): 界面关闭后自动解除; 失败锁(false)需手动解除   // 本趟补货计划: 材料 -> 组数(每槽一组), 多材料按容量统一分配

    // 寻路进度
    private BlockPos walkGoal;
    private int walkRange;
    private State walkReturn;
    private int walkTicks;
    private int walkRepaths;

    // 展示框补给箱: 物品 -> 箱子列表
    private final Map<Item, List<BlockPos>> supplyChests = new HashMap<>();

    // 存放盒: 黄玻璃展示框标记的容器 (合成成品的默认存放位置)
    private final List<BlockPos> depositChests = new ArrayList<>();
    private int depositIndex = -1;
    private boolean storeContinue;   // true: 存完回工作台继续合成; false: 存完直接收尾

    public AutoCrafterModule() {
        super("自动合成", "选择目标物品与数量后, 打开工作台自动合成。缺材料时自动前往展示框标记的补给箱拿取 (框里放什么, 箱子专供什么)。");
        this.targetId = this.str("合成目标", "要合成的物品 registry id (面板中点击\"选择物品\"设置)", "");
        this.qty = this.intSetting("合成数量", "要合成的成品个数 (面板中可用 ±64/±1728 快速调整)", 64, 0, 1000000);
        this.supplyRange = this.intSetting("补给扫描半径", "扫描展示框补给箱的半径", 96, 16, 512);
        this.actionDelay = this.intSetting("动作延迟", "两次操作之间的 tick 延迟", 3, 0, 20);
        this.closeWhenDone = this.bool("完成后关界面", "合成完毕后自动关闭工作台界面", true);
        this.craftMode = this.intSetting("合成模式", "成品去向: 0=丢出合成(丢地上) 1=存放背包 2=装箱合成(存入黄玻璃标记盒)", MODE_PACK, 0, 2);
        this.toggleKey = this.intSetting("开关快捷键", "切换本模块的 GLFW 按键代码", GLFW.GLFW_KEY_K, 0, 512);
        this.panelKey = this.intSetting("面板快捷键", "打开合成控制面板的按键, 无表示未绑定", GLFW.GLFW_KEY_J, 0, 512);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void onActivate() {
        this.batchDone = false;
        this.resetRun();
        this.setBaritoneBreak(false);   // 模块运行期间禁止 baritone 挖方块 (含头顶), 避免破坏建筑
        info("已开启: 打开工作台开始 (目标: %s, 数量: %d)", this.targetLabel(), this.qty.get());
    }

    @Override
    public void onDeactivate() {
        this.setBaritoneBreak(true);    // 恢复 baritone 默认挖掘行为
        this.resetRun();
        info("已关闭");
    }

    /** 切换 baritone 的方块破坏许可 (false=寻路不挖任何方块) */
    private void setBaritoneBreak(boolean allow) {
        try {
            baritone.api.Settings settings = BaritoneAPI.getSettings();
            if (settings.allowBreak.value != allow) {
                settings.allowBreak.value = allow;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            this.state = State.IDLE;
            return;
        }
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }
        try {
            switch (this.state) {
                case IDLE -> this.tickIdle();
                case CRAFT -> this.tickCraft();
                case FETCH -> this.tickFetch();
                case STORE -> this.tickStore();
                case WALK -> this.tickWalk();
                case BACK -> this.tickBack();
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            error("发生异常: %s", throwable.getMessage());
            this.lockBatch();
            this.resetRun();
        }
    }

    // ==================================================================
    // IDLE
    // ==================================================================

    private void tickIdle() {
        if (this.qty.get() <= 0) return;
        if (!(mc.player.currentScreenHandler instanceof CraftingScreenHandler)) {
            // 正常完成的锁: 界面已关闭即解除, 下次打开工作台直接开始新批次 (无需开关模块)
            // 装箱模式例外: 任务终点是成品入库, 同数量不得自动重跑 (改数量或重开模块后才再跑)
            if (this.batchDone && this.batchDoneByFinish && this.mode() != MODE_BOX) {
                this.batchDone = false;
                info("批次完成锁已解除, 打开工作台将开始新批次");
            }
            return;
        }
        // 上一批已终结(完成/失败/中断): 同数量下不自动重开, 改数量或重开模块后才解除
        if (this.batchDone && this.qty.get() == this.doneQty) return;
        this.target = this.resolveTarget();
        if (this.target == null) {
            this.pauseChat();
            warning("未选择合成目标, 请按面板键 (%s) 打开控制面板选择", keyName(this.panelKey.get()));
            return;
        }
        if (!this.captureTable()) {
            this.pauseChat();
            warning("无法定位工作台, 请正对工作台打开后再试");
            return;
        }
        if (!this.prepareRecipe()) {
            this.pauseChat();
            return;
        }
        this.remaining = this.qty.get();
        this.craftedTotal = 0;
        this.nextStoreAt = BOX_BATCH;
        this.scanSupplyChests();   // 刷新补给箱与黄玻璃存放盒标记
        if (this.mode() == MODE_BOX && this.depositChests.isEmpty()) {
            this.pauseChat();
            warning("装箱合成: 未找到黄玻璃标记的存放盒, 摆好后自动开始 (或改回存放背包/丢出模式)");
            return;
        }
        info("开始合成 %s x%d", Names.get(this.target), this.remaining);
        if (this.hasMissingMaterial()) {
            this.startFetch("开始前检查到缺少材料");
        } else {
            this.state = State.CRAFT;
        }
        this.throttle();
    }

    private void pauseChat() {
        this.delayTicks = Math.max(this.delayTicks, 20);
    }

    /** 记录工作台与站位 (先看准星, 再搜周围 3 格) */
    private boolean captureTable() {
        BlockPos pos = null;
        if (mc.crosshairTarget instanceof BlockHitResult hit) {
            BlockPos hitPos = hit.getBlockPos();
            if (mc.world.getBlockState(hitPos).getBlock() == Blocks.CRAFTING_TABLE
                    && this.distanceSq(hitPos) <= 36.0) {
                pos = hitPos;
            }
        }
        if (pos == null) {
            BlockPos base = mc.player.getBlockPos();
            outer:
            for (int dy = -1; dy <= 2; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockPos candidate = base.add(dx, dy, dz);
                        if (mc.world.getBlockState(candidate).getBlock() == Blocks.CRAFTING_TABLE) {
                            pos = candidate;
                            break outer;
                        }
                    }
                }
            }
        }
        if (pos == null) return false;
        this.tablePos = pos;
        this.anchor = mc.player.getBlockPos();
        return true;
    }

    // ==================================================================
    // 配方解析
    // ==================================================================

    private Item resolveTarget() {
        String id = this.targetId.get();
        if (id == null || id.isEmpty()) return null;
        Identifier identifier = Identifier.of(id);
        Item item = Registries.ITEM.get(identifier);
        return item == null || item == Items.AIR ? null : item;
    }

    /** 当前成品去向模式: MODE_DROP / MODE_PACK / MODE_BOX */
    public int mode() {
        return this.craftMode.get();
    }

    public String targetLabel() {
        Item item = this.resolveTarget();
        return item == null ? "(未选择)" : Names.get(item);
    }

    /** 找到目标合成配方并统计单次材料 */
    private boolean prepareRecipe() {
        this.recipeEntry = null;
        this.perCraft.clear();
        this.resultPerCraft = 1;
        if (!(mc.player.getRecipeBook() instanceof ClientRecipeBook book)) return false;
        for (RecipeResultCollection collection : book.getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (!(entry.display() instanceof ShapedCraftingRecipeDisplay)
                        && !(entry.display() instanceof ShapelessCraftingRecipeDisplay)) {
                    continue;
                }
                if (entry.craftingRequirements().isEmpty()) continue;   // 特殊配方无法自动摆料
                List<ItemStack> results = entry.getStacks(SlotDisplayContexts.createParameters(mc.world));
                ItemStack resultStack = null;
                for (ItemStack stack : results) {
                    if (stack.getItem() == this.target) {
                        resultStack = stack;
                        break;
                    }
                }
                if (resultStack == null) continue;
                this.recipeEntry = entry;
                this.resultPerCraft = Math.max(1, resultStack.getCount());
                for (Ingredient ingredient : entry.craftingRequirements().get()) {
                    List<Item> options = ingredient.getMatchingItems().map(RegistryEntry::value).toList();
                    if (options.isEmpty()) continue;
                    this.perCraft.merge(this.pickOption(options), 1, Integer::sum);
                }
                return true;
            }
        }
        warning("找不到 <%s> 的可自动合成配方", this.targetLabel());
        return false;
    }

    private Item pickOption(List<Item> options) {
        for (Item option : options) {
            if (this.countInInventory(option) > 0) return option;
        }
        for (Item option : options) {
            if (this.supplyChests.containsKey(option)) return option;
        }
        return options.getFirst();
    }

    private boolean hasMissingMaterial() {
        return !this.computeMissing().isEmpty();
    }

    private Map<Item, Integer> computeMissing() {
        Map<Item, Integer> missing = new HashMap<>();
        if (this.remaining <= 0 || this.perCraft.isEmpty()) return missing;
        int crafts = (int) Math.ceil(this.remaining / (double) this.resultPerCraft);
        for (Map.Entry<Item, Integer> entry : this.perCraft.entrySet()) {
            int need = entry.getValue() * crafts;
            int have = this.countInInventory(entry.getKey());
            if (need > have) missing.put(entry.getKey(), need - have);
        }
        return missing;
    }

    /**
     * 单趟取料计划: 把 freeStacks 个空位按"各材料还需组数"的比例分配,
     * 每种缺失材料至少保底 1 组 (空位实在不够时给需求最大的几种)。
     * 避免只拿需求最大的材料导致别的材料拿不到。
     */
    private Map<Item, Integer> buildFetchPlan(Map<Item, Integer> missing, int freeStacks) {
        java.util.LinkedHashMap<Item, Integer> need = new java.util.LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            int stacks = (int) Math.ceil(entry.getValue() / 64.0);
            if (stacks > 0) need.put(entry.getKey(), stacks);
        }
        Map<Item, Integer> plan = new java.util.LinkedHashMap<>();
        if (need.isEmpty() || freeStacks <= 0) return plan;
        java.util.ArrayList<Item> keys = new java.util.ArrayList<>(need.keySet());
        keys.sort((a, b) -> Integer.compare(need.get(b), need.get(a)));
        int n = keys.size();
        if (freeStacks < n) {
            // 空位还不够每种 1 组: 只给需求最大的 freeStacks 种材料各 1 组
            for (int i = 0; i < freeStacks; i++) plan.put(keys.get(i), 1);
            return plan;
        }
        for (Item key : keys) plan.put(key, 1);   // 每种缺失材料保底 1 组
        int extra = freeStacks - n;
        if (extra <= 0) return plan;
        long totalRemain = 0;
        for (int i = 0; i < n; i++) totalRemain += need.get(keys.get(i)) - 1;
        if (totalRemain <= 0) return plan;
        // 剩余空位按还需组数比例分配 (最大余数法)
        double[] frac = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            int remain = need.get(keys.get(i)) - 1;
            double exact = remain * (double) extra / totalRemain;
            int base = Math.min((int) exact, remain);
            plan.put(keys.get(i), plan.get(keys.get(i)) + base);
            assigned += base;
            frac[i] = exact - base;
        }
        java.util.ArrayList<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> Double.compare(frac[b], frac[a]));
        for (int idx : order) {
            if (assigned >= extra) break;
            int cap = need.get(keys.get(idx)) - plan.get(keys.get(idx));
            if (cap <= 0) continue;
            plan.put(keys.get(idx), plan.get(keys.get(idx)) + 1);
            assigned++;
        }
        return plan;
    }

    // ==================================================================
    // CRAFT
    // ==================================================================

    private void tickCraft() {
        if (!(mc.player.currentScreenHandler instanceof CraftingScreenHandler crafting)) {
            if (this.remaining > 0) {
                if (this.selfClosed) {
                    // 模块自己关的界面 (fetch 补货流程): 自动回工作台重开
                    if (this.goBackToTable()) return;
                    warning("自动回工作台失败, 批次中止 (改数量或重开模块可继续)");
                    this.lockBatch();
                    this.state = State.IDLE;
                    return;
                }
                // 用户手动关闭: 尊重操作, 中止批次并关闭模块开关, 需要时重新打开即可
                warning("已手动关闭工作台, 批次中止 (已合成 %d 个, 背包中成品 %d 个)",
                        this.craftedTotal, this.target == null ? 0 : this.countInInventory(this.target));
                this.lockBatch();
                this.state = State.IDLE;
                this.shutdownModule();
                return;
            }
            // 数量已达标但界面不在工作台 (最后一批刚收走/玩家恰好关了台):
            // 装箱模式先入盒, 其余直接正常收尾并上锁, 防止"再开台自动清零重跑"造成重复合成
            if (this.mode() == MODE_BOX && this.target != null && this.hasAnyTargetInInventory()) {
                info("合成达标 %d 个, 存入存放盒", this.craftedTotal);
                this.startStore(false);
                return;
            }
            this.finishRun();
            return;
        }
        this.selfClosed = false;   // 工作台已成功打开
        if (this.remaining <= 0) {
            // 收尾: 装箱模式 -> 成品全部存入黄玻璃盒; 丢出模式 -> 背包成品全部丢光(含散尾);
            // 存放背包 -> 丢"配方一次产出多个"的溢出, 保证实际数量=设定数量
            if (this.target != null && this.hasAnyTargetInInventory()) {
                if (this.mode() == MODE_BOX) {
                    this.startStore(false);
                    return;
                }
                if (this.mode() == MODE_DROP) {
                    if (this.target.getDefaultStack().getMaxCount() <= 1) {
                        this.dropOneTargetSlot();
                    } else {
                        this.dropLargestTargetStack();
                    }
                    this.throttle();
                    return;
                }
                int extra = this.craftedTotal - this.qty.get();
                if (extra > 0) {
                    // THROW button=0 = 丢 1 个, 逐个丢掉溢出部分
                    InvUtils.click(InvUtils.indexToId(this.firstTargetSlot()), 0, SlotActionType.THROW);
                    this.throttle();
                    return;
                }
            }
            this.finishRun();
            return;
        }
        // 1.21.11 一次合成请求/一次 shift 会连续产出多个成品并直接塞进背包,
        // 结果槽显示数量不可信 -> shift 后按"背包内成品实物增量"结算计数
        Slot result = crafting.getOutputSlot();
        if (this.takePending) {
            int delta = this.countInInventory(this.target) - this.takeBaseCount;
            if (delta > 0) {
                this.takePending = false;
                this.craftedTotal += delta;
                this.remaining -= delta;
                this.fullWarned = false;
                if (this.craftedTotal - this.lastProgressLog >= 16) {
                    this.lastProgressLog = this.craftedTotal;
                    info("合成进度: %d/%d", this.craftedTotal, this.qty.get());
                }
                // 装箱模式: 累计每满 320 个装盒一次 (或背包快满时提前装), 防成品占满背包拿不了材料
                if (this.mode() == MODE_BOX && this.remaining > 0 && this.hasAnyTargetInInventory()
                        && (this.craftedTotal >= this.nextStoreAt || this.emptyInvSlots() <= 4)) {
                    info("已合成 %d 个, 先装入存放盒再继续 (每 %d 个装盒一次)", this.craftedTotal, BOX_BATCH);
                    this.nextStoreAt = this.craftedTotal + BOX_BATCH;
                    this.startStore(true);
                    return;
                }
            } else if (++this.takeWaitTicks >= 5) {
                // 连续多 tick 无增量: shift 未生效 (背包满/同步异常), 放弃本次, 回正常流程重试
                this.takePending = false;
            }
            this.throttle();
            return;
        }
        // 丢出模式: 清背包成品 (不可堆叠逐个丢 / 可堆叠满 64 整组丢), 丢完再继续合成
        if (this.mode() == MODE_DROP && this.remaining > 0 && this.target != null
                && this.hasAnyTargetInInventory()) {
            if (this.target.getDefaultStack().getMaxCount() <= 1 || this.hasFullStackInInventory(this.target)) {
                this.dropOneTargetSlot();
                this.throttle();
                return;
            }
        }
        if (result.hasStack()) {
            this.resultEmptyTicks = 0;
            if (this.emptyInvSlots() == 0 && this.mergeSpaceInInventory(this.target) == 0) {
                if (!this.fullWarned) {
                    this.fullWarned = true;
                    warning("背包没有空位, 合成暂停 (清出格子后自动继续)");
                }
                this.delayTicks = 40;
                return;
            }
            this.fullWarned = false;
            this.takeBaseCount = this.countInInventory(this.target);
            InvUtils.shiftClick(result.id);
            this.takePending = true;
            this.takeWaitTicks = 0;
            this.throttle();
            return;
        }
        if (this.gridEmpty(crafting)) {
            // 摆料: 材料足够时一两个往返内网格就会上料;
            // 连续多次摆不上 = 材料不足, 立即补货 (不再等固定超时)
            this.placeRecipe();
            if (++this.gridWaitTicks > 3) {
                this.gridWaitTicks = 0;
                this.resultEmptyTicks = 0;
                this.startFetch("材料不足, 自动补货");
                return;
            }
        } else {
            this.gridWaitTicks = 0;
            // 网格有料但结果槽迟迟不出现 (配方不匹配/合成停滞), 超时补货;
            // startFetch 关界面时网格残留材料会自动退回背包, 不会卡死
            if (++this.resultEmptyTicks > RESULT_WAIT_TICKS) {
                this.resultEmptyTicks = 0;
                this.startFetch("合成停滞, 自动补货");
            }
        }
        this.throttle();
    }

    private void placeRecipe() {
        if (this.recipeEntry == null) return;
        NetworkRecipeId id = this.recipeEntry.id();
        // craftAll=true: 一次把背包里该配方的材料尽量合成 (一批最多堆满结果槽 64),
        // 配合"背包实物增量结算"计数, 快且数量精确; 材料用尽后自动补货
        mc.interactionManager.clickRecipe(mc.player.currentScreenHandler.syncId, id, true);
    }

    /** 背包中是否存在堆叠数 >= 64 的目标成品堆 */
    private boolean hasFullStackInInventory(Item item) {
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == item && stack.getCount() >= 64) return true;
        }
        return false;
    }

    /** 从背包丢出数量最多的目标成品整组 (拿起 -> 丢到窗口外) */
    private void dropLargestTargetStack() {
        int bestSlot = -1;
        int bestCount = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == this.target && stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return;
        // THROW: 服务端直接从槽位丢出; button=1 才是丢整组 (0=丢单个, 见 itemscroller InventoryUtils DROP_STACKS)
        InvUtils.click(InvUtils.indexToId(bestSlot), 1, SlotActionType.THROW);
    }

    /** 第一个装有目标成品的背包槽位, 没有返回 -1 */
    private int firstTargetSlot() {
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == this.target && !stack.isEmpty()) return i;
        }
        return -1;
    }

    /** 背包里是否还有目标成品 (任意数量) */
    private boolean hasAnyTargetInInventory() {
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == this.target && !stack.isEmpty()) return true;
        }
        return false;
    }

    /** 丢出背包里的一格目标成品 (不可堆叠: 一格一个) */
    private void dropOneTargetSlot() {
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == this.target && !stack.isEmpty()) {
                InvUtils.click(InvUtils.indexToId(i), 1, SlotActionType.THROW);
                return;
            }
        }
    }

    private boolean gridEmpty(CraftingScreenHandler menu) {
        for (Slot slot : menu.getInputSlots()) {
            if (slot.hasStack()) return false;
        }
        return true;
    }

    private void finishRun() {
        int targetQty = this.qty.get();
        if (this.craftedTotal > targetQty) {
            info("合成完毕: %s x%d (目标 %d, 配方一次产 %d 个, 溢出已丢弃)",
                    Names.get(this.target), this.craftedTotal, targetQty, this.resultPerCraft);
        } else if (this.mode() == MODE_BOX) {
            info("合成完毕: %s x%d 已全部存入存放盒 (改数量或重开模块可再合成同数量)", Names.get(this.target), this.craftedTotal);
        } else {
            info("合成完毕: %s x%d", Names.get(this.target), this.craftedTotal);
        }
        this.selfClosed = true;   // 模块主动关闭界面, 不算用户手动关
        if (this.closeWhenDone.get()) {
            InvUtils.closeContainer();
        }
        this.batchDone = true;
        this.doneQty = this.qty.get();
        this.batchDoneByFinish = true;
        this.resetRun();
        this.shutdownModule();   // 合成完毕: 自动关闭模块开关, 下次要合成重新打开即可
    }

    /** 自动关闭模块开关 (完成/手动中止后 UI 开关显示"关") */
    private void shutdownModule() {
        if (this.enabled) {
            this.toggle();
        }
    }

    // ==================================================================
    // FETCH
    // ==================================================================

    private void startFetch(String reason) {
        if (reason != null) info(reason);
        this.selfClosed = true;   // 模块主动关界面去补货, 回来要自动重开
        InvUtils.closeContainer();
        this.fetchItem = null;
        this.fetchNeed = 0;
        this.fetchChests = null;
        this.fetchChestIndex = -1;
        this.fetchScanned = false;
        this.state = State.FETCH;
        this.throttle();
    }

    private void tickFetch() {
        // 补货等待: 补给箱被拿空后每 8 tick 自动重试, 自动补货机补上货后自动继续
        if (this.refillWaitTicks > 0) {
            this.refillWaitTicks++;
            if (this.refillWaitTicks >= 12000) {
                this.refillWaitTicks = 0;
                this.failRun("等待自动补货超时 (10 分钟), 请检查补货机后重开模块");
                return;
            }
            if (this.refillWaitTicks % 600 == 1) {
                info("补给箱等待补货中 (自动重试中, 已等 %d 秒)", (this.refillWaitTicks - 1) / 20);
            }
            if (this.refillWaitTicks % 8 != 0) {
                this.throttle();
                return;
            }
            this.refillWaitTicks = 0;   // 冷却结束: 落到下方正常补货流程重试
            this.fetchItem = null;
            this.fetchNeed = 0;
            this.fetchPlan = null;
            this.fetchChests = null;
            this.fetchChestIndex = -1;
            this.fetchScanned = false;  // 重扫展示框, 识别新补的补给点
        }
        if (this.fetchItem == null) {
            if (!this.fetchScanned) {
                this.scanSupplyChests();
                this.fetchScanned = true;
            }
            if (this.fetchPlan == null) {
                Map<Item, Integer> missing = this.computeMissing();
                StringBuilder unresolved = new StringBuilder();
                missing.keySet().removeIf(item -> {
                    if (this.supplyChests.containsKey(item)) return false;
                    if (!unresolved.isEmpty()) unresolved.append(", ");
                    unresolved.append(Names.get(item));
                    return true;
                });
                if (missing.isEmpty()) {
                    if (!unresolved.isEmpty()) {
                        this.failRun("无法补货: <%s> 没有展示框标记的补给箱 (在箱子旁的展示框里放上该物品)", unresolved);
                        return;
                    }
                    this.state = State.BACK;
                    this.throttle();
                    return;
                }
                // 容量: 至少留 2 格给成品周转; 多材料按各自需求组数统一分配空格, 避免只拿一种
                int freeStacks = this.emptyInvSlots() - 2;
                if (freeStacks <= 0) {
                    if (++this.fetchNoProgress > 2) {
                        this.failRun("背包空间不足且无法继续补货 (已合成 %d 个), 请清理背包或补足补给箱后重开", this.craftedTotal);
                        return;
                    }
                    this.state = State.BACK;
                    info("背包空间不足, 先回去合成消耗材料");
                    this.throttle();
                    return;
                }
                this.fetchPlan = this.buildFetchPlan(missing, freeStacks);
            }
            // 取计划中下一个还有配额的材料
            Item nextItem = null;
            for (Map.Entry<Item, Integer> entry : this.fetchPlan.entrySet()) {
                if (entry.getValue() > 0) {
                    nextItem = entry.getKey();
                    break;
                }
            }
            if (nextItem == null) {
                // 本趟计划全部拿完: 回工作台合成消耗
                this.fetchPlan = null;
                this.state = State.BACK;
                this.throttle();
                return;
            }
            this.fetchItem = nextItem;
            this.fetchNeed = this.fetchPlan.get(nextItem);   // 单位: 组
            this.fetchChests = this.supplyChests.get(this.fetchItem);
            this.fetchChestIndex = this.nearestChestIndex(this.fetchChests);
            info("缺少材料: %s 还需 %d 组, 前往补给箱 (已合成 %d 个)", Names.get(this.fetchItem), this.fetchNeed, this.craftedTotal);
        }
        if (this.fetchChests == null || this.fetchChests.isEmpty() || this.fetchChestIndex < 0) {
            this.failRun("无法补货: 没有展示框标记的 <%s> 补给点 (潜影盒放展示框所贴方块正下方, 或箱子挨着展示框)", Names.get(this.fetchItem));
            return;
        }
        BlockPos chest = this.fetchChests.get(this.fetchChestIndex);
        // 交互范围 4.5 格: 不必贴近盒子, 4 格内即可远程右键打开, 减少寻路钻低矮处挖方块
        if (this.fartherThan(chest, 4.0)) {
            this.walkTo(chest, 3);
            return;
        }
        ScreenHandler menu = mc.player.currentScreenHandler;
        if (!(menu instanceof GenericContainerScreenHandler) && !(menu instanceof ShulkerBoxScreenHandler)) {
            if (!this.tryOpenContainer(chest)) {
                if (this.openTries > 6) {
                    this.openTries = 0;
                    warning("打开补给箱失败, 走近一点重试");
                    this.walkTo(chest, 2);
                } else {
                    this.throttle();
                }
            }
            return;
        }
        this.openTries = 0;
        if (this.fetchNeed <= 0) {
            InvUtils.closeContainer();
            if (this.fetchPlan != null) this.fetchPlan.put(this.fetchItem, 0);   // 该材料本趟配额用尽
            this.fetchItem = null;
            this.throttle();
            return;
        }
        int containerSize = this.containerSlotCount();
        for (int i = 0; i < containerSize; i++) {
            this.containerCursor = this.containerCursor >= containerSize - 1 ? 0 : this.containerCursor + 1;
            Slot slot = menu.slots.get(this.containerCursor);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || stack.getItem() != this.fetchItem) continue;
            // 背包只剩 2 格时提前收尾: 先回去合成, 材料耗尽后再来补 (防止塞满导致成品收不进)
            if (this.emptyInvSlots() <= 2) {
                InvUtils.closeContainer();
                this.fetchItem = null;
                this.fetchPlan = null;   // 本趟计划未完成即收尾, 下趟重新分配
                if (++this.fetchNoProgress > 2) {
                    this.failRun("背包空间不足且补给停滞 (已合成 %d 个), 请清理背包后重开", this.craftedTotal);
                    return;
                }
                info("背包快满, 先回去合成");
                this.state = State.BACK;
                this.throttle();
                return;
            }
            InvUtils.shiftClick(this.containerCursor);
            this.fetchNoProgress = 0;   // 本趟有收获
            this.fetchNeed--;           // 按组计数: 每拿一槽(整组或散组)消耗一组配额
            if (this.fetchNeed <= 0 && this.fetchPlan != null) {
                this.fetchPlan.put(this.fetchItem, 0);
            }
            this.throttle();
            return;
        }
        this.fetchChestIndex = this.nextChestIndex(this.fetchChests, this.fetchChestIndex);
        if (this.fetchChestIndex < 0) {
            // 候选补给箱全部拿空: 不中止, 进入补货等待 (自动补货机补上后会自动继续)
            String emptyName = Names.get(this.fetchItem);
            this.fetchItem = null;
            this.fetchNeed = 0;
            this.fetchPlan = null;
            this.fetchChests = null;
            this.fetchChestIndex = -1;
            this.refillWaitTicks = 1;
            InvUtils.closeContainer();
            this.openSent = false;      // 解除开箱节流, 让重试立即重新开箱检测
            this.lastOpenTime = 0;
            warning("补给箱 <%s> 已拿空, 等待自动补货机补料 (自动重试中)...", emptyName);
            this.throttle();
            return;
        }
        InvUtils.closeContainer();
        this.throttle();
    }

    // ==================================================================
    // STORE / 存放盒
    // ==================================================================

    /** 进入存盒流程: 关闭当前界面, 把背包成品存入黄玻璃标记的存放盒 */
    private void startStore(boolean continueRun) {
        this.selfClosed = true;   // 模块主动关界面去存盒, 回来要自动重开工作台
        InvUtils.closeContainer();
        this.storeContinue = continueRun;
        this.depositIndex = -1;
        this.state = State.STORE;
        this.throttle();
    }

    private void tickStore() {
        // 存放盒丢失/未识别
        if (this.depositChests.isEmpty()) {
            if (this.storeContinue) {
                this.failRun("存放盒丢失/未识别, 成品无法存入 (请放置黄玻璃标记的潜影盒后重开)");
            } else {
                warning("存放盒丢失, 成品留在背包");
                this.finishRun();
            }
            return;
        }
        // 背包成品已全部存入
        if (!this.hasAnyTargetInInventory()) {
            if (this.storeContinue) {
                this.selfClosed = true;   // 主动关盒界面, 回工作台继续
                InvUtils.closeContainer();
                info("成品已存入, 回工作台继续 (已合成 %d/%d)", this.craftedTotal, this.qty.get());
                this.state = State.BACK;
            } else {
                this.finishRun();
            }
            this.throttle();
            return;
        }
        if (this.depositIndex < 0 || this.depositIndex >= this.depositChests.size()) {
            this.depositIndex = this.nearestChestIndex(this.depositChests);
            if (this.depositIndex < 0) {
                this.failRun("无法定位存放盒");
                return;
            }
        }
        BlockPos box = this.depositChests.get(this.depositIndex);
        // 交互范围 4.5 格: 不必贴近盒子, 4 格内即可远程右键打开, 减少寻路钻低矮处挖方块
        if (this.fartherThan(box, 4.0)) {
            this.walkTo(box, 3);
            return;
        }
        ScreenHandler menu = mc.player.currentScreenHandler;
        if (!(menu instanceof GenericContainerScreenHandler) && !(menu instanceof ShulkerBoxScreenHandler)) {
            if (!this.tryOpenContainer(box)) {
                if (this.openTries > 6) {
                    this.openTries = 0;
                    warning("打开存放盒失败, 走近一点重试");
                    this.walkTo(box, 2);
                } else {
                    this.throttle();
                }
            }
            return;
        }
        this.openTries = 0;
        if (this.boxFullOfTarget(menu)) {
            // 当前盒满: 换下一个存放盒; 全满则中止
            this.depositIndex = this.nextChestIndex(this.depositChests, this.depositIndex);
            if (this.depositIndex < 0) {
                this.failRun("存放盒已满, 请取走成品或增加黄玻璃标记的存放盒");
                return;
            }
            InvUtils.closeContainer();
            this.throttle();
            return;
        }
        // shift 一格背包成品入盒 (一次一格, 防封包风暴)
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == this.target && !stack.isEmpty()) {
                InvUtils.shiftClickInv(i);
                this.throttle();
                return;
            }
        }
    }

    /** 盒内已无空槽且无目标成品可续堆 => 一点都塞不下 */
    private boolean boxFullOfTarget(ScreenHandler menu) {
        int containerSize = this.containerSlotCount();
        int max = this.target.getDefaultStack().getMaxCount();
        boolean emptyFound = false;
        boolean roomFound = false;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = menu.slots.get(i).getStack();
            if (stack.isEmpty()) {
                emptyFound = true;
            } else if (stack.getItem() == this.target && stack.getCount() < max) {
                roomFound = true;
            }
        }
        return !emptyFound && !roomFound;
    }

    // ==================================================================
    // BACK / 寻路 / 开容器
    // ==================================================================

    private void tickBack() {
        if (mc.player.currentScreenHandler instanceof CraftingScreenHandler) {
            this.selfClosed = false;   // 工作台已重开
            this.state = State.CRAFT;
            this.resultEmptyTicks = 0;
            this.throttle();
            return;
        }
        if (this.tablePos == null || this.anchor == null) {
            warning("工作台位置丢失, 批次中止 (请手动打开工作台或重开模块)");
            this.lockBatch();
            this.state = State.IDLE;
            return;
        }
        if (this.fartherThan(this.anchor, 2.0)) {
            this.walkTo(this.anchor, 1);
            return;
        }
        if (this.tryOpenContainer(this.tablePos)) {
            this.throttle();
        } else if (this.openTries > 12) {
            this.openTries = 0;
            warning("自动打开工作台失败, 批次中止 (请手动右键工作台或重开模块)");
            this.lockBatch();
            this.state = State.IDLE;
        } else {
            this.throttle();
        }
    }

    private boolean goBackToTable() {
        if (this.tablePos == null || this.anchor == null) return false;
        if (this.fartherThan(this.anchor, 2.0)) {
            this.walkTo(this.anchor, 1);
            return true;
        }
        if (this.tryOpenContainer(this.tablePos)) {
            this.throttle();
            return true;
        }
        if (this.openTries <= 3) return true;
        this.openTries = 0;
        return false;
    }

    private void walkTo(BlockPos pos, int range) {
        if (this.distanceTo(pos) > MAX_PATH_DISTANCE) {
            this.failRun("目标距离超过 %d 格", MAX_PATH_DISTANCE);
            return;
        }
        if (!this.fartherThan(pos, range + 0.5)) {
            this.throttle();
            return;
        }
        this.walkGoal = pos;
        this.walkRange = range;
        this.walkReturn = this.state;
        this.walkTicks = 0;
        this.walkRepaths = 0;
        this.state = State.WALK;
        this.goalProcess().setGoalAndPath(new GoalNear(pos, range));
    }

    private void tickWalk() {
        this.walkTicks++;
        if (this.walkGoal == null) {
            this.lockBatch();   // 防御: 走位目标意外丢失也不得静默重开新批
            this.state = State.IDLE;
            return;
        }
        double dist = this.distanceTo(this.walkGoal);
        if (dist <= this.walkRange + 1.0) {
            State ret = this.walkReturn;
            this.walkGoal = null;
            this.state = ret == null ? State.IDLE : ret;
            this.throttle();
            return;
        }
        if (this.walkTicks >= 120) {
            this.walkTicks = 0;
            if (++this.walkRepaths > 3) {
                this.failRun("寻路停滞");
                return;
            }
            warning("寻路停滞, 重新寻路");
            this.goalProcess().setGoalAndPath(new GoalNear(this.walkGoal, this.walkRange));
        }
    }

    /** 用右键打开方块容器; 已打开目标容器返回 true */
    private boolean tryOpenContainer(BlockPos pos) {
        ScreenHandler menu = mc.player.currentScreenHandler;
        boolean isContainer = menu instanceof GenericContainerScreenHandler
                || menu instanceof ShulkerBoxScreenHandler
                || menu instanceof CraftingScreenHandler;
        if (isContainer) {
            if (this.lastOpenPos != null && this.lastOpenPos.equals(pos)) return true;
            InvUtils.closeContainer();
            this.lastOpenPos = null;
            this.throttle();
            return false;
        }
        if (mc.currentScreen != null) {
            mc.setScreen(null);
        }
        long now = System.currentTimeMillis();
        if (!this.openSent || now - this.lastOpenTime > 600) {
            PlaceUtils.open(pos);
            this.openSent = true;
            this.openTries++;
            this.lastOpenPos = pos;
            this.lastOpenTime = now;
        }
        return false;
    }

    // ==================================================================
    // 展示框补给点扫描 (相邻格容器 + 框所贴方块正下方的容器, 不依赖框朝向 API)
    // ==================================================================

    private void scanSupplyChests() {
        boolean depositWas = !this.depositChests.isEmpty();
        this.supplyChests.clear();
        this.depositChests.clear();
        ClientPlayerEntity player = mc.player;
        int range = this.supplyRange.get();
        BlockPos p = player.getBlockPos();
        Box box = new Box(p.getX() - range, p.getY() - range, p.getZ() - range,
                p.getX() + range, p.getY() + range, p.getZ() + range);
        List<ItemFrameEntity> frames = mc.world.getEntitiesByType(
                TypeFilter.instanceOf(ItemFrameEntity.class), box,
                frame -> !frame.getHeldItemStack().isEmpty());
        for (ItemFrameEntity frame : frames) {
            BlockPos framePos = frame.getBlockPos();
            if (framePos == null) continue;
            Item item = frame.getHeldItemStack().getItem();
            if (item == Items.AIR) continue;
            // 布局1(旧): 容器在展示框相邻格 (框贴在容器上或挨着容器)
            // 布局2: 框贴在支撑方块侧面, 潜影盒压在方块顶 (坐方块上) 或方块压在盒顶
            for (int d = 0; d < 6; d++) {
                BlockPos neighbor = framePos.offset(net.minecraft.util.math.Direction.values()[d]);
                if (this.isSupplyBlock(neighbor)) {
                    this.addMarkedContainer(item, neighbor);
                    continue;
                }
                // 支撑方块是实心块: 正下方与水平两侧都可能是容器 (下方识别保留, 上方识别移除)
                if (!mc.world.getBlockState(neighbor).isAir()) {
                    BlockPos below = neighbor.down();
                    if (this.isSupplyBlock(below)) {
                        this.addMarkedContainer(item, below);
                        continue;
                    }
                    for (int hd = 0; hd < 6; hd++) {
                        net.minecraft.util.math.Direction hDir = net.minecraft.util.math.Direction.values()[hd];
                        if (hDir.getAxis() == net.minecraft.util.math.Direction.Axis.Y) continue;
                        if (this.isSupplyBlock(neighbor.offset(hDir))) {
                            this.addMarkedContainer(item, neighbor.offset(hDir));
                        }
                    }
                }
            }
        }
        if (!this.depositChests.isEmpty() && !depositWas) {
            info("发现黄玻璃标记的存放盒: 装箱合成模式的成品将存入此处");
        }
    }

    /** 按展示框内物品分流: 黄玻璃 = 存放盒标记; 其它 = 该物品的补给箱 */
    private void addMarkedContainer(Item item, BlockPos pos) {
        if (item == Items.YELLOW_STAINED_GLASS) {
            if (!this.depositChests.contains(pos)) this.depositChests.add(pos);
            return;
        }
        this.addSupplyChest(item, pos);
    }

    /** 补给容器: 箱子/陷阱箱/任意颜色潜影盒 */
    private boolean isSupplyBlock(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
                || block instanceof ShulkerBoxBlock;
    }

    private void addSupplyChest(Item item, BlockPos pos) {
        List<BlockPos> chests = this.supplyChests.computeIfAbsent(item, key -> new ArrayList<>());
        if (!chests.contains(pos)) chests.add(pos);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void throttle() {
        this.delayTicks = Math.max(1, this.actionDelay.get());
    }

    private double distanceTo(BlockPos pos) {
        return Math.sqrt(this.distanceSq(pos));
    }

    private double distanceSq(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean fartherThan(BlockPos pos, double dist) {
        return this.distanceTo(pos) > dist;
    }

    private int emptyInvSlots() {
        int empty = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (mc.player.getInventory().getMainStacks().get(i).isEmpty()) empty++;
        }
        return empty;
    }

    private int countInInventory(Item item) {
        int count = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    /** 背包还能塞下多少个该物品 (空位 x64 + 半组剩余) */
    private int mergeSpaceInInventory(Item item) {
        int space = 0;
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getMainStacks().get(i);
            if (stack.isEmpty()) {
                space += 64;
            } else if (stack.getItem() == item) {
                space += Math.max(0, 64 - stack.getCount());
            }
        }
        return space;
    }

    private int containerSlotCount() {
        ScreenHandler menu = mc.player.currentScreenHandler;
        if (menu instanceof PlayerScreenHandler) {
            return menu.slots.size() - MAIN_SIZE - 1;
        }
        return menu.slots.size() - MAIN_SIZE;
    }

    private int nearestChestIndex(List<BlockPos> chests) {
        double best = Double.MAX_VALUE;
        int bestIndex = -1;
        for (int i = 0; i < chests.size(); i++) {
            double d = this.distanceSq(chests.get(i));
            if (d < best) {
                best = d;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private int nextChestIndex(List<BlockPos> chests, int current) {
        for (int i = current + 1; i < chests.size(); i++) {
            if (this.isSupplyBlock(chests.get(i))) return i;
        }
        return -1;
    }

    private void failRun(String format, Object... args) {
        error("运行中止: " + String.format(format, args));
        this.lockBatch();
        this.resetRun();
    }

    /** 批次终结: 同数量下不再自动重开, 改数量/重开模块后解除 */
    private void lockBatch() {
        this.lockBatch(false);
    }

    /** 批次终结: byFinish=true 表示正常完成(界面关闭后自动解除), 否则失败锁(需改数量/重开模块) */
    private void lockBatch(boolean byFinish) {
        this.batchDone = true;
        this.doneQty = this.qty.get();
        this.batchDoneByFinish = byFinish;
    }

    private void resetRun() {
        this.state = State.IDLE;
        this.remaining = 0;
        this.craftedTotal = 0;
        this.lastProgressLog = 0;
        this.takePending = false;
        this.takeWaitTicks = 0;
        this.takeBaseCount = 0;
        this.gridWaitTicks = 0;
        this.target = null;
        this.recipeEntry = null;
        this.perCraft.clear();
        this.resultPerCraft = 1;
        this.fetchItem = null;
        this.fetchNeed = 0;
        this.fetchChests = null;
        this.fetchChestIndex = -1;
        this.containerCursor = 0;
        this.openTries = 0;
        this.openSent = false;
        this.lastOpenPos = null;
        this.walkGoal = null;
        this.walkReturn = null;
        this.walkTicks = 0;
        this.walkRepaths = 0;
        this.resultEmptyTicks = 0;
        this.fetchScanned = false;
        this.fullWarned = false;
        this.fetchNoProgress = 0;
        this.refillWaitTicks = 0;
        this.selfClosed = false;
        this.depositIndex = -1;
        this.storeContinue = false;
        this.delayTicks = 0;
        try {
            InvUtils.closeContainer();
        } catch (Throwable ignored) {
        }
        this.cancelPath();
    }

    private ICustomGoalProcess goalProcess() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        return baritone.getCustomGoalProcess();
    }

    private void cancelPath() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            baritone.getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {
        }
    }

    private static String keyName(int code) {
        if (code == 0) return "无";
        String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0);
        if (name != null && !name.isEmpty()) return name.toUpperCase(java.util.Locale.ROOT);
        String full = net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(code).getTranslationKey();
        if (full.startsWith("key.keyboard.")) full = full.substring("key.keyboard.".length());
        return full.toUpperCase(java.util.Locale.ROOT);
    }

    // ==================================================================
    // 面板用公开访问器
    // ==================================================================

    public String getStateName() {
        return this.state.name();
    }

    public int getRemaining() {
        return this.remaining;
    }

    /** 列出所有可自动合成的物品 */
    public static List<Item> listCraftableItems() {
        ArrayList<Item> items = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !(mc.player.getRecipeBook() instanceof ClientRecipeBook book)) return items;
        for (RecipeResultCollection collection : book.getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (!(entry.display() instanceof ShapedCraftingRecipeDisplay)
                        && !(entry.display() instanceof ShapelessCraftingRecipeDisplay)) {
                    continue;
                }
                if (entry.craftingRequirements().isEmpty()) continue;
                for (ItemStack stack : entry.getStacks(SlotDisplayContexts.createParameters(mc.world))) {
                    Item item = stack.getItem();
                    if (item != Items.AIR && !items.contains(item)) items.add(item);
                }
            }
        }
        return items;
    }

    // ==================================================================
    // 聊天
    // ==================================================================
    private static final String PREFIX = "§b[自动合成]§r ";

    private static void info(String message) {
        send(PREFIX + "§7" + message);
    }

    private static void info(String format, Object... args) {
        send(PREFIX + "§7" + String.format(format, args));
    }

    private static void warning(String message) {
        send(PREFIX + "§e" + message);
    }

    private static void warning(String format, Object... args) {
        send(PREFIX + "§e" + String.format(format, args));
    }

    private static void error(String message) {
        send(PREFIX + "§c" + message);
    }

    private static void error(String format, Object... args) {
        send(PREFIX + "§c" + String.format(format, args));
    }

    private static void send(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        mc.player.sendMessage(Text.literal(text), false);
    }
}
