/*
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
package org.apache.pulsar.common.compression;

import java.util.EnumMap;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.CompressionType;

/**
 * Provider of compression codecs used in Pulsar.
 *
 * @see CompressionCodecNone
 * @see CompressionCodecLZ4
 * @see CompressionCodecZLib
 * @see CompressionCodecZstd
 * @see CompressionCodecSnappy
 */
@UtilityClass
@Slf4j
public class CompressionCodecProvider {
    private static final EnumMap<org.apache.pulsar.common.api.proto.CompressionType, CompressionCodec> codecs;
    private static final EnumMap<org.apache.pulsar.common.api.proto.CompressionType, CompressionCodec> jniCodecs;

    private static final int jniRecommendedSize = jniRecommendedMinSize();

    static {
        codecs = new EnumMap<>(org.apache.pulsar.common.api.proto.CompressionType.class);
        codecs.put(org.apache.pulsar.common.api.proto.CompressionType.NONE, new CompressionCodecNone());
        codecs.put(org.apache.pulsar.common.api.proto.CompressionType.LZ4, new CompressionCodecLZ4());
        codecs.put(org.apache.pulsar.common.api.proto.CompressionType.ZLIB, new CompressionCodecZLib());
        codecs.put(org.apache.pulsar.common.api.proto.CompressionType.ZSTD, new CompressionCodecZstd());
        codecs.put(org.apache.pulsar.common.api.proto.CompressionType.SNAPPY, new CompressionCodecSnappy());

        jniCodecs = new EnumMap<>(org.apache.pulsar.common.api.proto.CompressionType.class);
        if (allowJni()) {
            try {
                // avoid exceptions from lz4 being swallowed until its use
                net.jpountz.util.Native.load();

                jniCodecs.put(org.apache.pulsar.common.api.proto.CompressionType.LZ4, new CompressionCodecLZ4JNI());
                jniCodecs.put(org.apache.pulsar.common.api.proto.CompressionType.ZSTD, new CompressionCodecZstdJNI());
                jniCodecs.put(org.apache.pulsar.common.api.proto.CompressionType.SNAPPY,
                        new CompressionCodecSnappyJNI());
            } catch (Throwable t) {
                log.error("Failed to load JNI compression codecs", t);
            }
        }
    }

    public static boolean allowJni() {
        return System.getProperty("pulsar.compression.allowJni", "false").equals("true");
    }

    public static int jniRecommendedMinSize() {
        return Integer.parseInt(System.getProperty("pulsar.compression.jniMinSize", "10240"));
    }

    public static CompressionCodec getCompressionCodec(org.apache.pulsar.common.api.proto.CompressionType type) {
        return codecs.get(type);
    }

    public static CompressionCodec getCompressionCodec(CompressionType type, int size) {
        return getCompressionCodec(convertToWireProtocol(type), size);
    }

    public static CompressionCodec getCompressionCodec(org.apache.pulsar.common.api.proto.CompressionType type,
                                                       int size) {
        if (size > jniRecommendedSize && jniCodecs.containsKey(type)) {
            return jniCodecs.get(type);
        }
        return codecs.get(type);
    }

    public static CompressionCodec getCompressionCodec(CompressionType type) {
        return getCompressionCodec(convertToWireProtocol(type));
    }


    public static org.apache.pulsar.common.api.proto.CompressionType convertToWireProtocol(
            CompressionType compressionType) {
        switch (compressionType) {
        case NONE:
            return org.apache.pulsar.common.api.proto.CompressionType.NONE;
        case LZ4:
            return org.apache.pulsar.common.api.proto.CompressionType.LZ4;
        case ZLIB:
            return org.apache.pulsar.common.api.proto.CompressionType.ZLIB;
        case ZSTD:
            return org.apache.pulsar.common.api.proto.CompressionType.ZSTD;
        case SNAPPY:
            return org.apache.pulsar.common.api.proto.CompressionType.SNAPPY;

        default:
            throw new RuntimeException("Invalid compression type");
        }
    }

    public static CompressionType convertFromWireProtocol(
            org.apache.pulsar.common.api.proto.CompressionType compressionType) {
        switch (compressionType) {
        case NONE:
            return CompressionType.NONE;
        case LZ4:
            return CompressionType.LZ4;
        case ZLIB:
            return CompressionType.ZLIB;
        case ZSTD:
            return CompressionType.ZSTD;
        case SNAPPY:
            return CompressionType.SNAPPY;

        default:
            throw new RuntimeException("Invalid compression type");
        }
    }
}
