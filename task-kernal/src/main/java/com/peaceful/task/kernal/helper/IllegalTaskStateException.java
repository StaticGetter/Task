package com.peaceful.task.kernal.helper;

/**
 * @author WangJun
 * @version 1.0 16/4/1
 */
public class IllegalTaskStateException extends RuntimeException{

    public IllegalTaskStateException(String msg){
        super(msg);
    }

}
