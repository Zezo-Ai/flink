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

package org.apache.flink.table.planner.plan.nodes.exec.serde;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.DefaultIndex;
import org.apache.flink.table.catalog.Index;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.UniqueConstraint;
import org.apache.flink.table.catalog.WatermarkSpec;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonParser;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.ObjectCodec;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationContext;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;

import static org.apache.flink.table.planner.plan.nodes.exec.serde.CompiledPlanSerdeUtil.deserializeFieldOrNull;
import static org.apache.flink.table.planner.plan.nodes.exec.serde.CompiledPlanSerdeUtil.deserializeList;
import static org.apache.flink.table.planner.plan.nodes.exec.serde.CompiledPlanSerdeUtil.deserializeListOrEmpty;
import static org.apache.flink.table.planner.plan.nodes.exec.serde.ResolvedSchemaJsonSerializer.COLUMNS;
import static org.apache.flink.table.planner.plan.nodes.exec.serde.ResolvedSchemaJsonSerializer.INDEXES;
import static org.apache.flink.table.planner.plan.nodes.exec.serde.ResolvedSchemaJsonSerializer.PRIMARY_KEY;
import static org.apache.flink.table.planner.plan.nodes.exec.serde.ResolvedSchemaJsonSerializer.WATERMARK_SPECS;

/**
 * JSON deserializer for {@link ResolvedSchema}.
 *
 * @see ResolvedSchemaJsonSerializer for the reverse operation
 */
@Internal
final class ResolvedSchemaJsonDeserializer extends StdDeserializer<ResolvedSchema> {
    private static final long serialVersionUID = 1L;

    ResolvedSchemaJsonDeserializer() {
        super(ResolvedSchema.class);
    }

    @Override
    public ResolvedSchema deserialize(JsonParser jsonParser, DeserializationContext ctx)
            throws IOException {
        ObjectNode jsonNode = jsonParser.readValueAsTree();
        ObjectCodec codec = jsonParser.getCodec();

        List<Column> columns = deserializeList(jsonNode, COLUMNS, Column.class, codec, ctx);
        List<WatermarkSpec> watermarkSpecs =
                deserializeListOrEmpty(jsonNode, WATERMARK_SPECS, WatermarkSpec.class, codec, ctx);
        UniqueConstraint primaryKey =
                deserializeFieldOrNull(jsonNode, PRIMARY_KEY, UniqueConstraint.class, codec, ctx);
        List<Index> indexes =
                deserializeListOrEmpty(jsonNode, INDEXES, DefaultIndex.class, codec, ctx);

        return new ResolvedSchema(columns, watermarkSpecs, primaryKey, indexes);
    }
}
