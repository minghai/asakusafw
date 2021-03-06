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
package com.asakusafw.vocabulary.bulkloader;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;

import com.asakusafw.vocabulary.model.TableModel;


/**
 * DBへのエクスポーターの処理内容を記述するクラスの基底。
 * <p>
 * サブクラスでは以下のメソッドを継承して、エクスポーターの処理方法を記述する。
 * </p>
 * <ul>
 * <li> {@link #getTableName()} 接続先データベースを表す識別子 </li>
 * <li> {@link #getModelType()} 対象のモデルクラス </li>
 * </ul>
 * <p>
 * なお、重複チェックを行う場合は{@link DupCheckDbExporterDescription}
 * を継承してエクスポーターの処理内容を記述するのがよい。
 * </p>
 * <p>
 * このクラスを継承するクラスは次のような要件を満たす必要がある。
 * </p>
 * <ul>
 * <li> {@code public}で宣言されている </li>
 * <li> {@code abstract}で宣言されていない </li>
 * <li> 型引数が宣言されていない </li>
 * <li> 明示的なコンストラクターが宣言されていない </li>
 * </ul>
 */
public abstract class DbExporterDescription extends BulkLoadExporterDescription {

    /**
     * {@inheritDoc}
     * <p>
     * この実装では、テーブル名をモデルクラスの注釈から自動的に抽出する。
     * 注釈が指定されていない場合は例外がスローされる。
     * </p>
     */
    @Override
    public String getTableName() {
        TableModel meta = getModelType().getAnnotation(TableModel.class);
        if (meta == null) {
            throw new UnsupportedOperationException(MessageFormat.format(
                    "クラス{0}には@{1}の指定がないため、テーブル名を自動的に判別できませんでした。{2}#{3}()をオーバーライドして明示的に指定して下さい",
                    getClass().getName(),
                    TableModel.class.getSimpleName(),
                    DbExporterDescription.class.getSimpleName(),
                    "getTableName"));
        }
        return meta.name();
    }

    /**
     * {@inheritDoc}
     * <p>
     * この実装では、カラム名をモデルクラスの注釈から自動的に抽出する。
     * 注釈が指定されていない場合は例外がスローされる。
     * </p>
     */
    @Override
    public List<String> getColumnNames() {
        TableModel meta = getModelType().getAnnotation(TableModel.class);
        if (meta == null) {
            throw new UnsupportedOperationException(MessageFormat.format(
                    "クラス{0}には@{1}の指定がないため、カラム名を自動的に判別できませんでした。{2}#{3}()をオーバーライドして明示的に指定して下さい",
                    getClass().getName(),
                    TableModel.class.getSimpleName(),
                    DbExporterDescription.class.getSimpleName(),
                    "getColumnNames"));
        }
        return Arrays.asList(meta.columns());
    }

    @Override
    public final List<String> getTargetColumnNames() {
        return getColumnNames();
    }

    @Override
    public List<String> getPrimaryKeyNames() {
        TableModel meta = getModelType().getAnnotation(TableModel.class);
        if (meta == null) {
            throw new UnsupportedOperationException(MessageFormat.format(
                    "クラス{0}には@{1}の指定がないため、主キー名を自動的に判別できませんでした。{2}#{3}()をオーバーライドして明示的に指定して下さい",
                    getClass().getName(),
                    TableModel.class.getSimpleName(),
                    DbExporterDescription.class.getSimpleName(),
                    "getColumnNames"));
        }
        return Arrays.asList(meta.primary());
    }

    @Override
    public final DuplicateRecordCheck getDuplicateRecordCheck() {
        return null;
    }
}
