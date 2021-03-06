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
package com.asakusafw.compiler.operator.flow;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;

import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.compiler.operator.ExecutableAnalyzer;
import com.asakusafw.compiler.operator.ExecutableAnalyzer.TypeConstraint;
import com.asakusafw.compiler.operator.OperatorCompilerException;
import com.asakusafw.compiler.operator.OperatorCompilingEnvironment;
import com.asakusafw.compiler.operator.OperatorPortDeclaration;
import com.asakusafw.vocabulary.flow.FlowDescription;
import com.ashigeru.lang.java.model.syntax.DocElement;

/**
 * フロー部品からなる演算子を集める。
 */
public class FlowPartClassCollector {

    private OperatorCompilingEnvironment environment;

    private List<FlowPartClass> collected;

    private boolean sawError;

    /**
     * インスタンスを生成する。
     * @param environment 環境オブジェクト
     * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
     */
    public FlowPartClassCollector(OperatorCompilingEnvironment environment) {
        Precondition.checkMustNotBeNull(environment, "environment"); //$NON-NLS-1$
        this.environment = environment;
        this.collected = new ArrayList<FlowPartClass>();
        this.sawError = false;
    }

    /**
     * フロー部品としての注釈が付与された要素を追加する。
     * @param element 追加する要素
     * @throws IllegalArgumentException 引数に{@code null}が含まれる場合
     */
    public void add(Element element) {
        Precondition.checkMustNotBeNull(element, "element"); //$NON-NLS-1$
        if (element.getKind() != ElementKind.CLASS) {
            error(element, "フロー部品はクラスとして宣言される必要があります");
            return;
        }
        TypeElement typeDecl = (TypeElement) element;
        FlowPartClass result = toFlowPartClass(typeDecl);
        if (result != null) {
            collected.add(result);
        }
    }

    private FlowPartClass toFlowPartClass(Element element) {
        assert element != null;
        if (validateClassModifiers(element) == false) {
            return null;
        }
        TypeElement type = (TypeElement) element;
        ExecutableElement ctor = findConstructor(type);
        if (ctor == null) {
            return null;
        }
        validateConstructorModifiers(ctor);
        FlowPartClass aClass = analyze(type, ctor);
        return aClass;
    }

    private FlowPartClass analyze(TypeElement aClass, ExecutableElement ctor) {
        assert aClass != null;
        assert ctor != null;
        ExecutableAnalyzer analyzer = new ExecutableAnalyzer(environment, ctor);
        List<? extends DocElement> documentation = analyzer.getDocument(aClass);
        List<OperatorPortDeclaration> inputPorts = new ArrayList<OperatorPortDeclaration>();
        List<OperatorPortDeclaration> outputPorts = new ArrayList<OperatorPortDeclaration>();
        List<OperatorPortDeclaration> parameters = new ArrayList<OperatorPortDeclaration>();
        for (int i = 0, n = analyzer.countParameters(); i < n; i++) {
            OperatorPortDeclaration port = analyzePort(analyzer, i);
            if (port == null) {
                continue;
            } else if (port.getKind() == OperatorPortDeclaration.Kind.INPUT) {
                inputPorts.add(port);
            } else if (port.getKind() == OperatorPortDeclaration.Kind.OUTPUT) {
                outputPorts.add(port);
            } else {
                parameters.add(port);
            }
        }
        if (inputPorts.isEmpty() && outputPorts.isEmpty()) {
            analyzer.error("フロー部品の入出力が指定されていません。コンストラクターの引数にIn<...>, Out<...>型で指定して下さい");
        }
        if (analyzer.hasError()) {
            sawError = true;
            return null;
        }
        return new FlowPartClass(
                aClass,
                documentation,
                inputPorts,
                outputPorts,
                parameters);
    }

    private OperatorPortDeclaration analyzePort(
            ExecutableAnalyzer analyzer, int index) {
        TypeConstraint type = analyzer.getParameterType(index);
        if (type.isIn()) {
            return toPort(analyzer, type, index, OperatorPortDeclaration.Kind.INPUT);
        } else if (type.isOut()) {
            return toPort(analyzer, type, index, OperatorPortDeclaration.Kind.OUTPUT);
        } else {
            return toParameter(analyzer, index, type);
        }
    }

