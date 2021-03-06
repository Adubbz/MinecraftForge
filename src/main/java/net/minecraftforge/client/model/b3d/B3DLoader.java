package net.minecraftforge.client.model.b3d;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.IModelCustomData;
import net.minecraftforge.client.model.IModelPart;
import net.minecraftforge.client.model.IModelSimpleProperties;
import net.minecraftforge.client.model.IModelState;
import net.minecraftforge.client.model.IPerspectiveAwareModel;
import net.minecraftforge.client.model.IRetexturableModel;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.client.model.ModelStateComposition;
import net.minecraftforge.client.model.TRSRTransformation;
import net.minecraftforge.client.model.animation.IAnimatedModel;
import net.minecraftforge.client.model.animation.IClip;
import net.minecraftforge.client.model.animation.IJoint;
import net.minecraftforge.client.model.b3d.B3DModel.Animation;
import net.minecraftforge.client.model.b3d.B3DModel.Face;
import net.minecraftforge.client.model.b3d.B3DModel.Key;
import net.minecraftforge.client.model.b3d.B3DModel.Mesh;
import net.minecraftforge.client.model.b3d.B3DModel.Node;
import net.minecraftforge.client.model.b3d.B3DModel.Texture;
import net.minecraftforge.client.model.b3d.B3DModel.Vertex;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.client.model.pipeline.UnpackedBakedQuad;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraftforge.common.property.Properties;
import net.minecraftforge.fml.common.FMLLog;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/*
 * Loader for Blitz3D models.
 * To enable for your mod call instance.addDomain(modid).
 * If you need more control over accepted resources - extend the class, and register a new instance with ModelLoaderRegistry.
 */
public final class B3DLoader implements ICustomModelLoader
{
    public static final B3DLoader instance = new B3DLoader();

    private IResourceManager manager;

    private final Set<String> enabledDomains = new HashSet<String>();
    private final Map<ResourceLocation, B3DModel> cache = new HashMap<ResourceLocation, B3DModel>();

    public void addDomain(String domain)
    {
        enabledDomains.add(domain.toLowerCase());
    }

    public void onResourceManagerReload(IResourceManager manager)
    {
        this.manager = manager;
        cache.clear();
    }

    public boolean accepts(ResourceLocation modelLocation)
    {
        return enabledDomains.contains(modelLocation.getResourceDomain()) && modelLocation.getResourcePath().endsWith(".b3d");
    }

    @SuppressWarnings("unchecked")
    public IModel loadModel(ResourceLocation modelLocation) throws Exception
    {
        ResourceLocation file = new ResourceLocation(modelLocation.getResourceDomain(), modelLocation.getResourcePath());
        if(!cache.containsKey(file))
        {
            try
            {
                IResource resource = null;
                try
                {
                    resource = manager.getResource(file);
                }
                catch(FileNotFoundException e)
                {
                    if(modelLocation.getResourcePath().startsWith("models/block/"))
                        resource = manager.getResource(new ResourceLocation(file.getResourceDomain(), "models/item/" + file.getResourcePath().substring("models/block/".length())));
                    else if(modelLocation.getResourcePath().startsWith("models/item/"))
                        resource = manager.getResource(new ResourceLocation(file.getResourceDomain(), "models/block/" + file.getResourcePath().substring("models/item/".length())));
                    else throw e;
                }
                B3DModel.Parser parser = new B3DModel.Parser(resource.getInputStream());
                B3DModel model = parser.parse();
                cache.put(file, model);
            }
            catch(IOException e)
            {
                cache.put(file, null);
                throw e;
            }
        }
        B3DModel model = cache.get(file);
        if(model == null) throw new ModelLoaderRegistry.LoaderException("Error loading model previously: " + file);
        if(!(model.getRoot().getKind() instanceof Mesh))
        {
            return new ModelWrapper(modelLocation, model, ImmutableSet.<String>of(), true, true, 1);
        }
        return new ModelWrapper(modelLocation, model, ImmutableSet.of(((Node<Mesh>)model.getRoot()).getName()), true, true, 1);
    }

    public static final class B3DState implements IModelState
    {
        private final Animation animation;
        private final int frame;
        private final int nextFrame;
        private final float progress;
        private final IModelState parent;

        public B3DState(Animation animation, int frame)
        {
            this(animation, frame, frame, 0);
        }

