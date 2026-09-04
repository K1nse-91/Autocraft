package com.autocarpet.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 方块交互 (1.21.11): interactBlock 不校验视线, 服务端只校验距离,
 * 因此可以"静默"对任意可达方块发右键。
 */
public class PlaceUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * 对准方块离玩家最近的一个面中心右键 (开箱/开工作台通用)。
     * 服务端只校验命中点距离, 选最近面可比固定顶面多争取约半格交互距离,
     * 潜影盒/箱子在角落或隔墙时更容易打开成功。
     */
    public static ActionResult open(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return ActionResult.FAIL;
        Vec3d eye = mc.player.getEyePos();
        Direction best = Direction.UP;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            Vec3d faceCenter = pos.toCenterPos().add(dir.getOffsetX() * 0.5, dir.getOffsetY() * 0.5, dir.getOffsetZ() * 0.5);
            double d = faceCenter.squaredDistanceTo(eye);
            if (d < bestDist) {
                bestDist = d;
                best = dir;
            }
        }
        Vec3d hitPos = pos.toCenterPos().add(best.getOffsetX() * 0.5, best.getOffsetY() * 0.5, best.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, best, pos, false);
        return mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
    }
}
