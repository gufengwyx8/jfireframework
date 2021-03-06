package com.jfireframework.jnet.server.CompletionHandler;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;
import com.jfireframework.baseutil.collection.buffer.ByteBuf;
import com.jfireframework.baseutil.collection.buffer.DirectByteBuf;
import com.jfireframework.baseutil.concurrent.CpuCachePadingInt;
import com.jfireframework.baseutil.disruptor.Disruptor;
import com.jfireframework.baseutil.disruptor.Sequence;
import com.jfireframework.baseutil.simplelog.ConsoleLogFactory;
import com.jfireframework.baseutil.simplelog.Logger;
import com.jfireframework.jnet.common.channel.impl.ServerChannel;
import com.jfireframework.jnet.common.decodec.FrameDecodec;
import com.jfireframework.jnet.common.exception.BufNotEnoughException;
import com.jfireframework.jnet.common.exception.LessThanProtocolException;
import com.jfireframework.jnet.common.exception.NotFitProtocolException;
import com.jfireframework.jnet.common.handler.DataHandler;
import com.jfireframework.jnet.common.result.ServerInternalTask;
import com.jfireframework.jnet.server.util.AsyncTaskCenter;
import com.jfireframework.jnet.server.util.WorkMode;
import com.jfireframework.jnet.server.util.WriteMode;

public class ReadCompletionHandler implements CompletionHandler<Integer, ServerChannel>
{
    private static final Logger          logger         = ConsoleLogFactory.getLogger();
    private final FrameDecodec           frameDecodec;
    private final DataHandler[]          handlers;
    private final DirectByteBuf          ioBuf          = DirectByteBuf.allocate(100);
    private final ServerChannel          serverChannel;
    private final CpuCachePadingInt      readState      = new CpuCachePadingInt(IN_READ);
    public final static int              IN_READ        = 1;
    public final static int              OUT_OF_READ    = 2;
    private final Sequence               sequence       = new Sequence(0);
    private long                         wrapPoint      = 0;
    private final WriteCompletionHandler writeCompletionHandler;
    // 读取超时时间
    private final long                   readTimeout;
    private final long                   waitTimeout;
    // 最后一次读取时间
    private long                         lastReadTime;
    // 本次读取的截止时间
    private long                         endReadTime;
    // 启动读取超时的计数
    private boolean                      startCountdown = false;
    private final WorkMode               workMode;
    private final AsyncTaskCenter        asyncTaskCenter;
    private final int                    capacity;
    private final Disruptor              disruptor;
    
    public ReadCompletionHandler(ServerChannel serverChannel, WorkMode workMode, AsyncTaskCenter asyncTaskCenter, WriteMode writeMode, int maxBatchWriteNum, Disruptor disruptor)
    {
        this.disruptor = disruptor;
        this.asyncTaskCenter = asyncTaskCenter;
        this.workMode = workMode;
        this.serverChannel = serverChannel;
        capacity = serverChannel.capacity();
        frameDecodec = serverChannel.getFrameDecodec();
        handlers = serverChannel.getHandlers();
        readTimeout = serverChannel.getReadTimeout();
        waitTimeout = serverChannel.getWaitTimeout();
        if (workMode == WorkMode.ASYNC_WITHOUT_ORDER)
        {
            writeCompletionHandler = new UnOrderedWriteCompletionHandler(this, serverChannel, maxBatchWriteNum);
        }
        else
        {
            writeCompletionHandler = new OrderedWriteCompletionHandler(this, serverChannel, writeMode, maxBatchWriteNum);
        }
    }
    
    @Override
    public void completed(Integer read, ServerChannel channelInfo)
    {
        if (read == -1)
        {
            channelInfo.closeChannel();
            return;
        }
        ioBuf.addWriteIndex(read);
        doRead();
    }
    
    @Override
    public void failed(Throwable exc, ServerChannel channelInfo)
    {
        catchThrowable(exc);
        ioBuf.release();
    }
    
