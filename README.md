## 一、实现原理

MyBatis框架允许用户通过自定义拦截器的方式改变SQL的执行行为，所以MyBatis插件本质上其实是一个拦截器**InterceptorChain**，用户自定义的拦截器也被称为MyBatis插件。

例如：在SQL执行时追加SQL分页语法，从而达到简化分页查询的目的。

以执行一个查询操作为例，介绍一个MyBatis插件实现原理

SqlSession获取Executor实例的过程如下：

1. SqlSessionFactory中会调用Configuration类提供的newExecutor()工厂方法创建Executor对象
2. Configuration类中通过一个InterceptorChain对象维护了用户自定义的拦截器链。
3.  newExecutor()工厂方法中调用InterceptorChain对象的pluginAll()方法
4.  InterceptorChain对象的pluginAll()方法中会调用自定义拦截器的plugin()方法
5.  自定义拦截器的plugin()方法是由我们来编写的，通常会调用Plugin类的wrap()静态方法创建一个代理对象

![image-20230326221800085](https://damon-study.oss-cn-shenzhen.aliyuncs.com/%20typora/%E5%B9%B6%E5%8F%91%E7%BC%96%E7%A8%8Bimage-20230326221800085.png)

```
DefaultSqlSessionFactory#openSessionFromDataSource()//开启会话
    Configuration.newExecutor()//创建执行器
        executor = new CachingExecutor(executor);////创建缓存类型的执行器，装饰器模式
            InterceptorChain.pluginAll();//为执行器设置拦截器链
//当Executor执行的时候，就会触发拦截器，这其中包含我们编写的自定义拦截器
```

SqlSession获取到的Executor实例实际上是个动态代理对象。

接下来，我们就以SqlSession执行查询操作为例，介绍自定义插件执行拦截逻辑的过程。

1.  SqlSession操作数据库依赖于Executor，SqlSession会调用SqlSessionFactory#openSession()方法中Configuration#newExecutor()创建的Executor代理对象
2.  SqlSession获取的是Executor组件的代理对象，执行查询操作时，会调用代理对象的query()方法
3.  按照JDK动态代理机制，调用Executor代理对象的query()方法时，会调用Plugin类的invoke()方法
4.  Plugin类的invoke()方法中会调用自定义拦截器对象的intercept()方法执行拦截逻辑
5.  自定义拦截器对象的intercept()方法调用完毕后，调用目标Executor对象的query()方法
6.  所有操作执行完毕后，会将查询结果返回给SqlSession对象

![image-20230326222227648](https://damon-study.oss-cn-shenzhen.aliyuncs.com/%20typora/%E5%B9%B6%E5%8F%91%E7%BC%96%E7%A8%8Bimage-20230326222227648.png)

```
SqlSessionFactory#selectOne()//执行查询
SqlSessionFactory#selectList()//执行查询
    ExecutorProxy#query()//代理执行器执行查询
        Plugin#invoke();//插件拦截
            MyInterceptor#intercept()//调用自定义拦截器的拦截方法
                Executor#query()//执行被代理的Executor的query方法
```

## 二、代码实现

### 2.1 自定义拦截器

MyBatis用户自定义插件类都必须实现Interceptor接口，因此我们自定义的PageInterceptor类也实现了该接口

```java
@Intercepts({
        // 这里指定对StatementHandler实例的prepare()方法进行拦截，
        @Signature(method = "prepare", type = StatementHandler.class, args = {Connection.class, Integer.class})
})
public class PageInterceptor implements Interceptor {

    private String databaseType;

    public Object intercept(Invocation invocation) throws Throwable {
        // 获取拦截的目标对象
        RoutingStatementHandler handler = (RoutingStatementHandler) invocation.getTarget();
        StatementHandler delegate = (StatementHandler) ReflectionUtils.getFieldValue(handler, "delegate");

        BoundSql boundSql = delegate.getBoundSql();
        // 获取参数对象，当参数对象为Page的子类时执行分页操作
        Object parameterObject = boundSql.getParameterObject();
        if (parameterObject instanceof Page<?>) {
            Page<?> page = (Page<?>) parameterObject;
            MappedStatement mappedStatement = (MappedStatement) ReflectionUtils.getFieldValue(delegate, "mappedStatement");
            //获取Connection对象
            Connection connection = (Connection) invocation.getArgs()[0];
            //获取拦截SQL语句
            String sql = boundSql.getSql();
            if (page.isFull()) {
                // 获取记录总数
                this.setTotalCount(page, mappedStatement, connection);
            }
            page.setTimestamp(System.currentTimeMillis());
            // 获取分页SQL
            String pageSql = this.getPageSql(page, sql);
            // 将原始SQL语句替换成分页语句
            ReflectionUtils.setFieldValue(boundSql, "sql", pageSql);
        }
        return invocation.proceed();
    }

    /**
     * 拦截器对应的封装原始对象的方法
     */
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    /**
     * 设置注册拦截器时设定的属性
     */
    public void setProperties(Properties properties) {
        this.databaseType = properties.getProperty("databaseType");
    }

    /**
     * 根据page对象获取对应的分页查询Sql语句，
     * 这里只做了三种数据库类型，MySQL、Oracle
     * 其它的数据库都没有进行分页
     * @param page 分页对象
     * @param sql  原始sql语句
     */
    private String getPageSql(Page<?> page, String sql) {
        StringBuffer sqlBuffer = new StringBuffer(sql);
        if ("mysql".equalsIgnoreCase(databaseType)) {
            return getMysqlPageSql(page, sqlBuffer);
        } else if ("oracle".equalsIgnoreCase(databaseType)) {
            return getOraclePageSql(page, sqlBuffer);
        }
        return sqlBuffer.toString();
    }

    /**
     * 获取Mysql数据库的分页查询语句
     *
     * @param page      分页对象
     * @param sqlBuffer 包含原sql语句的StringBuffer对象
     * @return Mysql数据库分页语句
     */
    private String getMysqlPageSql(Page<?> page, StringBuffer sqlBuffer) {
        int offset = (page.getPageNo() - 1) * page.getPageSize();
        sqlBuffer.append(" limit ").append(offset).append(",").append(page.getPageSize());
        return sqlBuffer.toString();
    }

    /**
     * 获取Oracle数据库的分页查询语句
     *
     * @param page      分页对象
     * @param sqlBuffer 包含原sql语句的StringBuffer对象
     * @return Oracle数据库的分页查询语句
     */
    private String getOraclePageSql(Page<?> page, StringBuffer sqlBuffer) {
        int offset = (page.getPageNo() - 1) * page.getPageSize() + 1;
        sqlBuffer.insert(0, "select u.*, rownum r from (").append(") u where rownum < ")
                .append(offset + page.getPageSize());
        sqlBuffer.insert(0, "select * from (").append(") where r >= ").append(offset);
        return sqlBuffer.toString();
    }

    /**
     * 给当前的参数对象page设置总记录数
     * @param page            Mapper映射语句对应的参数对象
     * @param mappedStatement Mapper映射语句
     * @param connection      当前的数据库连接
     */
    private void setTotalCount(Page<?> page, MappedStatement mappedStatement, Connection connection) {
        BoundSql boundSql = mappedStatement.getBoundSql(page);
        String sql = boundSql.getSql();
        // 获取总记录数
        String countSql = this.getCountSql(sql);
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        //构建查询总记录数SQL
        BoundSql countBoundSql = new BoundSql(mappedStatement.getConfiguration(), countSql, parameterMappings, page);
        ParameterHandler parameterHandler = new DefaultParameterHandler(mappedStatement, page, countBoundSql);
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = connection.prepareStatement(countSql);
            parameterHandler.setParameters(pstmt);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                int totalCount = rs.getInt(1);
                page.setTotalCount(totalCount);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(rs);
            IOUtils.closeQuietly(pstmt);
        }
    }
    
    /**
     * 根据原Sql语句获取对应的查询总记录数的Sql语句
     */
    private String getCountSql(String sql) {
        return "select count(1) " + sql.substring(sql.toLowerCase().indexOf("from"));
    }

}
```

### 2.2 Page类

```java
public class Page<T> implements Pageable<T> {
    public static final int DEFAULT_PAGE_SIZE = 10; // 默认每页记录数
    public static final int PAGE_COUNT = 10;
    private int pageNo = 1; // 页码
    private int pageSize = DEFAULT_PAGE_SIZE; // 每页记录数
    private int totalCount = 0; // 总记录数
    private int totalPage = 0; // 总页数
    //...
}
```

### 2.3 Pageable接口

```java
public interface Pageable<T> {
    /** 总记录数 */
    int getTotalCount();
    /** 总页数 */
    int getTotalPage();
    /** 每页记录数 */
    int getPageSize();
    /** 当前页号 */
    int getPageNo();
    /** 是否第一页 */
    boolean isFirstPage();
    /** 是否最后一页 */
    boolean isLastPage();
    /** 返回下页的页号 */
    int getNextPage();
    /** 返回上页的页号 */
    int getPrePage();
    /** 取得当前页显示的项的起始序号 */
    int getBeginIndex();
    /** 取得当前页显示的末项序号 */
    int getEndIndex();
    /** 获取开始页*/
    int getBeginPage();
    /** 获取结束页*/
    int getEndPage();
}
```

### 2.4 注册插件

在Mybatis主配置文件中注册插件

```xml
<plugins>
    <plugin interceptor="com.hero.plugin.pager.PageInterceptor">
    <!-- 支持MySQL和Oracle -->
    <property name="databaseType" value="mysql"/>
    </plugin>
</plugins>
```

### 2.5 使用分页插件

定义Mapper接口，使用Page的子类作为参数，使用分页查询数据

```Java
public interface UserMapper {

    @Select("select * from t_user")
    List<User> getUserPageable(com.damon.pager.utils.CustomPager pager);

    @Insert("insert into t_user (id,name,age,address) values (#{id},#{name},#{age},#{address});")    void save(User user);

    @Delete("delete from t_user")
    void deleteAll();
}
```

### 2.6 测试代码

```Java
public class DamonPagerExample {

    private UserMapper userMapper;

    private SqlSession sqlSession;

    @Before
    public void init() throws IOException {
        // 获取配置文件输入流
        InputStream inputStream = Resources.getResourceAsStream("mybatis-config.xml");
        // 通过SqlSessionFactoryBuilder的build()方法创建SqlSessionFactory实例
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        // 调用openSession()方法创建SqlSession实例
        sqlSession = sqlSessionFactory.openSession();
        // 获取UserMapper代理对象
        userMapper = sqlSession.getMapper(UserMapper.class);
        //初始化一些数据
        initUsers(userMapper);
    }

    private void initUsers(UserMapper userMapper) {
        userMapper.deleteAll();

        for (int i = 0; i < 20; i++) {
            User user = User.builder().id(Long.valueOf(i)).name("刘备【" + i + "】").age(11).address("蜀国").build();
            userMapper.save(user);
        }
        sqlSession.commit();
    }

    @Test
    public void testPageInterceptor() {
        com.damon.pager.utils.CustomPager pager = new com.damon.pager.utils.CustomPager();
        pager.setPageSize(10);
        pager.setFull(false);
        List<User> users = userMapper.getUserPageable(pager);
        System.out.println("总数据量：" + pager.getTotalCount() + ",总页数：" + pager.getTotalPage());
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(0);
            System.out.println("user" + i + ": " + user);
        }
        sqlSession.close();
    }
}
```

测试结果

![image-20230326223532971](https://damon-study.oss-cn-shenzhen.aliyuncs.com/%20typora/%E5%B9%B6%E5%8F%91%E7%BC%96%E7%A8%8Bimage-20230326223532971.png)
