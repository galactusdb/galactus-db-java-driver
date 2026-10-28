package db.galactus;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.*;
import javax.net.ssl.*;

/**
 * One serial Bolt connection (Bolt 5.0 to 5.4, falling back to 4.4). A
 * database error resets the connection and leaves the driver usable; an I/O,
 * TLS or protocol error closes it. close() also rolls back any open
 * transaction.
 */
public final class Driver implements AutoCloseable {
  public static final String VERSION = "0.2.0";
  public static final String USER_AGENT = "galactus-db-java-driver/" + VERSION;

  /**
   * An error reported by the server. {@link #code} and the message are kept
   * unchanged; the classification fields are parsed from the code.
   */
  public static final class DatabaseException extends IOException {
    private static final long serialVersionUID = 1L;
    public final String code;
    public final Map<String, Object> metadata;
    /**
     * Parts of {@code {Vendor}.{Classification}.{Category}.{Title}}; null when
     * the code does not have four parts.
     */
    public final String classification, category, title;
    DatabaseException(Map<String, Object> m) {
      super(String.valueOf(m.get("message")));
      code = String.valueOf(m.get("code"));
      metadata = m;
      String[] parts = code.split("\\.", -1);
      boolean ok = parts.length == 4;
      classification = ok ? parts[1] : null;
      category = ok ? parts[2] : null;
      title = ok ? parts[3] : null;
    }
    public boolean isClientError() {
      return "ClientError".equals(classification);
    }
    public boolean isTransient() {
      return "TransientError".equals(classification);
    }
    public boolean isDatabaseError() {
      return "DatabaseError".equals(classification);
    }
    /** True for TransientError codes only. The driver never retries itself. */
    public boolean isRetryable() { return isTransient(); }
  }

  public static final class Result {
    public final List<String> keys;
    public final List<Map<String, Object>> records;
    public final Map<String, Object> summary;
    Result(List<String> keys, List<Map<String, Object>> records,
           Map<String, Object> summary) {
      this.keys = keys;
      this.records = records;
      this.summary = summary;
    }
  }

  /** Optional connection settings. Every setter returns this options object. */
  public static final class Options {
    private String database = "";
    private int timeoutMillis = 30000;
    private Path trustedCertificates;
    /** Empty (the default) lets the server choose its configured default. */
    public Options database(String database) {
      this.database = database == null ? "" : database;
      return this;
    }
    public Options timeoutMillis(int timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }
    /**
     * A PEM file of one or more certificates that replaces the platform trust
     * store for {@code bolt+s://}. Setting it on {@code bolt://} is a
     * configuration error.
     */
    public Options trustedCertificates(Path trustedCertificates) {
      this.trustedCertificates = trustedCertificates;
      return this;
    }
  }

  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private final String database;
  private final boolean bolt5;
  private final String protocolVersion, serverAgent, connectionId;
  private String lastBookmark;
  private boolean transaction;

