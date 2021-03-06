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
package com.asakusafw.compiler.operator;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.compiler.operator.OperatorPortDeclaration.Kind;
import com.asakusafw.compiler.operator.OperatorProcessor.Context;
import com.asakusafw.vocabulary.flow.graph.FlowElementAttribute;
import com.asakusafw.vocabulary.flow.graph.OperatorHelper;
import com.asakusafw.vocabulary.flow.graph.ShuffleKey;
import com.ashigeru.lang.java.jsr269.bridge.Jsr269;
import com.ashigeru.lang.java.model.syntax.DocElement;
import com.ashigeru.lang.java.model.syntax.Expression;
import com.ashigeru.lang.java.model.syntax.ModelFactory;
import com.ashigeru.lang.java.model.util.ImportBuilder;
import com.ashigeru.lang.java.model.util.Models;
import com.ashigeru.lang.java.model.util.TypeBuilder;

/**
 * 演算子メソッドの内容を記述する情報。
 */
public class OperatorMethodDescriptor {

    private Class<? extends Annotation> annotationType;

    private List<DocElement> documentation;

    private String name;

    private List<OperatorPortDeclaration> inputPorts;

    private List<OperatorPortDeclaration> outputPorts;

    private List<OperatorPortDeclaration> parameters;

    private List<Expression> attributes;

