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
package test.modelgen.table.io;
import java.io.IOException;
import javax.annotation.Generated;

import com.asakusafw.runtime.io.ModelInput;
import com.asakusafw.runtime.io.RecordParser;

import test.modelgen.table.model.ExportTempImportTarget11;
/**
 * TSVファイルなどのレコードを表すファイルを入力として{@link ExportTempImportTarget11}を読み出す。
 */
@Generated("ModelInputEmitter:0.0.1")@SuppressWarnings("deprecation") public final class
        ExportTempImportTarget11ModelInput implements ModelInput<ExportTempImportTarget11> {
    /**
     * 内部で利用するパーサー
     */
    private final RecordParser parser;
    /**
     * インスタンスを生成する
     * @param parser 利用するパーサー
     * @throw IllegalArgumentException 引数にnullが指定された場合
     */
    public ExportTempImportTarget11ModelInput(RecordParser parser) {
        if(parser == null) {
            throw new IllegalArgumentException();
        }
        this.parser = parser;
    }
    @Override public boolean readTo(ExportTempImportTarget11 model) throws IOException {
        if(parser.next()== false) {
            return false;
        }
        parser.fill(model.getTempSidOption());
        parser.fill(model.getSidOption());
        parser.fill(model.getVersionNoOption());
        parser.fill(model.getRgstDateOption());
        parser.fill(model.getUpdtDateOption());
        parser.fill(model.getDuplicateFlgOption());
        parser.fill(model.getTextdata1Option());
        parser.fill(model.getIntdata1Option());
        parser.fill(model.getDatedata1Option());
        return true;
    }
    @Override public void close() throws IOException {
        parser.close();
    }
}