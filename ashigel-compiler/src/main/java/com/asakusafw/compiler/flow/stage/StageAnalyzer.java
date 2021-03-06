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
package com.asakusafw.compiler.flow.stage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.compiler.common.NameGenerator;
import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.compiler.flow.FlowCompilingEnvironment;
import com.asakusafw.compiler.flow.FlowElementProcessor;
import com.asakusafw.compiler.flow.FlowElementProcessor.Kind;
import com.asakusafw.compiler.flow.plan.FlowBlock;
import com.asakusafw.compiler.flow.plan.FlowGraphUtil;
import com.asakusafw.compiler.flow.plan.StageBlock;
import com.asakusafw.compiler.flow.stage.StageModel.Factor;
import com.asakusafw.compiler.flow.stage.StageModel.Fragment;
import com.asakusafw.compiler.flow.stage.StageModel.MapUnit;
import com.asakusafw.compiler.flow.stage.StageModel.ReduceUnit;
import com.asakusafw.compiler.flow.stage.StageModel.ResourceFragment;
import com.asakusafw.compiler.flow.stage.StageModel.Sink;
import com.asakusafw.compiler.flow.stage.StageModel.Unit;
import com.asakusafw.vocabulary.flow.graph.FlowElement;
import com.asakusafw.vocabulary.flow.graph.FlowElementDescription;
import com.asakusafw.vocabulary.flow.graph.FlowElementInput;
import com.asakusafw.vocabulary.flow.graph.FlowElementKind;
import com.asakusafw.vocabulary.flow.graph.FlowElementOutput;
import com.asakusafw.vocabulary.flow.graph.FlowResourceDescription;
import com.ashigeru.util.graph.Graph;
import com.ashigeru.util.graph.Graphs;

/**
 * 各ステージのマップレデュースで行われる内容を解析する。
 */
public class StageAnalyzer {

    static final Logger LOG = LoggerFactory.getLogger(StageAnalyzer.class);

    private FlowCompilingEnvironment environment;

    private boolean sawError;

    /**
     * インスタンスを生成する。
     * @param environment 環境オブジェクト
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public StageAnalyzer(FlowCompilingEnvironment environment) {
        Precondition.checkMustNotBeNull(environment, "environment"); //$NON-NLS-1$
        this.environment = environment;
    }

    /**
     * 現在までにエラーが発生していた場合に{@code true}を返す。
     * @return 現在までにエラーが発生していた場合に{@code true}
     */
    public boolean hasError() {
        return sawError;
    }

    /**
     * 現在までに発生したエラーの情報をクリアする。
     * @see #hasError()
     */
    public void clearError() {
        sawError = false;
    }

