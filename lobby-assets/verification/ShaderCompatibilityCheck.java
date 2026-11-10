import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** Offline GLSL -> SPIR-V -> Metal/OpenGL check using the official client's LWJGL libraries.
 * Arguments: lobby pack directory, hint pack directory, official 26.3 client JAR.
 * Run with Java 25, --enable-native-access=ALL-UNNAMED and the client's lwjgl,
 * lwjgl-shaderc, lwjgl-spvc and corresponding platform-native JARs on the classpath.
 * Translation does not prove execution on a physical Mac GPU.
 */
class ShaderCompatibilityCheck {
    static ZipFile client;
    static String expand(Path pack,String name) throws Exception {
        Path path=pack.resolve(name);
        var entry=client.getEntry(name);
        String source=Files.exists(path)?Files.readString(path):null;
        if(source==null) {
            if(entry==null)throw new IllegalArgumentException("Missing shader: "+name);
            try(var input=client.getInputStream(entry)){source=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        }
        var matcher=Pattern.compile("(?m)^\\s*#(?:include|moj_import) <([^:>]+):([^>]+)>\\s*$").matcher(source);
        var out=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(
                expand(pack,"assets/"+matcher.group(1)+"/shaders/include/"+matcher.group(2))
                        .replaceAll("(?m)^#version[^\\n]*","")));
        matcher.appendTail(out);return out.toString();
    }
    static void require(int status,long context) {
        if(status!=SPVC_SUCCESS)throw new AssertionError(spvc_context_get_last_error_string(context));
    }
    static void translate(ByteBuffer bytes,int backend) {
        try(var stack=MemoryStack.stackPush()) {
            var pointer=stack.mallocPointer(1);
            if(spvc_context_create(pointer)!=SPVC_SUCCESS)throw new AssertionError("SPIRV-Cross initialization failed");
            long context=pointer.get(0);
            try {
                require(spvc_context_parse_spirv(context,bytes.asIntBuffer(),bytes.remaining()/4,pointer),context);
                long ir=pointer.get(0);
                require(spvc_context_create_compiler(context,backend,ir,SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,pointer),context);
                long compiler=pointer.get(0);
                require(spvc_compiler_create_compiler_options(compiler,pointer),context);long options=pointer.get(0);
                require(spvc_compiler_options_set_uint(options,backend==SPVC_BACKEND_MSL?
                        SPVC_COMPILER_OPTION_MSL_VERSION:SPVC_COMPILER_OPTION_GLSL_VERSION,backend==SPVC_BACKEND_MSL?20300:330),context);
                require(spvc_compiler_install_compiler_options(compiler,options),context);
                require(spvc_compiler_compile(compiler,pointer),context);
            }finally{spvc_context_destroy(context);}
        }
    }
    static void check(Path pack,String shader,String label,String defines) throws Exception {
        String source=expand(pack,"assets/minecraft/shaders/core/"+shader)
                .replaceFirst("#version \\d+","#version 450\n"+defines);
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize(),result=0;
        try {
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(options,true);
            shaderc_compile_options_set_auto_map_locations(options,true);
            result=shaderc_compile_into_spv(compiler,source,shader.endsWith(".vsh")?shaderc_vertex_shader:shaderc_fragment_shader,label,"main",options);
            if(shaderc_result_get_compilation_status(result)!=0)throw new AssertionError(label+": "+shaderc_result_get_error_message(result));
            ByteBuffer bytes=shaderc_result_get_bytes(result).order(ByteOrder.nativeOrder());
            translate(bytes,SPVC_BACKEND_MSL);translate(bytes,SPVC_BACKEND_GLSL);
            System.out.println(label+" "+shader+": SPIR-V, Metal and OpenGL PASS");
        }finally {
            if(result!=0)shaderc_result_release(result);
            shaderc_compile_options_release(options);shaderc_compiler_release(compiler);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Expected lobby pack, hint pack, client JAR");
        Path lobby=Path.of(args[0]),hint=Path.of(args[1]);
        try(var paths=Files.walk(lobby)) {
            if(paths.anyMatch(p->Files.isRegularFile(p)&&p.toString().replace('\\','/').contains("/shaders/")))
                throw new AssertionError("Lobby pack must inherit vanilla shaders");
        }
        try(ZipFile archive=new ZipFile(args[2])) {
            client=archive;
            String[] defines={"","#define EMISSIVE\n#define NO_OVERLAY\n#define NO_CARDINAL_LIGHTING\n",
                "#define PER_FACE_LIGHTING\n","#define GLINT\n",
                "#define OIT\n#define OIT_COEFF_COUNT 8\n#define OIT_WAVELET_RANK 2\n#define OIT_ALPHA_ONLY\n#define OIT_DEPTH_BOUNDS\n",
                "#define OIT\n#define OIT_COEFF_COUNT 8\n#define OIT_WAVELET_RANK 2\n#define OIT_ACCUMULATE\n#define ALPHA_CUTOUT 0.1\n"};
            for(int i=0;i<defines.length;i++)for(String suffix:new String[]{".vsh",".fsh"})
                check(lobby,"entity"+suffix,"lobby-entity-variant-"+i,defines[i]);
            for(String suffix:new String[]{".vsh",".fsh"}) {
                check(hint,"text"+suffix,"hint-world","");
                check(hint,"text"+suffix,"hint-gui","#define IS_GUI\n");
            }
        }
    }
}
