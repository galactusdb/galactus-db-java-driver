package db.galactus;
import java.math.BigDecimal;
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
  static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b)
      s.append(String.format("%02x", x));
    return s.toString();
  }
  static List<String> lines(String name) throws Exception {
    return Files.readAllLines(Paths.get("tests", "fixtures", name));
  }
  public static void main(String[] args) throws Exception {
    int codec = 0;
    // values.tsv round-trips in 4.4 mode (also the 0.1 default encoder).
    for (String line : lines("values.tsv")) {
      String[] p = line.split("\t");
      byte[] b = unhex(p[1]);
      check(Arrays.equals(b, PackStream.encode(PackStream.decode(b), false)),
            p[0]);
      check(Arrays.equals(b, PackStream.encode(PackStream.decode(b))),
            p[0] + " (default encoder)");
      codec++;
    }
    // bolt5.tsv round-trips in Bolt 5 mode.
    for (String line : lines("bolt5.tsv")) {
      String[] p = line.split("\t");
      byte[] b = unhex(p[1]);
      check(Arrays.equals(b, PackStream.encode(PackStream.decode(b), true)),
            p[0] + " (Bolt 5)");
      codec++;
    }
    // F and I decode to equal values; encoders follow the protocol.
    for (String line : lines("datetime_pairs.tsv")) {
      String[] p = line.split("\t");
      Object legacy = PackStream.decode(unhex(p[0]));
      Object utc = PackStream.decode(unhex(p[1]));
      check(legacy instanceof OffsetDateTime && legacy.equals(utc),
            "datetime pair decode " + line);
      check(hex(PackStream.encode(legacy, false)).equals(p[0]) &&
                hex(PackStream.encode(utc, false)).equals(p[0]),
            "datetime pair 4.4 encode " + line);
      check(hex(PackStream.encode(legacy, true)).equals(p[1]) &&
                hex(PackStream.encode(utc, true)).equals(p[1]),
            "datetime pair Bolt 5 encode " + line);
      codec++;
    }
    for (String h : lines("malformed.tsv")) {
      boolean failed = false;
      try {
        PackStream.decode(unhex(h));
      } catch (Exception e) {
        failed = true;
      }
      check(failed, "malformed");
      codec++;
    }
    // Bolt 4.4 graph values get element ids from their integer ids.
    Types.Node node4 =
        (Types.Node)PackStream.decode(unhex("b34e0191865065727" +
                                            "36f6ea0"));
    check(node4.elementId.equals("1"), "4.4 node element id");
    Types.Relationship rel4 = (Types.Relationship)PackStream.decode(
        unhex("b5520201038552454c4154a0"));
    check(rel4.elementId.equals("2") && rel4.startElementId.equals("1") &&
              rel4.endElementId.equals("3"),
          "4.4 relationship element ids");
    // Zoned values compare by basis as well as seconds.
    Object zonedUtc =
        PackStream.decode(unhex("b36900008d4575726f70652f4c6f6e646f6e"));
    check(zonedUtc.equals(
              Types.ZonedDateTimeValue.utc(0, 0, "Europe/London")) &&
              !zonedUtc.equals(
                  Types.ZonedDateTimeValue.local(0, 0, "Europe/London")),
          "zoned basis equality");
    // BigDecimal is sent as its exact plain text.
    check(hex(PackStream.encode(new BigDecimal("1.20E+3")))
                  .equals("8431323030") &&
              hex(PackStream.encode(new BigDecimal("12.30")))
                  .equals("8531322e3330"),
          "BigDecimal plain text");
    System.out.println("Java codec tests passed (" + codec + " fixture rows)");

    String uri = System.getenv("GDB_TEST_URI");
    if (uri == null) {
      System.out.println("Java live tests skipped (GDB_TEST_URI not set)");
      return;
    }
    String password = System.getenv("GDB_TEST_PASSWORD");
    String caFile = System.getenv("GDB_TEST_CA_FILE");
    String expectBolt = System.getenv("GDB_TEST_EXPECT_BOLT");
    Driver.Options options = new Driver.Options();
    if (caFile != null && !caFile.isEmpty())
      options.trustedCertificates(Paths.get(caFile));
    try (Driver d = new Driver(uri, "gdb", password, options)) {
      if (expectBolt != null)
        check(expectBolt.equals(d.protocolVersion()),
              "protocol " + d.protocolVersion() + ", expected " + expectBolt);
      check(d.serverAgent() != null && d.connectionId() != null,
            "server agent and connection id");
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
      check(d.lastBookmark() == null || !d.lastBookmark().isEmpty(),
            "bookmark");

      // Element ids equal the decimal id text on every protocol version.
      Map<String, Object> graph =
          d.executeQuery("CREATE (a:DriverJavaElement)-[r:DRIVER_JAVA_LINK]->"
                         + "(b:DriverJavaElement) RETURN a, r, b")
              .records.get(0);
      Types.Node a = (Types.Node)graph.get("a"), b = (Types.Node)graph.get("b");
      Types.Relationship r = (Types.Relationship)graph.get("r");
      check(a.elementId.equals(Long.toString(a.id)) &&
                b.elementId.equals(Long.toString(b.id)) &&
                r.elementId.equals(Long.toString(r.id)) &&
                r.startElementId.equals(a.elementId) &&
                r.endElementId.equals(b.elementId),
            "element ids");

      // Offset and named-zone datetimes round-trip through RETURN $v.
      OffsetDateTime offset = OffsetDateTime.of(2026, 10, 28, 9, 30, 15,
                                                123456789, ZoneOffset.ofHours(-5));
      check(offset.equals(d.executeQuery("RETURN $v AS v", Map.of("v", offset))
                              .records.get(0)
                              .get("v")),
            "offset datetime round trip");
      ZonedDateTime london =
          ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 5, ZoneId.of("Europe/London"));
      List<Types.ZonedDateTimeValue> zonedInputs = List.of(
          Types.ZonedDateTimeValue.of(london),
          Types.ZonedDateTimeValue.local(
              london.toLocalDateTime().toEpochSecond(ZoneOffset.UTC), 5,
              "Europe/London"));
      for (Types.ZonedDateTimeValue zoned : zonedInputs) {
        Types.ZonedDateTimeValue back =
            (Types.ZonedDateTimeValue)d
                .executeQuery("RETURN $v AS v", Map.of("v", zoned))
                .records.get(0)
                .get("v");
        check(back.zoneId.equals("Europe/London") &&
                  back.toZonedDateTime().equals(london),
              "named-zone datetime round trip " + zoned + " -> " + back);
      }

      // A database error resets the connection; the same driver carries on.
      Driver.DatabaseException error = null;
      try {
        d.executeQuery("INVALID QUERY");
      } catch (Driver.DatabaseException e) {
        error = e;
      }
      check(error != null, "database error");
      check(error.isClientError() && !error.isTransient() &&
                !error.isDatabaseError() && !error.isRetryable() &&
                "ClientError".equals(error.classification) &&
                error.category != null && error.title != null &&
                error.code.endsWith("." + error.category + "." + error.title),
            "error classification " + error.code);
      check(d.executeQuery("RETURN 1 AS one").records.get(0).get("one").equals(
                1L),
            "usable after database error");

      // A failure inside a transaction closes it; autocommit then works.
      d.begin(false);
      d.executeQuery("CREATE (:DriverJavaTx)");
      boolean failed = false;
      try {
        d.executeQuery("INVALID QUERY");
      } catch (Driver.DatabaseException e) {
        failed = true;
      }
      check(failed, "database error in transaction");
      boolean closed = false;
      try {
        d.rollback();
      } catch (IllegalStateException e) {
        closed = true;
      }
      check(closed, "transaction closed after failure");
      check(d.executeQuery("MATCH (n:DriverJavaTx) RETURN count(n) AS n")
                .records.get(0)
                .get("n")
                .equals(0L),
            "failed transaction rolled back");

      // A unique constraint violation carries the published code.
      d.executeQuery(
          "CREATE CONSTRAINT FOR (n:DriverJavaUnique) REQUIRE n.key IS UNIQUE");
      d.executeQuery("CREATE (:DriverJavaUnique {key: 'k'})");
      Driver.DatabaseException violation = null;
      try {
        d.executeQuery("CREATE (:DriverJavaUnique {key: 'k'})");
      } catch (Driver.DatabaseException e) {
        violation = e;
      }
      check(violation != null &&
                ErrorCodes.CONSTRAINT_VALIDATION_FAILED.equals(violation.code) &&
                "Schema".equals(violation.category) &&
                "ConstraintValidationFailed".equals(violation.title),
            "constraint violation " +
                (violation == null ? "none" : violation.code));

      // BigDecimal goes out as plain text; the decimal comes back as a string.
      check("12.30".equals(
                d.executeQuery("RETURN decimal($d) AS d",
                               Map.of("d", new BigDecimal("12.30")))
                    .records.get(0)
                    .get("d")),
            "decimal");

      // A rejected parameter raises before sending and keeps the connection.
      int rejected = 0;
      for (Object bad : List.of(new Object(), a)) {
        try {
          d.executeQuery("RETURN $p AS p", Map.of("p", bad));
        } catch (java.io.IOException | IllegalArgumentException e) {
          check(!(e instanceof Driver.DatabaseException), "client-side reject");
          rejected++;
        }
      }
      check(rejected == 2 && d.executeQuery("RETURN 3 AS three")
                                 .records.get(0)
                                 .get("three")
                                 .equals(3L),
            "usable after rejected parameter");
    }

    if (uri.startsWith("bolt+s://")) {
      boolean untrusted = false;
      try (Driver plain = new Driver(uri, "gdb", password)) {
      } catch (javax.net.ssl.SSLException e) {
        untrusted = true;
      }
      check(untrusted, "untrusted certificate rejected");
      boolean configError = false;
      try (Driver wrong = new Driver(uri.replace("bolt+s://", "bolt://"), "gdb",
                                     password, options)) {
      } catch (IllegalArgumentException e) {
        configError = true;
      }
      check(configError, "trustedCertificates on bolt:// rejected");
    }

    boolean failed = false;
    try (Driver bad = new Driver(uri, "gdb", "wrong-password", options)) {
    } catch (Driver.DatabaseException e) {
      failed = true;
    }
    check(failed, "auth failure");
    System.out.println("Java live tests passed (Bolt " + expectBolt + ", " +
                       uri.substring(0, uri.indexOf(':')) + ")");
  }
}
