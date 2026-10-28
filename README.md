# Galactus DB Java Driver

[Galactus DB](https://galactusdb.com) · [Driver catalogue](https://galactusdb.com/product/drivers) · [Type mapping](docs/BLUEPRINT.md) · [Spatial guide](docs/SPATIAL.md) · [Changelog](CHANGELOG.md) · [Issues](https://github.com/galactusdb/galactus-db-java-driver/issues)

Connect Java applications to Galactus DB with parameterised Cypher, native
values and explicit transactions. The driver speaks Bolt 5.4 and agrees Bolt 4.4
with servers that offer only that. It uses the JDK alone, with **zero
third-party runtime package dependencies**, including for TLS.

**Status: 0.2 / early access.** Source is available on `main`. Version 0.2.0
adds Bolt 5, verified TLS straight to the Galactus server, recovery after
database errors, error classification, element ids and exact decimals. Each
driver owns one connection and returns eager results; install from source as
shown below and check the [scope of this release](#scope-of-this-release)
against your application.

## Contents

- [Quickstart](#quickstart)
- [Connection configuration](#connection-configuration)
- [Encrypted connections](#encrypted-connections)
- [Queries and results](#queries-and-results)
- [Transactions](#transactions)
- [Native type mapping](#native-type-mapping)
- [Spatial values](#spatial-values)
- [Errors and connection lifecycle](#errors-and-connection-lifecycle)
- [Testing](#testing)
- [Scope of this release](#scope-of-this-release)

## Quickstart

### 1. Get the source

Requirements: **Java 11+**, Git, and a reachable Galactus DB instance.

```sh
git clone --branch main https://github.com/galactusdb/galactus-db-java-driver.git
cd galactus-db-java-driver
```

Run this in the driver checkout.

```sh
mvn install
```

Then add the locally installed artifact to your application:

```xml
<dependency>
  <groupId>db.galactus</groupId>
  <artifactId>galactus-db-java-driver</artifactId>
  <version>0.2.0</version>
</dependency>
```

Git-based installs follow `main`; pin a reviewed commit in your application for
reproducible builds. No npm, NuGet, PyPI, Maven Central or crates.io availability
is implied by the package names.

### 2. Configure your database credentials

Start Galactus DB on your infrastructure using the setup available from
[galactusdb.com](https://galactusdb.com). The examples use
`bolt://127.0.0.1:7687` and username `gdb`; replace them with your server settings.
Set your configured password in the application environment:

```sh
# Bash / zsh
export GDB_PASSWORD='your-database-password'
```

```powershell
# PowerShell
$env:GDB_PASSWORD = 'your-database-password'
```

`GDB_PASSWORD` is read by these examples; the driver does not automatically load
environment variables or change the server password. Keep credentials out of
source control and connection URIs.

### 3. Run your first query

```java
import db.galactus.Driver;
import java.util.Map;

try (Driver driver = new Driver("bolt://127.0.0.1:7687", "gdb",
                               System.getenv("GDB_PASSWORD"))) {
    Driver.Result result = driver.executeQuery("RETURN $name AS name", Map.of("name", "Ada"));
    System.out.println(result.records.get(0).get("name"));
    System.out.println("Bolt " + driver.protocolVersion());
}
```

The following examples reuse an open `driver` within the same resource scope.
Java examples belong inside a method or a JShell session. Adapt error handling to your application.

## Connection configuration

`new Driver(uri, username, password)` uses the server's default database and a
30,000 ms timeout. Pass `Driver.Options` for anything else:

```java
Driver.Options options = new Driver.Options()
    .database("app")
    .timeoutMillis(10_000);
try (Driver driver = new Driver("bolt://127.0.0.1:7687", "gdb",
                               System.getenv("GDB_PASSWORD"), options)) {
    // ...
}
```

The 0.1 five-argument constructor `(uri, username, password, database,
timeoutMillis)` still works. Close the driver with try-with-resources.

| Setting | Behaviour |
|---|---|
| URI | `bolt://host:port` (plaintext) or `bolt+s://host:port` (verified TLS); omitted port is 7687 |
| Authentication | Required username and password; no credentials in the URI |
| Database | Empty/omitted database delegates selection to the server’s configured default |
| Named database | `Options.database("app")` for an existing database |
| Timeout | `Options.timeoutMillis`, 30,000 ms by default |
| Trusted certificates | `Options.trustedCertificates(path)`, `bolt+s://` only; see below |
| Result handling | Records are buffered in memory until the query completes |

The driver offers Bolt 5.4 down to 5.0 and Bolt 4.4, and the server picks the
highest version both support. After connecting, `protocolVersion()` returns the
agreed version (`"5.4"` or `"4.4"`), `serverAgent()` the server's agent string
and `connectionId()` the server's id for this connection, which helps when
matching server logs.

Timeouts bound socket waits, not necessarily total query duration or DNS lookup.
Calls are synchronised. Use separate drivers for concurrent transactions.

## Encrypted connections

A Galactus server started with `GDB_TLS_CERT` and `GDB_TLS_KEY` serves TLS
itself, on its usual Bolt port. Connect with `bolt+s://` and the driver verifies
the certificate chain and the host name from the URI, and sends that name for
SNI. It uses JSSE from the JDK.

By default the JDK's trust store decides which certificates are trusted. For a
private CA or a self-signed development certificate, point the driver at a PEM
file holding one or more certificates; it then trusts those certificates
instead of the JDK store:

```java
Driver.Options options = new Driver.Options()
    .trustedCertificates(java.nio.file.Path.of("/etc/galactus/ca.pem"));
try (Driver driver = new Driver("bolt+s://db.internal:7687", "gdb",
                               System.getenv("GDB_PASSWORD"), options)) {
    // ...
}
```

Verification is always on: there is no option to skip it, and the driver never
falls back from `bolt+s://` to plaintext. Setting `trustedCertificates` on a
`bolt://` URI is a configuration error (`IllegalArgumentException`).
`bolt+ssc://` and the routing schemes `neo4j://`, `neo4j+s://` and
`neo4j+ssc://` are rejected.

## Queries and results

Bind data as parameters, never interpolate user input into Cypher:

```java
Driver.Result result = driver.executeQuery(
    "CREATE (p:Person {name:$name, age:$age}) RETURN p.name AS name, p.age AS age",
    Map.of("name", "Ada", "age", 37L));
System.out.println(result.records.get(0).get("name"));
System.out.println(result.summary);
```

Results expose column keys, records indexed by column name, and server summary
metadata. Read a column with `result.records.get(0).get("name")`. Use unique column aliases because
records are maps. Summary fields can include the database, query classification,
timings, bookmark and update statistics; only fields supplied by the server are
present. A query without rows still returns its summary. `driver.lastBookmark()`
returns the most recent bookmark from an autocommit query or a commit.

Lists and maps can be nested. Values are recursively encoded and hydrated,
including graph properties and spatial envelopes. Use Cypher `LIMIT` or
application-level pagination for large result sets: results are read eagerly
rather than through a lazy cursor.

## Transactions

Queries outside an explicit transaction autocommit. Group related writes with
begin/commit; explicitly roll back when abandoning work:

```java
driver.begin(false);
driver.executeQuery("CREATE (:Person {name:$name})", Map.of("name", "Ada"));
driver.executeQuery("CREATE (:Person {name:$name})", Map.of("name", "Grace"));
driver.commit();
// To deliberately abandon an open transaction: driver.rollback();
```

The transaction belongs to the connection. Keep both statements on the same
driver and finish the transaction before reusing it for unrelated work. Closing
a driver rolls back an unfinished transaction; it never implicitly commits. The
read-only argument requests a read transaction.

When a query inside a transaction fails with a database error, the server rolls
the transaction back and the driver marks it closed. The driver itself stays
open: start a new transaction, or run autocommit queries, straight away.

## Native type mapping

`Long`, `Double`, `Boolean`, `String`, `byte[]`, `List<Object>`, and `Map<String,Object>`. `java.time` inputs, arrays, collections and checked `BigInteger` work directly. Named types live under `Types`; `Spatial` is a top-level class. Use a mutable map to bind nulls because `Map.of` rejects them.

- Database integers are signed 64-bit; out-of-range integer inputs fail.
- Bytes stay binary; values do not pass through JSON.
- `OffsetDateTime` keeps its instant and offset on both Bolt 5 and Bolt 4.4.
- Named-zone datetimes arrive as `Types.ZonedDateTimeValue`, which keeps the
  IANA zone name and nanoseconds. Its `basis` says whether `seconds` counts
  local wall-clock time or UTC; build one with `ZonedDateTimeValue.local(...)`,
  `ZonedDateTimeValue.utc(...)` or `ZonedDateTimeValue.of(zonedDateTime)`, and
  turn it into a `java.time.ZonedDateTime` with `toZonedDateTime()`. A plain
  `ZonedDateTime` parameter is sent with its resolved offset.
- Calendar duration keeps months, days, seconds and nanoseconds separately.
- Graph nodes, relationships and paths have named result types. Each node and
  relationship has an `elementId` string, and relationships also have
  `startElementId` and `endElementId`; on Galactus these are the decimal text
  of the integer ids. Bind ids or properties as parameters; the graph entities
  themselves are result-only.
- `DECIMAL` values arrive as strings holding their exact digits. Send a
  `BigDecimal` and the driver writes its exact plain text; convert it in Cypher
  with `decimal($amount)`.
- Unknown wire structures remain explicit structures and cannot be used as
  generic application-object serialisation.

```java
Driver.Result r = driver.executeQuery("RETURN decimal($amount) AS amount",
    Map.of("amount", new java.math.BigDecimal("12.30")));
java.math.BigDecimal amount = new java.math.BigDecimal((String) r.records.get(0).get("amount"));
```

No ORM or automatic arbitrary-object mapping is included. Convert application
models and UUIDs explicitly to a supported format. See the
[complete type mapping](docs/BLUEPRINT.md) for precision and range details.

## Spatial values

The driver supports `Point2D`, `Point3D`, and a native `Spatial` value covering
every shape family currently supported by Galactus DB:

| Shape family | Geometry | Geography |
|---|---|---|
| Point / LineString / Polygon, including holes | Yes | Yes |
| MultiPoint / MultiLineString / MultiPolygon | Yes | Yes |
| GeometryCollection, including nested collections | Yes | Yes |
| XY, XYZ, and typed empty shapes | Yes | Yes |

`Spatial` preserves WKB bytes together with domain, SRID, coordinate layout and
model. Geometry uses `planar`; geography uses `greatCircle`. Neither domain nor
SRID is inferred by converting through GeoJSON.

```java
db.galactus.Spatial shape = (db.galactus.Spatial) driver.executeQuery(
    "RETURN spatial.fromWKT($wkt, {domain:$domain}) AS shape",
    Map.of("wkt", "POLYGON((0 0,4 0,4 4,0 4,0 0))", "domain", "geometry")
).records.get(0).get("shape");
driver.executeQuery(
    "CREATE (:Region {name:$name, shape:spatial.fromMap($shape)})",
    Map.of("name", "Square", "shape", shape));
System.out.println(shape.domain + " / " + shape.srid + " / " + shape.layout);
```

Binding `Spatial` sends a lossless envelope. Use `spatial.fromMap($shape)` to
promote it into a database spatial value. `RETURN $shape` alone echoes the map;
it does not change the server’s value type. To import external bytes or text,
use `spatial.fromWKB`, `spatial.fromWKT`, or `spatial.fromGeoJSON` with parameters.
The server performs geometry/topology validation. M/ZM, curves, surfaces and
solids are outside the current database scope. See the [spatial guide](docs/SPATIAL.md).

## Errors and connection lifecycle

Server errors raise `Driver.DatabaseException`. It keeps the server's `code`,
`getMessage()` and `metadata`, and splits the code into `classification`,
`category` and `title`. `isClientError()`, `isTransient()` and
`isDatabaseError()` report the classification, and `isRetryable()` is true for
transient errors. `ErrorCodes` holds the common codes as constants:

```java
try {
    driver.executeQuery("CREATE (:Account {email:$email})", Map.of("email", "ada@example.com"));
} catch (Driver.DatabaseException e) {
    if (ErrorCodes.ConstraintValidationFailed.equals(e.code)) {
        // The email is already registered.
    } else {
        throw e;
    }
}
// The same driver is ready for the next query.
```

After a database error the driver resets the connection, so the same driver
runs the next query. A parameter the driver cannot encode raises before
anything is sent, and the connection stays open. The driver never retries anything on its own; when
`isRetryable()` is true, your application decides whether to run the work again.

The connection is closed when reading or writing fails (network, timeout or
TLS), when the server breaks the protocol, or when authentication fails. Open a
new driver after any of these. If the connection drops during `commit()`, the
outcome of that commit is unknown: check the data before running the write
again. A `DatabaseException` from `commit()` means the transaction did not
commit.

| Symptom | Check |
|---|---|
| Connection refused | Host, port, listener and network reachability |
| Authentication rejected | Database username and configured password |
| Database not found | Explicit database name; omit it to use the server default |
| TLS handshake fails | `bolt+s://` against a TLS-enabled server, the trust chain (`trustedCertificates`) and the host name in the certificate |
| Unsupported parameter | Convert the object to a supported scalar/container or named value |
| Driver is closed after an error | A network, TLS, protocol or authentication failure closed it; open a new driver |

Credentials and parameters are not logged by the driver. Avoid writing them to
application logs when reporting errors.

## Testing

Run from the repository root:

```sh
mkdir -p .build/java
javac -d .build/java src/main/java/db/galactus/*.java src/test/java/db/galactus/*.java
java -cp .build/java db.galactus.DriverTest
```

Fixtures and tests are self-contained. Without `GDB_TEST_URI`, live database
tests are skipped. For integration tests, use a fresh disposable server and
set `GDB_TEST_URI` and `GDB_TEST_PASSWORD`; tests create data, a uniqueness
constraint and exercise transactions. Reset the disposable database before
repeating the suite.

See [test instructions](tests/README.md) for the environment variables, the TLS
setup and the full list of checks. The live suite covers 57 spatial cases,
integer boundaries, native values, large chunked payloads, transactions, element
ids, datetimes on both protocols, recovery after errors, error codes, decimals
and verified TLS.

## Scope of this release

Version 0.2.0 provides direct connections over Bolt 5.4 or 4.4, verified TLS,
basic authentication, parameterised queries, eager records and summaries,
explicit transactions, recovery after database errors, classified errors,
native type mapping and full spatial interchange. Each driver owns one
connection. Connection pools, routing, sessions that send bookmarks, lazy
cursors, cancellation, managed retries and asynchronous APIs are outside this
release. Messages are capped at 64 MiB and nesting at 64 levels; aggregate
result memory can exceed a single message.

See [the shared blueprint](docs/BLUEPRINT.md) and
[implementation overview](docs/OVERVIEW.md) for the precise contract and the
platforms tested.

## Feedback and contribution

Use [GitHub issues](https://github.com/galactusdb/galactus-db-java-driver/issues) for reproducible
bugs and focused feature requests. Include runtime/OS versions, driver commit,
expected behaviour and a small reproduction with credentials and sensitive
data removed. Keep changes dependency-free and run the relevant tests before
opening a pull request against `main`.

Visit [galactusdb.com](https://galactusdb.com) for Galactus DB, or browse the
[other language drivers](https://galactusdb.com/product/drivers).
