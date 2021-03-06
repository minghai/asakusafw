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
package com.asakusafw.compiler.bulkloader;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.compiler.flow.Location;
import com.asakusafw.compiler.testing.MultipleModelInput;
import com.asakusafw.compiler.testing.SequenceFileModelInput;
import com.asakusafw.compiler.testing.SequenceFileModelOutput;
import com.asakusafw.runtime.io.ModelInput;
import com.asakusafw.runtime.io.ModelOutput;
import com.asakusafw.runtime.stage.AbstractStageClient;
import com.asakusafw.runtime.stage.ToolLauncher;

/**
 * Hadoopに処理を移譲するためのドライバ。
 */
public class HadoopDriver implements Closeable {

    static final Logger LOG = LoggerFactory.getLogger(HadoopDriver.class);

    /**
     * クラスタ上の作業ディレクトリのルート。
     */
    public static final String RUNTIME_WORK_ROOT = "target/testing";

    private File home;

    private HadoopDriver(File home) {
        assert home != null;
        this.home = home;
    }

    /**
     * 指定のセグメント一覧をHadoopが認識するファイルシステムのパスに変換して返す。
     * @param segments 対象のセグメント一覧
     * @return ファイルシステムのパス
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public Location toPath(String...segments) {
        if (segments == null) {
            throw new IllegalArgumentException("segments must not be null"); //$NON-NLS-1$
        }
        Location path = Location.fromPath(RUNTIME_WORK_ROOT, '/');
        for (String segment : segments) {
            path = path.append(segment);
        }
        return path;
    }

    /**
     * インスタンスを生成する。
     * @return 生成したインスタンス、利用できない場合は{@code null}
     */
    public static HadoopDriver createInstance() {
        File home = getHome();
        if (home == null) {
            return null;
        }
        return new HadoopDriver(home);
    }

    /**
     * Hadoopのインストール先を返す。
     * @return Hadoopのインストール先、不明の場合は{@code null}
     */
    public static File getHome() {
        String home = System.getenv("HADOOP_HOME");
        if (home == null) {
            home = System.getProperty("HADOOP_HOME");
        }
        if (home == null) {
            LOG.warn("HADOOP_HOME is not set");
            return null;
        }
        File homeDir = new File(home);
        if (homeDir.isDirectory() == false) {
            LOG.warn("HADOOP_HOME: {} is not found", homeDir);
            return null;
        }
        return homeDir.isDirectory() ? homeDir : null;
    }

    /**
     * 指定のディレクトリパスの内容をすべて含めた入力を返す。
     * @param <T> モデルの種類
     * @param modelType モデルの種類
     * @param location 対象のパス
     * @return 入力
     * @throws IOException 情報の取得に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public <T extends Writable> ModelInput<T> openInput(
            Class<T> modelType,
            final Location location) throws IOException {
        if (modelType == null) {
            throw new IllegalArgumentException("modelType must not be null"); //$NON-NLS-1$
        }
        if (location == null) {
            throw new IllegalArgumentException("location must not be null"); //$NON-NLS-1$
        }
        final File temp = createTempFile(modelType);
        temp.delete();
        if (temp.mkdirs() == false) {
            throw new IOException(temp.getAbsolutePath());
        }
        copyFromHadoop(location.toPath('/'), temp);

        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(temp.toURI(), conf);
        List<ModelInput<T>> sources = new ArrayList<ModelInput<T>>();
        if (location.isPrefix()) {
            for (File file : temp.listFiles()) {
                if (file.isFile() && file.getName().equals("_SUCCESS") == false) {
                    SequenceFile.Reader reader = new SequenceFile.Reader(
                            fs,
                            new Path(file.toURI().getPath()),
                            conf);
                    sources.add(new SequenceFileModelInput<T>(reader));
                }
            }
        } else {
            for (File folder : temp.listFiles()) {
                for (File file : folder.listFiles()) {
                    if (file.isFile() && file.getName().equals("_SUCCESS") == false) {
                        SequenceFile.Reader reader = new SequenceFile.Reader(
                                fs,
                                new Path(file.toURI().getPath()),
                                conf);
                        sources.add(new SequenceFileModelInput<T>(reader));
                    }
                }
            }
        }
        return new MultipleModelInput<T>(sources) {
            final AtomicBoolean closed = new AtomicBoolean();
            @Override
            public void close() throws IOException {
                if (closed.compareAndSet(false, true) == false) {
                    return;
                }
                super.close();
                onInputCompleted(temp, location);
            }
        };
    }

    /**
     * 指定のパスにモデルの内容を出力する。
     * @param <T> モデルの種類
     * @param modelType モデルの種類
     * @param path 出力先のファイルパス
     * @return 出力
     * @throws IOException 情報の取得に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public <T extends Writable> ModelOutput<T> openOutput(
            Class<T> modelType,
            final Location path) throws IOException {
        final File temp = createTempFile(modelType);
        URI uri = temp.toURI();
        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(uri, conf);
        SequenceFile.Writer writer = SequenceFile.createWriter(
                fs,
                conf,
                new Path(uri.getPath()),
                NullWritable.class,
                modelType);
        return new SequenceFileModelOutput<T>(writer) {
            final AtomicBoolean closed = new AtomicBoolean();
            @Override
            public void close() throws IOException {
                if (closed.compareAndSet(false, true) == false) {
                    return;
                }
                super.close();
                onOutputCompleted(temp, path);
            }
        };
    }

    private <T> File createTempFile(Class<T> modelType) throws IOException {
        assert modelType != null;
        return File.createTempFile(
                modelType.getSimpleName() + "_",
                ".seq");
    }

    void onInputCompleted(File temp, Location path) {
        assert temp != null;
        assert path != null;
        LOG.debug("Input completed: {} -> {}", path, temp);
        if (delete(temp) == false) {
            LOG.warn("Failed to delete temporary file: {}", temp);
        }
    }

    void onOutputCompleted(File temp, Location path) throws IOException {
        assert temp != null;
        assert path != null;
        LOG.debug("Output completed: {} -> {}", temp, path);
        copyToHadoop(temp, path);
        if (delete(temp) == false) {
            LOG.warn("Failed to delete temporary file: {}", temp);
        }
    }

    private boolean delete(File temp) {
        boolean success = true;
        if (temp.isDirectory()) {
            for (File child : temp.listFiles()) {
                success &= delete(child);
            }
        }
        success &= temp.delete();
        return success;
    }

    /**
     * 指定のクラス名のジョブを実行する。
     * @param jarFile 対象のファイル
     * @param className 対象のクラス名
     * @return 正常終了した場合のみ{@code true}
     * @throws IOException 起動に失敗した場合
     * @throws IllegalArgumentException 引数に{@code null}が指定された場合
     */
    public boolean runJob(File jarFile, String className) throws IOException {
        if (jarFile == null) {
            throw new IllegalArgumentException("jarFile must not be null"); //$NON-NLS-1$
        }
        if (className == null) {
            throw new IllegalArgumentException("className must not be null"); //$NON-NLS-1$
        }
        LOG.info("run {} with {}", className, jarFile);
        List<String> arguments = new ArrayList<String>();
        arguments.add("jar");
        arguments.add(jarFile.getAbsolutePath());
        arguments.add(ToolLauncher.class.getName());
        arguments.add(className);

        arguments.add("-conf");
        arguments.add(getPluginConfigurations());
        arguments.add("-D");
        arguments.add(AbstractStageClient.PROP_USER + "=" + System.getProperty("user.name"));
        arguments.add("-D");
        arguments.add(AbstractStageClient.PROP_EXECUTION_ID + "=" + "testing");

        int ret = invoke(arguments.toArray(new String[arguments.size()]));
        if (ret != 0) {
            LOG.info("running {} returned {}", className, ret);
            return false;
        }
        return true;
    }

