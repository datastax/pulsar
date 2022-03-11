    /**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;

import java.util.*;

/**
 * Convert an AVRO GenericRecord to a JsonNode.
 */
public class JsonConverter {

    private static Map<String, LogicalTypeConverter> logicalTypeConverters = new HashMap<>();
    private static final JsonNodeFactory jsonNodeFactory = JsonNodeFactory.withExactBigDecimals(true);

    public static JsonNode topLevelMerge(JsonNode n1, JsonNode n2) {
        ObjectNode objectNode = jsonNodeFactory.objectNode();
        n1.fieldNames().forEachRemaining(f -> objectNode.put(f, n1.get(f)));
        n2.fieldNames().forEachRemaining(f -> objectNode.put(f, n2.get(f)));
        return objectNode;
    }

    public static JsonNode toJson(ElasticSearchConfig elasticSearchConfig, GenericRecord genericRecord) {
        if (genericRecord == null) {
            return null;
        }
        ObjectNode objectNode = jsonNodeFactory.objectNode();
        for(Schema.Field field: genericRecord.getSchema().getFields()) {
            objectNode.set(field.name(), toJson(elasticSearchConfig, field.schema(), genericRecord.get(field.name())));
        }
        return objectNode;
    }

    public static JsonNode toJson(ElasticSearchConfig elasticSearchConfig, Schema schema, Object value) {
        if (value == null) {
            return jsonNodeFactory.nullNode();
        }
        if (schema.getLogicalType() != null) {
            if (logicalTypeConverters.containsKey(schema.getLogicalType().getName())) {
                return logicalTypeConverters.get(schema.getLogicalType().getName()).toJson(schema, value);
            } else if (!elasticSearchConfig.isIgnoreUnsupportedFields()) {
                throw new UnsupportedOperationException("Unknown AVRO logical type=" + schema.getType());
            }
        }
        try {
            switch (schema.getType()) {
                case NULL: // this should not happen
                    return jsonNodeFactory.nullNode();
                case INT:
                    return jsonNodeFactory.numberNode((Integer) value);
                case LONG:
                    return jsonNodeFactory.numberNode((Long) value);
                case DOUBLE:
                    return jsonNodeFactory.numberNode((Double) value);
                case FLOAT:
                    return jsonNodeFactory.numberNode((Float) value);
                case BOOLEAN:
                    return jsonNodeFactory.booleanNode((Boolean) value);
                case BYTES:
                    return jsonNodeFactory.binaryNode((byte[]) value);
                case FIXED:
                    return jsonNodeFactory.binaryNode(((GenericFixed) value).bytes());
                case ARRAY: {
                    Schema elementSchema = schema.getElementType();
                    ArrayNode arrayNode = jsonNodeFactory.arrayNode();
                    for (Object elem : (Object[]) value) {
                        JsonNode fieldValue = toJson(elasticSearchConfig, elementSchema, elem);
                        arrayNode.add(fieldValue);
                    }
                    return arrayNode;
                }
                case MAP: {
                    Map<String, Object> map = (Map<String, Object>) value;
                    ObjectNode objectNode = jsonNodeFactory.objectNode();
                    for (Map.Entry<String, Object> entry : map.entrySet()) {
                        JsonNode jsonNode = toJson(elasticSearchConfig, schema.getValueType(), entry.getValue());
                        objectNode.set(entry.getKey(), jsonNode);
                    }
                    return objectNode;
                }
                case RECORD:
                    return toJson(elasticSearchConfig, (GenericRecord) value);
                case UNION:
                    for (Schema s : schema.getTypes()) {
                        if (s.getType() == Schema.Type.NULL)
                            continue;
                        return toJson(elasticSearchConfig, s, value);
                    }
                    // this case should not happen
                    return jsonNodeFactory.textNode(value.toString());
                case ENUM: // GenericEnumSymbol
                case STRING:  // can be a String or org.apache.avro.util.Utf8
                    return jsonNodeFactory.textNode(value.toString());
                default:
                    if (!elasticSearchConfig.isIgnoreUnsupportedFields()) {
                        throw new UnsupportedOperationException("Unknown AVRO schema type=" + schema.getType());
                    } else {
                        return jsonNodeFactory.nullNode();
                    }
            }
        } catch (ClassCastException error) {
            throw new IllegalArgumentException("Error while converting a value of type " + value.getClass() + " to a " + schema.getType()
                    + ": " + error, error);
        }
    }

    abstract static class LogicalTypeConverter {
        abstract JsonNode toJson(Schema schema, Object value);
    }

    private static void checkType(Object value, String name, Class expected) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid type for " + name + ", expected " + expected.getName() + " but was NULL");
        }
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException("Invalid type for " + name + ", expected " + expected.getName() + " but was " + value.getClass());
        }
    }

    static {
        logicalTypeConverters.put("date", new JsonConverter.LogicalTypeConverter() {
            @Override
            JsonNode toJson(Schema schema, Object value) {
                checkType(value, "date", Integer.class);
                Integer daysFromEpoch = (Integer)value;
                return jsonNodeFactory.numberNode(daysFromEpoch);
            }
        });
        logicalTypeConverters.put("time-millis", new JsonConverter.LogicalTypeConverter() {
            @Override
            JsonNode toJson(Schema schema, Object value) {
                checkType(value, "time-millis", Integer.class);
                Integer timeMillis = (Integer)value;
                return jsonNodeFactory.numberNode(timeMillis);
            }
        });
        logicalTypeConverters.put("time-micros", new JsonConverter.LogicalTypeConverter() {
            @Override
            JsonNode toJson(Schema schema, Object value) {
                checkType(value, "time-micros", Long.class);
                Long timeMicro = (Long)value;
                return jsonNodeFactory.numberNode(timeMicro);
            }
        });
        logicalTypeConverters.put("timestamp-millis", new JsonConverter.LogicalTypeConverter() {
            @Override
            JsonNode toJson(Schema schema, Object value) {
                checkType(value, "timestamp-millis", Long.class);
                Long epochMillis = (Long)value;
                return jsonNodeFactory.numberNode(epochMillis);
            }
        });
        logicalTypeConverters.put("timestamp-micros", new JsonConverter.LogicalTypeConverter() {
            @Override
            JsonNode toJson(Schema schema, Object value) {
                checkType(value, "timestamp-micros", Long.class);
                Long epochMillis = (Long)value;
                return jsonNodeFactory.numberNode(epochMillis);
            }
        });
        logicalTypeConverters.put("uuid", new JsonConverter.LogicalTypeConverter() {
            @Override
            JsonNode toJson(Schema schema, Object value) {
                return jsonNodeFactory.textNode(value == null ? null : value.toString());
            }
        });
    }

    public static ArrayNode toJsonArray(JsonNode jsonNode, List<String> fields) {
        ArrayNode arrayNode = jsonNodeFactory.arrayNode();
        Iterator<String>  it = jsonNode.fieldNames();
        while (it.hasNext()) {
            String fieldName = it.next();
            if (fields.contains(fieldName)) {
                arrayNode.add(jsonNode.get(fieldName));
            }
        }
        return arrayNode;
    }
}
