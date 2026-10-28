# galactus-db-java-driver tests

[Galactus DB website](https://galactusdb.com) · [Driver Quickstart](../README.md)

## Quickstart

Run these commands from the repository root:

```sh
mkdir -p .build/java
javac -d .build/java src/main/java/db/galactus/*.java src/test/java/db/galactus/*.java
java -cp .build/java db.galactus.DriverTest
```

The fixtures are included in `tests/fixtures`; no sibling repository is needed.
The tests use standard runtime/test facilities, with no test framework packages.

The codec tests need no server:

- `values.tsv` round-trips through the Bolt 4.4 encoder: integer boundaries,
  UTF-8, temporal values, graph values, points and unknown structures.
- `bolt5.tsv` round-trips through the Bolt 5 encoder: nodes and relationships
  with element ids, UTC (`I`) datetimes and both bases of named-zone datetimes.
- `datetime_pairs.tsv` pairs a legacy (`F`) and a UTC (`I`) encoding of the same
  offset datetime. Both decode to equal values; the 4.4 encoder writes the first
  column and the Bolt 5 encoder the second.
- `malformed.tsv` inputs must be rejected.

The spatial fixture contains 57 geometry/geography cases, including XY/XYZ,
empties, holes, multipart shapes, nested collections, and antimeridian
geography; it runs against a live server.

### Live database tests

Without `GDB_TEST_URI`, live tests are skipped. To include them, start a **fresh,
disposable** Galactus database and set these environment variables before running
the same test command:

| Variable | Meaning |
|---|---|
| `GDB_TEST_URI` | `bolt://host:port` or `bolt+s://host:port` |
| `GDB_TEST_PASSWORD` | Password for user `gdb` |
| `GDB_TEST_EXPECT_BOLT` | Optional; the protocol the driver must agree, `5.4` or `4.4` |
| `GDB_TEST_CA_FILE` | Optional; a PEM file passed as `trustedCertificates` for `bolt+s://` |

```sh
export GDB_TEST_URI='bolt://127.0.0.1:7687'
export GDB_TEST_PASSWORD='your-disposable-database-password'
export GDB_TEST_EXPECT_BOLT='5.4'
```

```powershell
$env:GDB_TEST_URI = 'bolt://127.0.0.1:7687'
$env:GDB_TEST_PASSWORD = 'your-disposable-database-password'
$env:GDB_TEST_EXPECT_BOLT = '5.4'
```

The live suite checks:

- the agreed protocol, server agent and connection id;
- native values, a 70,000-byte string spanning several Bolt chunks, and the 57
  spatial cases echoed and nested;
- explicit rollback and commit;
- node and relationship element ids against their integer ids;
- offset and `Europe/London` named-zone datetimes through `RETURN $v`;
- a failing query raising a classified `DatabaseException`, after which the
  same driver runs `RETURN 1`;
- a failure inside an explicit transaction closing the transaction and rolling
  back its writes, with autocommit working afterwards;
- a unique constraint violation reporting
  `ErrorCodes.CONSTRAINT_VALIDATION_FAILED`;
- `RETURN decimal($d)` with `BigDecimal("12.30")` returning `"12.30"`;
- unsupported parameters (a plain object, a returned node) raising before
  anything is sent, with the driver still usable;
- with a `bolt+s://` URI, that connecting without the trusted certificate fails
  TLS verification and that `trustedCertificates` on `bolt://` is rejected;
- rejected authentication.

Tests use the server's default database and create labels starting with
`DriverJava` plus a uniqueness constraint. Do not point them at valuable data,
and reset the disposable database between runs.

The workspace runner `tests/run_live.py` (in the multi-driver workspace) runs
the suite against fresh in-memory servers in three passes: Bolt 5.4, a server
capped with `GDB_BOLT_MAX_VERSION=4.4`, and `bolt+s://localhost` against a
server started with `GDB_TLS_CERT` and `GDB_TLS_KEY` and a throwaway test CA.
See [implementation scope](../docs/OVERVIEW.md#scope-of-02).

Database information: [Galactus DB website](https://galactusdb.com).

In PowerShell, create `.build/java` with `New-Item -ItemType Directory -Force .build/java`.
