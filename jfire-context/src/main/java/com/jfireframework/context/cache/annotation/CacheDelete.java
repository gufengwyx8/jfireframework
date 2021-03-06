package com.jfireframework.context.cache.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface CacheDelete
{
    /**
     * key的规则
     * 
     * @return
     */
    public String value();
    
    /**
     * 缓存名称
     * 
     * @return
     */
    public String cacheName() default "default";
    
    /**
     * 进行缓存操作的条件
     * 
     * @return
     */
    public String condition() default "";
}
