package db.galactus;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

public final class PackStream {
  public static final int MAX_MESSAGE = 64 * 1024 * 1024;
  private PackStream() {}
  /** Encodes in the Bolt 4.4 value forms, as 0.1 did. */
  public static byte[] encode(Object value) throws IOException {
    return encode(value, false);
  }
  /**
   * Encodes for the agreed protocol: {@code bolt5} writes offset datetimes as
   * {@code I} and graph values with element ids; otherwise the Bolt 4.4 forms
   * ({@code F}, no element ids) are written.
   */
  public static byte[] encode(Object value, boolean bolt5) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    write(new DataOutputStream(b), value, 0, bolt5);
    if (b.size() > MAX_MESSAGE)
      throw new IOException("Message exceeds 64 MiB");
    return b.toByteArray();
  }
  private static void header(DataOutputStream o, int n, int tiny, int base)
      throws IOException {
    if (tiny != 0 && n < 16)
      o.writeByte(tiny | n);
    else if (n < 256) {
      o.writeByte(base);
      o.writeByte(n);
    } else if (n < 65536) {
      o.writeByte(base + 1);
      o.writeShort(n);
    } else {
      o.writeByte(base + 2);
      o.writeInt(n);
    }
  }
  private static void write(DataOutputStream o, Object v, int depth,
                            boolean bolt5) throws IOException {
    if (depth >= 64)
      throw new IOException("Nesting exceeds 64 levels");
    v = Types.dehydrate(v, bolt5);
    if (v == null) {
      o.writeByte(0xc0);
      return;
    }
    if (v instanceof Boolean) {
      o.writeByte((Boolean)v ? 0xc3 : 0xc2);
      return;
    }
    if (v instanceof Byte || v instanceof Short || v instanceof Integer ||
        v instanceof Long || v instanceof java.math.BigInteger) {
      long n = v instanceof java.math.BigInteger
                   ? ((java.math.BigInteger)v).longValueExact()
                   : ((Number)v).longValue();
      if (n >= -16 && n <= 127)
        o.writeByte((int)n);
      else if (n >= -128 && n <= 127) {
        o.writeByte(0xc8);
        o.writeByte((int)n);
      } else if (n >= -32768 && n <= 32767) {
        o.writeByte(0xc9);
        o.writeShort((int)n);
      } else if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
        o.writeByte(0xca);
        o.writeInt((int)n);
      } else {
        o.writeByte(0xcb);
        o.writeLong(n);
      }
      return;
    }
    if (v instanceof Float || v instanceof Double) {
      o.writeByte(0xc1);
      o.writeDouble(((Number)v).doubleValue());
      return;
    }
    if (v instanceof String) {
      ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                               .onMalformedInput(CodingErrorAction.REPORT)
                               .encode(CharBuffer.wrap((String)v));
      byte[] b = new byte[encoded.remaining()];
      encoded.get(b);
      header(o, b.length, 0x80, 0xd0);
      o.write(b);
      return;
    }
    if (v instanceof byte[]) {
      byte[] b = (byte[])v;
      header(o, b.length, 0, 0xcc);
      o.write(b);
      return;
    }
    if (v instanceof Types.Structure) {
      Types.Structure s = (Types.Structure)v;
      List<Object> fields = s.fields;
      if (!bolt5 && (s instanceof Types.Node ||
                     s instanceof Types.UnboundRelationship))
        fields = fields.subList(0, 3);
      else if (!bolt5 && s instanceof Types.Relationship)
        fields = fields.subList(0, 5);
      if (fields.size() > 15 || s.tag < 0 || s.tag > 255)
        throw new IOException("Invalid structure");
      o.writeByte(0xb0 | fields.size());
      o.writeByte(s.tag);
      for (Object f : fields)
        write(o, f, depth + 1, bolt5);
      return;
    }
    if (v instanceof Map) {
      Map<?, ?> m = (Map<?, ?>)v;
      header(o, m.size(), 0xa0, 0xd8);
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!(e.getKey() instanceof String))
          throw new IOException("Map keys must be strings");
        write(o, e.getKey(), depth + 1, bolt5);
        write(o, e.getValue(), depth + 1, bolt5);
      }
      return;
    }
    if (v instanceof Collection) {
      Collection<?> c = (Collection<?>)v;
      header(o, c.size(), 0x90, 0xd4);
      for (Object f : c)
        write(o, f, depth + 1, bolt5);
      return;
    }
    if (v.getClass().isArray()) {
      int n = java.lang.reflect.Array.getLength(v);
      header(o, n, 0x90, 0xd4);
      for (int i = 0; i < n; i++)
        write(o, java.lang.reflect.Array.get(v, i), depth + 1, bolt5);
      return;
    }
    throw new IOException("Unsupported parameter type: " +
                          v.getClass().getName());
  }
  public static Object decode(byte[] data) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
    try {
      Object v = read(in, 0);
      if (in.available() != 0)
        throw new IOException("Trailing PackStream bytes");
      return v;
    } catch (RuntimeException e) {
      throw new IOException("Malformed PackStream", e);
    }
  }
  private static Object read(DataInputStream in, int depth) throws IOException {
    if (depth >= 64)
      throw new IOException("Nesting exceeds 64 levels");
    int m = in.readUnsignedByte();
    if (m <= 127)
      return (long)m;
    if (m >= 240)
      return (long)(byte)m;
    switch (m) {
    case 0xc0:
      return null;
    case 0xc2:
      return false;
    case 0xc3:
      return true;
    case 0xc1:
      return in.readDouble();
    case 0xc8:
      return (long)in.readByte();
    case 0xc9:
      return (long)in.readShort();
    case 0xca:
      return (long)in.readInt();
    case 0xcb:
      return in.readLong();
    }
    int kind = m & 0xf0, n = m & 15;
    if (m < 0x80 || m > 0xbf) {
      int base = 0;
      for (int b : new int[] {0xcc, 0xd0, 0xd4, 0xd8})
        if (m >= b && m <= b + 2) {
          base = b;
          break;
        }
      if (base == 0)
        throw new IOException("Unknown PackStream marker");
      kind = base == 0xcc   ? 0xcc
             : base == 0xd0 ? 0x80
             : base == 0xd4 ? 0x90
                            : 0xa0;
      n = m == base       ? in.readUnsignedByte()
          : m == base + 1 ? in.readUnsignedShort()
                          : in.readInt();
    }
    if (n < 0 || n > in.available())
      throw new IOException("Invalid collection size");
    if (kind == 0x80 || kind == 0xcc) {
      byte[] b = new byte[n];
      in.readFully(b);
      return kind == 0xcc ? b
                          : StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(b))
                                .toString();
    }
    if (kind == 0xb0) {
      int tag = in.readUnsignedByte();
      Object[] f = new Object[n];
      for (int i = 0; i < n; i++)
        f[i] = read(in, depth + 1);
      return Types.hydrate(tag, f);
    }
    if (kind == 0x90) {
      List<Object> list = new ArrayList<>();
      for (int i = 0; i < n; i++)
        list.add(read(in, depth + 1));
      return list;
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      Object key = read(in, depth + 1);
      if (!(key instanceof String) || map.containsKey(key))
        throw new IOException("Invalid or duplicate map key");
      map.put((String)key, read(in, depth + 1));
    }
    return Spatial.hydrate(map);
  }
}
