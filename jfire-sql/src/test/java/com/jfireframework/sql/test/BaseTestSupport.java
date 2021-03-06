package com.jfireframework.sql.test;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.Before;
import com.alibaba.druid.pool.DruidDataSource;
import com.jfireframework.baseutil.simplelog.ConsoleLogFactory;
import com.jfireframework.baseutil.simplelog.Logger;
import com.jfireframework.dbunit.schema.DbUnit;
import com.jfireframework.sql.function.SqlSession;
import com.jfireframework.sql.function.impl.SessionFactoryImpl;

public abstract class BaseTestSupport
{
    protected static DbUnit             testUnit;
    protected static SessionFactoryImpl sessionFactory;
    protected SqlSession                session;
    protected Connection                connection;
    protected Logger                    logger = ConsoleLogFactory.getLogger();
                                               
    static
    {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:mysql://localhost:3306/test?characterEncoding=utf8");
        dataSource.setUsername("root");
        dataSource.setPassword("centerm");
        dataSource.setMaxActive(150);
        dataSource.setMaxWait(500);
        try
        {
            dataSource.getConnection();
        }
        catch (SQLException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        sessionFactory = new SessionFactoryImpl(dataSource);
        sessionFactory.setScanPackage("com.jfireframework.sql.test");
        sessionFactory.init();
        testUnit = new DbUnit(DbUnit.SAVE_IN_MEM, dataSource);
    }
    
    public BaseTestSupport()
    {
//        testUnit.clearSchemaData();
//        testUnit.importExcelFile();
    }
    
//    @Before
    public void before()
    {
        testUnit.clearSchemaData();
        testUnit.importExcelFile();
        session = sessionFactory.getCurrentSession();
        if (session == null)
        {
            session = sessionFactory.openSession();
        }
        connection = session.getConnection();
    }
    
    // @After
    public void after()
    {
        testUnit.clearSchemaData();
        testUnit.restoreSchemaData();
        session.close();
    }
    
}
