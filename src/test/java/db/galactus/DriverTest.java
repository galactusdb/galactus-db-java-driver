package db.galactus;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class DriverTest {
  static void check(boolean b, String name) {
    if (!b)
      throw new AssertionError(name);
  }
  static byte[] unhex(String h) {
    byte[] b = new byte[h.length() / 2];
    for (int i = 0; i < b.length; i++)
      b[i] = (byte)Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
    return b;
  }
  static List<String> lines(String name) throws Exception {
    return Files.readAllLines(Paths.get("tests", "fixtures", name));
  }
  public static void main(String[] args) throws Exception {
    for (String line : lines("values.tsv")) {
      String[] p = line.split("\t");
      byte[] b = unhex(p[1]);
      check(Arrays.equals(b, PackStream.encode(PackStream.decode(b))), p[0]);
    }
    for (String h : lines("malformed.tsv")) {
      boolean failed = false;
      try {
        PackStream.decode(unhex(h));
      } catch (Exception e) {
        failed = true;
      }
      check(failed, "malformed");
    }
    String uri = System.getenv("GDB_TEST_URI");
    if (uri == null) {
      System.out.println("Java codec passed; live skipped");
      return;
    }
    try (Driver d =
             new Driver(uri, "gdb", System.getenv("GDB_TEST_PASSWORD"))) {
      OffsetDateTime dt = OffsetDateTime.of(1960, 1, 1, 1, 2, 3, 456789123,
                                            ZoneOffset.ofHoursMinutes(5, 30));
      Map<String, Object> v =
          Map.of("n", Long.MAX_VALUE, "b", new byte[] {0, -1}, "s",
                 "x".repeat(70000), "t", dt);
      Map<?, ?> got =
          (Map<?, ?>)d.executeQuery("RETURN $v AS v", Map.of("v", v))
              .records.get(0)
              .get("v");
      check(got.get("n").equals(Long.MAX_VALUE) &&
                Arrays.equals((byte[])got.get("b"), (byte[])v.get("b")) &&
                got.get("s").equals(v.get("s")) && got.get("t").equals(dt),
            "native mapping");
      for (String line : lines("spatial.tsv")) {
        String[] p = line.split("\t");
        Spatial s =
            (Spatial)d
                .executeQuery(
                    "RETURN spatial.fromWKT($wkt,{domain:$domain}) AS shape",
                    Map.of("domain", p[0], "wkt", p[1]))
                .records.get(0)
                .get("shape");
        Map<String, Object> row =
            d.executeQuery(
                 "RETURN spatial.fromMap($s) AS shape, $nested AS nested",
                 Map.of("s", s, "nested", List.of(Map.of("shape", s))))
                .records.get(0);
        Spatial echoed = (Spatial)row.get("shape"),
                nested =
                    (Spatial)((Map<?, ?>)((List<?>)row.get("nested")).get(0))
                        .get("shape");
        check(Arrays.equals(s.wkb(), echoed.wkb()) &&
                  Arrays.equals(s.wkb(), nested.wkb()) &&
                  s.domain.equals(echoed.domain) && s.srid == echoed.srid &&
                  s.layout.equals(echoed.layout),
              line);
      }
      d.begin(false);
      d.executeQuery("CREATE (:DriverJava {n:1})");
      d.rollback();
      check(d.executeQuery("MATCH (n:DriverJava) RETURN count(n) AS n")
                .records.get(0)
                .get("n")
                .equals(0L),
            "rollback");
      d.begin(false);
      d.executeQuery("CREATE (:DriverJava {n:2})");
      d.commit();
      check(((Types.Node)d.executeQuery("MATCH (n:DriverJava) RETURN n")
                 .records.get(0)
                 .get("n"))
                .properties.get("n")
                .equals(2L),
            "commit");
      boolean failed = false;
      try {
        d.executeQuery("INVALID QUERY");
      } catch (Driver.DatabaseException e) {
        failed = true;
      }
      check(failed, "database error");
    }
    boolean failed = false;
    try (Driver bad = new Driver(uri, "gdb", "wrong-password")) {
    } catch (Driver.DatabaseException e) {
      failed = true;
    }
    check(failed, "auth failure");
    System.out.println("Java codec and live tests passed");
  }
}
