package artifacts.client.item.mesh;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;

import java.util.Set;

public final class BodyMeshDefinitions {

    private BodyMeshDefinitions() { }

    public static MeshDefinition createNecklace(CubeListBuilder body) {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0);

        mesh.getRoot().addOrReplaceChild(
                "body",
                body.texOffs(0, 0)
                        .addBox(-(2 * 8) / 2F, -1 / 2F, -(2 * 4 + 1) / 2F, 2 * 8, 2 * 12 + 1, 2 * 4 + 1),
                PartPose.ZERO.withScale(0.51F)
        );

        mesh.getRoot().retainExactParts(Set.of("body"));

        return mesh;
    }

    public static MeshDefinition createCenteredNecklace(CubeListBuilder body) {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0);

        mesh.getRoot().addOrReplaceChild(
                "body",
                body.texOffs(0, 0)
                        .addBox(-(2 * 8 + 1) / 2F, -1 / 2F, -(2 * 4 + 1) / 2F, 2 * 8 + 1, 2 * 12 + 1, 2 * 4 + 1),
                PartPose.ZERO.withScale(0.51F)
        );

        mesh.getRoot().retainExactParts(Set.of("body"));

        return mesh;
    }

    public static MeshDefinition createCharmOfSinking() {
        CubeListBuilder body = CubeListBuilder.create();

        body.texOffs(50, 0);
        body.addBox(-1, 3.5F, -5, 2, 4, 1);

        return createNecklace(body);
    }

    public static MeshDefinition createCrossNecklace() {
        CubeListBuilder body = CubeListBuilder.create();

        // cross vertical
        body.texOffs(52, 0);
        body.addBox(-0.5F, 4.5F, -5, 1, 4, 1);

        // cross horizontal
        body.texOffs(56, 0);
        body.addBox(-1.5F, 5.5F, -5, 3, 1, 1);

        return createCenteredNecklace(body);
    }

    public static MeshDefinition createPanicNecklace() {
        CubeListBuilder body = CubeListBuilder.create();

        // gem top
        body.texOffs(52, 0);
        body.addBox(-2.5F, 5.5F, -5, 2, 2, 1);
        body.texOffs(58, 0);
        body.addBox(0.5F, 5.5F, -5, 2, 2, 1);

        // gem middle
        body.texOffs(52, 3);
        body.addBox(-1.5F, 6.5F, -5, 3, 2, 1);

        // gem bottom
        body.texOffs(60, 4);
        body.addBox(-0.5F, 8.5F, -5, 1, 1, 1);

        return createCenteredNecklace(body);
    }

    public static MeshDefinition createCharmOfShrinking() {
        CubeListBuilder body = CubeListBuilder.create();

        body.texOffs(52, 0);
        body.addBox(-3F / 2, 4.5F, -5, 3, 2, 1);

        body.texOffs(52, 3);
        body.addBox(-1F / 2, 6.5F, -5, 1, 1, 1);

        return createCenteredNecklace(body);
    }

    public static MeshDefinition createPendant() {
        CubeListBuilder body = CubeListBuilder.create();

        // gem
        body.texOffs(50, 0);
        body.addBox(-1, 4.5F, -5, 2, 2, 1);

        return createNecklace(body);
    }

    public static MeshDefinition createScarf() {
        MeshDefinition mesh = HumanoidModel.createMesh(new CubeDeformation(0.51F), 0);

        mesh.getRoot().addOrReplaceChild(
                "body",
                CubeListBuilder.create()
                        .texOffs(0, 16)
                        .addBox(-6.01F, -2, -4, 12, 6, 8),
                PartPose.ZERO
        );

        mesh.getRoot().getChild("body").addOrReplaceChild(
                "cloak",
                CubeListBuilder.create()
                        .texOffs(32, 0)
                        .addBox(-5, 0, 0, 5, 12, 2),
                PartPose.offset(0, 0, 1.99F)
        );

        mesh.getRoot().getChild("head").clearRecursively();
        mesh.getRoot().retainPartsAndChildren(Set.of("body", "head"));

        return mesh;
    }
}
