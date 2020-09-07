/**
 * Copyright 2009-2018 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.ibatis.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 继承 BaseExecutor 抽象类，可重用的 Executor 实现类。
 * <p>
 * 1.每次开始读或写操作，优先从缓存中获取对应的 Statement 对象。如果不存在，才进行创建。
 * <p>
 * 2.执行完成后，不关闭该 Statement 对象。
 * <p>
 * 3.其它的，和 SimpleExecutor 是一致的。
 *
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

    /**
     * Statement 的缓存
     * <p>
     * KEY ：SQL
     */
    private final Map<String, Statement> statementMap = new HashMap<>();

    public ReuseExecutor(Configuration configuration, Transaction transaction) {
        super(configuration, transaction);
    }

    @Override
    public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
        Configuration configuration = ms.getConfiguration();
        // 创建 StatementHandler 对象
        StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null,
                null);
        // 初始化 StatementHandler 对象
        Statement stmt = prepareStatement(handler, ms.getStatementLog());
        // 执行 StatementHandler  ，进行写操作
        return handler.update(stmt);
    }

    @Override
    public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler
            , BoundSql boundSql) throws SQLException {
        Configuration configuration = ms.getConfiguration();
        // 创建 StatementHandler 对象
        StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler
                , boundSql);
        // 区别：初始化 StatementHandler 对象
        Statement stmt = prepareStatement(handler, ms.getStatementLog());

        // 执行 StatementHandler  ，进行读操作
        return handler.<E>query(stmt, resultHandler);
    }

    @Override
    protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds,
                                          BoundSql boundSql) throws SQLException {
        Configuration configuration = ms.getConfiguration();
        // 创建 StatementHandler 对象
        StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
        // 初始化 StatementHandler 对象
        Statement stmt = prepareStatement(handler, ms.getStatementLog());
        // 执行 StatementHandler  ，进行读操作
        return handler.<E>queryCursor(stmt);
    }

    /**
     * ReuseExecutor 考虑到重用性，但是 Statement 最终还是需要有地方关闭。
     * <p>
     * 答案就在 #doFlushStatements(boolean isRollback) 方法中。而 BaseExecutor 在关闭 #close() 方法中，最终也会调用该方法，从而完成关闭缓存的 Statement
     * 对象们。
     * <p>
     * 另外，BaseExecutor 在提交或者回滚事务方法中，最终也会调用该方法，也能完成关闭缓存的 Statement 对象们
     */
    @Override
    public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
        // 关闭缓存的 Statement 对象们
        for (Statement stmt : statementMap.values()) {
            closeStatement(stmt);
        }
        // 返回空集合
        statementMap.clear();
        return Collections.emptyList();
    }

    private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
        Statement stmt;
        BoundSql boundSql = handler.getBoundSql();
        String sql = boundSql.getSql();
        // 存在
        if (hasStatementFor(sql)) {
            // <1.1> 从缓存中获得 Statement 或 PrepareStatement 对象
            stmt = getStatement(sql);

            // <1.2> 设置事务超时时间
            applyTransactionTimeout(stmt);
        } else {// 不存在
            // <2.1> 获得 Connection 对象
            Connection connection = getConnection(statementLog);

            // <2.2> 创建 Statement 或 PrepareStatement 对象
            stmt = handler.prepare(connection, transaction.getTimeout());

            // <2.3> 添加到缓存中
            putStatement(sql, stmt);
        }
        // <2> 设置 SQL 上的参数，例如 PrepareStatement 对象上的占位符
        handler.parameterize(stmt);
        return stmt;
    }

    /**
     * 判断是否存在对应的 Statement 对象
     */
    private boolean hasStatementFor(String sql) {
        try {
            // 在缓存中，且要求连接未关闭。
            return statementMap.keySet().contains(sql) && !statementMap.get(sql).getConnection().isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private Statement getStatement(String s) {
        return statementMap.get(s);
    }

    private void putStatement(String sql, Statement stmt) {
        statementMap.put(sql, stmt);
    }

}
