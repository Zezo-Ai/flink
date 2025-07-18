---
title: 从源码构建 Flink
weight: 21
type: docs
aliases:
  - /zh/flinkDev/building.html
  - /zh/start/building.html
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

# 从源码构建 Flink

本篇主题是如何从版本 {{< version >}} 的源码构建 Flink。



## 构建 Flink

首先需要准备源码。可以[从发布版本下载源码]({{< downloads >}}) 或者[从 Git 库克隆 Flink 源码]({{< github_repo >}})。

还需要准备 **Maven 3.8.6** 和 **JDK** (Java开发套件)。Flink 依赖 **Java 11** 来进行构建。

输入以下命令从 Git 克隆代码

```bash
git clone {{< github_repo >}}
```

最简单的构建 Flink 的方法是执行如下命令：

```bash
mvn clean install -DskipTests
```

上面的 [Maven](http://maven.apache.org) 指令（`mvn`）首先删除（`clean`）所有存在的构建，然后构建一个新的 Flink 运行包（`install`）。

为了加速构建，可以：
- 使用 ' -DskipTests' 跳过测试
- 使用 `fast` Maven profile 跳过 QA 的插件和 JavaDocs 的生成
- 使用 `skip-webui-build` Maven profile 跳过 WebUI 编译
- 使用 Maven 并行构建功能，比如 'mvn package -T 1C' 会尝试并行使用多核 CPU，同时让每一个 CPU 核构建1个模块。{{< hint warning >}}maven-shade-plugin 现存的 bug 可能会在并行构建时产生死锁。建议分2步进行构建：首先使用并行方式运行 `mvn validate/test-compile/test`，然后使用单线程方式运行 `mvn package/verify/install`。{{< /hint >}} 

构建脚本如下：
```bash
mvn clean install -DskipTests -Dfast -Pskip-webui-build -T 1C
```
`fast` 和 `skip-webui-build` 这两个 Maven profiles 对整体构建时间影响比较大，特别是在存储设备比较慢的机器上，因为对应的任务会读写很多小文件。

<a name="build-pyflink"/>

## 构建 PyFlink

#### 先决条件

1. 构建 Flink

    如果想构建一个可用于 pip 安装的 PyFlink 包，需要先构建 Flink 工程，如 [构建 Flink](#build-flink) 中所述。

2. Python 的版本为 3.9, 3.10, 3.11 或者 3.12.

    ```shell
    $ python --version
    # the version printed here must be 3.9, 3.10, 3.11 or 3.12
    ```

3. 构建 PyFlink 的 Cython 扩展模块（可选的）

    为了构建 PyFlink 的 Cython 扩展模块，需要 C 编译器。在不同操作系统上安装 C 编译器的方式略有不同：

    * **Linux** Linux 操作系统通常预装有 GCC。否则，需要手动安装。例如，可以在 Ubuntu 或 Debian 上使用命令`sudo apt-get install build-essential`安装。

    * **Mac OS X** 要在 Mac OS X 上安装 GCC，你需要下载并安装 [Xcode 命令行工具](https://developer.apple.com/downloads/index.action
    )，该工具可在 Apple 的开发人员页面中找到。

    还需要使用以下命令安装依赖项：

    ```shell
    $ python -m pip install --group flink-python/pyproject.toml:dev
    ```

#### 安装

进入 Flink 源码根目录，并执行以下命令，构建 `apache-flink` 和 `apache-flink-libraries` 的源码发布包和 wheel 包：

```bash
cd flink-python; python setup.py sdist bdist_wheel; cd apache-flink-libraries; python setup.py sdist; cd ..;
```

构建好的 `apache-flink-libraries` 的源码发布包位于 `./flink-python/apache-flink-libraries/dist/` 目录下。可使用 pip 安装，比如:

```bash
python -m pip install apache-flink-libraries/dist/*.tar.gz
```

构建好的 `apache-flink` 的源码发布包和 wheel 包位于 `./flink-python/dist/` 目录下。它们均可使用 pip 安装，比如:

```bash
python -m pip install dist/*.whl
```

## Scala 版本

{{< hint info >}}
只是用 Java 库和 API 的用户可以*忽略*这一部分。
{{< /hint >}}

Flink 有使用 [Scala](http://scala-lang.org) 来写的 API，库和运行时模块。使用 Scala API 和库的同学必须配置 Flink 的 Scala 版本和自己的 Flink 版本（因为 Scala 
并不严格的向后兼容）。

从 1.15 版本开始，Flink 已经不再支持使用Scala 2.11编译，默认使用 2.12 来构建。

要针对特定的二进制 Scala 版本进行构建，可以使用
```bash
mvn clean install -DskipTests -Dscala.version=<scala version>
```

{{< top >}}

## 加密的文件系统

如果你的 home 目录是加密的，可能遇到如下异常 `java.io.IOException: File name too long`。一些像 Ubuntu 的 enfs 这样的加密文件系统因为不支持长文件名会产生这个异常。

解决方法是添加如下内容到 pom.xml 文件中出现这个错误的模块的编译器配置项下。

```xml
<args>
    <arg>-Xmax-classfile-name</arg>
    <arg>128</arg>
</args>
```

例如，如果错误出现在 `flink-yarn` 模块下，上述的代码需要添加到 `scala-maven-plugin` 的 `<configuration>` 项下。请查看[这个问题](https://issues.apache.org/jira/browse/FLINK-2003)的链接获取更多信息。

{{< top >}}
