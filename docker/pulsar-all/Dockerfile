#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

FROM busybox as pulsar-all

ARG PULSAR_IO_DIR
ARG PULSAR_OFFLOADER_TARBALL

ADD ${PULSAR_IO_DIR} /connectors
ADD ${PULSAR_OFFLOADER_TARBALL} /
RUN mv /apache-pulsar-offloaders-*/offloaders /offloaders

FROM apachepulsar/pulsar:latest
COPY --from=pulsar-all /connectors/pulsar-io-elastic-search-*.nar /pulsar/connectors/
COPY --from=pulsar-all /connectors/pulsar-io-kinesis-*.nar /pulsar/connectors/
# Exclude kafka-connect connector
COPY --from=pulsar-all "/connectors/pulsar-io-kafka-[^connect-adaptor*]*.nar" /pulsar/connectors/
COPY --from=pulsar-all /connectors/pulsar-io-jdbc-*.nar /pulsar/connectors/
COPY --from=pulsar-all /connectors/pulsar-io-debezium-*.nar /pulsar/connectors/
COPY --from=pulsar-all /connectors/pulsar-io-data-generator-*.nar /pulsar/connectors/
COPY --from=pulsar-all /connectors/pulsar-io-http-*.nar /pulsar/connectors/
COPY --from=pulsar-all /offloaders /pulsar/offloaders
