/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.sql;

import org.apache.flink.formats.json.debezium.DebeziumJsonDeserializationSchema;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.DefaultIndex;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.UniqueConstraint;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** End-to-End tests for using remote jar. */
public class UsingRemoteJarITCase extends HdfsITCaseBase {

    private static final ResolvedSchema USER_ORDER_SCHEMA =
            new ResolvedSchema(
                    Arrays.asList(
                            Column.physical("user_name", DataTypes.STRING()),
                            Column.physical("order_cnt", DataTypes.BIGINT())),
                    Collections.emptyList(),
                    UniqueConstraint.primaryKey("pk", Collections.singletonList("user_name")),
                    Collections.singletonList(
                            DefaultIndex.newIndex("idx", Collections.singletonList("user_name"))));

    private static final DebeziumJsonDeserializationSchema USER_ORDER_DESERIALIZATION_SCHEMA =
            createDebeziumDeserializationSchema(USER_ORDER_SCHEMA);

    @Rule public TestName name = new TestName();
    private org.apache.hadoop.fs.Path hdPath;
    private org.apache.hadoop.fs.FileSystem hdfs;

    public UsingRemoteJarITCase(String executionMode) {
        super(executionMode);
    }

    @Override
    protected void createHDFS() {
        super.createHDFS();
        hdPath = new org.apache.hadoop.fs.Path("/test.jar");
        try {
            hdfs = hdPath.getFileSystem(hdConf);

            hdfs.copyFromLocalFile(
                    new org.apache.hadoop.fs.Path(SQL_TOOL_BOX_JAR.toString()), hdPath);
        } catch (IOException e) {
            Assert.fail("Failed to copy local test.jar to HDFS env" + e.getMessage());
        }
    }

    @Override
    protected void destroyHDFS() {
        try {
            hdfs.delete(hdPath, false);
        } catch (IOException e) {
            Assert.fail("Failed to cleanup HDFS path" + e.getMessage());
        }
        super.destroyHDFS();
    }

    @Test
    public void testUdfInRemoteJar() throws Exception {
        runAndCheckSQL(
                "remote_jar_e2e.sql",
                Arrays.asList("+I[Bob, 2]", "+I[Alice, 1]"),
                raw ->
                        convertToMaterializedResult(
                                raw, USER_ORDER_SCHEMA, USER_ORDER_DESERIALIZATION_SCHEMA));
    }

    @Test
    public void testCreateFunctionFromRemoteJarViaSqlClient() throws Exception {
        runAndCheckSQL(
                "sql_client_remote_jar_e2e.sql",
                Collections.singletonMap(result, Arrays.asList("+I[Bob, 2]", "+I[Alice, 1]")),
                Collections.singletonMap(
                        result,
                        raw ->
                                convertToMaterializedResult(
                                        raw, USER_ORDER_SCHEMA, USER_ORDER_DESERIALIZATION_SCHEMA)),
                Collections.singletonList(
                        URI.create(
                                String.format(
                                        "hdfs://%s:%s/%s",
                                        hdfsCluster.getURI().getHost(),
                                        hdfsCluster.getNameNodePort(),
                                        hdPath))));
    }

    @Test
    public void testScalarUdfWhenCheckpointEnable() throws Exception {
        runAndCheckSQL(
                "scalar_udf_e2e.sql",
                Collections.singletonList(
                        "{\"before\":null,\"after\":{\"id\":1,\"str\":\"Hello Flink\"},\"op\":\"c\"}"));
    }

    @Test
    public void testCreateTemporarySystemFunctionUsingRemoteJar() throws Exception {
        runAndCheckSQL(
                "create_function_using_remote_jar_e2e.sql",
                Arrays.asList("+I[Bob, 2]", "+I[Alice, 1]"),
                raw ->
                        convertToMaterializedResult(
                                raw, USER_ORDER_SCHEMA, USER_ORDER_DESERIALIZATION_SCHEMA));
    }

    @Test
    public void testCreateCatalogFunctionUsingRemoteJar() throws Exception {
        runAndCheckSQL(
                "create_function_using_remote_jar_e2e.sql",
                Arrays.asList("+I[Bob, 2]", "+I[Alice, 1]"),
                raw ->
                        convertToMaterializedResult(
                                raw, USER_ORDER_SCHEMA, USER_ORDER_DESERIALIZATION_SCHEMA));
    }

    @Test
    public void testCreateTemporaryCatalogFunctionUsingRemoteJar() throws Exception {
        Map<String, String> replaceVars = generateReplaceVars();
        replaceVars.put("$TEMPORARY", "TEMPORARY");
        runAndCheckSQL(
                "create_function_using_remote_jar_e2e.sql",
                Arrays.asList("+I[Bob, 2]", "+I[Alice, 1]"),
                raw ->
                        convertToMaterializedResult(
                                raw, USER_ORDER_SCHEMA, USER_ORDER_DESERIALIZATION_SCHEMA));
    }

    @Override
    protected Map<String, String> generateReplaceVars() {
        String remoteJarPath =
                String.format(
                        "hdfs://%s:%s/%s",
                        hdfsCluster.getURI().getHost(), hdfsCluster.getNameNodePort(), hdPath);

        Map<String, String> varsMap = super.generateReplaceVars();
        varsMap.put("$JAR_PATH", remoteJarPath);
        String methodName = name.getMethodName();
        if (methodName.startsWith("testCreateTemporarySystemFunction")) {
            varsMap.put("$TEMPORARY", "TEMPORARY SYSTEM");
        } else if (methodName.startsWith("testCreateTemporaryCatalogFunction")) {
            varsMap.put("$TEMPORARY", "TEMPORARY");
        } else if (methodName.startsWith("testCreateCatalogFunction")) {
            varsMap.put("$TEMPORARY", "");
        }
        return varsMap;
    }
}