        public B3DState(Animation animation, int frame, IModelState parent)
        {
            this(animation, frame, frame, 0, parent);
        }

        public B3DState(Animation animation, int frame, int nextFrame, float progress)
        {
            this(animation, frame, nextFrame, progress, null);
        }

        public B3DState(Animation animation, int frame, int nextFrame, float progress, IModelState parent)
        {
            this.animation = animation;
            this.frame = frame;
            this.nextFrame = nextFrame;
            this.progress = MathHelper.clamp_float(progress, 0, 1);
            this.parent = getParent(parent);
        }

        private IModelState getParent(IModelState parent)
        {
            if (parent == null) return null;
            else if (parent instanceof B3DState) return ((B3DState)parent).parent;
            return parent;
        }

        public Animation getAnimation()
        {
            return animation;
        }

        public int getFrame()
        {
            return frame;
        }

        public int getNextFrame()
        {
            return nextFrame;
        }

        public float getProgress()
        {
            return progress;
        }

        public IModelState getParent()
        {
            return parent;
        }

        public Optional<TRSRTransformation> apply(Optional<? extends IModelPart> part)
        {
            // TODO optionify better
            if(!part.isPresent()) return parent.apply(part);
            if(!(part.get() instanceof NodeJoint))
            {
                return Optional.absent();
            }
            Node<?> node = ((NodeJoint)part.get()).getNode();
            TRSRTransformation nodeTransform;
            if(progress < 1e-5 || frame == nextFrame)
            {
                nodeTransform = getNodeMatrix(node, frame);
            }
            else if(progress > 1 - 1e-5)
            {
                nodeTransform = getNodeMatrix(node, nextFrame);
            }
            else
            {
                nodeTransform = getNodeMatrix(node, frame);
                nodeTransform = nodeTransform.slerp(getNodeMatrix(node, nextFrame), progress);
            }
            if(parent != null && node.getParent() == null)
            {
                return Optional.of(parent.apply(part).or(TRSRTransformation.identity()).compose(nodeTransform));
            }
            return Optional.of(nodeTransform);
        }

        private static LoadingCache<Triple<Animation, Node<?>, Integer>, TRSRTransformation> cache = CacheBuilder.newBuilder()
            .maximumSize(16384)
            .expireAfterAccess(2, TimeUnit.MINUTES)
            .build(new CacheLoader<Triple<Animation, Node<?>, Integer>, TRSRTransformation>()
            {
                public TRSRTransformation load(Triple<Animation, Node<?>, Integer> key) throws Exception
                {
                    return getNodeMatrix(key.getLeft(), key.getMiddle(), key.getRight());
                }
            });

        public TRSRTransformation getNodeMatrix(Node<?> node)
        {
            return getNodeMatrix(node, frame);
        }

        public TRSRTransformation getNodeMatrix(Node<?> node, int frame)
        {
            return cache.getUnchecked(Triple.<Animation, Node<?>, Integer>of(animation, node, frame));
        }

        public static TRSRTransformation getNodeMatrix(Animation animation, Node<?> node, int frame)
        {
            TRSRTransformation ret = TRSRTransformation.identity();
            Key key = null;
            if(animation != null) key = animation.getKeys().get(frame, node);
            else if(node.getAnimation() != null && node.getAnimation() != animation) key = node.getAnimation().getKeys().get(frame, node);
            if(key != null)
            {
                Node<?> parent = node.getParent();
                if(parent != null)
                {
                    // parent model-global current pose
                    TRSRTransformation pm = cache.getUnchecked(Triple.<Animation, Node<?>, Integer>of(animation, node.getParent(), frame));
                    ret = ret.compose(pm);
                    // joint offset in the parent coords
                    ret = ret.compose(new TRSRTransformation(parent.getPos(), parent.getRot(), parent.getScale(), null));
                }
                // current node local pose
                ret = ret.compose(new TRSRTransformation(key.getPos(), key.getRot(), key.getScale(), null));
                // this part moved inside the model
                // inverse bind of the curent node
                /*Matrix4f rm = new TRSRTransformation(node.getPos(), node.getRot(), node.getScale(), null).getMatrix();
                rm.invert();
                ret = ret.compose(new TRSRTransformation(rm));
                if(parent != null)
                {
                    // inverse bind of the parent
                    rm = new TRSRTransformation(parent.getPos(), parent.getRot(), parent.getScale(), null).getMatrix();
                    rm.invert();
                    ret = ret.compose(new TRSRTransformation(rm));
                }*/
                // TODO cache
                TRSRTransformation invBind = new NodeJoint(node).getInvBindPose();
                ret = ret.compose(invBind);
            }
            else
            {
                Node<?> parent = node.getParent();
                if(parent != null)
                {
                    // parent model-global current pose
                    TRSRTransformation pm = cache.getUnchecked(Triple.<Animation, Node<?>, Integer>of(animation, node.getParent(), frame));
                    ret = ret.compose(pm);
                    // joint offset in the parent coords
                    ret = ret.compose(new TRSRTransformation(parent.getPos(), parent.getRot(), parent.getScale(), null));
                }
                ret = ret.compose(new TRSRTransformation(node.getPos(), node.getRot(), node.getScale(), null));
                // TODO cache
                TRSRTransformation invBind = new NodeJoint(node).getInvBindPose();
                ret = ret.compose(invBind);
            }
            return ret;
        }
    }

