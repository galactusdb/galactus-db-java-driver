# Changelog

## 0.2.0 - 28/10/2026

### Added

- Bolt 5.0 to 5.4, with Bolt 4.4 when the server offers only that. From 5.1
  HELLO carries `bolt_agent` and LOGON sends the credentials.
- `protocolVersion()`, `serverAgent()`, `connectionId()` and `lastBookmark()`.
- `Driver.Options` with `database`, `timeoutMillis` and `trustedCertificates`,
  a PEM file of one or more certificates that replaces the JDK trust store for
  `bolt+s://`. Setting it on `bolt://` is a configuration error.
- Verified TLS straight to a Galactus server started with `GDB_TLS_CERT` and
  `GDB_TLS_KEY`, with host name verification and SNI from the URI host.
- `elementId` on nodes, relationships and unbound relationships, and
  `startElementId` / `endElementId` on relationships. On Bolt 4.4 they hold the
  decimal text of the integer ids.
- `DatabaseException.classification`, `category` and `title`, plus
  `isClientError()`, `isTransient()`, `isDatabaseError()` and `isRetryable()`.
- `ErrorCodes` constants, such as `ErrorCodes.CONSTRAINT_VALIDATION_FAILED`.
- `BigDecimal` parameters, sent as `toPlainString()` text for `decimal($param)`.
- `ZonedDateTimeValue.Basis` (local or UTC), with `local`, `utc` and `of`
  factories, `toZonedDateTime()`, and equality on basis, seconds, nanoseconds
  and zone.

### Changed

- A database error no longer closes the driver. The driver sends RESET, marks
  any open transaction closed (the server rolls it back) and raises the error;
  the same driver runs the next query. I/O, TLS and protocol failures and
  failed authentication still close it.
- A parameter the driver cannot encode raises before anything is sent and no
  longer closes the driver.
- Offset datetimes are sent as Bolt 5 UTC datetimes (`I`) on a 5.x connection
  and as legacy datetimes (`F`) on 4.4. Both forms decode to the same
  `OffsetDateTime`.
- The user agent is `galactus-db-java-driver/0.2.0`.
- `PackStream.encode(value, bolt5)` selects the protocol's value forms;
  `PackStream.encode(value)` keeps the 4.4 forms.

## 0.1.0

- First release: Bolt 4.4, parameterised queries, explicit transactions,
  native type mapping and full spatial interchange.