  public Driver(String uri, String username, String password)
      throws IOException {
    this(uri, username, password, new Options());
  }
  public Driver(String uri, String username, String password, String database,
                int timeoutMillis) throws IOException {
    this(uri, username, password,
         new Options().database(database).timeoutMillis(timeoutMillis));
  }
  public Driver(String uri, String username, String password, Options options)
      throws IOException {
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(password, "password");
    if (options == null)
      options = new Options();
    URI u = URI.create(uri);
    String scheme = u.getScheme();
    if (!Arrays.asList("bolt", "bolt+s").contains(scheme) ||
        u.getHost() == null || u.getUserInfo() != null ||
        !(u.getPath() == null || u.getPath().isEmpty() ||
          u.getPath().equals("/")) ||
        u.getQuery() != null || u.getFragment() != null)
      throw new IllegalArgumentException(
          "Expected bolt://host:port or bolt+s://host:port");
    boolean tls = scheme.equals("bolt+s");
    if (!tls && options.trustedCertificates != null)
      throw new IllegalArgumentException(
          "trustedCertificates requires a bolt+s:// URI");
    SSLSocketFactory factory = null;
    if (tls)
      factory = options.trustedCertificates == null
                    ? (SSLSocketFactory)SSLSocketFactory.getDefault()
                    : trustingFactory(options.trustedCertificates);
    int timeoutMillis = options.timeoutMillis;
    String host = u.getHost();
    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress(host, u.getPort() < 0 ? 7687
                                                            : u.getPort()),
                timeoutMillis);
      s.setSoTimeout(timeoutMillis);
      s.setTcpNoDelay(true);
      if (tls) {
        SSLSocket secure =
            (SSLSocket)factory.createSocket(s, host, s.getPort(), true);
        s = secure;
        SSLParameters p = secure.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isIpLiteral(host))
          p.setServerNames(List.of(new SNIHostName(host)));
        secure.setSSLParameters(p);
        secure.startHandshake();
      }
      socket = s;
      in = new DataInputStream(s.getInputStream());
      out = s.getOutputStream();
      this.database = options.database;
      out.write(new byte[] {0x60, 0x60, (byte)0xb0, 0x17, 0, 4, 4, 5, 0, 0,
                            4,    4,    0,          0,    0, 0, 0, 0, 0, 0});
      out.flush();
      int agreed = in.readInt();
      int minor = (agreed >> 8) & 0xff;
      if (agreed == 0x404) {
        bolt5 = false;
        protocolVersion = "4.4";
      } else if ((agreed & 0xffff00ff) == 5 && minor <= 4) {
        bolt5 = true;
        protocolVersion = "5." + minor;
      } else {
        throw new IOException(
            String.format("Server chose unsupported Bolt version %08x", agreed));
      }
      Map<String, Object> hello = new LinkedHashMap<>();
      hello.put("user_agent", USER_AGENT);
      Map<String, Object> auth = new LinkedHashMap<>();
      auth.put("scheme", "basic");
      auth.put("principal", username);
      auth.put("credentials", password);
      Map<String, Object> greeted;
      if (bolt5 && minor >= 1) {
        hello.put("bolt_agent", boltAgent());
        send(0x01, hello);
        greeted = success();
        send(0x6a, auth);
        success();
      } else {
        hello.putAll(auth);
        send(0x01, hello);
        greeted = success();
      }
      serverAgent = stringOrNull(greeted.get("server"));
      connectionId = stringOrNull(greeted.get("connection_id"));
    } catch (IOException | RuntimeException e) {
      s.close();
      throw e;
    }
  }

  /** The agreed Bolt version, for example {@code "5.4"} or {@code "4.4"}. */
  public String protocolVersion() { return protocolVersion; }
  /** The {@code server} agent string from the HELLO SUCCESS, or null. */
  public String serverAgent() { return serverAgent; }
  /** The {@code connection_id} from the HELLO SUCCESS, or null. */
  public String connectionId() { return connectionId; }
  /**
   * The most recent bookmark from an autocommit query or COMMIT, or null. The
   * driver does not send bookmarks.
   */
  public synchronized String lastBookmark() { return lastBookmark; }

  private static String stringOrNull(Object v) {
    return v instanceof String ? (String)v : null;
  }
  private static boolean isIpLiteral(String host) {
    return host.indexOf(':') >= 0 || host.startsWith("[") ||
        host.matches("[0-9.]+");
  }
  private static Map<String, Object> boltAgent() {
    Map<String, Object> agent = new LinkedHashMap<>();
    agent.put("product", USER_AGENT);
    agent.put("language", "Java/" + System.getProperty("java.version"));
    agent.put("language_details", System.getProperty("java.vm.name", "") +
                                      " " +
                                      System.getProperty("java.vm.version", ""));
    agent.put("platform", System.getProperty("os.name", "") + " " +
                              System.getProperty("os.version", "") + "; " +
                              System.getProperty("os.arch", ""));
    return agent;
  }
  private static SSLSocketFactory trustingFactory(Path file)
      throws IOException {
    try (InputStream pem = Files.newInputStream(file)) {
      Collection<? extends Certificate> certs =
          CertificateFactory.getInstance("X.509").generateCertificates(pem);
      if (certs.isEmpty())
        throw new IllegalArgumentException(
            "trustedCertificates file holds no certificates: " + file);
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (Certificate c : certs)
        store.setCertificateEntry("trusted-" + i++, c);
      TrustManagerFactory trust = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException(
          "Cannot read trustedCertificates file " + file + ": " +
              e.getMessage(),
          e);
    }
  }

  private void send(int tag, Object... fields) throws IOException {
    write(frame(tag, fields));
  }
  /**
   * Validates and encodes a complete request without touching the socket, so
   * an unsupported parameter leaves the connection usable.
   */
  private byte[] frame(int tag, Object... fields) throws IOException {
    if (tag == 0x10 || tag == 0x11) {
      int index = tag == 0x10 ? 2 : 0;
      Map<?, ?> original = (Map<?, ?>)fields[index];
      Map<Object, Object> extra = new LinkedHashMap<>(original);
      if ("".equals(extra.get("db")))
        extra.remove("db");
      fields[index] = extra;
    }
    if (tag == 0x10)
      Types.validateParameters(fields[1], 0);
    byte[] b = PackStream.encode(new Types.Structure(tag, fields), bolt5);
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    DataOutputStream o = new DataOutputStream(frame);
    for (int p = 0; p < b.length; p += 65535) {
      int n = Math.min(65535, b.length - p);
      o.writeShort(n);
      o.write(b, p, n);
    }
    o.writeShort(0);
    return frame.toByteArray();
  }
  private void write(byte[] frame) throws IOException {
    out.write(frame);
    out.flush();
  }
  /**
   * Reads one response. FAILURE becomes a DatabaseException. IGNORED is
   * returned only when {@code allowIgnored}; otherwise it is a protocol error.
   */
  @SuppressWarnings("unchecked")
  private Types.Structure receive(boolean allowIgnored) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (;;) {
      int n = in.readUnsignedShort();
      if (n == 0) {
        if (body.size() > 0)
          break;
        continue;
      }
      if (body.size() + n > PackStream.MAX_MESSAGE)
        throw new IOException("Message exceeds 64 MiB");
      byte[] b = new byte[n];
      in.readFully(b);
      body.write(b);
    }
    Object v = PackStream.decode(body.toByteArray());
    if (!(v instanceof Types.Structure))
      throw new IOException("Invalid Bolt response");
    Types.Structure m = (Types.Structure)v;
    if (m.tag == 0x7e && m.fields.isEmpty()) {
      if (allowIgnored)
        return m;
      throw new IOException("Unexpected IGNORED response");
    }
    if (m.fields.size() != 1)
      throw new IOException("Invalid Bolt response");
    if (m.tag == 0x7f) {
      if (!(m.fields.get(0) instanceof Map))
        throw new IOException("Invalid FAILURE response");
      throw new DatabaseException((Map<String, Object>)m.fields.get(0));
    }
    if (m.tag != 0x70 && m.tag != 0x71)
      throw new IOException("Unexpected Bolt response");
    if (!(m.fields.get(0) instanceof Map) && m.tag == 0x70)
      throw new IOException("Invalid SUCCESS response");
    return m;
  }
  @SuppressWarnings("unchecked")
  private Map<String, Object> success() throws IOException {
    Types.Structure m = receive(false);
    if (m.tag != 0x70)
      throw new IOException("Expected SUCCESS");
    return (Map<String, Object>)m.fields.get(0);
  }
  /**
   * After a FAILURE the server ignores everything until RESET. RESET also
   * rolls back an open transaction. If RESET does not succeed the connection
   * is discarded and the reset error is attached to {@code cause}.
   */
  private void recover(DatabaseException cause) {
    transaction = false;
    try {
      send(0x0f);
      for (;;) {
        Types.Structure m = receive(true);
        if (m.tag == 0x70)
          return;
        if (m.tag != 0x7e)
          throw new IOException("Unexpected response to RESET");
      }
    } catch (IOException | RuntimeException e) {
      cause.addSuppressed(e);
      closeQuietly();
    }
  }
  private void closeQuietly() {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
  private void noteBookmark(Map<String, Object> summary) {
    Object bookmark = summary.get("bookmark");
    if (bookmark instanceof String)
      lastBookmark = (String)bookmark;
  }

  public Result executeQuery(String query) throws IOException {
    return executeQuery(query, Collections.emptyMap());
  }
  @SuppressWarnings("unchecked")
  public synchronized Result executeQuery(String query,
                                          Map<String, ?> parameters)
      throws IOException {
    // Encoding errors raise before anything is sent and keep the connection.
    byte[] run = frame(0x10, query, parameters,
                       transaction ? Map.of() : Map.of("db", database));
    try {
      write(run);
      Map<String, Object> meta = success();
      List<String> keys = (List<String>)meta.get("fields");
      List<Map<String, Object>> records = new ArrayList<>();
      send(0x3f, Map.of("n", -1L));
      for (;;) {
        Types.Structure m = receive(false);
        if (m.tag == 0x70) {
          Map<String, Object> summary = (Map<String, Object>)m.fields.get(0);
          if (Boolean.TRUE.equals(summary.get("has_more"))) {
            send(0x3f, Map.of("n", -1L));
            continue;
          }
          meta.putAll(summary);
          if (!transaction)
            noteBookmark(summary);
          return new Result(keys, records, meta);
        }
        List<?> row = (List<?>)m.fields.get(0);
        if (row.size() != keys.size())
          throw new IOException("Record width mismatch");
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++)
          r.put(keys.get(i), row.get(i));
        records.add(r);
      }
    } catch (DatabaseException e) {
      recover(e);
      throw e;
    } catch (IOException | RuntimeException e) {
      close();
      throw e;
    }
  }
  public synchronized Driver begin(boolean readOnly) throws IOException {
    if (transaction)
      throw new IllegalStateException("Transaction already open");
    try {
      send(0x11, Map.of("db", database, "mode", readOnly ? "r" : "w"));
      success();
      transaction = true;
      return this;
    } catch (DatabaseException e) {
      recover(e);
      throw e;
    } catch (IOException | RuntimeException e) {
      close();
      throw e;
    }
  }
  /**
   * Commits the open transaction. A DatabaseException means it did not
   * commit; any other IOException leaves the outcome unknown, so never retry
   * it automatically.
   */
  public synchronized Map<String, Object> commit() throws IOException {
    Map<String, Object> m = finish(0x12);
    noteBookmark(m);
    return m;
  }
  public synchronized void rollback() throws IOException { finish(0x13); }
  private Map<String, Object> finish(int tag) throws IOException {
    if (!transaction)
      throw new IllegalStateException("No transaction");
    try {
      send(tag);
      Map<String, Object> m = success();
      transaction = false;
      return m;
    } catch (DatabaseException e) {
      recover(e);
      throw e;
    } catch (IOException | RuntimeException e) {
      close();
      throw e;
    }
  }
  public synchronized void close() throws IOException {
    transaction = false;
    socket.close();
  }
}
