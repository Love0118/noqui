package dev.noqui.api;

import java.util.UUID;
import org.bukkit.entity.Player;

/** Main-thread API. Look up the service on each use; never retain a runtime provider. */
public interface QuizService {
    boolean started();
    boolean participant(UUID player);
    boolean routeJoin(Player player);
    void leave(Player player);
}
