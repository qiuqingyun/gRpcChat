package org.gRpcChat;

import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.hybrid.HybridConfig;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.*;
import java.util.HashMap;

//包括一张表：UserList(id, name, pk, skHashSalted, salt, regDate)
public class ChatDatabase {
    private final static Logger logger = LoggerFactory.getLogger("Server");
    private final String databaseRoot = "./db/";
    private final String database;
    private Connection connection = null;
    private final String databaseFilePath;
    private final long originId = 1597534;

    public ChatDatabase(String database) {
        this.database = database;
        File dir = new File(databaseRoot);
        if (!dir.exists())
            dir.mkdirs();
        databaseFilePath = databaseRoot + database;
    }

    //打开数据库
    public boolean openDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFilePath);
        } catch (ClassNotFoundException | SQLException e) {
            logger.error("Database " + database + " Open Error: " + e);
            return false;
        }
        logger.info("Database " + database + " Open Succeed");
        return true;
    }

    //关闭数据库
    public boolean closeDB() {
        try {
            connection.close();
        } catch (SQLException | NullPointerException e) {
            logger.error("Database " + database + " Close Error: " + e);
            return false;
        }
        logger.info("Database " + database + " Close Succeed");
        return true;
    }

    //新建表
    public boolean createTable() {
        try {
            try (Statement stmt = connection.createStatement()) {
                String sql1 = "create table UserList(" +
                        "id integer not null constraint UserList_pk primary key autoincrement," +
                        "name text not null," +
                        "pk blob not null," +
                        "skHashSalted text not null," +
                        "salt text not null," +
                        "regDate date not null" +
                        ");";
                String sql2 = "create unique index UserList_id_uindex on UserList (id)";
                stmt.executeUpdate(sql1);
                stmt.executeUpdate(sql2);
            }
        } catch (SQLException | NullPointerException e) {
            logger.error("Create Table \"UserList\" Error: " + e);
            return false;
        }
        logger.info("Create Table \"UserList\" Succeed");
        //初始化
        try {
            //生成随机密钥
            ByteString pkInit = null;
            String skHashSalted = null;
            String salt = GRpcUtil.getSalt();
            try {
                HybridConfig.register();
                KeysetHandle skInitH = KeysetHandle.generateNew(KeyTemplates.get("ECIES_P256_COMPRESSED_HKDF_HMAC_SHA256_AES128_GCM"));//私钥
                KeysetHandle pkInitH = skInitH.getPublicKeysetHandle();//公钥
                pkInit = GRpcUtil.getKeyByteString(pkInitH);
                skHashSalted = GRpcUtil.addSalt(GRpcUtil.keyHash(skInitH), salt);
            } catch (GeneralSecurityException | IOException e) {
                logger.error("KeyGen Error: " + e.getMessage());
                System.err.println("KeyGen Error: " + e.getMessage());
                System.exit(1);
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO UserList(id, name, pk, skHashSalted, salt,regDate) VALUES (?,?,?,?,?,?);")) {
                ps.setObject(1, originId);
                ps.setObject(2, "#Everyone");
                ps.setObject(3, pkInit.toByteArray());
                ps.setObject(4, skHashSalted);
                ps.setObject(5, salt);
                ps.setObject(6, GRpcUtil.getTimeStamp());
                ps.executeUpdate();
            }
        } catch (SQLException | NullPointerException e) {
            logger.error("Insert Error: " + e);
            return false;
        }
        logger.info("Init Table \"UserList\" Succeed");
        return true;
    }

    //注册，并插入新的用户信息，返回一个不重复的id，插入失败则返回-1
    public long reg(String name, ByteString pk, String skHash) {
        long id;
        try {
            String salt = GRpcUtil.getSalt();
            String skHashSalted = GRpcUtil.addSalt(skHash, salt);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO UserList(name, pk, skHashSalted, salt,regDate) VALUES (?, ?, ?, ?, ?);",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setObject(1, name);
                ps.setObject(2, pk.toByteArray());
                ps.setObject(3, skHashSalted);
                ps.setObject(4, salt);
                ps.setObject(5, GRpcUtil.getTimeStamp());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    id = rs.getLong(1); // 注意：索引从1开始
                    logger.info("New User " + name + "'s Id: " + id);
                }
            }
        } catch (SQLException | NullPointerException e) {
            logger.error("Insert Error: " + e);
            return -1;
        }
        logger.info("Insert Succeed");
        return id;
    }

    //登录
    public String login(long id, String skHash) {
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, name, skHashSalted, salt FROM UserList WHERE id=?;")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {//用户存在
                        String skHashSalted = rs.getString("skHashSalted");
                        String salt = rs.getString("salt");
                        if (GRpcUtil.addSalt(skHash, salt).equals(skHashSalted)) {
                            logger.info("User " + id + " Login Succeed");
                            return rs.getString("name");
                        }
                    }
                }
            }
        } catch (SQLException | NullPointerException e) {
            logger.error("Select ID " + id + " Error: " + e);
            return null;
        }
        logger.warn("User " + id + " Login Failed");
        return null;
    }

    //获取所有用户信息
    public boolean selectAll(HashMap<Long, UserInfo> register) {
        register.clear();
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM UserList;")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UserInfo userInfo = new UserInfo();
                        userInfo.setId(rs.getLong("id"));
                        userInfo.setName(rs.getString("name"));
                        userInfo.setPk(ByteString.copyFrom(rs.getBytes("pk")));
                        userInfo.setStream(null);
                        register.put(userInfo.getId(), userInfo);
                    }
                }
            }
        } catch (SQLException | NullPointerException e) {
            logger.error("Select All Error: " + e);
            return false;
        }
        logger.info("Select All Succeed");
        return true;
    }

    //清空表
    public boolean clearTable(String tableName) {
        try {
            try (Statement stmt = connection.createStatement()) {
                String sql = "DELETE from " + tableName;
                stmt.executeUpdate(sql);
            }
        } catch (SQLException | NullPointerException e) {
            logger.error("Clear Table " + tableName + " Failed: " + e);
            return false;
        }
        logger.info("Clear Table " + tableName + " Succeed");
        return true;
    }

    public long getOriginId() {
        return originId;
    }
}
