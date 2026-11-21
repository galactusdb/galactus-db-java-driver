package db.galactus;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Object mapping: nodes and relationships as the application's own classes,
 * and the Cypher for everyday operations. JDK only; docs/MAPPER.md is the
 * contract every Galactus mapper follows.
 *
 * <pre>{@code
 * @Mapper.Node("Person")
 * public class Person {
 *   @Mapper.Key public long id;
 *   public String name;
 *   @Mapper.Property("email_address") public String email;
 * }
 *
 * Mapper people = new Mapper(driver);
 * people.save(ann);
 * Person found = people.find(Person.class, 1L);
 * }</pre>
 *
 * <p>Every field that is not static or transient is mapped, whatever its
 * visibility, under its own name unless {@link Property} gives another. A
 * mapped class needs a constructor with no parameters.
 */
public final class Mapper {
  /** Names the label of a node type. Without it the label is the class name. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface Node {
    String value() default "";
  }

  /** Marks a class as a relationship type and names its type. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface Relationship {
    String value() default "";
  }

  /** Marks the field that identifies a node to the application. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface Key {}

  /** Names the database property a field is stored as. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface Property {
    String value();
  }

  /** A type that cannot be mapped, or a value that does not fit it. */
  public static final class MappingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public MappingException(String message) { super(message); }
  }

  /** A relationship and the node at its far end. */
  public static final class Pair<R, B> {
    public final R relationship;
    public final B node;
    Pair(R relationship, B node) {
      this.relationship = relationship;
      this.node = node;
    }
  }

  private static final class Mapped {
    final Field field;
    final String stored;
    Mapped(Field field, String stored) {
      this.field = field;
      this.stored = stored;
    }
  }

  private static final class Shape {
    final Class<?> type;
    final String name;
    final boolean relationship;
    final List<Mapped> fields = new ArrayList<>();
    final Constructor<?> constructor;
    Mapped key;

    Shape(Class<?> type) {
      this.type = type;
      Relationship rel = type.getAnnotation(Relationship.class);
      Node node = type.getAnnotation(Node.class);
      relationship = rel != null;
      String given = rel != null ? rel.value() : node != null ? node.value() : "";
      name = checked(!given.isEmpty() ? given
                         : relationship ? type.getSimpleName().toUpperCase(java.util.Locale.ROOT)
                         : type.getSimpleName(),
                     type.getSimpleName() + "'s label or relationship type");
      try {
        constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
      } catch (ReflectiveOperationException | RuntimeException e) {
        throw new MappingException(type.getSimpleName() + " needs a constructor with no parameters");
      }
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          int modifiers = field.getModifiers();
          if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic())
            continue;
          Property property = field.getAnnotation(Property.class);
          Mapped mapped = new Mapped(field, checked(property != null ? property.value() : field.getName(),
                                                    type.getSimpleName() + "." + field.getName()));
          field.setAccessible(true);
          fields.add(mapped);
          if (field.getAnnotation(Key.class) == null)
            continue;
          if (key != null)
            throw new MappingException(type.getSimpleName() + " has more than one key");
          key = mapped;
        }
      }
    }

    Map<String, Object> properties(Object object) {
      Map<String, Object> out = new LinkedHashMap<>();
      try {
        for (Mapped mapped : fields)
          out.put(mapped.stored, mapped.field.get(object));
      } catch (IllegalAccessException e) {
        throw new MappingException(type.getSimpleName() + ": " + e.getMessage());
      }
      return out;
    }

    Object build(Map<?, ?> properties) {
      try {
        Object object = constructor.newInstance();
        for (Mapped mapped : fields) {
          Object value = properties.get(mapped.stored);
          if (value == null) {
            if (!mapped.field.getType().isPrimitive())
              mapped.field.set(object, null);
            continue;
          }
          try {
            mapped.field.set(object, convert(value, mapped.field.getType()));
          } catch (IllegalArgumentException | ArithmeticException e) {
            throw new MappingException(type.getSimpleName() + "." + mapped.field.getName() + ": a " +
                                       value.getClass().getSimpleName() + " does not fit a " +
                                       mapped.field.getType().getSimpleName());
          }
        }
        return object;
      } catch (ReflectiveOperationException e) {
        throw new MappingException(type.getSimpleName() + ": " + e);
      }
    }
  }

  private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();

  private static String checked(String value, String what) {
    if (value.isEmpty() || value.indexOf('`') >= 0)
      throw new MappingException(what + " must be a name without backticks, got '" + value + "'");
    return value;
  }

  // A database value as a field type: the number widths Java has from the two
  // the database has. Anything else must already be of the field's type.
  private static Object convert(Object value, Class<?> target) {
    if (value instanceof Long) {
      long whole = (Long) value;
      if (target == long.class || target == Long.class) return whole;
      if (target == int.class || target == Integer.class) return Math.toIntExact(whole);
      if (target == short.class || target == Short.class) {
        if (whole != (short) whole) throw new ArithmeticException();
        return (short) whole;
      }
      if (target == byte.class || target == Byte.class) {
        if (whole != (byte) whole) throw new ArithmeticException();
        return (byte) whole;
      }
      if (target == double.class || target == Double.class) return (double) whole;
      if (target == float.class || target == Float.class) return (float) whole;
    }
    if (value instanceof Double) {
      if (target == double.class || target == Double.class) return value;
      if (target == float.class || target == Float.class) return ((Double) value).floatValue();
    }
    if (value instanceof Boolean && (target == boolean.class || target == Boolean.class)) return value;
    if (value instanceof String && target == java.math.BigDecimal.class)
      return new java.math.BigDecimal((String) value);
    if (target.isInstance(value)) return value;
    throw new IllegalArgumentException();
  }

  private static Shape shape(Class<?> type) {
    return SHAPES.computeIfAbsent(type, Shape::new);
  }
  private static Shape node(Class<?> type) {
    Shape shape = shape(type);
    if (shape.relationship)
      throw new MappingException(type.getSimpleName() + " is a relationship type, not a node type");
    return shape;
  }
  private static Shape relationship(Class<?> type) {
    Shape shape = shape(type);
    if (!shape.relationship)
      throw new MappingException(type.getSimpleName() + " is not marked @Mapper.Relationship");
    return shape;
  }
  private static Shape keyed(Class<?> type) {
    Shape shape = node(type);
    if (shape.key == null) throw new MappingException(type.getSimpleName() + " has no key");
    return shape;
  }
  private static Object keyValue(Shape shape, Object key) {
    if (key == null)
      throw new MappingException(shape.type.getSimpleName() + ": the key " + shape.key.field.getName() +
                                 " has no value");
    return key;
  }
  private static Object keyOf(Shape shape, Object object) {
    return keyValue(shape, shape.properties(object).get(shape.key.stored));
  }
  private static String quote(String name) { return "`" + name + "`"; }
  private static String matchKey(String variable, Shape shape, String parameter) {
    return "(" + variable + ":" + quote(shape.name) + " {" + quote(shape.key.stored) + ": $" + parameter + "})";
  }
  private static Map<String, Object> parameters(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
    return map;
  }

  /** Every mapped property of an object, by its stored name. */
  public static Map<String, Object> propertiesOf(Object object) {
    return shape(object.getClass()).properties(object);
  }

  /** An object of a node type from a node. */
  public static <T> T fromNode(Class<T> type, Types.Node value) {
    return type.cast(node(type).build(value.properties));
  }

  /** An object of a relationship type from a relationship. */
  public static <T> T fromRelationship(Class<T> type, Types.Relationship value) {
    return type.cast(relationship(type).build(value.properties));
  }

  private static <T> T fromValue(Class<T> type, Object value) {
    if (value instanceof Types.Node) return fromNode(type, (Types.Node) value);
    if (value instanceof Types.Relationship) return fromRelationship(type, (Types.Relationship) value);
    if (value instanceof Types.UnboundRelationship)
      return type.cast(relationship(type).build(((Types.UnboundRelationship) value).properties));
    if (value instanceof Map) return type.cast(shape(type).build((Map<?, ?>) value));
    throw new MappingException(type.getSimpleName() + ": expected a node, a relationship or a map, got " +
                               (value == null ? "null" : value.getClass().getSimpleName()));
  }

  private final Driver driver;

  /** A mapper that runs each operation on the driver as one statement. */
  public Mapper(Driver driver) { this.driver = driver; }

  public Driver driver() { return driver; }

  /** Makes a new node of the object and returns it as stored. */
  @SuppressWarnings("unchecked")
  public <T> T create(T object) throws IOException {
    Shape shape = node(object.getClass());
    Driver.Result result = driver.executeQuery("CREATE (n:" + quote(shape.name) + ") SET n = $properties RETURN n",
                                               parameters("properties", shape.properties(object)));
    return fromValue((Class<T>) object.getClass(), result.records.get(0).get("n"));
  }

  /** Creates the node with the object's key, or changes it, and returns it as stored. */
  @SuppressWarnings("unchecked")
  public <T> T save(T object) throws IOException {
    Shape shape = keyed(object.getClass());
    Driver.Result result = driver.executeQuery(
        "MERGE " + matchKey("n", shape, "key") + " SET n += $properties RETURN n",
        parameters("key", keyOf(shape, object), "properties", shape.properties(object)));
    return fromValue((Class<T>) object.getClass(), result.records.get(0).get("n"));
  }

  /** The node with the key, or null when there is none. */
  public <T> T find(Class<T> type, Object key) throws IOException {
    Shape shape = keyed(type);
    Driver.Result result = driver.executeQuery("MATCH " + matchKey("n", shape, "key") + " RETURN n LIMIT 1",
                                               parameters("key", keyValue(shape, key)));
    return result.records.isEmpty() ? null : fromValue(type, result.records.get(0).get("n"));
  }

  /** Every node of the type. */
  public <T> List<T> findAll(Class<T> type) throws IOException { return findAll(type, null, -1); }

  /**
   * Every node of the type whose fields equal those in {@code filter}, which
   * is keyed by field name. A negative limit is no limit.
   */
  public <T> List<T> findAll(Class<T> type, Map<String, ?> filter, int limit) throws IOException {
    Shape shape = node(type);
    Map<String, Object> parameters = new LinkedHashMap<>();
    List<String> conditions = new ArrayList<>();
    if (filter != null) {
      for (Map.Entry<String, ?> entry : filter.entrySet()) {
        Mapped mapped = shape.fields.stream().filter(f -> f.field.getName().equals(entry.getKey())).findFirst().orElse(null);
        if (mapped == null)
          throw new MappingException(type.getSimpleName() + " has no field '" + entry.getKey() + "' to filter by");
        String parameter = "p" + conditions.size();
        parameters.put(parameter, entry.getValue());
        conditions.add("n." + quote(mapped.stored) + " = $" + parameter);
      }
    }
    StringBuilder query = new StringBuilder("MATCH (n:" + quote(shape.name) + ")");
    if (!conditions.isEmpty()) query.append(" WHERE ").append(String.join(" AND ", conditions));
    query.append(" RETURN n");
    if (shape.key != null) query.append(" ORDER BY n.").append(quote(shape.key.stored));
    if (limit >= 0) {
      parameters.put("limit", (long) limit);
      query.append(" LIMIT $limit");
    }
    List<T> out = new ArrayList<>();
    for (Map<String, Object> row : driver.executeQuery(query.toString(), parameters).records)
      out.add(fromValue(type, row.get("n")));
    return out;
  }

  /** Removes the node with the key and its relationships. Whether there was one. */
  public boolean delete(Class<?> type, Object key) throws IOException {
    Shape shape = keyed(type);
    Driver.Result result = driver.executeQuery(
        "MATCH " + matchKey("n", shape, "key") + " DETACH DELETE n RETURN count(*) AS deleted",
        parameters("key", keyValue(shape, key)));
    return (Long) result.records.get(0).get("deleted") > 0;
  }

  /** Joins two stored nodes with a relationship, or changes the one between them. */
  @SuppressWarnings("unchecked")
  public <R> R relate(Object from, R link, Object to) throws IOException {
    Shape a = keyed(from.getClass()), b = keyed(to.getClass()), r = relationship(link.getClass());
    Driver.Result result = driver.executeQuery(
        "MATCH " + matchKey("a", a, "a") + ", " + matchKey("b", b, "b") + " MERGE (a)-[r:" + quote(r.name) +
            "]->(b) SET r += $properties RETURN r",
        parameters("a", keyOf(a, from), "b", keyOf(b, to), "properties", r.properties(link)));
    if (result.records.isEmpty())
      throw new MappingException(a.type.getSimpleName() + " or " + b.type.getSimpleName() + " was not found to relate");
    return fromValue((Class<R>) link.getClass(), result.records.get(0).get("r"));
  }

  /** The relationships of one type from a node to nodes of another type. */
  public <R, B> List<Pair<R, B>> related(Object from, Class<R> linkType, Class<B> toType) throws IOException {
    Shape a = keyed(from.getClass()), b = node(toType), r = relationship(linkType);
    String query = "MATCH " + matchKey("a", a, "a") + "-[r:" + quote(r.name) + "]->(b:" + quote(b.name) + ") RETURN r, b";
    if (b.key != null) query += " ORDER BY b." + quote(b.key.stored);
    List<Pair<R, B>> out = new ArrayList<>();
    for (Map<String, Object> row : driver.executeQuery(query, parameters("a", keyOf(a, from))).records)
      out.add(new Pair<>(fromValue(linkType, row.get("r")), fromValue(toType, row.get("b"))));
    return out;
  }

  /**
   * Runs a statement and maps the first column of each row: a node, a
   * relationship, or a map of properties.
   */
  public <T> List<T> query(Class<T> type, String cypher, Map<String, ?> parameters) throws IOException {
    shape(type);
    Driver.Result result = driver.executeQuery(cypher, parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters));
    List<T> out = new ArrayList<>();
    if (result.keys.isEmpty()) return out;
    for (Map<String, Object> row : result.records) out.add(fromValue(type, row.get(result.keys.get(0))));
    return out;
  }
}
