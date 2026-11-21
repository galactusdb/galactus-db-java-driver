package db.galactus;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The mapper's shared test (docs/MAPPER.md), and its checks that need no database. */
final class MapperTest {
  @Mapper.Node("Person")
  static final class Person {
    @Mapper.Key long id;
    String name;
    @Mapper.Property("email_address") String email;
    Long age;
    Person() {}
    Person(long id, String name, String email, Long age) {
      this.id = id;
      this.name = name;
      this.email = email;
      this.age = age;
    }
    @Override public boolean equals(Object o) {
      if (!(o instanceof Person)) return false;
      Person p = (Person) o;
      return id == p.id && Objects.equals(name, p.name) && Objects.equals(email, p.email) && Objects.equals(age, p.age);
    }
    @Override public int hashCode() { return Long.hashCode(id); }
    @Override public String toString() { return "Person(" + id + ", " + name + ", " + email + ", " + age + ")"; }
  }

  static final class Company {
    @Mapper.Key String name;
    Company() {}
    Company(String name) { this.name = name; }
    @Override public boolean equals(Object o) { return o instanceof Company && Objects.equals(name, ((Company) o).name); }
    @Override public int hashCode() { return Objects.hashCode(name); }
  }

  @Mapper.Relationship("WORKS_AT")
  static final class WorksAt {
    long since;
    String role;
    WorksAt() {}
    WorksAt(long since, String role) {
      this.since = since;
      this.role = role;
    }
    @Override public boolean equals(Object o) {
      return o instanceof WorksAt && since == ((WorksAt) o).since && Objects.equals(role, ((WorksAt) o).role);
    }
    @Override public int hashCode() { return Long.hashCode(since); }
  }

  static final class Note {
    String text;
    int stars;
    transient String scratch;
    Note() {}
    Note(String text, int stars) {
      this.text = text;
      this.stars = stars;
    }
    @Override public boolean equals(Object o) {
      return o instanceof Note && stars == ((Note) o).stars && Objects.equals(text, ((Note) o).text);
    }
    @Override public int hashCode() { return stars; }
  }

  @Mapper.Node("Bad`Label")
  static final class BadLabel {
    long x;
  }

  static final class BadProperty {
    @Mapper.Property("a`b") long x;
  }

  static final class NoConstructor {
    final long x;
    NoConstructor(long x) { this.x = x; }
  }

  interface Action {
    void run() throws IOException;
  }

