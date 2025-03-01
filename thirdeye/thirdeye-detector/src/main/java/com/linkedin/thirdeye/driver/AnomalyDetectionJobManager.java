package com.linkedin.thirdeye.driver;

import com.codahale.metrics.MetricRegistry;
import com.linkedin.thirdeye.api.AnomalyFunctionSpec;
import com.linkedin.thirdeye.client.ThirdEyeClient;
import com.linkedin.thirdeye.db.AnomalyFunctionSpecDAO;
import com.linkedin.thirdeye.db.AnomalyResultDAO;
import com.linkedin.thirdeye.function.AnomalyFunction;
import com.linkedin.thirdeye.function.AnomalyFunctionFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnomalyDetectionJobManager {
  private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetectionJobManager.class);
  private final Scheduler quartzScheduler;
  private final ThirdEyeClient thirdEyeClient;
  private final AnomalyFunctionSpecDAO specDAO;
  private final AnomalyResultDAO resultDAO;
  private final SessionFactory sessionFactory;
  private final Object sync;
  private final Map<Long, String> jobKeys;
  private final MetricRegistry metricRegistry;

  public AnomalyDetectionJobManager(Scheduler quartzScheduler,
                                    ThirdEyeClient thirdEyeClient,
                                    AnomalyFunctionSpecDAO specDAO,
                                    AnomalyResultDAO resultDAO,
                                    SessionFactory sessionFactory,
                                    MetricRegistry metricRegistry) {
    this.quartzScheduler = quartzScheduler;
    this.thirdEyeClient = thirdEyeClient;
    this.specDAO = specDAO;
    this.resultDAO = resultDAO;
    this.sessionFactory = sessionFactory;
    this.metricRegistry = metricRegistry;
    this.sync = new Object();
    this.jobKeys = new HashMap<>();
  }

  public List<Long> getActiveJobs() {
    synchronized (sync) {
      List<Long> jobs = new ArrayList<>(jobKeys.keySet());
      Collections.sort(jobs);
      return jobs;
    }
  }

  public void runAdHoc(Long id, String windowStartIsoString, String windowEndIsoString) throws Exception {
    synchronized (sync) {
      AnomalyFunctionSpec spec = specDAO.findById(id);
      if (spec == null) {
        throw new IllegalArgumentException("No function with id " + id);
      }
      AnomalyFunction anomalyFunction = AnomalyFunctionFactory.fromSpec(spec);

      String triggerKey = String.format("ad_hoc_anomaly_function_trigger_%d", spec.getId());
      Trigger trigger = TriggerBuilder.newTrigger()
          .withIdentity(triggerKey)
          .startNow()
          .build();

      String jobKey = String.format("ad_hoc_anomaly_function_job_%d", spec.getId());
      JobDetail job = JobBuilder.newJob(AnomalyDetectionJob.class)
          .withIdentity(jobKey)
          .build();

      job.getJobDataMap().put(AnomalyDetectionJob.FUNCTION, anomalyFunction);
      job.getJobDataMap().put(AnomalyDetectionJob.CLIENT, thirdEyeClient);
      job.getJobDataMap().put(AnomalyDetectionJob.WINDOW_START, windowStartIsoString);
      job.getJobDataMap().put(AnomalyDetectionJob.WINDOW_END, windowEndIsoString);
      job.getJobDataMap().put(AnomalyDetectionJob.RESULT_DAO, resultDAO);
      job.getJobDataMap().put(AnomalyDetectionJob.SESSION_FACTORY, sessionFactory);
      job.getJobDataMap().put(AnomalyDetectionJob.METRIC_REGISTRY, metricRegistry);

      quartzScheduler.scheduleJob(job, trigger);

      LOG.info("Started {}: {}", jobKey, spec);
    }
  }

  public void start(Long id) throws Exception {
    synchronized (sync) {
      AnomalyFunctionSpec spec = specDAO.findById(id);
      if (spec == null) {
        throw new IllegalArgumentException("No function with id " + id);
      }
      AnomalyFunction anomalyFunction = AnomalyFunctionFactory.fromSpec(spec);

      String triggerKey = String.format("scheduled_anomaly_function_trigger_%d", spec.getId());
      CronTrigger trigger = TriggerBuilder.newTrigger()
          .withIdentity(triggerKey)
          .withSchedule(CronScheduleBuilder.cronSchedule(spec.getCron()))
          .build();

      String jobKey = String.format("scheduled_anomaly_function_job_%d", spec.getId());
      jobKeys.put(id, jobKey);
      JobDetail job = JobBuilder.newJob(AnomalyDetectionJob.class)
          .withIdentity(jobKey)
          .build();

      job.getJobDataMap().put(AnomalyDetectionJob.FUNCTION, anomalyFunction);
      job.getJobDataMap().put(AnomalyDetectionJob.CLIENT, thirdEyeClient);
      job.getJobDataMap().put(AnomalyDetectionJob.RESULT_DAO, resultDAO);
      job.getJobDataMap().put(AnomalyDetectionJob.SESSION_FACTORY, sessionFactory);
      job.getJobDataMap().put(AnomalyDetectionJob.METRIC_REGISTRY, metricRegistry);

      quartzScheduler.scheduleJob(job, trigger);

      LOG.info("Started {}: {}", jobKey, spec);
    }
  }

  public void stop(Long id) throws Exception {
    synchronized (sync) {
      String jobKey = jobKeys.remove(id);
      if (jobKey == null) {
        throw new IllegalArgumentException("No scheduled job for function id " + id);
      }

      quartzScheduler.deleteJob(JobKey.jobKey(jobKey));

      LOG.info("Stopped {}", jobKey);
    }
  }
}
