package io.digdag.core.database;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.digdag.core.queue.QueueSettingStore;
import io.digdag.core.queue.QueueSettingStoreManager;
import io.digdag.core.queue.StoredQueueSetting;
import io.digdag.core.queue.ImmutableStoredQueueSetting;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.GetGeneratedKeys;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.sqlobject.customizers.Mapper;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.exceptions.UnableToExecuteStatementException;
import org.immutables.value.Value;
import io.digdag.client.config.Config;
import io.digdag.core.repository.ResourceConflictException;
import io.digdag.core.repository.ResourceNotFoundException;
import static io.digdag.core.queue.QueueSettingStore.DEFAULT_QUEUE_NAME;

public class DatabaseTaskQueueStore
        extends BasicDatabaseStoreManager<DatabaseTaskQueueStore.Dao>
{
    @Value.Immutable
    public static interface LockResult
    {
        boolean getSharedTask();

        long getLockId();

        public static LockResult of(boolean sharedTask, long lockId)
        {
            return ImmutableLockResult.builder()
                .sharedTask(sharedTask)
                .lockId(lockId)
                .build();
        }
    }

    private final QueueSettingStoreManager qm;
    private final int expireLockInterval;
    private final LocalLockMap localLockMap = new LocalLockMap();
    private final ScheduledExecutorService expireExecutor;

    private final boolean skipLockedAvailable;

    @Inject
    public DatabaseTaskQueueStore(DBI dbi, DatabaseConfig config, QueueSettingStoreManager qm)
    {
        super(config.getType(), Dao.class, dbi);
        this.qm = qm;
        this.expireLockInterval = config.getExpireLockInterval();
        this.expireExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("lock-expire-%d")
                .build()
                );
        this.skipLockedAvailable = checkSkipLockedAvailable(dbi);
    }

    private boolean checkSkipLockedAvailable(DBI dbi)
    {
        if (!isSharedDatabase()) {
            return false;
        }
        try (Handle h = dbi.open()) {
            try {
                // added since postgresql 9.5
                h.createQuery("select 1 for update skip locked").list();
                return true;
            }
            catch (UnableToExecuteStatementException ex) {
                return false;
            }
        }
    }

    @PostConstruct
    public void start()
    {
        expireExecutor.scheduleWithFixedDelay(() -> expireLocks(),
                expireLockInterval, expireLockInterval, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown()
    {
        expireExecutor.shutdown();
        // TODO wait for shutdown completion?
    }

    private boolean isSharedDatabase()
    {
        switch (databaseType) {
        case "h2":
            return false;
        default:
            return true;
        }
    }

    private String statementUnixTimestampSql()
    {
        return "extract(epoch from now())";
    }

    // TODO support Optional<String> resourceType
    public long enqueue(int siteId, String queueName, int priority, long taskId, byte[] data)
        throws ResourceConflictException
    {
        boolean useSharedTaskQueue = queueName.equals(DEFAULT_QUEUE_NAME);
        Integer resourceTypeId = null;

        return transaction((handle, dao, ts) -> {
            int queueId = qm.getQueueIdByNameOrInsertDefault(siteId, queueName);
            long queuedTaskId = catchConflict(() ->
                dao.insertQueuedTask(siteId, queueId, priority, resourceTypeId, taskId, data),
                "lock id=%d in queue id=%d", taskId, queueId);
            if (useSharedTaskQueue) {
                dao.insertQueuedSharedTaskLock(queuedTaskId, queueId, priority, resourceTypeId);
            }
            else {
                dao.insertQueuedTaskLock(queuedTaskId, queueId, priority, resourceTypeId);
            }
            return queuedTaskId;
        }, ResourceConflictException.class);
    }

    public void delete(int siteId, LockResult lock, String agentId)
        throws ResourceNotFoundException, ResourceConflictException
    {
        this.<Boolean, ResourceNotFoundException, ResourceConflictException>transaction((handle, dao, ts) -> {
            int deleted;

            deleted = dao.deleteTask(lock.getLockId(), siteId);
            if (deleted == 0) {
                throw new ResourceNotFoundException("Deleting lock does not exist: lock id=" + lock.getLockId() + " site id=" + siteId);
            }

            if (lock.getSharedTask()) {
                deleted = dao.deleteSharedTaskLock(lock.getLockId(), agentId);
            }
            else {
                deleted = dao.deleteTaskLock(lock.getLockId(), agentId);
            }
            if (deleted == 0) {
                throw new ResourceConflictException("Deleting lock does not exist or preempted by another agent: lock id=" + lock.getLockId() + " agent id=" + agentId);
            }

            return true;
        }, ResourceNotFoundException.class, ResourceConflictException.class);
    }

    public List<LockResult> lockSharedTasks(int limit, String agentId, int lockSeconds)
    {
        // optimized implementation of
        //   select distinct queue_id as id from locks where hold_expire_time is null
        return transaction((handle, dao, ts) -> {
            List<Integer> queueIds = handle.createQuery(
                "with recursive t (queue_id) as (" +
                    "(" +
                        "select queue_id from queued_shared_task_locks " +
                        "where hold_expire_time is null " +
                        "order by queue_id limit 1" +
                    ") " +
                    "union all " +
                    "select (" +
                        "select queue_id from queued_shared_task_locks " +
                        "where hold_expire_time is null and queue_id > t.queue_id " +
                        "order by queue_id limit 1" +
                    ") from t where t.queue_id is not null" +
                ") " +
                "select queue_id as id from t " +
                "where queue_id is not null"
                )
                .mapTo(int.class)
                .list();
            ImmutableList.Builder<LockResult> builder = ImmutableList.builder();
            int remaining = limit;
            for (int queueId : queueIds) {
                List<Long> lockIds = tryLockTasks(handle, "queued_shared_task_locks", queueId, remaining);
                if (!lockIds.isEmpty()) {
                    for (long lockId : lockIds) {
                        builder.add(LockResult.of(true, lockId));
                    }
                    remaining -= lockIds.size();
                    if (remaining <= 0) {
                        break;
                    }
                }
            }
            List<LockResult> results = builder.build();
            setHold(handle, "queued_shared_task_locks", results, agentId, lockSeconds);
            return results;
        });
    }

    public List<LockResult> lockTasks(int siteId, String queueName, int limit, String agentId, int lockSeconds)
        throws ResourceNotFoundException
    {
        int queueId = qm.getQueueIdByName(siteId, queueName);
        return transaction((handle, dao, ts) -> {
            List<Long> lockIds = tryLockTasks(handle, "queued_task_locks", queueId, limit);
            ImmutableList.Builder<LockResult> builder = ImmutableList.builder();
            for (long lockId : lockIds) {
                builder.add(LockResult.of(false, lockId));
            }
            List<LockResult> results = builder.build();
            setHold(handle, "queued_task_locks", results, agentId, lockSeconds);
            return results;
        });
    }

    private void setHold(Handle handle, String tableName, List<LockResult> locks, String agentId, int lockSeconds)
    {
        if (locks.isEmpty()) {
            return;
        }
        if (isSharedDatabase()) {
            handle.createStatement(
                    "update " + tableName +
                    " set hold_expire_time = " + statementUnixTimestampSql() + " + :lockSeconds, hold_agent_id = :agentId" +
                    " where id in (" +
                        locks.stream()
                        .map(it -> Long.toString(it.getLockId())).collect(Collectors.joining(", ")) +
                    ")"
                )
                .bind("lockSeconds", lockSeconds)
                .bind("agentId", agentId)
                .execute();
        }
        else {
            handle.createStatement(
                    "update " + tableName +
                    " set hold_expire_time = :expireTime, hold_agent_id = :agentId" +
                    " where id in (" +
                        locks.stream()
                        .map(it -> Long.toString(it.getLockId())).collect(Collectors.joining(", ")) +
                    ")"
                )
                .bind("expireTime", Instant.now().getEpochSecond() + lockSeconds)
                .bind("agentId", agentId)
                .execute();
        }
    }

    public boolean heartbeat(LockResult lock, String agentId, int lockSeconds)
    {
        String tableName = lock.getSharedTask() ? "queued_shared_task_locks" : "queued_task_locks";

        return autoCommit((handle, dao) -> {
            if (isSharedDatabase()) {
                return handle.createStatement(
                        "update " + tableName +
                        " set hold_expire_time = " + statementUnixTimestampSql() + " + :lockSeconds" +
                        " where id = :id" +
                        " and hold_agent_id = :agentId"
                    )
                    .bind("lockSeconds", lockSeconds)
                    .bind("id", lock.getLockId())
                    .bind("agentId", agentId)
                    .execute();
            }
            else {
                return handle.createStatement(
                        "update " + tableName +
                        " set hold_expire_time = :expireTime" +
                        " where id = :id" +
                        " and hold_agent_id = :agentId"
                    )
                    .bind("expireTime", Instant.now().getEpochSecond() + lockSeconds)
                    .bind("id", lock.getLockId())
                    .bind("agentId", agentId)
                    .execute();
            }
        }) > 0;
    }

    private List<Long> tryLockTasks(Handle handle, String tableName, int qId, int limit)
    {
        boolean locked;
        try {
            locked = localLockMap.tryLock(qId, 500);
        }
        catch (InterruptedException ex) {
            return ImmutableList.of();
        }
        if (locked) {
            try {
                // TODO if isSharedDatabase (postgresql) and skipLockedAvailable is false, use pg_try_advisory_lock
                //      to lock qId so that only one thread can start a task in a qId concurrently and number of
                //      running tasks doesn't overcommit
                if (isSharedDatabase()) {
                    return handle.createQuery(
                            "with rr as (" +
                                "select resource_type_id, count(*) as count " +
                                "from " + tableName + " " +
                                "where queue_id = " + qId + " " +
                                "and hold_expire_time is not null " +
                                "and resource_type_id is not null " +
                                "group by resource_type_id" +
                            ") " +
                            "select ks.id " +
                            "from " + tableName + " ks " +
                            "where exists ( " +
                                "select * from queues " +
                                "where id = " + qId + " " +
                                "and max_concurrency > (select coalesce(sum(count), 0) from rr)" +
                                (skipLockedAvailable ? " for update skip locked" : "") +
                            ") " +
                            "and queue_id = " + qId + " " +
                            "and not exists ( " +
                                "select * from resource_types rt " +
                                "join rr on rt.id = rr.resource_type_id " +
                                "where rt.id = ks.resource_type_id " +
                                "and rt.max_concurrency <= count" +
                            ") " +
                            "and hold_expire_time is null " +
                            "order by priority desc, id asc " +
                            "for update"
                        )
                        .mapTo(long.class)
                        .list();
                }
                else {
                    return handle.createQuery(
                            "select ks.id " +
                            "from " + tableName + " ks " +
                            "where queue_id = " + qId + " " +
                            "and exists (" +
                                "select * from queues " +
                                "where id = " + qId + " " +
                                "and max_concurrency > (" +
                                    "select count(*) as count " +
                                    "from " + tableName + " " +
                                    "where queue_id = " + qId + " " +
                                    "and hold_expire_time is not null" +
                                ")" +
                            ") " +
                            "and not exists (" +
                                "select rt.id " +
                                "from resource_types rt " +
                                "join (" +
                                    "select resource_type_id, count(*) as count " +
                                    "from " + tableName + " " +
                                    "where queue_id = " + qId + " " +
                                    "and hold_expire_time is not null " +
                                    "and resource_type_id is not null " +
                                    "group by resource_type_id" +
                                ") rr on rt.id = rr.resource_type_id " +
                                "where rt.id = ks.resource_type_id " +
                                "and rt.max_concurrency <= count" +
                            ") " +
                            "and hold_expire_time is null " +
                            "order by priority desc, id asc"
                            // h2 database doesn't need FOR UPDATE
                        )
                        .mapTo(long.class)
                        .list();
                }
            }
            finally {
                localLockMap.unlock(qId);
            }
        }
        else {
            return ImmutableList.of();
        }
    }

    private void expireLocks()
    {
        expireLocks("queued_shared_task_locks");
        expireLocks("queued_task_locks");
    }

    private void expireLocks(String tableName)
    {
        try {
            int c = autoCommit((handle, dao) -> {
                if (isSharedDatabase()) {
                    return handle.createStatement(
                            "update " + tableName +
                            " set hold_expire_time = NULL, hold_agent_id = NULL, retry_count = retry_count + 1" +
                            " where hold_expire_time is not null" +
                            " and hold_expire_time < " + statementUnixTimestampSql()
                        )
                        .execute();
                }
                else {
                    return handle.createStatement(
                            "update " + tableName +
                            " set hold_expire_time = NULL, hold_agent_id = NULL, retry_count = retry_count + 1" +
                            " where hold_expire_time is not null" +
                            " and hold_expire_time < :expireTime"
                        )
                        .bind("expireTime", Instant.now().getEpochSecond())
                        .execute();
                }
            });
            if (c > 0) {
                logger.warn("{} task locks are expired. Tasks will be retried.", c);
            }
        }
        catch (Throwable t) {
            logger.error("An uncaught exception is ignored. Lock expireation thread will be retried.", t);
        }
    }

    public byte[] getTaskData(long lockId)
        throws ResourceNotFoundException
    {
        return autoCommit((handle, dao) -> dao.getTaskData(lockId));
    }

    public interface Dao
    {
        @SqlUpdate("insert into queued_tasks" +
                " (site_id, queue_id, priority, resource_type_id, task_id, data, created_at)" +
                " values (:siteId, :queueId, :priority, :resourceTypeId, :taskId, :data, now())")
        @GetGeneratedKeys
        long insertQueuedTask(@Bind("siteId") int siteId, @Bind("queueId") int queueId, @Bind("priority") int priority,
                @Bind("resourceTypeId") Integer resourceTypeId, @Bind("taskId") long taskId,
                @Bind("data") byte[] data);

        @SqlUpdate("insert into queued_shared_task_locks" +
                " (id, queue_id, priority, resource_type_id, retry_count, hold_expire_time)" +
                " values (:id, :queueId, :priority, :resourceTypeId, 0, NULL)")
        int insertQueuedSharedTaskLock(@Bind("id") long id,
                @Bind("queueId") int queueId, @Bind("priority") int priority,
                @Bind("resourceTypeId") Integer resourceTypeId);

        @SqlUpdate("insert into queued_task_locks" +
                " (id, queue_id, priority, resource_type_id, retry_count, hold_expire_time)" +
                " values (:id, :queueId, :priority, :resourceTypeId, 0, NULL)")
        int insertQueuedTaskLock(@Bind("id") long id,
                @Bind("queueId") int queueId, @Bind("priority") int priority,
                @Bind("resourceTypeId") Integer resourceTypeId);

        @SqlQuery("select data from queued_tasks where id = :taskId")
        byte[] getTaskData(@Bind("taskId") long taskId);

        @SqlUpdate("delete from queued_shared_task_locks" +
                " where id = :taskId" +
                " and hold_agent_id = :agentId")
        int deleteSharedTaskLock(@Bind("taskId") long taskId, @Bind("agentId") String agentId);

        @SqlUpdate("delete from queued_task_locks" +
                " where id = :taskId" +
                " and hold_agent_id = :agentId")
        int deleteTaskLock(@Bind("taskId") long taskId, @Bind("agentId") String agentId);

        @SqlUpdate("delete from queued_tasks" +
                " where id = :taskId and site_id = :siteId")
        int deleteTask(@Bind("taskId") long taskId, @Bind("siteId") int siteId);
    }
}
