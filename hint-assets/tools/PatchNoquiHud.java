import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Offline merge: retain every current live plugin entry outside the reviewed HUD delta. */
public final class PatchNoquiHud {
    static byte[] constructor(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (var method : node.methods) if (method.name.equals("<init>")) {
            for (var instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/noqui/paper/QuizHud")
                    && call.name.equals("<init>") && call.desc.equals("()V")) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                    call.desc = "(Lorg/bukkit/plugin/Plugin;)V"; changed++;
                }
        }
        if (changed != 1) throw new IllegalStateException("Live HUD constructor changed; review required");
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        try (var live = new ZipFile(args[0]); var build = new ZipFile(args[1]);
             var out = new ZipOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            Map<String,byte[]> entries = new TreeMap<>();
            for (var entry : Collections.list(live.entries())) if (!entry.isDirectory())
                try (var in = live.getInputStream(entry)) { entries.put(entry.getName(), in.readAllBytes()); }
            String plugin = "dev/noqui/paper/NoquiPlugin.class";
            entries.put(plugin, constructor(Objects.requireNonNull(entries.get(plugin))));
            for (var entry : Collections.list(build.entries())) {
                String name = entry.getName();
                if (name.matches("dev/noqui/paper/(QuizHud|HintOverlay|HintGlyphs)(\\$[^/]+)?\\.class")
                    || name.equals("hint-pack.properties") || name.equals("hint-glyph-widths.json"))
                    try (var in = build.getInputStream(entry)) { entries.put(name, in.readAllBytes()); }
            }
            if (!entries.containsKey("dev/noqui/paper/HintOverlay.class")) throw new IllegalStateException("HUD classes missing");
            for (var entry : entries.entrySet()) {
                var zip = new ZipEntry(entry.getKey()); zip.setTime(1792886400000L);
                out.putNextEntry(zip); out.write(entry.getValue()); out.closeEntry();
            }
        }
    }
}
