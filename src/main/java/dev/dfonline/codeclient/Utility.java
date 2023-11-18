package dev.dfonline.codeclient;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.action.impl.PlaceTemplates;
import dev.dfonline.codeclient.hypercube.template.Template;
import dev.dfonline.codeclient.hypercube.template.TemplateBlock;
import dev.dfonline.codeclient.location.Dev;
import io.netty.util.internal.ObjectUtil;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.ObjectUtils;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class Utility {
    /**
     * Get the slot id to be used with a creative packet, from a local slot id.
     */
    public static int getRemoteSlot(int slot) {
        if(0 <= slot && slot <= 8) { // this is for the hotbar, which is after the inventory in packets.
            return slot + 36;
        }
        else return slot;
    }

    /**
     * Be lazy, send your whole inventory!
     */
    public static void sendInventory() {
        for (int i = 0; i <= 35; i++) {
            CodeClient.MC.getNetworkHandler().sendPacket(new CreativeInventoryActionC2SPacket(getRemoteSlot(i), CodeClient.MC.player.getInventory().getStack(i)));
        }
    }

    /**
     * Ensure the player is holding an item, by holding and setting the first slot.
     * @param item Any item
     */
    public static void makeHolding(ItemStack item) {
        PlayerInventory inv = CodeClient.MC.player.getInventory();
        Utility.sendHandItem(item);
        inv.selectedSlot = 0;
        inv.setStack(0, item);
    }

    public static PlaceTemplates createSwapper(List<ItemStack> templates, Action.Callback callback) {
        if(CodeClient.location instanceof Dev dev) {
            HashMap<BlockPos, ItemStack> map = new HashMap<>();
            var scan = dev.scanForSigns(Pattern.compile(".*"));
            for (ItemStack item : templates) {
                if (!item.hasNbt()) continue;
                NbtCompound nbt = item.getNbt();
                if (nbt == null) continue;
                if (!nbt.contains("PublicBukkitValues")) continue;
                NbtCompound publicBukkit = nbt.getCompound("PublicBukkitValues");
                if (!publicBukkit.contains("hypercube:codetemplatedata")) continue;
                String codeTemplateData = publicBukkit.getString("hypercube:codetemplatedata");
                try {
                    Template template = Template.parse64(JsonParser.parseString(codeTemplateData).getAsJsonObject().get("code").getAsString());
                    if(template.blocks.isEmpty()) continue;
                    TemplateBlock block = template.blocks.get(0);
                    if(block.block == null) continue;
                    TemplateBlock.Block blockName = TemplateBlock.Block.valueOf(block.block.toUpperCase());
                    String name = ObjectUtils.firstNonNull(block.action, block.data);
                    for (Map.Entry<BlockPos, SignText> sign: scan.entrySet()) { // Loop through scanned signs
                        SignText text = sign.getValue();                        // ↓ If the blockName and name match
                        if(text.getMessage(0,false).getString().equals(blockName.name) && text.getMessage(1,false).getString().equals(name)) {
                            map.put(sign.getKey().east(), item);                // Put it into map
                            break;                                              // break out :D
                        }
                    }
                }
                catch (Exception e) {
                    CodeClient.LOGGER.warn(e.getMessage());
                }
            }
            return new PlaceTemplates(map, callback);
        }
        return null;
    }

    public static void sendHandItem(ItemStack item) {
        CodeClient.MC.getNetworkHandler().sendPacket(new CreativeInventoryActionC2SPacket(36 + CodeClient.MC.player.getInventory().selectedSlot, item));
    }

    /**
     * Gets all templates in the players inventory.
     */
    public static List<ItemStack> TemplatesInInventory() {
        PlayerInventory inv = CodeClient.MC.player.getInventory();
        ArrayList<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < (27 + 9); i++) {
            ItemStack item = inv.getStack(i);
            if (!item.hasNbt()) continue;
            NbtCompound nbt = item.getNbt();
            if (!nbt.contains("PublicBukkitValues")) continue;
            NbtCompound publicBukkit = nbt.getCompound("PublicBukkitValues");
            if (!publicBukkit.contains("hypercube:codetemplatedata")) continue;
            templates.add(item);
        }
        return templates;
    }

    public static String compileTemplate(JsonObject data) throws IOException {
        return compileTemplate(data.getAsString());
    }

    /**
     * GZIPs and base64's data for use in templates.
     * @throws IOException If an I/O error happened with gzip
     */
    public static String compileTemplate(String data) throws IOException {
        ByteArrayOutputStream obj = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(obj);
        gzip.write(data.getBytes());
        gzip.close();

        return new String(Base64.getEncoder().encode(obj.toByteArray()));
    }

    public static void sendMessage(String message, ChatType type) {
        sendMessage(Text.of(message), type);
    }
    public static void sendMessage(String message) {
        sendMessage(Text.of(message), ChatType.INFO);
    }
    public static void sendMessage(Text message) {
        sendMessage(message, ChatType.INFO);
    }

    public static void sendMessage(Text message, @Nullable ChatType type) {
        ClientPlayerEntity player = CodeClient.MC.player;
        if (player == null) return;
        if (type == null) {
            player.sendMessage(message, false);
        } else {
            player.sendMessage(Text.literal(type.getString() + " ").append(message).setStyle(Style.EMPTY.withColor(type.getTrailing())), false);
            if (type == ChatType.FAIL) {
                player.playSound(SoundEvent.of(new Identifier("minecraft:block.note_block.didgeridoo")), SoundCategory.PLAYERS, 2, 0);
            }
        }
    }

    /**
     * Prepares a text object for use in an item's display tag
     * @return Usable in lore and as a name in nbt.
     */
    public static NbtString nbtify(Text text) {
        JsonObject json = Text.Serializer.toJsonTree(text).getAsJsonObject();

        if(!json.has("color")) json.addProperty("color","white");
        if(!json.has("italic")) json.addProperty("italic",false);
        if(!json.has("bold")) json.addProperty("bold",false);

        return NbtString.of(json.toString());
    }

    /**
     * Parses § formatted strings.
     * @param text § formatted string.
     * @return Text with all parsed text as siblings.
     */
    public static MutableText textFromString(String text) {
        MutableText output = Text.empty().setStyle(Text.empty().getStyle().withColor(TextColor.fromRgb(0xFFFFFF)).withItalic(false));
        MutableText component = Text.empty();

        Matcher m = Pattern.compile("§(([0-9a-kfmnolr])|x(§[0-9a-f]){6})|[^§]+").matcher(text);
        while (m.find()) {
            String data = m.group();
            if(data.startsWith("§")) {
                if(data.startsWith("§x")) {
                    component = component.setStyle(component.getStyle().withColor(Integer.valueOf(data.replaceAll("§x|§",""), 16)));
                }
                else {
                    component = component.formatted(Formatting.byCode(data.charAt(1)));
                }
            }
            else {
                component.append(data);
                output.append(component);
                component = Text.empty().setStyle(component.getStyle());
            }
        }
        return output;
    }

    public static boolean isGlitchStick(ItemStack item) {
        if(item == null) return false;
        NbtCompound nbt = item.getNbt();
        if(nbt == null) return false;
        if(nbt.isEmpty()) return false;
        if(Objects.equals(nbt.getCompound("PublicBukkitValues").getString("hypercube:item_instance"), "")) return false;
        return Objects.equals(nbt.getCompound("display").getString("Name"), "{\"italic\":false,\"color\":\"red\",\"text\":\"Glitch Stick\"}");
    }
}