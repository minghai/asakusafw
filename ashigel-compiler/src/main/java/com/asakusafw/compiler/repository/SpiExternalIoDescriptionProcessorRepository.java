/**
 * Copyright 2011 Asakusa Framework Team.
 *
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
package com.asakusafw.compiler.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.compiler.flow.ExternalIoDescriptionProcessor;
import com.asakusafw.compiler.flow.FlowCompilingEnvironment;
import com.asakusafw.vocabulary.external.ExporterDescription;
import com.asakusafw.vocabulary.external.ImporterDescription;
import com.asakusafw.vocabulary.flow.graph.InputDescription;
import com.asakusafw.vocabulary.flow.graph.OutputDescription;

/**
 * Service Provider Interfaceを利用して{@link ExternalIoDescriptionProcessor}を探索するリポジトリー。
 */
public class SpiExternalIoDescriptionProcessorRepository
        extends FlowCompilingEnvironment.Initialized
        implements ExternalIoDescriptionProcessor.Repository {

    static final Logger LOG = LoggerFactory.getLogger(SpiExternalIoDescriptionProcessorRepository.class);

    private List<ExternalIoDescriptionProcessor> processors;

    private Map<Class<?>, ExternalIoDescriptionProcessor> map;

    @Override
    protected void doInitialize() {
        LOG.info("外部入出力のプラグインを読み出します");
        this.processors = new ArrayList<ExternalIoDescriptionProcessor>();
        this.map = new HashMap<Class<?>, ExternalIoDescriptionProcessor>();
        ServiceLoader<ExternalIoDescriptionProcessor> services = ServiceLoader.load(
                ExternalIoDescriptionProcessor.class,
                getEnvironment().getServiceClassLoader());
        for (ExternalIoDescriptionProcessor proc : services) {
            proc.initialize(getEnvironment());
            processors.add(proc);
            Class<?> importerType = proc.getImporterDescriptionType();
            Class<?> exporterType = proc.getExporterDescriptionType();
            if (map.containsKey(importerType)) {
                getEnvironment().error(
                        "インポーター記述{0}はすでに{1}の対象となっているため、{2}は無視されます",
                        importerType.getName(),
                        map.get(importerType).getClass().getName(),
                        proc.getClass().getName());
            } else {
                LOG.debug("{}が利用可能になります ({})",
                        proc.getImporterDescriptionType().getName(),
                        proc.getClass().getName());
                map.put(importerType, proc);
            }
            if (map.containsKey(exporterType)) {
                getEnvironment().error(
                        "エクスポーター記述{0}はすでに{1}の対象となっているため、{2}は無視されます",
                        exporterType.getName(),
                        map.get(exporterType).getClass().getName(),
                        proc.getClass().getName());
            } else {
                LOG.debug("{}が利用可能になります ({})",
                        proc.getExporterDescriptionType().getName(),
                        proc.getClass().getName());
                map.put(exporterType, proc);
            }
        }
    }

    @Override
    public ExternalIoDescriptionProcessor findProcessor(InputDescription description) {
        Precondition.checkMustNotBeNull(description, "description"); //$NON-NLS-1$
        ImporterDescription desc = description.getImporterDescription();
        if (desc == null) {
            return null;
        }
        Class<?> keyClass = desc.getClass();
        return findProcessor(keyClass);
    }

    @Override
    public ExternalIoDescriptionProcessor findProcessor(OutputDescription description) {
        Precondition.checkMustNotBeNull(description, "description"); //$NON-NLS-1$
        ExporterDescription desc = description.getExporterDescription();
        if (desc == null) {
            return null;
        }
        Class<?> keyClass = desc.getClass();
        return findProcessor(keyClass);
    }

    private ExternalIoDescriptionProcessor findProcessor(Class<?> keyClass) {
        assert keyClass != null;
        Class<?> current = keyClass;
        while (current != null) {
            ExternalIoDescriptionProcessor processor = map.get(current);
            if (processor != null) {
                return processor;
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
