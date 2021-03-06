package com.jfireframework.mvc.binder.impl;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.jfireframework.mvc.binder.ParamInfo;

public class SqlDateBinder extends DateBinder
{
    
    public SqlDateBinder(ParamInfo info, Set<Class<?>> cycleSet)
    {
        super(info, cycleSet);
    }
    
    @Override
    public Object binder(HttpServletRequest request, Map<String, String> map, HttpServletResponse response)
    {
        Date date = (Date) super.binder(request, map, response);
        if (date != null)
        {
            return new java.sql.Date(date.getTime());
        }
        else
        {
            return null;
        }
    }
    
}
