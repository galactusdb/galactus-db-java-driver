package db.galactus;
import java.util.*;

/**
 * Lossless WKB for every supported geometry/geography shape, including
 * empties.
 */
public final class Spatial {
  public final String domain, layout, model;
  public final long srid;
  private final byte[] wkb;
  public Spatial(String domain, long srid, String layout, String model,
                 byte[] wkb) {
    if (!Arrays.asList("geometry", "geography").contains(domain) ||
        !Arrays.asList("XY", "XYZ").contains(layout) ||
        !Objects.equals(model,
                        domain.equals("geometry") ? "planar" : "greatCircle"))
      throw new IllegalArgumentException("Unsupported spatial metadata");
    this.domain = domain;
    this.srid = srid;
    this.layout = layout;
    this.model = model;
    this.wkb = wkb.clone();
  }
  public byte[] wkb() { return wkb.clone(); }
  public Map<String, Object> toMap() {
    return Map.of("$gdbType", "spatial", "version", 1L, "domain", domain,
                  "srid", srid, "layout", layout, "model", model, "wkb", wkb());
  }
  public static Spatial fromMap(Map<String, Object> m) {
    String payload = m.containsKey("wkbHex") ? "wkbHex" : "wkb";
    if (!Objects.equals(m.get("$gdbType"), "spatial") ||
        !Objects.equals(m.get("version"), 1L) ||
        !m.keySet().equals(Set.of("$gdbType", "version", "domain", "srid",
                                  "layout", "model", payload)))
      throw new IllegalArgumentException("Invalid spatial envelope");
    byte[] bytes;
    if (payload.equals("wkb"))
      bytes = (byte[])m.get(payload);
    else {
      String h = (String)m.get(payload);
      if (h.length() % 2 != 0)
        throw new IllegalArgumentException("Invalid WKB hex");
      bytes = new byte[h.length() / 2];
      for (int i = 0; i < bytes.length; i++) {
        int a = Character.digit(h.charAt(i * 2), 16),
            b = Character.digit(h.charAt(i * 2 + 1), 16);
        if (a < 0 || b < 0)
          throw new IllegalArgumentException("Invalid WKB hex");
        bytes[i] = (byte)(a * 16 + b);
      }
    }
    return new Spatial((String)m.get("domain"), (Long)m.get("srid"),
                       (String)m.get("layout"), (String)m.get("model"), bytes);
  }
  static Object hydrate(Map<String, Object> m) {
    return Objects.equals(m.get("$gdbType"), "spatial") &&
            Objects.equals(m.get("version"), 1L)
        ? fromMap(m)
        : m;
  }
}
