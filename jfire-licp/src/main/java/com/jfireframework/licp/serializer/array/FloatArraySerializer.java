package com.jfireframework.licp.serializer.array;

import com.jfireframework.baseutil.collection.buffer.ByteBuf;
import com.jfireframework.licp.Licp;

public class FloatArraySerializer extends AbstractArraySerializer
{
    
    public FloatArraySerializer()
    {
        super(float[].class);
    }
    
    @Override
    public void serialize(Object src, ByteBuf<?> buf, Licp licp)
    {
        float[] array = (float[]) src;
        buf.writePositive(array.length);
        for (float each : array)
        {
            buf.writeFloat(each);
        }
    }
    
    @Override
    public Object deserialize(ByteBuf<?> buf, Licp licp)
    {
        int length = buf.readPositive();
        float[] array = new float[length];
        licp.putObject(array);
        for (int i = 0; i < length; i++)
        {
            array[i] = buf.readFloat();
        }
        return array;
    }
    
}