    /**
     * インスタンスを生成する。
     * @param annotationType この演算子の種類をあらわす注釈型
     * @param documentation 演算子に関するドキュメンテーション
     * @param name 演算子の名前
     * @param inputPorts 入力ポートの一覧
     * @param outputPorts 出力ポートの一覧
     * @param parameters 入出力に関連のないパラメーターの一覧
     * @param attributes 属性の一覧
     * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
     */
    public OperatorMethodDescriptor(
            Class<? extends Annotation> annotationType,
            List<DocElement> documentation,
            String name,
            List<OperatorPortDeclaration> inputPorts,
            List<OperatorPortDeclaration> outputPorts,
            List<OperatorPortDeclaration> parameters,
            List<Expression> attributes) {
        Precondition.checkMustNotBeNull(annotationType, "annotationType"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(documentation, "documentation"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(name, "name"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(inputPorts, "inputPorts"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(outputPorts, "outputPorts"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(parameters, "parameters"); //$NON-NLS-1$
        this.annotationType = annotationType;
        this.documentation = Collections.unmodifiableList(new ArrayList<DocElement>(documentation));
        this.name = name;
        this.inputPorts = Collections.unmodifiableList(new ArrayList<OperatorPortDeclaration>(inputPorts));
        this.outputPorts = Collections.unmodifiableList(new ArrayList<OperatorPortDeclaration>(outputPorts));
        this.parameters = Collections.unmodifiableList(new ArrayList<OperatorPortDeclaration>(parameters));
        this.attributes = Collections.unmodifiableList(new ArrayList<Expression>(attributes));
    }

    /**
     * この演算子の種類をあらわす注釈型を返す。
     * @return 演算子の種類をあらわす注釈型
     */
    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    /**
     * 演算子に関する説明を返す。
     * @return 演算子に関する説明、存在しない場合は{@code null}
     */
    public List<DocElement> getDocumentation() {
        return documentation;
    }

    /**
     * 演算子の名前を返す。
     * @return 演算子の名前
     */
    public String getName() {
        return name;
    }

    /**
     * 入力ポートの一覧を返す。
     * @return 入力ポートの一覧
     */
    public List<OperatorPortDeclaration> getInputPorts() {
        return inputPorts;
    }

    /**
     * 出力ポートの一覧を返す。
     * @return 出力ポートの一覧
     */
    public List<OperatorPortDeclaration> getOutputPorts() {
        return outputPorts;
    }

    /**
     * 入出力に関連のないパラメーターの一覧を返す。
     * @return 入出力に関連のないパラメーターの一覧
     */
    public List<OperatorPortDeclaration> getParameters() {
        return parameters;
    }

    /**
     * この演算子の属性一覧を返す。
     * @return この演算子の属性一覧
     */
    public List<Expression> getAttributes() {
        return attributes;
    }

    /**
     * {@link OperatorMethodDescriptor}を構築するビルダー。
     */
    public static class Builder {

        private Class<? extends Annotation> annotationType;

        private List<DocElement> operatorDescription;

        private String name;

        private List<OperatorPortDeclaration> inputPorts;

        private List<OperatorPortDeclaration> outputPorts;

        private List<OperatorPortDeclaration> parameters;

        private List<Expression> attributes;

        private Context context;

        /**
         * インスタンスを生成する。
         * @param annotationType 構築対象の演算子の種類を表す注釈型
         * @param context 文脈情報
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public Builder(
                Class<? extends Annotation> annotationType,
                OperatorProcessor.Context context) {
            Precondition.checkMustNotBeNull(annotationType, "annotationType"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(context, "context"); //$NON-NLS-1$
            this.context = context;
            this.annotationType = annotationType;
            this.name = context.element.getSimpleName().toString();
            this.operatorDescription = new ArrayList<DocElement>();
            this.inputPorts = new ArrayList<OperatorPortDeclaration>();
            this.outputPorts = new ArrayList<OperatorPortDeclaration>();
            this.parameters = new ArrayList<OperatorPortDeclaration>();
            this.attributes = new ArrayList<Expression>();
        }

        /**
         * 演算子の説明を設定する。
         * @param description 設定する説明
         */
        public void setDocumentation(List<? extends DocElement> description) {
            Precondition.checkMustNotBeNull(description, "description"); //$NON-NLS-1$
            this.operatorDescription = new ArrayList<DocElement>(description);
        }

        /**
         * 入力を追加する。
         * @param documentation 変数の説明
         * @param varName 変数の名前
         * @param type 変数の型
         * @param position 宣言されたパラメーター上での位置、パラメーターから導出されていない場合は{@code null}
         * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
         */
        public void addInput(
                List<? extends DocElement> documentation,
                String varName,
                TypeMirror type,
                Integer position) {
            addInput(documentation, varName, type, position, null);
        }

        /**
         * 入力を追加する。
         * @param documentation 変数の説明
         * @param varName 変数の名前
         * @param type 変数の型
         * @param position 宣言されたパラメーター上での位置、パラメーターから導出されていない場合は{@code null}
         * @param shuffleKey シャッフル条件
         * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
         */
        public void addInput(
                List<? extends DocElement> documentation,
                String varName,
                TypeMirror type,
                Integer position,
                ShuffleKey shuffleKey) {
            Precondition.checkMustNotBeNull(documentation, "documentation"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(varName, "varName"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(type, "type"); //$NON-NLS-1$
            inputPorts.add(new OperatorPortDeclaration(
                    Kind.INPUT,
                    documentation,
                    varName,
                    type,
                    position,
                    shuffleKey));
        }

        /**
         * 出力を追加する。
         * @param documentation 変数の説明
         * @param varName 変数の名前
         * @param type 変数の型
         * @param position 宣言されたパラメーター上での位置、パラメーターから導出されていない場合は{@code null}
         * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
         */
        public void addOutput(
                List<? extends DocElement> documentation,
                String varName,
                TypeMirror type,
                Integer position) {
            Precondition.checkMustNotBeNull(documentation, "documentation"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(varName, "varName"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(type, "type"); //$NON-NLS-1$
            outputPorts.add(new OperatorPortDeclaration(
                    Kind.OUTPUT,
                    documentation,
                    varName,
                    type,
                    position,
                    null));
        }

        /**
         * 出力を追加する。
         * @param documentation 変数の説明
         * @param varName 変数の名前
         * @param type 変数の型
         * @param position 宣言されたパラメーター上での位置、パラメーターから導出されていない場合は{@code null}
         * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
         */
        public void addOutput(
                String documentation,
                String varName,
                TypeMirror type,
                Integer position) {
            Precondition.checkMustNotBeNull(varName, "varName"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(type, "type"); //$NON-NLS-1$
            List<? extends DocElement> elements = Collections.emptyList();
            if (documentation != null) {
                elements = Collections.singletonList(Models.getModelFactory()
                    .newDocText(documentation));
            }
            addOutput(elements, varName, type, position);
        }

        /**
         * 入出力以外のパラメーターを追加する。
         * @param documentation 変数の説明
         * @param varName 変数の名前
         * @param type 変数の型
         * @param position 宣言されたパラメーター上での位置、パラメーターから導出されていない場合は{@code null}
         * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
         */
        public void addParameter(
                List<? extends DocElement> documentation,
                String varName,
                TypeMirror type,
                Integer position) {
            Precondition.checkMustNotBeNull(documentation, "documentation"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(varName, "varName"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(type, "type"); //$NON-NLS-1$
            parameters.add(new OperatorPortDeclaration(
                    Kind.CONSTANT,
                    documentation,
                    varName,
                    type,
                    position,
                    null));
        }

        /**
         * 属性を追加する。
         * @param attribute 追加する属性
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public void addAttribute(Expression attribute) {
            Precondition.checkMustNotBeNull(attribute, "attribute"); //$NON-NLS-1$
            attributes.add(attribute);
        }

        /**
         * 属性を追加する。
         * @param constant 定数表記の{@link FlowElementAttribute}
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public void addAttribute(Enum<? extends FlowElementAttribute> constant) {
            Precondition.checkMustNotBeNull(constant, "constant"); //$NON-NLS-1$
            ModelFactory f = context.factory;
            ImportBuilder ib = context.importer;
            Expression attribute = new TypeBuilder(f, ib.toType(constant.getDeclaringClass()))
                .field(constant.name())
                .toExpression();
            addAttribute(attribute);
        }

        /**
         * 属性を追加する。
         * @param environment 環境オブジェクト
         * @param helperMethod 補助演算子を表すメソッド
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public void addOperatorHelper(OperatorCompilingEnvironment environment, ExecutableElement helperMethod) {
            Precondition.checkMustNotBeNull(environment, "environment"); //$NON-NLS-1$
            Precondition.checkMustNotBeNull(helperMethod, "helperMethod"); //$NON-NLS-1$
            ModelFactory f = context.factory;
            ImportBuilder ib = context.importer;
            Jsr269 conv = new Jsr269(f);
            List<Expression> parameterTypeLiterals = new ArrayList<Expression>();
            for (VariableElement parameter : helperMethod.getParameters()) {
                TypeMirror type = environment.getErasure(parameter.asType());
                parameterTypeLiterals.add(new TypeBuilder(f, ib.resolve(conv.convert(type)))
                    .dotClass()
                    .toExpression());
            }
            Expression attribute = new TypeBuilder(f, ib.toType(OperatorHelper.class))
                .newObject(new Expression[] {
                        // name
                        Models.toLiteral(f, helperMethod.getSimpleName().toString()),
                        // parameter types
                        new TypeBuilder(f, ib.toType(Arrays.class))
                            .method("asList", new TypeBuilder(f, ib.toType(Class.class))
                                .parameterize(f.newWildcard())
                                .array(1)
                                .newArray(f.newArrayInitializer(parameterTypeLiterals))
                                .toExpression())
                            .toExpression()
                }).toExpression();
            addAttribute(attribute);
        }

        /**
         * これまでに構築した情報を元に{@link OperatorMethodDescriptor}を生成して返す。
         * @return 生成した{@link OperatorMethodDescriptor}
         */
        public OperatorMethodDescriptor toDescriptor() {
            return new OperatorMethodDescriptor(
                    annotationType,
                    operatorDescription,
                    name,
                    inputPorts,
                    outputPorts,
                    parameters,
                    attributes);
        }
    }
}
