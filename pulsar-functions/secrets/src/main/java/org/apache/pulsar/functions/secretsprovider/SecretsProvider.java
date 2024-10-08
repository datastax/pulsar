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
package org.apache.pulsar.functions.secretsprovider;

import java.util.Map;

/**
 * This file defines the SecretsProvider interface. This interface is used by the function
 * instances/containers to actually fetch the secrets. What SecretsProvider to use is
 * decided by the SecretsProviderConfigurator.
 */
public interface SecretsProvider {
    /**
     * Initialize the SecretsProvider.
     *
     * @return
     */
    default void init(Map<String, String> config) {}

    /**
     * Fetches a secret.
     *
     * @return The actual secret
     */
    String provideSecret(String secretName, Object pathToSecret);

    /**
     * If the passed value is formatted as a reference to a secret, as defined by the implementation, return the
     * referenced secret. If the value is not formatted as a secret reference or the referenced secret does not exist,
     * return null.
     *
     * @param value a config value that may be formatted as a reference to a secret
     * @return the materialized secret. Otherwise, null.
     */
    default String interpolateSecretForValue(String value) {
        return null;
    }
}