    /**
     * 指定のステージブロックを解析し、対象ステージのマップレデュースプログラムに関する情報を返す。
     * @param block 対象のステージブロック
     * @param shuffle シャッフルの情報、存在しない場合は{@code null}
     * @return マップレデュースプログラムに関する情報、解析に失敗した場合は{@code null}
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public StageModel analyze(StageBlock block, ShuffleModel shuffle) {
        Precondition.checkMustNotBeNull(block, "block"); //$NON-NLS-1$
        LOG.debug("{}のマップレデュースプログラムを分析しています", block);

        NameGenerator names = new NameGenerator(environment.getModelFactory());
        List<MapUnit> mapUnits = new ArrayList<MapUnit>();
        for (FlowBlock flowBlock : block.getMapBlocks()) {
            mapUnits.addAll(collectMapUnits(flowBlock, names));
        }
        mapUnits = composeMapUnits(mapUnits);

        List<ReduceUnit> reduceUnits = new ArrayList<ReduceUnit>();
        for (FlowBlock flowBlock : block.getReduceBlocks()) {
            reduceUnits.addAll(collectReduceUnits(flowBlock, names));
        }

        List<Sink> outputs = new ArrayList<StageModel.Sink>();
        if (block.hasReduceBlocks()) {
            outputs.addAll(collectOutputs(reduceUnits, block.getReduceBlocks(), names));
        } else {
            outputs.addAll(collectOutputs(mapUnits, block.getMapBlocks(), names));
        }
        if (hasError()) {
            return null;
        }
        StageModel model = new StageModel(block, mapUnits, shuffle, reduceUnits, outputs);
        LOG.debug("{}は{}のようにコンパイルされます", block, model);
        return model;
    }

    private List<MapUnit> collectMapUnits(FlowBlock block, NameGenerator names) {
        assert block != null;
        assert names != null;
        LOG.debug("{}を分解してMapperプログラムの情報を抽出します", block);
        Set<FlowElement> startElements = collectFragmentStartElements(block);
        Map<FlowElement, Fragment> fragments = collectFragments(startElements);
        Graph<Fragment> fgraph = buildFragmentGraph(fragments);
        List<MapUnit> units = buildMapUnits(block, fragments, fgraph, names);
        return units;
    }

    private List<MapUnit> composeMapUnits(List<MapUnit> mapUnits) {
        assert mapUnits != null;
        Map<Set<FlowBlock.Output>, List<MapUnit>> sameInputs =
            new HashMap<Set<FlowBlock.Output>, List<MapUnit>>();
        for (MapUnit unit : mapUnits) {
            Set<FlowBlock.Output> sources = new HashSet<FlowBlock.Output>();
            for (FlowBlock.Input input : unit.getInputs()) {
                for (FlowBlock.Connection conn : input.getConnections()) {
                    sources.add(conn.getUpstream());
                }
            }
            List<MapUnit> group = sameInputs.get(sources);
            if (group == null) {
                group = new ArrayList<MapUnit>();
                sameInputs.put(sources, group);
            }
            group.add(unit);
        }
        List<MapUnit> results = new ArrayList<MapUnit>();
        for (Map.Entry<Set<FlowBlock.Output>, List<MapUnit>> entry : sameInputs.entrySet()) {
            Set<FlowBlock.Output> sources = entry.getKey();
            List<MapUnit> group = entry.getValue();
            results.add(compose(sources, group));
        }
        return results;
    }

    private MapUnit compose(Set<FlowBlock.Output> sources, List<MapUnit> group) {
        assert sources != null;
        assert group != null;
        assert group.isEmpty() == false;
        if (group.size() == 1) {
            return group.get(0);
        }
        Set<FlowBlock.Input> sawInputs = new HashSet<FlowBlock.Input>();
        List<FlowBlock.Input> inputs = new ArrayList<FlowBlock.Input>();
        List<Fragment> fragments = new ArrayList<Fragment>();

        for (MapUnit unit : group) {
            fragments.addAll(unit.getFragments());
            for (FlowBlock.Input input : unit.getInputs()) {
                if (sawInputs.contains(input)) {
                    continue;
                }
                sawInputs.add(input);
                inputs.add(input);
            }
        }
        return new MapUnit(inputs, fragments);
    }

    private List<ReduceUnit> collectReduceUnits(FlowBlock block, NameGenerator names) {
        assert block != null;
        assert names != null;
        LOG.debug("{}を分解してReducerプログラムの情報を抽出します", block);
        Set<FlowElement> startElements = collectFragmentStartElements(block);
        Map<FlowElement, Fragment> fragments = collectFragments(startElements);
        Graph<Fragment> fgraph = buildFragmentGraph(fragments);
        List<ReduceUnit> units = buildReduceUnits(block, fragments, fgraph, names);
        return units;
    }

    private List<MapUnit> buildMapUnits(
            FlowBlock block,
            Map<FlowElement, Fragment> fragments,
            Graph<Fragment> fgraph,
            NameGenerator names) {
        assert block != null;
        assert fragments != null;
        assert fgraph != null;
        assert names != null;
        Map<FlowBlock.Input, Graph<Fragment>> streams =
            new LinkedHashMap<FlowBlock.Input, Graph<Fragment>>();
        for (FlowBlock.Input blockInput : block.getBlockInputs()) {
            FlowElementInput input = blockInput.getElementPort();
            Fragment head = fragments.get(input.getOwner());
            Graph<Fragment> subgraph = createSubgraph(head, fgraph);
            streams.put(blockInput, subgraph);
        }
        List<MapUnit> results = new ArrayList<MapUnit>();
        for (Map.Entry<FlowBlock.Input, Graph<Fragment>> entry : streams.entrySet()) {
            FlowBlock.Input input = entry.getKey();
            Graph<Fragment> subgraph = entry.getValue();
            List<Fragment> body = sort(subgraph);
            for (int i = 0, n = body.size(); i < n; i++) {
                body.set(i, body.get(i));
            }
            MapUnit unit = new MapUnit(Collections.singletonList(input), body);
            LOG.debug("{}を元に{}が生成されます", input, unit);
            results.add(unit);
        }
        return results;
    }

    private List<ReduceUnit> buildReduceUnits(
            FlowBlock block,
            Map<FlowElement, Fragment> fragments,
            Graph<Fragment> fgraph,
            NameGenerator names) {
        assert block != null;
        assert fragments != null;
        assert fgraph != null;
        Map<FlowElement, List<FlowBlock.Input>> inputGroups =
            new LinkedHashMap<FlowElement, List<FlowBlock.Input>>();
        for (FlowBlock.Input blockInput : block.getBlockInputs()) {
            FlowElement element = blockInput.getElementPort().getOwner();
            List<FlowBlock.Input> group = inputGroups.get(element);
            if (group == null) {
                group = new ArrayList<FlowBlock.Input>();
                inputGroups.put(element, group);
            }
            group.add(blockInput);
        }

        Map<FlowElement, Graph<Fragment>> streams =
            new HashMap<FlowElement, Graph<Fragment>>();

        for (FlowElement element : inputGroups.keySet()) {
            Fragment head = fragments.get(element);
            Graph<Fragment> subgraph = createSubgraph(head, fgraph);
            streams.put(element, subgraph);
        }
        List<ReduceUnit> results = new ArrayList<ReduceUnit>();
        for (Map.Entry<FlowElement, Graph<Fragment>> entry : streams.entrySet()) {
            FlowElement element = entry.getKey();
            Graph<Fragment> subgraph = entry.getValue();
            List<Fragment> body = sort(subgraph);
            for (int i = 0, n = body.size(); i < n; i++) {
                body.set(i, body.get(i));
            }
            List<FlowBlock.Input> inputs = inputGroups.get(element);
            ReduceUnit unit = new ReduceUnit(inputs, body);
            LOG.debug("{}を元に{}が生成されます", element, unit);
            results.add(unit);
        }
        return results;
    }

    private List<Sink> collectOutputs(
            Collection<? extends Unit<?>> units,
            Collection<FlowBlock> blocks,
            NameGenerator names) {
        assert units != null;
        assert blocks != null;
        assert names != null;
        Set<FlowElementOutput> candidates = new HashSet<FlowElementOutput>();
        for (FlowBlock block : blocks) {
            for (FlowBlock.Output blockOutput : block.getBlockOutputs()) {
                candidates.add(blockOutput.getElementPort());
            }
        }
        Set<FlowElementOutput> outputs = new HashSet<FlowElementOutput>();
        for (Unit<?> unit : units) {
            for (Fragment fragment : unit.getFragments()) {
                for (FlowElementOutput output : fragment.getOutputPorts()) {
                    if (candidates.contains(output)) {
                        outputs.add(output);
                    }
                }
            }
        }

        Map<Set<FlowBlock.Input>, Set<FlowBlock.Output>> opposites =
            new HashMap<Set<FlowBlock.Input>, Set<FlowBlock.Output>>();
        for (FlowBlock block : blocks) {
            for (FlowBlock.Output blockOutput : block.getBlockOutputs()) {
                // 実際に出力につながらないポートは除外
                if (outputs.contains(blockOutput.getElementPort()) == false) {
                    continue;
                }
                // 同じ入力への出力でまとめる
                Set<FlowBlock.Input> downstream = new HashSet<FlowBlock.Input>();
                for (FlowBlock.Connection connection : blockOutput.getConnections()) {
                    downstream.add(connection.getDownstream());
                }
                Set<FlowBlock.Output> upstream = opposites.get(downstream);
                if (upstream == null) {
                    upstream = new HashSet<FlowBlock.Output>();
                    opposites.put(downstream, upstream);
                }
                upstream.add(blockOutput);
            }
        }

        List<Sink> results = new ArrayList<Sink>();
        for (Set<FlowBlock.Output> group : opposites.values()) {
            String name = names.create("result").getToken();
            results.add(new Sink(group, names.create(name).getToken()));
        }
        return results;
    }

    private List<Fragment> sort(Graph<Fragment> subgraph) {
        assert subgraph != null;
        Graph<Fragment> tgraph = Graphs.transpose(subgraph);
        List<Fragment> sorted = Graphs.sortPostOrder(tgraph);
        return sorted;
    }

    private Graph<Fragment> createSubgraph(Fragment head, Graph<Fragment> fgraph) {
        assert head != null;
        assert fgraph != null;
        Set<Fragment> path =
            Graphs.collectAllConnected(fgraph, Collections.singleton(head));
        path.add(head);
        Graph<Fragment> result = Graphs.newInstance();
        for (Fragment fragment : path) {
            result.addNode(fragment);
            for (Fragment successor : fgraph.getConnected(fragment)) {
                if (path.contains(successor)) {
                    result.addEdge(fragment, successor);
                }
            }
        }
        return result;
    }

    private Graph<Fragment> buildFragmentGraph(
            Map<FlowElement, Fragment> fragments) {
        assert fragments != null;
        Graph<Fragment> result = Graphs.newInstance();
        for (Fragment fragment : fragments.values()) {
            result.addNode(fragment);
            for (FlowElementOutput output : fragment.getOutputPorts()) {
                for (FlowElementInput next : output.getOpposites()) {
                    Fragment successor = fragments.get(next.getOwner());
                    assert successor != null;
                    result.addEdge(fragment, successor);
                }
            }
        }
        return result;
    }

    private Map<FlowElement, Fragment> collectFragments(
            Set<FlowElement> startElements) {
        assert startElements != null;
        Map<FlowElement, Fragment> results = new HashMap<FlowElement, Fragment>();
        for (FlowElement element : startElements) {
            Fragment fragment = getFragment(element, startElements);
            results.put(element, fragment);
        }
        return results;
    }

    private Fragment getFragment(
            FlowElement element,
            Set<FlowElement> startElements) {
        assert element != null;
        assert startElements != null;
        FlowElement current = element;
        List<Factor> factors = new ArrayList<Factor>();
        List<ResourceFragment> resources = new ArrayList<ResourceFragment>();
        while (true) {
            Factor factor = getFactor(current);
            if (factor == null) {
                break;
            }
            factors.add(factor);
            resources.addAll(getResources(current));
            if (factor.isLineEnd()) {
                break;
            }
            Set<FlowElement> successors = FlowGraphUtil.getSuccessors(current);
            if (successors.size() != 1) {
                break;
            }
            FlowElement next = successors.iterator().next();
            if (startElements.contains(next)) {
                break;
            }
            current = next;
        }
        return new Fragment(factors, resources);
    }

    private Set<FlowElement> collectFragmentStartElements(FlowBlock block) {
        assert block != null;

        Set<FlowElement> outputs = new HashSet<FlowElement>();
        for (FlowBlock.Output blockOutput : block.getBlockOutputs()) {
            outputs.add(blockOutput.getElementPort().getOwner());
        }

        Set<FlowElement> results = new HashSet<FlowElement>();
        for (FlowElement element : block.getElements()) {

            // 入力を取らない、または複数の入力を取る要素はフラグメントの先頭になる
            Set<FlowElement> predecessors = FlowGraphUtil.getPredecessors(element);
            if (predecessors.size() != 1) {
                results.add(element);
            }

            Set<FlowElement> successors = FlowGraphUtil.getSuccessors(element);
            if (successors.size() >= 2 || element.getOutputPorts().size() >= 2) {
                // 複数の出力を取る要素は後続する要素がフラグメントの先頭になる
                results.addAll(successors);
            } else if (outputs.contains(element)) {
                // 出力に一つでもつながっている要素は、後続する要素がフラグメントの先頭になる
                results.addAll(successors);
            } else if (isFragmentEnd(element)) {
                // 強制的にフラグメントの末尾となる要素は、直後の要素がフラグメントの先頭になる
                results.addAll(successors);
            }
        }
        return results;
    }

    private boolean isFragmentEnd(FlowElement element) {
        assert element != null;
        FlowElementDescription description = element.getDescription();
        if (description.getKind() == FlowElementKind.PSEUD) {
            return false;
        }
        assert description.getKind() == FlowElementKind.OPERATOR;
        FlowElementProcessor.Repository repo = environment.getProcessors();
        FlowElementProcessor processor = repo.findProcessor(description);
        if (processor == null) {
            error("{0}に対するプロセッサが見つかりません", description);
            return false;
        }
        return processor.getKind() == Kind.LINE_END
            || processor.getKind() == Kind.RENDEZVOUS;
    }

    private Factor getFactor(FlowElement element) {
        assert element != null;
        FlowElementProcessor.Repository repo = environment.getProcessors();
        FlowElementDescription description = element.getDescription();
        if (description.getKind() == FlowElementKind.PSEUD) {
            return new Factor(element, repo.getEmptyProcessor());
        }
        FlowElementProcessor processor = repo.findProcessor(description);
        if (processor == null) {
            error("{0}に対するプロセッサが見つかりません", description);
            return new Factor(element, repo.getEmptyProcessor());
        }
        return new Factor(element, processor);
    }

    private List<ResourceFragment> getResources(FlowElement element) {
        assert element != null;
        List<FlowResourceDescription> resources = element.getDescription().getResources();
        if (resources.isEmpty()) {
            return Collections.emptyList();
        }
        List<ResourceFragment> results = new ArrayList<ResourceFragment>();
        for (FlowResourceDescription description : resources) {
            results.add(new ResourceFragment(description));
        }
        return results;
    }

    private void error(String format, Object...args) {
        environment.error(format, args);
        sawError = true;
    }
}
