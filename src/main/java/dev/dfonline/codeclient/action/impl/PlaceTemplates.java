package dev.dfonline.codeclient.action.impl;

import com.google.gson.JsonParser;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.data.DFItem;
import dev.dfonline.codeclient.hypercube.template.Template;
import dev.dfonline.codeclient.hypercube.template.TemplateBlock;
import dev.dfonline.codeclient.location.Dev;
import dev.dfonline.codeclient.mixin.entity.player.ClientPlayerInteractionManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignText;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.ObjectUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PlaceTemplates extends Action {
    private final ArrayList<Operation> operations;
    private int cooldown = 0;
    private ItemStack recoverMainHand;
    private GoTo goTo = null;
    private boolean shouldBeSwapping = false;

    public PlaceTemplates(ArrayList<Operation> operations, Callback callback) {
        super(callback);
        this.operations = operations;
    }

    public PlaceTemplates(List<ItemStack> templates, Callback callback) {
        super(callback);
        if (CodeClient.location instanceof Dev plot) {
            int i = 0;
            ArrayList<Operation> templatesToPlace = new ArrayList<>();
            var rowSize = plot.assumeSize().codeWidth / 2 + 1;
            for (ItemStack template : templates) {
                int row = i % rowSize;
                int level = i / rowSize;
                i++;
                Vec3d pos = new Vec3d(plot.getX(), plot.getFloorY(), plot.getZ()).add((2 + (row * 3)) * -1, level * 5, 0);
                templatesToPlace.add(new TemplateToPlace(pos, template));
            }
            this.operations = templatesToPlace;
        } else {
            throw new IllegalStateException("Player must be in dev mode.");
        }
    }

    public PlaceTemplates(HashMap<BlockPos, ItemStack> templates, Callback callback) {
        super(callback);
        ArrayList<Operation> templatesToPlace = new ArrayList<>();
        for (var template : templates.entrySet()) {
            templatesToPlace.add(new TemplateToPlace(template.getKey(), template.getValue()));
        }
        this.operations = templatesToPlace;
    }

    /**
     * Doesn't add .swap(), that needs to be added yourself.
     * Will return <b>null</b> if there is no space.
     */
    public static @Nullable PlaceTemplates createSwapper(List<ItemStack> templates, Callback callback) {
        if (CodeClient.location instanceof Dev dev) {
            HashMap<BlockPos, ItemStack> map = new HashMap<>();
            var scan = dev.scanForSigns(Pattern.compile(".*"));
            ArrayList<ItemStack> leftOvers = new ArrayList<>(templates);
            for (ItemStack item : templates) {
                DFItem dfItem = new DFItem(item);
                String codeTemplateData = dfItem.getHypercubeStringValue("codetemplatedata");
                if (codeTemplateData.isEmpty()) continue;
                try {
                    Template template = Template.parse64(JsonParser.parseString(codeTemplateData).getAsJsonObject().get("code").getAsString());
                    if (template == null || template.blocks.isEmpty()) continue;
                    TemplateBlock block = template.blocks.get(0);
                    if (block.block == null) continue;
                    TemplateBlock.Block blockName = TemplateBlock.Block.valueOf(block.block.toUpperCase());
                    String name = ObjectUtils.firstNonNull(block.action, block.data);
                    for (Map.Entry<BlockPos, SignText> sign : scan.entrySet()) { // Loop through scanned signs
                        SignText text = sign.getValue();                         // ↓ If the blockName and name match
                        if (text.getMessage(0, false).getString().equals(blockName.name) && text.getMessage(1, false).getString().equals(name)) {
                            map.put(sign.getKey().east(), item);                 // Put it into map
                            leftOvers.remove(item);                              // Remove the template, so we can see if there's anything left over
                            break;                                               // break out :D
                        }
                    }
                } catch (Exception e) {
                    CodeClient.LOGGER.warn(e.getMessage());
                }
            }
            if (!leftOvers.isEmpty()) {
                BlockPos freePos = dev.findFreePlacePos();
                for (var item : leftOvers) {
                    if(freePos == null) return null;
                    map.put(freePos, item);
                    freePos = dev.findFreePlacePos(freePos.west(2));
                }
            }
            return new PlaceTemplates(map, callback);
        }
        return null;
    }

    /**
     * A regular placer will always start from where code starts.
     * This will use any free spaces instead.
     * Will return <b>null</b> if there is no space.
     */
    public static PlaceTemplates createPlacer(List<ItemStack> templates, Callback callback) {
        return createPlacer(templates, callback, false);
    }

    /**
     * A regular placer will always start from where code starts.
     * This will use any free spaces instead.
     * Will return <b>null</b> if there is no space or some other error occurs.
     */
    public static PlaceTemplates createPlacer(List<ItemStack> templates, Callback callback, boolean compacter) {
        if (CodeClient.location instanceof Dev dev) {
            var map = new HashMap<BlockPos, ItemStack>();
            if (!compacter) {
                BlockPos lastPos = dev.findFreePlacePos();
                for (var template : templates) {
                    if(lastPos == null) return null;
                    map.put(lastPos, template);
                    lastPos = dev.findFreePlacePos(lastPos.west(2));
                }
            } else {
                BlockPos nextPos = dev.findFreePlacePos();
                for (var template : templates) {
                    Template parsed = Template.parse64(Utility.templateDataItem(template));
                    if(parsed == null) return null;
                    int size = parsed.getLength();
                    var placePos = nextPos;
                    if(placePos == null) return null;
                    var templateEndPos = placePos.south(size);
                    if (dev.isInDev(templateEndPos)) {
                        nextPos = templateEndPos;
                    } else {
                        placePos = dev.findFreePlacePos(placePos.west(2));
                        if(placePos == null) return null;
                        nextPos = placePos.south(size);
                    }
                    map.put(placePos, template);
                }
            }
            return new PlaceTemplates(map, callback);
        }
        return null;
    }

    public static PlaceTemplates breakTemplates(List<String> names, Callback callback) {
        if(CodeClient.location instanceof Dev dev) { // TODO: make this passed in as a parameter
            var targets = new ArrayList<Operation>();
            HashMap<BlockPos, SignText> scan = dev.scanForSigns(Pattern.compile(".*"));
            for (var entry: scan.entrySet()) {
                var sign = entry.getValue();
                for (var name : names) {
                    var type = name.split(" ")[0];
                    var action = name.substring(type.length() + 1);
                    if (type.equals("any") || sign.getMessage(0, false).getString().toLowerCase().contains(type.toLowerCase())) {
                        if (action.equals(sign.getMessage(1, false).getString())) {
                            targets.add(new DestroyOperation(entry.getKey()));
                        }
                    }
                }
            }
            return new PlaceTemplates(targets,callback);
        }
        return null;
    }

    /**
     * If there is a template in the way, replace it.
     */
    public PlaceTemplates swap() {
        this.shouldBeSwapping = true;
        return this;
    }

    @Override
    public void init() {
        cooldown = 4;
        assert CodeClient.MC.player != null;
        recoverMainHand = CodeClient.MC.player.getMainHandStack();
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        if (packet instanceof OpenScreenS2CPacket) {
            return true;
        }
        if (packet instanceof ChunkDeltaUpdateS2CPacket updates) {
            for (Operation operation : operations) {
                var block = new Object() {
                    boolean isTemplate = false;
                    BlockState state = null;
                };
                updates.visitUpdates((blockPos, blockState) -> {
                    if (operation.pos.equals(blockPos)) {
                        block.isTemplate = true;
                        block.state = blockState;
                    }
                });
                if (operation instanceof TemplateToPlace || operation instanceof DestroyOperation) {
                    if (!block.isTemplate) continue;
                    operation.setComplete();
                }
            }
        }
        return super.onReceivePacket(packet);
    }

    @Override
    public void tick() {
        var net = CodeClient.MC.getNetworkHandler();
        if (CodeClient.MC.interactionManager == null || CodeClient.MC.player == null || net == null) return;
        if (CodeClient.location instanceof Dev) {
            if (operations.isEmpty()) {
                callback();
                return;
            }
            if (goTo != null) {
                goTo.tick();
            }
            if (cooldown > 0) cooldown--;
            Operation closestOperation = null;
            operations.removeIf(Operation::isComplete);
            for (Operation operation : operations.stream().toList()) {
                double distanceToCurrentOperation = (operation.pos().distanceTo(CodeClient.MC.player.getEyePos()));
                if (distanceToCurrentOperation <= 5.8 && operation.isOpen() && !operation.isComplete()) {
                    if (cooldown == 0) {
                        if (operation instanceof TemplateToPlace template) {
                            if (shouldBeSwapping) {
                                var player = CodeClient.MC.player;
                                boolean sneaky = !player.isSneaking();
                                if (sneaky)
                                    net.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
                                net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, template.pos, Direction.UP));
                                net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, template.pos, Direction.UP));
                                if (sneaky)
                                    net.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
                            }
                            Utility.makeHolding(template.template);
                            BlockHitResult blockHitResult = new BlockHitResult(template.pos().add(0, 1, 0), Direction.UP, template.pos, false);
                            ((ClientPlayerInteractionManagerAccessor) (CodeClient.MC.interactionManager)).invokeSequencedPacket(CodeClient.MC.world, sequence -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, blockHitResult, sequence));
                            template.setOpen(false);
                        }
                        if (operation instanceof DestroyOperation destroy) {
                            var player = CodeClient.MC.player;
                            boolean sneaky = !player.isSneaking();
                            if (sneaky)
                                net.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
                            net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, destroy.pos, Direction.UP));
                            net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, destroy.pos, Direction.UP));
                            if (sneaky)
                                net.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
                        }
                    }
                    return;
                }
                if ((closestOperation == null) || (distanceToCurrentOperation < closestOperation.pos().distanceTo(CodeClient.MC.player.getEyePos()))) {
                    closestOperation = operation;
                }
            }
            if (closestOperation == null) {
                for (Operation operation : operations) {
                    operation.setOpen(!operation.isComplete());
                }
            } else {
                goTo = new GoTo(closestOperation.pos().add(-2, 0.5, 0), () -> this.goTo = null);
                goTo.init();
                cooldown = 2;
            }
        }
    }

    public static abstract class Operation {
        protected final BlockPos pos;
        /**
         * If this operation can be acted on, and needs to be completed.
         */
        private boolean open = true;
        /**
         * Whether this operation is complete and can be dismissed.
         */
        private boolean complete = false;

        public Operation(BlockPos pos) {
            this.pos = pos;
        }

        public Operation(Vec3d pos) {
            this.pos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        }

        public Vec3d pos() {
            return pos.toCenterPos();
        }

        public boolean isOpen() {
            return open;
        }

        public void setOpen(boolean open) {
            this.open = open;
        }

        /**
         * Mark for dismissing. Doesn't need to be used again.
         */
        public void setComplete() {
            this.open = false;
            this.complete = true;
        }

        public boolean isComplete() {
            return complete;
        }
    }

    private static class TemplateToPlace extends Operation {
        private final ItemStack template;

        public TemplateToPlace(Vec3d pos, ItemStack template) {
            super(pos);
            this.template = template;
        }

        public TemplateToPlace(BlockPos pos, ItemStack template) {
            super(pos);
            this.template = template;
        }
    }

    private static class DestroyOperation extends Operation {
        public DestroyOperation(BlockPos pos) {
            super(pos);
        }
    }
}
