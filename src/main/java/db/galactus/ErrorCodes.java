package db.galactus;

/**
 * Server error codes applications commonly act on. Compare them with
 * {@link Driver.DatabaseException#code}. Codes the server adds later still
 * arrive as plain strings.
 */
public final class ErrorCodes {
  private ErrorCodes() {}
  public static final String CONSTRAINT_VALIDATION_FAILED =
      "Galactus.ClientError.Schema.ConstraintValidationFailed";
  public static final String TYPE_ERROR =
      "Galactus.ClientError.Statement.TypeError";
  public static final String ARGUMENT_ERROR =
      "Galactus.ClientError.Statement.ArgumentError";
  public static final String SQL_TOO_MANY_ROWS =
      "Galactus.ClientError.Statement.SqlTooManyRows";
  public static final String SQL_CAST_FAILED =
      "Galactus.ClientError.Statement.SqlCastFailed";
  public static final String TABLE_TOO_LARGE =
      "Galactus.ClientError.Statement.TableTooLarge";
  public static final String UNSUPPORTED_FEATURE =
      "Galactus.DatabaseError.Storage.UnsupportedFeature";
  public static final String PROCEDURE_NOT_FOUND =
      "Galactus.ClientError.Procedure.ProcedureNotFound";
  public static final String LICENSE_RESTRICTED =
      "Galactus.ClientError.License.Restricted";
  public static final String READ_ONLY_REPLICA =
      "Galactus.ClientError.Database.ReadOnlyReplica";
  public static final String TRANSACTION_TIMED_OUT =
      "Galactus.ClientError.Transaction.TimedOut";
  public static final String TRANSACTION_TERMINATED =
      "Galactus.ClientError.Transaction.Terminated";
  public static final String SNAPSHOT_TOO_OLD =
      "Galactus.TransientError.Transaction.SnapshotTooOld";
  public static final String FORBIDDEN =
      "Galactus.ClientError.Security.Forbidden";
  public static final String SYNTAX_ERROR =
      "Neo.ClientError.Statement.SyntaxError";
  public static final String SEMANTIC_ERROR =
      "Neo.ClientError.Statement.SemanticError";
  public static final String UNAUTHORIZED =
      "Neo.ClientError.Security.Unauthorized";
  public static final String DATABASE_NOT_FOUND =
      "Neo.ClientError.Database.DatabaseNotFound";
}
