package db.galactus;

/**
 * Server error codes applications commonly act on. Compare them with
 * {@link Driver.DatabaseException#code}. Codes the server adds later still
 * arrive as plain strings.
 */
public final class ErrorCodes {
  private ErrorCodes() {}
  public static final String ConstraintValidationFailed =
      "Galactus.ClientError.Schema.ConstraintValidationFailed";
  public static final String TypeError =
      "Galactus.ClientError.Statement.TypeError";
  public static final String ArgumentError =
      "Galactus.ClientError.Statement.ArgumentError";
  public static final String SqlTooManyRows =
      "Galactus.ClientError.Statement.SqlTooManyRows";
  public static final String SqlCastFailed =
      "Galactus.ClientError.Statement.SqlCastFailed";
  public static final String TableTooLarge =
      "Galactus.ClientError.Statement.TableTooLarge";
  public static final String UnsupportedFeature =
      "Galactus.DatabaseError.Storage.UnsupportedFeature";
  public static final String ProcedureNotFound =
      "Galactus.ClientError.Procedure.ProcedureNotFound";
  public static final String LicenseRestricted =
      "Galactus.ClientError.License.Restricted";
  public static final String ReadOnlyReplica =
      "Galactus.ClientError.Database.ReadOnlyReplica";
  public static final String TransactionTimedOut =
      "Galactus.ClientError.Transaction.TimedOut";
  public static final String TransactionTerminated =
      "Galactus.ClientError.Transaction.Terminated";
  public static final String SnapshotTooOld =
      "Galactus.TransientError.Transaction.SnapshotTooOld";
  public static final String Forbidden =
      "Galactus.ClientError.Security.Forbidden";
  public static final String SyntaxError =
      "Neo.ClientError.Statement.SyntaxError";
  public static final String SemanticError =
      "Neo.ClientError.Statement.SemanticError";
  public static final String Unauthorized =
      "Neo.ClientError.Security.Unauthorized";
  public static final String DatabaseNotFound =
      "Neo.ClientError.Database.DatabaseNotFound";
}
