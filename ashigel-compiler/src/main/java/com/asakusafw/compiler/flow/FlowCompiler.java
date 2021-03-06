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
package com.asakusafw.compiler.flow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.List;

import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.compiler.flow.external.ExternalIoAnalyzer;
import com.asakusafw.compiler.flow.jobflow.JobflowCompiler;
import com.asakusafw.compiler.flow.jobflow.JobflowModel;
import com.asakusafw.compiler.flow.plan.StageBlock;
import com.asakusafw.compiler.flow.plan.StageGraph;
import com.asakusafw.compiler.flow.plan.StagePlanner;
import com.asakusafw.compiler.flow.stage.StageCompiler;
import com.asakusafw.compiler.flow.stage.StageModel;
import com.asakusafw.compiler.flow.visualizer.FlowVisualizer;
import com.asakusafw.vocabulary.flow.graph.FlowGraph;

/**
 * 演算子グラフをコンパイルする。
 */
public class FlowCompiler {

    private FlowCompilerConfiguration configuration;

    private FlowCompilingEnvironment environment;

    /**
     * インスタンスを生成する。
     * @param configuration このコンパイラの設定
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public FlowCompiler(FlowCompilerConfiguration configuration) {
        Precondition.checkMustNotBeNull(configuration, "configuration"); //$NON-NLS-1$
        this.configuration = configuration;
        this.environment = createEnvironment();
    }

    /**
     * 処理対象のフローIDを返す。
     * @return 処理対象のフローID
     */
    public String getTargetFlowId() {
        return configuration.getFlowId();
    }

    /**
     * コンパイルを実行する。
     * @param graph コンパイル結果を出力する先のグラフ
     * @return コンパイル結果のモデル
     * @throws IOException 出力に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public JobflowModel compile(FlowGraph graph) throws IOException {
        Precondition.checkMustNotBeNull(graph, "graph"); //$NON-NLS-1$
        validate(graph);
        StageGraph stageGraph = plan(graph);
        visualize(graph, stageGraph);
        List<StageModel> stages = compileStages(stageGraph);
        JobflowModel jobflow = compileJobflow(stageGraph, stages);
        return jobflow;
    }

    /**
     * ここまでのコンパイル結果をビルドして出力する。
     * @param output 出力先のファイル
     * @throws IOException 出力に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public void buildSources(File output) throws IOException {
        Precondition.checkMustNotBeNull(output, "output"); //$NON-NLS-1$
        OutputStream stream = open(output);
        try {
            buildSources(stream);
        } finally {
            stream.close();
        }
    }

    /**
     * ここまでのコンパイル結果をそのままアーカイブして出力する。
     * @param output 出力先のファイル
     * @throws IOException 出力に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public void collectSources(File output) throws IOException {
        Precondition.checkMustNotBeNull(output, "output"); //$NON-NLS-1$
        OutputStream stream = open(output);
        try {
            collectSources(stream);
        } finally {
            stream.close();
        }
    }

    private OutputStream open(File file) throws IOException {
        assert file != null;
        if (file.exists() == false) {
            File parent = file.getParentFile();
            assert parent != null;
            if (parent.isDirectory() == false && parent.mkdirs() == false) {
                throw new IOException(MessageFormat.format(
                        "Failed to create {0} (cannot create parent directory)",
                        file));
            }
        }
        return new FileOutputStream(file);
    }

    /**
     * ここまでのコンパイル結果をビルドして出力する。
     * @param output 出力先のストリーム
     * @throws IOException 出力に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public void buildSources(OutputStream output) throws IOException {
        Precondition.checkMustNotBeNull(output, "output"); //$NON-NLS-1$
        configuration.getPackager().build(output);
    }

    /**
     * ここまでのコンパイル結果をそのままアーカイブして出力する。
     * @param output 出力先のストリーム
     * @throws IOException 出力に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public void collectSources(OutputStream output) throws IOException {
        Precondition.checkMustNotBeNull(output, "output"); //$NON-NLS-1$
        configuration.getPackager().packageSources(output);
    }

    private FlowCompilingEnvironment createEnvironment() {
        assert configuration != null;
        FlowCompilingEnvironment result = new FlowCompilingEnvironment(configuration);
        result.bless();
        return result;
    }

    private void validate(FlowGraph graph) throws IOException {
        assert graph != null;
        ExternalIoAnalyzer analyzer = new ExternalIoAnalyzer(environment);
        if (analyzer.validate(graph) == false) {
            throw new IOException("フローの入出力が正しくないため、コンパイルを中止します");
        }
    }

    private StageGraph plan(FlowGraph flowGraph) throws IOException {
        assert flowGraph != null;
        StagePlanner planner = new StagePlanner(
                configuration.getGraphRewriters().getRewriters(),
                configuration.getOptions());
        StageGraph plan = planner.plan(flowGraph);
        if (plan == null) {
            throw new IOException(MessageFormat.format(
                    "実行計画の作成に失敗しました ({0})",
                    planner.getDiagnostics()));
        }
        return plan;
    }

    private void visualize(FlowGraph flowGraph, StageGraph stageGraph) throws IOException {
        assert flowGraph != null;
        assert stageGraph != null;
        FlowVisualizer visualizer = new FlowVisualizer(environment);
        visualizer.visualize(flowGraph);
        visualizer.visualize(stageGraph);
        for (StageBlock stage : stageGraph.getStages()) {
            visualizer.visualize(stage);
        }
    }

    private List<StageModel> compileStages(
            StageGraph stageGraph) throws IOException {
        assert stageGraph != null;
        StageCompiler compiler = new StageCompiler(environment);
        return compiler.compile(stageGraph);
    }

    private JobflowModel compileJobflow(
            StageGraph stageGraph,
            List<StageModel> model) throws IOException {
        assert stageGraph != null;
        assert model != null;
        JobflowCompiler compiler = new JobflowCompiler(environment);
        return compiler.compile(stageGraph, model);
    }
}
