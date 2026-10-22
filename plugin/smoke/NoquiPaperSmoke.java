package dev.noqui.smoke;

import dev.noqui.api.*;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs only in the disposable fixture server, with zero external clients. */
public final class NoquiPaperSmoke extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                require(Bukkit.getPluginManager().isPluginEnabled("Noqui"),"Noqui enabled");
                require(Bukkit.getPluginManager().isPluginEnabled("MCLuckDefense"),"Moma enabled");
                var world=Objects.requireNonNull(Bukkit.getWorld("noqui_cabin"));
                require(world.getWorldBorder().getSize()==250,"250 border");
                require(world.getWorldBorder().getCenter().getX()==416 && world.getWorldBorder().getCenter().getZ()==-192,"border center");
                var spawn=world.getSpawnLocation();require(world.getBlockAt(spawn.clone().subtract(0,1,0)).getType().isSolid(),"spawn floor");
                var dialog=Dialog.create(b->b.empty().base(DialogBase.builder(Component.text("Noqui native fixture"))
                        .inputs(List.of(DialogInput.text("title",Component.text("제목")).maxLength(160).build())).build())
                        .type(DialogType.notice(ActionButton.create(Component.text("닫기"),null,120,null))));
                require(dialog!=null,"native dialog factory");
                var noqui=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Noqui"));
                var dialogsField=noqui.getClass().getDeclaredField("dialogs");dialogsField.setAccessible(true);Object dialogs=dialogsField.get(noqui);
                Class<?> entryType=Class.forName("dev.noqui.paper.BrowserConnections$Entry",true,noqui.getClass().getClassLoader());
                var entryConstructor=entryType.getDeclaredConstructor(UUID.class,UUID.class);entryConstructor.setAccessible(true);
                Object entry=entryConstructor.newInstance(UUID.randomUUID(),UUID.randomUUID());
                int[] nativePrompts={0};
                var player=(org.bukkit.entity.Player)java.lang.reflect.Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),new Class<?>[]{org.bukkit.entity.Player.class},(proxy,method,args)->{
                    if(method.getName().equals("getUniqueId"))return UUID.fromString("12345678-1234-4234-8234-123456789abc");
                    if(method.getName().equals("showDialog")){nativePrompts[0]++;return null;}
                    if(method.getName().equals("openInventory"))throw new IllegalStateException("Connection prompt used inventory");
                    if(method.getName().equals("toString"))return "NoquiFixturePlayer";
                    throw new UnsupportedOperationException(method.getName());
                });
                var connection=dialogs.getClass().getDeclaredMethod("connection",org.bukkit.entity.Player.class,entryType,String.class);connection.setAccessible(true);
                connection.invoke(dialogs,player,entry,null);connection.invoke(dialogs,player,entry,"https://noqui.danta.qzz.io/join#t=fixture");
                require(nativePrompts[0]==2,"native pending and URL connection dialogs");
                var quiz=Objects.requireNonNull(Bukkit.getServicesManager().load(QuizService.class));
                var before=Objects.requireNonNull(Bukkit.getServicesManager().load(DefenseService.class));
                boolean expectedActive=Boolean.getBoolean("noqui.smoke.expect-active");
                require(quiz.started()==expectedActive,"persisted active flag");
                if(expectedActive){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui stop");require(!quiz.started(),"restored waiting game stops");}
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui start");require(quiz.started(),"empty-server start");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"mud reload");
                var after=Objects.requireNonNull(Bukkit.getServicesManager().load(DefenseService.class));
                require(before!=after,"runtime provider replaced");require(quiz.started(),"quiz survives Moma reload");
                require(Bukkit.getServicesManager().getRegistrations(DefenseService.class).size()==1,"single current provider");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui stop");require(!quiz.started(),"stop");
                if(Boolean.getBoolean("noqui.smoke.keep-active")){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"noqui start");require(quiz.started(),"persist active before shutdown");}
                getLogger().info("NOQUI_SMOKE_OK world native-dialog connection-dialog services empty-start runtime-reload stop persisted-active="+expectedActive);
            } catch(Throwable error){getLogger().log(java.util.logging.Level.SEVERE,"NOQUI_SMOKE_FAIL",error);}
            finally{Bukkit.shutdown();}
        },40);
    }
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
