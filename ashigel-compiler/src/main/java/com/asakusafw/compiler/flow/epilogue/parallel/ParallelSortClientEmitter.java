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
package com.asakusafw.compiler.flow.epilogue.parallel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.compiler.common.Naming;
import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.compiler.flow.FlowCompilingEnvironment;
import com.asakusafw.compiler.flow.Location;
import com.asakusafw.compiler.flow.jobflow.CompiledStage;
import com.asakusafw.compiler.flow.stage.CompiledType;
import com.asakusafw.runtime.stage.AbstractStageClient;
import com.asakusafw.runtime.stage.StageInput;
import com.asakusafw.runtime.stage.StageOutput;
import com.asakusafw.runtime.stage.collector.SortableSlot;
import com.asakusafw.runtime.stage.collector.WritableSlot;
import com.ashigeru.lang.java.model.syntax.Comment;
import com.ashigeru.lang.java.model.syntax.CompilationUnit;
import com.ashigeru.lang.java.model.syntax.Expression;
import com.ashigeru.lang.java.model.syntax.FormalParameterDeclaration;
import com.ashigeru.lang.java.model.syntax.Javadoc;
import com.ashigeru.lang.java.model.syntax.MethodDeclaration;
import com.ashigeru.lang.java.model.syntax.ModelFactory;
import com.ashigeru.lang.java.model.syntax.Name;
import com.ashigeru.lang.java.model.syntax.QualifiedName;
import com.ashigeru.lang.java.model.syntax.SimpleName;
import com.ashigeru.lang.java.model.syntax.Statement;
import com.ashigeru.lang.java.model.syntax.Type;
import com.ashigeru.lang.java.model.syntax.TypeBodyDeclaration;
import com.ashigeru.lang.java.model.syntax.TypeDeclaration;
import com.ashigeru.lang.java.model.syntax.TypeParameterDeclaration;
import com.ashigeru.lang.java.model.util.AttributeBuilder;
import com.ashigeru.lang.java.model.util.ExpressionBuilder;
import com.ashigeru.lang.java.model.util.ImportBuilder;
import com.ashigeru.lang.java.model.util.JavadocBuilder;
import com.ashigeru.lang.java.model.util.Models;
import com.ashigeru.lang.java.model.util.TypeBuilder;

/**
 * parallel reduceを行うステージクライアントクラスを生成する。
 */
public class ParallelSortClientEmitter {

    static final Logger LOG = LoggerFactory.getLogger(ParallelSortClientEmitter.class);

    private FlowCompilingEnvironment environment;

