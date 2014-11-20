/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.prodmanager;

import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.config.PipelineConfiguration;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.runner.MockStages;
import com.streamsets.pipeline.runner.PipelineRuntimeException;
import com.streamsets.pipeline.snapshotstore.SnapshotStatus;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import com.streamsets.pipeline.store.PipelineStoreException;
import com.streamsets.pipeline.store.PipelineStoreTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import com.streamsets.pipeline.util.Configuration;
import com.streamsets.pipeline.util.TestUtil;
import dagger.Module;
import dagger.ObjectGraph;
import dagger.Provides;

import org.apache.commons.io.FileUtils;
import org.junit.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class TestProductionRun {

  private static final String MY_PIPELINE = "myPipeline";
  private ProductionPipelineManagerTask manager;

  @BeforeClass
  public static void beforeClass() throws IOException {
    System.setProperty("pipeline.data.dir", "./target/var");
    File f = new File(System.getProperty("pipeline.data.dir"));
    FileUtils.deleteDirectory(f);
    TestUtil.captureStagesForProductionRun();
  }

  @AfterClass
  public static void afterClass() throws IOException {
    System.getProperties().remove("pipeline.data.dir");
  }

  @Before
  public void setUp() throws IOException {

    File f = new File(System.getProperty("pipeline.data.dir"));
    FileUtils.deleteDirectory(f);

    ObjectGraph g = ObjectGraph.create(TestProdManagerModule.class);
    manager = g.get(ProductionPipelineManagerTask.class);
    manager.init();
  }

  @After
  public void tearDown() {
    manager.stop();
    manager.getStateTracker().getStateFile().delete();
    manager.getOffsetTracker().getOffsetFile().delete();

  }

  @Test(expected = PipelineRuntimeException.class)
  public void testStartInvalidPipeline() throws PipelineStoreException, PipelineStateException, PipelineRuntimeException, StageException {
    //cannot run pipeline "xyz", the default created by FilePipelineStore.init() as it is not valid
    manager.startPipeline("xyz", "0");
  }

  @Test()
  public void testStartStopPipeline() throws PipelineStoreException, PipelineStateException, PipelineRuntimeException, StageException {
    manager.startPipeline(MY_PIPELINE, "0");
    Assert.assertEquals(State.RUNNING, manager.getPipelineState().getState());

    PipelineState pipelineState = manager.stopPipeline();
    Assert.assertEquals(State.STOPPING, pipelineState.getState());
  }

  @Test
  public void testStopManager() throws PipelineStateException, StageException, PipelineRuntimeException, PipelineStoreException {
    //Set state to running
    manager.startPipeline(MY_PIPELINE, "0");
    Assert.assertEquals(State.RUNNING, manager.getPipelineState().getState());

    manager.stop();
    manager.init();
    //Stopping the pipeline also stops the pipeline
    Assert.assertEquals(State.NOT_RUNNING, manager.getPipelineState().getState());

  }

  @Test
  public void testCaptureSnapshot() throws PipelineStoreException, PipelineStateException, PipelineRuntimeException, StageException, InterruptedException {
    manager.startPipeline(MY_PIPELINE, "0");
    Assert.assertEquals(State.RUNNING, manager.getPipelineState().getState());

    manager.captureSnapshot(10);

    while(!manager.getSnapshotStatus().isExists()) {
      Thread.sleep(5);
    }
    SnapshotStatus snapshotStatus = manager.getSnapshotStatus();
    Assert.assertEquals(true, snapshotStatus.isExists());
    Assert.assertEquals(false, snapshotStatus.isSnapshotInProgress());

    InputStream snapshot = manager.getSnapshot();
    //read the input snapshot into String format and Use de-serializer when ready

    manager.deleteSnapshot();
    snapshotStatus = manager.getSnapshotStatus();
    Assert.assertEquals(false, snapshotStatus.isExists());
    Assert.assertEquals(false, snapshotStatus.isSnapshotInProgress());

  }

  /*********************************************/
  /*********************************************/

  @Module(library = true)
  static class TestStageLibraryModule {

    public TestStageLibraryModule() {
    }

    @Provides
    public StageLibraryTask provideStageLibrary() {
      return MockStages.createStageLibrary();
    }
  }

  @Module(library = true, includes = {TestRuntimeModule.class, TestConfigurationModule.class})
  static class TestPipelineStoreModule {

    public TestPipelineStoreModule() {
    }

    @Provides
    public PipelineStoreTask providePipelineStore(RuntimeInfo info, Configuration conf) {
      FilePipelineStoreTask pipelineStoreTask = new FilePipelineStoreTask(info, conf);
      pipelineStoreTask.init();
      try {
        pipelineStoreTask.create(MY_PIPELINE, "description", "tag");
        PipelineConfiguration pipelineConf = pipelineStoreTask.load(MY_PIPELINE, "0");
        PipelineConfiguration mockPipelineConf = MockStages.createPipelineConfigurationSourceProcessorTarget();
        pipelineConf.setStages(mockPipelineConf.getStages());
        pipelineStoreTask.save(MY_PIPELINE, "admin", "tag", "description"
            , pipelineConf);
      } catch (PipelineStoreException e) {
        throw new RuntimeException(e);
      }

      return pipelineStoreTask;
    }
  }

  @Module(library = true)
  static class TestConfigurationModule {

    public TestConfigurationModule() {
    }

    @Provides
    public Configuration provideRuntimeInfo() {
      Configuration conf = new Configuration();
      return conf;
    }
  }

  @Module(library = true)
  static class TestRuntimeModule {

    public TestRuntimeModule() {
    }

    @Provides
    public RuntimeInfo provideRuntimeInfo() {
      RuntimeInfo info = new RuntimeInfo(Arrays.asList(getClass().getClassLoader()));
      return info;
    }
  }

  @Module(injects = ProductionPipelineManagerTask.class
      , library = true, includes = {TestRuntimeModule.class, TestPipelineStoreModule.class
      , TestStageLibraryModule.class, TestConfigurationModule.class})
  static class TestProdManagerModule {

    public TestProdManagerModule() {
    }

    @Provides
    public ProductionPipelineManagerTask provideStateManager(RuntimeInfo runtimeInfo, Configuration configuration
        ,PipelineStoreTask pipelineStore, StageLibraryTask stageLibrary) {
      return new ProductionPipelineManagerTask(runtimeInfo, configuration, pipelineStore, stageLibrary);
    }
  }


}