    /**
     * 使用处理器处理异常，处理之后关闭当前的通道
     * 
     * @param exc
     */
    public void catchThrowable(Throwable exc)
    {
        try
        {
            ServerInternalTask result = new ServerInternalTask(-1, exc, serverChannel, this, writeCompletionHandler, 0);
            Object intermediateResult = exc;
            try
            {
                for (DataHandler each : handlers)
                {
                    intermediateResult = each.catchException(intermediateResult, result);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        catch (Exception e)
        {
            logger.error("关闭通道异常", e);
        }
        serverChannel.closeChannel();
        /**
         * 这个方法里不能去释放iobuf。因为这个方法有可能是异步处理的时候被调用，这样通道还没有关闭的情况下就先释放了iobuf，
         * 然后关闭通道又释放一次就会造成错误
         * 或者是该方法中被释放，其他地方回收了又再次使用，然后通过中关闭的时候释放掉，就错误的释放了别的地方的ioBuf。
         * 所以这个方法中是不可以释放iobuf的，
         * 一定是要在ReadCompletionHandler的complete或者fail方法中完成对iobuf的释放
         */
    }
    
    public void doRead()
    {
        while (true)
        {
            try
            {
                int result = frameAndHandle();
                if (result == IN_READ)
                {
                    readAndWait();
                    return;
                }
                else if (result == OUT_OF_READ)
                {
                    return;
                }
            }
            catch (LessThanProtocolException e)
            {
                readAndWait();
                return;
            }
            catch (BufNotEnoughException e)
            {
                ioBuf.compact().ensureCapacity(e.getNeedSize());
                continueRead();
                return;
            }
            catch (NotFitProtocolException e)
            {
                logger.debug("协议错误，关闭链接");
                catchThrowable(e);
                ioBuf.release();
                return;
            }
            catch (Throwable e)
            {
                catchThrowable(e);
                ioBuf.release();
                return;
            }
        }
    }
    
    public void reStartRead()
    {
        int state = readState.value();
        if (state == OUT_OF_READ)
        {
            if (readState.compareAndSwap(OUT_OF_READ, IN_READ))
            {
                // logger.trace("恢复读取，当前读取{}", cursor - 1);
                doRead();
            }
        }
    }
    
    public int frameAndHandle() throws Exception
    {
        while (true)
        {
            long cursor = sequence.value();
            testcursor: if (cursor >= wrapPoint)
            {
                wrapPoint = writeCompletionHandler.cursor() + capacity;
                if (cursor < wrapPoint)
                {
                    break testcursor;
                }
                // 在设置之前，可能写线程已经将所有的数据都写出完毕了并且写线程结束运行。此时就不会有人来唤醒读取线程了
                readState.set(OUT_OF_READ);
                // 设置之后必须进行尝试
                wrapPoint = writeCompletionHandler.cursor() + capacity;
                if (cursor < wrapPoint)
                {
                    if (readState.compareAndSwap(OUT_OF_READ, IN_READ))
                    {
                        break testcursor;
                    }
                    else
                    {
                        return OUT_OF_READ;
                    }
                }
                return OUT_OF_READ;
            }
            Object intermediateResult = frameDecodec.decodec(ioBuf);
            if (intermediateResult == null)
            {
                return IN_READ;
            }
            if (workMode == WorkMode.ASYNC_WITHOUT_ORDER)
            {
                ServerInternalTask task = asyncTaskCenter.askFor();
                task.init(-1, intermediateResult, serverChannel, this, writeCompletionHandler, 0);
                sequence.set(cursor + 1);
                asyncTaskCenter.addTask(task);
                if (ioBuf.remainRead() == 0)
                {
                    return IN_READ;
                }
            }
            else
            {
                ServerInternalTask task = (ServerInternalTask) serverChannel.getData(cursor);
                task.init(cursor, intermediateResult, serverChannel, this, writeCompletionHandler, 0);
                sequence.set(cursor + 1);
                switch (workMode)
                {
                    case MIX_WITH_ORDER:
                    {
                        for (int i = 0; i < handlers.length;)
                        {
                            intermediateResult = handlers[i].handle(intermediateResult, task);
                            if (intermediateResult == DataHandler.skipToWorkRing)
                            {
                                break;
                            }
                            if (i == task.getIndex())
                            {
                                i++;
                                task.setIndex(i);
                            }
                            else
                            {
                                i = task.getIndex();
                            }
                        }
                        if (intermediateResult instanceof ByteBuf<?>)
                        {
                            task.setData(intermediateResult);
                            long version = task.version();
                            task.flowDone();
                            task.write(version);
                        }
                        break;
                    }
                    case SYNC_WITH_ORDER:
                    {
                        for (int i = 0; i < handlers.length;)
                        {
                            intermediateResult = handlers[i].handle(intermediateResult, task);
                            if (i == task.getIndex())
                            {
                                i++;
                                task.setIndex(i);
                            }
                            else
                            {
                                i = task.getIndex();
                            }
                        }
                        if (intermediateResult instanceof ByteBuf<?>)
                        {
                            task.setData(intermediateResult);
                            long version = task.version();
                            task.flowDone();
                            task.write(version);
                        }
                        break;
                    }
                    case ASYNC_WITH_ORDER:
                    {
                        disruptor.publish(task);
                        break;
                    }
                    default:
                        throw new RuntimeException("error");
                }
                if (ioBuf.remainRead() == 0)
                {
                    return IN_READ;
                }
            }
        }
    }
    
    public boolean isAvailable(long cursor)
    {
        return cursor < sequence.value();
    }
    
    /**
     * 开始空闲读取等待，并且将倒数计时状态重置为false
     */
    public void readAndWait()
    {
        startCountdown = false;
        serverChannel.getSocketChannel().read(getWriteBuffer(), waitTimeout, TimeUnit.MILLISECONDS, serverChannel, this);
    }
    
    /**
     * 将iobuf的内容进行压缩，返回一个处于可写状态的ByteBuffer
     * 
     * @return
     */
    private ByteBuffer getWriteBuffer()
    {
        ioBuf.compact();
        ByteBuffer ioBuffer = ioBuf.nioBuffer();
        ioBuffer.position(ioBuffer.limit()).limit(ioBuffer.capacity());
        return ioBuffer;
    }
    
    /**
     * 在通道上继续读取未读取完整的数据
     */
    public void continueRead()
    {
        if (startCountdown == false)
        {
            lastReadTime = System.currentTimeMillis();
            endReadTime = lastReadTime + readTimeout;
            startCountdown = true;
        }
        serverChannel.getSocketChannel().read(getWriteBuffer(), getRemainTime(), TimeUnit.MILLISECONDS, serverChannel, this);
        lastReadTime = System.currentTimeMillis();
    }
    
    /**
     * 剩余的读取消息时间
     * 
     * @return
     */
    private long getRemainTime()
    {
        long time = endReadTime - lastReadTime;
        return time;
    }
    
    public void handleAsync(ServerInternalTask task)
    {
        asyncTaskCenter.addTask(task);
    }
    
    public long cursor()
    {
        return sequence.value();
    }
}
