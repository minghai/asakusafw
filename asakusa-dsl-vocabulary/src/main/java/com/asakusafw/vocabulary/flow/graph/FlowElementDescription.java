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
package com.asakusafw.vocabulary.flow.graph;

import java.util.List;

/**
 * フロー要素の定義記述。
 */
public interface FlowElementDescription {

    /**
     * この要素の種類を返す。
     * @return この要素の種類
     */
    FlowElementKind getKind();

    /**
     * この要素の名前を返す。
     * @return この要素の名前
     */
    String getName();

    /**
     * この要素の名前を設定する。
     * @param newName 設定する名前
     * @throws UnsupportedOperationException この要素の名前を変更できない場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    void setName(String newName);

    /**
     * この要素への入力ポートの一覧を返す。
     * @return この要素への入力ポートの一覧
     */
    List<FlowElementPortDescription> getInputPorts();

    /**
     * この要素からの出力ポートの一覧を返す。
     * @return この要素からの出力ポートの一覧
     */
    List<FlowElementPortDescription> getOutputPorts();

    /**
     * この要素が利用するリソースの一覧を返す。
     * @return この要素が利用するリソースの一覧
     */
    List<FlowResourceDescription> getResources();

    /**
     * この要素に指定された指定の属性を返す。
     * @param <T> 属性の種類
     * @param attributeClass 属性の種類
     * @return 対象の属性値、未設定の場合は{@code null}
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    <T extends FlowElementAttribute> T getAttribute(Class<T> attributeClass);
}
