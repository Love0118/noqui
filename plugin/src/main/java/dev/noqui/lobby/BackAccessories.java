package dev.noqui.lobby;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One ordinary model display per selected back accessory; no custom GPU decoder. */
final class BackAccessories {
    static final String TAG="noqui_back_accessory_v2";
    private static final Map<String,Vector3f> OFFSETS=offsets();
    private final Map<UUID,Attachment> attachments=new HashMap<>();
    private record Attachment(ItemDisplay display,String model,boolean crouching) {}

    void tick(Player player,String model) {
        UUID owner=player.getUniqueId();
        if(model==null){remove(owner);return;}
        Vector3f offset=OFFSETS.get(model);
        if(offset==null)throw new IllegalArgumentException("Unknown back accessory: "+model);
        Location location=player.getLocation();location.setYaw(player.getBodyYaw());location.setPitch(0);
        boolean crouching=player.isSneaking();
        Attachment current=attachments.get(owner);
        if(current!=null&&(!current.display.isValid()||current.display.getWorld()!=location.getWorld())){remove(owner);current=null;}
        if(current==null) {
            ItemDisplay display=location.getWorld().spawn(location,ItemDisplay.class,entity->{
                entity.addScoreboardTag(TAG);entity.setPersistent(false);entity.setGravity(false);entity.setInvulnerable(true);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setTeleportDuration(1);entity.setInterpolationDuration(1);
                // A zero culling box keeps tall instruments visible at the screen edge.
                entity.setDisplayWidth(0);entity.setDisplayHeight(0);
                entity.setItemStack(item(model));entity.setTransformationMatrix(transform(offset,crouching));
            });
            attachments.put(owner,new Attachment(display,model,crouching));
        } else {
            if(!current.model.equals(model)) {
                current.display.setItemStack(item(model));
            }
            if(!current.display.getLocation().equals(location))current.display.teleport(location);
            if(!current.model.equals(model)||current.crouching!=crouching) {
                current.display.setTransformationMatrix(transform(offset,crouching));
                current.display.setInterpolationDelay(0);
                attachments.put(owner,new Attachment(current.display,model,crouching));
            }
        }
    }
    static Matrix4f transform(Vector3f offset,boolean crouching) {
        // Rotate about the shoulders, then place the model at the torso centre.
        return new Matrix4f().translate(0,crouching?1.3f:1.5f,0)
                .rotateX(crouching?.5f:0).translate(0,-.375f,0)
                // ItemDisplayRenderer supplies the 180-degree Y rotation itself.
                .translate(offset).scale(.8f);
    }
    private static ItemStack item(String model) {
        ItemStack item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();
        meta.setItemModel(new NamespacedKey("noqui",model));item.setItemMeta(meta);return item;
    }
    void remove(UUID owner){Attachment current=attachments.remove(owner);if(current!=null)current.display.remove();}
    void close(){for(UUID owner:List.copyOf(attachments.keySet()))remove(owner);}
    private static Map<String,Vector3f> offsets() {
        try(var stream=BackAccessories.class.getResourceAsStream("/native-equipment.json")) {
            if(stream==null)throw new IOException("Missing native-equipment.json");
            var root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("schemaVersion").getAsInt()!=2)throw new IOException("Unsupported equipment manifest");
            var result=new HashMap<String,Vector3f>();
            for(var value:root.getAsJsonArray("backs")) {
                var row=value.getAsJsonObject();var xyz=row.getAsJsonArray("translation");
                if(xyz.size()!=3)throw new IOException("Invalid back attachment translation");
                Vector3f offset=new Vector3f(xyz.get(0).getAsFloat(),xyz.get(1).getAsFloat(),xyz.get(2).getAsFloat());
                if(!offset.isFinite()||result.putIfAbsent(row.get("id").getAsString(),offset)!=null)throw new IOException("Invalid back attachment");
            }
            var expected=new HashSet<String>();
            for(var product:LobbyCatalog.defaults())if(product.slot().equals("back"))expected.add(product.id());
            if(!result.keySet().equals(expected))throw new IOException("Back attachments differ from cosmetic catalogue");
            return Map.copyOf(result);
        }catch(IOException|RuntimeException error){throw new IllegalStateException("Could not load back attachments",error);}
    }
}
