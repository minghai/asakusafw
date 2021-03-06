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
package com.asakusafw.compiler.flow.plan;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

import org.junit.Test;

import com.asakusafw.compiler.flow.FlowCompilerOptions;
import com.asakusafw.compiler.flow.FlowGraphGenerator;
import com.asakusafw.compiler.flow.FlowGraphRewriter;
import com.asakusafw.vocabulary.flow.graph.Connectivity;
import com.asakusafw.vocabulary.flow.graph.FlowBoundary;
import com.asakusafw.vocabulary.flow.graph.FlowElement;
import com.asakusafw.vocabulary.flow.graph.FlowElementKind;
import com.asakusafw.vocabulary.flow.graph.FlowGraph;

/**
 * Test for {@link StagePlanner}.
 */
public class StagePlannerTest {

    private FlowGraphGenerator gen = new FlowGraphGenerator();

    private StagePlanner planner = new StagePlanner(
            Collections.<FlowGraphRewriter>emptyList(),
            new FlowCompilerOptions());

    /**
     * {@link StagePlanner#validate(FlowGraph)}
     */
    @Test
    public void validate_ok() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");

        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(true));
    }

    /**
     * {@link StagePlanner#validate(FlowGraph)}
     */
    @Test
    public void validate_notInConnected() {
        gen.defineInput("in");
        gen.defineOperator("op", "in opened", "out");
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");

        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(false));
    }

    /**
     * {@link StagePlanner#validate(FlowGraph)}
     */
    @Test
    public void validate_notOutConnected() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out opened");
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");

        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(false));
    }

    /**
     * {@link StagePlanner#validate(FlowGraph)}
     */
    @Test
    public void validate_notOutConnected_butOptional() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out opened", Connectivity.OPTIONAL);
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");

        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(true));
    }

    /**
     * {@link StagePlanner#validate(FlowGraph)}
     */
    @Test
    public void validate_looped() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out");
        gen.defineOperator("back", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");
        gen.connect("op", "back").connect("back", "op");

        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(false));
    }

    /**
     * {@link StagePlanner#validate(FlowGraph)}
     */
    @Test
    public void validate_componentError() {
        FlowGraphGenerator comp = new FlowGraphGenerator();
        comp.defineInput("in");
        comp.defineOperator("op", "in", "out opened");
        comp.defineOutput("out");
        comp.connect("in", "op.in").connect("op.out", "out");


        gen.defineInput("in");
        gen.defineFlowPart("c", comp.toGraph());
        gen.defineOutput("out");
        gen.connect("in", "c.in").connect("c.out", "out");

        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(false));
    }

    /**
     * {@link StagePlanner#insertCheckpoints(FlowGraph)}
     */
    @Test
    public void insertCheckpoints_insert() {
        gen.defineInput("in1");
        gen.defineOperator("op1", "in", "a b", FlowBoundary.SHUFFLE);
        gen.defineOperator("op2", "in", "out", FlowBoundary.SHUFFLE);
        gen.defineOutput("out1");
        gen.defineOutput("out2");
        gen.connect("in1", "op1").connect("op1.a", "op2").connect("op2", "out1");
        gen.connect("op1.b", "out2");

        FlowGraph graph = gen.toGraph();
        planner.insertCheckpoints(graph);
        assertThat(FlowGraphUtil.collectBoundaries(graph),
                not(gen.getAsSet("in1", "op1", "op2", "out1", "out2")));

        Set<FlowElement> a = FlowGraphUtil.getSucceedingBoundaries(gen.output("op1.a"));
        Set<FlowElement> b = FlowGraphUtil.getSucceedingBoundaries(gen.output("op1.b"));
        assertThat(a.isEmpty(), is(false));
        assertThat(b.isEmpty(), is(false));

        for (FlowElement elem : a) {
            assertThat(FlowGraphUtil.isStageBoundary(elem), is(true));
        }
        for (FlowElement elem : b) {
            assertThat(FlowGraphUtil.isStageBoundary(elem), is(true));
        }
    }

    /**
     * {@link StagePlanner#insertCheckpoints(FlowGraph)}
     */
    @Test
    public void insertCheckpoints_nothing() {
        gen.defineInput("in1");
        gen.defineOperator("op1", "in", "a b", FlowBoundary.SHUFFLE);
        gen.defineOperator("op2", "in", "out");
        gen.defineOutput("out1");
        gen.defineOutput("out2");
        gen.connect("in1", "op1").connect("op1.a", "op2").connect("op2", "out1");
        gen.connect("op1.b", "out2");

        FlowGraph graph = gen.toGraph();
        planner.insertCheckpoints(graph);

        assertThat(FlowGraphUtil.collectBoundaries(graph),
                is(gen.getAsSet("in1", "op1", "out1", "out2")));
    }

    /**
     * {@link StagePlanner#insertIdentities(FlowGraph)}
     */
    @Test
    public void insertIdentities_nothing() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");
        FlowGraph graph = gen.toGraph();
        planner.insertIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op", "out")));
    }

    /**
     * {@link StagePlanner#insertIdentities(FlowGraph)}
     */
    @Test
    public void insertIdentities_stage_shuffle() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out", FlowBoundary.SHUFFLE);
        gen.defineOperator("op2", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "op2").connect("op2", "out");
        FlowGraph graph = gen.toGraph();
        planner.insertIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                not(gen.getAsSet("in", "op1", "op2", "out")));

        FlowElement id = succ(gen.get("in"));
        assertThat(FlowGraphUtil.isIdentity(id), is(true));

        FlowElement op1 = succ(id);
        assertThat(op1, is(gen.get("op1")));

        FlowElement op2 = succ(op1);
        assertThat(op2, is(gen.get("op2")));

        FlowElement out = succ(op2);
        assertThat(out, is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#insertIdentities(FlowGraph)}
     */
    @Test
    public void insertIdentities_stage_stage() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out", FlowBoundary.STAGE);
        gen.defineOperator("op2", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "op2").connect("op2", "out");
        FlowGraph graph = gen.toGraph();
        planner.insertIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                not(gen.getAsSet("in", "op1", "op2", "out")));

        FlowElement id = succ(gen.get("in"));
        assertThat(FlowGraphUtil.isIdentity(id), is(true));

        FlowElement op1 = succ(id);
        assertThat(op1, is(gen.get("op1")));

        FlowElement op2 = succ(op1);
        assertThat(op2, is(gen.get("op2")));

        FlowElement out = succ(op2);
        assertThat(out, is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#insertIdentities(FlowGraph)}
     */
    @Test
    public void insertIdentities_shuffle_stage() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out");
        gen.defineOperator("op2", "in", "out", FlowBoundary.SHUFFLE);
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "op2").connect("op2", "out");
        FlowGraph graph = gen.toGraph();
        planner.insertIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op1", "op2", "out")));
    }

    /**
     * {@link StagePlanner#splitIdentities(FlowGraph)}
     */
    @Test
    public void splitIdentities_nothing() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op.in").connect("op.out", "out");

        FlowGraph graph = gen.toGraph();
        planner.splitIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op", "out")));
    }

    /**
     * {@link StagePlanner#splitIdentities(FlowGraph)}
     */
    @Test
    public void splitIdentities_split() {
        gen.defineInput("in1");
        gen.defineInput("in2");
        gen.definePseud("id");
        gen.defineOutput("out1");
        gen.defineOutput("out2");
        gen.connect("in1", "id").connect("id", "out1");
        gen.connect("in2", "id").connect("id", "out2");

        FlowGraph graph = gen.toGraph();
        planner.splitIdentities(graph);

        Set<FlowElement> succ1 = FlowGraphUtil.getSuccessors(gen.get("in1"));
        Set<FlowElement> succ2 = FlowGraphUtil.getSuccessors(gen.get("in2"));

        assertThat(succ1.size(), is(2));
        assertThat(succ2.size(), is(2));

        Iterator<FlowElement> iter1 = succ1.iterator();
        FlowElement elem1 = iter1.next();
        FlowElement elem2 = iter1.next();
        Iterator<FlowElement> iter2 = succ2.iterator();
        FlowElement elem3 = iter2.next();
        FlowElement elem4 = iter2.next();

        assertThat(FlowGraphUtil.getSuccessors(elem1).size(), is(1));
        assertThat(FlowGraphUtil.getSuccessors(elem2).size(), is(1));
        assertThat(FlowGraphUtil.getSuccessors(elem3).size(), is(1));
        assertThat(FlowGraphUtil.getSuccessors(elem4).size(), is(1));
        assertThat(FlowGraphUtil.getPredecessors(elem1).size(), is(1));
        assertThat(FlowGraphUtil.getPredecessors(elem2).size(), is(1));
        assertThat(FlowGraphUtil.getPredecessors(elem3).size(), is(1));
        assertThat(FlowGraphUtil.getPredecessors(elem4).size(), is(1));

        assertThat(elem1, not(sameInstance(elem3)));
        assertThat(elem1, not(sameInstance(elem4)));
        assertThat(elem2, not(sameInstance(elem3)));
        assertThat(elem2, not(sameInstance(elem4)));
    }

    /**
     * {@link StagePlanner#splitIdentities(FlowGraph)}
     */
    @Test
    public void splitIdentities_yetSplitted() {
        gen.defineInput("in1");
        gen.definePseud("id1");
        gen.definePseud("id2");
        gen.defineOutput("out1");
        gen.defineOutput("out2");
        gen.connect("in1", "id1").connect("id1", "out1");
        gen.connect("in1", "id2").connect("id2", "out2");

        FlowGraph graph = gen.toGraph();
        planner.splitIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in1", "id1", "id2", "out1", "out2")));
    }

    /**
     * {@link StagePlanner#reduceIdentities(FlowGraph)}
     */
    @Test
    public void reduceIdentities_op_op() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out");
        gen.defineOperator("op2", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "op2").connect("op2", "out");

        FlowGraph graph = gen.toGraph();
        planner.reduceIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op1", "op2", "out")));
        assertThat(succ(gen.get("in")), is(gen.get("op1")));
        assertThat(succ(gen.get("op1")), is(gen.get("op2")));
        assertThat(succ(gen.get("op2")), is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#reduceIdentities(FlowGraph)}
     */
    @Test
    public void reduceIdentities_op_id() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out");
        gen.definePseud("id");
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "id").connect("id", "out");

        FlowGraph graph = gen.toGraph();
        planner.reduceIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op1", "out")));

        assertThat(succ(gen.get("in")), is(gen.get("op1")));
        assertThat(succ(gen.get("op1")), is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#reduceIdentities(FlowGraph)}
     */
    @Test
    public void reduceIdentities_id_op() {
        gen.defineInput("in");
        gen.definePseud("id");
        gen.defineOperator("op1", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "id").connect("id", "op1").connect("op1", "out");

        FlowGraph graph = gen.toGraph();
        planner.reduceIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op1", "out")));

        assertThat(succ(gen.get("in")), is(gen.get("op1")));
        assertThat(succ(gen.get("op1")), is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#reduceIdentities(FlowGraph)}
     */
    @Test
    public void reduceIdentities_mapBody() {
        gen.defineInput("in");
        gen.definePseud("id");
        gen.defineOutput("out");
        gen.connect("in", "id").connect("id", "out");

        FlowGraph graph = gen.toGraph();
        planner.reduceIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "id", "out")));

        assertThat(succ(gen.get("in")), is(gen.get("id")));
        assertThat(succ(gen.get("id")), is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#reduceIdentities(FlowGraph)}
     */
    @Test
    public void reduceIdentities_reduceBody() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out", FlowBoundary.SHUFFLE);
        gen.definePseud("id");
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "id").connect("id", "out");

        FlowGraph graph = gen.toGraph();
        planner.reduceIdentities(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op1", "out")));

        assertThat(succ(gen.get("in")), is(gen.get("op1")));
        assertThat(succ(gen.get("op1")), is(gen.get("out")));
    }

    /**
     * {@link StagePlanner#normalizeFlowGraph(FlowGraph)}
     */
    @Test
    public void normalizeFlowGraph() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out");
        gen.definePseud("id");
        gen.defineOutput("out1");
        gen.defineOutput("out2");
        gen.connect("in", "op1").connect("op1", "id").connect("id", "out1");
        gen.connect("id", "out2");

        FlowGraph graph = gen.toGraph();
        planner.normalizeFlowGraph(graph);

        assertThat(FlowGraphUtil.collectElements(graph),
                is(gen.getAsSet("in", "op1", "out1", "out2")));

        assertThat(succ(gen.get("in")), is(gen.get("op1")));
        assertThat(FlowGraphUtil.getSuccessors(gen.get("op1")), is(gen.getAsSet("out1", "out2")));
        assertThat(pred(gen.get("out1")), is(gen.get("op1")));
        assertThat(pred(gen.get("out2")), is(gen.get("op1")));
        assertThat(pred(gen.get("op1")), is(gen.get("in")));
    }

    /**
     * {@link StagePlanner#normalizeFlowGraph(FlowGraph)}
     */
    @Test
    public void normalizeFlowGraph_component() {
        FlowGraphGenerator comp = new FlowGraphGenerator();
        comp.defineInput("in");
        comp.defineOperator("op1", "in", "out");
        comp.definePseud("id");
        comp.defineOutput("out1");
        comp.defineOutput("out2");
        comp.connect("in", "op1").connect("op1", "id").connect("id", "out1");
        comp.connect("id", "out2");

        gen.defineInput("in");
        gen.defineFlowPart("c", comp.toGraph());
        gen.defineOutput("out");
        gen.connect("in", "c").connect("c.out1", "out").connect("c.out2", "out");

        FlowGraph graph = gen.toGraph();
        planner.normalizeFlowGraph(graph);
        deletePseuds(graph);

        assertThat(succ(gen.get("in")), is(comp.get("op1")));
        assertThat(succ(comp.get("op1")), is(gen.get("out")));
        assertThat(pred(gen.get("out")), is(comp.get("op1")));
        assertThat(pred(comp.get("op1")), is(gen.get("in")));
    }

    /**
     * {@link StagePlanner#plan(FlowGraph)}
     */
    @Test
    public void plan_through() {
        gen.defineInput("in");
        gen.defineOutput("out");
        gen.connect("in", "out");
        FlowGraph graph = normalize();

        StageGraph stages = planner.buildStageGraph(graph);

        assertThat(stages.getInput().getBlockOutputs().size(), is(1));
        assertThat(stages.getOutput().getBlockInputs().size(), is(1));
        assertThat(stages.getStages().size(), is(0));

        assertThat(FlowBlock.isConnected(
                stages.getInput().getBlockOutputs().get(0),
                stages.getOutput().getBlockInputs().get(0)), is(true));
    }

    /**
     * {@link StagePlanner#plan(FlowGraph)}
     */
    @Test
    public void plan_singleMapper() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out");
        gen.defineOutput("out");
        gen.connect("in", "op").connect("op", "out");
        FlowGraph graph = normalize();

        StageGraph stages = planner.buildStageGraph(graph);

        assertThat(stages.getInput().getBlockOutputs().size(), is(1));
        assertThat(stages.getOutput().getBlockInputs().size(), is(1));
        assertThat(stages.getStages().size(), is(1));

        StageBlock mr = stages.getStages().get(0);
        assertThat(mr.getMapBlocks().size(), is(1));
        assertThat(mr.hasReduceBlocks(), is(false));

        FlowBlock mapper = single(mr.getMapBlocks());

        assertThat(FlowBlock.isConnected(
                stages.getInput().getBlockOutputs().get(0),
                mapper.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                mapper.getBlockOutputs().get(0),
                stages.getOutput().getBlockInputs().get(0)), is(true));

        assertThat(mapper.getElements().size(), is(1));
        FlowElement mapperOp = single(mapper.getElements());

        assertThat(mapperOp.getDescription(), is(gen.desc("op")));
    }

    /**
     * {@link StagePlanner#plan(FlowGraph)}
     */
    @Test
    public void plan_singleReducer() {
        gen.defineInput("in");
        gen.defineOperator("op", "in", "out", FlowBoundary.SHUFFLE);
        gen.defineOutput("out");
        gen.connect("in", "op").connect("op", "out");
        FlowGraph graph = normalize();

        StageGraph stages = planner.buildStageGraph(graph);

        assertThat(stages.getInput().getBlockOutputs().size(), is(1));
        assertThat(stages.getOutput().getBlockInputs().size(), is(1));
        assertThat(stages.getStages().size(), is(1));

        StageBlock mr = stages.getStages().get(0);
        assertThat(mr.getMapBlocks().size(), is(1));
        assertThat(mr.getReduceBlocks().isEmpty(), is(false));

        FlowBlock mapper = single(mr.getMapBlocks());
        FlowBlock reducer = single(mr.getReduceBlocks());

        assertThat(FlowBlock.isConnected(
                stages.getInput().getBlockOutputs().get(0),
                mapper.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                mapper.getBlockOutputs().get(0),
                reducer.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                reducer.getBlockOutputs().get(0),
                stages.getOutput().getBlockInputs().get(0)), is(true));

        assertThat(mapper.getElements().size(), is(1));
        FlowElement mapperOp = single(mapper.getElements());
        assertThat(FlowGraphUtil.isIdentity(mapperOp), is(true));

        assertThat(reducer.getElements().size(), is(1));
        FlowElement reducerOp = single(reducer.getElements());
        assertThat(reducerOp.getDescription(), is(gen.desc("op")));
    }

    /**
     * {@link StagePlanner#plan(FlowGraph)}
     */
    @Test
    public void plan_singleMapReduce() {
        gen.defineInput("in");
        gen.defineOperator("op1", "in", "out");
        gen.defineOperator("op2", "in", "out", FlowBoundary.SHUFFLE);
        gen.defineOutput("out");
        gen.connect("in", "op1").connect("op1", "op2").connect("op2", "out");
        FlowGraph graph = normalize();

        StageGraph stages = planner.buildStageGraph(graph);

        assertThat(stages.getInput().getBlockOutputs().size(), is(1));
        assertThat(stages.getOutput().getBlockInputs().size(), is(1));
        assertThat(stages.getStages().size(), is(1));

        StageBlock mr = stages.getStages().get(0);
        assertThat(mr.getMapBlocks().size(), is(1));
        assertThat(mr.getReduceBlocks().isEmpty(), is(false));

        FlowBlock mapper = single(mr.getMapBlocks());
        FlowBlock reducer = single(mr.getReduceBlocks());

        assertThat(FlowBlock.isConnected(
                stages.getInput().getBlockOutputs().get(0),
                mapper.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                mapper.getBlockOutputs().get(0),
                reducer.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                reducer.getBlockOutputs().get(0),
                stages.getOutput().getBlockInputs().get(0)), is(true));

        assertThat(mapper.getElements().size(), is(1));
        FlowElement mapperOp = single(mapper.getElements());
        assertThat(mapperOp.getDescription(), is(gen.desc("op1")));

        assertThat(reducer.getElements().size(), is(1));
        FlowElement reducerOp = single(reducer.getElements());
        assertThat(reducerOp.getDescription(), is(gen.desc("op2")));
    }

    /**
     * {@link StagePlanner#plan(FlowGraph)}
     */
    @Test
    public void plan_recurse() {
        FlowGraphGenerator comp = new FlowGraphGenerator();
        comp.defineInput("in");
        comp.defineOperator("op1", "in", "out");
        comp.defineOperator("op2", "in", "out", FlowBoundary.SHUFFLE);
        comp.defineOutput("out");
        comp.connect("in", "op1").connect("op1", "op2").connect("op2", "out");

        gen.defineInput("in");
        gen.defineFlowPart("c", comp.toGraph());
        gen.defineOutput("out");
        gen.connect("in", "c").connect("c", "out");
        FlowGraph graph = normalize();

        StageGraph stages = planner.buildStageGraph(graph);

        assertThat(stages.getInput().getBlockOutputs().size(), is(1));
        assertThat(stages.getOutput().getBlockInputs().size(), is(1));
        assertThat(stages.getStages().size(), is(1));

        StageBlock mr = stages.getStages().get(0);
        assertThat(mr.getMapBlocks().size(), is(1));
        assertThat(mr.getReduceBlocks().isEmpty(), is(false));

        FlowBlock mapper = single(mr.getMapBlocks());
        FlowBlock reducer = single(mr.getReduceBlocks());

        assertThat(FlowBlock.isConnected(
                stages.getInput().getBlockOutputs().get(0),
                mapper.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                mapper.getBlockOutputs().get(0),
                reducer.getBlockInputs().get(0)), is(true));
        assertThat(FlowBlock.isConnected(
                reducer.getBlockOutputs().get(0),
                stages.getOutput().getBlockInputs().get(0)), is(true));

        assertThat(mapper.getElements().size(), is(1));
        FlowElement mapperOp = single(mapper.getElements());
        assertThat(mapperOp.getDescription(), is(comp.desc("op1")));

        assertThat(reducer.getElements().size(), is(1));
        FlowElement reducerOp = single(reducer.getElements());
        assertThat(reducerOp.getDescription(), is(comp.desc("op2")));
    }

    private FlowGraph normalize() {
        FlowGraph graph = gen.toGraph();
        assertThat(planner.validate(graph), is(true));
        planner.normalizeFlowGraph(graph);
        return graph;
    }

    private void deletePseuds(FlowGraph graph) {
        for (FlowElement element : FlowGraphUtil.collectElements(graph)) {
            if (element.getDescription().getKind() == FlowElementKind.PSEUD) {
                FlowGraphUtil.skip(element);
            }
        }
    }

    private FlowElement pred(FlowElement elem) {
        return single(FlowGraphUtil.getPredecessors(elem));
    }

    private FlowElement succ(FlowElement elem) {
        return single(FlowGraphUtil.getSuccessors(elem));
    }

    private <T> T single(Iterable<T> collection) {
        Iterator<T> iter = collection.iterator();
        assert iter.hasNext() : collection;
        T result = iter.next();
        assert iter.hasNext() == false : collection;
        return result;
    }
}
