import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Final empty check and shutdown request happen together on Minecraft's main thread. */
public final class EmptyHintRestartAgent {
    public static void agentmain(String directory, Instrumentation instrumentation) throws Exception {
        Path stage = Path.of(directory).toAbsolutePath();
        Class<?> bukkit = Arrays.stream(instrumentation.getAllLoadedClasses())
            .filter(type -> type.getName().equals("org.bukkit.Bukkit")).findFirst().orElseThrow();
        Object manager = bukkit.getMethod("getPluginManager").invoke(null);
        Object plugin = manager.getClass().getMethod("getPlugin", String.class).invoke(manager, "Noqui");
        if (plugin == null) throw new IllegalStateException("Noqui unavailable; no shutdown requested");
        Class<?> pluginType = Class.forName("org.bukkit.plugin.Plugin", false, plugin.getClass().getClassLoader());
        Object scheduler = bukkit.getMethod("getScheduler").invoke(null);
        var complete = new CompletableFuture<Void>();
        Runnable check = () -> {
            try {
                if (!(Boolean)bukkit.getMethod("isPrimaryThread").invoke(null)) throw new IllegalStateException("Not main thread");
                var players = (Collection<?>)bukkit.getMethod("getOnlinePlayers").invoke(null);
                if (!players.isEmpty()) {
                    Files.writeString(stage.resolve("empty-check.txt"), "busy=" + players.size() + "\n");
                    complete.complete(null); return;
                }
                Files.writeString(stage.resolve("empty-check.txt"), "shutdown-requested; players=0; main-thread=true\n");
                complete.complete(null);
                bukkit.getMethod("shutdown").invoke(null);
            } catch (Throwable error) { complete.completeExceptionally(error); }
        };
        scheduler.getClass().getMethod("runTask", pluginType, Runnable.class).invoke(scheduler, plugin, check);
        complete.get(20, TimeUnit.SECONDS);
    }
}
