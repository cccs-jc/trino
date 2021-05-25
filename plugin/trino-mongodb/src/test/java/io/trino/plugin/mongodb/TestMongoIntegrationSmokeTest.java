/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.mongodb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import io.trino.sql.planner.plan.LimitNode;
import io.trino.testing.AbstractTestIntegrationSmokeTest;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import org.bson.Document;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static io.trino.plugin.mongodb.MongoQueryRunner.createMongoClient;
import static io.trino.plugin.mongodb.MongoQueryRunner.createMongoQueryRunner;
import static io.trino.tpch.TpchTable.CUSTOMER;
import static io.trino.tpch.TpchTable.NATION;
import static io.trino.tpch.TpchTable.ORDERS;
import static io.trino.tpch.TpchTable.REGION;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

@Test(singleThreaded = true)
public class TestMongoIntegrationSmokeTest
        // TODO extend BaseConnectorTest
        extends AbstractTestIntegrationSmokeTest
{
    private MongoServer server;
    private MongoClient client;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.server = new MongoServer();
        this.client = createMongoClient(server);
        return createMongoQueryRunner(server, CUSTOMER, NATION, ORDERS, REGION);
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        server.close();
        client.close();
    }

    @Test
    public void createTableWithEveryType()
    {
        String query = "" +
                "CREATE TABLE test_types_table AS " +
                "SELECT" +
                " 'foo' _varchar" +
                ", cast('bar' as varbinary) _varbinary" +
                ", cast(1 as bigint) _bigint" +
                ", 3.14E0 _double" +
                ", true _boolean" +
                ", DATE '1980-05-07' _date" +
                ", TIMESTAMP '1980-05-07 11:22:33.456' _timestamp" +
                ", ObjectId('ffffffffffffffffffffffff') _objectid";

        assertUpdate(query, 1);

        MaterializedResult results = getQueryRunner().execute(getSession(), "SELECT * FROM test_types_table").toTestTypes();
        assertEquals(results.getRowCount(), 1);
        MaterializedRow row = results.getMaterializedRows().get(0);
        assertEquals(row.getField(0), "foo");
        assertEquals(row.getField(1), "bar".getBytes(UTF_8));
        assertEquals(row.getField(2), 1L);
        assertEquals(row.getField(3), 3.14);
        assertEquals(row.getField(4), true);
        assertEquals(row.getField(5), LocalDate.of(1980, 5, 7));
        assertEquals(row.getField(6), LocalDateTime.of(1980, 5, 7, 11, 22, 33, 456_000_000));
        assertUpdate("DROP TABLE test_types_table");

        assertFalse(getQueryRunner().tableExists(getSession(), "test_types_table"));
    }

    @Test
    public void testInsertWithEveryType()
    {
        String createSql = "" +
                "CREATE TABLE test_insert_types_table " +
                "(" +
                "  vc varchar" +
                ", vb varbinary" +
                ", bi bigint" +
                ", d double" +
                ", b boolean" +
                ", dt  date" +
                ", ts  timestamp" +
                ", objid objectid" +
                ")";
        getQueryRunner().execute(getSession(), createSql);

        String insertSql = "" +
                "INSERT INTO test_insert_types_table " +
                "SELECT" +
                " 'foo' _varchar" +
                ", cast('bar' as varbinary) _varbinary" +
                ", cast(1 as bigint) _bigint" +
                ", 3.14E0 _double" +
                ", true _boolean" +
                ", DATE '1980-05-07' _date" +
                ", TIMESTAMP '1980-05-07 11:22:33.456' _timestamp" +
                ", ObjectId('ffffffffffffffffffffffff') _objectid";
        getQueryRunner().execute(getSession(), insertSql);

        MaterializedResult results = getQueryRunner().execute(getSession(), "SELECT * FROM test_insert_types_table").toTestTypes();
        assertEquals(results.getRowCount(), 1);
        MaterializedRow row = results.getMaterializedRows().get(0);
        assertEquals(row.getField(0), "foo");
        assertEquals(row.getField(1), "bar".getBytes(UTF_8));
        assertEquals(row.getField(2), 1L);
        assertEquals(row.getField(3), 3.14);
        assertEquals(row.getField(4), true);
        assertEquals(row.getField(5), LocalDate.of(1980, 5, 7));
        assertEquals(row.getField(6), LocalDateTime.of(1980, 5, 7, 11, 22, 33, 456_000_000));
        assertUpdate("DROP TABLE test_insert_types_table");
        assertFalse(getQueryRunner().tableExists(getSession(), "test_insert_types_table"));
    }

    @Test
    public void testArrays()
    {
        assertUpdate("CREATE TABLE tmp_array1 AS SELECT ARRAY[1, 2, NULL] AS col", 1);
        assertQuery("SELECT col[2] FROM tmp_array1", "SELECT 2");
        assertQuery("SELECT col[3] FROM tmp_array1", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_array2 AS SELECT ARRAY[1.0E0, 2.5E0, 3.5E0] AS col", 1);
        assertQuery("SELECT col[2] FROM tmp_array2", "SELECT 2.5");

        assertUpdate("CREATE TABLE tmp_array3 AS SELECT ARRAY['puppies', 'kittens', NULL] AS col", 1);
        assertQuery("SELECT col[2] FROM tmp_array3", "SELECT 'kittens'");
        assertQuery("SELECT col[3] FROM tmp_array3", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_array4 AS SELECT ARRAY[TRUE, NULL] AS col", 1);
        assertQuery("SELECT col[1] FROM tmp_array4", "SELECT TRUE");
        assertQuery("SELECT col[2] FROM tmp_array4", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_array5 AS SELECT ARRAY[ARRAY[1, 2], NULL, ARRAY[3, 4]] AS col", 1);
        assertQuery("SELECT col[1][2] FROM tmp_array5", "SELECT 2");

        assertUpdate("CREATE TABLE tmp_array6 AS SELECT ARRAY[ARRAY['\"hi\"'], NULL, ARRAY['puppies']] AS col", 1);
        assertQuery("SELECT col[1][1] FROM tmp_array6", "SELECT '\"hi\"'");
        assertQuery("SELECT col[3][1] FROM tmp_array6", "SELECT 'puppies'");
    }

    @Test
    public void testTemporalArrays()
    {
        assertUpdate("CREATE TABLE tmp_array7 AS SELECT ARRAY[DATE '2014-09-30'] AS col", 1);
        assertOneNotNullResult("SELECT col[1] FROM tmp_array7");
        assertUpdate("CREATE TABLE tmp_array8 AS SELECT ARRAY[TIMESTAMP '2001-08-22 03:04:05.321'] AS col", 1);
        assertOneNotNullResult("SELECT col[1] FROM tmp_array8");
    }

    @Test
    public void testSkipUnknownTypes()
    {
        Document document1 = new Document("col", Document.parse("{\"key1\": \"value1\", \"key2\": null}"));
        client.getDatabase("test").getCollection("tmp_guess_schema1").insertOne(document1);
        assertQuery("SHOW COLUMNS FROM test.tmp_guess_schema1", "SELECT 'col', 'row(key1 varchar)', '', ''");
        assertQuery("SELECT col.key1 FROM test.tmp_guess_schema1", "SELECT 'value1'");

        Document document2 = new Document("col", new Document("key1", null));
        client.getDatabase("test").getCollection("tmp_guess_schema2").insertOne(document2);
        assertQueryReturnsEmptyResult("SHOW COLUMNS FROM test.tmp_guess_schema2");
    }

    @Test
    public void testMaps()
    {
        assertUpdate("CREATE TABLE tmp_map1 AS SELECT MAP(ARRAY[0,1], ARRAY[2,NULL]) AS col", 1);
        assertQuery("SELECT col[0] FROM tmp_map1", "SELECT 2");
        assertQuery("SELECT col[1] FROM tmp_map1", "SELECT NULL");

        assertUpdate("CREATE TABLE tmp_map2 AS SELECT MAP(ARRAY[1.0E0], ARRAY[2.5E0]) AS col", 1);
        assertQuery("SELECT col[1.0] FROM tmp_map2", "SELECT 2.5");

        assertUpdate("CREATE TABLE tmp_map3 AS SELECT MAP(ARRAY['puppies'], ARRAY['kittens']) AS col", 1);
        assertQuery("SELECT col['puppies'] FROM tmp_map3", "SELECT 'kittens'");

        assertUpdate("CREATE TABLE tmp_map4 AS SELECT MAP(ARRAY[TRUE], ARRAY[FALSE]) AS col", "SELECT 1");
        assertQuery("SELECT col[TRUE] FROM tmp_map4", "SELECT FALSE");

        assertUpdate("CREATE TABLE tmp_map5 AS SELECT MAP(ARRAY[1.0E0], ARRAY[ARRAY[1, 2]]) AS col", 1);
        assertQuery("SELECT col[1.0][2] FROM tmp_map5", "SELECT 2");

        assertUpdate("CREATE TABLE tmp_map6 AS SELECT MAP(ARRAY[DATE '2014-09-30'], ARRAY[DATE '2014-09-29']) AS col", 1);
        assertOneNotNullResult("SELECT col[DATE '2014-09-30'] FROM tmp_map6");
        assertUpdate("CREATE TABLE tmp_map7 AS SELECT MAP(ARRAY[TIMESTAMP '2001-08-22 03:04:05.321'], ARRAY[TIMESTAMP '2001-08-22 03:04:05.321']) AS col", 1);
        assertOneNotNullResult("SELECT col[TIMESTAMP '2001-08-22 03:04:05.321'] FROM tmp_map7");

        assertUpdate("CREATE TABLE test.tmp_map8 (col MAP<VARCHAR, VARCHAR>)");
        client.getDatabase("test").getCollection("tmp_map8").insertOne(new Document(
                ImmutableMap.of("col", new Document(ImmutableMap.of("key1", "value1", "key2", "value2")))));
        assertQuery("SELECT col['key1'] FROM test.tmp_map8", "SELECT 'value1'");

        assertUpdate("CREATE TABLE test.tmp_map9 (col VARCHAR)");
        client.getDatabase("test").getCollection("tmp_map9").insertOne(new Document(
                ImmutableMap.of("col", new Document(ImmutableMap.of("key1", "value1", "key2", "value2")))));
        assertQuery("SELECT col FROM test.tmp_map9", "SELECT '{ \"key1\" : \"value1\", \"key2\" : \"value2\" }'");

        assertUpdate("CREATE TABLE test.tmp_map10 (col VARCHAR)");
        client.getDatabase("test").getCollection("tmp_map10").insertOne(new Document(
                ImmutableMap.of("col", ImmutableList.of(new Document(ImmutableMap.of("key1", "value1", "key2", "value2")),
                        new Document(ImmutableMap.of("key3", "value3", "key4", "value4"))))));
        assertQuery("SELECT col FROM test.tmp_map10", "SELECT '[{ \"key1\" : \"value1\", \"key2\" : \"value2\" }, { \"key3\" : \"value3\", \"key4\" : \"value4\" }]'");

        assertUpdate("CREATE TABLE test.tmp_map11 (col VARCHAR)");
        client.getDatabase("test").getCollection("tmp_map11").insertOne(new Document(
                ImmutableMap.of("col", 10)));
        assertQuery("SELECT col FROM test.tmp_map11", "SELECT '10'");

        assertUpdate("CREATE TABLE test.tmp_map12 (col VARCHAR)");
        client.getDatabase("test").getCollection("tmp_map12").insertOne(new Document(
                ImmutableMap.of("col", Arrays.asList(10, null, 11))));
        assertQuery("SELECT col FROM test.tmp_map12", "SELECT '[10, null, 11]'");
    }

    @Test
    public void testCollectionNameContainsDots()
    {
        assertUpdate("CREATE TABLE \"tmp.dot1\" AS SELECT 'foo' _varchar", 1);
        assertQuery("SELECT _varchar FROM \"tmp.dot1\"", "SELECT 'foo'");
        assertUpdate("DROP TABLE \"tmp.dot1\"");
    }

    @Test
    public void testObjectIds()
    {
        String values = "VALUES " +
                " (10, NULL, NULL)," +
                " (11, ObjectId('ffffffffffffffffffffffff'), ObjectId('ffffffffffffffffffffffff'))," +
                " (12, ObjectId('ffffffffffffffffffffffff'), ObjectId('aaaaaaaaaaaaaaaaaaaaaaaa'))," +
                " (13, ObjectId('000000000000000000000000'), ObjectId('000000000000000000000000'))," +
                " (14, ObjectId('ffffffffffffffffffffffff'), NULL)," +
                " (15, NULL, ObjectId('ffffffffffffffffffffffff'))";
        String inlineTable = format("(%s) AS t(i, one, two)", values);

        assertUpdate("DROP TABLE IF EXISTS tmp_objectid");
        assertUpdate("CREATE TABLE tmp_objectid AS SELECT * FROM " + inlineTable, 6);

        // IS NULL
        assertQuery("SELECT i FROM " + inlineTable + " WHERE one IS NULL", "VALUES 10, 15");
        assertQuery("SELECT i FROM tmp_objectid WHERE one IS NULL", "SELECT 0 WHERE false"); // NULL gets replaced with new unique ObjectId in MongoPageSink, this affects other test cases

        // CAST AS varchar
        assertQuery(
                "SELECT i, CAST(one AS varchar) FROM " + inlineTable + " WHERE i <= 13",
                "VALUES (10, NULL), (11, 'ffffffffffffffffffffffff'), (12, 'ffffffffffffffffffffffff'), (13, '000000000000000000000000')");

        // EQUAL
        assertQuery("SELECT i FROM tmp_objectid WHERE one = two", "VALUES 11, 13");
        assertQuery("SELECT i FROM tmp_objectid WHERE one = ObjectId('ffffffffffffffffffffffff')", "VALUES 11, 12, 14");

        // IS DISTINCT FROM
        assertQuery("SELECT i FROM " + inlineTable + " WHERE one IS DISTINCT FROM two", "VALUES 12, 14, 15");
        assertQuery("SELECT i FROM " + inlineTable + " WHERE one IS NOT DISTINCT FROM two", "VALUES 10, 11, 13");

        assertQuery("SELECT i FROM tmp_objectid WHERE one IS DISTINCT FROM two", "VALUES 10, 12, 14, 15");
        assertQuery("SELECT i FROM tmp_objectid WHERE one IS NOT DISTINCT FROM two", "VALUES 11, 13");

        // Join on ObjectId
        assertQuery(
                format("SELECT l.i, r.i FROM (%1$s) AS l(i, one, two) JOIN (%1$s) AS r(i, one, two) ON l.one = r.two", values),
                "VALUES (11, 11), (14, 11), (11, 15), (12, 15), (12, 11), (14, 15), (13, 13)");

        // Group by ObjectId (IS DISTINCT FROM)
        assertQuery("SELECT array_agg(i ORDER BY i) FROM " + inlineTable + " GROUP BY one", "VALUES ((10, 15)), ((11, 12, 14)), ((13))");
        assertQuery("SELECT i FROM " + inlineTable + " GROUP BY one, i", "VALUES 10, 11, 12, 13, 14, 15");

        // Group by Row(ObjectId) (ID DISTINCT FROM in @OperatorDependency)
        assertQuery(
                "SELECT r.i, count(*) FROM (SELECT CAST(row(one, i) AS row(one ObjectId, i bigint)) r FROM " + inlineTable + ") GROUP BY r",
                "VALUES (10, 1), (11, 1), (12, 1), (13, 1), (14, 1), (15, 1)");
        assertQuery(
                "SELECT r.x, CAST(r.one AS varchar), count(*) FROM (SELECT CAST(row(one, i / 3 * 3) AS row(one ObjectId, x bigint)) r FROM " + inlineTable + ") GROUP BY r",
                "VALUES (9, NULL, 1), (9, 'ffffffffffffffffffffffff', 1), (12, 'ffffffffffffffffffffffff', 2), (12, '000000000000000000000000', 1), (15, NULL, 1)");

        assertUpdate("DROP TABLE tmp_objectid");
    }

    @Test
    public void testCaseInsensitive()
            throws Exception
    {
        MongoCollection<Document> collection = client.getDatabase("testCase").getCollection("testInsensitive");
        collection.insertOne(new Document(ImmutableMap.of("Name", "abc", "Value", 1)));

        assertQuery("SHOW SCHEMAS IN mongodb LIKE 'testcase'", "SELECT 'testcase'");
        assertQuery("SHOW TABLES IN testcase", "SELECT 'testinsensitive'");
        assertQuery(
                "SHOW COLUMNS FROM testcase.testInsensitive",
                "VALUES ('name', 'varchar', '', ''), ('value', 'bigint', '', '')");

        assertQuery("SELECT name, value FROM testcase.testinsensitive", "SELECT 'abc', 1");
        assertUpdate("INSERT INTO testcase.testinsensitive VALUES('def', 2)", 1);

        assertQuery("SELECT value FROM testcase.testinsensitive WHERE name = 'def'", "SELECT 2");
        assertUpdate("DROP TABLE testcase.testinsensitive");
    }

    @Test
    public void testNonLowercaseViewName()
    {
        // Case insensitive schema name
        MongoCollection<Document> collection = client.getDatabase("NonLowercaseSchema").getCollection("test_collection");
        collection.insertOne(new Document(ImmutableMap.of("Name", "abc", "Value", 1)));

        client.getDatabase("NonLowercaseSchema").createView("lowercase_view", "test_collection", ImmutableList.of());
        assertQuery("SELECT value FROM nonlowercaseschema.lowercase_view WHERE name = 'abc'", "SELECT 1");

        // Case insensitive view name
        collection = client.getDatabase("test_database").getCollection("test_collection");
        collection.insertOne(new Document(ImmutableMap.of("Name", "abc", "Value", 1)));

        client.getDatabase("test_database").createView("NonLowercaseView", "test_collection", ImmutableList.of());
        assertQuery("SELECT value FROM test_database.nonlowercaseview WHERE name = 'abc'", "SELECT 1");

        // Case insensitive schema and view name
        client.getDatabase("NonLowercaseSchema").createView("NonLowercaseView", "test_collection", ImmutableList.of());
        assertQuery("SELECT value FROM nonlowercaseschema.nonlowercaseview WHERE name = 'abc'", "SELECT 1");

        assertUpdate("DROP TABLE nonlowercaseschema.lowercase_view");
        assertUpdate("DROP TABLE test_database.nonlowercaseview");
        assertUpdate("DROP TABLE nonlowercaseschema.test_collection");
        assertUpdate("DROP TABLE test_database.test_collection");
        assertUpdate("DROP TABLE nonlowercaseschema.nonlowercaseview");
    }

    @Test
    public void testSelectView()
    {
        assertUpdate("CREATE TABLE test.view_base AS SELECT 'foo' _varchar", 1);
        client.getDatabase("test").createView("test_view", "view_base", ImmutableList.of());
        assertQuery("SELECT * FROM test.view_base", "SELECT 'foo'");
        assertUpdate("DROP TABLE test.test_view");
        assertUpdate("DROP TABLE test.view_base");
    }

    @Test
    public void testDropTable()
    {
        assertUpdate("CREATE TABLE test.drop_table(col bigint)");
        assertUpdate("DROP TABLE test.drop_table");
        assertQueryFails("SELECT * FROM test.drop_table", ".*Table 'mongodb.test.drop_table' does not exist");
    }

    @Test
    public void testNullPredicates()
    {
        assertUpdate("CREATE TABLE test.null_predicates(name varchar, value integer)");

        MongoCollection<Document> collection = client.getDatabase("test").getCollection("null_predicates");
        collection.insertOne(new Document(ImmutableMap.of("name", "abc", "value", 1)));
        collection.insertOne(new Document(ImmutableMap.of("name", "abcd")));
        collection.insertOne(new Document(Document.parse("{\"name\": \"abcde\", \"value\": null}")));

        assertQuery("SELECT count(*) FROM test.null_predicates WHERE value IS NULL OR rand() = 42", "SELECT 2");
        assertQuery("SELECT count(*) FROM test.null_predicates WHERE value IS NULL", "SELECT 2");
        assertQuery("SELECT count(*) FROM test.null_predicates WHERE value IS NOT NULL", "SELECT 1");

        assertUpdate("DROP TABLE test.null_predicates");
    }

    @Test
    public void testLimitPushdown()
    {
        assertThat(query("SELECT name FROM nation LIMIT 30")).isFullyPushedDown(); // Use high limit for result determinism

        // Make sure LIMIT 0 returns empty result because cursor.limit(0) means no limit in MongoDB
        assertThat(query("SELECT name FROM nation LIMIT 0")).returnsEmptyResult();

        // MongoDB doesn't support limit number greater than integer max
        assertThat(query("SELECT name FROM nation LIMIT 2147483647")).isFullyPushedDown();
        assertThat(query("SELECT name FROM nation LIMIT 2147483648")).isNotFullyPushedDown(LimitNode.class);
    }

    private void assertOneNotNullResult(String query)
    {
        MaterializedResult results = getQueryRunner().execute(getSession(), query).toTestTypes();
        assertEquals(results.getRowCount(), 1);
        assertEquals(results.getMaterializedRows().get(0).getFieldCount(), 1);
        assertNotNull(results.getMaterializedRows().get(0).getField(0));
    }
}
