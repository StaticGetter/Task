package com.peaceful.task.kernal.dispatch;

import akka.actor.ActorSystem;
import akka.actor.Props;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.peaceful.task.kernal.Task;
import com.peaceful.task.kernal.coding.TUR;
import com.peaceful.task.kernal.conf.TaskConfigOps;
import com.peaceful.task.kernal.dispatch.actor.DispatchManagerActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 无阻塞自动任务调度模块
 * Created by wangjun on 16-8-27.
 */
@Singleton
public class NoBlockAutoConsumer extends AbstractIdleService {

    private ActorSystem system;
    private Logger logger = LoggerFactory.getLogger(getClass());
    private static Map<String,ActorSystem> systemMap = new HashMap<String, ActorSystem>();

    @Inject
    private TaskConfigOps ops;

    // 接收来自TaskDispatchService的消息,并将消息直接传入到ExecutorsManagerActor
    public void tell(TUR tur) {
        system.actorSelection("/user/dispatcher/executors").tell(tur,system.actorFor("/user/dispatcher/"));
    }

    protected void startUp() throws Exception {
        system = ActorSystem.create(ops.name);
        // 创建actor系统的top actor,所有actor都要求从这里继承下去
        system.actorOf(Props.create(DispatchManagerActor.class, Task.getTaskContext(ops.name)), "dispatcher");
        systemMap.put(ops.name,system);
        logger.info("no block auto dispatch service start...");
    }

    protected void shutDown() throws Exception {
        if (!system.isTerminated()) {
            system.shutdown();
        }
    }

    public static ActorSystem getSystem(String name){
        return systemMap.get(name);
    }
}
