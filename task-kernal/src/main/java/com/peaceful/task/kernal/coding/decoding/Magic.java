package com.peaceful.task.kernal.coding.decoding;


import com.google.inject.Singleton;
import com.peaceful.task.kernal.coding.TU;
import com.peaceful.task.kernal.coding.TUR;

/**
 * @author <a href="mailto:wangjuntytl@163.com">WangJun</a>
 * @version 1.0 16/1/12
 */
@Singleton
public class Magic {

    public TUR go(InvokeContext invokeContext) {
        TU task02 = new TU();
        task02.id = invokeContext.id;
        task02.queueName = invokeContext.queueName;
        task02.aclass = invokeContext.aClass;
        task02.method = invokeContext.methodName;
        task02.args = invokeContext.newArgs;
        task02.parameterTypes = invokeContext.parameterTypes;
        task02.executor = invokeContext.executor;
        task02.submitTime = invokeContext.submitTime;
        TUR taskUnit = new TUR(task02);
        return taskUnit;
    }
}
