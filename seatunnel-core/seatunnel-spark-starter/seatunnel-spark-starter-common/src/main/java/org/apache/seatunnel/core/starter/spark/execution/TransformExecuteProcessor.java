/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.core.starter.spark.execution;

import org.apache.seatunnel.shade.com.typesafe.config.Config;

import org.apache.seatunnel.api.common.JobContext;
import org.apache.seatunnel.api.configuration.ReadonlyConfig;
import org.apache.seatunnel.api.configuration.util.ConfigValidator;
import org.apache.seatunnel.api.table.factory.TableTransformFactory;
import org.apache.seatunnel.api.table.factory.TableTransformFactoryContext;
import org.apache.seatunnel.api.table.type.SeaTunnelDataType;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.transform.SeaTunnelTransform;
import org.apache.seatunnel.core.starter.exception.TaskExecuteException;
import org.apache.seatunnel.core.starter.execution.PluginUtil;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelTransformPluginDiscovery;
import org.apache.seatunnel.translation.spark.serialization.SeaTunnelRowConverter;
import org.apache.seatunnel.translation.spark.utils.TypeConverterUtils;

import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.StructType;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.apache.seatunnel.api.common.CommonOptions.RESULT_TABLE_NAME;

@Slf4j
public class TransformExecuteProcessor
        extends SparkAbstractPluginExecuteProcessor<TableTransformFactory> {

    protected TransformExecuteProcessor(
            SparkRuntimeEnvironment sparkRuntimeEnvironment,
            JobContext jobContext,
            List<? extends Config> pluginConfigs) {
        super(sparkRuntimeEnvironment, jobContext, pluginConfigs);
    }

    @Override
    protected List<TableTransformFactory> initializePlugins(List<? extends Config> pluginConfigs) {
        SeaTunnelTransformPluginDiscovery transformPluginDiscovery =
                new SeaTunnelTransformPluginDiscovery();
        List<URL> pluginJars = new ArrayList<>();
        List<TableTransformFactory> transforms =
                pluginConfigs.stream()
                        .map(
                                transformConfig ->
                                        PluginUtil.createTransformFactory(
                                                transformPluginDiscovery,
                                                transformConfig,
                                                pluginJars))
                        .distinct()
                        .collect(Collectors.toList());
        sparkRuntimeEnvironment.registerPlugin(pluginJars);
        return transforms;
    }

    @Override
    public List<DatasetTableInfo> execute(List<DatasetTableInfo> upstreamDataStreams)
            throws TaskExecuteException {
        if (plugins.isEmpty()) {
            return upstreamDataStreams;
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        DatasetTableInfo input = upstreamDataStreams.get(0);
        for (int i = 0; i < plugins.size(); i++) {
            try {
                Config pluginConfig = pluginConfigs.get(i);
                DatasetTableInfo dataset =
                        fromSourceTable(pluginConfig, sparkRuntimeEnvironment, upstreamDataStreams)
                                .orElse(input);
                TableTransformFactory factory = plugins.get(i);
                TableTransformFactoryContext context =
                        new TableTransformFactoryContext(
                                Collections.singletonList(dataset.getCatalogTable()),
                                ReadonlyConfig.fromConfig(pluginConfig),
                                classLoader);
                ConfigValidator.of(context.getOptions()).validate(factory.optionRule());
                SeaTunnelTransform transform = factory.createTransform(context).createTransform();

                Dataset<Row> inputDataset = sparkTransform(transform, dataset);
                registerInputTempView(pluginConfig, inputDataset);
                upstreamDataStreams.add(
                        new DatasetTableInfo(
                                inputDataset,
                                transform.getProducedCatalogTable(),
                                pluginConfig.hasPath(RESULT_TABLE_NAME.key())
                                        ? pluginConfig.getString(RESULT_TABLE_NAME.key())
                                        : null));
            } catch (Exception e) {
                throw new TaskExecuteException(
                        String.format(
                                "SeaTunnel transform task: %s execute error",
                                plugins.get(i).factoryIdentifier()),
                        e);
            }
        }
        return upstreamDataStreams;
    }

    private Dataset<Row> sparkTransform(SeaTunnelTransform transform, DatasetTableInfo tableInfo)
            throws IOException {
        Dataset<Row> stream = tableInfo.getDataset();
        SeaTunnelDataType<?> seaTunnelDataType = tableInfo.getCatalogTable().getSeaTunnelRowType();
        transform.setTypeInfo(seaTunnelDataType);
        StructType outputSchema =
                (StructType) TypeConverterUtils.convert(transform.getProducedType());
        SeaTunnelRowConverter inputRowConverter = new SeaTunnelRowConverter(seaTunnelDataType);
        SeaTunnelRowConverter outputRowConverter =
                new SeaTunnelRowConverter(transform.getProducedType());
        ExpressionEncoder<Row> encoder = RowEncoder.apply(outputSchema);
        Dataset<Row> result =
                stream.mapPartitions(
                                (MapPartitionsFunction<Row, Row>)
                                        (Iterator<Row> rowIterator) -> {
                                            TransformIterator iterator =
                                                    new TransformIterator(
                                                            rowIterator,
                                                            transform,
                                                            outputSchema,
                                                            inputRowConverter,
                                                            outputRowConverter);
                                            return iterator;
                                        },
                                encoder)
                        .filter(Objects::nonNull);
        return result;
    }

    private static class TransformIterator implements Iterator<Row>, Serializable {
        private Iterator<Row> sourceIterator;
        private SeaTunnelTransform<SeaTunnelRow> transform;
        private StructType structType;
        private SeaTunnelRowConverter inputRowConverter;
        private SeaTunnelRowConverter outputRowConverter;

        public TransformIterator(
                Iterator<Row> sourceIterator,
                SeaTunnelTransform<SeaTunnelRow> transform,
                StructType structType,
                SeaTunnelRowConverter inputRowConverter,
                SeaTunnelRowConverter outputRowConverter) {
            this.sourceIterator = sourceIterator;
            this.transform = transform;
            this.structType = structType;
            this.inputRowConverter = inputRowConverter;
            this.outputRowConverter = outputRowConverter;
        }

        @Override
        public boolean hasNext() {
            return sourceIterator.hasNext();
        }

        @Override
        public Row next() {
            try {
                Row row = sourceIterator.next();
                SeaTunnelRow seaTunnelRow =
                        inputRowConverter.reconvert(
                                new SeaTunnelRow(((GenericRowWithSchema) row).values()));
                seaTunnelRow = (SeaTunnelRow) transform.map(seaTunnelRow);
                if (seaTunnelRow == null) {
                    return null;
                }
                seaTunnelRow = outputRowConverter.convert(seaTunnelRow);
                return new GenericRowWithSchema(seaTunnelRow.getFields(), structType);
            } catch (Exception e) {
                throw new TaskExecuteException("Row convert failed, caused: " + e.getMessage(), e);
            }
        }
    }
}
