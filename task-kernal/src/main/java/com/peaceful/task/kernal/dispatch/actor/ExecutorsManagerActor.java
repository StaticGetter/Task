package com.peaceful.task.kernal.dispatch.actor;

import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.UntypedActor;
import akka.event.DiagnosticLoggingAdapter;
import akka.event.Logging;
import akka.japi.Function;
import com.peaceful.task.kernal.TaskContext;
import com.peaceful.task.kernal.coding.TUR;
import com.peaceful.task.kernal.conf.Executor;
import com.peaceful.task.kernal.dispatch.PullTask;
import com.peaceful.task.kernal.dispatch.actor.msg.TaskCompleted;
import com.peaceful.task.kernal.helper.TaskLog;
import scala.Option;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Executor 分发器,负责将Task对象分发到对应的executor上执行
 *
 * @author <a href="mailto:wangjuntytl@163.com">WangJun</a>
 * @version 1.0 16/1/12
 */
public class ExecutorsManagerActor extends UntypedActor {

    private List<String> executors;
    final DiagnosticLoggingAdapter log = Logging.getLogger(this);

    private TaskContext context;
    private PullTask pullTask;

    public ExecutorsManagerActor(TaskContext context) {
        this.context = context;
        this.pullTask = PullTask.get(context);
    }

    @Override
    public void preStart() throws Exception {
        // 创建对应executor的监管者
        executors = new ArrayList<String>();
        for (Executor executor : context.getConfigOps().executorConfigOps.executorNodeList) {
            getContext().actorOf(Props.create(ExecutorSupervisionActor.class, executor.Class.newInstance(), context), executor.name);
            log.info("Started executor[{}] OK...", executor.name);
            executors.add(executor.name);
        }
        super.preStart();
    }

    @Override
    public void preRestart(Throwable reason, Option<Object> message) throws Exception {
        // 处理失败的异常信息和最后处理的消息将发送过来
        log.error("{} will preRestart,resone:{},message: {}", getSelf().path().name(), reason, message);
        super.preRestart(reason, message);
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof TUR) {
            // 来自于TaskDispatcherService的外部队列监控消息
            TUR taskUnit = (TUR) message;
            if (executors.contains(taskUnit.getTask().executor)) {
                getContext().actorSelection(taskUnit.getTask().executor).tell(taskUnit, getSelf());
            } else {
                TaskLog.DISPATCH_TASK.warn("execute {} is not exist,the task {} will push to {}", taskUnit.getTask().executor, taskUnit.getTask().id, executors.get(0));
                getContext().actorSelection(executors.get(0)).tell(taskUnit, getSelf());
            }
        } else if (message instanceof TaskCompleted) {
            // 收到任务执行完毕的消息
            TaskCompleted completed = (TaskCompleted) message;
            if (completed.isHasException) {
                context.getTaskMonitor().exceptionIncr(completed.queue);
            }
            Object[] params = new Object[]{completed.id, completed.executor, completed.startTime - completed.submitTime, completed.startTime - completed.createTime, completed.completeTime - completed.startTime};
            // 如果本地的taskUnit对象已经缓存时间超过1s,停止向executor主动推送task unit,这样可以让已经缓存的task unit 尽快执行,因为设计这个系统的初衷并不想把这些task unit缓存到本地
            if (completed.startTime - completed.createTime > 1000) {
                TaskLog.DISPATCH_TASK.warn("SLOW TASK: completed {} on {} remote wait {}ms local wait {}ms cost {}ms", params);
                return;
            } else {
                TaskLog.COMPLETE_TASK.info("completed {} on {} remote wait {}ms local wait {}ms cost {}ms", params);
            }
            TUR taskUnit = pullTask.next(completed.queue);
            if (taskUnit != null) {
                getContext().actorSelection(taskUnit.getTask().executor).tell(taskUnit, getSelf());
            }
        } else {
            unhandled(message);
        }
    }

    /**
     * executor 运行监管策略
     *
     * @return
     */
    @Override
    public SupervisorStrategy supervisorStrategy() {
        SupervisorStrategy strategy = new OneForOneStrategy(
                10, Duration.create("1 minute"), new Function<Throwable, SupervisorStrategy.Directive>() {
            @Override
            public SupervisorStrategy.Directive apply(Throwable t) {
                // 开发者把异常信息抛给了调度系统,调度系统懒得处理最好也不要让调度系统处理异常
                TaskLog.DISPATCH_TASK.error("executor supervisor[{}] receive task execute exception,you should handle the exception,don't throw to TaskSystem,exception:{}", getSender().path().name(), t);
                // ignore all exception from executor
                return SupervisorStrategy.resume();
            }
        });
        return strategy;
    }

}
