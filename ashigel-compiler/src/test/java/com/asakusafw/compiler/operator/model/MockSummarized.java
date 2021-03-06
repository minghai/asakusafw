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
package com.asakusafw.compiler.operator.model;

import com.asakusafw.vocabulary.model.DataModel;
import com.asakusafw.vocabulary.model.Key;
import com.asakusafw.vocabulary.model.ModelRef;
import com.asakusafw.vocabulary.model.Property;
import com.asakusafw.vocabulary.model.SummarizedModel;

/**
 * ダミーのテーブルモデル。
 */
@DataModel
@SummarizedModel(
    from = @ModelRef(type = MockHoge.class, key = @Key(group = "value"))
)
public class MockSummarized implements SummarizedModel.Interface<MockSummarized, MockHoge> {

    /**
     * 唯一のプロパティ
     */
    @Property(from = @Property.Source(declaring = MockHoge.class, name = "value"), aggregator = Property.Aggregator.IDENT)
    public int value;

    @Override
    public void copyFrom(MockSummarized source) {
        return;
    }

    @Override
    public void startSummarization(MockHoge original) {
        value = original.value;
    }

    @Override
    public void combineSummarization(MockSummarized original) {
        value += original.value;
    }
}
