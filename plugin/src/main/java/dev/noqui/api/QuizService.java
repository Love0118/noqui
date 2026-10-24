package dev.noqui.api;

import java.util.UUID;
import org.bukkit.entity.Player;

/** Main-thread API. Look up the service on each use; never retain a runtime provider. */
public interface QuizService {
    boolean started();
    boolean participant(UUID player);
    boolean ownsCabin(UUID player);
    boolean routeJoin(Player player);
    void openControls(Player player);
    default long ticketCount(UUID player){return 0;}
    default void openProxy(Player player){openControls(player);}
    void prepareDefense(Player player);
    void defenseEntered(Player player);
    void defenseFailed(Player player);
    void leave(Player player);
    /** Shared lobby items; the mine separately owns its outside-inventory recovery transaction. */
    default void restoreSharedInventory(Player player){throw new IllegalStateException("공용 인벤토리를 업데이트하세요.");}
    default void saveSharedInventory(Player player){throw new IllegalStateException("공용 인벤토리를 업데이트하세요.");}
}
