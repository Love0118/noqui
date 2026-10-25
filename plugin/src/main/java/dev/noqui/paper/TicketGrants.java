package dev.noqui.paper;

import dev.noqui.api.TicketService;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class TicketGrants implements TicketService {
    private final NoquiPlugin plugin;
    TicketGrants(NoquiPlugin plugin) { this.plugin=plugin; }
    @Override public long balance(UUID player) { return plugin.ticketCount(player); }
    @Override public void grant(UUID player,long amount,String transactionId) { plugin.giveTickets(player,amount,transactionId); }

    static boolean deliver(NoquiPlugin plugin,QuestionTickets tickets,UUID id) {
        if(tickets!=null)tickets.refresh(id);
        Player player=Bukkit.getPlayer(id);
        if(player==null)return true;
        if(plugin.ownsCabin(id))return !plugin.started()||tickets==null||tickets.pending(player)==0;
        var services=Bukkit.getServicesManager();
        if(services!=null)for(var registration:services.getRegistrations(TicketService.Inventory.class)) {
            var inventory=registration.getProvider();
            if(inventory.ownsInventory(player)){inventory.refreshTickets(player,plugin.ticketCount(id));break;}
        }
        return true;
    }
}
