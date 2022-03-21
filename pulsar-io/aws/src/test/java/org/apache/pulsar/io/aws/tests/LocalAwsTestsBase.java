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
package org.apache.pulsar.io.aws.tests;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

public class LocalAwsTestsBase {
    /*private static final LocalstackDockerAnnotationProcessor PROCESSOR = new LocalstackDockerAnnotationProcessor();
    private Localstack localstackDocker;

    public LocalstackTestRunner(Class<?> klass) throws InitializationError {
        super(klass);
        this.localstackDocker = Localstack.INSTANCE;
    }

    public void run(RunNotifier notifier) {
        try {
            LocalstackDockerConfiguration dockerConfig = PROCESSOR.process(this.getTestClass().getJavaClass());
            this.localstackDocker.startup(dockerConfig);
            super.run(notifier);
        } finally {
            this.localstackDocker.stop();
        }

    }*/

}
