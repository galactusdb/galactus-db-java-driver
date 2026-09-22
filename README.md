# Galactus Java driver

[Galactus DB website](https://galactusdb.com) · [Source](https://github.com/galactusdb/galactus-db-java-driver) · [Type mapping](docs/BLUEPRINT.md) · [Spatial types](docs/SPATIAL.md)

Native Bolt 4.4 driver for Galactus DB. This experimental 0.1 implementation
has zero third-party runtime package dependencies. Source is available here;
no npm, NuGet, PyPI, Maven Central, or crates.io release is implied.

## How To

### 1. Get the driver

```sh
git clone --branch main https://github.com/galactusdb/galactus-db-java-driver.git
cd galactus-db-java-driver
```

Java 11+; no runtime dependencies. Install this checkout into your local Maven repository:

```sh
mvn install
```

Then add `db.galactus:galactus-db-java-driver:0.1.0` to your application dependencies.

### 2. Configure your connection

Start or obtain a Galactus DB instance; visit [galactusdb.com](https://galactusdb.com)
for database information. Use its Bolt address (locally, `bolt://127.0.0.1:7687`),
username, and configured password. The example reads `GDB_PASSWORD` from your
environment; it is an application variable, not a command to change the server password.

```sh
# Bash / zsh
export GDB_PASSWORD='your-database-password'
```

```powershell
# PowerShell
$env:GDB_PASSWORD = 'your-database-password'
```

### 3. Execute a parameterised query

```java
import db.galactus.Driver;
import java.util.Map;

try (Driver driver = new Driver("bolt://127.0.0.1:7687", "gdb",
                               System.getenv("GDB_PASSWORD"))) {
    Driver.Result result = driver.executeQuery("RETURN $name AS name", Map.of("name", "Ada"));
    System.out.println(result.records.get(0).get("name"));
}
```

The five-argument constructor also accepts database and timeout milliseconds
(`neo4j`, 30000 by default). `bolt+s://` uses JSSE with certificate and hostname
verification. `begin(boolean readOnly)`, `commit()`, `rollback()`, and `close()`
manage lifecycle. Calls are synchronized; use one connection per concurrent
transaction. Close rolls back, never commits.

Primitives, collections, string-keyed maps, arrays, and java.time types map
directly. Database integers return Long. Use `Types.Duration` for mixed calendar
durations, `Types.ZonedDateTimeValue` to retain an IANA zone name, and
`Types.Point2D`, `Types.Node`, etc. for graph/spatial structures. `Spatial` retains
all shape families as WKB with metadata. Map.of rejects null; use a HashMap or
LinkedHashMap when binding null values.

See [mapping](docs/BLUEPRINT.md), [spatial examples](docs/SPATIAL.md), and [scope](docs/OVERVIEW.md#scope-of-01).

### 4. Run the tests

From this repository's root:

```sh
mkdir -p .build/java
javac -d .build/java src/main/java/db/galactus/*.java src/test/java/db/galactus/*.java
java -cp .build/java db.galactus.DriverTest
```

See [test instructions](tests/README.md) for prerequisites and opt-in live tests.
Connection failures discard the connection; writes are never automatically retried.
Results are eager and each driver owns one connection; see the documented scope.

Learn more at [Galactus DB website](https://galactusdb.com).
