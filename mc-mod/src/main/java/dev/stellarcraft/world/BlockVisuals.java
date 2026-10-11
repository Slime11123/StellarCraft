package dev.stellarcraft.world;

import net.minecraft.client.Minecraft;
import dev.stellarcraft.render.NativePixels;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/** Exports the actual resource-pack block model and its face textures for UE's native mesh. */
public final class BlockVisuals {
    private static final Path DIRECTORY = Path.of(
        "D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/block-assets");
    private static final Map<String, BufferedImage> textures = new LinkedHashMap<>();
    private static final Set<Integer> models = new HashSet<>();

    private BlockVisuals() {}

    public static int export(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        try {
            var model = mc.getBlockRenderer().getBlockModel(state);
            List<BakedQuad> quads = new ArrayList<>();
            quads.addAll(model.getQuads(state, null, RandomSource.create(0)));
            for (Direction side : Direction.values())
                quads.addAll(model.getQuads(state, side, RandomSource.create(0)));
            if (quads.isEmpty()) return 0;

            Map<String, List<BakedQuad>> groups = new LinkedHashMap<>();
            for (BakedQuad quad : quads) {
                int tint = quad.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, quad.getTintIndex()) : 0xFFFFFF;
                String key = quad.getSprite().contents().name() + "#" + Integer.toHexString(tint & 0xFFFFFF);
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(quad);
            }

            StringBuilder mesh = new StringBuilder("SCBLOCK1\n").append(groups.size()).append('\n');
            Files.createDirectories(DIRECTORY);
            for (var group : groups.entrySet()) {
                BakedQuad first = group.getValue().get(0);
                var sprite = first.getSprite();
                int tint = first.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, first.getTintIndex()) : 0xFFFFFF;
                int textureId = checksum(group.getKey());
                String filename = Integer.toUnsignedString(textureId) + ".png";
                if (!textures.containsKey(group.getKey())) {
                    ResourceLocation name = sprite.contents().name();
                    ResourceLocation resource = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + ".png");
                    try (var input = mc.getResourceManager().getResourceOrThrow(resource).open()) {
                        BufferedImage source = ImageIO.read(input);
                        int w = sprite.contents().width(), h = sprite.contents().height();
                        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                            int rgba = source.getRGB(x, y);
                            int r = ((rgba >>> 16) & 255) * ((tint >>> 16) & 255) / 255;
                            int g = ((rgba >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
                            int b = (rgba & 255) * (tint & 255) / 255;
                            image.setRGB(x, y, (rgba & 0xFF000000) | (r << 16) | (g << 8) | b);
                        }
                        Path temporary = DIRECTORY.resolve(filename + ".tmp");
                        ImageIO.write(image, "png", temporary.toFile());
                        Files.move(temporary, DIRECTORY.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                        textures.put(group.getKey(), image);
                    } catch (Exception error) {
                        textures.remove(group.getKey());
                        throw error;
                    }
                }
                List<float[][]> pixels = new ArrayList<>();
                for (BakedQuad quad : group.getValue()) {
                    int[] vertices = quad.getVertices();
                    int stride = vertices.length / 4;
                    float[][] face = new float[4][8];
                    Direction normal = quad.getDirection();
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int offset = vertex * stride;
                        float x = Float.intBitsToFloat(vertices[offset]);
                        float y = Float.intBitsToFloat(vertices[offset + 1]);
                        float z = Float.intBitsToFloat(vertices[offset + 2]);
                        face[vertex] = new float[]{-(x-.5f)*100, -(z-.5f)*100, (y-.5f)*100,
                            (Float.intBitsToFloat(vertices[offset+4])-sprite.getU0())/(sprite.getU1()-sprite.getU0()),
                            (Float.intBitsToFloat(vertices[offset+5])-sprite.getV0())/(sprite.getV1()-sprite.getV0()),
                            -normal.getStepX(), -normal.getStepZ(), normal.getStepY()};
                    }
                    NativePixels.split(face, textures.get(group.getKey()), pixels::add);
                }
                mesh.append(filename).append('\n').append(pixels.size()).append('\n');
                for (float[][] face : pixels) {
                    for (float[] vertex : face)
                        for (int i=0; i<5; i++) mesh.append(vertex[i]).append(' ');
                    mesh.append(face[0][5]).append(' ').append(face[0][6]).append(' ').append(face[0][7]).append('\n');
                }
            }
            String data = mesh.toString();
            int id = checksum(data);
            if (!models.contains(id)) {
                Path temporary = DIRECTORY.resolve(Integer.toUnsignedString(id) + ".scmesh.tmp");
                Files.writeString(temporary, data, StandardCharsets.UTF_8);
                Files.move(temporary, DIRECTORY.resolve(Integer.toUnsignedString(id) + ".scmesh"), StandardCopyOption.REPLACE_EXISTING);
                models.add(id);
                System.out.println("[StellarCraft-Blocks] native model " + Integer.toUnsignedString(id)
                    + " quads=" + quads.size() + " materials=" + groups.size());
            }
            return id;
        } catch (Exception error) {
            System.out.println("[StellarCraft-Blocks] model export failed: " + error);
            return 0;
        }
    }

    private static int checksum(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        int id = (int) crc.getValue();
        return id == 0 ? 1 : id;
    }
}
