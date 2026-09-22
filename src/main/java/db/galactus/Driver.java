package db.galactus;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;

/** One serial Bolt connection. close() also rolls back any open transaction. */
public final class Driver implements AutoCloseable {
  public static final class DatabaseException extends IOException {
    private static final long serialVersionUID = 1L;
    public final String code;
    public final Map<String, Object> metadata;
    DatabaseException(Map<String, Object> m) {
      super(String.valueOf(m.get("message")));
      code = String.valueOf(m.get("code"));
      metadata = m;
    }
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
  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private final String database;
  private boolean transaction;
  public Driver(String uri, String username, String password)
      throws IOException {
    this(uri, username, password, "", 30000);
  }
  public Driver(String uri, String username, String password, String database,
                int timeoutMillis) throws IOException {
    URI u = URI.create(uri);
    String scheme = u.getScheme();
    if (!Arrays.asList("bolt", "bolt+s").contains(scheme) ||
        u.getHost() == null || u.getUserInfo() != null ||
        !(u.getPath() == null || u.getPath().isEmpty() ||
          u.getPath().equals("/")) ||
        u.getQuery() != null || u.getFragment() != null)
      throw new IllegalArgumentException(
          "Expected bolt://host:port or bolt+s://host:port");
    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress(u.getHost(),
                                      u.getPort() < 0 ? 7687 : u.getPort()),
                timeoutMillis);
      s.setSoTimeout(timeoutMillis);
      s.setTcpNoDelay(true);
      if (scheme.equals("bolt+s")) {
        SSLSocket secure =
            (SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
                .createSocket(s, u.getHost(), s.getPort(), true);
        s = secure;
        SSLParameters p = secure.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        secure.setSSLParameters(p);
        secure.startHandshake();
      }
      socket = s;
      in = new DataInputStream(s.getInputStream());
      out = s.getOutputStream();
      this.database = database;
      out.write(new byte[] {0x60, 0x60, (byte)0xb0, 0x17, 0, 0, 4, 4, 0, 0,
                            0,    0,    0,          0,    0, 0, 0, 0, 0, 0});
      out.flush();
      if (in.readInt() != 0x404)
        throw new IOException("Server did not select Bolt 4.4");
      send(1, Map.of("user_agent", "galactus-java/0.1", "scheme", "basic",
                     "principal", username, "credentials", password));
      success();
    } catch (IOException | RuntimeException e) {
      s.close();
      throw e;
    }
  }
  private void send(int tag, Object... fields) throws IOException {
    if (tag == 0x10 || tag == 0x11) {
      int index = tag == 0x10 ? 2 : 0;
      Map<?, ?> original = (Map<?, ?>) fields[index];
      Map<Object, Object> extra = new LinkedHashMap<>(original);
      if ("".equals(extra.get("db"))) extra.remove("db");
      fields[index] = extra;
    }
    if (tag == 0x10)
      Types.validateParameters(fields[1], 0);
    byte[] b = PackStream.encode(new Types.Structure(tag, fields));
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    DataOutputStream o = new DataOutputStream(frame);
    for (int p = 0; p < b.length; p += 65535) {
      int n = Math.min(65535, b.length - p);
      o.writeShort(n);
      o.write(b, p, n);
    }
    o.writeShort(0);
    out.write(frame.toByteArray());
    out.flush();
  }
  @SuppressWarnings("unchecked")
  private Types.Structure receive() throws IOException {
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
    if (m.fields.size() != 1)
      throw new IOException("Invalid Bolt response");
    if (m.tag == 0x7f)
      throw new DatabaseException((Map<String, Object>)m.fields.get(0));
    if (m.tag != 0x70 && m.tag != 0x71)
      throw new IOException("Unexpected Bolt response");
    return m;
  }
  @SuppressWarnings("unchecked")
  private Map<String, Object> success() throws IOException {
    Types.Structure m = receive();
    if (m.tag != 0x70)
      throw new IOException("Expected SUCCESS");
    return (Map<String, Object>)m.fields.get(0);
  }
  public Result executeQuery(String query) throws IOException {
    return executeQuery(query, Collections.emptyMap());
  }
  @SuppressWarnings("unchecked")
  public synchronized Result executeQuery(String query,
                                          Map<String, ?> parameters)
      throws IOException {
    try {
      send(0x10, query, parameters,
           transaction ? Map.of() : Map.of("db", database));
      Map<String, Object> meta = success();
      List<String> keys = (List<String>)meta.get("fields");
      List<Map<String, Object>> records = new ArrayList<>();
      send(0x3f, Map.of("n", -1L));
      for (;;) {
        Types.Structure m = receive();
        if (m.tag == 0x70) {
          Map<String, Object> summary = (Map<String, Object>)m.fields.get(0);
          if (Boolean.TRUE.equals(summary.get("has_more"))) {
            send(0x3f, Map.of("n", -1L));
            continue;
          }
          meta.putAll(summary);
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
    } catch (IOException | RuntimeException e) {
      close();
      throw e;
    }
  }
  public synchronized Map<String, Object> commit() throws IOException {
    return finish(0x12);
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
