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
package io.trino.tests.product.launcher.env.environment;

import com.google.common.collect.ImmutableList;
import io.trino.tests.product.launcher.docker.DockerFiles;
import io.trino.tests.product.launcher.env.Debug;
import io.trino.tests.product.launcher.env.DockerContainer;
import io.trino.tests.product.launcher.env.Environment;
import io.trino.tests.product.launcher.env.EnvironmentConfig;
import io.trino.tests.product.launcher.env.EnvironmentProvider;
import io.trino.tests.product.launcher.env.ServerPackage;
import io.trino.tests.product.launcher.env.common.Hadoop;
import io.trino.tests.product.launcher.env.common.Standard;
import io.trino.tests.product.launcher.env.common.TestsEnvironment;

import javax.inject.Inject;

import java.io.File;

import static io.trino.tests.product.launcher.env.EnvironmentContainers.COORDINATOR;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.WORKER;
import static io.trino.tests.product.launcher.env.common.Hadoop.CONTAINER_PRESTO_HIVE_PROPERTIES;
import static io.trino.tests.product.launcher.env.common.Hadoop.CONTAINER_PRESTO_ICEBERG_PROPERTIES;
import static io.trino.tests.product.launcher.env.common.Standard.CONTAINER_PRESTO_CONFIG_PROPERTIES;
import static io.trino.tests.product.launcher.env.common.Standard.CONTAINER_PRESTO_JVM_CONFIG;
import static io.trino.tests.product.launcher.env.common.Standard.createPrestoContainer;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.utility.MountableFile.forHostPath;

@TestsEnvironment
public final class Multinode
        extends EnvironmentProvider
{
    private final DockerFiles dockerFiles;
    private final DockerFiles.ResourceProvider configDir;

    private final String imagesVersion;
    private final File serverPackage;
    private final boolean debug;

    @Inject
    public Multinode(
            DockerFiles dockerFiles,
            Standard standard,
            Hadoop hadoop,
            EnvironmentConfig environmentConfig,
            @ServerPackage File serverPackage,
            @Debug boolean debug)
    {
        super(ImmutableList.of(standard, hadoop));
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
        this.configDir = dockerFiles.getDockerFilesHostDirectory("conf/environment/multinode");
        this.imagesVersion = requireNonNull(environmentConfig, "environmentConfig is null").getImagesVersion();
        this.serverPackage = requireNonNull(serverPackage, "serverPackage is null");
        this.debug = debug;
    }

    @Override
    public void extendEnvironment(Environment.Builder builder)
    {
        builder.configureContainer(COORDINATOR, container -> {
            container
                    .withCopyFileToContainer(forHostPath(configDir.getPath("multinode-master-jvm.config")), CONTAINER_PRESTO_JVM_CONFIG)
                    .withCopyFileToContainer(forHostPath(configDir.getPath("multinode-master-config.properties")), CONTAINER_PRESTO_CONFIG_PROPERTIES);
        });

        builder.addContainer(createPrestoWorker());
    }

    @SuppressWarnings("resource")
    private DockerContainer createPrestoWorker()
    {
        return createPrestoContainer(dockerFiles, serverPackage, debug, "ghcr.io/trinodb/testing/centos7-oj11:" + imagesVersion, WORKER)
                .withCopyFileToContainer(forHostPath(configDir.getPath("multinode-worker-jvm.config")), CONTAINER_PRESTO_JVM_CONFIG)
                .withCopyFileToContainer(forHostPath(configDir.getPath("multinode-worker-config.properties")), CONTAINER_PRESTO_CONFIG_PROPERTIES)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("common/hadoop/hive.properties")), CONTAINER_PRESTO_HIVE_PROPERTIES)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("common/hadoop/iceberg.properties")), CONTAINER_PRESTO_ICEBERG_PROPERTIES);
    }
}
