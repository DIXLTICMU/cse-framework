/*
 *  Copyright 2012 Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package edu.cmu.lti.oaqa.cse.driver;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import mx.bigdata.anyobject.AnyObject;

import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.fit.factory.TypeSystemDescriptionFactory;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;

import edu.cmu.lti.oaqa.ecd.BaseExperimentBuilder;
import edu.cmu.lti.oaqa.ecd.ExperimentBuilder;
import edu.cmu.lti.oaqa.ecd.config.ConfigurationLoader;
import edu.cmu.lti.oaqa.ecd.config.Stage;
import edu.cmu.lti.oaqa.ecd.config.StagedConfiguration;
import edu.cmu.lti.oaqa.ecd.config.StagedConfigurationImpl;
import edu.cmu.lti.oaqa.ecd.driver.SimplePipelineRev803;
import edu.cmu.lti.oaqa.ecd.flow.FunneledFlow;
import edu.cmu.lti.oaqa.ecd.flow.strategy.FunnelingStrategy;
import edu.cmu.lti.oaqa.ecd.impl.DefaultFunnelingStrategy;
import edu.cmu.lti.oaqa.framework.async.ConsumerManager;
import edu.cmu.lti.oaqa.framework.async.ConsumerManagerImpl;
import edu.cmu.lti.oaqa.framework.async.ProducerManager;
import edu.cmu.lti.oaqa.framework.async.ProducerManagerImpl;

public final class AsyncDriver {

  private final ExperimentBuilder builder;

  private final AnyObject config;

  private final OpMode opMode;

  private final AsyncConfiguration asyncConfig;

  private final AnyObject localConfig;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  public AsyncDriver(String resource, String uuid, OpMode op) throws Exception {
    this.opMode = op;
    this.localConfig = ConfigurationLoader.load(resource);
    if (opMode == OpMode.PRODUCER || opMode == OpMode.REPORT) {
      resource += "-producer";
      this.config = ConfigurationLoader.load(resource);
    } else {
      resource += "-consumer";
      this.config = ConfigurationLoader.load(resource);
    }
    TypeSystemDescription typeSystem = TypeSystemDescriptionFactory.createTypeSystemDescription();
    this.builder = new BaseExperimentBuilder(uuid, resource, typeSystem);
    this.asyncConfig = builder.initializeResource(config, "async-configuration",
            AsyncConfiguration.class);
  }

  public void run() throws Exception {
    if (opMode == OpMode.PRODUCER) {
      runProducer();
    } else if (opMode == OpMode.REPORT) {
      runReport();
    } else {
      runConsumer();
    }
  }

  private FunnelingStrategy getProcessingStrategy() throws ResourceInitializationException {
    FunnelingStrategy ps = new DefaultFunnelingStrategy();
    AnyObject map = config.getAnyObject("processing-strategy");
    if (map != null) {
      ps = BaseExperimentBuilder.loadProvider(map, FunnelingStrategy.class);
    }
    return ps;
  }

  private void runProducer() throws Exception {
    StagedConfiguration stagedConfig = new StagedConfigurationImpl(config);
    ProducerManager manager = new ProducerManagerImpl(builder.getExperimentUuid(), asyncConfig);
    try {
      for (final Stage stage : stagedConfig) {
        scheduler.scheduleWithFixedDelay(new Runnable() {
          @Override
          public void run() {
            try {
              CollectionReader postReader = builder.buildCollectionReader(localConfig,
                      stage.getId());
              AnalysisEngine post = builder.buildPipeline(stage.getConfiguration(), "post-process",
                      stage.getId());
              SimplePipelineRev803.runPipeline(postReader, post);
            } catch (Exception e) {
              System.err.println("Error executing post-process");
              e.printStackTrace();
            }
          }
        }, 10, 10, TimeUnit.MINUTES);
        AnyObject conf = stage.getConfiguration();
        CollectionReader reader = builder.buildCollectionReader(conf, stage.getId());
        AnalysisEngine noOp = builder.createNoOpEngine();
        SimplePipelineRev803.runPipeline(reader, noOp);
        // Progress progress = reader.getProgress()[0];
        // long total = progress.getCompleted();
        manager.waitForReaderCompletion();
        manager.notifyCloseCollectionReaders();
        manager.waitForProcessCompletion();
        manager.notifyNextConfigurationIsReady();
      }
    } finally {
      scheduler.shutdown();
      manager.close();
    }
  }

  private void runReport() throws Exception {
    StagedConfiguration stagedConfig = new StagedConfigurationImpl(config);
    ProducerManager manager = new ProducerManagerImpl(builder.getExperimentUuid(), asyncConfig);
    try {
      for (final Stage stage : stagedConfig) {
        try {
          CollectionReader postReader = builder.buildCollectionReader(localConfig, stage.getId());
          AnalysisEngine post = builder.buildPipeline(stage.getConfiguration(), "post-process",
                  stage.getId());
          SimplePipelineRev803.runPipeline(postReader, post);
        } catch (Exception e) {
          System.err.println("Error executing post-process");
          e.printStackTrace();
        }
      }
    } finally {
      manager.close();
    }
  }

  private void runConsumer() throws Exception {
    StagedConfiguration stagedConfig = new StagedConfigurationImpl(config);
    ConsumerManager manager = new ConsumerManagerImpl(builder.getExperimentUuid(), asyncConfig);
    FunnelingStrategy ps = getProcessingStrategy();
    try {
      for (Stage stage : stagedConfig) {
        try {
          FunneledFlow funnel = ps.newFunnelStrategy(builder.getExperimentUuid());
          CollectionReader reader = builder.buildCollectionReader(stage.getConfiguration(),
                  stage.getId());
          AnalysisEngine pipeline = builder.buildPipeline(stage.getConfiguration(), "pipeline",
                  stage.getId(), funnel);
          SimplePipelineRev803.runPipeline(reader, pipeline);
          manager.notifyProcessCompletion();
          manager.waitForNextConfiguration();
        } catch (UIMAException e) {
          e.printStackTrace();
        }
      }
    } finally {
      manager.close();
    }
  }

  public static void main(String[] args) throws Exception {
    OpMode op = OpMode.valueOf(args[1]);
    String uuid = UUID.randomUUID().toString();
    if (args.length > 2) {
      uuid = args[2];
    }
    System.out.println("Experiment UUID: " + uuid);
    AsyncDriver driver = new AsyncDriver(args[0], uuid, op);
    driver.run();
  }
}