import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
public class NativeSkinMesh {
  static final List<Object> faces=new ArrayList<>();
  @SuppressWarnings("unchecked") static void walk(String name, ModelPart part,float x,float y,float z)throws Exception {
    x+=part.x;y+=part.y;z+=part.z;
    if(part.xRot!=0||part.yRot!=0||part.zRot!=0)throw new IllegalStateException("Unexpected rest rotation");
    Field cubes=ModelPart.class.getDeclaredField("cubes");cubes.setAccessible(true);
    for(ModelPart.Cube cube:(List<ModelPart.Cube>)cubes.get(part))for(var polygon:cube.polygons) {
      List<float[]> vertices=new ArrayList<>();
      for(var vertex:polygon.vertices())vertices.add(new float[]{x+vertex.x(),24-y-vertex.y(),-z-vertex.z(),vertex.u(),vertex.v()});
      var normal=polygon.normal();faces.add(Map.of("part",name,"vertices",vertices,"normal",new float[]{normal.x(),-normal.y(),-normal.z()}));
    }
    Field children=ModelPart.class.getDeclaredField("children");children.setAccessible(true);
    for(var entry:new TreeMap<>((Map<String,ModelPart>)children.get(part)).entrySet())walk(name+"/"+entry.getKey(),entry.getValue(),x,y,z);
  }
  public static void main(String[] args)throws Exception {
    walk("root",PlayerModel.createMesh(CubeDeformation.NONE,true).getRoot().bake(64,64),0,0,0);
    Files.writeString(Path.of(args[0]),new Gson().toJson(Map.of("minecraftVersion","26.3","clientSha1","e877b6a07acd633fb3bb475002175cec036e7b87","model","slim","faces",faces)));
    System.out.println("Native slim mesh faces: "+faces.size());
  }
}
