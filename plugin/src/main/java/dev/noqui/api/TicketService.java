package dev.noqui.api;

import java.util.UUID;
import org.bukkit.entity.Player;

/** Main-thread ticket account API. Resolve the current service for each operation. */
public interface TicketService {
    long balance(UUID player);
    /** Durable and idempotent: reuse the transaction ID when retrying the same grant. */
    void grant(UUID player, long amount, String transactionId);

    /** A minigame owns its inventory and refreshes its own quiz icon without replacing other items. */
    interface Inventory {
        boolean ownsInventory(Player player);
        void refreshTickets(Player player, long balance);
    }
}