    private OperatorPortDeclaration toPort(
            ExecutableAnalyzer analyzer,
            TypeConstraint type,
            int index,
            OperatorPortDeclaration.Kind direction) {
        assert analyzer != null;
        assert type != null;
        assert direction != null;
        TypeConstraint dataType = type.getTypeArgument();
        if (dataType.isModel() == false) {
            analyzer.error(index, "モデルオブジェクト型以外は入出力に指定できません");
            return null;
        }
        return new OperatorPortDeclaration(
                direction,
                analyzer.getParameterDocument(index),
                analyzer.getParameterName(index),
                dataType.getType(),
                index,
                null);
    }

    private OperatorPortDeclaration toParameter(
            ExecutableAnalyzer analyzer,
            int index,
            TypeConstraint type) {
        assert analyzer != null;
        assert type != null;
        if (type.isOperator()) {
            analyzer.error(index, "演算子オブジェクトは引数に直接指定できません");
            return null;
        }
        return new OperatorPortDeclaration(
                OperatorPortDeclaration.Kind.CONSTANT,
                analyzer.getParameterDocument(index),
                analyzer.getParameterName(index),
                type.getType(),
                index,
                null);
    }

    private ExecutableElement findConstructor(TypeElement type) {
        assert type != null;
        List<ExecutableElement> elements = new ArrayList<ExecutableElement>();
        for (Element element : type.getEnclosedElements()) {
            if (element.getKind() == ElementKind.CONSTRUCTOR
                    && element.getModifiers().contains(Modifier.PUBLIC)) {
                elements.add((ExecutableElement) element);
            }
        }
        if (elements.isEmpty()) {
            error(type, "フロー部品クラスには明示的なpublicコンストラクターが必要です");
            return null;
        }
        if (elements.size() >= 2) {
            for (ExecutableElement odd : elements) {
                error(odd, "フロー部品クラスに定義可能なpublicコンストラクタは一つまでです");
            }
            return null;
        }
        return elements.get(0);
    }

    /**
     * ここまでに{@link #add(Element)}に指定された要素を元に構築したフロー部品クラスの一覧を返す。
     * @return 構築したフロー部品クラスの一覧
     * @throws OperatorCompilerException 構築に失敗した場合
     */
    public List<FlowPartClass> collect() {
        if (sawError) {
            throw new OperatorCompilerException("フロー部品クラスの分析に失敗したため、処理を中止します");
        }
        return collected;
    }

    private boolean validateClassModifiers(Element element) {
        assert element != null;
        TypeElement type = (TypeElement) element;
        DeclaredType superType = environment.getDeclaredType(FlowDescription.class);
        if (environment.getTypeUtils().isSubtype(type.asType(), superType) == false) {
            raiseInvalidClass(type, MessageFormat.format(
                    "フロー部品クラス{0}は{1}のサブクラスとして宣言する必要があります",
                    "{0}",
                    FlowDescription.class.getName()));
        }
        if (type.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
            raiseInvalidClass(type, "フロー部品クラス{0}はパッケージ直下のトップレベルクラスとして宣言する必要があります");
        }
        if (type.getModifiers().contains(Modifier.PUBLIC) == false) {
            raiseInvalidClass(type, "フロー部品クラス{0}はpublicとして宣言する必要があります");
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            raiseInvalidClass(type, "フロー部品クラス{0}はabstractとして宣言できません");
        }
        if (type.getTypeParameters().isEmpty() == false) {
            raiseInvalidClass(type, "フロー部品クラス{0}には型引数を指定できません");
        }
        return true;
    }

    private void validateConstructorModifiers(ExecutableElement ctor) {
        assert ctor != null;
        if (ctor.getThrownTypes().isEmpty() == false) {
            error(ctor, "フロー部品クラスのコンストラクターには例外型を指定できません");
        }
        if (ctor.getTypeParameters().isEmpty() == false) {
            error(ctor, "フロー部品クラスのコンストラクターには型引数を指定できません");
        }
    }

    private void raiseInvalidClass(TypeElement element, String message) {
        error(element, MessageFormat.format(
                message,
                element.getQualifiedName()));
    }

    private void error(Element element, String message) {
        assert element != null;
        assert message != null;
        this.environment.getMessager().printMessage(Diagnostic.Kind.ERROR,
                message,
                element);
        this.sawError = true;
    }
}
