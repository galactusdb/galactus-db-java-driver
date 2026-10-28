package db.galactus;

import java.time.*;
import java.util.*;

/**
 * Lossless Bolt values. Graph values carry Bolt 5 element ids (filled from the
 * integer id on Bolt 4.4); {@link ZonedDateTimeValue} records whether its
 * seconds are local or UTC.
 */
public final class Types {
  private Types() {}
  static void validateParameters(Object v, int depth) {
    if (depth >= 64)
      throw new IllegalArgumentException("Nesting exceeds 64 levels");
    v = dehydrate(v, false);
    if (v instanceof Structure) {
      Structure s = (Structure)v;
      switch (s.tag) {
      case 0x44:
      case 0x74:
      case 0x54:
      case 0x64:
      case 0x46:
      case 0x66:
      case 0x49:
      case 0x69:
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
    /** Bolt 5 element id; on Bolt 4.4 the decimal text of {@link #id}. */
    public final String elementId;
    public Node(long id, List<?> labels, Map<?, ?> properties) {
      this(id, labels, properties, Long.toString(id));
    }
    public Node(long id, List<?> labels, Map<?, ?> properties,
                String elementId) {
      super(0x4e, id, labels, properties, elementId);
      this.id = id;
      this.labels = labels;
      this.properties = properties;
      this.elementId = elementId;
    }
  }
  public static final class Relationship extends Structure {
    public final long id, startId, endId;
    public final String type;
    public final Map<?, ?> properties;
    /** Bolt 5 element ids; on Bolt 4.4 the decimal text of the integer ids. */
    public final String elementId, startElementId, endElementId;
    public Relationship(long id, long startId, long endId, String type,
                        Map<?, ?> properties) {
      this(id, startId, endId, type, properties, Long.toString(id),
           Long.toString(startId), Long.toString(endId));
    }
    public Relationship(long id, long startId, long endId, String type,
                        Map<?, ?> properties, String elementId,
                        String startElementId, String endElementId) {
      super(0x52, id, startId, endId, type, properties, elementId,
            startElementId, endElementId);
      this.id = id;
      this.startId = startId;
      this.endId = endId;
      this.type = type;
      this.properties = properties;
      this.elementId = elementId;
      this.startElementId = startElementId;
      this.endElementId = endElementId;
    }
  }
  public static final class UnboundRelationship extends Structure {
    public final long id;
    public final String type;
    public final Map<?, ?> properties;
    /** Bolt 5 element id; on Bolt 4.4 the decimal text of {@link #id}. */
    public final String elementId;
    public UnboundRelationship(long id, String type, Map<?, ?> properties) {
      this(id, type, properties, Long.toString(id));
    }
    public UnboundRelationship(long id, String type, Map<?, ?> properties,
                               String elementId) {
      super(0x72, id, type, properties, elementId);
      this.id = id;
      this.type = type;
      this.properties = properties;
      this.elementId = elementId;
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
  /**
   * A datetime in a named IANA zone. {@link #basis} says whether
   * {@link #seconds} counts local wall-clock epoch seconds (Bolt tag
   * {@code f}) or UTC epoch seconds (tag {@code i}). The value is sent in its
   * own basis on every protocol version.
   */
  public static final class ZonedDateTimeValue extends Structure {
    public enum Basis { LOCAL, UTC }
    public final long seconds, nanoseconds;
    public final String zoneId;
    public final Basis basis;
    /** Local-based value, as in 0.1: {@code seconds} are local epoch seconds. */
    public ZonedDateTimeValue(long seconds, long nanoseconds, String zoneId) {
      this(Basis.LOCAL, seconds, nanoseconds, zoneId);
    }
    public ZonedDateTimeValue(Basis basis, long seconds, long nanoseconds,
                              String zoneId) {
      super(basis == Basis.UTC ? 0x69 : 0x66, seconds, nanoseconds, zoneId);
      this.basis = Objects.requireNonNull(basis);
      this.seconds = seconds;
      this.nanoseconds = nanoseconds;
      this.zoneId = zoneId;
    }
    public static ZonedDateTimeValue local(long localSeconds, long nanoseconds,
                                           String zoneId) {
      return new ZonedDateTimeValue(Basis.LOCAL, localSeconds, nanoseconds,
                                    zoneId);
    }
    public static ZonedDateTimeValue utc(long utcSeconds, long nanoseconds,
                                         String zoneId) {
      return new ZonedDateTimeValue(Basis.UTC, utcSeconds, nanoseconds, zoneId);
    }
    /** UTC-based value keeping the zone ID of a {@code java.time} value. */
    public static ZonedDateTimeValue of(ZonedDateTime t) {
      return utc(t.toEpochSecond(), t.getNano(), t.getZone().getId());
    }
    /**
     * Resolves with the JDK time zone database. A local value in a DST overlap
     * takes the earlier offset; one in a gap moves forward.
     */
    public ZonedDateTime toZonedDateTime() {
      ZoneId zone = ZoneId.of(zoneId);
      int nanos = Math.toIntExact(nanoseconds);
      return basis == Basis.UTC
          ? Instant.ofEpochSecond(seconds, nanos).atZone(zone)
          : LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC)
                .atZone(zone);
    }
    /** Equal only when basis, seconds, nanoseconds and zone all match. */
    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ZonedDateTimeValue))
        return false;
      ZonedDateTimeValue z = (ZonedDateTimeValue)o;
      return basis == z.basis && seconds == z.seconds &&
          nanoseconds == z.nanoseconds && Objects.equals(zoneId, z.zoneId);
    }
    @Override
    public int hashCode() {
      return Objects.hash(basis, seconds, nanoseconds, zoneId);
    }
    @Override
    public String toString() {
      return "ZonedDateTimeValue(" + basis + ", " + seconds + ", " +
          nanoseconds + ", " + zoneId + ")";
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
  /** Bolt 4.4 form, as in 0.1. */
  static Object dehydrate(Object v) { return dehydrate(v, false); }
  /**
   * Converts a native input to its wire form. Offset datetimes use tag
   * {@code I} (UTC seconds) when {@code bolt5}, {@code F} (local seconds)
   * otherwise. BigDecimal becomes its exact plain text.
   */
  static Object dehydrate(Object v, boolean bolt5) {
    if (v instanceof java.math.BigDecimal)
      return ((java.math.BigDecimal)v).toPlainString();
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
      if (bolt5)
        return new Structure(0x49, t.toEpochSecond(), (long)t.getNano(),
                             (long)t.getOffset().getTotalSeconds());
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
        arity(f, 3, 4);
        return f.length == 3
            ? new Node((Long)f[0], (List<?>)f[1], (Map<?, ?>)f[2])
            : new Node((Long)f[0], (List<?>)f[1], (Map<?, ?>)f[2],
                       (String)f[3]);
      case 0x52:
        arity(f, 5, 8);
        return f.length == 5
            ? new Relationship((Long)f[0], (Long)f[1], (Long)f[2],
                               (String)f[3], (Map<?, ?>)f[4])
            : new Relationship((Long)f[0], (Long)f[1], (Long)f[2],
                               (String)f[3], (Map<?, ?>)f[4], (String)f[5],
                               (String)f[6], (String)f[7]);
      case 0x72:
        arity(f, 3, 4);
        return f.length == 3
            ? new UnboundRelationship((Long)f[0], (String)f[1],
                                      (Map<?, ?>)f[2])
            : new UnboundRelationship((Long)f[0], (String)f[1],
                                      (Map<?, ?>)f[2], (String)f[3]);
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
      case 0x49:
        arity(f, 3);
        return OffsetDateTime.ofInstant(
            Instant.ofEpochSecond((Long)f[0], (Long)f[1]),
            ZoneOffset.ofTotalSeconds(Math.toIntExact((Long)f[2])));
      case 0x66:
        arity(f, 3);
        return new ZonedDateTimeValue(ZonedDateTimeValue.Basis.LOCAL,
                                      (Long)f[0], (Long)f[1], (String)f[2]);
      case 0x69:
        arity(f, 3);
        return new ZonedDateTimeValue(ZonedDateTimeValue.Basis.UTC,
                                      (Long)f[0], (Long)f[1], (String)f[2]);
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
  static void arity(Object[] f, int... allowed) {
    for (int n : allowed)
      if (f.length == n)
        return;
    throw new IllegalArgumentException("Invalid structure field count");
  }
}
