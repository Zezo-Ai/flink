---
title: 概览
weight: 1
type: docs
aliases:
  - /zh/dev/connectors/
  - /zh/apis/connectors.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# DataStream Connectors

## 预定义的 Source 和 Sink

一些比较基本的 Source 和 Sink 已经内置在 Flink 里。
[预定义 data sources]({{< ref "docs/dev/datastream/overview" >}}#data-sources) 支持从文件、目录、socket，以及 collections 和 iterators 中读取数据。
[预定义 data sinks]({{< ref "docs/dev/datastream/overview" >}}#data-sinks) 支持把数据写入文件、标准输出（stdout）、标准错误输出（stderr）和 socket。

## 附带的连接器Flink Project Connectors

连接器可以和多种多样的第三方系统进行交互。目前支持以下系统。
Currently these systems are supported as part of the Apache Flink project:

 * [Apache Kafka]({{< ref "docs/connectors/datastream/kafka" >}}) (source/sink)
 * [Apache Cassandra]({{< ref "docs/connectors/datastream/cassandra" >}}) (source/sink)
 * [Amazon DynamoDB]({{< ref "docs/connectors/datastream/dynamodb" >}}) (sink)
 * [Amazon Kinesis Data Streams]({{< ref "docs/connectors/datastream/kinesis" >}}) (source/sink)
 * [Amazon Kinesis Data Firehose]({{< ref "docs/connectors/datastream/firehose" >}}) (sink)
 * [DataGen]({{< ref "docs/connectors/datastream/datagen" >}}) (source)
 * [Elasticsearch]({{< ref "docs/connectors/datastream/elasticsearch" >}}) (sink)
 * [Opensearch]({{< ref "docs/connectors/datastream/opensearch" >}}) (sink)
 * [FileSystem]({{< ref "docs/connectors/datastream/filesystem" >}}) (sink)
 * [RabbitMQ]({{< ref "docs/connectors/datastream/rabbitmq" >}}) (source/sink)
 * [Google PubSub]({{< ref "docs/connectors/datastream/pubsub" >}}) (source/sink)
 * [Hybrid Source]({{< ref "docs/connectors/datastream/hybridsource" >}}) (source)
 * [Apache Pulsar]({{< ref "docs/connectors/datastream/pulsar" >}}) (source)
 * [JDBC]({{< ref "docs/connectors/datastream/jdbc" >}}) (sink)
 * [MongoDB]({{< ref "docs/connectors/datastream/mongodb" >}}) (source/sink)
 * [Prometheus]({{< ref "docs/connectors/datastream/prometheus" >}}) (sink)

请记住，在使用一种连接器时，通常需要额外的第三方组件，比如：数据存储服务器或者消息队列。
要注意这些列举的连接器是 Flink 工程的一部分，包含在发布的源码中，但是不包含在二进制发行版中。
更多说明可以参考对应的子部分。

{{< hint info >}}
由于 flink-connector-base 依赖已经在 flink-dist 中提供，
在<a href="https://issues.apache.org/jira/browse/FLINK-30400">FLINK-30400</a>完成后，
这些外部连接器开始停止打包 flink-connector-base 依赖。
如果需要在本地环境测试运行，请确保 flink-connector-base 依赖被正确的提供，而且能在 classpath 下找到。
{{< /hint >}}

## Apache Bahir 中的连接器

Flink 还有些一些额外的连接器通过 [Apache Bahir](https://bahir.apache.org/) 发布, 包括:

 * [Apache ActiveMQ](https://bahir.apache.org/docs/flink/current/flink-streaming-activemq/) (source/sink)
 * [Apache Flume](https://bahir.apache.org/docs/flink/current/flink-streaming-flume/) (sink)
 * [Redis](https://bahir.apache.org/docs/flink/current/flink-streaming-redis/) (sink)
 * [Akka](https://bahir.apache.org/docs/flink/current/flink-streaming-akka/) (sink)
 * [Netty](https://bahir.apache.org/docs/flink/current/flink-streaming-netty/) (source)

## 连接Flink的其他方法

### 异步 I/O

使用connector并不是唯一可以使数据进入或者流出Flink的方式。
一种常见的模式是从外部数据库或者 Web 服务查询数据得到初始数据流，然后通过 `Map` 或者 `FlatMap` 对初始数据流进行丰富和增强。
Flink 提供了[异步 I/O]({{< ref "docs/dev/datastream/operators/asyncio" >}}) API 来让这个过程更加简单、高效和稳定。

{{< top >}}
