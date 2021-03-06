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
package com.asakusafw.compiler.common;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;


import org.junit.Rule;
import org.junit.Test;

import com.asakusafw.compiler.batch.ResourceRepository.Cursor;
import com.asakusafw.compiler.common.ZipRepository;
import com.asakusafw.compiler.flow.Location;
import com.asakusafw.compiler.util.TemporaryFolder;

/**
 * Test for {@link ZipRepository}.
 */
public class ZipRepositoryTest {

    /**
     * 作業用のテンポラリフォルダ。
     */
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /**
     * 単一のファイルのみを含む。
     * @throws Exception テスト中に例外が発生した場合
     */
    @Test
    public void single() throws Exception {
        ZipRepository repository = new ZipRepository(open("single.zip"));
        Cursor cur = repository.createCursor();
        Map<String, List<String>> entries = drain(cur);

        Map<String, List<String>> expected = new HashMap<String, List<String>>();
        expected.put("hello.txt", Arrays.asList("Hello, world!"));

        assertThat(entries, is(expected));
    }

    /**
     * 複数のファイルを含む。
     * @throws Exception テスト中に例外が発生した場合
     */
    @Test
    public void multiple() throws Exception {
        ZipRepository repository = new ZipRepository(open("multiple.zip"));
        Cursor cur = repository.createCursor();
        Map<String, List<String>> entries = drain(cur);

        Map<String, List<String>> expected = new HashMap<String, List<String>>();
        expected.put("a.txt", Arrays.asList("aaa"));
        expected.put("b.txt", Arrays.asList("bbb"));
        expected.put("c.txt", Arrays.asList("ccc"));

        assertThat(entries, is(expected));
    }

    /**
     * ディレクトリ構造を含む。
     * @throws Exception テスト中に例外が発生した場合
     */
    @Test
    public void structured() throws Exception {
        ZipRepository repository = new ZipRepository(open("structured.zip"));
        Cursor cur = repository.createCursor();
        Map<String, List<String>> entries = drain(cur);

        Map<String, List<String>> expected = new HashMap<String, List<String>>();
        expected.put("a.txt", Arrays.asList("aaa"));
        expected.put("a/b.txt", Arrays.asList("bbb"));
        expected.put("a/b/c.txt", Arrays.asList("ccc"));

        assertThat(entries, is(expected));
    }

    /**
     * そもそもZIPでない。
     * @throws Exception テスト中に例外が発生した場合
     */
    @Test(expected = IOException.class)
    public void notarchive() throws Exception {
        ZipRepository repository = new ZipRepository(open("notarchive.zip"));
        Cursor cur = repository.createCursor();
        drain(cur);
    }

    private File open(String name) {
        String path = getClass().getSimpleName() + ".files/" + name;
        InputStream input = getClass().getResourceAsStream(path);
        assertThat(path, input, not(nullValue()));
        try {
            try {
                return temporary.copy(input);
            } finally {
                input.close();
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Map<String, List<String>> drain(Cursor cur) throws IOException {
        try {
            Map<String, List<String>> entries = new TreeMap<String, List<String>>();
            while (cur.next()) {
                Location location = cur.getLocation();
                InputStream input = cur.openResource();
                try {
                    List<String> contents = new ArrayList<String>();
                    Scanner scanner = new Scanner(input, "UTF-8");
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        contents.add(line);
                    }
                    entries.put(location.toPath('/'), contents);
                } finally {
                    input.close();
                }
            }
            return entries;
        } finally {
            cur.close();
        }
    }
}
