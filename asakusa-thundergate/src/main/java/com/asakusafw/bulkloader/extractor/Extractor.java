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
package com.asakusafw.bulkloader.extractor;

import java.util.Date;
import java.util.List;

import com.asakusafw.bulkloader.bean.ImportBean;
import com.asakusafw.bulkloader.common.BulkLoaderInitializer;
import com.asakusafw.bulkloader.common.Constants;
import com.asakusafw.bulkloader.common.JobFlowParamLoader;
import com.asakusafw.bulkloader.common.MessageIdConst;
import com.asakusafw.bulkloader.log.Log;

/**
 * Extractorの実行クラス。
 * @author yuta.shirai
 */
public class Extractor {
    /**
     * このクラス。
     */
    private static final Class<?> CLASS = Extractor.class;

    /**
     * Extractorで読み込むプロパティファイル。
     */
    private static final List<String> PROPERTIES = Constants.PROPERTIES_HC;

    /**
     * メインメソッド
     * <p>
     * コマンドライン引数として以下の値をとる。
     * </p>
<pre>
・args[0]=ターゲット名
・args[1]=バッチID
・args[2]=ジョブフローID
・args[3]=ジョブフロー実行ID
・args[4]=OSのユーザー名
</pre>
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        Extractor extractor = new Extractor();
        int result = extractor.execute(args);
        System.exit(result);
    }
    /**
     * Extractorの処理を実行する。
     * @param args コマンドライン引数
     * @return 処理全体の終了コード
     * @see Constants#EXIT_CODE_SUCCESS
     * @see Constants#EXIT_CODE_WARNING
     * @see Constants#EXIT_CODE_ERROR
     */
    protected int execute(String[] args) {
        if (args.length != 5) {
            System.err.println("Extractorに指定する引数の数が不正です。 引数の数：" + args.length);
            return Constants.EXIT_CODE_ERROR;
        }

        String targetName = args[0];
        String batchId = args[1];
        String jobFlowId = args[2];
        String executionId = args[3];
        String user = args[4];

        try {
            // 初期処理
            if (!BulkLoaderInitializer.initHadoopCluster(jobFlowId, executionId, PROPERTIES)) {
                Log.log(
                        CLASS,
                        MessageIdConst.EXT_INIT_ERROR,
                        new Date(), targetName, batchId, jobFlowId, executionId, user);
                return Constants.EXIT_CODE_ERROR;
            }

            // 開始ログ出力
            Log.log(
                    CLASS,
                    MessageIdConst.EXT_START,
                    new Date(), targetName, batchId, jobFlowId, executionId, user);

            // パラメータオブジェクトを作成
            ImportBean bean = createBean(targetName, batchId, jobFlowId, executionId);
            if (bean == null) {
                // パラメータのチェックでエラー
                Log.log(
                        CLASS,
                        MessageIdConst.EXT_PARAM_ERROR,
                        new Date(), targetName, batchId, jobFlowId, executionId, user);
                return Constants.EXIT_CODE_ERROR;
            }

            // Importファイルを受取り、HDFSに書き出す
            Log.log(
                    CLASS,
                    MessageIdConst.EXT_CREATEFILE,
                    targetName, batchId, jobFlowId, executionId, user);

            DfsFileImport fileInport = createDfsFileImport();
            if (!fileInport.importFile(bean, user)) {
                Log.log(
                        CLASS,
                        MessageIdConst.EXT_CREATEFILE_ERROR,
                        new Date(), targetName, batchId, jobFlowId, executionId, user);
                return Constants.EXIT_CODE_ERROR;
            } else {
                Log.log(
                        CLASS,
                        MessageIdConst.EXT_CREATEFILE_SUCCESS,
                        targetName, batchId, jobFlowId, executionId, user);
            }

            // 正常終了
            Log.log(
                    CLASS,
                    MessageIdConst.EXT_EXIT,
                    new Date(), targetName, batchId, jobFlowId, executionId, user);
            return Constants.EXIT_CODE_SUCCESS;

        } catch (Exception e) {
            try {
                Log.log(
                        e,
                        CLASS,
                        MessageIdConst.EXT_EXCEPRION,
                        new Date(), targetName, batchId, jobFlowId, executionId, user);
                return Constants.EXIT_CODE_ERROR;
            } catch (Exception e1) {
                System.err.print("Extractorで不明なエラーが発生しました。");
                e1.printStackTrace();
                return Constants.EXIT_CODE_ERROR;
            }
        }
    }
    /**
     * パラメータを保持するBeanを作成する。
     * @param targetName ターゲット名
     * @param batchId バッチID
     * @param jobFlowId ジョブフローID
     * @param executionId OSのユーザー名
     * @return パラメータを保持するBean
     */
    private ImportBean createBean(String targetName, String batchId, String jobFlowId, String executionId) {
        // 引数を分解
        ImportBean bean = new ImportBean();
        // ターゲット名
        bean.setTargetName(targetName);
        // バッチID
        bean.setBatchId(batchId);
        // ジョブフローID
        bean.setJobflowId(jobFlowId);
        // ジョブフロー実行ID
        bean.setExecutionId(executionId);

        // DSLプロパティを読み込み
        JobFlowParamLoader dslLoader = createJobFlowParamLoader();
        if (!dslLoader.loadImportParam(bean.getTargetName(), bean.getBatchId(), bean.getJobflowId(), true)) {
            return null;
        }
        bean.setTargetTable(dslLoader.getImportTargetTables());

        return bean;
    }
    /**
     * DfsFileImportを生成して返す。
     * @return DfsFileImport
     */
    protected DfsFileImport createDfsFileImport() {
        return new DfsFileImport();
    }
    /**
     * DSLParamLoaderのインスタンスを生成して返す。
     * @return JobFlowParamLoader
     */
    protected JobFlowParamLoader createJobFlowParamLoader() {
        return new JobFlowParamLoader();
    }
}