  static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError("mapper: " + name);
  }

  static boolean refused(Action action) throws IOException {
    try {
      action.run();
      return false;
    } catch (Mapper.MappingException e) {
      return true;
    }
  }

  static Map<String, Object> map(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
    return out;
  }

  static void offline() throws IOException {
    Person ann = new Person(1, "Ann", "ann@example.com", null);
    check(Mapper.propertiesOf(ann).equals(map("id", 1L, "name", "Ann", "email_address", "ann@example.com", "age", null)),
          "properties use stored names");
    Types.Node node = new Types.Node(7, Arrays.asList("Person"), map("id", 1L, "name", "Ann", "email_address", "a@b", "extra", true));
    check(Mapper.fromNode(Person.class, node).equals(new Person(1, "Ann", "a@b", null)), "a node becomes an object");
    check(Mapper.fromNode(Person.class, new Types.Node(7, Arrays.asList(), map("id", 2L))).equals(new Person(2, null, null, null)),
          "a node that lacks properties");
    check(refused(() -> Mapper.fromNode(Person.class, new Types.Node(7, Arrays.asList(), map("id", "one")))),
          "a value of the wrong kind");
    Note note = Mapper.fromNode(Note.class, new Types.Node(1, Arrays.asList(), map("stars", 5L)));
    check(note.stars == 5 && !Mapper.propertiesOf(note).containsKey("scratch"), "widths and transient fields");
    check(refused(() -> Mapper.fromNode(Note.class, new Types.Node(1, Arrays.asList(), map("stars", 1L << 40)))),
          "a number too large for its field");
    check(refused(() -> Mapper.propertiesOf(new BadLabel())), "a label with a backtick");
    check(refused(() -> Mapper.propertiesOf(new BadProperty())), "a property with a backtick");
    check(refused(() -> Mapper.propertiesOf(new NoConstructor(1))), "a class with no empty constructor");
    check(refused(() -> Mapper.fromNode(WorksAt.class, node)), "a relationship type from a node");
    System.out.println("Java mapper checks passed");
  }

  static List<Long> ids(List<Person> people) {
    return people.stream().map(p -> p.id).collect(Collectors.toList());
  }

  static void live(Driver driver) throws IOException {
    driver.executeQuery("MATCH (n) WHERE n:Person OR n:Company OR n:Note DETACH DELETE n");
    Mapper mapper = new Mapper(driver);
    // 2. create, find, and the stored property name
    Person ann = new Person(1, "Ann", "ann@example.com", null);
    check(mapper.create(ann).equals(ann), "create returns the object as stored");
    check(ann.equals(mapper.find(Person.class, 1L)), "find returns an equal object");
    Map<String, Object> raw =
        driver.executeQuery("MATCH (p:Person {id: 1}) RETURN p.email_address AS e, p.email AS none").records.get(0);
    check("ann@example.com".equals(raw.get("e")) && raw.get("none") == null, "the stored property name");
    // 3. save changes, and creates
    ann.age = 41L;
    check(Long.valueOf(41).equals(mapper.save(ann).age), "save changes");
    check(mapper.save(new Person(2, "Bob", null, null)).equals(new Person(2, "Bob", null, null)), "save creates");
    // 4. a missing key
    check(mapper.find(Person.class, 99L) == null, "a missing key");
    // 5. find all
    mapper.save(new Person(3, "Bob", null, 41L));
    check(ids(mapper.findAll(Person.class)).equals(Arrays.asList(1L, 2L, 3L)), "find all");
    check(ids(mapper.findAll(Person.class, map("name", "Bob"), -1)).equals(Arrays.asList(2L, 3L)), "find all with a filter");
    check(ids(mapper.findAll(Person.class, map("name", "Bob", "age", 41L), -1)).equals(Arrays.asList(3L)),
          "find all with two filters");
    check(ids(mapper.findAll(Person.class, null, 2)).equals(Arrays.asList(1L, 2L)), "find all with a limit");
    check(refused(() -> mapper.findAll(Person.class, map("height", 1L), -1)), "a filter on a field the type lacks");
    // 6. relate and related
    Company acme = mapper.save(new Company("Acme"));
    check(mapper.relate(ann, new WorksAt(2020, "engineer"), acme).equals(new WorksAt(2020, "engineer")), "relate");
    mapper.relate(ann, new WorksAt(2021, "lead"), acme);
    List<Mapper.Pair<WorksAt, Company>> jobs = mapper.related(ann, WorksAt.class, Company.class);
    check(jobs.size() == 1 && jobs.get(0).relationship.equals(new WorksAt(2021, "lead")) &&
              jobs.get(0).node.equals(new Company("Acme")),
          "related");
    check(refused(() -> mapper.relate(new Person(77, "Nobody", null, null), new WorksAt(1, "x"), acme)),
          "relating a person who is not stored");
    // 7. a hand-written statement
    check(ids(mapper.query(Person.class, "MATCH (p:Person) WHERE p.age >= $age RETURN p ORDER BY p.id", map("age", 41L)))
              .equals(Arrays.asList(1L, 3L)),
          "query");
    check(mapper.query(Company.class, "RETURN {name: $n} AS c", map("n", "Made up")).equals(Arrays.asList(new Company("Made up"))),
          "query maps a map");
    // 8. delete
    check(mapper.delete(Person.class, 3L) && !mapper.delete(Person.class, 3L), "delete");
    // 9. no key
    check(mapper.create(new Note("hello", 3)).equals(new Note("hello", 3)), "create with no key");
    check(refused(() -> mapper.save(new Note("x", 0))), "saving a type with no key");
    check(refused(() -> mapper.find(Note.class, "x")), "finding a type with no key");
    check(refused(() -> mapper.find(Person.class, null)), "finding by no key");
    // the operations join an open transaction
    driver.begin(false);
    mapper.save(new Person(50, "Temporary", null, null));
    driver.rollback();
    check(mapper.find(Person.class, 50L) == null, "a rolled-back save");
    System.out.println("Java mapper live test passed");
  }
}
