package dev.noqui.lobby;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/** Jump-input boosts and one owned gravity modifier. Never takes ownership of vanilla flight. */
final class LobbyMovement {
    static final NamespacedKey GRAVITY_KEY=new NamespacedKey("noqui","lobby_gravity");
    static final double AIR_JUMP_VELOCITY=.85;
    private static final class State { boolean jumpHeld; int used; }
    private final Map<UUID,State> states=new HashMap<>();
    private final World cabin;
    LobbyMovement(World cabin){this.cabin=cabin;}
    static int extraJumps(String boots){return "quad_boots".equals(boots)?3:"triple_boots".equals(boots)?2:1;}
    static boolean eligible(Player p,World cabin){return p.getWorld().equals(cabin)&&(p.getGameMode()==GameMode.ADVENTURE||p.getGameMode()==GameMode.SURVIVAL)&&!p.isDead();}
    void tick(Player p,String boots,boolean suit) {
        if(!eligible(p,cabin)){leave(p);return;}
        State s=states.computeIfAbsent(p.getUniqueId(),id->new State());
        if(p.isOnGround())s.used=0;
        gravity(p,suit);
    }
    boolean input(Player p,boolean pressed,String boots) {
        if(!eligible(p,cabin)){leave(p);return false;}
        State s=states.computeIfAbsent(p.getUniqueId(),id->new State());
        boolean rising=pressed&&!s.jumpHeld;s.jumpHeld=pressed;
        if(p.isOnGround()){s.used=0;return false;}
        return rising&&jump(p,boots);
    }
    boolean jump(Player p,String boots) {
        if(!eligible(p,cabin)||p.isOnGround()||p.getAllowFlight()||p.isFlying()||p.isGliding()||p.isInsideVehicle())return false;
        State s=states.computeIfAbsent(p.getUniqueId(),id->new State());
        if(s.used>=extraJumps(boots))return false;
        s.used++;
        Vector direction=p.getLocation().getDirection().setY(0);
        if(direction.lengthSquared()>0)direction.normalize().multiply(.55);
        p.setVelocity(direction.setY(AIR_JUMP_VELOCITY));p.setFallDistance(0);
        p.playSound(Sound.sound(Key.key("minecraft:entity.breeze.jump"),Sound.Source.PLAYER,.4f,1f+.1f*(s.used-1)));
        return true;
    }
    void leave(Player p) {
        states.remove(p.getUniqueId());
        gravity(p,false);
    }
    void suspend(Player p){gravity(p,false);}
    private static void gravity(Player p,boolean active) {
        AttributeInstance attribute=p.getAttribute(Attribute.GRAVITY);if(attribute==null)return;
        AttributeModifier existing=attribute.getModifier(GRAVITY_KEY);
        if(active&&existing==null)attribute.addTransientModifier(new AttributeModifier(GRAVITY_KEY,-.5,AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        else if(!active&&existing!=null&&existing.getAmount()==-.5&&existing.getOperation()==AttributeModifier.Operation.MULTIPLY_SCALAR_1)attribute.removeModifier(existing);
    }
}