    private void copyFromHadoop(String source, File destination) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null"); //$NON-NLS-1$
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null"); //$NON-NLS-1$
        }
        LOG.info("copy {} to {}", source, destination);
        int ret = invoke("fs", "-get", source, destination.getAbsolutePath());
        if (ret != 0) {
            throw new IOException(MessageFormat.format(
                    "Failed to fs -get: result={0}, source={1}, destination={2}",
                    String.valueOf(ret),
                    source,
                    destination.getAbsolutePath()));
        }
    }

    private void copyToHadoop(File source, Location destination) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null"); //$NON-NLS-1$
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null"); //$NON-NLS-1$
        }
        LOG.info("copy {} to {}", source, destination);
        int ret = invoke("fs", "-put", source.getAbsolutePath(), destination.toPath('/'));
        if (ret != 0) {
            throw new IOException(MessageFormat.format(
                    "Failed to fs -put: result={0}, source={1}, destination={2}",
                    String.valueOf(ret),
                    source.getAbsolutePath(),
                    destination));
        }
    }

    /**
     * クラスタ上のユーザーディレクトリ以下のリソースを削除する。
     * @throws IOException 起動に失敗した場合
     */
    public void clean() throws IOException {
        LOG.info("clean user directory");
        int ret = invoke("fs", "-rmr", toPath().toPath('/'));
        if (ret != 0) {
            LOG.info(MessageFormat.format(
                    "Failed to fs -rmr {0}: result={1}",
                    toPath(),
                    String.valueOf(ret)));
        }
    }

    private int invoke(String... arguments) throws IOException {
        String hadoop = getHadoop();
        List<String> commands = new ArrayList<String>();
        commands.add(hadoop);
        Collections.addAll(commands, arguments);

        LOG.info("invoke: {}", commands);
        ProcessBuilder builder = new ProcessBuilder()
            .command(commands.toArray(new String[commands.size()]))
            .redirectErrorStream(true);
        Process process = builder.start();
        try {
            InputStream stream = process.getInputStream();
            Scanner scanner = new Scanner(stream);
            while (scanner.hasNextLine()) {
                LOG.info(scanner.nextLine());
            }
            return process.waitFor();
        } catch (InterruptedException e) {
            throw new IOException(e);
        } finally {
            process.destroy();
        }
    }

    private String getPluginConfigurations() {
        URL config = getClass().getResource("conf/asakusa-resources.xml");
        if (config == null || config.getProtocol().equals("file") == false) {
            throw new AssertionError("");
        }
        File file;
        try {
            file = new File(config.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
        return file.getAbsolutePath();
    }

    private String getHadoop() {
        return new File(home, "bin/hadoop").getAbsolutePath();
    }

    @Override
    public void close() throws IOException {
        // do nothing
    }
}
