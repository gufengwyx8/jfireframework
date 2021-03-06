package com.jfireframework.baseutil.concurrent.time;

import java.util.concurrent.TimeUnit;

public interface Timer extends Runnable
{
    /**
     * 添加一个任务，同时指定该任务的延迟时间和时间单位。返回一个超时实例。
     * 
     * @param task
     * @param delay
     * @param unit
     * @return
     */
    public Timeout addTask(TimeTask task, long delay, TimeUnit unit);
    
    /**
     * 结束这个timer计时器。结束之后该timer不能再被使用
     */
    public void stop();
    
    public void start();
}
