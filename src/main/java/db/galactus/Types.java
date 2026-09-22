package db.galactus;

import java.time.*;
import java.util.*;

/** Lossless Bolt values. Seconds in legacy DateTime are LOCAL epoch seconds. */
public final class Types {
  private Types() {}
  static void validateParameters(Object v, int depth) {
    if (depth >= 64)
      throw new IllegalArgumentException("Nesting exceeds 64 levels");
    v = dehydrate(v);
    if (v instanceof Structure) {
      Structure s = (Structure)v;
      switch (s.tag) {
      case 0x44:
      case 0x74:
      case 0x54:
      case 0x64:
      case 0x46:
      case 0x66:
      case 0x45:
      case 0x58:
      case 0x59:
        break;
      default:
        throw new IllegalArgumentException(
            "Graph entities and unknown structures are result-only; pass properties or an ID");
      }
      for (Object f : s.fields)
        validateParameters(f, depth + 1);
    } else if (v instanceof Map) {
      for (Object f : ((Map<?, ?>)v).values())
        validateParameters(f, depth + 1);
    } else if (v instanceof Collection) {
      for (Object f : (Collection<?>)v)
        validateParameters(f, depth + 1);
    } else if (v != null && v.getClass().isArray() && !(v instanceof byte[])) {
      for (int i = 0; i < java.lang.reflect.Array.getLength(v); i++)
        validateParameters(java.lang.reflect.Array.get(v, i), depth + 1);
    }
  }
  public static class Structure {
    public final int tag;
    public final List<Object> fields;
    public Structure(int tag, Object... fields) {
      this.tag = tag;
      this.fields = Collections.unmodifiableList(Arrays.asList(fields.clone()));
    }
  }
  public static final class Node extends Structure {
    public final long id;
    public final List<?> labels;
    public final Map<?, ?> properties;
    public Node(long id, List<?> labels, Map<?, ?> properties) {
      super(0x4e, id, labels, properties);
      this.id = id;
      this.labels = labels;
      this.properties = properties;
    }
  }
  public static final class Relationship extends Structure {
    public final long id, startId, endId;
    public final String type;
    public final Map<?, ?> properties;
    public Relationship(long id, long startId, long endId, String type,
                        Map<?, ?> properties) {
      super(0x52, id, startId, endId, type, properties);
      this.id = id;
      this.startId = startId;
      this.endId = endId;
      this.type = type;
      this.properties = properties;
    }
  }
  public static final class UnboundRelationship extends Structure {
    public final long id;
    public final String type;
    public final Map<?, ?> properties;
    public UnboundRelationship(long id, String type, Map<?, ?> properties) {
      super(0x72, id, type, properties);
      this.id = id;
      this.type = type;
      this.properties = properties;
    }
  }
  public static final class Path extends Structure {
    public final List<?> nodes, relationships, sequence;
    public Path(List<?> nodes, List<?> relationships, List<?> sequence) {
      super(0x50, nodes, relationships, sequence);
      this.nodes = nodes;
      this.relationships = relationships;
      this.sequence = sequence;
    }
  }
  public static final class Duration extends Structure {
    public final long months, days, seconds, nanoseconds;
    public Duration(long months, long days, long seconds, long nanoseconds) {
      super(0x45, months, days, seconds, nanoseconds);
      this.months = months;
      this.days = days;
      this.seconds = seconds;
      this.nanoseconds = nanoseconds;
    }
  }
  public static final class ZonedDateTimeValue extends Structure {
    public final long seconds, nanoseconds;
    public final String zoneId;
    public ZonedDateTimeValue(long seconds, long nanoseconds, String zoneId) {
      super(0x66, seconds, nanoseconds, zoneId);
      this.seconds = seconds;
      this.nanoseconds = nanoseconds;
      this.zoneId = zoneId;
    }
  }
  public static final class Point2D extends Structure {
    public final long srid;
    public final double x, y;
    public Point2D(long srid, double x, double y) {
      super(0x58, srid, x, y);
      this.srid = srid;
      this.x = x;
      this.y = y;
    }
  }
  public static final class Point3D extends Structure {
    public final long srid;
    public final double x, y, z;
    public Point3D(long srid, double x, double y, double z) {
      super(0x59, srid, x, y, z);
      this.srid = srid;
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }
  static Object dehydrate(Object v) {
    if (v instanceof LocalDate)
      return new Structure(0x44, ((LocalDate)v).toEpochDay());
    if (v instanceof LocalTime)
      return new Structure(0x74, ((LocalTime)v).toNanoOfDay());
    if (v instanceof OffsetTime) {
      OffsetTime t = (OffsetTime)v;
      return new Structure(0x54, t.toLocalTime().toNanoOfDay(),
                           (long)t.getOffset().getTotalSeconds());
    }
    if (v instanceof LocalDateTime) {
      LocalDateTime t = (LocalDateTime)v;
      return new Structure(0x64, t.toEpochSecond(ZoneOffset.UTC),
                           (long)t.getNano());
    }
    if (v instanceof Instant)
      v = ((Instant)v).atOffset(ZoneOffset.UTC);
    if (v instanceof ZonedDateTime)
      v = ((ZonedDateTime)v).toOffsetDateTime(); // preserves overlap instant
    if (v instanceof OffsetDateTime) {
      OffsetDateTime t = (OffsetDateTime)v;
      return new Structure(
          0x46, t.toLocalDateTime().toEpochSecond(ZoneOffset.UTC),
          (long)t.getNano(), (long)t.getOffset().getTotalSeconds());
    }
    if (v instanceof java.time.Duration) {
      java.time.Duration t = (java.time.Duration)v;
      return new Duration(0, 0, t.getSeconds(), t.getNano());
    }
    if (v instanceof Period) {
      Period t = (Period)v;
      return new Duration(t.toTotalMonths(), t.getDays(), 0, 0);
    }
    if (v instanceof Spatial)
      return ((Spatial)v).toMap();
    return v;
  }
  static Object hydrate(int tag, Object[] f) {
    try {
      switch (tag) {
      case 0x4e:
        arity(f, 3);
        return new Node((Long)f[0], (List<?>)f[1], (Map<?, ?>)f[2]);
      case 0x52:
        arity(f, 5);
        return new Relationship((Long)f[0], (Long)f[1], (Long)f[2],
                                (String)f[3], (Map<?, ?>)f[4]);
      case 0x72:
        arity(f, 3);
        return new UnboundRelationship((Long)f[0], (String)f[1],
                                       (Map<?, ?>)f[2]);
      case 0x50:
        arity(f, 3);
        return new Path((List<?>)f[0], (List<?>)f[1], (List<?>)f[2]);
      case 0x44:
        arity(f, 1);
        return LocalDate.ofEpochDay((Long)f[0]);
      case 0x74:
        arity(f, 1);
        return LocalTime.ofNanoOfDay((Long)f[0]);
      case 0x54:
        arity(f, 2);
        return OffsetTime.of(
            LocalTime.ofNanoOfDay((Long)f[0]),
            ZoneOffset.ofTotalSeconds(Math.toIntExact((Long)f[1])));
      case 0x64:
        arity(f, 2);
        return LocalDateTime.ofEpochSecond(
            (Long)f[0], Math.toIntExact((Long)f[1]), ZoneOffset.UTC);
      case 0x46:
        arity(f, 3);
        return OffsetDateTime.of(
            LocalDateTime.ofEpochSecond((Long)f[0], Math.toIntExact((Long)f[1]),
                                        ZoneOffset.UTC),
            ZoneOffset.ofTotalSeconds(Math.toIntExact((Long)f[2])));
      case 0x66:
        arity(f, 3);
        return new ZonedDateTimeValue((Long)f[0], (Long)f[1], (String)f[2]);
      case 0x45:
        arity(f, 4);
        return new Duration((Long)f[0], (Long)f[1], (Long)f[2], (Long)f[3]);
      case 0x58:
        arity(f, 3);
        return new Point2D((Long)f[0], (Double)f[1], (Double)f[2]);
      case 0x59:
        arity(f, 4);
        return new Point3D((Long)f[0], (Double)f[1], (Double)f[2],
                           (Double)f[3]);
      default:
        return new Structure(tag, f);
      }
    } catch (DateTimeException | ArithmeticException e) {
      return new Structure(tag, f);
    }
  }
  static void arity(Object[] f, int n) {
    if (f.length != n)
      throw new IllegalArgumentException("Invalid structure field count");
  }
}