    /**
     * インスタンスを生成する。
     * @param environment 環境オブジェクト
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public ParallelSortClientEmitter(FlowCompilingEnvironment environment) {
        Precondition.checkMustNotBeNull(environment, "environment"); //$NON-NLS-1$
        this.environment = environment;
    }

    /**
     * 指定のステージ情報を元にステージクライアントクラスを生成し、生成したステージの情報を返す。
     * @param moduleId モジュール識別子
     * @param slots 処理対象のスロット一覧
     * @param outputDirectory 出力先のディレクトリ
     * @return ステージクライアントクラス
     * @throws IOException 生成に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public CompiledStage emit(
            String moduleId,
            List<ResolvedSlot> slots,
            Location outputDirectory) throws IOException {
        Precondition.checkMustNotBeNull(moduleId, "moduleId"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(slots, "slots"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(outputDirectory, "outputDirectory"); //$NON-NLS-1$
        LOG.debug("{}に対するエピローグジョブ実行クライアントを生成します", moduleId);
        Engine engine = new Engine(environment, moduleId, slots, outputDirectory);
        CompilationUnit source = engine.generate();
        environment.emit(source);
        Name packageName = source.getPackageDeclaration().getName();
        SimpleName simpleName = source.getTypeDeclarations().get(0).getName();
        QualifiedName name = environment
            .getModelFactory()
            .newQualifiedName(packageName, simpleName);
        LOG.debug("エピローグ\"{}\"のジョブ実行には{}が利用されます", moduleId, name);
        return new CompiledStage(name, Naming.getEpilogueName(moduleId));
    }

    private static class Engine {

        private static final char PATH_SEPARATOR = '/';

        private FlowCompilingEnvironment environment;

        private String moduleId;

        private List<ResolvedSlot> slots;

        private Location outputDirectory;

        private ModelFactory factory;

        private ImportBuilder importer;

        Engine(
                FlowCompilingEnvironment environment,
                String moduleId,
                List<ResolvedSlot> slots,
                Location outputDirectory) {
            assert environment != null;
            assert moduleId != null;
            assert slots != null;
            this.environment = environment;
            this.moduleId = moduleId;
            this.slots = slots;
            this.outputDirectory = outputDirectory;
            this.factory = environment.getModelFactory();
            Name packageName = environment.getEpiloguePackageName(moduleId);
            this.importer = new ImportBuilder(
                    factory,
                    factory.newPackageDeclaration(packageName),
                    ImportBuilder.Strategy.TOP_LEVEL);
        }


        public CompilationUnit generate() throws IOException {
            TypeDeclaration type = createType();
            return factory.newCompilationUnit(
                    importer.getPackageDeclaration(),
                    importer.toImportDeclarations(),
                    Collections.singletonList(type),
                    Collections.<Comment>emptyList());
        }

        private TypeDeclaration createType() throws IOException {
            SimpleName name = factory.newSimpleName(Naming.getClientClass());
            importer.resolvePackageMember(name);
            List<TypeBodyDeclaration> members = new ArrayList<TypeBodyDeclaration>();
            members.addAll(createIdMethods());
            members.add(createStageOutputPath());
            members.add(createStageInputsMethod());
            members.add(createStageOutputsMethod());
            members.addAll(createShuffleMethods());
            return factory.newClassDeclaration(
                    createJavadoc(),
                    new AttributeBuilder(factory)
                        .Public()
                        .Final()
                        .toAttributes(),
                    name,
                    Collections.<TypeParameterDeclaration>emptyList(),
                    t(AbstractStageClient.class),
                    Collections.<Type>emptyList(),
                    members);
        }

        private List<MethodDeclaration> createIdMethods() {
            List<MethodDeclaration> results = new ArrayList<MethodDeclaration>();
            results.add(createValueMethod(
                    AbstractStageClient.METHOD_BATCH_ID,
                    t(String.class),
                    Models.toLiteral(factory, environment.getBatchId())));
            results.add(createValueMethod(
                    AbstractStageClient.METHOD_FLOW_ID,
                    t(String.class),
                    Models.toLiteral(factory, environment.getFlowId())));
            results.add(createValueMethod(
                    AbstractStageClient.METHOD_STAGE_ID,
                    t(String.class),
                    Models.toLiteral(factory, Naming.getEpilogueName(moduleId))));
            return results;
        }

        private MethodDeclaration createStageOutputPath() {
            return createValueMethod(
                    AbstractStageClient.METHOD_STAGE_OUTPUT_PATH,
                    t(String.class),
                    Models.toLiteral(factory, outputDirectory.toPath(PATH_SEPARATOR)));
        }

        private MethodDeclaration createStageInputsMethod() throws IOException {
            SimpleName list = factory.newSimpleName("results");
            List<Statement> statements = new ArrayList<Statement>();
            statements.add(new TypeBuilder(factory, t(ArrayList.class, t(StageInput.class)))
                .newObject()
                .toLocalVariableDeclaration(t(List.class, t(StageInput.class)), list));
            for (ResolvedSlot slot : slots) {
                Type mapperType = generateMapper(slot);
                for (Slot.Input input : slot.getSource().getInputs()) {
                    statements.add(new ExpressionBuilder(factory, list)
                        .method("add", new TypeBuilder(factory, t(StageInput.class))
                            .newObject(
                                    Models.toLiteral(factory, input.getLocation().toPath(PATH_SEPARATOR)),
                                    factory.newClassLiteral(t(input.getFormatType())),
                                    factory.newClassLiteral(mapperType))
                            .toExpression())
                        .toStatement());
                }
            }
            statements.add(new ExpressionBuilder(factory, list)
                .toReturnStatement());

            return factory.newMethodDeclaration(
                    null,
                    new AttributeBuilder(factory)
                        .annotation(t(Override.class))
                        .Protected()
                        .toAttributes(),
                    t(List.class, t(StageInput.class)),
                    factory.newSimpleName(AbstractStageClient.METHOD_STAGE_INPUTS),
                    Collections.<FormalParameterDeclaration>emptyList(),
                    statements);
        }


        private MethodDeclaration createStageOutputsMethod() {
            SimpleName list = factory.newSimpleName("results");
            List<Statement> statements = new ArrayList<Statement>();
            statements.add(new TypeBuilder(factory, t(ArrayList.class, t(StageOutput.class)))
                .newObject()
                .toLocalVariableDeclaration(t(List.class, t(StageOutput.class)), list));
            for (ResolvedSlot slot : slots) {
                Expression valueType = factory.newClassLiteral(t(slot.getValueClass().getType()));
                Class<?> outputFormatType = slot.getSource().getOutputFormatType();
                statements.add(new ExpressionBuilder(factory, list)
                    .method("add", new TypeBuilder(factory, t(StageOutput.class))
                        .newObject(
                                Models.toLiteral(factory, slot.getSource().getOutputName()),
                                factory.newClassLiteral(t(NullWritable.class)),
                                valueType,
                                factory.newClassLiteral(t(outputFormatType)))
                        .toExpression())
                    .toStatement());
            }
            statements.add(new ExpressionBuilder(factory, list)
                .toReturnStatement());

            return factory.newMethodDeclaration(
                    null,
                    new AttributeBuilder(factory)
                        .annotation(t(Override.class))
                        .Protected()
                        .toAttributes(),
                    t(List.class, t(StageOutput.class)),
                    factory.newSimpleName(AbstractStageClient.METHOD_STAGE_OUTPUTS),
                    Collections.<FormalParameterDeclaration>emptyList(),
                    statements);
        }

        private List<MethodDeclaration> createShuffleMethods() throws IOException {
            Type reducer = generateReducer();
            List<MethodDeclaration> results = new ArrayList<MethodDeclaration>();
            results.add(createClassLiteralMethod(
                    AbstractStageClient.METHOD_SHUFFLE_KEY_CLASS,
                    importer.toType(SortableSlot.class)));
            results.add(createClassLiteralMethod(
                    AbstractStageClient.METHOD_SHUFFLE_VALUE_CLASS,
                    importer.toType(WritableSlot.class)));
            results.add(createClassLiteralMethod(
                    AbstractStageClient.METHOD_PARTITIONER_CLASS,
                    importer.toType(SortableSlot.Partitioner.class)));
            results.add(createClassLiteralMethod(
                    AbstractStageClient.METHOD_REDUCER_CLASS,
                    reducer));
            return results;
        }

        private Type generateMapper(ResolvedSlot slot) throws IOException {
            assert slot != null;
            ParallelSortMapperEmitter sub = new ParallelSortMapperEmitter(environment);
            CompiledType type = sub.emit(moduleId, slot);
            return importer.toType(type.getQualifiedName());
        }

        private Type generateReducer() throws IOException {
            ParallelSortReducerEmitter sub = new ParallelSortReducerEmitter(environment);
            CompiledType type = sub.emit(moduleId, slots);
            return importer.toType(type.getQualifiedName());
        }

        private Javadoc createJavadoc() {
            return new JavadocBuilder(factory)
                .text("\"{0}\"のエピローグステージのジョブを実行するクライアント。", moduleId)
                .toJavadoc();
        }

        private MethodDeclaration createClassLiteralMethod(
                String methodName,
                Type type) {
            assert methodName != null;
            assert type != null;
            return createValueMethod(
                    methodName,
                    t(Class.class, type),
                    factory.newClassLiteral(type));
        }

        private MethodDeclaration createValueMethod(
                String methodName,
                Type returnType,
                Expression expression) {
            return factory.newMethodDeclaration(
                    null,
                    new AttributeBuilder(factory)
                        .annotation(t(Override.class))
                        .Protected()
                        .toAttributes(),
                    returnType,
                    factory.newSimpleName(methodName),
                    Collections.<FormalParameterDeclaration>emptyList(),
                    Collections.singletonList(factory.newReturnStatement(expression)));
        }

        private Type t(java.lang.reflect.Type type, Type...typeArgs) {
            assert type != null;
            assert typeArgs != null;
            Type raw = importer.toType(type);
            if (typeArgs.length == 0) {
                return raw;
            }
            return factory.newParameterizedType(raw, Arrays.asList(typeArgs));
        }
    }
}
