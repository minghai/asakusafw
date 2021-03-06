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

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.asakusafw.compiler.common.JavaName;
import com.asakusafw.compiler.common.Precondition;
import com.asakusafw.runtime.core.Result;
import com.asakusafw.vocabulary.flow.In;
import com.asakusafw.vocabulary.flow.Operator;
import com.asakusafw.vocabulary.flow.Out;
import com.asakusafw.vocabulary.flow.graph.ObservationCount;
import com.asakusafw.vocabulary.flow.graph.ShuffleKey;
import com.asakusafw.vocabulary.model.DataModel;
import com.asakusafw.vocabulary.model.JoinedModel;
import com.asakusafw.vocabulary.model.Key;
import com.asakusafw.vocabulary.model.SummarizedModel;
import com.asakusafw.vocabulary.operator.Sticky;
import com.asakusafw.vocabulary.operator.Volatile;
import com.ashigeru.lang.java.model.syntax.DocBlock;
import com.ashigeru.lang.java.model.syntax.DocElement;
import com.ashigeru.lang.java.model.syntax.Javadoc;
import com.ashigeru.lang.java.model.syntax.ModelFactory;
import com.ashigeru.lang.java.model.syntax.ModelKind;
import com.ashigeru.lang.java.model.syntax.SimpleName;
import com.ashigeru.lang.java.parser.javadoc.JavadocConverter;
import com.ashigeru.lang.java.parser.javadoc.JavadocParseException;

/**
 * メソッドやコンストラクターの宣言を解析する。
 */
public class ExecutableAnalyzer {

    final OperatorCompilingEnvironment environment;

    final ExecutableElement executable;

    final Javadoc documentation;

    private boolean sawError;

    /**
     * インスタンスを生成する。
     * @param environment 注釈処理の環境
     * @param executable メソッドやコンストラクタの宣言
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public ExecutableAnalyzer(
            OperatorCompilingEnvironment environment,
            ExecutableElement executable) {
        Precondition.checkMustNotBeNull(environment, "environment"); //$NON-NLS-1$
        Precondition.checkMustNotBeNull(executable, "executable"); //$NON-NLS-1$
        this.environment = environment;
        this.executable = executable;
        this.documentation = getJavadoc(environment, executable);
        this.sawError = false;
    }

    /**
     * この要素に対してエラーを表示する。
     * @param message 表示するメッセージ、引数を指定した場合はメッセージのフォーマット
     * @param arguments メッセージの引数
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public void error(String message, Object...arguments) {
        Precondition.checkMustNotBeNull(message, "message"); //$NON-NLS-1$
        environment.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                format(message, arguments),
                executable);
        sawError = true;
    }

    /**
     * パラメーターに対してエラーを表示する。
     * @param parameterIndex パラメーターの位置 (0起算)
     * @param message 表示するメッセージ、引数を指定した場合はメッセージのフォーマット
     * @param arguments メッセージの引数
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public void error(int parameterIndex, String message, Object...arguments) {
        Precondition.checkMustNotBeNull(message, "message"); //$NON-NLS-1$
        if (parameterIndex < 0 || parameterIndex >= executable.getParameters().size()) {
            error(format(message, arguments));
            return;
        }
        environment.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                format(message, arguments),
                executable.getParameters().get(parameterIndex));
        sawError = true;
    }

    boolean typeEqual(TypeMirror a, TypeMirror b) {
        Types types = environment.getTypeUtils();
        return types.isSameType(a, b);
    }

    boolean typeDeclEqual(TypeMirror a, TypeMirror b) {
        if (a.getKind() != TypeKind.DECLARED) {
            return false;
        }
        if (b.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Types types = environment.getTypeUtils();
        if (types.isSameType(a, b)) {
            return true;
        }
        // EclipseJDTのバグでerasureは正しく計算されない模様
        DeclaredType at = (DeclaredType) a;
        DeclaredType bt = (DeclaredType) b;
        return at.asElement().equals(bt.asElement());
    }

    private String format(String message, Object... arguments) {
        if (arguments == null || arguments.length == 0) {
            return message;
        } else {
            return MessageFormat.format(message, arguments);
        }
    }

    /**
     * {@code error()}メソッドが利用された場合に{@code true}を返す。
     * @return {@code error()}メソッドが利用された場合に{@code true}
     */
    public boolean hasError() {
        return sawError;
    }

