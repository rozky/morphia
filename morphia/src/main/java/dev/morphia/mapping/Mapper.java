package dev.morphia.mapping;


import com.mongodb.DBRef;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import dev.morphia.Datastore;
import dev.morphia.EntityInterceptor;
import dev.morphia.Key;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.PostLoad;
import dev.morphia.annotations.PostPersist;
import dev.morphia.annotations.PreLoad;
import dev.morphia.annotations.PrePersist;
import dev.morphia.mapping.codec.DocumentWriter;
import dev.morphia.mapping.codec.EnumCodecProvider;
import dev.morphia.mapping.codec.MorphiaCodecProvider;
import dev.morphia.mapping.codec.MorphiaTypesCodecProvider;
import dev.morphia.mapping.codec.PrimitiveCodecProvider;
import dev.morphia.mapping.codec.pojo.MorphiaCodec;
import dev.morphia.mapping.codec.pojo.MorphiaModel;
import dev.morphia.mapping.lazy.proxy.ProxiedEntityReference;
import dev.morphia.mapping.lazy.proxy.ProxyHelper;
import dev.morphia.sofia.Sofia;
import dev.morphia.utils.ReflectionUtils;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;


/**
 * @morphia.internal
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class Mapper {

    /**
     * Special name that can never be used. Used as default for some fields to indicate default state.
     *
     * @morphia.internal
     */
    public static final String IGNORED_FIELDNAME = ".";

    static final String CLASS_NAME_FIELDNAME = "className";

    private static final Logger LOG = LoggerFactory.getLogger(Mapper.class);
    /**
     * Annotations interesting for life-cycle events
     */
    @SuppressWarnings("unchecked")
    public static final List<Class<? extends Annotation>> LIFECYCLE_ANNOTATIONS = asList(PrePersist.class,
                                                                                          PreLoad.class,
                                                                                          PostPersist.class,
                                                                                          PostLoad.class);
    /**
     * Set of classes that registered by this mapper
     */
    private final Map<Class, MappedClass> mappedClasses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<MappedClass>> mappedClassesByCollection = new ConcurrentHashMap<>();

    //EntityInterceptors; these are called after EntityListeners and lifecycle methods on an Entity, for all Entities
    private final List<EntityInterceptor> interceptors = new LinkedList<>();

    private CodecRegistry codecRegistry;
    private Datastore datastore;
    private final MapperOptions opts;

    // TODO:  unify with DefaultCreator if it survives the Codec switchover
    private Map<String, Class> classNameCache = new ConcurrentHashMap<>();

    /**
     * Creates a Mapper with the given options.
     *
     * @morphia.internal
     * @param opts the options to use
     */
    public Mapper(final Datastore datastore, final CodecRegistry codecRegistry, final MapperOptions opts) {
        this.datastore = datastore;
        this.opts = opts;
        this.codecRegistry = fromRegistries(
            new PrimitiveCodecProvider(codecRegistry),
            codecRegistry,
            fromProviders(
                new EnumCodecProvider(),
                new MorphiaTypesCodecProvider(this),
                new MorphiaCodecProvider(this, List.of(new MorphiaConvention(datastore, opts)), Set.of(""))));
    }

    public Datastore getDatastore() {
        return datastore;
    }

    /**
     * Maps a set of classes
     *
     * @param entityClasses the classes to map
     * @return
     */
    public List<MappedClass> map(final Class... entityClasses) {
        return map(List.of(entityClasses));
    }

    /**
     * Maps a set of classes
     *
     * @param classes the classes to map
     * @return the list of mapped classes
     */
    public List<MappedClass> map(final List<Class> classes) {
        return classes.stream()
                      .map(c -> getMappedClass(c))
                      .filter(mc -> mc != null)
                      .collect(Collectors.toList());
    }

    /**
     * Tries to map all classes in the package specified.
     *
     * @param packageName the name of the package to process
     */
    public synchronized void mapPackage(final String packageName) {
        try {
            for (final Class clazz : ReflectionUtils.getClasses(getClass().getClassLoader(), packageName,
                getOptions().isMapSubPackages())) {
                map(clazz);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new MappingException("Could not get map classes from package " + packageName, e);
        }
    }

    /**
     * Maps all the classes found in the package to which the given class belongs.
     *
     * @param clazz the class to use when trying to find others to map
     */
    public void mapPackageFromClass(final Class clazz) {
        mapPackage(clazz.getPackage().getName());
    }

    /**
     * Adds an {@link EntityInterceptor}
     *
     * @param ei the interceptor to add
     */
    public void addInterceptor(final EntityInterceptor ei) {
        interceptors.add(ei);
    }

    public boolean hasInterceptors() {
        return !interceptors.isEmpty();
    }

    public MorphiaModel getModel(final Class<?> aClass) {
        Codec<?> codec = getCodecRegistry().get(aClass);
        if(codec instanceof MorphiaCodec) {
            return ((MorphiaCodec)codec).getClassModel();
        }
        return null;
    }

    /**
     * Creates a MappedClass and validates it.
     *
     * @param c the Class to map
     * @return the MappedClass for the given Class
     */
    private MappedClass addMappedClass(final Class c) {
        MappedClass mappedClass = mappedClasses.get(c);
        if (mappedClass == null) {
            try {
                final Codec codec1 = codecRegistry.get(c);
                if (codec1 instanceof MorphiaCodec) {
                    return addMappedClass(((MorphiaCodec) codec1).getMappedClass());
                }
            } catch (CodecConfigurationException ignore) {
                ignore.printStackTrace();
            }
        }
        return mappedClass;
    }

    private MappedClass addMappedClass(final MappedClass mc) {
        mappedClasses.put(mc.getType(), mc);
        if(mc.getEntityAnnotation() != null) {
            mappedClassesByCollection.computeIfAbsent(mc.getCollectionName(), s -> new CopyOnWriteArraySet<>())
                                     .add(mc);
        }

        if (!mc.isInterface()) {
            mc.validate(this);
        }

        return mc;
    }

    /**
     * Finds any subtypes for the given MappedClass.
     *
     * @param mc the parent type
     * @return the list of subtypes
     * @since 1.3
     */
    public List<MappedClass> getSubTypes(final MappedClass mc) {
        List<MappedClass> subtypes = new ArrayList<>();
        for (MappedClass mappedClass : getMappedClasses()) {
            if (mappedClass.isSubType(mc)) {
                subtypes.add(mappedClass);
            }
        }

        return subtypes;
    }

    public CodecRegistry getCodecRegistry() {
        return codecRegistry;
    }

    /**
     * Converts a Document back to a type-safe java object (POJO)
     *
     * @param <T>      the type of the entity
     * @param clazz
     * @param document the Document containing the document from mongodb
     * @return the new entity
     * @morphia.internal
     */
    public <T> T fromDocument(final Class<T> clazz, final Document document) {
        if (document == null) {
            return null;
        }

        Class<T> aClass = clazz;
        if (document.containsKey(opts.getDiscriminatorField())) {
            aClass = getClass(document);
        }

        CodecRegistry codecRegistry = getCodecRegistry();

        BsonDocumentReader reader = new BsonDocumentReader(document.toBsonDocument(aClass, codecRegistry));

        return codecRegistry
                   .get(clazz)
                   .decode(reader, DecoderContext.builder().build());
    }

    @SuppressWarnings("unchecked")
    private <T> Class<T> getClass(final Document document) {
        // see if there is a className value
        Class c = null;
        if (document.containsKey(getOptions().getDiscriminatorField())) {
            final String className = (String) document.get(getOptions().getDiscriminatorField());
            // try to Class.forName(className) as defined in the documentect first,
            // otherwise return the entityClass
            try {
                if (getOptions().isCacheClassLookups()) {
                    c = classNameCache.get(className);
                    if (c == null) {
                        c = Class.forName(className, true, currentThread().getContextClassLoader());
                        classNameCache.put(className, c);
                    }
                } else {
                    c = Class.forName(className, true, currentThread().getContextClassLoader());
                }
            } catch (ClassNotFoundException e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Class not found defined in document: ", e);
                }
            }
        }
        return c;
    }

    /**
     * Looks up the class mapped to a named collection.
     *
     * @param collection the collection name
     * @param <T> the class type
     * @return the Class mapped to this collection name
     * @morphia.internal
     */
    public <T> Class<T> getClassFromCollection(final String collection) {
        final List<MappedClass> classes = getClassesMappedToCollection(collection);
        if (classes.size() > 1) {
                Sofia.logMoreThanOneMapper(collection,
                    classes.stream()
                           .map(c-> c.getType().getName())
                               .collect(Collectors.joining(", ")));
        }
        return (Class<T>) classes.get(0).getType();
    }

    /**
     * @morphia.internal
     * @param collection
     * @return
     */
    public List<MappedClass> getClassesMappedToCollection(final String collection) {
        final Set<MappedClass> mcs = mappedClassesByCollection.get(collection);
        if (mcs == null || mcs.isEmpty()) {
            throw new MappingException(Sofia.collectionNotMapped(collection));
        }
        return new ArrayList<>(mcs);
    }

    public <T> MongoCollection<T> getCollection(final Class<T> clazz) {
        MappedClass mappedClass = getMappedClass(clazz);
        if (mappedClass == null) {
            throw new MappingException(Sofia.notMappable(clazz.getName()));
        }
        if (mappedClass.getCollectionName() == null) {
            throw new MappingException(Sofia.noMappedCollection(clazz.getName()));
        }

        MongoCollection<T> collection = null;
        if (mappedClass.getEntityAnnotation() != null) {
            collection = datastore.getDatabase().getCollection(mappedClass.getCollectionName(), clazz);
            collection = enforceWriteConcern(collection, clazz);
        }
        return collection;
    }

    public MongoCollection enforceWriteConcern(final MongoCollection collection, final Class klass) {
        WriteConcern applied = getWriteConcern(klass);
        return applied != null
               ? collection.withWriteConcern(applied)
               : collection;
    }

    /**
     * Gets the write concern for entity or returns the default write concern for this datastore
     *
     * @param clazz the class to use when looking up the WriteConcern
     * @morphia.internal
     */
    public WriteConcern getWriteConcern(final Class clazz) {
        WriteConcern wc = null;
        if (clazz != null) {
            final Entity entityAnn = getMappedClass(clazz).getEntityAnnotation();
            if (entityAnn != null && !entityAnn.concern().isEmpty()) {
                wc = WriteConcern.valueOf(entityAnn.concern());
            }
        }

        return wc;
    }

    /**
     * Gets the ID value for an entity
     *
     * @param entity the entity to process
     * @return the ID value
     */
    public Object getId(final Object entity) {
        Object unwrapped = entity;
        if (unwrapped == null) {
            return null;
        }
        unwrapped = ProxyHelper.unwrap(unwrapped);
        try {
            final MappedClass mappedClass = getMappedClass(unwrapped.getClass());
            if (mappedClass != null) {
                final MappedField idField = mappedClass.getIdField();
                if (idField != null) {
                    return idField.getFieldValue(unwrapped);
                }
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
        return null;
    }

    /**
     * Gets list of {@link EntityInterceptor}s
     *
     * @return the Interceptors
     */
    public Collection<EntityInterceptor> getInterceptors() {
        return interceptors;
    }

    /**
     * Gets the Key for an entity
     *
     * @param entity the entity to process
     * @param <T>    the type of the entity
     * @return the Key
     */
    public <T> Key<T> getKey(final T entity) {
        T unwrapped = entity;
        if (unwrapped instanceof ProxiedEntityReference) {
            final ProxiedEntityReference proxy = (ProxiedEntityReference) unwrapped;
            return (Key<T>) proxy.__getKey();
        }

        unwrapped = ProxyHelper.unwrap(unwrapped);
        if (unwrapped instanceof Key) {
            return (Key<T>) unwrapped;
        }

        final Object id = getId(unwrapped);
        final Class<T> aClass = (Class<T>) unwrapped.getClass();
        return id == null ? null : new Key<>(aClass, getMappedClass(aClass).getCollectionName(), id);
    }

    /**
     * Gets the Key for an entity and a specific collection
     *
     * @param entity     the entity to process
     * @param collection the collection to use in the Key rather than the mapped collection as defined on the entity's class
     * @param <T>        the type of the entity
     * @return the Key
     */
    public <T> Key<T> getKey(final T entity, final String collection) {
        T unwrapped = entity;
        if (unwrapped instanceof ProxiedEntityReference) {
            final ProxiedEntityReference proxy = (ProxiedEntityReference) unwrapped;
            return (Key<T>) proxy.__getKey();
        }

        unwrapped = ProxyHelper.unwrap(unwrapped);
        if (unwrapped instanceof Key) {
            return (Key<T>) unwrapped;
        }

        final Object id = getId(unwrapped);
        final Class<T> aClass = (Class<T>) unwrapped.getClass();
        return id == null ? null : new Key<>(aClass, collection, id);
    }

    /**
     * Gets the Keys for a list of objects
     *
     * @param clazz the Class of the objects
     * @param refs  the objects to fetch the keys for
     * @param <T>   the type of the entity
     * @return the list of Keys
     */
    public <T> List<Key<T>> getKeysByManualRefs(final Class<T> clazz, final List<Object> refs) {
        final String collection = getMappedClass(clazz).getCollectionName();
        final List<Key<T>> keys = new ArrayList<>(refs.size());
        for (final Object ref : refs) {
            keys.add(this.manualRefToKey(collection, ref));
        }

        return keys;
    }

    /**
     * Gets the Keys for a list of objects
     *
     * @param refs the objects to process
     * @param <T>  the type of the objects
     * @return the list of Keys
     */
    public <T> List<Key<T>> getKeysByRefs(final List<DBRef> refs) {
        final List<Key<T>> keys = new ArrayList<>(refs.size());
        for (final DBRef ref : refs) {
            final Key<T> testKey = refToKey(ref);
            keys.add(testKey);
        }
        return keys;
    }

    /**
     * Gets the {@link MappedClass} for the object (type). If it isn't mapped, create a new class and cache it (without validating).
     *
     * @param type the type to process
     * @return the MappedClass for the object given
     */
    public MappedClass getMappedClass(final Class type) {
        if (type == null) {
            return null;
        }

        MappedClass mc = mappedClasses.get(type);
        if (mc == null) {
            mc = addMappedClass(type);
        }
        return mc;
    }

    /**
     * @return collection of MappedClasses
     */
    public Collection<MappedClass> getMappedClasses() {
        return new ArrayList<>(mappedClasses.values());
    }

    /**
     * @return the options used by this Mapper
     */
    public MapperOptions getOptions() {
        return opts;
    }

    /**
     * Sets the options this Mapper should use
     *
     * @param options the options to use
     * @deprecated no longer used
     */
    @SuppressWarnings("unused")
    @Deprecated(since = "2.0", forRemoval = true)
    public void setOptions(final MapperOptions options) {
    }

    /**
     * Checks to see if a Class has been mapped.
     *
     * @param c the Class to check
     * @return true if the Class has been mapped
     */
    public boolean isMapped(final Class c) {
        return mappedClasses.containsKey(c.getName());
    }

    /**
     * Converts a DBRef to a Key
     *
     * @param ref the DBRef to convert
     * @param <T> the type of the referenced entity
     * @return the Key
     */
    public <T> Key<T> refToKey(final DBRef ref) {
        return ref == null ? null : new Key<>((Class<? extends T>) getClassFromCollection(ref.getCollectionName()),
            ref.getCollectionName(), ref.getId());
    }

    /**
     * Converts an entity (POJO) to a Document.  A special field will be added to keep track of the class type.
     *
     * @param entity The POJO
     * @return the Document
     * @morphia.internal
     */
    public Document toDocument(final Object entity) {

        final MappedClass mc = getMappedClass(entity.getClass());

        DocumentWriter writer = new DocumentWriter();
        Codec codec = getCodecRegistry().get(mc.getType());
        codec.encode(writer, entity,
            EncoderContext.builder()
                          .build());

        return writer.getRoot();

/*
        Document document = new Document();

        if (mc.getEntityAnnotation() == null || mc.getEntityAnnotation().useDiscriminator()) {
            document.put(opts.getDiscriminatorField(), entity.getClass().getName());
        }

        if (lifecycle) {
            mc.callLifecycleMethods(PrePersist.class, entity, document, this);
        }

        for (final MappedField mf : mc.getFields()) {
            try {
                writeMappedField(document, mf, entity, involvedObjects);
            } catch (Exception e) {
                throw new MappingException("Error mapping field:" + mf, e);
            }
        }
        if (involvedObjects != null) {
            involvedObjects.put(entity, document);
        }

        if (lifecycle) {
            mc.callLifecycleMethods(PreSave.class, entity, document, this);
        }

        return document;
*/
    }

    /**
     * Updates the collection value on a Key with the mapped value on the Key's type Class
     *
     * @param key the Key to update
     * @return the collection name on the Key
     */
    public String updateCollection(final Key key) {
        if (key.getCollection() == null && key.getType() == null) {
            throw new IllegalStateException("Key is invalid! " + key);
        } else if (key.getCollection() == null) {
            key.setCollection(getMappedClass(key.getType()).getCollectionName());
        }

        return key.getCollection();
    }

    <T> Key<T> manualRefToKey(final String collection, final Object id) {
        return id == null ? null : new Key<>((Class<? extends T>) getClassFromCollection(collection), collection, id);
    }

}