    static final class NodeJoint implements IJoint
    {
        private final Node<?> node;

        public NodeJoint(Node<?> node)
        {
            this.node = node;
        }

        public TRSRTransformation getInvBindPose()
        {
            Matrix4f m = new TRSRTransformation(node.getPos(), node.getRot(), node.getScale(), null).getMatrix();
            m.invert();
            TRSRTransformation pose = new TRSRTransformation(m);

            if(node.getParent() != null)
            {
                TRSRTransformation parent = new NodeJoint(node.getParent()).getInvBindPose();
                pose = pose.compose(parent);
            }
            return pose;
        }

        public Optional<NodeJoint> getParent()
        {
            // FIXME cache?
            if(node.getParent() == null) return Optional.absent();
            return Optional.of(new NodeJoint(node.getParent()));
        }

        public Node<?> getNode()
        {
            return node;
        }

        @Override
        public int hashCode()
        {
            return node.hashCode();
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) return true;
            if (!super.equals(obj)) return false;
            if (getClass() != obj.getClass()) return false;
            NodeJoint other = (NodeJoint) obj;
            return Objects.equal(node, other.node);
        }
    }

    public static enum B3DFrameProperty implements IUnlistedProperty<B3DState>
    {
        instance;
        public String getName()
        {
            return "B3DFrame";
        }

        public boolean isValid(B3DState value)
        {
            return value instanceof B3DState;
        }

        public Class<B3DState> getType()
        {
            return B3DState.class;
        }

        public String valueToString(B3DState value)
        {
            return value.toString();
        }
    }

    private static final class ModelWrapper implements IRetexturableModel, IModelCustomData, IModelSimpleProperties, IAnimatedModel
    {
        private final ResourceLocation modelLocation;
        private final B3DModel model;
        private final ImmutableSet<String> meshes;
        private final ImmutableMap<String, ResourceLocation> textures;
        private final boolean smooth;
        private final boolean gui3d;
        private final int defaultKey;

        public ModelWrapper(ResourceLocation modelLocation, B3DModel model, ImmutableSet<String> meshes, boolean smooth, boolean gui3d, int defaultKey)
        {
            this(modelLocation, model, meshes, smooth, gui3d, defaultKey, buildTextures(model.getTextures()));
        }

        public ModelWrapper(ResourceLocation modelLocation, B3DModel model, ImmutableSet<String> meshes, boolean smooth, boolean gui3d, int defaultKey, ImmutableMap<String, ResourceLocation> textures)
        {
            this.modelLocation = modelLocation;
            this.model = model;
            this.meshes = meshes;
            this.textures = textures;
            this.smooth = smooth;
            this.gui3d = gui3d;
            this.defaultKey = defaultKey;
        }

        private static ImmutableMap<String, ResourceLocation> buildTextures(List<Texture> textures)
        {
            ImmutableMap.Builder<String, ResourceLocation> builder = ImmutableMap.builder();

            for(Texture t : textures)
            {
                String path = t.getPath();
                String location = getLocation(path);
                if(!location.startsWith("#")) location = "#" + location;
                builder.put(path, new ResourceLocation(location));
            }
            return builder.build();
        }

        private static String getLocation(String path)
        {
            if(path.endsWith(".png")) path = path.substring(0, path.length() - ".png".length());
            return path;
        }

        @Override
        public Collection<ResourceLocation> getDependencies()
        {
            return Collections.emptyList();
        }

        @Override
        public Collection<ResourceLocation> getTextures()
        {
            return Collections2.filter(textures.values(), new Predicate<ResourceLocation>()
            {
                public boolean apply(ResourceLocation loc)
                {
                    return !loc.getResourcePath().startsWith("#");
                }
            });
        }

        @Override
        public IBakedModel bake(IModelState state, VertexFormat format, Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
        {
            ImmutableMap.Builder<String, TextureAtlasSprite> builder = ImmutableMap.builder();
            TextureAtlasSprite missing = bakedTextureGetter.apply(new ResourceLocation("missingno"));
            for(Map.Entry<String, ResourceLocation> e : textures.entrySet())
            {
                if(e.getValue().getResourcePath().startsWith("#"))
                {
                    FMLLog.severe("unresolved texture '%s' for b3d model '%s'", e.getValue().getResourcePath(), modelLocation);
                    builder.put(e.getKey(), missing);
                }
                else
                {
                    builder.put(e.getKey(), bakedTextureGetter.apply(e.getValue()));
                }
            }
            builder.put("missingno", missing);
            return new BakedWrapper(model.getRoot(), state, smooth, gui3d, format, meshes, builder.build());
        }

        @Override
        public ModelWrapper retexture(ImmutableMap<String, String> textures)
        {
            ImmutableMap.Builder<String, ResourceLocation> builder = ImmutableMap.builder();
            for(Map.Entry<String, ResourceLocation> e : this.textures.entrySet())
            {
                String path = e.getKey();
                String loc = getLocation(path);
                if(textures.containsKey(loc))
                {
                    String newLoc = textures.get(loc);
                    if(newLoc == null) newLoc = getLocation(path);
                    builder.put(e.getKey(), new ResourceLocation(newLoc));
                }
                else
                {
                    builder.put(e);
                }
            }
            return new ModelWrapper(modelLocation, model, meshes, smooth, gui3d, defaultKey, builder.build());
        }

        @Override
        public ModelWrapper process(ImmutableMap<String, String> data)
        {
            if(data.containsKey("mesh"))
            {
                JsonElement e = new JsonParser().parse(data.get("mesh"));
                if(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
                {
                    return new ModelWrapper(modelLocation, model, ImmutableSet.of(e.getAsString()), smooth, gui3d, defaultKey, textures);
                }
                else if (e.isJsonArray())
                {
                    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
                    for(JsonElement s : e.getAsJsonArray())
                    {
                        if(s.isJsonPrimitive() && s.getAsJsonPrimitive().isString())
                        {
                            builder.add(s.getAsString());
                        }
                        else
                        {
                            FMLLog.severe("unknown mesh definition '%s' in array for b3d model '%s'", s.toString(), modelLocation);
                            return this;
                        }
                    }
                    return new ModelWrapper(modelLocation, model, builder.build(), smooth, gui3d, defaultKey, textures);
                }
                else
                {
                    FMLLog.severe("unknown mesh definition '%s' for b3d model '%s'", e.toString(), modelLocation);
                    return this;
                }
            }
            if(data.containsKey("key"))
            {
                JsonElement e = new JsonParser().parse(data.get("key"));
                if(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())
                {
                    return new ModelWrapper(modelLocation, model, meshes, smooth, gui3d, e.getAsNumber().intValue(), textures);
                }
                else
                {
                    FMLLog.severe("unknown keyframe definition '%s' for b3d model '%s'", e.toString(), modelLocation);
                    return this;
                }
            }
            return this;
        }

        public Optional<IClip> getClip(String name)
        {
            if(name.equals("main"))
            {
                return Optional.<IClip>of(B3DClip.instance);
            }
            return Optional.absent();
        }

        public IModelState getDefaultState()
        {
            return new B3DState(model.getRoot().getAnimation(), defaultKey, defaultKey, 0);
        }

        @Override
        public ModelWrapper smoothLighting(boolean value)
        {
            if(value == smooth)
            {
                return this;
            }
            return new ModelWrapper(modelLocation, model, meshes, value, gui3d, defaultKey, textures);
        }

        @Override
        public ModelWrapper gui3d(boolean value)
        {
            if(value == gui3d)
            {
                return this;
            }
            return new ModelWrapper(modelLocation, model, meshes, smooth, value, defaultKey, textures);
        }
    }

    private static final class BakedWrapper implements IPerspectiveAwareModel
    {
        private final Node<?> node;
        private final IModelState state;
        private final boolean smooth;
        private final boolean gui3d;
        private final VertexFormat format;
        private final ImmutableSet<String> meshes;
        private final ImmutableMap<String, TextureAtlasSprite> textures;
        private final LoadingCache<Integer, B3DState> cache;

        private ImmutableList<BakedQuad> quads;

        public BakedWrapper(final Node<?> node, final IModelState state, final boolean smooth, final boolean gui3d, final VertexFormat format, final ImmutableSet<String> meshes, final ImmutableMap<String, TextureAtlasSprite> textures)
        {
            this(node, state, smooth, gui3d, format, meshes, textures, CacheBuilder.newBuilder()
                .maximumSize(128)
                .expireAfterAccess(2, TimeUnit.MINUTES)
                .<Integer, B3DState>build(new CacheLoader<Integer, B3DState>()
                {
                    public B3DState load(Integer frame) throws Exception
                    {
                        IModelState parent = state;
                        Animation newAnimation = node.getAnimation();
                        if(parent instanceof B3DState)
                        {
                            B3DState ps = (B3DState)parent;
                            parent = ps.getParent();
                        }
                        return new B3DState(newAnimation, frame, frame, 0, parent);
                    }
                }));
        }

        public BakedWrapper(Node<?> node, IModelState state, boolean smooth, boolean gui3d, VertexFormat format, ImmutableSet<String> meshes, ImmutableMap<String, TextureAtlasSprite> textures, LoadingCache<Integer, B3DState> cache)
        {
            this.node = node;
            this.state = state;
            this.smooth = smooth;
            this.gui3d = gui3d;
            this.format = format;
            this.meshes = meshes;
            this.textures = textures;
            this.cache = cache;
        }

        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand)
        {
            if(side != null) return ImmutableList.of();
            IModelState modelState = this.state;
            if(state instanceof IExtendedBlockState)
            {
                IExtendedBlockState exState = (IExtendedBlockState)state;
                if(exState.getUnlistedNames().contains(B3DFrameProperty.instance))
                {
                    B3DState s = exState.getValue(B3DFrameProperty.instance);
                    if(s != null)
                    {
                        //return getCachedModel(s.getFrame());
                        IModelState parent = this.state;
                        Animation newAnimation = s.getAnimation();
                        if(parent instanceof B3DState)
                        {
                            B3DState ps = (B3DState)parent;
                            parent = ps.getParent();
                        }
                        if(newAnimation == null)
                        {
                            newAnimation = node.getAnimation();
                        }
                        if(s.getFrame() == s.getNextFrame())
                        {
                            modelState = cache.getUnchecked(s.getFrame());
                        }
                        else
                        {
                            modelState = new B3DState(newAnimation, s.getFrame(), s.getNextFrame(), s.getProgress(), parent);
                        }
                    }
                }
                else if(exState.getUnlistedNames().contains(Properties.AnimationProperty))
                {
                    // FIXME: should animation state handle the parent state, or should it remain here?
                    IModelState parent = this.state;
                    if(parent instanceof B3DState)
                    {
                        B3DState ps = (B3DState)parent;
                        parent = ps.getParent();
                    }
                    IModelState newState = exState.getValue(Properties.AnimationProperty);
                    if(newState != null)
                    {
                        modelState = new ModelStateComposition(parent, newState);
                    }
                }
            }
            if(quads == null)
            {
                ImmutableList.Builder<BakedQuad> builder = ImmutableList.builder();
                generateQuads(builder, node, modelState);
                quads = builder.build();
            }
            return quads;
        }

        private void generateQuads(ImmutableList.Builder<BakedQuad> builder, Node<?> node, final IModelState state)
        {
            for(Node<?> child : node.getNodes().values())
            {
                generateQuads(builder, child, state);
            }
            if(node.getKind() instanceof Mesh && meshes.contains(node.getName()))
            {
                Mesh mesh = (Mesh)node.getKind();
                Collection<Face> faces = mesh.bake(new Function<Node<?>, Matrix4f>()
                {
                    private final TRSRTransformation global = state.apply(Optional.<IModelPart>absent()).or(TRSRTransformation.identity());
                    private final LoadingCache<Node<?>, TRSRTransformation> localCache = CacheBuilder.newBuilder()
                        .maximumSize(32)
                        .build(new CacheLoader<Node<?>, TRSRTransformation>()
                        {
                            public TRSRTransformation load(Node<?> node) throws Exception
                            {
                                return state.apply(Optional.of(new NodeJoint(node))).or(TRSRTransformation.identity());
                            }
                        });

                    public Matrix4f apply(Node<?> node)
                    {
                        return global.compose(localCache.getUnchecked(node)).getMatrix();
                    }
                });
                for(Face f : faces)
                {
                    UnpackedBakedQuad.Builder quadBuilder = new UnpackedBakedQuad.Builder(format);
                    quadBuilder.setQuadOrientation(EnumFacing.getFacingFromVector(f.getNormal().x, f.getNormal().y, f.getNormal().z));
                    quadBuilder.setQuadColored();
                    List<Texture> textures = null;
                    if(f.getBrush() != null) textures = f.getBrush().getTextures();
                    TextureAtlasSprite sprite;
                    if(textures == null || textures.isEmpty()) sprite = this.textures.get("missingno");
                    else if(textures.get(0) == B3DModel.Texture.White) sprite = ModelLoader.White.instance;
                    else sprite = this.textures.get(textures.get(0).getPath());
                    putVertexData(quadBuilder, f.getV1(), f.getNormal(), sprite);
                    putVertexData(quadBuilder, f.getV2(), f.getNormal(), sprite);
                    putVertexData(quadBuilder, f.getV3(), f.getNormal(), sprite);
                    putVertexData(quadBuilder, f.getV3(), f.getNormal(), sprite);
                    builder.add(quadBuilder.build());
                }
            }
        }

        private final void putVertexData(UnpackedBakedQuad.Builder builder, Vertex v, Vector3f faceNormal, TextureAtlasSprite sprite)
        {
            // TODO handle everything not handled (texture transformations, bones, transformations, normals, e.t.c)
            for(int e = 0; e < format.getElementCount(); e++)
            {
                switch(format.getElement(e).getUsage())
                {
                case POSITION:
                    builder.put(e, v.getPos().x, v.getPos().y, v.getPos().z, 1);
                    break;
                case COLOR:
                    float d = LightUtil.diffuseLight(faceNormal.x, faceNormal.y, faceNormal.z);
                    if(v.getColor() != null)
                    {
                        builder.put(e, d * v.getColor().x, d * v.getColor().y, d * v.getColor().z, v.getColor().w);
                    }
                    else
                    {
                        builder.put(e, d, d, d, 1);
                    }
                    break;
                case UV:
                    // TODO handle more brushes
                    if(format.getElement(e).getIndex() < v.getTexCoords().length)
                    {
                        builder.put(e,
                            sprite.getInterpolatedU(v.getTexCoords()[0].x * 16),
                            sprite.getInterpolatedV(v.getTexCoords()[0].y * 16),
                            0,
                            1
                        );
                    }
                    else
                    {
                        builder.put(e, 0, 0, 0, 1);
                    }
                    break;
                case NORMAL:
                    if(v.getNormal() != null)
                    {
                        builder.put(e, v.getNormal().x, v.getNormal().y, v.getNormal().z, 0);
                    }
                    else
                    {
                        builder.put(e, faceNormal.x, faceNormal.y, faceNormal.z, 0);
                    }
                    break;
                default:
                    builder.put(e);
                }
            }
        }

        public boolean isAmbientOcclusion()
        {
            return smooth;
        }

        public boolean isGui3d()
        {
            return gui3d;
        }

        public boolean isBuiltInRenderer()
        {
            return false;
        }

        public TextureAtlasSprite getParticleTexture()
        {
            // FIXME somehow specify particle texture in the model
            return textures.values().asList().get(0);
        }

        public ItemCameraTransforms getItemCameraTransforms()
        {
            return ItemCameraTransforms.DEFAULT;
        }

        public Pair<? extends IBakedModel, Matrix4f> handlePerspective(TransformType cameraTransformType)
        {
            return IPerspectiveAwareModel.MapWrapper.handlePerspective(this, state, cameraTransformType);
        }

        public ItemOverrideList getOverrides()
        {
            // TODO handle items
            return ItemOverrideList.NONE;
        }
    }
}