    /**
     * この要素が{@code abstract}として宣言されている場合に{@code true}を返す。
     * @return {@code abstract}として宣言されている場合に{@code true}
     */
    public boolean isAbstract() {
        return executable.getModifiers().contains(Modifier.ABSTRACT);
    }

    /**
     * この要素の観測回数に関する属性を計算する。
     * @param defaults この要素が本質的に有する観測回数に関する制約
     * @return 観測回数に関する属性
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public ObservationCount getObservationCount(ObservationCount... defaults) {
        Precondition.checkMustNotBeNull(defaults, "defaults"); //$NON-NLS-1$
        ObservationCount current = ObservationCount.DONT_CARE;
        for (ObservationCount oc : defaults) {
            current = current.and(oc);
        }
        if (current.atLeastOnce == false) {
            if (executable.getAnnotation(Sticky.class) != null) {
                current = current.and(ObservationCount.AT_LEAST_ONCE);
            }
        }
        if (current.atMostOnce == false) {
            if (executable.getAnnotation(Volatile.class) != null) {
                current = current.and(ObservationCount.AT_MOST_ONCE);
            }
        }
        return current;
    }

    /**
     * 指定の要素のドキュメンテーションを返す。
     * @param element 対象の要素
     * @return ドキュメンテーション、存在しない場合は空のリスト
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public List<? extends DocElement> getDocument(Element element) {
        Precondition.checkMustNotBeNull(element, "element"); //$NON-NLS-1$
        Javadoc doc = getJavadoc(environment, element);
        return getAbstractBlock(doc);
    }

    private static Javadoc getJavadoc(
            OperatorCompilingEnvironment environment,
            Element element) {
        assert environment != null;
        assert element != null;
        ModelFactory f = environment.getFactory();
        String comment = environment.getElementUtils().getDocComment(element);
        if (comment == null) {
            return f.newJavadoc(Collections.<DocBlock>emptyList());
        }
        if (comment.startsWith("/**") == false) {
            comment = "/**" + comment;
        }
        if (comment.endsWith("*/") == false) {
            comment = comment + "*/";
        }

        try {
            return new JavadocConverter(f).convert(comment, 0);
        } catch (JavadocParseException e) {
            environment.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    e.getMessage(),
                    element);
            return f.newJavadoc(Collections.<DocBlock>emptyList());
        }
    }

    private static List<? extends DocElement> getAbstractBlock(Javadoc doc) {
        assert doc != null;
        List<? extends DocBlock> blocks = doc.getBlocks();
        if (blocks.isEmpty()) {
            return Collections.emptyList();
        }
        DocBlock first = blocks.get(0);
        if (first.getTag().equals("") == false) {
            return Collections.emptyList();
        }
        return first.getElements();
    }

    /**
     * この要素に宣言されたパラメーターの個数を返す。
     * @return この要素に宣言されたパラメーターの個数
     */
    public int countParameters() {
        return executable.getParameters().size();
    }

    /**
     * 戻り値の型に対する制約オブジェクトを返す。
     * @return 戻り値の型に対する制約オブジェクト
     */
    public TypeConstraint getReturnType() {
        return new TypeConstraint(executable.getReturnType());
    }

    /**
     * 指定の引数の型に対する制約オブジェクトを返す。
     * @param index 対象の位置 (0起算)
     * @return 指定の引数の型に対する制約オブジェクト
     */
    public TypeConstraint getParameterType(int index) {
        List<? extends VariableElement> parameters = executable.getParameters();
        if (index >= parameters.size()) {
            return new TypeConstraint(environment.getTypeUtils().getNoType(TypeKind.NONE));
        }
        return new TypeConstraint(parameters.get(index).asType());
    }

    /**
     * 指定の位置の引数名を返す。
     * @param index 対象の位置 (0起算)
     * @return 指定の位置の引数名
     */
    public String getParameterName(int index) {
        List<? extends VariableElement> parameters = executable.getParameters();
        String name = parameters.get(index).getSimpleName().toString();
        return name;
    }

    /**
     * 指定の位置の引数に付与されたキーを返す。
     * @param index 対象の位置 (0起算)
     * @return 指定の位置の引数に付与されたキー
     */
    public ShuffleKey getParameterKey(int index) {
        VariableElement parameter = executable.getParameters().get(index);
        TypeConstraint type = getParameterType(index);

        // TODO 外側からしっかりと型を指定させる？
        TypeConstraint arg = type.getTypeArgument();
        if (arg.exists()) {
            type = arg;
        }
        return toShuffleKey(index, type.getType(), findAnnotation(parameter, environment.getDeclaredType(Key.class)));
    }

    /**
     * この要素に対するドキュメント(概要のみ)を返す。
     * @return この要素に対するドキュメント、存在しない場合は空のリスト
     */
    public List<? extends DocElement> getExecutableDocument() {
        Javadoc doc = documentation;
        return getAbstractBlock(doc);
    }

    /**
     * この要素の引数に対するドキュメントを返す。
     * @param index 対象の位置 (0起算)
     * @return この要素の引数に対するドキュメント、存在しない場合は空のリスト
     */
    public List<? extends DocElement> getParameterDocument(int index) {
        String name = getParameterName(index);
        for (DocBlock block : documentation.getBlocks()) {
            if (block.getTag().equals("@param") == false) {
                continue;
            }
            List<? extends DocElement> elements = block.getElements();
            if (elements.isEmpty()) {
                continue;
            }
            DocElement first = elements.get(0);
            if (first.getModelKind() != ModelKind.SIMPLE_NAME) {
                continue;
            }
            if (name.equals(((SimpleName) first).getToken()) == false) {
                continue;
            }
            return elements.subList(1, elements.size());
        }
        return Collections.emptyList();
    }

    /**
     * この要素の戻り値に対するドキュメントを返す。
     * @return この要素の戻り値に対するドキュメント、存在しない場合は空のリスト
     */
    public List<? extends DocElement> getReturnDocument() {
        for (DocBlock block : documentation.getBlocks()) {
            if (block.getTag().equals("@return") == false
                    && block.getTag().equals("@returns") == false) {
                continue;
            }
            return block.getElements();
        }
        return Collections.emptyList();
    }

    ShuffleKey toShuffleKey(
            int position,
            TypeMirror type,
            AnnotationMirror annotation) {
        if (annotation == null) {
            return null;
        }
        ShuffleKey key = toUncheckedShuffleKey(position, annotation);
        if (key == null) {
            return null;
        }
        Element elem = environment.getTypeUtils().asElement(type);
        if ((elem instanceof TypeElement) == false) {
            error(position, "型{0}には@Keyを指定できません", type);
            return null;
        }
        checkShuffleKey(position, (TypeElement) elem, key);
        return key;
    }

    private ShuffleKey toUncheckedShuffleKey(
            int position,
            AnnotationMirror annotation) {
        assert annotation != null;
        Map<String, AnnotationValue> values = getValues(annotation);
        List<String> group = toStringList(values.get("group"));
        List<String> order = toStringList(values.get("order"));
        if (group == null) {
            error(position, "@Keyにgroupが指定されていません");
            return null;
        }
        if (order == null) {
            order = Collections.emptyList();
        }
        List<ShuffleKey.Order> formedOrder = new ArrayList<ShuffleKey.Order>();
        for (String orderString : order) {
            ShuffleKey.Order o = ShuffleKey.Order.parse(orderString);
            if (o == null) {
                error(position, "@Keyのorder \"{0}\" を正しく解析できません", orderString);
            } else {
                formedOrder.add(o);
            }
        }
        return new ShuffleKey(group, formedOrder);
    }

    private void checkShuffleKey(int position, TypeElement type, ShuffleKey key) {
        assert type != null;
        assert key != null;
        Set<String> properties = createPropertyNameTable(type);
        for (String name : key.getGroupProperties()) {
            String propName = JavaName.of(name).toMemberName();
            if (properties.contains(propName) == false) {
                error(position, "@Keyのgroupに指定されたプロパティ\"{0}\"が見つかりません({1})",
                        name,
                        type.getQualifiedName());
            }
        }
        for (ShuffleKey.Order order : key.getOrderings()) {
            String propName = JavaName.of(order.getProperty()).toMemberName();
            if (properties.contains(propName) == false) {
                error(position, "@Keyのgroupに指定されたプロパティ\"{0}\"が見つかりません({1})",
                        order.getProperty(),
                        type.getQualifiedName());
            }
        }
    }

    private Set<String> createPropertyNameTable(TypeElement type) {
        assert type != null;
        Set<String> properties = new HashSet<String>();
        for (Element member : environment.getElementUtils().getAllMembers(type)) {
            if (member.getModifiers().contains(Modifier.PUBLIC)  == false) {
                continue;
            }
            if (member.getKind() == ElementKind.METHOD) {
                String methodName = member.getSimpleName().toString();
                String propertyName = toPropertyName(methodName);
                if (propertyName != null) {
                    properties.add(propertyName);
                }
            }
            if (member.getKind() == ElementKind.FIELD) {
                properties.add(member.getSimpleName().toString());
            }
        }
        return properties;
    }

    private String toPropertyName(String methodName) {
        assert methodName != null;
        StringBuilder buf = new StringBuilder();
        if (methodName.startsWith("get")) {
            buf.append(methodName, 3, methodName.length());
        } else if (methodName.startsWith("is")) {
            buf.append(methodName, 2, methodName.length());
        } else {
            return null;
        }
        if (buf.length() == 0) {
            return null;
        }
        char head = buf.charAt(0);
        if ('0' <= head && head <= '9' || 'a' <= head && head <= 'z') {
            return null;
        }
        buf.setCharAt(0, Character.toLowerCase(head));
        return buf.toString();
    }

    List<String> toStringList(AnnotationValue value) {
        if (value == null) {
            return null;
        }
        Object object = value.getValue();
        if (object instanceof String) {
            return Collections.singletonList((String) object);
        }
        if ((object instanceof List<?>) == false) {
            return null;
        }
        List<?> list = (List<?>) object;
        List<String> results = new ArrayList<String>();
        for (Object element : list) {
            Object elementValue = ((AnnotationValue) element).getValue();
            if ((elementValue instanceof String) == false) {
                return null;
            }
            results.add((String) elementValue);
        }
        return results;
    }

    AnnotationMirror findAnnotation(
            Element elem,
            DeclaredType annotationType) {
        assert annotationType != null;
        if (elem == null) {
            return null;
        }
        for (AnnotationMirror annotation : elem.getAnnotationMirrors()) {
            DeclaredType aType = annotation.getAnnotationType();
            if (typeEqual(aType, annotationType)) {
                return annotation;
            }
        }
        return null;
    }

    static <T> T getValue(
            Class<T> valueType,
            Map<String, AnnotationValue> valueMap,
            String name) {
        assert valueType != null;
        assert valueMap != null;
        assert name != null;
        AnnotationValue value = valueMap.get(name);
        if (value == null) {
            return null;
        }
        Object content = value.getValue();
        if (valueType.isInstance(content) == false) {
            return null;
        }
        return valueType.cast(content);
    }

    static TypeMirror getModelRefType(AnnotationMirror annotation) {
        assert annotation != null;
        Map<String, AnnotationValue> values = getValues(annotation);
        return getValue(TypeMirror.class, values, "type");
    }

    static AnnotationMirror getModelRefKey(AnnotationMirror annotation) {
        assert annotation != null;
        Map<String, AnnotationValue> values = getValues(annotation);
        return getValue(AnnotationMirror.class, values, "key");
    }

    static Map<String, AnnotationValue> getValues(
            AnnotationMirror annotation) {
        assert annotation != null;
        Map<String, AnnotationValue> results = new HashMap<String, AnnotationValue>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : annotation.getElementValues().entrySet()) {
            ExecutableElement key = entry.getKey();
            AnnotationValue value = entry.getValue();
            results.put(key.getSimpleName().toString(), value);
        }
        return results;
    }

    /**
     * 型に関する制約。
     */
    public class TypeConstraint {

        private final TypeMirror type;

        private final Element element;

        /**
         * インスタンスを生成する。
         * @param type 対象の型
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        TypeConstraint(TypeMirror type) {
            Precondition.checkMustNotBeNull(type, "type"); //$NON-NLS-1$
            this.type = type;
            this.element = environment.getTypeUtils().asElement(type);
        }

        /**
         * このオブジェクトが制約の対象としている型を返す。
         * @return 対象の型
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public TypeMirror getType() {
            return type;
        }

        /**
         * この型が存在する場合のみ{@code true}を返す。
         * @return この型が存在する場合のみ{@code true}
         */
        public boolean exists() {
            return type.getKind() != TypeKind.NONE;
        }

        /**
         * この型が{@code void}を表現する場合のみ{@code true}を返す。
         * @return {@code void}を表現する場合のみ{@code true}
         */
        public boolean isVoid() {
            return type.getKind() == TypeKind.VOID;
        }

        /**
         * この型が任意の列挙型を表現する場合のみ{@code true}を返す。
         * @return 任意の列挙型を表現する場合のみ{@code true}
         */
        public boolean isEnum() {
            if (element == null) {
                return false;
            }
            if (element.getKind() == ElementKind.ENUM) {
                return true;
            }
            return false;
        }

        /**
         * この型が型要素で表現される場合にそれを返す。
         * @return この型に対する型要素、存在しない場合は{@code null}
         */
        public TypeElement getTypeElement() {
            if (element instanceof TypeElement) {
                return (TypeElement) element;
            }
            return null;
        }

        /**
         * この型が列挙型である場合に、それらの定数一覧の情報を返す。
         * @return 定数一覧の情報
         * @throws IllegalStateException この型が列挙型を表さない場合
         */
        public List<VariableElement> getEnumConstants() {
            if (isEnum() == false) {
                throw new IllegalStateException();
            }
            TypeElement decl = (TypeElement) element;
            List<VariableElement> results = new ArrayList<VariableElement>();
            for (Element member : decl.getEnclosedElements()) {
                if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                    results.add((VariableElement) member);
                }
            }
            return results;
        }

        /**
         * この型が演算子オブジェクトを表現する場合のみ{@code true}を返す。
         * @return この型が演算子オブジェクトを表現する場合のみ{@code true}
         */
        public boolean isOperator() {
            return environment.getTypeUtils().isSubtype(type, environment.getDeclaredType(Operator.class));
        }

        /**
         * この型がモデルを表現する場合のみ{@code true}を返す。
         * @return この型がモデルを表現する場合のみ{@code true}
         */
        public boolean isModel() {
            if (element == null) {
                return false;
            }
            return element.getAnnotation(DataModel.class) != null;
        }

        /**
         * この型が結合モデルを表現する場合のみ{@code true}を返す。
         * @param a 結合する型
         * @param b 結合する型
         * @return この型が結合モデルを表現する場合のみ{@code true}
         */
        public boolean isJoinedModel(TypeMirror a, TypeMirror b) {
            AnnotationMirror annotation = findAnnotation(element, environment.getDeclaredType(JoinedModel.class));
            if (annotation == null) {
                return false;
            }
            Map<String, AnnotationValue> values = getValues(annotation);
            AnnotationMirror from = getValue(AnnotationMirror.class, values, "from");
            AnnotationMirror join = getValue(AnnotationMirror.class, values, "join");
            if (from == null || join == null) {
                return false;
            }
            TypeMirror fromType = getModelRefType(from);
            TypeMirror joinType = getModelRefType(join);
            if (fromType == null || joinType == null) {
                return false;
            }
            if (environment.getTypeUtils().isSameType(a, fromType)) {
                return environment.getTypeUtils().isSameType(b, joinType);
            }
            if (environment.getTypeUtils().isSameType(b, fromType)) {
                return environment.getTypeUtils().isSameType(a, joinType);
            }
            return false;
        }

        /**
         * この型が結合モデルを表現し、かつ指定の型が左結合項に出現する場合のみ{@code true}を返す。
         * @param target 対象の型
         * @return この型が結合モデルを表現する場合のみ{@code true}
         */
        public boolean isJoinFrom(TypeMirror target) {
            Precondition.checkMustNotBeNull(target, "target"); //$NON-NLS-1$
            AnnotationMirror annotation = findAnnotation(element, environment.getDeclaredType(JoinedModel.class));
            if (annotation == null) {
                return false;
            }
            Map<String, AnnotationValue> values = getValues(annotation);
            AnnotationMirror from = getValue(AnnotationMirror.class, values, "from");
            if (from == null) {
                return false;
            }
            TypeMirror fromType = getModelRefType(from);
            if (fromType == null) {
                return false;
            }
            return typeEqual(fromType, target);
        }

        /**
         * この型が結合をモデルを表す場合に、指定の型に関する結合キーを返す。
         * @param target 対象の型
         * @return 結合キー
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public ShuffleKey getJoinKey(TypeMirror target) {
            Precondition.checkMustNotBeNull(target, "target"); //$NON-NLS-1$
            AnnotationMirror annotation = findAnnotation(element, environment.getDeclaredType(JoinedModel.class));
            if (annotation == null) {
                throw new IllegalArgumentException();
            }
            Map<String, AnnotationValue> values = getValues(annotation);
            AnnotationMirror ref = getValue(AnnotationMirror.class, values, "from");
            if (ref == null) {
                throw new IllegalArgumentException();
            }
            AnnotationMirror refKey;
            if (environment.getTypeUtils().isSameType(target, getModelRefType(ref))) {
                refKey = getModelRefKey(ref);
            } else {
                ref = getValue(AnnotationMirror.class, values, "join");
                if (ref == null) {
                    throw new IllegalArgumentException();
                }
                if (environment.getTypeUtils().isSameType(target, getModelRefType(ref))) {
                    refKey = getModelRefKey(ref);
                } else {
                    throw new IllegalArgumentException();
                }
            }
            return toShuffleKey(-1, target, refKey);
        }

        /**
         * この型が集計モデルを表現する場合のみ{@code true}を返す。
         * @param target 集計する型
         * @return この型が集計モデルを表現する場合のみ{@code true}
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public boolean isSummarizedModel(TypeMirror target) {
            Precondition.checkMustNotBeNull(target, "target"); //$NON-NLS-1$
            AnnotationMirror annotation = findAnnotation(element, environment.getDeclaredType(SummarizedModel.class));
            if (annotation == null) {
                return false;
            }
            Map<String, AnnotationValue> values = getValues(annotation);
            AnnotationMirror from = getValue(AnnotationMirror.class, values, "from");
            if (from == null) {
                return false;
            }
            TypeMirror fromType = getModelRefType(from);
            if (fromType == null) {
                return false;
            }
            return typeEqual(fromType, target);
        }

        /**
         * この型が集約をモデルを表す場合に、指定の型に関する結合キーを返す。
         * @return 集約キー
         * @throws IllegalArgumentException 引数に{@code null}が指定された場合
         */
        public ShuffleKey getSummarizeKey() {
            AnnotationMirror annotation = findAnnotation(element, environment.getDeclaredType(SummarizedModel.class));
            if (annotation == null) {
                throw new IllegalArgumentException();
            }
            Map<String, AnnotationValue> values = getValues(annotation);
            AnnotationMirror ref = getValue(AnnotationMirror.class, values, "from");
            if (ref == null) {
                throw new IllegalArgumentException();
            }
            TypeMirror refType = getModelRefType(ref);
            AnnotationMirror refKey = getModelRefKey(ref);
            if (refKey != null) {
                return toShuffleKey(-1, refType, refKey);
            }
            throw new IllegalArgumentException();
        }

        /**
         * この型が論理値を表現する場合のみ{@code true}を返す。
         * @return 論理値を表現する場合のみ{@code true}
         */
        public boolean isBoolean() {
            return type.getKind() == TypeKind.BOOLEAN;
        }

        /**
         * この型が文字列を表現する場合のみ{@code true}を返す。
         * @return 文字列を表現する場合のみ{@code true}
         */
        public boolean isString() {
            return typeEqual(type, environment.getDeclaredType(String.class));
        }

        /**
         * この型がリストを表現する場合のみ{@code true}を返す。
         * @return リストを表現する場合のみ{@code true}
         * @see #getTypeArgument()
         */
        public boolean isList() {
            return typeDeclEqual(type, environment.getDeclaredType(List.class));
        }

        /**
         * この型が結果を表現する場合のみ{@code true}を返す。
         * @return 結果を表現する場合のみ{@code true}
         * @see #getTypeArgument()
         */
        public boolean isResult() {
            return typeDeclEqual(type, environment.getDeclaredType(Result.class));
        }

        /**
         * この型がフローへの入力を表現する場合のみ{@code true}を返す。
         * @return フローへの入力を表現する場合のみ{@code true}
         * @see #getTypeArgument()
         */
        public boolean isIn() {
            return typeDeclEqual(type, environment.getDeclaredType(In.class));
        }

        /**
         * この型がフローへの出力を表現する場合のみ{@code true}を返す。
         * @return フローへの出力を表現する場合のみ{@code true}
         * @see #getTypeArgument()
         */
        public boolean isOut() {
            return typeDeclEqual(type, environment.getDeclaredType(Out.class));
        }

        /**
         * この型が文字列またはプリミティブ型を表現するする場合のみ{@code true}を返す。
         * @return 文字列またはプリミティブ型を表現する場合のみ{@code true}
         * @see #getTypeArgument()
         */
        public boolean isBasic() {
            if (type.getKind().isPrimitive() || typeEqual(type, environment.getDeclaredType(String.class))) {
                return true;
            }
            return false;
        }

        /**
         * この型の最初の型引数に対する制約オブジェクトを返す。
         * @return この型の最初の型引数に対する制約オブジェクト
         */
        public TypeConstraint getTypeArgument() {
            if (type.getKind() != TypeKind.DECLARED) {
                return new TypeConstraint(environment.getTypeUtils().getNoType(TypeKind.NONE));
            }
            DeclaredType declared = (DeclaredType) type;
            List<? extends TypeMirror> arguments = declared.getTypeArguments();
            if (arguments.isEmpty()) {
                return new TypeConstraint(environment.getTypeUtils().getNoType(TypeKind.NONE));
            }
            return new TypeConstraint(arguments.get(0));
        }
    }
}
