package vip.justlive.frost.core.persistence;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RListMultimap;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RSemaphore;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import com.google.common.collect.Lists;
import vip.justlive.frost.api.model.JobExecuteRecord;
import vip.justlive.frost.api.model.JobExecutor;
import vip.justlive.frost.api.model.JobGroup;
import vip.justlive.frost.api.model.JobInfo;
import vip.justlive.frost.api.model.JobRecordStatus;
import vip.justlive.frost.api.model.JobScript;
import vip.justlive.frost.core.config.JobConfig;
import vip.justlive.frost.core.model.HashRef;
import vip.justlive.oxygen.core.ioc.Bean;
import vip.justlive.oxygen.core.ioc.Inject;
import vip.justlive.oxygen.core.util.Checks;

/**
 * redis持久化实现
 * 
 * @author wubo
 *
 */
@Bean
public class RedisJobRepositoryImpl implements JobRepository {

  private final RedissonClient redissonClient;
  private final RTopic<String> workerTopic;

  @Inject
  public RedisJobRepositoryImpl(Redisson redissonClient) {
    this.redissonClient = redissonClient;
    this.workerTopic = redissonClient.getTopic(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobConfig.JOB_REGIST_PREFIX));
  }


  private void waitFor(String uuid, int subscribers) {
    RSemaphore semaphore =
        redissonClient.getSemaphore(String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX,
            JobExecutor.class.getName(), RSemaphore.class.getSimpleName(), uuid));
    try {
      semaphore.tryAcquire(subscribers, 10L, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    redissonClient.getKeys().delete(semaphore);
  }

  @Override
  public int countExecutors() {
    String uuid = UUID.randomUUID().toString();
    int subscribers = (int) workerTopic.publish(uuid);
    if (subscribers == 0) {
      return 0;
    }
    RMapCache<String, JobExecutor> cache = redissonClient.getMapCache(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecutor.class.getName(), uuid));
    waitFor(uuid, subscribers);
    return cache.size();
  }

  @Override
  public List<JobExecutor> queryJobExecutors() {
    String uuid = UUID.randomUUID().toString();
    int subscribers = (int) workerTopic.publish(uuid);
    if (subscribers == 0) {
      return Collections.emptyList();
    }
    RMapCache<String, JobExecutor> cache = redissonClient.getMapCache(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecutor.class.getName(), uuid));
    waitFor(uuid, subscribers);
    return new ArrayList<>(cache.readAllValues());
  }

  @Override
  public void addJob(JobInfo jobInfo) {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    jobInfo.setId(UUID.randomUUID().toString());
    RList<String> sortList = redissonClient.getList(String.join(JobConfig.SEPERATOR,
        JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName(), RList.class.getSimpleName()));
    sortList.add(jobInfo.getId());
    // script
    if (JobInfo.TYPE.SCRIPT.name().equals(jobInfo.getType())) {
      RListMultimap<String, JobScript> scriptList = redissonClient.getListMultimap(
          String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobScript.class.getName()));
      JobScript script = new JobScript();
      script.setId(UUID.randomUUID().toString());
      script.setJobId(jobInfo.getId());
      script.setScript(jobInfo.getScript());
      script.setTime(Date.from(ZonedDateTime.now().toInstant()));
      script.setVersion("default");
      scriptList.put(jobInfo.getId(), script);
    }
    jobInfo.setScript(null);
    map.put(jobInfo.getId(), jobInfo);
  }

  @Override
  public void updateJob(JobInfo jobInfo) {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    // script
    RListMultimap<String, JobScript> scriptList = redissonClient.getListMultimap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobScript.class.getName()));
    if (jobInfo.getScript() != null && JobInfo.TYPE.SCRIPT.name().equals(jobInfo.getType())) {
      JobScript script = new JobScript();
      script.setId(UUID.randomUUID().toString());
      script.setJobId(jobInfo.getId());
      script.setScript(jobInfo.getScript());
      script.setTime(Date.from(ZonedDateTime.now().toInstant()));
      script.setVersion("default");
      scriptList.removeAll(jobInfo.getId());
      scriptList.put(jobInfo.getId(), script);
    } else if (JobInfo.TYPE.BEAN.name().equals(jobInfo.getType())) {
      scriptList.removeAll(jobInfo.getId());
    }
    jobInfo.setScript(null);
    map.put(jobInfo.getId(), jobInfo);
  }

  @Override
  public void removeJob(String jobId) {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    RList<String> list = redissonClient.getList(String.join(JobConfig.SEPERATOR,
        JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName(), RList.class.getSimpleName()));
    list.remove(jobId);
    map.remove(jobId);
  }

  @Override
  public int countJobInfos() {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    return map.size();
  }

  @Override
  public List<JobInfo> queryJobInfos(int from, int to) {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    RList<String> list = redissonClient.getList(String.join(JobConfig.SEPERATOR,
        JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName(), RList.class.getSimpleName()));
    List<JobInfo> result = Lists.newArrayList();
    for (String id : list.subList(from, Math.min(to, list.size()))) {
      result.add(map.get(id));
    }
    return result;
  }

  @Override
  public List<JobInfo> queryAllJobs() {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    return Lists.newArrayList(map.readAllValues());
  }

  @Override
  public JobInfo findJobInfoById(String id) {
    RMap<String, JobInfo> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobInfo.class.getName()));
    JobInfo jobInfo = map.get(id);
    if (jobInfo == null) {
      return jobInfo;
    }
    RListMultimap<String, JobScript> scriptList = redissonClient.getListMultimap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobScript.class.getName()));
    RList<JobScript> list = scriptList.get(id);
    int size = list.size();
    if (size > 0) {
      JobScript script = list.get(size - 1);
      jobInfo.setScript(script.getScript());
    }
    return jobInfo;
  }

  @Override
  public String addJobRecord(JobExecuteRecord record) {
    RListMultimap<String, JobExecuteRecord> listMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    listMultimap.put(record.getJobId(), record);
    HashRef ref =
        new HashRef(record.getJobId(), listMultimap.get(record.getJobId()).indexOf(record));
    RMap<String, HashRef> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, HashRef.class.getName()));
    map.put(record.getId(), ref);

    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX,
            HashRef.class.getName(), RListMultimap.class.getSimpleName()));
    // 全部
    hashedListmap.put(JobExecuteRecord.class.getName(), record.getId());
    JobInfo info = findJobInfoById(record.getJobId());
    JobGroup group = info.getGroup();
    if (Objects.equals(JobInfo.TYPE.BEAN.name(), info.getType())) {
      // groupKey
      hashedListmap.put(group.getGroupKey(), record.getId());
      // jobKey
      hashedListmap.put(String.join(JobConfig.SEPERATOR, group.getGroupKey(), group.getJobKey()),
          record.getId());
    } else if (group != null && group.getGroupKey() != null) {
      // groupKey
      hashedListmap.put(group.getGroupKey(), record.getId());
    }
    return record.getId();
  }

  @Override
  public int countJobRecords(String groupKey, String jobKey, String jobId) {
    RListMultimap<String, JobExecuteRecord> listMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    if (jobId != null && jobId.length() > 0) {
      List<JobExecuteRecord> list = listMultimap.get(jobId);
      return list.size();
    }
    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX,
            HashRef.class.getName(), RListMultimap.class.getSimpleName()));
    String key = JobExecuteRecord.class.getName();
    if (groupKey != null && groupKey.length() > 0) {
      key = groupKey;
    }
    if (jobKey != null && jobKey.length() > 0) {
      key = String.join(JobConfig.SEPERATOR, key, jobKey);
    }
    RList<String> list = hashedListmap.get(key);
    return list.size();
  }

  @Override
  public List<JobExecuteRecord> queryJobRecords(String groupKey, String jobKey, String jobId,
      int from, int to) {
    RListMultimap<String, JobExecuteRecord> listMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    List<JobExecuteRecord> records = Lists.newArrayList();
    if (jobId != null && jobId.length() > 0) {
      RList<JobExecuteRecord> list = listMultimap.get(jobId);
      if (list.size() <= from) {
        return records;
      }
      RListMultimap<String, JobRecordStatus> recordStatus = redissonClient.getListMultimap(String
          .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
      for (JobExecuteRecord record : list.subList(from, Math.min(to, list.size()))) {
        List<JobRecordStatus> statuses = recordStatus.getAll(record.getId());
        statuses.forEach(r -> r.fill(record));
        record.setRecordStatuses(statuses);
        records.add(record);
      }
      return records;
    }
    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX,
            HashRef.class.getName(), RListMultimap.class.getSimpleName()));
    String key = JobExecuteRecord.class.getName();
    if (groupKey != null && groupKey.length() > 0) {
      key = groupKey;
    }
    if (jobKey != null && jobKey.length() > 0) {
      key = String.join(JobConfig.SEPERATOR, key, jobKey);
    }
    RList<String> list = hashedListmap.get(key);
    if (list.size() <= from) {
      return records;
    }
    for (String id : list.subList(from, Math.min(to, list.size()))) {
      records.add(findJobExecuteRecordById(id));
    }
    return records;
  }

  @Override
  public JobExecuteRecord findJobExecuteRecordById(String id) {
    RMap<String, HashRef> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, HashRef.class.getName()));
    HashRef ref = map.get(id);
    Checks.notNull(ref);
    RListMultimap<String, JobExecuteRecord> listMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    JobExecuteRecord record = listMultimap.get(ref.getKey()).get(ref.getIndex());
    RListMultimap<String, JobRecordStatus> recordStatus = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
    List<JobRecordStatus> statuses = recordStatus.getAll(id);
    statuses.forEach(r -> r.fill(record));
    record.setRecordStatuses(statuses);
    return record;
  }

  @Override
  public void addJobRecordStatus(JobRecordStatus recordStatus) {
    // 日志只会增加不会减少，使用此种方式避开处理事务和异步问题
    RListMultimap<String, JobRecordStatus> listMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
    listMultimap.put(recordStatus.getLoggerId(), recordStatus);
  }

  @Override
  public void removeJobRecords(String jobId) {
    RListMultimap<String, JobExecuteRecord> listMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    List<JobExecuteRecord> list = listMultimap.removeAll(jobId);
    RMap<String, HashRef> map = redissonClient.getMap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, HashRef.class.getName()));
    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX,
            HashRef.class.getName(), RListMultimap.class.getSimpleName()));
    RListMultimap<String, JobRecordStatus> statusMultimap = redissonClient.getListMultimap(String
        .join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
    JobGroup group = findJobInfoById(jobId).getGroup();
    for (JobExecuteRecord r : list) {
      String key = r.getId();
      map.remove(key);
      hashedListmap.get(JobExecuteRecord.class.getName()).remove(key);
      hashedListmap.get(JobExecuteRecord.class.getName()).remove(key);
      if (group != null) {
        hashedListmap.get(group.getGroupKey()).remove(key);
        hashedListmap.get(String.join(JobConfig.SEPERATOR, group.getGroupKey(), group.getJobKey()))
            .remove(key);
      }
      statusMultimap.removeAll(key);
    }
  }

  @Override
  public void addJobScript(JobScript script) {
    RListMultimap<String, JobScript> scriptList = redissonClient.getListMultimap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobScript.class.getName()));
    script.setId(UUID.randomUUID().toString());
    script.setTime(Date.from(ZonedDateTime.now().toInstant()));
    scriptList.put(script.getJobId(), script);
    if (scriptList.get(script.getJobId()).size() > 20) {
      scriptList.get(script.getJobId()).remove(0);
    }
  }

  @Override
  public List<JobScript> queryJobScripts(String jobId) {
    RListMultimap<String, JobScript> scriptList = redissonClient.getListMultimap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobScript.class.getName()));
    return scriptList.getAll(jobId);
  }

  @Override
  public void removeJobScripts(String jobId) {
    RListMultimap<String, JobScript> scriptList = redissonClient.getListMultimap(
        String.join(JobConfig.SEPERATOR, JobConfig.EXECUTOR_PREFIX, JobScript.class.getName()));
    scriptList.removeAll(jobId);
  }
}
