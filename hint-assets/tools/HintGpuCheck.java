package dev.noqui.paper;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;

/** Real pack shaders, GPU vertex capture and glyph rasterization; no Minecraft world is launched. */
public final class HintGpuCheck {
    record Glyph(int texture, float u, float v, float du, float dv, float width, int height, int marker, int advance) {}
    final Map<String, Map<Integer, Glyph>> fonts = new HashMap<>();
    final Map<String, Integer> textures = new HashMap<>();
    final Map<String, Integer> uniformBuffers = new HashMap<>();
    final ZipFile client, pack;
    int program, vao, vertices, feedback, framebuffer, colorTexture; float guiWidth, guiHeight, cursor;
    int captured; double factor;
    HintGpuCheck(ZipFile client, ZipFile pack) { this.client = client; this.pack = pack; }
    static byte[] bytes(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name); if (entry == null) throw new AssertionError("Missing " + name);
        try (var in = zip.getInputStream(entry)) { return in.readAllBytes(); }
    }
    static String expand(ZipFile client, String source) throws Exception {
        var matcher = Pattern.compile("(?m)^\\s*#(?:include|moj_import) <([^:>]+):([^>]+)>\\s*$").matcher(source);
        var out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(expand(client,
            new String(bytes(client, "assets/" + matcher.group(1) + "/shaders/include/" + matcher.group(2))))
            .replaceAll("(?m)^#version[^\\n]*", "")));
        matcher.appendTail(out); return out.toString();
    }
    static int shader(int kind, String source) {
        int id = glCreateShader(kind); glShaderSource(id, source); glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(id));
        return id;
    }
    void initialize(boolean modern) throws Exception {
        String base = "assets/minecraft/shaders/core/" + (modern ? "text" : "rendertype_text_see_through");
        String v = expand(client, new String(bytes(pack, base + ".vsh"))), f = expand(client, new String(bytes(client, base + ".fsh")));
        if (modern) { v = v.replace("#version 330", "#version 330\n#define IS_GUI"); f = f.replace("#version 330", "#version 330\n#define IS_GUI"); }
        program = glCreateProgram(); int vs = shader(GL_VERTEX_SHADER, v), fs = shader(GL_FRAGMENT_SHADER, f);
        glAttachShader(program, vs); glAttachShader(program, fs);
        glTransformFeedbackVaryings(program, new String[]{"gl_Position"}, GL_INTERLEAVED_ATTRIBS); glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
        glDeleteShader(vs); glDeleteShader(fs); glUseProgram(program);
        vao = glGenVertexArrays(); glBindVertexArray(vao); vertices = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, vertices);
        for (var attribute : Map.of("Position", new int[]{3,0}, "Color", new int[]{4,3}, "UV0", new int[]{2,7}).entrySet()) {
            int location = glGetAttribLocation(program, attribute.getKey()); glEnableVertexAttribArray(location);
            glVertexAttribPointer(location, attribute.getValue()[0], GL_FLOAT, false, 9 * 4, attribute.getValue()[1] * 4L);
        }
        feedback = glGenBuffers(); glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, feedback); glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER, 64, GL_DYNAMIC_READ);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, feedback); glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDisable(GL_DEPTH_TEST);
    }
    void block(String name, int binding, Map<String, float[]> values) {
        int index = glGetUniformBlockIndex(program, name); if (index == GL_INVALID_INDEX) throw new AssertionError("Unbound " + name);
        var data = org.lwjgl.BufferUtils.createByteBuffer(glGetActiveUniformBlocki(program, index, GL_UNIFORM_BLOCK_DATA_SIZE));
        for (var value : values.entrySet()) {
            int uniform = glGetUniformIndices(program, value.getKey());
            if (uniform == GL_INVALID_INDEX) uniform = glGetUniformIndices(program, name + "." + value.getKey());
            if (uniform == GL_INVALID_INDEX) throw new AssertionError("Missing uniform " + value.getKey());
            int offset = glGetActiveUniformsi(program, uniform, GL_UNIFORM_OFFSET);
            for (int i = 0; i < value.getValue().length; i++) data.putFloat(offset + i * 4, value.getValue()[i]);
        }
        int buffer = uniformBuffers.computeIfAbsent(name, unused -> glGenBuffers()); glBindBuffer(GL_UNIFORM_BUFFER, buffer); glBufferData(GL_UNIFORM_BUFFER, data, GL_DYNAMIC_DRAW);
        glUniformBlockBinding(program, index, binding); glBindBufferBase(GL_UNIFORM_BUFFER, binding, buffer);
    }
    int texture(String path, ZipFile input) throws Exception {
        Integer cached = textures.get(path); if (cached != null) return cached;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes(input, path)));
        var data = org.lwjgl.BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int p = image.getRGB(x,y); data.put((byte)(p>>16)).put((byte)(p>>8)).put((byte)p).put((byte)(p>>24));
        }
        data.flip(); int id = glGenTextures(); glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.getWidth(), image.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        textures.put(path,id); return id;
    }
    Map<Integer,Glyph> font(String key) throws Exception {
        if (fonts.containsKey(key)) return fonts.get(key);
        var out = new HashMap<Integer,Glyph>(); String path = "assets/noqui_hint/font/" + key.substring(key.indexOf(':') + 1) + ".json";
        var providers = JsonParser.parseString(new String(bytes(pack, path))).getAsJsonObject().getAsJsonArray("providers");
        for (var value : providers) {
            var p = value.getAsJsonObject(); if (!p.get("type").getAsString().equals("bitmap")) continue;
            String file = p.get("file").getAsString().replace("noqui_hint:", "assets/noqui_hint/textures/");
            var image = ImageIO.read(new ByteArrayInputStream(bytes(pack,file))); var chars = p.getAsJsonArray("chars");
            int columns = chars.get(0).getAsString().codePointCount(0, chars.get(0).getAsString().length());
            int cw = image.getWidth() / columns, ch = image.getHeight() / chars.size(), height = p.get("height").getAsInt();
            for (int row = 0; row < chars.size(); row++) {
                int[] codes = chars.get(row).getAsString().codePoints().toArray();
                for (int col = 0; col < codes.length; col++) {
                    if (codes[col] == 0) continue; int right = 0;
                    for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) if ((image.getRGB(col*cw+x,row*ch+y)>>>24) != 0) right = Math.max(right,x+1);
                    out.putIfAbsent(codes[col], new Glyph(texture(file,pack), (float)col/columns, (float)row/chars.size(), 1f/columns, 1f/chars.size(), cw * (float)height/ch,
                        height, (-p.get("ascent").getAsInt() >> 13) - 1024, (int)(.5f+right*(float)height/ch)+1));
                }
            }
        }
        fonts.put(key,out); return out;
    }
    void quad(Glyph glyph, float x, float y, int rgb, boolean capture) {
        float[] data = new float[36];
        for (int vertex = 0; vertex < 4; vertex++) {
            boolean right = vertex >= 2, bottom = vertex == 1 || vertex == 2; int o = vertex * 9;
            data[o] = x + (right ? glyph.width : 0); data[o+1] = y + (bottom ? glyph.height : 0);
            data[o+3] = ((rgb>>16)&255)/255f; data[o+4] = ((rgb>>8)&255)/255f; data[o+5]=(rgb&255)/255f; data[o+6]=1;
            data[o+7] = glyph.u + (right ? glyph.du : 0); data[o+8] = glyph.v + (bottom ? glyph.dv : 0);
        }
        glBindBuffer(GL_ARRAY_BUFFER,vertices); glBufferData(GL_ARRAY_BUFFER,data,GL_STREAM_DRAW); glBindTexture(GL_TEXTURE_2D,glyph.texture);
        if (capture) {
            glEnable(GL_RASTERIZER_DISCARD); glBeginTransformFeedback(GL_POINTS); glDrawArrays(GL_POINTS,0,4); glEndTransformFeedback(); glDisable(GL_RASTERIZER_DISCARD);
            float[] actual = new float[16]; glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER,feedback); glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER,0,actual);
            for (int vertex = 0; vertex < 4; vertex++) {
                double expectedX = guiWidth*.5 + (x - guiWidth*.5 + (vertex >= 2 ? glyph.width : 0)) * factor;
                double expectedY = guiHeight - 78 - (glyph.marker - 300) * 14 * factor - (vertex == 1 || vertex == 2 ? 0 : glyph.height * factor);
                double ax = (actual[vertex*4] + 1) * guiWidth / 2, ay = (1 - actual[vertex*4+1]) * guiHeight / 2;
                if (Math.abs(ax-expectedX) > .02 || Math.abs(ay-expectedY) > .02) throw new AssertionError("GPU placement differs: "+ax+","+ay+" expected "+expectedX+","+expectedY);
                if (ax < 7 || ax > guiWidth - 7 || ay < 0 || ay > guiHeight - 77.9) throw new AssertionError("Hint overlaps game HUD or window bounds");
                captured++;
            }
        }
        glDrawArrays(GL_TRIANGLE_FAN,0,4);
    }
    void component(Component value, Key inherited, int rgb) throws Exception {
        Key key = value.font() == null ? inherited : value.font(); if (value.color() != null) rgb = value.color().value();
        if (value instanceof TextComponent text) for (int code : text.content().codePoints().toArray()) {
            if (code == 32) { cursor += 4; continue; }
            if (code >= 0xEC00 && code <= 0xF400) { cursor += code - 0xF000; continue; }
            var glyph = font(key.asString()).get(code); if (glyph == null) throw new AssertionError("Absent glyph " + code);
            quad(glyph, guiWidth/2 + cursor, (((1024 + glyph.marker) << 13) + 4095) + 30, rgb, true); cursor += glyph.advance;
        }
        for (Component child : value.children()) component(child,key,rgb);
    }
    void vanilla(String name, float x, float y, int width, int height) throws Exception {
        int id = texture("assets/minecraft/textures/gui/sprites/hud/" + name + ".png",client);
        quad(new Glyph(id,0,0,1,1,width,height,0,0),x,y,0xFFFFFF,false);
    }
    void render(Path output, int width, int height, int scale, String hint) throws Exception {
        if (framebuffer == 0) { framebuffer = glGenFramebuffers(); colorTexture = glGenTextures(); }
        glBindTexture(GL_TEXTURE_2D, colorTexture); glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
        glBindFramebuffer(GL_FRAMEBUFFER,framebuffer); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colorTexture,0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Offscreen framebuffer unavailable");
        guiWidth = (float)Math.ceil(width/(double)scale); guiHeight = (float)Math.ceil(height/(double)scale); cursor=0;
        factor = Math.min(1,Math.max(0,Math.min((guiWidth-32)/360.0,(guiHeight-108)/84.0)));
        block("Projection",0,Map.of("ProjMat",new float[]{2/guiWidth,0,0,0, 0,-2/guiHeight,0,0, 0,0,1,0, -1,1,0,1}));
        block("DynamicTransforms",1,Map.of("ModelViewMat",new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},"ColorModulator",new float[]{1,1,1,1}));
        glViewport(0,0,width,height); glClearColor(.09f,.18f,.22f,1); glClear(GL_COLOR_BUFFER_BIT);
        component(HintGlyphs.compose(hint),Key.key("noqui_hint:row_0"),0xFFFFFF);
        if(Math.abs(cursor)>.001) throw new AssertionError("Native font advances drifted by "+cursor);
        vanilla("hotbar",guiWidth/2-91,guiHeight-22,182,22); vanilla("experience_bar_background",guiWidth/2-91,guiHeight-29,182,5);
        for(int i=0;i<10;i++){vanilla("heart/full",guiWidth/2-91+i*8,guiHeight-39,9,9);vanilla("food_full",guiWidth/2+82-i*8,guiHeight-39,9,9);vanilla("armor_full",guiWidth/2-91+i*8,guiHeight-49,9,9);}
        var pixels = org.lwjgl.BufferUtils.createByteBuffer(width*height*4); glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        var image = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int o=(y*width+x)*4;image.setRGB(x,height-1-y,(pixels.get(o)&255)<<16|(pixels.get(o+1)&255)<<8|pixels.get(o+2)&255);}
        ImageIO.write(image,"png",output.toFile());
    }
    public static void main(String[] args) throws Exception {
        if(!glfwInit())throw new AssertionError("GLFW unavailable");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        long window=glfwCreateWindow(1920,1080,"HUD offscreen check",0,0);if(window==0)throw new AssertionError("Hidden context unavailable");
        glfwMakeContextCurrent(window);org.lwjgl.opengl.GL.createCapabilities();
        Path output=Files.createDirectories(Path.of(args[2])); JsonObject report=new JsonObject();
        try(ZipFile client=new ZipFile(args[0]);ZipFile pack=new ZipFile(args[1])){
            var checker=new HintGpuCheck(client,pack);checker.initialize(true); int cases=0;
            int[][] windows={{1920,1080},{1920,1006},{2560,1440},{3840,2160},{640,480}};
            for(int[] size:windows)for(int scale=1;scale<=4;scale++) {
                if(Math.ceil(size[0]/(double)scale)<320 || Math.ceil(size[1]/(double)scale)<240)continue;
                for(boolean longHint:new boolean[]{false,true}) {
                    checker.render(output.resolve("w"+size[0]+"h"+size[1]+"-gui-"+scale+"-"+(longHint?"long":"short")+".png"),size[0],size[1],scale,longHint?"힣".repeat(160):"ㄴㄹㅇㄱㄴ ㅅㄹㅎ MySong123");cases++;
                }
            }
            report.addProperty("status","PASS");report.addProperty("cases",cases);report.addProperty("gpuCapturedHintVertices",checker.captured);
            report.addProperty("packSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[1])))));
            report.addProperty("minecraftWorldRendered",false);report.addProperty("openGL",glGetString(GL_VERSION));report.addProperty("gpu",glGetString(GL_RENDERER));
            Files.writeString(output.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println(report);
        }finally{glfwDestroyWindow(window);glfwTerminate();}
    }
}
