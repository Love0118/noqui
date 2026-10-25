package dev.noqui.paper;

import dev.noqui.api.MiningControlService;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningCommandsTest {
    @Test void operatorCommandsUseTheMineServiceWithoutTouchingTheQuizAndRespectPermissions(){
        try(var bukkit=mockStatic(Bukkit.class)){
            var services=mock(org.bukkit.plugin.ServicesManager.class);bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            var mine=mock(MiningControlService.class);when(services.load(MiningControlService.class)).thenReturn(mine);
            when(mine.status()).thenReturn(new MiningControlService.Status(73,100,.60,1_200_000,true));
            var messages=new ArrayList<String>();var admin=mock(ConsoleCommandSender.class);when(admin.isOp()).thenReturn(true);
            doAnswer(c->{messages.add(PlainTextComponentSerializer.plainText().serialize(c.getArgument(0)));return null;}).when(admin).sendMessage(any(Component.class));
            var plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);
            plugin.onCommand(admin,null,"noqui",new String[]{"mine","rate"});assertTrue(messages.getLast().contains("73.0% (73/100)"));verify(mine,never()).reset();
            plugin.onCommand(admin,null,"noqui",new String[]{"mine","reset"});verify(mine).reset();assertEquals("광산 리셋 / 20:00",messages.getLast());
            clearInvocations(mine);when(admin.isOp()).thenReturn(false);plugin.onCommand(admin,null,"noqui",new String[]{"mine","reset"});verifyNoInteractions(mine);
            when(admin.isOp()).thenReturn(true);assertEquals(List.of("reset","rate"),plugin.onTabComplete(admin,null,"noqui",new String[]{"mine",""}));
            plugin.onCommand(admin,null,"noqui",new String[]{"mine","reset","extra"});verifyNoInteractions(mine);assertEquals("/noqui mine <reset|rate>",messages.getLast());
        }
    }
}
