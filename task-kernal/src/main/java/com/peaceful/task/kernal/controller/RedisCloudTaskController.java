package com.peaceful.task.kernal.controller;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.peaceful.common.redis.service.Redis;
import com.peaceful.common.util.ExceptionUtils;
import com.peaceful.task.kernal.TaskQueue;
import com.peaceful.task.kernal.conf.ExecutorsConf;
import com.peaceful.task.kernal.helper.Node;
import com.peaceful.task.kernal.conf.Executor;
import com.peaceful.task.kernal.conf.TaskConfigOps;
import com.peaceful.task.kernal.dispatch.TaskMeta;
import com.peaceful.task.kernal.helper.NetHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 任务调度控制状态信息保存到Redis中
 *
 * @author WangJun
 * @version 1.0 16/3/30
 */
@Singleton
public class RedisCloudTaskController extends LocalTaskController implements CloudController {


    private static String prefix = "TASK-";
    // 任务配置列表
    private static String task_list = "";
    // 集群列表
    private static String task_node_list = "";
    // 执行器列表
    private static String task_executor_list = "";
    private TaskQueue queue;
    private final static Logger LOGGER = LoggerFactory.getLogger(RedisCloudTaskController.class);

    @Inject
    public RedisCloudTaskController(TaskConfigOps ops,TaskQueue queue) {
        super(ops,queue);
        this.queue = queue;
        prefix += ops.name;
        task_list = prefix + "-CONFIG-LIST";
        task_node_list = prefix + "-NODE" + "-LIST";
        task_executor_list = prefix + "-EXECUTOR-LIST";
        ExecutorsConf.SINGLE_THREAD_SCHEDULED.scheduleWithFixedDelay(new RefreshController(), 1, 2, TimeUnit.SECONDS);
        ExecutorsConf.SINGLE_THREAD_SCHEDULED.submit(new UploadExecutorsOnStart());
    }

    private void upload() {
        // 上传任务配置信息，在上传前应该先同步
        Collection<TaskMeta> tasks = findAllTasks();
        for (TaskMeta task : tasks) {
            Redis.cmd().hset(task_list, task.name, JSON.toJSONString(task));
        }

        // 上传服务节点信息
        addNode();
        for (Node node : NODE_MAP.values()) {
            if ((node.updateTime + TimeUnit.MINUTES.toMillis(5)) < System.currentTimeMillis()) {
                // 五分钟内没有心跳,移除该节点
                NODE_MAP.remove(node.hostName);
                Redis.cmd().hdel(task_node_list, node.hostName);
            } else {
                Redis.cmd().hset(task_node_list, node.hostName, JSON.toJSONString(node));
            }
        }
    }

    private void download() {
        // 更新本地任务配置信息
        // 同步策略: desc state createTime 以redis为主  expire reserve 以本地为主,只上传,不下载 , updateTime 需要以最大为主
        Map<String, String> configs = Redis.cmd().hgetAll(task_list);
        for (String name : configs.keySet()) {
            TaskMeta task = JSON.parseObject(configs.get(name), TaskMeta.class);
            TaskMeta local = TASK_LIST.get(name);
            if (local == null) {
                local = TASK_HISTORY_LIST.get(task.name);
            }
            // 本地存在这个任务
            if (local != null) {
                // 以redis为准
                local.state = task.state;
                local.desc = task.desc;
                local.createTime = task.createTime;

                // updateTime字段以最大者为准
                if (local.updateTime < task.updateTime) {
                    local.updateTime = task.updateTime;
                }

                // reserve 字段以当前队列大小计算
                if (local.expire) {
                    local.reserve = 0;
                } else {
                    local.reserve =queue.size(prefix + "-" + task.name);
                }

            } else {
                // 本地不存在的可能性：1. 任务是有其它节点上传 2. 任务已经过期，本地已经清除
                if (task.expire) {
                    // 如果服务端认为是过期的,本地没有该任务就不要同步了
                    Redis.cmd().hdel(task_list, task.name);
                } else {
                    TASK_LIST.put(task.name, task);
                }
            }
        }

        // 更新集群配置信息,节点信息主要是状态属性，以Redis为主
        Map<String, String> nodeConfigs = Redis.cmd().hgetAll(task_node_list);
        for (String name : nodeConfigs.keySet()) {
            Node node = JSON.parseObject(nodeConfigs.get(name), Node.class);
            Node local = NODE_MAP.get(node.hostName);
            if (local != null && local.updateTime > node.updateTime) {
                node.updateTime = local.updateTime;
            }
            NODE_MAP.put(name, node);
        }

    }

    @Override
    public void sync() {
        download();
        upload();
    }

    @Override
    public Collection<Node> findNodes() {
        return NODE_MAP.values();
    }


    private void addNode() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            String ip = InetAddress.getLocalHost().getHostAddress();
            if (NODE_MAP.containsKey(hostName)) {
                NODE_MAP.get(hostName).updateTime = System.currentTimeMillis();
            } else {
                Node node = new Node(hostName, ip);
                node.updateTime = System.currentTimeMillis();
                NODE_MAP.put(hostName, node);
            }
        } catch (UnknownHostException var8) {
            LOGGER.error("Error: {}", var8);
        }
    }

    private void removeNode(String hostname) {
        Redis.cmd().hdel(task_node_list, hostname);
    }

    @Override
    public Collection<TaskMeta> findNeedDispatchTasks() {
        String hostname = NetHelper.getHostname();
        if (NODE_MAP.containsKey(hostname) && NODE_MAP.get(hostname).state.equals("OK")) {
            return super.findNeedDispatchTasks();
        } else {
            return Sets.newHashSet();
        }
    }

    private class RefreshController implements Runnable {
        private Logger LOGGER = LoggerFactory.getLogger(RedisCloudTaskController.class);

        @Override
        public void run() {
            try {
                sync();
            } catch (Exception e) {
                LOGGER.error("refresh cluster controller error:{}", ExceptionUtils.getStackTrace(e));
            }

        }
    }

    private class UploadExecutorsOnStart implements Runnable{

        @Override
        public void run() {
            // 启动时把执行器都写入到redis中
            Redis.cmd().del(task_executor_list);
            for (Executor e : EXECUTOR_LIST) {
                Redis.cmd().hset(task_executor_list, e.name, e.implementation);
            }
        }
    }

}

