package dev.noqui.paper;

import dev.noqui.api.MiningControlService;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;

final class MiningCommands {
    static void execute(CommandSender sender,String[] args){
        if(args.length!=2||!args[1].equalsIgnoreCase("reset")&&!args[1].equalsIgnoreCase("rate"))throw new IllegalArgumentException("/noqui mine <reset|rate>");
        var service=Bukkit.getServicesManager().load(MiningControlService.class);
        if(service==null)throw new IllegalArgumentException("광산이 준비되지 않았습니다.");
        if(args[1].equalsIgnoreCase("reset")){
            service.reset();sender.sendMessage(Component.text("광산 리셋 / "+time(service.status().remainingMillis())));return;
        }
        var status=service.status();double fraction=status.initialOre()>0?status.remainingOre()*100.0/status.initialOre():0;
        sender.sendMessage(Component.text(String.format(Locale.ROOT,"광물 %.1f%% (%,d/%,d) / 리셋 %.0f%% 이하 / %s",fraction,status.remainingOre(),status.initialOre(),status.resetThreshold()*100,status.ready()?time(status.remainingMillis()):"리셋 중")));
    }
    private static String time(long millis){long seconds=(Math.max(0,millis)+999)/1000;return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
}
