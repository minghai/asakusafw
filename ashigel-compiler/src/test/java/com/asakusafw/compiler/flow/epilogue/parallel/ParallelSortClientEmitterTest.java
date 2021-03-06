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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;

import com.asakusafw.compiler.flow.Location;
import com.asakusafw.compiler.flow.testing.model.Ex1;
import com.asakusafw.compiler.testing.JobflowInfo;
import com.asakusafw.compiler.util.CompilerTester;
import com.asakusafw.runtime.io.ModelOutput;
import com.asakusafw.runtime.value.IntOption;
import com.asakusafw.vocabulary.external.FileExporterDescription;

/**
 * Test for {@link ParallelSortClientEmitter}.
 */
public class ParallelSortClientEmitterTest {

    /**
     * テストヘルパー。
     */
    @Rule
    public CompilerTester tester = new CompilerTester();

    /**
     * 単一のファイルを出力する。
     * @throws Exception テストに失敗した場合
     */
    @Test
    public void single() throws Exception {
        JobflowInfo info = tester.compileJobflow(SingleOutputJob.class);

        ModelOutput<Ex1> source = tester.openOutput(Ex1.class, tester.getImporter(info, "input"));
        writeTestData(source);
        source.close();

        assertThat(tester.run(info), is(true));

        List<Ex1> out1 = getList(Out1ExporterDesc.class);
        checkSids(out1);
        checlValues(out1, 100);
    }

    /**
     * 複数のファイルを出力する。
     * @throws Exception テストに失敗した場合
     */
    @Test
    public void multiple() throws Exception {
        JobflowInfo info = tester.compileJobflow(MultipleOutputJob.class);

        ModelOutput<Ex1> source = tester.openOutput(Ex1.class, tester.getImporter(info, "input"));
        writeTestData(source);
        source.close();

        assertThat(tester.run(info), is(true));

        List<Ex1> out1 = getList(Out1ExporterDesc.class);
        checkSids(out1);
        checlValues(out1, 100);

        List<Ex1> out2 = getList(Out2ExporterDesc.class);
        checkSids(out2);
        checlValues(out2, 200);

        List<Ex1> out3 = getList(Out3ExporterDesc.class);
        checkSids(out3);
        checlValues(out3, 300);

        List<Ex1> out4 = getList(Out4ExporterDesc.class);
        checkSids(out4);
        checlValues(out4, 400);
    }

    private void checkSids(List<Ex1> results) {
        assertThat(results.size(), is(10));
        assertThat(results.get(0).getSidOption().isNull(), is(true));
        for (int i = 1; i < 10; i++) {
            assertThat(results.get(i).getSid(), is((long) i));
        }
    }

    private void checlValues(List<Ex1> results, int value) {
        for (Ex1 ex1 : results) {
            assertThat(ex1.getValueOption(), is(new IntOption(value)));
        }
    }

    private void writeTestData(ModelOutput<Ex1> source) throws IOException {
        Ex1 value = new Ex1();
        source.write(value);
        value.setSid(1);
        source.write(value);
        value.setSid(2);
        source.write(value);
        value.setSid(3);
        source.write(value);
        value.setSid(4);
        source.write(value);
        value.setSid(5);
        source.write(value);
        value.setSid(6);
        source.write(value);
        value.setSid(7);
        source.write(value);
        value.setSid(8);
        source.write(value);
        value.setSid(9);
        source.write(value);
    }

    private List<Ex1> getList(Class<? extends FileExporterDescription> exporter) {
        try {
            FileExporterDescription instance = exporter.newInstance();
            return tester.getList(
                    Ex1.class,
                    Location.fromPath(instance.getPathPrefix(), '/'),
                    new Comparator<Ex1>() {
                        @Override
                        public int compare(Ex1 o1, Ex1 o2) {
                            return o1.getSidOption().compareTo(o2.getSidOption());
                        }
                    });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
