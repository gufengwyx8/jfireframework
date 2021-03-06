package com.jfireframework.jnet.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import com.jfireframework.baseutil.StringUtil;
import com.jfireframework.baseutil.exception.UnSupportException;
import com.jfireframework.baseutil.simplelog.ConsoleLogFactory;
import com.jfireframework.baseutil.simplelog.Logger;
import com.jfireframework.jnet.server.CompletionHandler.AcceptHandler;
import com.jfireframework.jnet.server.util.ExecutorMode;
import com.jfireframework.jnet.server.util.ServerConfig;
import com.jfireframework.jnet.server.util.WorkMode;

public class AioServer
{
    private AcceptHandler                   acceptCompleteHandler;
    private AsynchronousServerSocketChannel serverSocketChannel;
    private Logger                          logger = ConsoleLogFactory.getLogger();
    private AsynchronousChannelGroup        channelGroup;
    private ServerConfig                    serverConfig;
    
    public AioServer(ServerConfig serverConfig)
    {
        this.serverConfig = serverConfig;
        checkServerConfig();
    }
    
    private void checkServerConfig()
    {
        WorkMode workMode = serverConfig.getWorkMode();
        ExecutorMode executorMode = serverConfig.getExecutorMode();
        if (workMode != WorkMode.SYNC_WITH_ORDER && executorMode == ExecutorMode.FIX)
        {
            throw new UnSupportException(StringUtil.format("配置错误！如果选择异步模式或者混合模式，那么线程池类型应该选择cached模式"));
        }
    }
    
    public AsynchronousServerSocketChannel getServerSocketChannel()
    {
        return serverSocketChannel;
    }
    
    /**
     * 以端口初始化server服务器。
     * 
     * @param port
     */
    public void start()
    {
        acceptCompleteHandler = new AcceptHandler(this, serverConfig);
        ThreadFactory threadFactory = new ThreadFactory() {
            int i = 1;
            
            @Override
            public Thread newThread(Runnable r)
            {
                return new Thread(r, "服务端socket线程-" + (i++));
            }
        };
        try
        {
            switch (serverConfig.getExecutorMode())
            {
                case FIX:
                    channelGroup = AsynchronousChannelGroup.withFixedThreadPool(serverConfig.getSocketThreadSize(), threadFactory);
                    break;
                case CACHED:
                    channelGroup = AsynchronousChannelGroup.withCachedThreadPool(Executors.newCachedThreadPool(threadFactory), serverConfig.getSocketThreadSize());
                    break;
            }
            serverSocketChannel = AsynchronousServerSocketChannel.open(channelGroup).bind(new InetSocketAddress(serverConfig.getPort()));
            logger.info("监听启动");
            serverSocketChannel.accept(null, acceptCompleteHandler);
        }
        catch (IOException e)
        {
            logger.error("服务器启动失败", e);
            throw new RuntimeException(e);
        }
    }
    
    public void stop()
    {
        try
        {
            if (channelGroup != null)
            {
                channelGroup.shutdownNow();
                channelGroup.awaitTermination(10, TimeUnit.SECONDS);
            }
            acceptCompleteHandler.stop();
            logger.info("服务器关闭");
        }
        catch (Exception e)
        {
            logger.error("关闭服务器失败", e);
            throw new RuntimeException(e);
        }
    }
}
