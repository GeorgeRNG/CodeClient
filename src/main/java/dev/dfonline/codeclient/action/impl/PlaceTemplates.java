package dev.dfonline.codeclient.action.impl;

import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.MoveToLocation;
import dev.dfonline.codeclient.PlotLocation;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class PlaceTemplates extends Action {
    public static final int rowSize = 6;

    private final List<ItemStack> templates;

    public int currentIndex = -1;
    private int timeSinceLastTick = 0;
    private ItemStack recoverMainHand;

    public PlaceTemplates(List<ItemStack> templates, Callback callback) {
        super(callback);
        this.templates = templates;
    }

    @Override
    public void init() {
        currentIndex = 0;
        recoverMainHand = CodeClient.MC.player.getMainHandStack();
    }

    static void makeHolding(ItemStack template) {
        PlayerInventory inv = CodeClient.MC.player.getInventory();
        CodeClient.MC.getNetworkHandler().sendPacket(new CreativeInventoryActionC2SPacket(36, template));
        inv.selectedSlot = 0;
        inv.setStack(0, template);
    }

    static void placeTemplateAt(int row, int level) {
        Vec3d pos = PlotLocation.getAsVec3d().add((2 + (row * 3)) * -1, level * 5, 0);
        new MoveToLocation(CodeClient.MC.player).setPos(pos.add(1,2,1));
        BlockHitResult blockHitResult = new BlockHitResult(pos.add(0,1,0), Direction.UP, new BlockPos.Mutable(pos.x, pos.y , pos.z), false);
        ((ClientPlayerInteractionManagerAccessor) (CodeClient.MC.interactionManager)).invokeSequencedPacket(CodeClient.MC.world, sequence -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, blockHitResult, sequence));
//        CodeClient.MC.interactionManager.interactBlock(CodeClient.MC.player, Hand.MAIN_HAND, blockHitResult);
    }

    @Override
    public void onTick() {
        if(currentIndex >= templates.size()) {
            currentIndex = -1;
            makeHolding(recoverMainHand);
            this.callback();
        };
        if(currentIndex == -1) return;
        timeSinceLastTick = timeSinceLastTick + 1;
        if(timeSinceLastTick == 5) {
            timeSinceLastTick = 0;
            makeHolding(templates.get(currentIndex));
            int level = currentIndex / rowSize;
            placeTemplateAt(currentIndex % rowSize, level);
            currentIndex += 1;
            if(currentIndex % rowSize == 0) {
                MoveToLocation.shove(CodeClient.MC.player, PlotLocation.getAsVec3d().add(0,(level * 5) + 2,0));
            }
        }
    }
}
